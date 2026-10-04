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

    @Test
    fun aServerThatIgnoresTheFormatHintStillPlaysTheOriginalFileItSends() = runTest {
        // A reference server without a working transcoder answers a hinted stream with the
        // original file (trap 28). It played before a cap was chosen and must still play.
        val flac = "fLaC".encodeToByteArray() + ByteArray(60)
        val client = PlaybackWireClient(
            ACCOUNT,
            QualityRecordingTransport(gets = mapOf("stream" to audio(flac, "audio/flac"))),
        )
        val plan = assertIs<PlaybackResolutionResult.Resolved>(
            client.resolve(legacyRequest().withStreamingQuality(StreamingQuality.Kbps128)),
        ).plan
        assertEquals(AudioContainer.Mp3, plan.expectedContainer)

        val loaded = assertIs<PlaybackLoadResult.Audio>(client.load(plan))
        assertEquals(AudioContainer.Flac, loaded.validation.container, "the container that arrived is recorded")

        // Either container still gets its whole signature check: a body declared FLAC that is not
        // FLAC, and not MP3 either, is refused.
        val forged = PlaybackWireClient(
            ACCOUNT,
            QualityRecordingTransport(gets = mapOf("stream" to audio(ByteArray(64) { 0x41 }, "audio/flac"))),
        )
        assertIs<PlaybackLoadResult.Failed>(forged.load(plan))

        // A plan with no hint accepts only its own container, as before.
        val original = assertIs<PlaybackResolutionResult.Resolved>(client.resolve(legacyRequest())).plan
        assertEquals(listOf(AudioContainer.Flac), original.acceptedContainers())
        assertEquals(listOf(AudioContainer.Mp3, AudioContainer.Flac), plan.acceptedContainers())
    }

    @Test
    fun aSourceAlreadyWithinTheCapStreamsAsTheOriginalFile() = runTest {
        val mp3Source = legacyRequest().copy(sourceContainer = AudioContainer.Mp3)

        val within = QualityRecordingTransport(gets = mapOf("getSong" to song(bitRateKbps = 128)))
        val fits = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, within).resolve(mp3Source.withStreamingQuality(StreamingQuality.Kbps192)),
        ).plan
        assertEquals(listOf("getSong"), within.getEndpoints)
        assertEquals(linkedMapOf("id" to MEDIA_ID), fits.parameters, "no transcode is asked for")
        assertFalse(fits.isTranscoded(), "so it stays seekable and resumable")

        val above = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, QualityRecordingTransport(gets = mapOf("getSong" to song(bitRateKbps = 320))))
                .resolve(mp3Source.withStreamingQuality(StreamingQuality.Kbps192)),
        ).plan
        assertEquals("192", above.parameters["maxBitRate"])

        // An unreadable bitrate keeps the cap: when in doubt, the person's data is not spent.
        val unread = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, QualityRecordingTransport())
                .resolve(mp3Source.withStreamingQuality(StreamingQuality.Kbps192)),
        ).plan
        assertEquals("192", unread.parameters["maxBitRate"])

        // An adapter's own hint is a request for a transcode and is never second-guessed.
        val explicit = QualityRecordingTransport(gets = mapOf("getSong" to song(bitRateKbps = 64)))
        val hinted = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, explicit).resolve(
                mp3Source.copy(legacyPreference = LegacyPlaybackPreference(AudioContainer.Mp3, 128)),
            ),
        ).plan
        assertTrue(explicit.getEndpoints.isEmpty())
        assertEquals("128", hinted.parameters["maxBitRate"])
    }

    private fun song(bitRateKbps: Int) = response(
        """{"subsonic-response":{"status":"ok","song":{"id":"$MEDIA_ID","suffix":"mp3","bitRate":$bitRateKbps}}}"""
            .encodeToByteArray(),
        "application/json",
    )

    private fun audio(body: ByteArray, contentType: String) = response(body, contentType)

    private fun response(body: ByteArray, contentType: String) = AuthenticatedEndpointResponse(
        statusCode = 200,
        body = body,
        redactedUrl = "https://music.invalid:443/rest/quality.view?<redacted>",
        headers = AuthenticatedEndpointResponseHeaders(
            contentType = contentType,
            contentLength = PlaybackContentLength.Exact(body.size.toLong()),
            retryAfter = null,
            acceptRanges = null,
            contentRange = null,
        ),
        requestTrace = RequestTrace.observed(
            endpoint = "quality",
            method = "GET",
            redactedUrl = "https://music.invalid:443/rest/quality.view?<redacted>",
            authenticationLocation = AuthenticationLocation.Query,
            queryAuthenticationParameters = emptySet(),
            formAuthenticationParameters = emptySet(),
            channels = emptySet(),
            requestedProtocolVersion = "1.16.1",
            saltFingerprint = "fixture",
        ),
    )

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

    private class QualityRecordingTransport(
        val gets: Map<String, AuthenticatedEndpointResponse> = emptyMap(),
    ) : PlaybackEndpointTransport {
        val posts = mutableListOf<String>()
        val getEndpoints = mutableListOf<String>()

        override suspend fun get(
            endpoint: String,
            parameters: Map<String, String>,
            options: AuthenticatedEndpointRequestOptions,
        ): AuthenticatedEndpointResponse {
            getEndpoints += endpoint
            return gets[endpoint] ?: error("unexpected GET $endpoint")
        }

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
