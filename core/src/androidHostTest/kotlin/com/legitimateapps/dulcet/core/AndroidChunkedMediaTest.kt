package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.*

/** Real HTTP framing, including a FIN immediately after a whole chunk without its terminal zero. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidChunkedMediaTest {
    @Test fun coreRejectsAWholeChunkWithoutTheTerminator() = core(Framing.BoundaryCut)
    @Test fun coreRejectsAMidChunkCut() = core(Framing.MidChunkCut)
    @Test fun coreLoadsACompleteChunkedBody() = core(Framing.Complete)
    @Test fun coreDownloadRejectsAWholeChunkWithoutTheTerminator() = core(Framing.BoundaryCut, "download")
    @Test fun coreDownloadRejectsAMidChunkCut() = core(Framing.MidChunkCut, "download")
    @Test fun coreDownloadLoadsACompleteChunkedBody() = core(Framing.Complete, "download")
    @Test fun playerRejectsAWholeChunkWithoutTheTerminator() = player(Framing.BoundaryCut, false)
    @Test fun playerRejectsAMidChunkCut() = player(Framing.MidChunkCut, false)
    @Test fun playerLoadsACompleteChunkedBody() = player(Framing.Complete, false)
    @Test fun cacheNeverPromotesAWholeChunkWithoutTheTerminator() = player(Framing.BoundaryCut, true)
    @Test fun cacheNeverPromotesAMidChunkCut() = player(Framing.MidChunkCut, true)
    @Test fun cachePromotesACompleteChunkedBody() = player(Framing.Complete, true)
    @Test fun downloadNeverPromotesAWholeChunkWithoutTheTerminator() = download(Framing.BoundaryCut)
    @Test fun downloadNeverPromotesAMidChunkCut() = download(Framing.MidChunkCut)
    @Test fun downloadPromotesACompleteChunkedBody() = download(Framing.Complete)

    private fun core(framing: Framing, endpoint: String = "stream") = runBlocking {
        WireServer(framing).use { server ->
            val wire = PlaybackWireClient(account(server))
            try {
                val result = wire.load(plan(endpoint))
                if (framing == Framing.Complete) assertContentEquals(AUDIO, assertIs<PlaybackLoadResult.Audio>(result).bytes)
                else {
                    val error = assertIs<PlaybackLoadResult.Failed>(result).error
                    assertIs<DomainError.Transport>(error)
                    assertFalse(error.toString().contains("CANARY"))
                }
                assertEquals(listOf("/rest/$endpoint.view"), server.mediaPaths)
            } finally { wire.close() }
        }
    }

    private fun player(framing: Framing, caching: Boolean) = runBlocking {
        for (endpoint in listOf("stream", "download")) WireServer(framing).use { server ->
            val authorizer = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(server.url, USER, PASSWORD, true), "chunk-test")
            try {
                withDownloads(server) { downloads, root, _ ->
                    val plan = plan(endpoint)
                    val http = AndroidHttpPlaybackResource(account(server), plan, authorizer)
                    val cache = if (caching) AndroidCachingPlaybackResource(http, plan, object : AndroidLocalPlaybackSource {
                        override suspend fun localPlan(rawId: String) = downloads.localPlan(rawId)
                        override fun beginStreamCacheWrite(plan: RemotePlaybackWirePlan, status: Int, contentType: String?, exactLength: Long?, position: Long) =
                            downloads.beginStreamCacheWrite(plan, status, contentType, exactLength, position)
                    }) else null
                    val data = AndroidPlaybackDataSourceFactory(plan, cache ?: http).createDataSource()
                    try {
                        val consume = { data.open(DataSpec.Builder().setUri(Uri.parse("dulcet://resource")).build()); drain(data) }
                        if (framing == Framing.Complete) assertContentEquals(AUDIO, consume())
                        else {
                            val failure = assertFailsWith<AndroidPlaybackIOException> { consume() }
                            assertIs<DomainError.Transport>(failure.error)
                            assertNull(failure.cause)
                            assertFalse(failure.stackTraceToString().contains("CANARY"))
                        }
                    } finally { data.close(); cache?.close() }
                    assertEquals(listOf("/rest/$endpoint.view"), server.mediaPaths)
                    if (caching && framing == Framing.Complete)
                        assertContentEquals(AUDIO, File(assertNotNull(downloads.localPlan("song:opaque")).absolutePath).readBytes())
                    else {
                        assertNull(downloads.localPlan("song:opaque"))
                        assertFalse(root.walkTopDown().any { it.isFile }, "a failed stream removes its partial cache")
                    }
                }
            } finally { authorizer.close() }
        }
    }

    private fun download(framing: Framing) = runBlocking {
        WireServer(framing).use { server ->
            withDownloads(server) { downloads, root, tasks ->
                downloads.download(listOf(AndroidDownloadItem("song:opaque", AudioContainer.Wav, null)))
                val result = downloads.runTask(tasks.started.single())
                assertEquals(listOf("/rest/stream.view"), server.mediaPaths)
                if (framing == Framing.Complete) {
                    assertEquals(AndroidDownloadRunOutcome.Downloaded, result)
                    assertContentEquals(AUDIO, File(assertNotNull(downloads.localPlan("song:opaque")).absolutePath).readBytes())
                } else {
                    assertEquals(AndroidDownloadRunOutcome.WillRetry, result)
                    assertNull(downloads.localPlan("song:opaque"), "the truncated body is unavailable offline")
                    assertFalse(root.walkTopDown().any { it.isFile && !it.name.endsWith(".partial") }, "no final file is promoted")
                    assertEquals(AndroidDownloadState.Interrupted, downloads.statuses.value.getValue("song:opaque").state)
                }
            }
        }
    }

    private suspend fun withDownloads(server: WireServer, block: suspend (AndroidDownloadController, File, Tasks) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "chunk-${UUID.randomUUID()}.db"
        val root = Files.createTempDirectory("dulcet-chunk-media").toFile()
        val tasks = Tasks()
        val controller = AndroidDownloadController(account(server),
            { DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver()) },
            root, tasks, { 2_000_000L }, { Long.MAX_VALUE }, null, {})
        try { assertTrue(controller.awaitReconciled()); block(controller, root, tasks) }
        finally { controller.close(); context.deleteDatabase(databaseName); root.deleteRecursively() }
    }

    private class Tasks : AndroidDownloadTasks {
        val started = CopyOnWriteArrayList<String>()
        override suspend fun outstanding() = emptyList<String>()
        override fun start(downloadId: String) { started += downloadId }
        override fun cancel(downloadId: String) {}
        override fun wakeAt(wallClockMilliseconds: Long) {}
    }
    private fun account(server: WireServer) = PlaybackEndpointAccount("provider:opaque", server.url, USER, PASSWORD, true)
    private fun plan(endpoint: String): RemotePlaybackWirePlan = playbackPlan().let {
        RemotePlaybackWirePlan(it.playbackSessionId, it.attemptId, it.itemId, it.path, it.deliveryProtocol,
            it.expectedContainer, it.transcode, endpoint = endpoint, parameters = it.parameters, resolutionRequest = it.resolutionRequest)
    }
    private fun drain(data: DataSource): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = data.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return result.toByteArray()
            result.write(buffer, 0, count)
        }
    }
    private enum class Framing { BoundaryCut, MidChunkCut, Complete }
    private class WireServer(private val framing: Framing) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val mediaPaths = CopyOnWriteArrayList<String>()
        private val worker = thread(isDaemon = true, name = "chunk-media-fixture") {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                client.use {
                    it.soTimeout = 10_000
                    val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val target = (reader.readLine() ?: return@use).split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val path = target.substringBefore('?')
                    val output = it.getOutputStream()
                    if (path.startsWith("/rest/getSong")) {
                        val json = """{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"song:opaque","title":"Chunk fixture","duration":1,"contentType":"audio/wav","suffix":"wav"}}}""".toByteArray()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(json)
                    } else {
                        mediaPaths += path
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                        val chunkLength = AUDIO.size + if (framing == Framing.MidChunkCut) 10 else 0
                        output.write("${chunkLength.toString(16)}\r\n".toByteArray())
                        output.write(AUDIO)
                        if (framing != Framing.MidChunkCut) output.write("\r\n".toByteArray())
                        if (framing == Framing.Complete) output.write("0\r\n\r\n".toByteArray())
                    }
                    output.flush()
                }
            }
        }
        override fun close() {
            listener.close()
            worker.join(10_000)
            check(!worker.isAlive) { "chunk fixture did not stop" }
        }
    }
    private companion object {
        const val USER = "chunk-user-CANARY"
        const val PASSWORD = "chunk-password-CANARY"
        val AUDIO = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray() + ByteArray(100_000) { (it % 251).toByte() }
    }
}
