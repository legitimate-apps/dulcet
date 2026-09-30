package com.legitimateapps.dulcet.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.legitimateapps.dulcet.AccountCredentialStore
import com.legitimateapps.dulcet.AccountRemovalJournal
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.CredentialStoreException
import com.legitimateapps.dulcet.core.AndroidDownloadController
import com.legitimateapps.dulcet.core.AndroidDownloadTasks
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The process's download controller for the account saved now (spec §14.5). There is one per
 * process, because the core's relaunch reconciliation runs when it is created and must run once,
 * before any task is started or any file is written. It is created by whichever needs it first —
 * the launch sweep ([com.legitimateapps.dulcet.CoreAccountDataGateway.sweep]), a screen showing
 * downloads, the playback service or a WorkManager task run after the process died — and replaced
 * when the saved account changes or it was closed. An account being removed gets none.
 */
public object AndroidDownloads {
    private var current: AndroidDownloadController? = null

    /** The controller for the saved account, or null when none is saved or its record cannot be read. */
    @JvmStatic
    public fun controller(context: Context): AndroidDownloadController? =
        controller(context.applicationContext, AndroidAccountCredentialStore(context.applicationContext))

    @Synchronized
    internal fun controller(context: Context, credentials: AccountCredentialStore): AndroidDownloadController? {
        val activeId = try { credentials.activeAccountId() } catch (_: CredentialStoreException) { null }
        current?.let { existing ->
            if (existing.providerInstanceId == activeId && !existing.isClosed) return existing
            existing.close()
            current = null
        }
        if (activeId == null || activeId in AccountRemovalJournal(context).pending()) return null
        // WorkManager initialises itself at process start through its startup provider; without it
        // (a host test that never initialised it) no task could be started or found.
        if (!WorkManager.isInitialized()) return null
        val account = try { credentials.load() } catch (_: CredentialStoreException) { null } ?: return null
        return AndroidDownloadController(
            context,
            PlaybackEndpointAccount(account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp),
            WorkManagerDownloadTasks(context, account.id),
        ).also { current = it }
    }

    /**
     * Stops every download task of [serverId] and closes its controller (spec §14.7 step 4), before
     * its files and rows are deleted. Returns once WorkManager has recorded the cancellation.
     */
    public suspend fun releaseForSignOut(context: Context, serverId: String) {
        synchronized(this) {
            current?.takeIf { it.providerInstanceId == serverId }?.let { it.close(); current = null }
        }
        // WorkManager initialises itself at process start through its startup provider; only a host
        // test that never initialised it has no instance, and such a test enqueued nothing.
        if (!WorkManager.isInitialized()) return
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(accountTag(serverId)).result.get()
        }
    }

    /** The WorkManager task registry for [accountId]'s downloads (spec §14.5). */
    public fun tasksFor(context: Context, accountId: String): AndroidDownloadTasks =
        WorkManagerDownloadTasks(context.applicationContext, accountId)

    internal fun accountTag(serverId: String): String = "dulcet.download.account:$serverId"
    internal fun downloadTag(downloadId: String): String = "$DOWNLOAD_TAG_PREFIX$downloadId"
    internal const val DOWNLOAD_TAG_PREFIX: String = "dulcet.download.id:"
    internal const val KEY_DOWNLOAD_ID: String = "downloadId"
    internal const val KEY_ACCOUNT_ID: String = "accountId"
}

/**
 * The core's download tasks as WorkManager work (spec §14.5): one unique work per download id,
 * tagged with the account, run when a network is connected, and kept by WorkManager across process
 * death — a task the process was killed in is run again by WorkManager, and one it lost is found
 * missing by the core's relaunch reconciliation and restarted.
 */
internal class WorkManagerDownloadTasks(context: Context, private val accountId: String) : AndroidDownloadTasks {
    private val application = context.applicationContext
    private val work: WorkManager get() = WorkManager.getInstance(application)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override suspend fun outstanding(): List<String> = withContext(Dispatchers.IO) {
        work.getWorkInfosByTag(AndroidDownloads.accountTag(accountId)).get()
            .filter { !it.state.isFinished }
            .flatMap { info -> info.tags.filter { it.startsWith(AndroidDownloads.DOWNLOAD_TAG_PREFIX) } }
            .map { it.removePrefix(AndroidDownloads.DOWNLOAD_TAG_PREFIX) }
            .distinct()
    }

    override fun start(downloadId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(network)
            .setInputData(Data.Builder()
                .putString(AndroidDownloads.KEY_DOWNLOAD_ID, downloadId)
                .putString(AndroidDownloads.KEY_ACCOUNT_ID, accountId)
                .build())
            .addTag(AndroidDownloads.accountTag(accountId))
            .addTag(AndroidDownloads.downloadTag(downloadId))
            .build()
        // Appended, not kept: a download started again from its own task's end (a retry whose
        // boundary has passed) must run after that task rather than be dropped because it is
        // still running.
        work.enqueueUniqueWork(downloadId, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun cancel(downloadId: String) {
        work.cancelUniqueWork(downloadId)
    }

    override fun wakeAt(wallClockMilliseconds: Long) {
        val delay = (wallClockMilliseconds - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<DownloadWakeWorker>()
            .setConstraints(network)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(AndroidDownloads.KEY_ACCOUNT_ID, accountId).build())
            .addTag(AndroidDownloads.accountTag(accountId))
            .build()
        work.enqueueUniqueWork("dulcet.download.wake:$accountId", ExistingWorkPolicy.REPLACE, request)
    }
}

/** Runs one download task through the process's controller, then starts the next one. */
public class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(AndroidDownloads.KEY_DOWNLOAD_ID) ?: return Result.success()
        val controller = AndroidDownloads.controller(applicationContext)
            ?.takeIf { it.providerInstanceId == inputData.getString(AndroidDownloads.KEY_ACCOUNT_ID) }
            // Another account's task, or none saved: nothing of it may run with these credentials.
            ?: return Result.success()
        controller.runTask(downloadId)
        controller.scheduleNext()
        // A failed transfer is recorded with the core, which owns its retry boundary; WorkManager's
        // own retry would bypass it.
        return Result.success()
    }
}

/** Woken at a download's retry boundary, to start whatever the core now allows. */
public class DownloadWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AndroidDownloads.controller(applicationContext)
            ?.takeIf { it.providerInstanceId == inputData.getString(AndroidDownloads.KEY_ACCOUNT_ID) }
            ?.scheduleNext()
        return Result.success()
    }
}
