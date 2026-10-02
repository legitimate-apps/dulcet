package com.legitimateapps.dulcet

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidDownloadController
import com.legitimateapps.dulcet.core.AndroidDownloadItem
import com.legitimateapps.dulcet.core.AndroidDownloadState
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The production download executor (spec §14.5) over a test WorkManager: the saved account's
 * controller from the process registry, WorkManager tasks tagged with the account and the row's
 * download id, the worker that runs one, the relaunch mapping of WorkManager's outstanding tasks
 * back to rows, and the sign-out gateway cancelling the tasks and deleting the files (spec §14.7).
 * Only the server is a loopback socket and only the Keystore is a host stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"])
class DownloadExecutorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = AudioServer()
    private val root = File(context.noBackupFilesDir, "downloads")
    private lateinit var account: StoredAccount

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        account = AndroidAccountCredentialStore(context).save("Server", server.url, "user", "password", true)
        // The host has no file system statistics; the device reports ample free space.
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
    }

    @After fun tearDown() {
        runBlocking { AndroidDownloads.releaseForSignOut(context, account.id) }
        val readerClosed = java.util.concurrent.CountDownLatch(1)
        AndroidLibraryReader.closeCurrent { readerClosed.countDown() }
        val deadline = System.currentTimeMillis() + 60_000
        while (readerClosed.count > 0) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "The reader did not close" }
            Thread.sleep(10)
        }
        AndroidAccountCredentialStore(context).delete()
        server.close()
        root.deleteRecursively()
        shadowOf(Looper.getMainLooper()).idle()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aRequestedDownloadRunsAsAWorkManagerTaskAndIsPromoted(): Unit = runBlocking {
        val controller = assertNotNull(AndroidDownloads.controller(context))
        assertTrue(controller.awaitReconciled())
        controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, 1_000)))

        val task = awaitOneTask()
        assertEquals(WorkInfo.State.ENQUEUED, task.state, "the task waits for a network, as WorkManager holds it")
        assertEquals(0, server.requests.size, "nothing is transferred before WorkManager runs the task")
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(task.id)
        awaitDownloaded(controller)

        // The row is promoted inside the worker, which then schedules the next download before it
        // returns, so the task may still be RUNNING here (OBSERVED on a CI host). Wait for it to
        // finish, then require that it SUCCEEDED: a failed or cancelled task still fails this.
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(task.id))
        assertEquals(listOf("getSong", "stream"), server.requests.map {
            it.substringAfter("/rest/").substringBefore(".view")
        }, "metadata is persisted before the original file is transferred")
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size, "exactly one promoted file: $files")
        assertContentEquals(AUDIO, files.single().readBytes())
        assertNotNull(controller.localPlan(RAW_ID))
    }

    @Test fun aNeverBrowsedDownloadKeepsItsMetadataAfterRelaunchWithoutTheServer(): Unit = runBlocking {
        val first = assertNotNull(AndroidDownloads.controller(context))
        first.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, 1_000)))
        val task = awaitOneTask()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(task.id)
        awaitDownloaded(first)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(task.id))
        first.close()
        server.close()

        val again = relaunch(first)
        val metadata = assertNotNull(again.localMetadata(RAW_ID))
        assertEquals("Saved offline song", metadata.title)
        assertEquals("Saved artist", metadata.artist)
        assertEquals("Saved album", metadata.album)
        assertEquals(1_000L, metadata.durationMilliseconds)
        assertEquals("saved-art", metadata.artworkKey)
        assertEquals(account.id, metadata.providerInstanceId)
        assertNotNull(again.localPlan(RAW_ID))
        assertEquals(2, server.requests.size, "relaunch and local metadata read perform no HTTP request")
    }

    @Test fun aRelaunchKeepsATaskWorkManagerStillHoldsAndRestartsOneItLost(): Unit = runBlocking {
        val first = assertNotNull(AndroidDownloads.controller(context))
        assertTrue(first.awaitReconciled())
        first.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))
        val held = awaitOneTask()
        // The process dies: its controller goes with it, and the next one is a new reconciliation.
        first.close()

        // WorkManager still holds the task, so the row is left to it and nothing starts a second.
        val relaunched = relaunch(first)
        assertEquals(AndroidDownloadState.Downloading, relaunched.statuses.value.getValue(RAW_ID).state)
        assertEquals(listOf(held.id), downloadTasks().filter { !it.state.isFinished }.map { it.id })
        relaunched.close()

        // WorkManager lost the task: the row is interrupted and given a new task, which completes it.
        WorkManager.getInstance(context).cancelWorkById(held.id).result.get()
        val again = relaunch(relaunched)
        val restarted = downloadTasks().filter { !it.state.isFinished }
        assertEquals(1, restarted.size, "the interrupted row is given a new task: ${downloadTasks().map { it.state }}")
        assertTrue(restarted.single().id != held.id)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(restarted.single().id)
        awaitDownloaded(again)
    }

    @Test fun signingOutCancelsTheAccountsTasksAndDeletesItsDownloads(): Unit = runBlocking {
        val controller = assertNotNull(AndroidDownloads.controller(context))
        assertTrue(controller.awaitReconciled())
        controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))
        val first = awaitOneTask()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        awaitDownloaded(controller)
        controller.download(listOf(AndroidDownloadItem("song:second", AudioContainer.Wav, null)))
        val pending = awaitOneTask { !it.state.isFinished }
        assertEquals(1, root.walkTopDown().count { it.isFile })

        // The removal waits for the process's library reader, whose completions use the main looper.
        val removal = CoroutineScope(Dispatchers.IO).async { CoreAccountDataGateway(context).removeAccountData(account.id) }
        val deadline = System.currentTimeMillis() + 60_000
        while (!removal.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "The removal never finished" }
            Thread.sleep(10)
        }
        removal.await()

        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfoById(pending.id).get()!!.state)
        assertEquals(0, root.walkTopDown().count { it.isFile }, "the account's downloaded files are deleted")
    }

    private fun relaunch(previous: AndroidDownloadController): AndroidDownloadController {
        val controller = assertNotNull(AndroidDownloads.controller(context))
        assertTrue(controller !== previous, "a relaunch must reconcile afresh")
        assertTrue(runBlocking { controller.awaitReconciled() })
        // Reconciliation publishes and schedules after it completes; wait for its publication.
        val deadline = System.currentTimeMillis() + 10_000
        while (RAW_ID !in controller.statuses.value) {
            check(System.currentTimeMillis() < deadline) { "The relaunched controller never published" }
            Thread.sleep(20)
        }
        Thread.sleep(200)
        return controller
    }

    /**
     * The one download task matching [which], once it exists: the scheduling pass that starts a
     * task may be the controller's own launch pass, which runs beside the call that queued the row.
     */
    private fun awaitOneTask(which: (WorkInfo) -> Boolean = { true }): WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val matching = downloadTasks().filter(which)
            if (matching.isNotEmpty()) return matching.singleOrNull() ?: error("More than one download task: $matching")
            check(System.currentTimeMillis() < deadline) { "No download task was started" }
            Thread.sleep(20)
        }
    }

    private fun awaitFinished(id: java.util.UUID): WorkInfo.State {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            val state = WorkManager.getInstance(context).getWorkInfoById(id).get()!!.state
            if (state.isFinished) return state
            check(System.currentTimeMillis() < deadline) { "The download task never finished: $state" }
            Thread.sleep(20)
        }
    }

    private fun downloadTasks(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosByTag("dulcet.download.account:${account.id}").get()
            .filter { info -> info.tags.any { it.startsWith("dulcet.download.id:") } }

    private fun awaitDownloaded(controller: AndroidDownloadController) {
        val deadline = System.currentTimeMillis() + 15_000
        while (controller.statuses.value[RAW_ID]?.state != AndroidDownloadState.Downloaded) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "Not downloaded: ${controller.statuses.value}" }
            Thread.sleep(50)
        }
    }

    private class AudioServer : AutoCloseable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        private val executor = Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val line = reader.readLine() ?: return@use
                            generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            requests += line
                            val song = line.contains("/rest/getSong.view?")
                            val body = if (song) """{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"$RAW_ID","title":"Saved offline song","artist":"Saved artist","album":"Saved album","albumId":"saved-album","duration":1,"suffix":"wav","coverArt":"saved-art"}}}""".toByteArray() else AUDIO
                            val type = if (song) "application/json" else "audio/wav"
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            client.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
        override fun close() { socket.close(); executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    private companion object {
        const val RAW_ID = "song:opaque-download"
        val AUDIO: ByteArray = "RIFF".toByteArray() + byteArrayOf(0x24, 0x70, 0, 0) + "WAVEfmt ".toByteArray() +
            ByteArray(20_000) { (it % 251).toByte() }
    }
}
