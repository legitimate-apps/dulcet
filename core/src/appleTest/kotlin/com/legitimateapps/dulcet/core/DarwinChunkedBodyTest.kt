package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real HTTP framing, through the same Darwin client as core playback. */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class DarwinChunkedBodyTest {
    @Test
    fun aCompleteChunkedBodyLoadsWhole() = checkBody(End.Complete)

    @Test
    fun aChunkBoundaryCloseWithoutAZeroChunkFails() = checkBody(End.BoundaryCut)

    @Test
    fun aMidChunkCloseFails() = checkBody(End.MidChunkCut)

    @Test
    fun aDeclaredBodyCutShortFails() = checkBody(End.DeclaredCut)

    @Test
    fun aDownloadChunkBoundaryCloseWithoutAZeroChunkFails() = checkBody(End.BoundaryCut, "download")

    @Test
    fun aMidChunkDownloadCloseFails() = checkBody(End.MidChunkCut, "download")

    @Test
    fun aCompleteChunkedDownloadLoadsWhole() = checkBody(End.Complete, "download")

    private fun checkBody(end: End, endpoint: String = "stream") = runBlocking {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\n" +
            (if (end == End.DeclaredCut) "Content-Length: ${BODY.size + 16}" else "Transfer-Encoding: chunked") +
            "\r\nConnection: close\r\n\r\n"
        val chunkSize = BODY.size + if (end == End.MidChunkCut) 16 else 0
        val response = if (end == End.DeclaredCut) head.encodeToByteArray() + BODY else
            (head + "${chunkSize.toString(16)}\r\n").encodeToByteArray() + BODY +
                when (end) {
                    End.Complete -> "\r\n0\r\n\r\n"
                    End.BoundaryCut -> "\r\n"
                    else -> ""
                }.encodeToByteArray()
        val server = OneShotLoopbackServer(response)
        val dispatcher = newSingleThreadContext("chunked-body-fixture")
        val account = PlaybackEndpointAccount(
            providerInstanceId = "provider:chunked",
            normalizedBaseUrl = "http://127.0.0.1:${server.port}",
            username = "chunk-user-canary",
            password = "chunk-password-canary",
            allowLocalHttp = true,
        )
        val wire = PlaybackWireClient(account)
        try {
            val served = CoroutineScope(dispatcher).async { server.serveOnce() }
            val result = withTimeout(15_000) {
                val plan = assertIs<PlaybackResolutionResult.Resolved>(wire.resolve(request())).plan
                wire.load(RemotePlaybackWirePlan(
                    plan.playbackSessionId, plan.attemptId, plan.itemId, plan.path, plan.deliveryProtocol,
                    plan.expectedContainer, plan.transcode, endpoint = endpoint,
                    parameters = plan.parameters, resolutionRequest = plan.resolutionRequest,
                ))
            }
            assertTrue(withTimeout(15_000) { served.await() }.requestHead.startsWith("GET /rest/$endpoint.view?"))
            if (end == End.Complete) {
                assertContentEquals(BODY, assertIs<PlaybackLoadResult.Audio>(result).bytes)
            } else {
                assertIs<PlaybackLoadResult.Failed>(result, "a cut-off body loaded as the whole song: $end")
                assertFalse(result.toString().contains("chunk-password-canary"))
                assertFalse(result.toString().contains("chunk-user-canary"))
            }
        } finally {
            wire.close()
            server.closeListener()
            dispatcher.close()
        }
    }

    private fun request() = PlaybackResolveRequest(
        playbackSessionId = PlaybackSessionId("session:chunked"),
        attemptId = AttemptId("attempt:chunked"),
        itemId = ProviderItemId("provider:chunked", "song"),
        sourceContainer = AudioContainer.Flac,
        supportsTranscodingExtension = false,
        deviceProfile = PlaybackDeviceProfile(
            name = "Dulcet Test", platform = "Darwin", maxAudioBitrate = 96_000,
            maxTranscodingAudioBitrate = 96_000,
            directPlayProfiles = listOf(DirectPlayAudioProfile(listOf(AudioContainer.Flac), listOf("flac"), maxAudioChannels = 2)),
            transcodingProfiles = listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)),
        ),
        legacyPreference = LegacyPlaybackPreference(format = AudioContainer.Mp3, maxBitRateKbps = 96),
    )

    private enum class End { Complete, BoundaryCut, MidChunkCut, DeclaredCut }

    private companion object {
        val BODY = ByteArray(24_639) { (it % 251).toByte() }.also {
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x50, 0xC4.toByte()).copyInto(it)
        }
    }
}
