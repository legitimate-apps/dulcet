package com.legitimateapps.dulcet.core

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
import kotlin.test.assertTrue

/**
 * Spec §12.5: a server may ignore a legacy format hint and send the original file. The Apple
 * validator accepts it under its own full signature check and tells AVFoundation what arrived.
 */
class ApplePlaybackIgnoredHintTest {
    @Test
    fun aCappedPlanAnsweredWithTheOriginalFlacIsAcceptedAndNamedFlac() = runTest {
        val plan = AppleRemotePlaybackPlanDto(cappedFlacPlan())
        assertEquals("Mp3", plan.expectedContainer)
        val flac = "fLaC".encodeToByteArray() + ByteArray(60)

        val outcome = validate(plan, flac, "audio/flac")

        assertTrue(outcome.accepted, "the original the server sent plays")
        assertEquals("Flac", outcome.container)
    }

    @Test
    fun theHintedFormatIsStillAcceptedAndNamedAsItself() = runTest {
        val plan = AppleRemotePlaybackPlanDto(cappedFlacPlan())
        val mp3 = "ID3".encodeToByteArray() + ByteArray(61)

        val outcome = validate(plan, mp3, "audio/mpeg")

        assertTrue(outcome.accepted)
        assertEquals("Mp3", outcome.container)
    }

    @Test
    fun aBodyThatIsNeitherIsRefused() = runTest {
        val plan = AppleRemotePlaybackPlanDto(cappedFlacPlan())

        val outcome = validate(plan, ByteArray(64) { 0x41 }, "audio/flac")

        assertFalse(outcome.accepted)
        assertEquals(null, outcome.container)
    }

    private suspend fun cappedFlacPlan(): RemotePlaybackWirePlan {
        val request = PlaybackResolveRequest(
            playbackSessionId = PlaybackSessionId("session:apple-hint"),
            attemptId = AttemptId("attempt:apple-hint"),
            itemId = ProviderItemId(ACCOUNT.providerInstanceId, "song:apple-hint"),
            sourceContainer = AudioContainer.Flac,
            supportsTranscodingExtension = false,
            deviceProfile = PlaybackDeviceProfile(
                name = "Apple hint test",
                platform = "Test",
                maxAudioBitrate = 1_536_000,
                maxTranscodingAudioBitrate = 320_000,
                directPlayProfiles = listOf(
                    DirectPlayAudioProfile(listOf(AudioContainer.Flac, AudioContainer.Mp3), listOf("flac", "mp3"), maxAudioChannels = 2),
                ),
                transcodingProfiles = listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)),
            ),
            legacyPreference = LegacyPlaybackPreference(null, null),
        ).withStreamingQuality(StreamingQuality.Kbps128)
        return assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, NoSongTransport).resolve(request),
        ).plan
    }

    private fun validate(plan: AppleRemotePlaybackPlanDto, body: ByteArray, contentType: String) =
        ApplePlaybackWireClient(ACCOUNT).validateResponse(
            plan = plan,
            statusCode = 200,
            contentType = contentType,
            contentLength = body.size.toLong(),
            retryAfter = null,
            acceptRanges = "bytes",
            contentRange = null,
            body = body.toNSData(),
            requestedRangeStart = 0,
            requestedRangeEndInclusive = body.size.toLong() - 1,
            requiresAudioSignature = true,
        )

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
            providerInstanceId = "provider:apple-hint",
            normalizedBaseUrl = "https://music.invalid",
            username = "hint-user",
            password = "hint-password",
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
