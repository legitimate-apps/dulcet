package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Source
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * The Android download executor over the core policy (spec §14.5), driven through its real HTTP
 * transfer against a loopback socket server, its real SQLDelight store in a file database and its
 * real file layout. Only the WorkManager registry is a recording stand-in: the tests run each task
 * exactly as a worker does, by calling [AndroidDownloadController.runTask] with its tag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDownloadControllerTest {
    private val directory: File = Files.createTempDirectory("dulcet-android-downloads").toFile()
    private val root = File(directory, "downloads")
    private val context = RuntimeEnvironment.getApplication()
    private val databaseName = "downloads-${java.util.UUID.randomUUID()}.db"
    private val opened = mutableListOf<AutoCloseable>()

    @After fun tearDown() {
        opened.asReversed().forEach { runCatching { it.close() } }
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
    }

    @Test fun aRequestedDownloadIsTransferredValidatedAndAtomicallyPromotedUnderItsRowsName() = runBlocking {
        WireServer { WireReply(200, AUDIO) }.use { server ->
            val tasks = RecordingTasks()
            val changed = CopyOnWriteArrayList<Set<String>>()
            val controller = controller(server, tasks, onChanged = { changed += it })
            assertTrue(controller.awaitReconciled())

            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, 1_000)))
            val task = tasks.started.single()
            // The row exists, and names the task, before any byte: the launch sweep keeps its files.
            val row = rows().single()
            assertEquals(task, row.download_id)
            assertEquals("downloading", row.state)
            assertTrue(files().isEmpty(), "no file may exist before the executor transfers it")
            assertEquals(listOf("track" to RAW_ID), pins())

            assertEquals(AndroidDownloadRunOutcome.Downloaded, controller.runTask(task))

            val stored = files()
            assertEquals(listOf(File(root, row.file_relative_path)), stored, "exactly one promoted file, under the row's name")
            assertContentEquals(AUDIO, stored.single().readBytes())
            val status = controller.statuses.value.getValue(RAW_ID)
            assertEquals(AndroidDownloadState.Downloaded, status.state)
            assertEquals(AUDIO.size.toLong(), status.totalBytes)
            assertEquals(listOf(setOf(RAW_ID)), changed, "library screens are told the track now plays offline")
            assertNotNull(controller.localPlan(RAW_ID))
            val request = server.requests.single()
            assertEquals("/rest/stream.view", request.path)
            assertEquals(listOf(RAW_ID), request.query["id"])
            assertTrue(request.query.keys.containsAll(setOf("u", "t", "s")), "the transfer is the signed stream request")
            // With no format a server applies any transcoding configured for this player; `raw` is
            // the API's explicit "never transcode", so the stored file is the original.
            assertEquals(listOf("raw"), request.query["format"], "a download is the original file, never a transcode")
            assertFalse("maxBitRate" in request.query)
            assertNull(request.headers["range"], "a first transfer asks for the whole file")
            println("ANDROID DOWNLOAD HOST OBSERVED transfer=http promotion=atomic bytes=${AUDIO.size} temp-left=false")
        }
    }

    @Test fun anErrorEnvelopeDeliveredWithHttp200IsRejectedAndNothingIsPromoted() = runBlocking {
        val envelope = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Song not found"}}}"""
        WireServer { WireReply(200, envelope.toByteArray(), contentType = "audio/wav") }.use { server ->
            val tasks = RecordingTasks()
            val controller = controller(server, tasks)
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))

            assertEquals(AndroidDownloadRunOutcome.WillRetry, controller.runTask(tasks.started.single()))

            assertEquals(1, server.requests.size, "the envelope must have been served, or nothing was tested")
            assertTrue(files().isEmpty(), "neither the envelope nor a destination may remain")
            assertEquals(AndroidDownloadState.Interrupted, controller.statuses.value.getValue(RAW_ID).state)
            assertNull(controller.localPlan(RAW_ID))
            assertTrue(rows().single().state == "interrupted")
        }
    }

    @Test fun aBodyShorterThanItsExactLengthIsNeverPromoted() = runBlocking {
        WireServer { WireReply(200, AUDIO, declaredLength = AUDIO.size + 512) }.use { server ->
            val tasks = RecordingTasks()
            val controller = controller(server, tasks)
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))

            assertEquals(AndroidDownloadRunOutcome.WillRetry, controller.runTask(tasks.started.single()))

            assertEquals(1, server.requests.size)
            assertTrue(files().isEmpty())
            assertNull(controller.localPlan(RAW_ID))
        }
    }

    @Test fun aTransferStoppedMidBodyResumesFromItsPartialFileWithARangeRequest() = runBlocking {
        val stopped = CountDownLatch(1)
        WireServer { request -> rangeAware(request, stopped) }.use { server ->
            val tasks = RecordingTasks()
            val controller = controller(server, tasks)
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))
            val task = tasks.started.single()
            val partial = stopMidBody(controller, task, stopped)

            // Stopped, as WorkManager stops work at its time limit: the row still names the running
            // task, which WorkManager runs again, and the bytes so far are kept.
            assertEquals("downloading", rows().single().state)
            val kept = partial.length()
            assertTrue(kept in STOP_AT until AUDIO.size.toLong(), "a strict prefix was kept: $kept of ${AUDIO.size}")

            assertEquals(AndroidDownloadRunOutcome.Downloaded, controller.runTask(task))

            assertEquals(2, server.requests.size)
            val resumed = server.requests.last()
            assertEquals("bytes=$kept-", resumed.headers["range"], "the rerun asks only for the rest, never from zero")
            assertEquals(listOf("raw"), resumed.query["format"])
            val row = rows().single()
            assertEquals("complete", row.state)
            assertContentEquals(AUDIO, File(root, row.file_relative_path).readBytes(), "prefix and rest form the original")
            assertFalse(partial.exists())
        }
    }

    @Test fun aResumeAnsweredForADifferentFileRestartsFromZeroAndNeverStitches() = runBlocking {
        val stopped = CountDownLatch(1)
        val changed = AUDIO.copyOf().also { it[AUDIO.size - 1] = 7 } + ByteArray(333) { 5 }
        WireServer { request ->
            val range = request.headers["range"]
            when {
                range == null && stopped.count > 0 -> rangeAware(request, stopped)
                range == null -> WireReply(200, changed)
                // The server's file is no longer the one the prefix came from: its length moved.
                else -> {
                    val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    WireReply(206, changed.copyOfRange(start, changed.size),
                        headers = mapOf("Content-Range" to "bytes $start-${changed.size - 1}/${changed.size}"))
                }
            }
        }.use { server ->
            val tasks = RecordingTasks()
            val controller = controller(server, tasks)
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))
            val task = tasks.started.single()
            val kept = stopMidBody(controller, task, stopped).length()

            assertEquals(AndroidDownloadRunOutcome.Downloaded, controller.runTask(task))

            assertEquals(listOf(null, "bytes=$kept-", null), server.requests.map { it.headers["range"] },
                "the mismatched range is abandoned for a whole-file request")
            assertContentEquals(changed, File(root, rows().single().file_relative_path).readBytes(),
                "the stored file is wholly the new one, not the old prefix with the new tail")
        }
    }

    @Test fun anErrorThrownWhilePromotingIsRecordedAsAFailureWithARetryBoundary() = runBlocking {
        WireServer { WireReply(200, AUDIO) }.use { server ->
            val tasks = RecordingTasks()
            val faulting = object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun source(file: Path): Source {
                    if (file.name.endsWith(".partial")) throw OutOfMemoryError("fixture: promotion ran out of memory")
                    return super.source(file)
                }
            }
            val controller = controller(server, tasks, fileSystem = faulting)
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))

            assertEquals(AndroidDownloadRunOutcome.WillRetry, controller.runTask(tasks.started.single()))

            assertEquals(1, server.requests.size, "the transfer ran, so the fault struck at promotion")
            val row = rows().single()
            assertEquals("interrupted", row.state, "an Error may not leave the row downloading with no task")
            val boundary = assertNotNull(controller.statuses.value.getValue(RAW_ID).retryNotBeforeWallClock)
            assertTrue(boundary > NOW && boundary != Long.MAX_VALUE, "the policy's backoff applies")
            assertTrue(files().isEmpty(), "a body that faulted in validation is not kept")
            controller.scheduleNext()
            assertEquals(listOf(boundary), tasks.wakes, "the queue moves on at the boundary")
        }
    }

    @Test fun aClosedControllersRunningTransferStopsAndIsAwaited() = runBlocking {
        val stopped = CountDownLatch(1)
        WireServer { request -> rangeAware(request, stopped) }.use { server ->
            val tasks = RecordingTasks()
            val controller = controller(server, tasks)
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))
            val task = tasks.started.single()
            val partial = File(controller.temporaryPathForTest(task))
            val run = CoroutineScope(Dispatchers.IO).async { controller.runTask(task) }
            withTimeout(10_000) { while (partial.length() < STOP_AT) delay(5) }

            controller.close()
            val waiter = CoroutineScope(Dispatchers.IO).async { controller.awaitTasksStopped(10_000) }
            delay(200)
            assertFalse(waiter.isCompleted, "a transfer still inside its read is still running, and is waited for")
            stopped.countDown()

            assertTrue(waiter.await(), "the transfer stopped within the wait")
            assertTrue(run.isCompleted)
            assertFailsWith<CancellationException> { run.await() }
            val kept = partial.length()
            delay(400)
            assertEquals(kept, partial.length(), "nothing is written after the wait returns")
            assertTrue(kept < AUDIO.size, "the closed controller stopped before the whole body")
        }
    }

    /** The whole file, pausing after [STOP_AT] bytes until [stopped]; the rest for a range request. */
    private fun rangeAware(request: WireRequest, stopped: CountDownLatch): WireReply {
        val range = request.headers["range"] ?: return WireReply(200, AUDIO, pauseAfter = STOP_AT, pause = {
            check(stopped.await(10, TimeUnit.SECONDS)) { "the test never stopped the transfer" }
        })
        val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
        return WireReply(206, AUDIO.copyOfRange(start, AUDIO.size),
            headers = mapOf("Content-Range" to "bytes $start-${AUDIO.size - 1}/${AUDIO.size}"))
    }

    /**
     * Runs [task] until [STOP_AT] bytes are on disk, then cancels it as WorkManager stops work, and
     * returns the partial file. The server sends one more chunk after the cancellation so the
     * blocked read returns and the executor observes the stop between reads, as it does in use.
     */
    private suspend fun stopMidBody(controller: AndroidDownloadController, task: String, stopped: CountDownLatch): File {
        val partial = File(controller.temporaryPathForTest(task))
        val run = CoroutineScope(Dispatchers.IO).async { controller.runTask(task) }
        withTimeout(10_000) { while (partial.length() < STOP_AT) delay(5) }
        run.cancel()
        stopped.countDown()
        assertFailsWith<CancellationException> { run.await() }
        return partial
    }

    @Test fun serverRefusalsAreRecordedWithTheirRetryBoundary() = runBlocking {
        WireServer { WireReply(429, byteArrayOf(), retryAfter = "30") }.use { server ->
            var now = NOW
            val tasks = RecordingTasks()
            val controller = controller(server, tasks, wall = { now })
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))

            assertEquals(AndroidDownloadRunOutcome.WillRetry, controller.runTask(tasks.started.single()))
            controller.scheduleNext()

            val status = controller.statuses.value.getValue(RAW_ID)
            assertEquals(NOW + 30_000, status.retryNotBeforeWallClock, "Retry-After is honoured over the backoff")
            assertEquals(listOf(NOW + 30_000), tasks.wakes, "the executor is woken at the boundary, not polled")
            assertEquals(1, tasks.started.size, "nothing restarts before the boundary")
            now = NOW + 30_000
            controller.scheduleNext()
            assertEquals(2, tasks.started.size)
        }
    }

    @Test fun removingADownloadCancelsItsTaskAndDeletesItsFileRowAndPin() = runBlocking {
        WireServer { WireReply(200, AUDIO) }.use { server ->
            val tasks = RecordingTasks()
            val changed = CopyOnWriteArrayList<Set<String>>()
            val controller = controller(server, tasks, onChanged = { changed += it })
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))
            val task = tasks.started.single()
            controller.runTask(task)
            assertEquals(1, files().size)

            controller.remove(listOf(RAW_ID))

            assertTrue(files().isEmpty())
            assertTrue(rows().isEmpty())
            assertTrue(pins().isEmpty())
            assertEquals(listOf(task), tasks.cancelled)
            assertFalse(RAW_ID in controller.statuses.value)
            assertNull(controller.localPlan(RAW_ID))
            assertEquals(setOf(RAW_ID), changed.last())
        }
    }

    @Test fun relaunchKeepsARunningTaskInterruptsAnOrphanedRowAndCancelsATaskWithNoRow() = runBlocking {
        WireServer { WireReply(200, AUDIO) }.use { server ->
            val first = RecordingTasks()
            val before = controller(server, first)
            before.download(listOf(
                AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null),
            ))
            val running = first.started.single()
            // The process dies mid-transfer: a partial file is on disk and the row says downloading.
            File(root, ".tmp/$running.partial").apply { parentFile!!.mkdirs(); writeBytes(AUDIO.copyOf(10)) }
            before.close()

            // The platform still holds the task, and one more nobody owns.
            val kept = RecordingTasks(outstanding = listOf(running, "download:ghost"))
            val relaunched = controller(server, kept)
            assertTrue(relaunched.awaitReconciled())
            assertEquals(listOf("download:ghost"), kept.cancelled)
            assertEquals("downloading", rows().single().state, "a row whose task survives keeps running")
            assertTrue(kept.started.isEmpty(), "its surviving task runs it; nothing starts a second")
            assertTrue(File(root, ".tmp/$running.partial").exists(), "an outstanding task's partial file is kept")
            assertEquals(AndroidDownloadRunOutcome.Downloaded, relaunched.runTask(running))
            relaunched.close()
        }
        // Relaunched with the task gone: the row would be stuck downloading forever; it is
        // interrupted and started again.
        WireServer { WireReply(200, AUDIO) }.use { server ->
            val tasks = RecordingTasks()
            val first = controller(server, tasks)
            first.download(listOf(AndroidDownloadItem("song:second", AudioContainer.Wav, null)))
            val orphaned = tasks.started.single()
            first.close()

            val none = RecordingTasks(outstanding = emptyList())
            val relaunched = controller(server, none)
            assertTrue(relaunched.awaitReconciled())
            withTimeout(10_000) { while (none.started.isEmpty()) kotlinx.coroutines.delay(20) }
            assertEquals(listOf(orphaned), none.started, "the interrupted row is scheduled again at launch")
            assertEquals(AndroidDownloadRunOutcome.Downloaded, relaunched.runTask(orphaned))
        }
    }

    @Test fun aDownloadedFilePlaysThroughTheValidatingDataSourceAfterNetworkClientsClose() = runBlocking {
        WireServer { WireReply(200, AUDIO) }.use { server ->
            val tasks = RecordingTasks()
            val controller = controller(server, tasks)
            controller.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null)))
            controller.runTask(tasks.started.single())
            val file = files().single()

            controller.closeNetworkAccess()
            server.close()
            val plan = assertNotNull(controller.localPlan(RAW_ID))
            assertEquals(AUDIO.size.toLong(), plan.exactByteLength)
            assertContentEquals(file.readBytes(), readThroughDataSource(plan))
            // A seek reads a range of the same file, as the player does.
            assertContentEquals(AUDIO.copyOfRange(100, AUDIO.size), readThroughDataSource(plan, position = 100))

            // The same file changed since its promotion: the data source refuses it rather than play it.
            file.writeBytes("""{"subsonic-response":{"status":"failed"}}""".toByteArray().copyOf(AUDIO.size))
            assertFailsWith<AndroidPlaybackIOException> { readThroughDataSource(plan) }
            file.writeBytes(AUDIO.copyOf(AUDIO.size - 1))
            assertFailsWith<AndroidPlaybackIOException> { readThroughDataSource(plan) }
            // Grown past its promoted length, with a valid signature still at its head.
            file.writeBytes(AUDIO + ByteArray(64))
            assertFailsWith<AndroidPlaybackIOException> { readThroughDataSource(plan) }
            Unit
        }
    }

    private fun readThroughDataSource(plan: LocalPlaybackPlan, position: Long = 0): ByteArray {
        val factory = AndroidPlaybackDataSourceFactory(plan.container,
            AndroidLocalFilePlaybackResource(File(plan.absolutePath), plan.exactByteLength))
        if (position > 0) {
            // The factory validates the signature at position 0 first, as the player's first open does.
            factory.createDataSource().apply { open(DataSpec(Uri.parse("dulcet://resource"))); close() }
        }
        val source = factory.createDataSource()
        source.open(DataSpec.Builder().setUri(Uri.parse("dulcet://resource")).setPosition(position).build())
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(777)
        try {
            while (true) {
                val read = source.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                output.write(chunk, 0, read)
            }
        } finally {
            source.close()
        }
        return output.toByteArray()
    }

    private fun controller(
        server: WireServer,
        tasks: RecordingTasks,
        wall: () -> Long = { NOW },
        onChanged: (Set<String>) -> Unit = {},
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): AndroidDownloadController = AndroidDownloadController(
        account = PlaybackEndpointAccount(SERVER_ID, server.url, USER, PASSWORD, allowLocalHttp = true),
        openStore = { openStore() },
        downloadRoot = root,
        tasks = tasks,
        wall = wall,
        diskBudgetBytes = { 10L * 1024 * 1024 * 1024 },
        openTransfer = null,
        onDownloadedChanged = onChanged,
        fileSystem = fileSystem,
    ).also { opened += it }

    // JDBC's process-wide DriverManager would retain a sandbox-loaded driver that ordinary host
    // tests cannot see, so this Robolectric test opens the production Android driver instead.
    private fun openStore(): DulcetDatabaseStore =
        DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())

    private fun inspect(): DulcetDatabaseStore = openStore().also { opened += AutoCloseable { it.close() } }

    private fun rows() = inspect().database.downloadsQueries.selectDownloadsForServer(SERVER_ID).executeAsList()

    private fun pins() = inspect().database.seenCacheQueries.selectPins(SERVER_ID).executeAsList()
        .filter { it.reason == "download" }.map { it.item_kind to it.raw_id }

    private fun files(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    private class RecordingTasks(private val outstanding: List<String> = emptyList()) : AndroidDownloadTasks {
        val started = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val wakes = CopyOnWriteArrayList<Long>()
        override suspend fun outstanding(): List<String> = outstanding
        override fun start(downloadId: String) { started += downloadId }
        override fun cancel(downloadId: String) { cancelled += downloadId }
        override fun wakeAt(wallClockMilliseconds: Long) { wakes += wallClockMilliseconds }
    }

    private data class WireRequest(val target: String, val headers: Map<String, String> = emptyMap()) {
        private val uri get() = URI(target)
        val path: String get() = uri.path
        val query: Map<String, List<String>> get() = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val parts = it.split('=', limit = 2)
            java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })
    }

    private class WireReply(
        val code: Int,
        val bytes: ByteArray,
        val declaredLength: Int = bytes.size,
        val contentType: String = "audio/wav",
        val retryAfter: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** Bytes sent before [pause] runs; the next [STOP_CHUNK] follow at once, the rest later. */
        val pauseAfter: Int? = null,
        val pause: () -> Unit = {},
    )

    private class WireServer(private val respond: (WireRequest) -> WireReply) : AutoCloseable {
        private val socket = ServerSocket().apply { bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<WireRequest>()
        private val executor = Executors.newSingleThreadExecutor()
        private var closed = false
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try { socket.accept().use(::serve) } catch (_: Exception) { }
                }
            }
        }
        private fun serve(client: Socket) {
            client.soTimeout = 10_000
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val line = checkNotNull(reader.readLine())
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val request = WireRequest(line.split(' ')[1], headers)
            requests += request
            val reply = respond(request)
            val header = "HTTP/1.1 ${reply.code} Fixture\r\nContent-Type: ${reply.contentType}\r\n" +
                "Content-Length: ${reply.declaredLength}\r\nConnection: close\r\n" +
                (reply.retryAfter?.let { "Retry-After: $it\r\n" } ?: "") +
                reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
            val output = client.getOutputStream()
            output.write(header.toByteArray(Charsets.US_ASCII))
            val pauseAt = reply.pauseAfter
            if (pauseAt == null) {
                output.write(reply.bytes); output.flush()
                return
            }
            output.write(reply.bytes, 0, pauseAt); output.flush()
            reply.pause()
            output.write(reply.bytes, pauseAt, STOP_CHUNK); output.flush()
            // The stopped executor closes its end; the rest may or may not find a reader.
            Thread.sleep(300)
            try { output.write(reply.bytes, pauseAt + STOP_CHUNK, reply.bytes.size - pauseAt - STOP_CHUNK); output.flush() }
            catch (_: java.io.IOException) { }
        }
        override fun close() {
            if (closed) return
            closed = true
            socket.close(); executor.shutdown()
            check(executor.awaitTermination(15, TimeUnit.SECONDS)) { "Fixture server did not stop" }
        }
    }

    private companion object {
        const val SERVER_ID = "provider:opaque"
        const val RAW_ID = "song:opaque-01HX"
        const val USER = "download-user"
        const val PASSWORD = "download-password"
        const val NOW = 1_800_000_000_000L
        const val STOP_AT = 16_384
        const val STOP_CHUNK = 1_024
        val AUDIO: ByteArray = "RIFF".toByteArray() + byteArrayOf(0x24, 0x70, 0, 0) + "WAVEfmt ".toByteArray() +
            ByteArray(40_000) { (it % 251).toByte() }
    }
}
