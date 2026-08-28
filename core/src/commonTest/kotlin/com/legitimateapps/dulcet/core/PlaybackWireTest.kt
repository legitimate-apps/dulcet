package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlaybackWireTest {
    @Test
    fun extensionDecisionUsesMediaIdSongAndAFlatClientInfoBody() = runTest {
        val transport = RecordingPlaybackTransport(
            postResponses = ArrayDeque(listOf(decisionResponse(OPAQUE_PLAN))),
        )
        val client = PlaybackWireClient(ACCOUNT, transport)

        val plan = assertIs<PlaybackResolutionResult.Resolved>(
            client.resolve(extensionRequest()),
        ).plan

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("getTranscodeDecision", request.endpoint)
        assertEquals(
            mapOf("mediaId" to OPAQUE_MEDIA_ID, "mediaType" to "song"),
            request.parameters,
        )
        assertFalse("id" in request.parameters)

        val body = Json.parseToJsonElement(request.jsonBody!!).jsonObject
        assertEquals(
            setOf(
                "name",
                "platform",
                "maxAudioBitrate",
                "maxTranscodingAudioBitrate",
                "directPlayProfiles",
                "transcodingProfiles",
                "codecProfiles",
            ),
            body.keys,
        )
        assertFalse("clientInfo" in body)
        assertEquals("Dulcet Test", body.getValue("name").jsonPrimitive.content)
        assertEquals(256, body.getValue("maxAudioBitrate").jsonPrimitive.int)
        assertEquals(1, (body.getValue("directPlayProfiles") as JsonArray).size)
        assertEquals(1, (body.getValue("transcodingProfiles") as JsonArray).size)
        assertEquals(0, (body.getValue("codecProfiles") as JsonArray).size)

        assertEquals(PlaybackDeliveryPath.ExtensionTranscode, plan.path)
        assertEquals("getTranscodeStream", plan.endpoint)
        assertEquals(
            linkedMapOf(
                "transcodeParams" to OPAQUE_PLAN,
                "mediaId" to OPAQUE_MEDIA_ID,
                "mediaType" to "song",
            ),
            plan.parameters,
        )
        assertEquals(
            OPAQUE_PLAN,
            assertIs<PlaybackWireTranscodeDecision.Transcoded>(plan.transcode).opaqueParams,
        )
        assertEquals(SESSION_ID, plan.playbackSessionId)
        assertEquals(ATTEMPT_ID, plan.attemptId)
    }

    @Test
    fun opaquePlanAndCredentialCanariesNeverRenderFromRequestOrPlanTypes() = runTest {
        val transport = RecordingPlaybackTransport(
            postResponses = ArrayDeque(listOf(decisionResponse(OPAQUE_PLAN))),
        )
        val client = PlaybackWireClient(ACCOUNT, transport)
        val request = extensionRequest()
        val plan = assertIs<PlaybackResolutionResult.Resolved>(client.resolve(request)).plan

        val rendered = listOf(ACCOUNT, request, plan, plan.transcode).joinToString("\n")
        listOf(PASSWORD_CANARY, USERNAME_CANARY, OPAQUE_PLAN, OPAQUE_MEDIA_ID).forEach { canary ->
            assertFalse(rendered.contains(canary), canary)
        }
        assertEquals("RemotePlaybackWirePlan(<redacted>)", plan.toString())
    }

    @Test
    fun legacyTranscodeHintsCarryEstimateLengthAndOffsetWithoutParsingTheId() = runTest {
        val transport = RecordingPlaybackTransport()
        val client = PlaybackWireClient(ACCOUNT, transport)
        val request = extensionRequest().copy(
            supportsTranscodingExtension = false,
            legacyPreference = LegacyPlaybackPreference(
                format = AudioContainer.Mp3,
                maxBitRateKbps = 64,
            ),
            legacyTimeOffset = 37.seconds,
        )

        val plan = assertIs<PlaybackResolutionResult.Resolved>(client.resolve(request)).plan

        assertTrue(transport.requests.isEmpty())
        assertEquals(PlaybackDeliveryPath.Legacy, plan.path)
        assertEquals("stream", plan.endpoint)
        assertEquals(
            linkedMapOf(
                "id" to OPAQUE_MEDIA_ID,
                "format" to "mp3",
                "maxBitRate" to "64",
                "estimateContentLength" to "true",
                "timeOffset" to "37",
            ),
            plan.parameters,
        )
        assertEquals(OPAQUE_MEDIA_ID, plan.itemId.rawId)
    }

    @Test
    fun extensionDirectPlayUsesTheLegacyStreamWithoutTranscodeHints() = runTest {
        val directDecision = """
            {"subsonic-response":{"status":"ok","transcodeDecision":{
              "canDirectPlay":true,"canTranscode":false,
              "transcodeReason":[],"sourceStream":{"container":"mp3","protocol":"http"}
            }}}
        """.trimIndent()
        val transport = RecordingPlaybackTransport(
            postResponses = ArrayDeque(listOf(response(body = directDecision.encodeToByteArray()))),
        )

        val plan = assertIs<PlaybackResolutionResult.Resolved>(
            PlaybackWireClient(ACCOUNT, transport).resolve(extensionRequest()),
        ).plan

        assertEquals(PlaybackDeliveryPath.ExtensionDirect, plan.path)
        assertEquals(mapOf("id" to OPAQUE_MEDIA_ID), plan.parameters)
        assertIs<PlaybackWireTranscodeDecision.DirectPlay>(plan.transcode)
    }

    @Test
    fun silentNoProfileDecisionIsANoPlayableSourceFailure() = runTest {
        val noProfile = """
            {"subsonic-response":{"status":"ok","transcodeDecision":{
              "canDirectPlay":false,"canTranscode":false,
              "errorReason":"no compatible playback profile found"
            }}}
        """.trimIndent()
        val transport = RecordingPlaybackTransport(
            postResponses = ArrayDeque(listOf(response(body = noProfile.encodeToByteArray()))),
        )

        val failure = assertIs<PlaybackResolutionResult.Failed>(
            PlaybackWireClient(ACCOUNT, transport).resolve(extensionRequest()),
        )

        assertEquals(DomainError.Playback.NoPlayableSource, failure.error)
    }

    @Test
    fun malformedSuccessfulDecisionCannotBecomeAServerLimitation() = runTest {
        val malformed = """
            {"subsonic-response":{"status":"ok","transcodeDecision":{
              "canDirectPlay":"false","canTranscode":true,"transcodeParams":"opaque"
            }}}
        """.trimIndent()
        val transport = RecordingPlaybackTransport(
            postResponses = ArrayDeque(listOf(response(body = malformed.encodeToByteArray()))),
        )

        val failure = assertIs<PlaybackResolutionResult.Failed>(
            PlaybackWireClient(ACCOUNT, transport).resolve(extensionRequest()),
        )

        assertEquals(DomainError.Protocol.MalformedEnvelope, failure.error)
    }

    private fun extensionRequest() = PlaybackResolveRequest(
        playbackSessionId = SESSION_ID,
        attemptId = ATTEMPT_ID,
        itemId = ProviderItemId(PROVIDER_ID, OPAQUE_MEDIA_ID),
        sourceContainer = AudioContainer.Mp3,
        supportsTranscodingExtension = true,
        deviceProfile = PlaybackDeviceProfile(
            name = "Dulcet Test",
            platform = "JVM",
            maxAudioBitrate = 256,
            maxTranscodingAudioBitrate = 128,
            directPlayProfiles = listOf(
                DirectPlayAudioProfile(
                    containers = listOf(AudioContainer.Flac),
                    audioCodecs = listOf("flac"),
                    maxAudioChannels = 2,
                ),
            ),
            transcodingProfiles = listOf(
                TranscodingAudioProfile(
                    container = AudioContainer.Mp3,
                    audioCodec = "mp3",
                    maxAudioChannels = 2,
                ),
            ),
        ),
        legacyPreference = LegacyPlaybackPreference(null, null),
    )

    private fun decisionResponse(opaquePlan: String): AuthenticatedEndpointResponse {
        val body = """
            {"subsonic-response":{"status":"ok","transcodeDecision":{
              "canDirectPlay":false,"canTranscode":true,
              "transcodeReason":["container not supported"],
              "transcodeParams":"$opaquePlan",
              "sourceStream":{"container":"flac","protocol":"http"},
              "transcodeStream":{"container":"mp3","protocol":"http"}
            }}}
        """.trimIndent()
        return response(body = body.encodeToByteArray())
    }

    private fun response(
        statusCode: Int = 200,
        body: ByteArray,
    ): AuthenticatedEndpointResponse = AuthenticatedEndpointResponse(
        statusCode = statusCode,
        body = body,
        redactedUrl = "https://music.invalid:443/rest/getTranscodeDecision.view?<redacted>",
        headers = AuthenticatedEndpointResponseHeaders(
            contentType = "application/json",
            contentLength = PlaybackContentLength.Exact(body.size.toLong()),
            retryAfter = null,
            acceptRanges = null,
            contentRange = null,
        ),
        requestTrace = RequestTrace.observed(
            endpoint = "getTranscodeDecision",
            method = "POST",
            redactedUrl = "https://music.invalid:443/rest/getTranscodeDecision.view?<redacted>",
            authenticationLocation = AuthenticationLocation.Query,
            queryAuthenticationParameters = emptySet(),
            formAuthenticationParameters = emptySet(),
            channels = emptySet(),
            requestedProtocolVersion = "1.16.1",
            saltFingerprint = "fixture",
        ),
    )

    private class RecordingPlaybackTransport(
        val postResponses: ArrayDeque<AuthenticatedEndpointResponse> = ArrayDeque(),
    ) : PlaybackEndpointTransport {
        val requests = mutableListOf<Request>()

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
            requests += Request("POST", endpoint, parameters, jsonBody)
            return postResponses.removeFirst()
        }

        override fun close() = Unit
    }

    private data class Request(
        val method: String,
        val endpoint: String,
        val parameters: Map<String, String>,
        val jsonBody: String?,
    )

    private companion object {
        const val PROVIDER_ID = "provider:opaque"
        const val OPAQUE_MEDIA_ID = "song-not-an-integer:abc-123"
        const val USERNAME_CANARY = "wire-user-canary"
        const val PASSWORD_CANARY = "wire-password-canary"
        const val OPAQUE_PLAN = "opaque.jwt.canary+with/slash=="
        val SESSION_ID = PlaybackSessionId("session:wire")
        val ATTEMPT_ID = AttemptId("attempt:wire")
        val ACCOUNT = PlaybackEndpointAccount(
            providerInstanceId = PROVIDER_ID,
            normalizedBaseUrl = "https://music.invalid",
            username = USERNAME_CANARY,
            password = PASSWORD_CANARY,
            allowLocalHttp = false,
        )
    }
}
