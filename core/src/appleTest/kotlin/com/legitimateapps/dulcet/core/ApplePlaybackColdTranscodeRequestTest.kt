package com.legitimateapps.dulcet.core

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §12.5, §28 2026-10-04: the Apple resource loader never asks Navidrome for an estimated
 * length. OBSERVED against Navidrome 0.63.2 with an empty transcoding cache: "Dulcet Health Probe"
 * at 96 kbps declared 24,576 bytes for a 24,639-byte body, refused the write that would cross the
 * declaration and closed after 23,385 bytes (sometimes 501) -- the same shape a complete body under
 * an overshooting estimate has. Without the flag the cold answer was a complete chunked 200.
 */
class ApplePlaybackColdTranscodeRequestTest {
    @Test
    fun theLoadersRequestForACappedTranscodeDoesNotAskForAnEstimatedLength() = runTest {
        val plan = cappedPlan()
        // The core plan itself never asks (§28 2026-10-04), so no client's request does.
        assertNull(plan.parameters["estimateContentLength"], "the core plan asks for an estimate")

        val client = ApplePlaybackWireClient(ACCOUNT)
        val prepared = client.prepareResourceRequest(plan, PlaybackByteRange(0, 262_143))
        client.close()

        val query = Url(prepared.url).parameters
        assertNull(query["estimateContentLength"], "the loader's request asks for an estimated length")
        assertEquals("96", query["maxBitRate"], "the cap survives")
        assertEquals("mp3", query["format"], "the format hint survives")
        assertEquals("bytes=0-262143", prepared.rangeHeader)
    }

    @Test
    fun aCappedBodyShortOfItsDeclaredLengthIsRefusedAsTruncation() = runTest {
        // The loader hands a 200 that ended in a lost connection to the validator. Its request
        // carried no estimate, so the declared length is exact and the short body is truncation.
        val plan = AppleRemotePlaybackPlanDto(cappedPlan())
        val received = "ID3".encodeToByteArray() + ByteArray(23_385 - 3)

        val outcome = ApplePlaybackWireClient(ACCOUNT).validateResponse(
            plan = plan,
            statusCode = 200,
            contentType = "audio/mpeg",
            contentLength = 24_576,
            retryAfter = null,
            acceptRanges = "none",
            contentRange = null,
            body = received.toNSData(),
            requestedRangeStart = 0,
            requestedRangeEndInclusive = 262_143,
            requiresAudioSignature = true,
        )

        assertFalse(outcome.accepted, "a truncated body was accepted as the whole song")
        assertEquals(-1L, outcome.contentLength)
    }

    @Test
    fun aCompleteChunkedColdTranscodeIsTheWholeRepresentation() = runTest {
        // What a cold transcode answers once the request carries no estimate: 200, no
        // Content-Length, Range ignored, every byte.
        val plan = AppleRemotePlaybackPlanDto(cappedPlan())
        val body = "ID3".encodeToByteArray() + ByteArray(24_639 - 3)

        val outcome = ApplePlaybackWireClient(ACCOUNT).validateResponse(
            plan = plan,
            statusCode = 200,
            contentType = "audio/mpeg",
            contentLength = -1,
            retryAfter = null,
            acceptRanges = "none",
            contentRange = null,
            body = body.toNSData(),
            requestedRangeStart = 0,
            requestedRangeEndInclusive = 262_143,
            requiresAudioSignature = true,
        )

        assertTrue(outcome.accepted)
        assertEquals(24_639L, outcome.contentLength)
        assertFalse(outcome.supportsByteRanges)
    }

    private suspend fun cappedPlan(): RemotePlaybackWirePlan {
        val request = PlaybackResolveRequest(
            playbackSessionId = PlaybackSessionId("session:apple-cold"),
            attemptId = AttemptId("attempt:apple-cold"),
            itemId = ProviderItemId(ACCOUNT.providerInstanceId, "song:apple-cold"),
            sourceContainer = AudioContainer.Flac,
            supportsTranscodingExtension = false,
            deviceProfile = PlaybackDeviceProfile(
                name = "Apple cold transcode test",
                platform = "Test",
                maxAudioBitrate = 1_536_000,
                maxTranscodingAudioBitrate = 320_000,
                directPlayProfiles = listOf(
                    DirectPlayAudioProfile(listOf(AudioContainer.Flac, AudioContainer.Mp3), listOf("flac", "mp3"), maxAudioChannels = 2),
                ),
                transcodingProfiles = listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)),
            ),
            legacyPreference = LegacyPlaybackPreference(null, null),
        ).withStreamingQuality(StreamingQuality.Kbps96)
        return assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, NoSongTransport).resolve(request),
        ).plan
    }

    /** The bitrate read fails, so the cap is kept and the plan hints MP3. */
    private object NoSongTransport : PlaybackEndpointTransport {
        override suspend fun get(
            endpoint: String,
            parameters: Map<String, String>,
            options: AuthenticatedEndpointRequestOptions,
        ): AuthenticatedEndpointResponse = throw AuthenticatedEndpointFailure(DomainError.Transport.Unreachable)

        override suspend fun postJson(
            endpoint: String,
            parameters: Map<String, String>,
            jsonBody: String,
        ): AuthenticatedEndpointResponse = error("unexpected POST $endpoint")

        override fun close() = Unit
    }

    private companion object {
        val ACCOUNT = PlaybackEndpointAccount(
            providerInstanceId = "provider:apple-cold",
            normalizedBaseUrl = "https://music.invalid",
            username = "cold-user",
            password = "cold-password",
            allowLocalHttp = false,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    val data = NSMutableData()
    data.setLength(size.toULong())
    memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong())
    data
}
