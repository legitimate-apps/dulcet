package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ScrobbleDeliveryTest {
    @Test
    fun playbackProgressBeganAndSixtyProgressingSecondsSendEphemeralNowPlaying() = runTest {
        val transport = RecordingScrobbleTransport(
            responses = ArrayDeque(listOf(okResponse(), okResponse())),
        )
        val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        val recorder = SubsonicPlaybackEventRecorder(
            PROVIDER_ID,
            ScrobbleEndpointSender(transport),
            SubmittedPlayOutboxSink(submitted::add),
        )
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(
            PlaybackSessionStart(
                QueueEntryId("queue:scrobble"),
                PlaybackSessionId("session:scrobble"),
                ATTEMPT,
                ITEM,
                initialDuration = 300.seconds,
            ),
        )

        deliver(
            recorder,
            machine.recordPlaybackEvent(
                PlaybackEngineEvent.PlaybackProgressBegan(
                    ATTEMPT,
                    PlaybackWallClockTime(1_788_000_000_000),
                    0.seconds,
                ),
            ).effects,
        )
        for (sample in 1..31) {
            deliver(
                recorder,
                machine.recordPlaybackEvent(
                    PlaybackEngineEvent.PositionChanged(
                        ATTEMPT,
                        mediaPosition = (sample * 2).seconds,
                        monotonicTime = PlaybackMonotonicTime((sample * 2).seconds),
                    ),
                ).effects,
            )
        }

        assertEquals(2, transport.parameters.size)
        transport.parameters.forEach { parameters ->
            assertEquals(MEDIA_ID, parameters["id"])
            assertEquals("false", parameters["submission"])
            assertFalse("time" in parameters)
        }
        assertTrue(submitted.isEmpty())
        assertEquals(2, recorder.diagnostics.nowPlayingSentCount)
    }

    @Test
    fun failedNowPlayingIsDroppedAndNeverSharesTheSubmittedPlaySink() = runTest {
        val transport = RecordingScrobbleTransport(
            responses = ArrayDeque(listOf(errorResponse(50))),
        )
        val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        val recorder = SubsonicPlaybackEventRecorder(
            PROVIDER_ID,
            ScrobbleEndpointSender(transport),
            SubmittedPlayOutboxSink(submitted::add),
        )

        recorder.recordPlaybackEvent(RecordedPlaybackEvent.NowPlaying(ITEM))

        assertTrue(submitted.isEmpty())
        assertEquals(0, recorder.diagnostics.nowPlayingSentCount)
        assertEquals(1, recorder.diagnostics.nowPlayingDroppedFailureCount)
    }

    @Test
    fun submittedPlayCrossesOnlyTheExplicitDurableHandOff() = runTest {
        val transport = RecordingScrobbleTransport()
        val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        val recorder = SubsonicPlaybackEventRecorder(
            PROVIDER_ID,
            ScrobbleEndpointSender(transport),
            SubmittedPlayOutboxSink(submitted::add),
        )
        val event = RecordedPlaybackEvent.SubmittedPlay(
            ITEM,
            PlaybackWallClockTime(1_788_000_123_456),
        )

        recorder.recordPlaybackEvent(event)

        assertEquals(listOf(event), submitted)
        assertTrue(transport.parameters.isEmpty())
        assertEquals(1, recorder.diagnostics.submittedPlayHandOffCount)
    }

    @Test
    fun lowLevelSubmittedSendCarriesWallClockTimeAndSubmissionTrue() = runTest {
        val transport = RecordingScrobbleTransport(
            responses = ArrayDeque(listOf(okResponse())),
        )
        val event = RecordedPlaybackEvent.SubmittedPlay(
            ITEM,
            PlaybackWallClockTime(1_788_000_123_456),
        )

        val result = ScrobbleEndpointSender(transport).send(ScrobbleEndpointRequest(event))

        assertIs<ScrobbleSendResult.Sent>(result)
        assertEquals(
            mapOf(
                "id" to MEDIA_ID,
                "time" to "1788000123456",
                "submission" to "true",
            ),
            transport.parameters.single(),
        )
    }

    @Test
    fun scrobbleWireRequestNeverRendersItemOrTimestampCanaries() {
        val request = ScrobbleEndpointRequest(
            RecordedPlaybackEvent.SubmittedPlay(
                ITEM,
                PlaybackWallClockTime(1_788_000_123_456),
            ),
        )

        assertEquals("ScrobbleEndpointRequest(<redacted>)", request.toString())
        assertFalse(request.toString().contains(MEDIA_ID))
        assertFalse(request.toString().contains("1788000123456"))
    }

    private suspend fun deliver(
        recorder: SubsonicPlaybackEventRecorder,
        effects: List<PlaybackCoreEffect>,
    ) {
        effects.forEach { effect ->
            if (effect is PlaybackCoreEffect.RecordPlaybackEvent) {
                recorder.recordPlaybackEvent(effect.event)
            }
        }
    }

    private class RecordingScrobbleTransport(
        val responses: ArrayDeque<AuthenticatedEndpointResponse> = ArrayDeque(),
    ) : ScrobbleEndpointTransport {
        val parameters = mutableListOf<Map<String, String>>()

        override suspend fun request(
            parameters: Map<String, String>,
        ): AuthenticatedEndpointResponse {
            this.parameters += parameters
            return responses.removeFirst()
        }
    }

    private companion object {
        const val PROVIDER_ID = "provider:scrobble"
        const val MEDIA_ID = "song:opaque-not-an-integer"
        val ITEM = ProviderItemId(PROVIDER_ID, MEDIA_ID)
        val ATTEMPT = AttemptId("attempt:scrobble")

        fun okResponse(): AuthenticatedEndpointResponse = response(
            200,
            """{"subsonic-response":{"status":"ok"}}""".encodeToByteArray(),
        )

        fun errorResponse(code: Int): AuthenticatedEndpointResponse = response(
            200,
            """{"subsonic-response":{"status":"failed","error":{"code":$code}}}"""
                .encodeToByteArray(),
        )

        fun response(status: Int, body: ByteArray): AuthenticatedEndpointResponse =
            AuthenticatedEndpointResponse(
                statusCode = status,
                body = body,
                redactedUrl = "https://music.invalid:443/rest/scrobble.view?<redacted>",
                headers = AuthenticatedEndpointResponseHeaders(
                    contentType = "application/json",
                    contentLength = body.size.toLong(),
                    retryAfter = null,
                    acceptRanges = null,
                    contentRange = null,
                ),
                requestTrace = RequestTrace.observed(
                    endpoint = "scrobble",
                    method = "GET",
                    redactedUrl = "https://music.invalid:443/rest/scrobble.view?<redacted>",
                    authenticationLocation = AuthenticationLocation.Query,
                    queryAuthenticationParameters = emptySet(),
                    formAuthenticationParameters = emptySet(),
                    channels = emptySet(),
                    requestedProtocolVersion = "1.16.1",
                    saltFingerprint = "fixture",
                ),
            )
    }
}
