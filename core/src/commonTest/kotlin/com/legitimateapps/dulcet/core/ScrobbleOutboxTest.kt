package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class ScrobbleOutboxTest {
    @Test
    fun sliceTwoSeamPersistsAcrossStoreRecreationBeforeDelivery() = runTest {
        val driver = createTestDriver()
        val firstOutbox = PersistentScrobbleOutbox(
            DulcetDatabaseStore.open(driver).database,
            OutboxWallClock { CREATED_AT },
        )
        val preReopenTransport = ScriptedTransport(ArrayDeque())
        val recorder = SubsonicPlaybackEventRecorder(
            SERVER,
            ScrobbleEndpointSender(preReopenTransport),
            firstOutbox,
        )
        recorder.recordPlaybackEvent(EVENT)
        assertEquals(0, preReopenTransport.parameters.size)
        assertEquals(1, recorder.diagnostics.submittedPlayHandOffCount)

        val reopenedOutbox = PersistentScrobbleOutbox(
            DulcetDatabaseStore.open(driver).database,
            OutboxWallClock { CREATED_AT },
        )
        val transport = ScriptedTransport(ArrayDeque(listOf(okResponse())))
        val worker = worker(reopenedOutbox, transport)

        val result = worker.onForeground()

        assertEquals(1, result.attemptedCount)
        assertEquals(1, result.deliveredCount)
        assertEquals(0, reopenedOutbox.count())
        assertEquals(SESSION_START.toString(), transport.parameters.single()["time"])
        driver.close()
    }

    @Test
    fun lostResponseKeepsTheRowAndRetryCanSendTheSamePlayAgain() = runTest {
        val fixture = fixture()
        val transport = ScriptedTransport(
            ArrayDeque(listOf(errorResponse(), okResponse())),
        )
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val worker = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics)

        val failed = worker.onForeground()
        assertEquals(1.seconds, failed.nextRetryAfter)
        assertEquals(1, fixture.outbox.count())
        assertEquals(1, fixture.outbox.pending(SERVER_ID).single().attemptCount)

        fixture.monotonic.advanceBy(999.seconds / 1000)
        assertEquals(0, worker.onReachable().attemptedCount)
        fixture.monotonic.advanceBy(1.seconds / 1000)
        assertEquals(1, worker.onRetryTimer().deliveredCount)

        assertEquals(2, transport.parameters.size)
        assertEquals(transport.parameters[0], transport.parameters[1])
        assertEquals(0, fixture.outbox.count())
        fixture.close()
    }

    @Test
    fun foregroundAndReachabilityTriggersRespectExponentialMonotonicBackoff() = runTest {
        val fixture = fixture()
        val transport = ScriptedTransport(
            ArrayDeque(listOf(errorResponse(), errorResponse(), okResponse())),
        )
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val worker = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics)

        assertEquals(1.seconds, worker.onForeground().nextRetryAfter)
        assertEquals(0, worker.onReachable().attemptedCount)
        fixture.monotonic.advanceBy(1.seconds)
        assertEquals(2.seconds, worker.onReachable().nextRetryAfter)
        fixture.monotonic.advanceBy(1.seconds)
        assertEquals(0, worker.onForeground().attemptedCount)
        fixture.monotonic.advanceBy(1.seconds)
        assertEquals(1, worker.onForeground().deliveredCount)

        val failures = fixture.diagnostics.events
            .filterIsInstance<ScrobbleOutboxDiagnosticEvent.DeliveryFailed>()
        assertEquals(listOf(1.seconds, 2.seconds), failures.map { it.nextRetryAfter })
        fixture.close()
    }

    @Test
    fun expiredEntryIsAttemptedBeforeProductRetentionDropsIt() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        fixture.wallClock.advanceBy(31.days)
        val transport = ScriptedTransport(ArrayDeque(listOf(errorResponse())))
        val worker = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics, fixture.wallClock)

        val result = worker.onForeground()

        assertEquals(1, result.retentionDropCount)
        assertEquals(1, result.attemptedCount)
        assertEquals(0, fixture.outbox.count())
        assertEquals(1, transport.parameters.size)
        val event = assertIs<ScrobbleOutboxDiagnosticEvent.ProductRetentionDropped>(
            fixture.diagnostics.events.last(),
        )
        assertEquals(30.days, event.retentionLimit)
        assertEquals(EVENT.sessionStartWallClock, event.entry.sessionStartWallClock)
        fixture.close()
    }

    @Test
    fun retentionCleanupIsScopedToTheWorkersAccount() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        val otherServer = ServerId("server:other-outbox")
        val otherEvent = RecordedPlaybackEvent.SubmittedPlay(
            itemId = ProviderItemId(otherServer.value, RAW_ID),
            sessionStartWallClock = EVENT.sessionStartWallClock,
        )
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        fixture.outbox.persistForAtLeastOnceDelivery(otherEvent)

        val dropped = fixture.outbox.dropExpired(
            serverId = SERVER_ID,
            nowWallClock = PlaybackWallClockTime(CREATED_AT + 31.days.inWholeMilliseconds),
            diagnosticSink = fixture.diagnostics,
        )

        assertEquals(1, dropped)
        assertEquals(1, fixture.outbox.count())
        assertEquals(listOf(otherEvent.sessionStartWallClock), fixture.outbox.pending(otherServer).map(ScrobbleOutboxEntry::sessionStartWallClock))
        fixture.close()
    }

    @Test
    fun retentionDiagnosticRunsOnlyAfterTheDeletionCommits() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val diagnosticFailure = assertFailsWith<IllegalStateException> {
            fixture.outbox.dropExpired(
                serverId = SERVER_ID,
                nowWallClock = PlaybackWallClockTime(CREATED_AT + 31.days.inWholeMilliseconds),
                diagnosticSink = ScrobbleOutboxDiagnosticSink { throw IllegalStateException("sink failed") },
            )
        }

        assertEquals("sink failed", diagnosticFailure.message)
        assertEquals(0, fixture.outbox.count())
        fixture.close()
    }

    @Test
    fun localUniquenessKeyCreatesOneRowWithoutClaimingNetworkIdempotence() = runTest {
        val fixture = fixture()

        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)

        assertEquals(1, fixture.outbox.count())
        fixture.close()
    }

    @Test
    fun localUniquenessKeyKeepsDifferentSessionsOfTheSameTrack() = runTest {
        val fixture = fixture()
        val laterSession = RecordedPlaybackEvent.SubmittedPlay(
            itemId = EVENT.itemId,
            sessionStartWallClock = PlaybackWallClockTime(SESSION_START + 1_000),
        )

        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        fixture.outbox.persistForAtLeastOnceDelivery(laterSession)

        assertEquals(2, fixture.outbox.count())
        assertEquals(
            listOf(EVENT.sessionStartWallClock, laterSession.sessionStartWallClock),
            fixture.outbox.pending(SERVER_ID).map(ScrobbleOutboxEntry::sessionStartWallClock),
        )
        fixture.close()
    }

    @Test
    fun retentionAgeStartsWhenEntryIsCreatedNotWhenPlaybackSessionStarted() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        val oldSession = RecordedPlaybackEvent.SubmittedPlay(
            itemId = EVENT.itemId,
            sessionStartWallClock = PlaybackWallClockTime(CREATED_AT - 31.days.inWholeMilliseconds),
        )
        fixture.outbox.persistForAtLeastOnceDelivery(oldSession)

        val dropped = fixture.outbox.dropExpired(
            serverId = SERVER_ID,
            nowWallClock = PlaybackWallClockTime(CREATED_AT + 1.days.inWholeMilliseconds),
            diagnosticSink = fixture.diagnostics,
        )

        assertEquals(0, dropped)
        assertEquals(1, fixture.outbox.count())
        assertEquals(emptyList(), fixture.diagnostics.events)
        fixture.close()
    }

    private fun fixture(wallClock: MutableWallClock = MutableWallClock(CREATED_AT)): Fixture {
        val driver = createTestDriver()
        val outbox = PersistentScrobbleOutbox(DulcetDatabaseStore.open(driver).database, wallClock)
        return Fixture(
            driver,
            outbox,
            wallClock,
            MutableMonotonicClock(),
            RecordingDiagnostics(),
        )
    }

    private fun worker(
        outbox: PersistentScrobbleOutbox,
        transport: ScriptedTransport,
        monotonic: MutableMonotonicClock = MutableMonotonicClock(),
        diagnostics: RecordingDiagnostics = RecordingDiagnostics(),
        wallClock: OutboxWallClock = OutboxWallClock { CREATED_AT },
    ): ScrobbleOutboxDeliveryWorker = ScrobbleOutboxDeliveryWorker(
        serverId = SERVER_ID,
        outbox = outbox,
        sender = ScrobbleEndpointSender(transport),
        wallClock = wallClock,
        monotonicClock = monotonic,
        diagnosticSink = diagnostics,
    )

    private class ScriptedTransport(
        private val responses: ArrayDeque<AuthenticatedEndpointResponse>,
    ) : ScrobbleEndpointTransport {
        val parameters = mutableListOf<Map<String, String>>()

        override suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse {
            this.parameters += parameters
            return responses.removeFirst()
        }
    }

    private class MutableWallClock(private var now: Long) : OutboxWallClock {
        override fun nowEpochMilliseconds(): Long = now
        fun advanceBy(duration: Duration) {
            now += duration.inWholeMilliseconds
        }
    }

    private class MutableMonotonicClock : OutboxMonotonicClock {
        private var now: Duration = Duration.ZERO
        override fun now(): Duration = now
        fun advanceBy(duration: Duration) {
            now += duration
        }
    }

    private class RecordingDiagnostics : ScrobbleOutboxDiagnosticSink {
        val events = mutableListOf<ScrobbleOutboxDiagnosticEvent>()
        override fun record(event: ScrobbleOutboxDiagnosticEvent) {
            events += event
        }
    }

    private data class Fixture(
        val driver: SqlDriver,
        val outbox: PersistentScrobbleOutbox,
        val wallClock: MutableWallClock,
        val monotonic: MutableMonotonicClock,
        val diagnostics: RecordingDiagnostics,
    ) {
        fun close() = driver.close()
    }

    private companion object {
        const val SERVER = "server:outbox"
        const val RAW_ID = "track:opaque"
        const val SESSION_START = 1_788_000_123_456L
        const val CREATED_AT = 1_788_000_999_000L
        val SERVER_ID = ServerId(SERVER)
        val EVENT = RecordedPlaybackEvent.SubmittedPlay(
            ProviderItemId(SERVER, RAW_ID),
            PlaybackWallClockTime(SESSION_START),
        )

        fun okResponse(): AuthenticatedEndpointResponse = response(
            200,
            """{"subsonic-response":{"status":"ok"}}""".encodeToByteArray(),
        )

        fun errorResponse(): AuthenticatedEndpointResponse = response(
            503,
            """{"subsonic-response":{"status":"failed","error":{"code":0}}}"""
                .encodeToByteArray(),
        )

        fun response(status: Int, body: ByteArray): AuthenticatedEndpointResponse =
            AuthenticatedEndpointResponse(
                statusCode = status,
                body = body,
                redactedUrl = "https://music.invalid/rest/scrobble.view?<redacted>",
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
                    redactedUrl = "https://music.invalid/rest/scrobble.view?<redacted>",
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
