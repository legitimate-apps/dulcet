package com.legitimateapps.dulcet.search.conformance

import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AndroidDownloadController
import com.legitimateapps.dulcet.core.AndroidDownloadItem
import com.legitimateapps.dulcet.core.AndroidDownloadState
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A download stopped by a dropped connection, the process killed, and the app relaunched (spec §14.5).
 *
 * Through the production registry, WorkManager task and worker, against a loopback server: the first
 * transfer's connection is reset part-way, so the worker keeps `<downloadId>.partial` for a retry
 * and records the failure — and WorkManager records the run as finished, so a relaunch finds no task
 * outstanding for the row. The process then dies; a later launch's controller reconciles, keeps the
 * partial file, starts the row's retry, and its worker asks for only the rest with a `Range` request.
 * Only the server is a loopback socket and only the Keystore is a host stand-in
 * ([HostCredentialCipher], which the platform's test class must name in its `@Config`).
 *
 * Each platform's test class calls [run] from its own test method, so phone and TV prove the same
 * thing under their own test identities.
 */
class DownloadRelaunchResumeScenario {
    private val context = RuntimeEnvironment.getApplication()
    private val server = DroppingServer()
    private val root = File(context.noBackupFilesDir, "downloads")
    private lateinit var account: StoredAccount

    /** The platform's test calls it from `@Before`. */
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        account = AndroidAccountCredentialStore(context).save("Server", server.url, "user", "password", true)
        // The host has no file system statistics; the device reports ample free space.
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
    }

    /** The platform's test calls it from `@After`. */
    fun tearDown() {
        runBlocking { AndroidDownloads.releaseForSignOut(context, account.id) }
        AndroidAccountCredentialStore(context).delete()
        server.close()
        root.deleteRecursively()
        shadowOf(Looper.getMainLooper()).idle()
        context.deleteDatabase("dulcet.db")
    }

    fun run() {
        val first = assertNotNull(AndroidDownloads.controller(context))
        assertTrue(runBlocking { first.awaitReconciled() })
        runBlocking { first.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null))) }
        val dropped = awaitTask { !it.state.isFinished }
        server.partial = { partials().singleOrNull() }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(dropped.id)

        // The worker returns once the failure is recorded; WorkManager holds nothing for the row.
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(dropped.id), "the dropped run is a finished task, not a held one")
        assertEquals(AndroidDownloadState.Interrupted, awaitState(first) { it != AndroidDownloadState.Downloading })
        assertEquals(listOf<String?>(null), server.transfers, "one transfer, from the start")
        val partial = partials().single()
        assertEquals(STOP_AT.toLong(), partial.length(), "the connection failure kept the bytes so far")

        // The process dies. A later launch comes after the retry's backoff (5 s for a first failure).
        first.close()
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(1))
        val relaunched = assertNotNull(AndroidDownloads.controller(context))
        assertTrue(relaunched !== first, "a relaunch must reconcile afresh")
        assertTrue(runBlocking { relaunched.awaitReconciled() })
        assertTrue(partial.exists(), "relaunch reconciliation keeps the resumable partial file: ${partials()}")
        assertEquals(STOP_AT.toLong(), partial.length())

        val retry = awaitTask { !it.state.isFinished && it.id != dropped.id }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(retry.id)
        assertEquals(AndroidDownloadState.Downloaded, awaitState(relaunched) { it == AndroidDownloadState.Downloaded })

        assertEquals(listOf(null, "bytes=$STOP_AT-"), server.transfers,
            "the retry after the relaunch asks only for the rest, never from zero")
        assertTrue(partials().isEmpty(), "the partial file became the download")
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size, "exactly one promoted file: $files")
        assertContentEquals(AUDIO, files.single().readBytes(), "the kept prefix and the rest form the original")
        println("ANDROID DOWNLOAD RELAUNCH RESUME OBSERVED transfers=${server.transfers} kept=$STOP_AT total=${AUDIO.size}")
    }

    private fun partials(): List<File> = root.walkTopDown().filter { it.isFile && it.name.endsWith(".partial") }.toList()

    private fun awaitTask(which: (WorkInfo) -> Boolean): WorkInfo {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            val matching = downloadTasks().filter(which)
            if (matching.isNotEmpty()) return matching.singleOrNull() ?: error("More than one download task: $matching")
            check(System.nanoTime() < deadline) { "No download task was started: ${downloadTasks().map { it.state }}" }
            Thread.sleep(20)
        }
    }

    private fun awaitFinished(id: UUID): WorkInfo.State {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            val state = WorkManager.getInstance(context).getWorkInfoById(id).get()!!.state
            if (state.isFinished) return state
            check(System.nanoTime() < deadline) { "The download task never finished: $state" }
            Thread.sleep(20)
        }
    }

    private fun awaitState(controller: AndroidDownloadController, done: (AndroidDownloadState) -> Boolean): AndroidDownloadState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            controller.statuses.value[RAW_ID]?.state?.takeIf(done)?.let { return it }
            check(System.nanoTime() < deadline) { "The download never settled: ${controller.statuses.value}" }
            Thread.sleep(20)
        }
    }

    private fun downloadTasks(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosByTag("dulcet.download.account:${account.id}").get()
            .filter { info -> info.tags.any { it.startsWith("dulcet.download.id:") } }

    /**
     * `getSong` answers the track's metadata. `stream` answers the whole file to a request without a
     * `Range`, but resets the connection once [STOP_AT] bytes are on disk; a `Range` request gets the rest.
     */
    private class DroppingServer : AutoCloseable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val url = "http://127.0.0.1:${socket.localPort}"
        /** Each transfer's `Range` header, or null for one from the start. */
        val transfers = CopyOnWriteArrayList<String?>()
        @Volatile var partial: () -> File? = { null }
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var closed = false

        init {
            executor.submit {
                while (!socket.isClosed) {
                    try { socket.accept().use(::serve) } catch (_: Exception) { }
                }
            }
        }

        private fun serve(client: Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val target = (reader.readLine() ?: return).split(' ')[1]
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val output = client.getOutputStream()
            fun respond(status: String, type: String, body: ByteArray, extra: String = "", length: Int = body.size) {
                output.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\n$extra" +
                    "Connection: close\r\n\r\n").toByteArray())
                output.write(body); output.flush()
            }
            if (target.startsWith("/rest/getSong")) return respond("200 OK", "application/json", SONG)
            if (!target.startsWith("/rest/stream")) return respond("200 OK", "application/json", OK)
            val range = headers["range"]
            transfers += range
            if (range == null) {
                respond("200 OK", "audio/wav", AUDIO.copyOfRange(0, STOP_AT), length = AUDIO.size)
                // Held until the bytes are on disk, then reset (RST), as a dropped network ends a read.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ((partial()?.length() ?: 0L) < STOP_AT) {
                    check(System.nanoTime() < deadline) { "the worker never wrote the first bytes" }
                    Thread.sleep(5)
                }
                client.setSoLinger(true, 0)
                return
            }
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            respond("206 Partial Content", "audio/wav", AUDIO.copyOfRange(start, AUDIO.size),
                "Content-Range: bytes $start-${AUDIO.size - 1}/${AUDIO.size}\r\n")
        }

        override fun close() {
            if (closed) return
            closed = true
            socket.close(); executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val RAW_ID = "song:opaque-dropped-mid-transfer"
        const val STOP_AT = 16_384
        val AUDIO: ByteArray = "RIFF".toByteArray() + byteArrayOf(0x24, 0x70, 0, 0) + "WAVEfmt ".toByteArray() +
            ByteArray(40_000) { (it % 251).toByte() }
        val SONG: ByteArray = ("""{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"$RAW_ID",""" +
            """"title":"Dropped Mid Transfer","duration":2,"suffix":"wav","contentType":"audio/wav"}}}""").toByteArray()
        val OK: ByteArray = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
    }
}
