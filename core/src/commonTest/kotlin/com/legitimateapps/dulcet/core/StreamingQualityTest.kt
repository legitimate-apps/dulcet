package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Spec §12.5: one streaming-quality preference, two encodings, never two settings. */
class StreamingQualityTest {
    @Test
    fun aCapReachesTheLegacyStreamAsMaxBitRateWithTheProfilesTranscodingFormat() = runTest {
        val transport = QualityRecordingTransport()
        val plan = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, transport).resolve(legacyRequest().withStreamingQuality(StreamingQuality.Kbps192)),
        ).plan

        assertTrue(transport.posts.isEmpty(), "the legacy path sends nothing before the stream")
        assertEquals(PlaybackDeliveryPath.Legacy, plan.path)
        assertEquals(
            linkedMapOf(
                "id" to MEDIA_ID,
                "format" to "mp3",
                "maxBitRate" to "192",
                "estimateContentLength" to "true",
            ),
            plan.parameters,
        )
        // The validator and the engine expect what was asked for, not the FLAC source.
        assertEquals(AudioContainer.Mp3, plan.expectedContainer)
        assertEquals(PlaybackWireTranscodeDecision.LegacyHint(AudioContainer.Mp3, 192), plan.transcode)
        assertTrue(plan.isTranscoded())
    }

    @Test
    fun originalLeavesTheLegacyStreamAsItWas() = runTest {
        val request = legacyRequest()
        assertSame(request, request.withStreamingQuality(StreamingQuality.Original))

        val plan = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, QualityRecordingTransport()).resolve(request.withStreamingQuality(StreamingQuality.Original)),
        ).plan
        assertEquals(linkedMapOf("id" to MEDIA_ID), plan.parameters)
        assertEquals(AudioContainer.Flac, plan.expectedContainer)
        assertFalse(plan.isTranscoded())
    }

    @Test
    fun aCapReachesTheTranscodingExtensionAsTheClientInfoBitrateLimits() = runTest {
        val capped = postedClientInfo(StreamingQuality.Kbps128)
        assertEquals(128_000, capped.getValue("maxAudioBitrate").jsonPrimitive.int)
        assertEquals(128_000, capped.getValue("maxTranscodingAudioBitrate").jsonPrimitive.int)

        // The control: without a cap the profile's own limits are sent.
        val original = postedClientInfo(StreamingQuality.Original)
        assertEquals(1_536_000, original.getValue("maxAudioBitrate").jsonPrimitive.int)
        assertEquals(320_000, original.getValue("maxTranscodingAudioBitrate").jsonPrimitive.int)
    }

    @Test
    fun aCapNeverRaisesALimitThatIsAlreadyLower() {
        val request = legacyRequest().copy(
            deviceProfile = PROFILE.copy(maxTranscodingAudioBitrate = 96_000),
            legacyPreference = LegacyPlaybackPreference(AudioContainer.Ogg, 64),
        )

        val capped = request.withStreamingQuality(StreamingQuality.Kbps320)

        assertEquals(96_000, capped.deviceProfile.maxTranscodingAudioBitrate)
        assertEquals(320_000, capped.deviceProfile.maxAudioBitrate)
        // A format the adapter already chose is kept; only the bitrate is capped.
        assertEquals(LegacyPlaybackPreference(AudioContainer.Ogg, 64), capped.legacyPreference)
    }

    @Test
    fun anUnclassifiedNetworkUsesTheMeteredChoice() {
        val preference = StreamingQualityPreference(unmetered = StreamingQuality.Original, metered = StreamingQuality.Kbps192)

        assertEquals(StreamingQuality.Kbps192, preference.qualityFor(null))
        assertEquals(StreamingQuality.Kbps192, preference.qualityFor(NetworkCostClass.Metered))
        assertEquals(StreamingQuality.Original, preference.qualityFor(NetworkCostClass.Unmetered))
    }

    @Test
    fun thePolicySaysWhenTheQualityForTheNextResolveChanged() {
        val policy = StreamingQualityPolicy()
        assertNull(policy.network)
        assertEquals(StreamingQuality.Original, policy.currentQuality)

        assertTrue(policy.setPreference(StreamingQualityPreference(StreamingQuality.Original, StreamingQuality.Kbps128)))
        assertEquals(StreamingQuality.Kbps128, policy.currentQuality, "unclassified is metered")

        assertTrue(policy.setNetwork(NetworkCostClass.Unmetered))
        assertEquals(StreamingQuality.Original, policy.currentQuality)
        assertFalse(policy.setNetwork(NetworkCostClass.Unmetered), "the same network changes nothing")
        assertFalse(
            policy.setPreference(StreamingQualityPreference(StreamingQuality.Original, StreamingQuality.Kbps256)),
            "a metered-only change is not a change on an unmetered network",
        )
        assertTrue(policy.setNetwork(NetworkCostClass.Metered))
        assertEquals(StreamingQuality.Kbps256, policy.currentQuality)

        assertTrue(policy.restore("unmetered=original;metered=96"))
        assertEquals(StreamingQuality.Kbps96, policy.currentQuality)
        assertTrue(policy.restore(null), "an absent stored value restores Original")
        assertEquals(StreamingQualityPreference.Default, policy.preference)
    }

    @Test
    fun theStoredFormRoundTripsEveryChoiceAndAnUnreadableOneIsTheDefault() {
        for (unmetered in StreamingQuality.entries) for (metered in StreamingQuality.entries) {
            val preference = StreamingQualityPreference(unmetered, metered)
            assertEquals(preference, StreamingQualityPreference.decode(preference.encoded()))
        }
        assertEquals("unmetered=original;metered=192",
            StreamingQualityPreference(StreamingQuality.Original, StreamingQuality.Kbps192).encoded())
        assertEquals(StreamingQualityPreference.Default, StreamingQualityPreference.decode(null))
        assertEquals(StreamingQualityPreference.Default, StreamingQualityPreference.decode("garbage"))
        assertEquals(
            StreamingQualityPreference(StreamingQuality.Original, StreamingQuality.Kbps128),
            StreamingQualityPreference.decode("unmetered=999;metered=128;later=field"),
            "an unknown value falls back field by field",
        )
        assertEquals(StreamingQuality.Original, StreamingQualityPreference.Default.unmetered)
        assertEquals(StreamingQuality.Original, StreamingQualityPreference.Default.metered)
    }

    @Test
    fun aCappedPlanCanNeverBecomeADownload() = runTest {
        val plan = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, QualityRecordingTransport()).resolve(legacyRequest().withStreamingQuality(StreamingQuality.Kbps128)),
        ).plan
        assertFailsWith<IllegalArgumentException> { plan.asOriginalFileDownload() }
    }

    private suspend fun postedClientInfo(quality: StreamingQuality) = QualityRecordingTransport().let { transport ->
        PlaybackWireClient(ACCOUNT, transport).resolve(
            legacyRequest().copy(supportsTranscodingExtension = true).withStreamingQuality(quality),
        )
        Json.parseToJsonElement(transport.posts.single()).jsonObject
    }

    private fun legacyRequest() = PlaybackResolveRequest(
        playbackSessionId = PlaybackSessionId("session:quality"),
        attemptId = AttemptId("attempt:quality"),
        itemId = ProviderItemId(PROVIDER_ID, MEDIA_ID),
        sourceContainer = AudioContainer.Flac,
        supportsTranscodingExtension = false,
        deviceProfile = PROFILE,
        legacyPreference = LegacyPlaybackPreference(null, null),
    )

    private class QualityRecordingTransport : PlaybackEndpointTransport {
        val posts = mutableListOf<String>()

        override suspend fun get(
            endpoint: String,
            parameters: Map<String, String>,
            options: AuthenticatedEndpointRequestOptions,
        ): AuthenticatedEndpointResponse = error("unexpected GET $endpoint")

        override suspend fun postJson(
            endpoint: String,
            parameters: Map<String, String>,
            jsonBody: String,
        ): AuthenticatedEndpointResponse {
            posts += jsonBody
            // The decision itself is not under test here; an unreachable server ends the resolve.
            throw AuthenticatedEndpointFailure(DomainError.Transport.Unreachable)
        }

        override fun close() = Unit
    }

    private companion object {
        const val PROVIDER_ID = "provider:quality"
        const val MEDIA_ID = "song:opaque-quality"
        val ACCOUNT = PlaybackEndpointAccount(
            providerInstanceId = PROVIDER_ID,
            normalizedBaseUrl = "https://music.invalid",
            username = "quality-user",
            password = "quality-password",
            allowLocalHttp = false,
        )
        val PROFILE = PlaybackDeviceProfile(
            name = "Dulcet Quality Test",
            platform = "Test",
            maxAudioBitrate = 1_536_000,
            maxTranscodingAudioBitrate = 320_000,
            directPlayProfiles = listOf(
                DirectPlayAudioProfile(
                    containers = listOf(AudioContainer.Flac, AudioContainer.Mp3),
                    audioCodecs = listOf("flac", "mp3"),
                    maxAudioChannels = 2,
                ),
            ),
            transcodingProfiles = listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)),
        )
    }
}
