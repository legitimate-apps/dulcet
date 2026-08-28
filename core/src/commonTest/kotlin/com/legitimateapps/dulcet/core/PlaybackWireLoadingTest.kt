package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlaybackWireLoadingTest {
    @Test
    fun legacyTranscodeCarriesReturnedLengthAsEstimatedOnTheLoadedPlan() = runTest {
        val body = "ID3\u0004complete cold transcode".encodeToByteArray()
        val estimate = body.size.toLong() + 23_398
        val transport = QueueTransport(
            gets = ArrayDeque(
                listOf(
                    response(
                        statusCode = 200,
                        body = body,
                        contentType = "audio/mpeg",
                        contentLength = PlaybackContentLength.Estimated(estimate),
                    ),
                ),
            ),
        )
        val client = PlaybackWireClient(ACCOUNT, transport)
        val plan = resolved(
            client,
            resolveRequest(FIRST_ATTEMPT).copy(supportsTranscodingExtension = false),
        )

        val loaded = assertIs<PlaybackLoadResult.Audio>(client.load(plan))

        assertEquals(null, plan.contentLength)
        assertEquals(PlaybackContentLength.Estimated(estimate), loaded.plan.contentLength)
        assertEquals(PlaybackContentLength.Estimated(estimate), loaded.validation.contentLength)
        assertEquals(
            AuthenticatedEndpointContentLengthKind.Estimated,
            transport.requests.single().options.contentLengthKind,
        )
    }

    @Test
    fun pathABadRequestReresolvesExactlyOnceWithANewAttemptInTheSameSession() = runTest {
        val transport = QueueTransport(
            posts = ArrayDeque(
                listOf(
                    decisionResponse(FIRST_PLAN),
                    decisionResponse(SECOND_PLAN),
                ),
            ),
            gets = ArrayDeque(
                listOf(
                    response(400, "Bad Request\n".encodeToByteArray(), "text/plain"),
                    response(200, "ID3\u0004".encodeToByteArray(), "audio/mpeg"),
                ),
            ),
        )
        val client = PlaybackWireClient(
            account = ACCOUNT,
            transport = transport,
            attemptIdSource = PlaybackAttemptIdSource { SECOND_ATTEMPT },
        )
        val initialPlan = resolved(client, resolveRequest(FIRST_ATTEMPT))

        val loaded = assertIs<PlaybackLoadResult.Audio>(client.load(initialPlan))

        assertTrue(loaded.didReresolveAfterBadRequest)
        assertEquals(SESSION, loaded.plan.playbackSessionId)
        assertEquals(SECOND_ATTEMPT, loaded.plan.attemptId)
        assertEquals(
            listOf("POST", "GET", "POST", "GET"),
            transport.requests.map(Request::method),
        )
        assertEquals(FIRST_PLAN, transport.requests[1].parameters["transcodeParams"])
        assertEquals(SECOND_PLAN, transport.requests[3].parameters["transcodeParams"])
        assertEquals(
            mapOf(
                "transcodeParams" to SECOND_PLAN,
                "mediaId" to MEDIA_ID,
                "mediaType" to "song",
            ),
            transport.requests[3].parameters,
        )
    }

    @Test
    fun secondPathABadRequestIsTerminal() = runTest {
        val transport = QueueTransport(
            posts = ArrayDeque(listOf(decisionResponse(FIRST_PLAN), decisionResponse(SECOND_PLAN))),
            gets = ArrayDeque(
                listOf(
                    response(400, "Bad Request\n".encodeToByteArray(), "text/plain"),
                    response(400, "Bad Request\n".encodeToByteArray(), "text/plain"),
                ),
            ),
        )
        val client = PlaybackWireClient(
            ACCOUNT,
            transport,
            PlaybackAttemptIdSource { SECOND_ATTEMPT },
        )

        val failure = assertIs<PlaybackLoadResult.Failed>(
            client.load(resolved(client, resolveRequest(FIRST_ATTEMPT))),
        )

        assertTrue(failure.didReresolveAfterBadRequest)
        assertEquals(SECOND_ATTEMPT, failure.plan.attemptId)
        assertEquals(400, failure.statusCode)
        assertEquals(PlaybackErrorResponseShape.BareHttpError, failure.shape)
        assertEquals(4, transport.requests.size)
    }

    @Test
    fun legacyBadRequestNeverUsesThePathARefreshRule() = runTest {
        var attemptRequests = 0
        val transport = QueueTransport(
            gets = ArrayDeque(
                listOf(response(400, "Bad Request\n".encodeToByteArray(), "text/plain")),
            ),
        )
        val client = PlaybackWireClient(
            ACCOUNT,
            transport,
            PlaybackAttemptIdSource {
                attemptRequests += 1
                SECOND_ATTEMPT
            },
        )
        val request = resolveRequest(FIRST_ATTEMPT).copy(supportsTranscodingExtension = false)

        val failure = assertIs<PlaybackLoadResult.Failed>(client.load(resolved(client, request)))

        assertEquals(0, attemptRequests)
        assertEquals(1, transport.requests.size)
        assertEquals(400, failure.statusCode)
        assertEquals(false, failure.didReresolveAfterBadRequest)
    }

    @Test
    fun rangeIsConstructedAtTheSharedAuthenticatedBoundary() = runTest {
        val transport = QueueTransport(
            posts = ArrayDeque(listOf(decisionResponse(FIRST_PLAN))),
            gets = ArrayDeque(
                listOf(
                    response(
                        206,
                        "ID3\u0004".encodeToByteArray(),
                        "audio/mpeg",
                        contentRange = "bytes 0-65535/7550103",
                    ),
                ),
            ),
        )
        val client = PlaybackWireClient(ACCOUNT, transport)
        val plan = resolved(client, resolveRequest(FIRST_ATTEMPT))

        val loaded = assertIs<PlaybackLoadResult.Audio>(
            client.load(plan, range = PlaybackByteRange(0, 65_535)),
        )

        assertEquals("bytes=0-65535", transport.requests.last().options.range)
        assertTrue(loaded.validation.supportsByteRanges)
    }

    @Test
    fun preloadBusyComesFromStatusAndReducesOnlyTheTranscodeBudget() = runTest {
        val busyEnvelope = """
            {"subsonic-response":{"status":"failed","error":{"code":0,"message":"generic"}}}
        """.trimIndent().encodeToByteArray()
        val transport = QueueTransport(
            posts = ArrayDeque(listOf(decisionResponse(FIRST_PLAN))),
            gets = ArrayDeque(
                listOf(response(429, busyEnvelope, "application/json", retryAfter = "5")),
            ),
        )
        val budget = PlaybackTranscodeBudget()
        val client = PlaybackWireClient(
            account = ACCOUNT,
            transport = transport,
            transcodeBudget = budget,
        )
        val plan = resolved(client, resolveRequest(FIRST_ATTEMPT))

        val failure = assertIs<PlaybackLoadResult.Failed>(
            client.load(plan, purpose = PlaybackWireRequestPurpose.Preload),
        )

        assertEquals(DomainError.Server.Busy(5.seconds), failure.error)
        assertEquals(DomainError.Server.Known(0), failure.presentationError)
        assertEquals(1, budget.maximumConcurrentTranscodes)
        assertTrue(
            budget.maySchedule(
                PlaybackWireRequestPurpose.Preload,
                isTranscoded = false,
                active = ActiveTranscodeCounts(currentPlayback = 1),
            ),
        )
    }

    @Test
    fun transcodeBudgetImplementsPlaybackThenPreloadThenDownloadPriority() {
        val budget = PlaybackTranscodeBudget()
        val currentAndPreload = ActiveTranscodeCounts(currentPlayback = 1, preload = 1)

        assertTrue(
            budget.maySchedule(
                PlaybackWireRequestPurpose.CurrentPlayback,
                isTranscoded = true,
                active = currentAndPreload,
            ),
        )
        assertEquals(
            false,
            budget.maySchedule(
                PlaybackWireRequestPurpose.Preload,
                isTranscoded = true,
                active = currentAndPreload,
            ),
        )
        assertEquals(
            false,
            budget.maySchedule(
                PlaybackWireRequestPurpose.Download,
                isTranscoded = true,
                active = currentAndPreload,
            ),
        )

        budget.observeFailure(
            PlaybackWireRequestPurpose.Preload,
            isTranscoded = true,
            error = DomainError.Server.Busy(5.seconds),
        )
        assertEquals(1, budget.maximumConcurrentTranscodes)
        budget.reset()
        assertEquals(2, budget.maximumConcurrentTranscodes)
    }

    private suspend fun resolved(
        client: PlaybackWireClient,
        request: PlaybackResolveRequest,
    ): RemotePlaybackWirePlan = assertIs<PlaybackResolutionResult.Resolved>(
        client.resolve(request),
    ).plan

    private fun resolveRequest(attemptId: AttemptId) = PlaybackResolveRequest(
        playbackSessionId = SESSION,
        attemptId = attemptId,
        itemId = ProviderItemId(PROVIDER_ID, MEDIA_ID),
        sourceContainer = AudioContainer.Flac,
        supportsTranscodingExtension = true,
        deviceProfile = PlaybackDeviceProfile(
            name = "Dulcet Test",
            platform = "JVM",
            maxAudioBitrate = 256,
            maxTranscodingAudioBitrate = 128,
            directPlayProfiles = listOf(
                DirectPlayAudioProfile(
                    listOf(AudioContainer.Flac),
                    listOf("flac"),
                    maxAudioChannels = 2,
                ),
            ),
            transcodingProfiles = listOf(
                TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2),
            ),
        ),
        legacyPreference = LegacyPlaybackPreference(AudioContainer.Mp3, 64),
    )

    private fun decisionResponse(plan: String): AuthenticatedEndpointResponse {
        val body = """
            {"subsonic-response":{"status":"ok","transcodeDecision":{
              "canDirectPlay":false,"canTranscode":true,"transcodeReason":[],
              "transcodeParams":"$plan",
              "sourceStream":{"container":"flac","protocol":"http"},
              "transcodeStream":{"container":"mp3","protocol":"http"}
            }}}
        """.trimIndent().encodeToByteArray()
        return response(200, body, "application/json")
    }

    private fun response(
        statusCode: Int,
        body: ByteArray,
        contentType: String,
        retryAfter: String? = null,
        contentRange: String? = null,
        contentLength: PlaybackContentLength? = PlaybackContentLength.Exact(body.size.toLong()),
    ): AuthenticatedEndpointResponse = AuthenticatedEndpointResponse(
        statusCode = statusCode,
        body = body,
        redactedUrl = "https://music.invalid:443/rest/wire.view?<redacted>",
        headers = AuthenticatedEndpointResponseHeaders(
            contentType = contentType,
            contentLength = contentLength,
            retryAfter = retryAfter,
            acceptRanges = null,
            contentRange = contentRange,
        ),
        requestTrace = RequestTrace.observed(
            endpoint = "wire",
            method = "GET",
            redactedUrl = "https://music.invalid:443/rest/wire.view?<redacted>",
            authenticationLocation = AuthenticationLocation.Query,
            queryAuthenticationParameters = emptySet(),
            formAuthenticationParameters = emptySet(),
            channels = emptySet(),
            requestedProtocolVersion = "1.16.1",
            saltFingerprint = "fixture",
        ),
    )

    private class QueueTransport(
        val posts: ArrayDeque<AuthenticatedEndpointResponse> = ArrayDeque(),
        val gets: ArrayDeque<AuthenticatedEndpointResponse> = ArrayDeque(),
    ) : PlaybackEndpointTransport {
        val requests = mutableListOf<Request>()

        override suspend fun get(
            endpoint: String,
            parameters: Map<String, String>,
            options: AuthenticatedEndpointRequestOptions,
        ): AuthenticatedEndpointResponse {
            requests += Request("GET", endpoint, parameters, null, options)
            return gets.removeFirst()
        }

        override suspend fun postJson(
            endpoint: String,
            parameters: Map<String, String>,
            jsonBody: String,
        ): AuthenticatedEndpointResponse {
            requests += Request(
                "POST",
                endpoint,
                parameters,
                jsonBody,
                AuthenticatedEndpointRequestOptions(),
            )
            return posts.removeFirst()
        }

        override fun close() = Unit
    }

    private data class Request(
        val method: String,
        val endpoint: String,
        val parameters: Map<String, String>,
        val jsonBody: String?,
        val options: AuthenticatedEndpointRequestOptions,
    )

    private companion object {
        const val PROVIDER_ID = "provider:wire-load"
        const val MEDIA_ID = "opaque-song-id:not-an-integer"
        const val FIRST_PLAN = "first.jwt+opaque/=="
        const val SECOND_PLAN = "second.jwt+opaque/=="
        val SESSION = PlaybackSessionId("session:wire-load")
        val FIRST_ATTEMPT = AttemptId("attempt:first")
        val SECOND_ATTEMPT = AttemptId("attempt:second")
        val ACCOUNT = PlaybackEndpointAccount(
            PROVIDER_ID,
            "https://music.invalid",
            "wire-user-canary",
            "wire-password-canary",
            false,
        )
    }
}
