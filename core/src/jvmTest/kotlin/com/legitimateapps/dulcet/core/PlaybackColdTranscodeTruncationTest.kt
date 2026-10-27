package com.legitimateapps.dulcet.core

import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §12.5, §28 2026-10-04, through the core's own client (`load`: CONF-92 and the core
 * controls) and the real HTTP stack against a real socket. OBSERVED against Navidrome 0.63.2 with
 * an empty transcoding cache: asked for an estimate, "Dulcet Health Probe" at 96 kbps declared
 * 24,576 bytes for a 24,639-byte body, refused the write that would cross the declaration and
 * closed after 23,385 bytes. This server replays that answer to whatever request arrives, so it
 * shows what the client makes of it; without the flag Navidrome sends the whole body chunked, which
 * the control replays.
 */
class PlaybackColdTranscodeTruncationTest {
    @Test
    fun aCappedLoadAsksForNoEstimateAndRefusesABodyCutOffShortOfItsDeclaredLength() {
        ReplayServer(declaredLength = DECLARED_LENGTH, body = MP3_BODY.copyOf(CUT_OFF_LENGTH)).use { server ->
            val result = load(server)

            val target = server.requestTargets.single()
            assertTrue(target.startsWith("/rest/stream.view?"), "the fixture never served the stream")
            assertTrue("maxBitRate=96" in target, "setup: the request is not the capped transcode")
            assertIs<PlaybackLoadResult.Failed>(result, "a body cut off short of its length loaded as the song")
            assertFalse("estimateContentLength" in target, "the load asked for an estimated length")
        }
    }

    @Test
    fun aCompleteChunkedColdTranscodeLoadsWhole() {
        ReplayServer(declaredLength = null, body = MP3_BODY).use { server ->
            val audio = assertIs<PlaybackLoadResult.Audio>(load(server), "the complete chunked body failed")

            assertContentEquals(MP3_BODY, audio.bytes)
            assertNull(audio.validation.contentLength, "a chunked answer declares no length")
        }
    }

    @Test
    fun aChunkBoundaryCloseWithoutTheTerminatingChunkFails() {
        ReplayServer(null, MP3_BODY, ChunkEnd.BoundaryCut).use { server ->
            val failed = assertIs<PlaybackLoadResult.Failed>(load(server), "a missing zero chunk loaded as the song")
            assertFalse(failed.toString().contains("cold-password-canary"))
            assertFalse(failed.toString().contains("cold-user-canary"))
        }
    }

    @Test
    fun aMidChunkCloseFails() {
        ReplayServer(null, MP3_BODY, ChunkEnd.MidChunkCut).use { server ->
            assertIs<PlaybackLoadResult.Failed>(load(server), "a mid-chunk cut loaded as the song")
        }
    }

    @Test
    fun aDownloadChunkBoundaryCloseWithoutTheTerminatingChunkFails() {
        ReplayServer(null, MP3_BODY, ChunkEnd.BoundaryCut).use { server ->
            assertIs<PlaybackLoadResult.Failed>(load(server, "download"))
        }
    }

    @Test
    fun aMidChunkDownloadCloseFails() {
        ReplayServer(null, MP3_BODY, ChunkEnd.MidChunkCut).use { server ->
            assertIs<PlaybackLoadResult.Failed>(load(server, "download"))
        }
    }

    @Test
    fun aCompleteChunkedDownloadLoadsWhole() {
        ReplayServer(null, MP3_BODY).use { server ->
            assertContentEquals(MP3_BODY, assertIs<PlaybackLoadResult.Audio>(load(server, "download")).bytes)
        }
    }

    private enum class ChunkEnd { Complete, BoundaryCut, MidChunkCut }

    private fun load(server: ReplayServer, endpoint: String = "stream"): PlaybackLoadResult = runBlocking {
        val account = PlaybackEndpointAccount(
            providerInstanceId = PROVIDER_ID,
            normalizedBaseUrl = "http://127.0.0.1:${server.port}",
            username = "cold-user-canary",
            password = "cold-password-canary",
            allowLocalHttp = true,
        )
        val wire = PlaybackWireClient(account)
        try {
            val plan = assertIs<PlaybackResolutionResult.Resolved>(wire.resolve(cappedRequest())).plan
            val media = RemotePlaybackWirePlan(
                plan.playbackSessionId, plan.attemptId, plan.itemId, plan.path, plan.deliveryProtocol,
                plan.expectedContainer, plan.transcode, endpoint = endpoint,
                parameters = plan.parameters, resolutionRequest = plan.resolutionRequest,
            )
            wire.load(media)
        } finally {
            wire.close()
        }
    }

    private fun cappedRequest() = PlaybackResolveRequest(
        playbackSessionId = PlaybackSessionId("session:cold-truncation"),
        attemptId = AttemptId("attempt:cold-truncation"),
        itemId = ProviderItemId(PROVIDER_ID, "song"),
        sourceContainer = AudioContainer.Flac,
        supportsTranscodingExtension = false,
        deviceProfile = PlaybackDeviceProfile(
            name = "Dulcet Test",
            platform = "JVM",
            maxAudioBitrate = 96_000,
            maxTranscodingAudioBitrate = 96_000,
            directPlayProfiles = listOf(DirectPlayAudioProfile(listOf(AudioContainer.Flac), listOf("flac"), maxAudioChannels = 2)),
            transcodingProfiles = listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)),
        ),
        // An explicit hint, so the resolve asks for the transcode without reading the source first.
        legacyPreference = LegacyPlaybackPreference(format = AudioContainer.Mp3, maxBitRateKbps = 96),
    )

    /** Answers every connection with one 200: [body], declared as [declaredLength] or chunked when null, then closes. */
    private class ReplayServer(private val declaredLength: Int?, private val body: ByteArray, private val end: ChunkEnd = ChunkEnd.Complete) : Closeable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val port = socket.localPort
        val requestTargets = CopyOnWriteArrayList<String>()
        private val worker = thread(isDaemon = true, name = "cold-transcode-replay") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                client.use {
                    it.soTimeout = 10_000
                    val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val line = reader.readLine() ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    requestTargets += line.split(' ')[1]
                    val output = it.getOutputStream()
                    val framing = declaredLength?.let { length -> "Content-Length: $length" } ?: "Transfer-Encoding: chunked"
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\n$framing\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    )
                    if (declaredLength == null) {
                        val chunkSize = body.size + if (end == ChunkEnd.MidChunkCut) 16 else 0
                        output.write("${chunkSize.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(body)
                        if (end != ChunkEnd.MidChunkCut) output.write("\r\n".toByteArray(Charsets.US_ASCII))
                        if (end == ChunkEnd.Complete) output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    } else {
                        output.write(body)
                    }
                    output.flush()
                }
            }
        }

        override fun close() {
            socket.close()
            worker.join(10_000)
        }
    }

    private companion object {
        const val PROVIDER_ID = "provider:cold-truncation"
        const val DECLARED_LENGTH = 24_576
        const val CUT_OFF_LENGTH = 23_385
        const val COMPLETE_LENGTH = 24_639

        // Opens with an MPEG-1 Layer III frame header, so the validator accepts it as the MP3 asked for.
        val MP3_BODY = ByteArray(COMPLETE_LENGTH) { (it % 251).toByte() }.also { body ->
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x50, 0xC4.toByte()).copyInto(body)
        }
    }
}
