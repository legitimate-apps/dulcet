package com.legitimateapps.dulcet.core

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** What a person sees of one download. `Stale` still plays; the server's copy changed since. */
public enum class AndroidDownloadState { Queued, Downloading, Interrupted, Downloaded, Stale }

/**
 * One track's download as the shells draw it. [bytesWritten] and [totalBytes] describe the transfer
 * under way ([totalBytes] null when the server named no exact length). [needsAttention] is a download
 * the policy will not retry by itself — the server refused the account — so the person is told
 * rather than shown a download that never moves.
 */
public data class AndroidDownloadStatus(
    val rawId: String,
    val state: AndroidDownloadState,
    val bytesWritten: Long = 0,
    val totalBytes: Long? = null,
    val retryNotBeforeWallClock: Long? = null,
    val needsAttention: Boolean = false,
) {
    val playsOffline: Boolean get() = state == AndroidDownloadState.Downloaded || state == AndroidDownloadState.Stale
}

/** A track to download: its opaque id and the container the server said it is stored in. */
public data class AndroidDownloadItem(
    val rawId: String,
    val sourceContainer: AudioContainer,
    val durationMilliseconds: Long?,
)

/**
 * The platform executor's task registry (spec §14.5): WorkManager in the app, whose tag for each task
 * is the row's `downloadId`, so a relaunched process maps a task back to its row. The core decides
 * what runs and when; this only starts, cancels and enumerates tasks.
 */
public interface AndroidDownloadTasks {
    /** The downloadIds of every task the platform still holds unfinished, from any earlier process. */
    public suspend fun outstanding(): List<String>

    /** Starts the task for [downloadId]; the task calls [AndroidDownloadController.runTask]. */
    public fun start(downloadId: String)

    public fun cancel(downloadId: String)

    /** Asks for a scheduling pass ([AndroidDownloadController.scheduleNext]) no earlier than [wallClockMilliseconds]. */
    public fun wakeAt(wallClockMilliseconds: Long)
}

/** How one executor task ended. The row, not this value, is the durable record. */
public enum class AndroidDownloadRunOutcome {
    /** Validated and atomically promoted, or found already promoted. */
    Downloaded,

    /** Refused or failed; the policy recorded its retry boundary. */
    WillRetry,

    /** Not a download this task may run: removed, finished, or never started. */
    NotRunnable,
}

/**
 * The Android executor over the core's download policy (spec §14.5), for one account.
 *
 * The core owns every decision — identity, order, disk budget, retry and backoff, validation and
 * atomic promotion — through [DownloadPolicyEngine] and the `download` table. This class owns only
 * what is Android's: the HTTP transfer into the row's temporary file, the WorkManager tasks (through
 * [AndroidDownloadTasks]) and the process's view of progress. Its guarantees:
 *
 * - **Relaunch reconciliation runs first.** Nothing touches the subsystem until the platform's
 *   outstanding tasks have been matched to rows ([awaitReconciled]); tasks with no row are cancelled.
 * - **A file is written only after its row, and only under the name the row gives it**
 *   (`<downloadId>.partial` in the temporary directory, the row's `file_relative_path` once
 *   promoted), which is what the launch sweep of spec §14.7 requires.
 * - **Nothing is promoted unvalidated.** The whole temporary file passes the §12.4 validator —
 *   envelope detection first, then the container signature — and an exact server length must match
 *   before the atomic rename. A server error envelope delivered with HTTP 200 is rejected there.
 * - **One transfer at a time**, so a download never competes with playback for more than one
 *   connection, and a transcoded download is never scheduled (every download is the original file).
 *
 * Every public call is safe from any thread; database work is confined to one thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class AndroidDownloadController internal constructor(
    private val account: PlaybackEndpointAccount,
    openStore: () -> DulcetDatabaseStore,
    private val downloadRoot: File,
    private val tasks: AndroidDownloadTasks,
    private val wall: () -> Long,
    /** Bytes the downloads may occupy in all, completed ones included (spec §14.6). */
    private val diskBudgetBytes: (usedBytes: Long) -> Long,
    /** Opens the transfer for a resolved plan; production signs and sends it over HTTP. */
    private val openTransfer: ((RemotePlaybackWirePlan) -> AndroidPlaybackResource)?,
    /** Told the raw ids whose downloaded state changed, so library screens republish their badges. */
    private val onDownloadedChanged: (Set<String>) -> Unit,
) : AutoCloseable {
    public constructor(context: Context, account: PlaybackEndpointAccount, tasks: AndroidDownloadTasks) : this(
        account = account,
        openStore = { DulcetDriverFactory(context.applicationContext).openDulcetDatabase() },
        downloadRoot = AndroidAccountData.downloadRootFor(context.applicationContext),
        tasks = tasks,
        wall = System::currentTimeMillis,
        diskBudgetBytes = { used -> productionDiskBudget(AndroidAccountData.downloadRootFor(context.applicationContext), used) },
        openTransfer = null,
        onDownloadedChanged = { rawIds -> AndroidLibraryReader.notifyDownloadsChanged(rawIds) },
    )

    public val providerInstanceId: String get() = account.providerInstanceId

    /** True once [close] has run; a closed controller runs and schedules nothing. */
    public val isClosed: Boolean get() = closed

    private val database = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store: DulcetDatabaseStore = openStore()
    private val engine = DownloadPolicyEngine(
        store.database,
        DownloadFileStore(downloadRoot.path, okio.FileSystem.SYSTEM),
    )
    @Volatile private var wire: PlaybackWireClient? = PlaybackWireClient(account)
    @Volatile private var requests: AuthenticatedEndpointClient? = AuthenticatedEndpointClient(
        AuthenticatedEndpointCredentials(account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp),
        "download.android",
    )
    private val reconciled = CompletableDeferred<Boolean>()
    private val progress = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long?>>()
    private val mutableStatuses = MutableStateFlow<Map<String, AndroidDownloadStatus>>(emptyMap())
    @Volatile private var closed = false

    /** Every download of the account, by raw id, with the transfer under way's progress. */
    public val statuses: StateFlow<Map<String, AndroidDownloadStatus>> = mutableStatuses

    init {
        scope.launch {
            val outcome = try {
                val outstanding = tasks.outstanding()
                val result = withContext(database) {
                    engine.reconcile(
                        outstanding.filter(String::isNotBlank).distinct().map { OutstandingDownloadTask(DownloadId(it)) },
                        // Account ids are never reused and an account's credentials never change
                        // under its id on Android, so its generation is constant.
                        mapOf(account.providerInstanceId to CREDENTIAL_GENERATION),
                    )
                }
                result.taskIdsToCancel.forEach { tasks.cancel(it.value) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            reconciled.complete(outcome)
            if (outcome) {
                publish()
                scheduleNext()
            }
        }
    }

    /** True once relaunch reconciliation has run; false when the subsystem could not be opened. */
    public suspend fun awaitReconciled(): Boolean = reconciled.await()

    /**
     * Downloads [items], each the original file (spec §14.5 identity `(server, raw id, original)`).
     * A track already downloaded or under way is left as it is. Each row is written, and its track's
     * metadata pinned against seen-cache eviction (§16.13), before any file exists.
     */
    public suspend fun download(items: List<AndroidDownloadItem>) {
        if (items.isEmpty() || !awaitReconciled()) return
        withContext(database) {
            for (item in items) {
                val identity = identity(item.rawId)
                val existing = engine.record(identity)
                if (existing != null && existing.state != DownloadState.Interrupted) continue
                store.database.transaction {
                    engine.enqueue(DownloadRequest(
                        identity = identity,
                        expectedContainer = item.sourceContainer,
                        declaredContentLength = null,
                        serverSnapshot = DownloadServerSnapshot(item.durationMilliseconds?.takeIf { it >= 0 }, null),
                        credentialGeneration = CREDENTIAL_GENERATION,
                        wallClockMilliseconds = wall(),
                    ))
                    store.database.seenCacheQueries.insertPin(account.providerInstanceId, "track", item.rawId, "download")
                }
                // An interrupted row asked for again is retried now, not at its backoff boundary.
                if (existing?.state == DownloadState.Interrupted) {
                    store.database.downloadsQueries.resetDownloadRetry(wall(), identity.serverId, identity.rawId, identity.transcodeProfile)
                    store.database.downloadsQueries.markDownloadQueued(existing.downloadId.value)
                }
            }
        }
        publish()
        scheduleNext()
    }

    /**
     * Removes the downloads of [rawIds] at the person's request: the platform task is cancelled, the
     * files deleted, then the rows (spec §14.6), and the tracks' download pins released.
     */
    public suspend fun remove(rawIds: Collection<String>) {
        if (rawIds.isEmpty() || !awaitReconciled()) return
        val removed = withContext(database) {
            rawIds.mapNotNull { rawId ->
                engine.remove(identity(rawId))?.also {
                    store.database.seenCacheQueries.deletePin(account.providerInstanceId, "track", rawId, "download")
                }
            }
        }
        removed.forEach { row ->
            tasks.cancel(row.downloadId.value)
            progress.remove(row.identity.rawId)
        }
        publish()
        if (removed.isNotEmpty()) onDownloadedChanged(removed.mapTo(mutableSetOf()) { it.identity.rawId })
        scheduleNext()
    }

    /**
     * Starts the next queued download if none is running and the disk budget and retry boundaries
     * allow (spec §14.5, §14.6); otherwise asks to be woken at the earliest retry boundary.
     */
    public suspend fun scheduleNext() {
        if (closed || !awaitReconciled()) return
        val decision = withContext(database) {
            val rows = engine.records(account.providerInstanceId)
            if (rows.any { it.state == DownloadState.Downloading }) return@withContext null
            val used = rows.filter { it.state == DownloadState.Complete || it.state == DownloadState.Stale }
                .sumOf(DownloadRecord::fileSizeBytes)
            val now = wall()
            when (val scheduled = engine.schedule(DownloadSchedulingContext(
                serverId = account.providerInstanceId,
                wallClockMilliseconds = now,
                diskBudgetBytes = diskBudgetBytes(used).coerceAtLeast(0),
                unknownLengthReservationBytes = UNKNOWN_LENGTH_RESERVATION_BYTES,
                activeTranscodes = ActiveTranscodeCounts(),
            ))) {
                is DownloadScheduleResult.Start -> Schedule.Start(scheduled.platformTaskIdentifier)
                else -> engine.nextRetryNotBefore(account.providerInstanceId)?.let(Schedule::Wake)
            }
        }
        when (decision) {
            is Schedule.Start -> { tasks.start(decision.downloadId); publish() }
            is Schedule.Wake -> tasks.wakeAt(decision.wallClockMilliseconds)
            null -> Unit
        }
    }

    /**
     * The executor's body for one task: transfers the row's file into its temporary path, then asks
     * the core to validate and promote it. A cancellation (the task was stopped or the download
     * removed) leaves the row as it is — WorkManager runs a stopped task again, and a removed one has
     * no row. Every other failure is recorded with the core, which sets the retry boundary.
     */
    public suspend fun runTask(downloadId: String): AndroidDownloadRunOutcome {
        if (closed || !awaitReconciled()) return AndroidDownloadRunOutcome.NotRunnable
        val id = DownloadId(downloadId)
        val row = withContext(database) { engine.record(id) }
            ?.takeIf { it.state == DownloadState.Downloading && it.identity.serverId == account.providerInstanceId }
            ?: return AndroidDownloadRunOutcome.NotRunnable
        val temporary = File(withContext(database) { engine.temporaryFilePath(id) })
        val outcome = try {
            val metadata = transfer(row, temporary)
            withContext(database) {
                when (val promoted = engine.promote(id, metadata)) {
                    is DownloadPromotionResult.Promoted, is DownloadPromotionResult.AlreadyPromoted -> AndroidDownloadRunOutcome.Downloaded
                    is DownloadPromotionResult.Rejected -> {
                        // A rejected body is never kept: it is not the file, and a retry starts over.
                        temporary.delete()
                        engine.recordFailure(id, promoted.error, wall())
                        AndroidDownloadRunOutcome.WillRetry
                    }
                    DownloadPromotionResult.MissingTemporaryFile -> {
                        engine.recordFailure(id, DomainError.Transport.Unreachable, wall())
                        AndroidDownloadRunOutcome.WillRetry
                    }
                    // Removed while it transferred: the bytes belong to no row.
                    DownloadPromotionResult.UnknownDownload -> { temporary.delete(); AndroidDownloadRunOutcome.NotRunnable }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = (failure as? AndroidDownloadTransferFailure)?.error
                ?: (failure as? AndroidPlaybackIOException)?.error
                ?: DomainError.Transport.Unreachable
            withContext(database) {
                temporary.delete()
                if (engine.record(id) != null) {
                    engine.recordFailure(id, error, wall())
                    AndroidDownloadRunOutcome.WillRetry
                } else {
                    AndroidDownloadRunOutcome.NotRunnable
                }
            }
        } finally {
            progress.remove(row.identity.rawId)
        }
        publish()
        if (outcome == AndroidDownloadRunOutcome.Downloaded) onDownloadedChanged(setOf(row.identity.rawId))
        return outcome
    }

    /**
     * The local plan for [rawId]'s complete download, or null when it has none or its file is gone.
     * The file itself is validated as the player reads it ([AndroidLocalFilePlaybackResource]), so
     * this reads nothing but the row and the file's presence.
     */
    public suspend fun localPlan(rawId: String): LocalPlaybackPlan? {
        if (!awaitReconciled()) return null
        return withContext(database) {
            (engine.offlinePlaybackPlan(identity(rawId)) as? OfflinePlaybackPlanResult.Available)?.plan
        }
    }

    /** The raw ids whose downloads are complete and present, for the library's playability (§16.14). */
    public fun downloadedRawIds(): Set<String> =
        statuses.value.values.filter(AndroidDownloadStatus::playsOffline).mapTo(mutableSetOf(), AndroidDownloadStatus::rawId)

    /** Closes every network client, keeping the durable policy and local files usable. */
    public fun closeNetworkAccess() {
        requests?.close(); requests = null
        wire?.close(); wire = null
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        closeNetworkAccess()
        // The store is released on the database thread, after anything already queued there.
        CoroutineScope(database).launch { store.close() }
    }

    private suspend fun transfer(row: DownloadRecord, temporary: File): DownloadResponseMetadata {
        val wireClient = wire ?: throw AndroidDownloadTransferFailure(DomainError.Transport.Cancelled)
        val plan = when (val resolved = wireClient.resolve(PlaybackResolveRequest(
            PlaybackSessionId("download-session:${UUID.randomUUID()}"),
            AttemptId("download-attempt:${UUID.randomUUID()}"),
            ProviderItemId(account.providerInstanceId, row.identity.rawId),
            row.expectedContainer,
            supportsTranscodingExtension = false,
            deviceProfile = DOWNLOAD_PROFILE,
            legacyPreference = LegacyPlaybackPreference(format = null, maxBitRateKbps = null),
        ))) {
            is PlaybackResolutionResult.Failed -> throw AndroidDownloadTransferFailure(resolved.error)
            is PlaybackResolutionResult.Resolved -> resolved.plan
        }
        val resource = openTransfer?.invoke(plan)
            ?: AndroidHttpPlaybackResource(account, plan, requests ?: throw AndroidDownloadTransferFailure(DomainError.Transport.Cancelled))
        return withContext(Dispatchers.IO) {
            val response = resource.open(0, -1)
            try {
                if (response.status !in 200..299) throw AndroidDownloadTransferFailure(when (response.status) {
                    401 -> DomainError.Auth.InvalidCredentials
                    403 -> DomainError.Auth.Forbidden
                    429 -> DomainError.Server.Busy(parseRetryAfterSeconds(response.headers.retryAfter))
                    else -> DomainError.Server.Unknown(response.status)
                })
                val exact = (response.headers.contentLength as? PlaybackContentLength.Exact)?.byteCount
                progress[row.identity.rawId] = 0L to exact
                publish()
                temporary.parentFile?.mkdirs()
                var written = 0L
                var reported = 0L
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = response.input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        if (written - reported >= PROGRESS_STEP_BYTES) {
                            reported = written
                            progress[row.identity.rawId] = written to exact
                            publish()
                        }
                    }
                    // The bytes reach the disk before the core may rename them into place.
                    output.flush()
                    output.fd.sync()
                }
                DownloadResponseMetadata(response.headers.contentType, response.headers.contentLength)
            } finally {
                try { response.close() } catch (_: Exception) { }
            }
        }
    }

    private suspend fun publish() {
        if (closed) return
        val rows = try {
            withContext(database) { engine.records(account.providerInstanceId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        mutableStatuses.value = rows.associate { row ->
            val transfer = progress[row.identity.rawId]
            row.identity.rawId to AndroidDownloadStatus(
                rawId = row.identity.rawId,
                state = when (row.state) {
                    DownloadState.Queued -> AndroidDownloadState.Queued
                    DownloadState.Downloading -> AndroidDownloadState.Downloading
                    DownloadState.Interrupted -> AndroidDownloadState.Interrupted
                    DownloadState.Complete -> AndroidDownloadState.Downloaded
                    DownloadState.Stale -> AndroidDownloadState.Stale
                },
                bytesWritten = if (row.state == DownloadState.Downloading) transfer?.first ?: 0 else row.fileSizeBytes,
                totalBytes = if (row.state == DownloadState.Downloading) transfer?.second else row.exactByteLength,
                retryNotBeforeWallClock = row.retryNotBeforeWallClock,
                needsAttention = row.retryNotBeforeWallClock == Long.MAX_VALUE,
            )
        }
    }

    private fun identity(rawId: String) =
        DownloadIdentity(account.providerInstanceId, rawId, DownloadIdentity.ORIGINAL_PROFILE)

    private sealed interface Schedule {
        data class Start(val downloadId: String) : Schedule
        data class Wake(val wallClockMilliseconds: Long) : Schedule
    }

    internal companion object {
        const val CREDENTIAL_GENERATION = 1L
        const val UNKNOWN_LENGTH_RESERVATION_BYTES = 512L * 1024 * 1024
        const val DISK_BUDGET_CEILING_BYTES = 10L * 1024 * 1024 * 1024
        /** Free space always left to the rest of the device when downloads are budgeted. */
        const val DISK_FREE_FLOOR_BYTES = 256L * 1024 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024

        /** Ten GiB, or what the device can spare beyond a floor, whichever is smaller (spec §14.6). */
        fun productionDiskBudget(root: File, usedBytes: Long): Long {
            root.mkdirs()
            val available = try { StatFs(root.path).availableBytes } catch (_: IllegalArgumentException) { 0L }
            return minOf(DISK_BUDGET_CEILING_BYTES, usedBytes + available - DISK_FREE_FLOOR_BYTES)
        }

        private val DOWNLOAD_PROFILE = PlaybackDeviceProfile("Dulcet download", "Android", 1_411_200, 320_000,
            listOf(DirectPlayAudioProfile(AudioContainer.entries, listOf("mp3", "aac", "flac", "opus", "vorbis", "pcm"), maxAudioChannels = 8)),
            listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)))
    }
}

/** A transfer that failed for a reason the core has a word for. Carries no URL. */
internal class AndroidDownloadTransferFailure(val error: DomainError) : IOException("download transfer failed")
