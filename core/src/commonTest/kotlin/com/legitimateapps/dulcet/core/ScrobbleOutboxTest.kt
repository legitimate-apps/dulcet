package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    fun futureSessionTimestampSurvivesStoreRecreationThenIsClampedAndFlaggedOnDelivery() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        val futureEvent = RecordedPlaybackEvent.SubmittedPlay(
            itemId = EVENT.itemId,
            sessionStartWallClock = PlaybackWallClockTime(CREATED_AT + 1.days.inWholeMilliseconds),
        )
        fixture.outbox.persistForAtLeastOnceDelivery(futureEvent)
        val reopenedOutbox = PersistentScrobbleOutbox(
            DulcetDatabaseStore.open(fixture.driver).database,
            fixture.wallClock,
        )
        val transport = ScriptedTransport(ArrayDeque(listOf(okResponse())))
        val worker = worker(
            reopenedOutbox,
            transport,
            fixture.monotonic,
            fixture.diagnostics,
            fixture.wallClock,
        )

        val result = worker.onForeground()

        assertEquals(1, result.deliveredCount)
        assertEquals(CREATED_AT.toString(), transport.parameters.single()["time"])
        val diagnostic = assertIs<ScrobbleOutboxDiagnosticEvent.FutureTimestampClamped>(
            fixture.diagnostics.events.single(),
        )
        assertEquals(futureEvent.sessionStartWallClock, diagnostic.entry.sessionStartWallClock)
        assertEquals(PlaybackWallClockTime(CREATED_AT), diagnostic.submittedAtWallClock)
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

    @Test
    fun aRateLimitedServersRetryAfterSetsTheWaitWhenItIsLongerThanTheBackoff() = runTest {
        val fixture = fixture()
        val transport = ScriptedTransport(ArrayDeque(listOf(rateLimited("30"), okResponse())))
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val worker = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics)

        assertEquals(30.seconds, worker.onForeground().nextRetryAfter)
        fixture.monotonic.advanceBy(30.seconds - 1.milliseconds)
        assertEquals(0, worker.onReachable().attemptedCount, "Retry-After is a floor, not a hint")
        fixture.monotonic.advanceBy(1.milliseconds)
        assertEquals(1, worker.onRetryTimer().deliveredCount)
        fixture.close()
    }

    @Test
    fun retryAfterNeverShortensTheBackoffAndNoWaitPassesFiveMinutes() = runTest {
        val fixture = fixture()
        val transport = ScriptedTransport(
            ArrayDeque(listOf(rateLimited("1"), errorResponse(), rateLimited("1"), rateLimited("86400"))),
        )
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val worker = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics)

        val waits = mutableListOf<Duration?>()
        repeat(4) {
            waits += worker.onForeground().nextRetryAfter
            fixture.monotonic.advanceBy(10.minutes)
        }

        // 1 s (attempt 1: max(1, 1)), 2 s (a plain failure), 4 s (attempt 3: the backoff beats a
        // Retry-After of 1), and the five-minute ceiling for a Retry-After of a day.
        assertEquals(listOf<Duration?>(1.seconds, 2.seconds, 4.seconds, 5.minutes), waits)
        fixture.close()
    }

    @Test
    fun aFailedSubmissionIsRetriedWithoutAnyForegroundOrReachabilityEvent() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        val clock = OutboxMonotonicClock { testScheduler.currentTime.milliseconds }
        val transport = recordingTransport(clock) { call, _ -> if (call <= 2) errorResponse() else okResponse() }
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val loop = retryLoop(fixture, transport, clock)

        loop.drainNow()
        assertEquals(listOf(0.milliseconds), transport.times, "One drain, one request")

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, transport.times.size, "The first retry waits the whole 1 s backoff")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0.milliseconds, 1000.milliseconds), transport.times)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, transport.times.size, "The second retry waits the whole 2 s backoff")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0.milliseconds, 1000.milliseconds, 3000.milliseconds), transport.times)
        assertEquals(0, fixture.outbox.count(), "The third request was acknowledged")

        advanceTimeBy(1.days.inWholeMilliseconds)
        runCurrent()
        assertEquals(3, transport.times.size, "Nothing is sent once the outbox is empty")
        fixture.close()
    }

    @Test
    fun theLoopHonoursARetryAfterAndStaysBoundedWhileTheServerKeepsFailing() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        val clock = OutboxMonotonicClock { testScheduler.currentTime.milliseconds }
        val transport = recordingTransport(clock) { call, _ -> if (call == 1) rateLimited("30") else errorResponse() }
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val loop = retryLoop(fixture, transport, clock)

        loop.drainNow()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, transport.times.size, "A Retry-After of 30 s holds the next request back")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(30_000.milliseconds, transport.times.last())

        // The 30 s wait was attempt 1's; attempt 2 onward backs off 2, 4, 8 ... 256 s and then stays
        // at 256 s: thirty minutes of a server that never answers cost a handful of requests.
        advanceTimeBy(30.minutes.inWholeMilliseconds - 30_000)
        runCurrent()
        val gaps = transport.times.zipWithNext { a, b -> b - a }
        assertEquals(listOf(30.seconds, 2.seconds, 4.seconds), gaps.take(3))
        assertTrue(gaps.all { it <= 5.minutes }, "No wait passes five minutes: $gaps")
        assertEquals(256.seconds, gaps.last())
        assertEquals(14, transport.times.size, "30 minutes of failures cost a bounded number of requests: $gaps")
        assertEquals(1, fixture.outbox.count(), "A failing play stays in the outbox")
        fixture.close()
    }

    @Test
    fun aDrainInsideTheWaitNeitherSendsEarlyNorStacksASecondTimer() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        val clock = OutboxMonotonicClock { testScheduler.currentTime.milliseconds }
        val transport = recordingTransport(clock) { call, _ -> if (call == 1) errorResponse() else okResponse() }
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val loop = retryLoop(fixture, transport, clock)

        loop.drainNow()
        assertEquals(1, loop.liveTimers)
        advanceTimeBy(400)
        loop.drainNow() // a new play, a foreground: another trigger inside the wait
        assertEquals(1, loop.liveTimers, "The earlier timer was replaced, not joined by a second")
        advanceTimeBy(400)
        loop.drainNow()
        runCurrent()
        assertEquals(1, loop.liveTimers, "Still one live timer after a third trigger")
        assertEquals(1, transport.times.size, "The wait still holds")

        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(0.milliseconds, 1000.milliseconds), transport.times, "Exactly one retry, on time")
        advanceTimeBy(10.minutes.inWholeMilliseconds)
        runCurrent()
        assertEquals(2, transport.times.size)
        assertEquals(0, loop.liveTimers, "Nothing is left armed once the play is acknowledged")
        fixture.close()
    }

    @Test
    fun aClosedLoopIsNotRevivedByADrainThatWasInFlightWhenItClosed() = runTest {
        val fixture = fixture(wallClock = MutableWallClock(CREATED_AT))
        val clock = OutboxMonotonicClock { testScheduler.currentTime.milliseconds }
        val gate = kotlinx.coroutines.CompletableDeferred<AuthenticatedEndpointResponse>()
        var requests = 0
        val transport = object : ScrobbleEndpointTransport {
            override suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse {
                requests += 1
                return gate.await()
            }
        }
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val worker = ScrobbleOutboxDeliveryWorker(
            serverId = SERVER_ID,
            outbox = fixture.outbox,
            sender = ScrobbleEndpointSender(transport),
            wallClock = fixture.wallClock,
            monotonicClock = clock,
            diagnosticSink = fixture.diagnostics,
        )
        var drains = 0
        val loop = ScrobbleOutboxRetryLoop(backgroundScope, { worker.onForeground() }, onDrained = { drains += 1 })

        val inFlight = backgroundScope.launch { loop.drainNow() }
        runCurrent()
        assertEquals(1, requests, "The drain is suspended inside the send")

        loop.cancel() // the account was reconfigured: this loop and its sender are retired
        gate.complete(errorResponse()) // the closed client's send fails
        runCurrent()
        assertTrue(inFlight.isCompleted)
        assertEquals(0, loop.liveTimers, "A drain that finished after cancel armed no timer")

        advanceTimeBy(1.days.inWholeMilliseconds)
        runCurrent()
        assertEquals(1, requests, "The closed loop sends nothing more")
        loop.drainNow()
        assertEquals(1, requests, "Nor does a trigger that reaches it later")
        assertEquals(1, fixture.outbox.pending(SERVER_ID).single().attemptCount, "No failed attempt is added to rows the new worker shares")
        fixture.close()
    }

    @Test
    fun aDrainThatThrewIsReportedAndRetriedOnTheSameBackoff() = runTest {
        var calls = 0
        val drained = mutableListOf<Int>()
        var failures = 0
        val loop = ScrobbleOutboxRetryLoop(
            scope = backgroundScope,
            drain = {
                calls += 1
                if (calls <= 2) throw IllegalStateException("storage failed")
                ScrobbleOutboxDeliveryResult(1, 1, 0, null)
            },
            onDrained = { drained += it.deliveredCount },
            onFailed = { failures += 1 },
        )

        loop.drainNow()
        assertEquals(1, failures)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, failures)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1), drained)
        advanceTimeBy(1.days.inWholeMilliseconds)
        runCurrent()
        assertEquals(3, calls, "A delivered drain with nothing left ends the loop")
    }

    @Test
    fun aPlayThatNeverGetsThroughIsDroppedAtThirtyDaysAndTheRetriesStop() = runTest {
        val wall = MutableWallClock(CREATED_AT)
        val fixture = fixture(wallClock = wall)
        val clock = OutboxMonotonicClock { testScheduler.currentTime.milliseconds }
        val transport = recordingTransport(clock) { _, _ -> errorResponse() }
        fixture.outbox.persistForAtLeastOnceDelivery(EVENT)
        val worker = ScrobbleOutboxDeliveryWorker(
            serverId = SERVER_ID,
            outbox = fixture.outbox,
            sender = ScrobbleEndpointSender(transport),
            wallClock = wall,
            monotonicClock = clock,
            diagnosticSink = fixture.diagnostics,
        )
        val loop = ScrobbleOutboxRetryLoop(
            scope = backgroundScope,
            drain = {
                wall.setTo(CREATED_AT + testScheduler.currentTime)
                worker.onForeground()
            },
        )

        loop.drainNow()
        advanceTimeBy(29.days.inWholeMilliseconds)
        runCurrent()
        assertEquals(1, fixture.outbox.count(), "Day 29: still held")
        assertTrue(fixture.diagnostics.events.none { it is ScrobbleOutboxDiagnosticEvent.ProductRetentionDropped })

        advanceTimeBy(2.days.inWholeMilliseconds)
        runCurrent()
        assertEquals(0, fixture.outbox.count(), "Day 31: dropped by the product retention decision")
        assertEquals(1, fixture.diagnostics.events.count { it is ScrobbleOutboxDiagnosticEvent.ProductRetentionDropped })
        val sentWhenDropped = transport.times.size
        advanceTimeBy(1.days.inWholeMilliseconds)
        runCurrent()
        assertEquals(sentWhenDropped, transport.times.size, "No request leaves for a dropped play")
        fixture.close()
    }

    // -- A play the server permanently refuses (spec §15.3) --------------------------------------

    @Test
    fun aPlayTheServerSaysIsGoneDoesNotHoldBackTheOnesBehindIt() = runTest {
        val fixture = fixture()
        val clock = fixture.monotonic
        val transport = recordingTransport(clock) { _, parameters ->
            if (parameters["id"] == GONE_ID) notFound() else okResponse()
        }
        fixture.outbox.persistForAtLeastOnceDelivery(play(GONE_ID, 1))
        fixture.outbox.persistForAtLeastOnceDelivery(play("second", 2))
        fixture.outbox.persistForAtLeastOnceDelivery(play("third", 3))
        val worker = worker(fixture.outbox, transport, clock, fixture.diagnostics)

        val first = worker.onForeground()

        assertEquals(listOf(GONE_ID, "second", "third"), transport.ids, "Oldest first, and the queue goes on past the refusal")
        assertEquals(2, first.deliveredCount)
        assertEquals(listOf(GONE_ID), fixture.outbox.pending(SERVER_ID).map { it.rawId })
        assertEquals(1.minutes, first.nextRetryAfter, "The refused play's own first wait")

        // A drain inside that wait leaves it alone: no early send, no burning of its refusals.
        clock.advanceBy(59.seconds)
        val inside = worker.onReachable()
        assertEquals(0, inside.attemptedCount)
        assertEquals(1.seconds, inside.nextRetryAfter)
        assertEquals(3, transport.ids.size)
        fixture.close()
    }

    @Test
    fun aPlayTheServerRefusesThreeTimesIsDroppedAndToldAndNeverSentAgain() = runTest {
        val wall = MutableWallClock(CREATED_AT)
        val fixture = fixture(wallClock = wall)
        val clock = OutboxMonotonicClock { testScheduler.currentTime.milliseconds }
        val transport = recordingTransport(clock) { _, parameters ->
            if (parameters["id"] == GONE_ID) notFound() else okResponse()
        }
        fixture.outbox.persistForAtLeastOnceDelivery(play(GONE_ID, 1))
        fixture.outbox.persistForAtLeastOnceDelivery(play("behind", 2))
        val loop = retryLoop(fixture, transport, clock)

        loop.drainNow()
        advanceTimeBy(1.days.inWholeMilliseconds)
        runCurrent()

        val goneTimes = transport.times.zip(transport.ids).filter { it.second == GONE_ID }.map { it.first }
        assertEquals(
            listOf(0.milliseconds, 1.minutes, 6.minutes),
            goneTimes,
            "Refused at once, a minute later, and five minutes after that; then never again",
        )
        assertEquals(1, transport.ids.count { it == "behind" }, "The play behind it was sent once, at once")
        assertEquals(0, fixture.outbox.count(), "The refused play is gone from the outbox")
        val dropped = fixture.diagnostics.events.filterIsInstance<ScrobbleOutboxDiagnosticEvent.RefusedDropped>().single()
        assertEquals(GONE_ID, dropped.entry.rawId)
        assertEquals(3, dropped.refusals)
        assertEquals(70, dropped.serverCode)
        assertEquals(
            3,
            fixture.diagnostics.events.count {
                it is ScrobbleOutboxDiagnosticEvent.DeliveryFailed || it is ScrobbleOutboxDiagnosticEvent.RefusedDropped
            },
            "Each refusal is a failed attempt in the diagnostics, the last one as the drop",
        )
        assertEquals(0, loop.liveTimers, "Nothing is left waiting")
        fixture.close()
    }

    @Test
    fun aPlayRefusedWhileTheServerRescansIsDeliveredWhenItsTrackIsBack() = runTest {
        val fixture = fixture()
        val clock = OutboxMonotonicClock { testScheduler.currentTime.milliseconds }
        val transport = recordingTransport(clock) { call, _ -> if (call == 1) notFound() else okResponse() }
        fixture.outbox.persistForAtLeastOnceDelivery(play(GONE_ID, 1))
        val loop = retryLoop(fixture, transport, clock)

        loop.drainNow()
        advanceTimeBy(1.minutes.inWholeMilliseconds)
        runCurrent()

        assertEquals(listOf(0.milliseconds, 1.minutes), transport.times)
        assertEquals(0, fixture.outbox.count(), "One refusal never drops a play")
        assertTrue(fixture.diagnostics.events.none { it is ScrobbleOutboxDiagnosticEvent.RefusedDropped })
        fixture.close()
    }

    @Test
    fun onlyAnAnswerAboutThePlayItselfDropsItEveryOtherFailureHoldsTheQueueInOrder() = runTest {
        // Each case is the whole answer the server gives for the first play; the play behind it is
        // always accepted. The refusals are the control: the test must show the condition was met
        // (the play behind IS sent), or the rows that hold the queue prove nothing.
        val refuses: Map<String, () -> AuthenticatedEndpointResponse> = linkedMapOf(
            "error 70" to { envelope(200, 70) },
            "error 70 at HTTP 404" to { envelope(404, 70) },
        )
        val holds: Map<String, () -> AuthenticatedEndpointResponse> = linkedMapOf(
            "error 0" to { envelope(200, 0) },
            "error 10 missing parameter" to { envelope(200, 10) },
            "error 20 client must upgrade" to { envelope(200, 20) },
            "error 30 server must upgrade" to { envelope(200, 30) },
            "error 40 wrong credentials" to { envelope(200, 40) },
            "error 41 token auth unsupported" to { envelope(200, 41) },
            "error 50 not authorised" to { envelope(200, 50) },
            "error 60 trial over" to { envelope(200, 60) },
            "an unknown error code" to { envelope(200, 99) },
            "HTTP 500, no envelope" to { response(500, "oops".encodeToByteArray()) },
            "HTTP 401, no envelope" to { response(401, "".encodeToByteArray()) },
            "HTTP 404, no envelope" to { response(404, "Not Found".encodeToByteArray()) },
            "HTTP 502, no envelope" to { response(502, "".encodeToByteArray()) },
            "a 429 with Retry-After" to { rateLimited("30") },
            "an HTML login page" to { response(200, "<html></html>".encodeToByteArray()) },
        )

        for ((label, answer) in refuses) {
            val outcome = runOne(answer)
            assertEquals(listOf(GONE_ID, "behind"), outcome.ids, "$label: the queue goes on past a refused play")
            assertEquals(1, outcome.delivered, label)
            assertEquals(1, outcome.pending, "$label: one refusal keeps the play for its next try")
            assertEquals(0, outcome.refusedDrops, "$label: one refusal never drops a play")
        }
        for ((label, answer) in holds) {
            val outcome = runOne(answer)
            assertEquals(listOf(GONE_ID), outcome.ids, "$label: the queue stops at the first failure, in order")
            assertEquals(0, outcome.delivered, label)
            assertEquals(2, outcome.pending, label)
            assertEquals(0, outcome.refusedDrops, label)
        }
        // And a hold is never a drop however often it repeats: five drains of an account refusal.
        val fixture = fixture()
        val transport = recordingTransport(fixture.monotonic) { _, _ -> envelope(200, 40) }
        fixture.outbox.persistForAtLeastOnceDelivery(play(GONE_ID, 1))
        val worker = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics)
        repeat(5) {
            assertEquals(0, worker.onForeground().refusedDropCount)
            fixture.monotonic.advanceBy(10.minutes)
        }
        assertEquals(5, transport.ids.size)
        assertEquals(1, fixture.outbox.count(), "Credentials refused: the play waits for a reconnect, never dropped")
        fixture.close()
    }

    private class OneOutcome(val ids: List<String>, val delivered: Int, val refusedDrops: Int, val pending: Int)

    private suspend fun runOne(answer: () -> AuthenticatedEndpointResponse): OneOutcome {
        val fixture = fixture()
        val transport = recordingTransport(fixture.monotonic) { _, parameters ->
            if (parameters["id"] == GONE_ID) answer() else okResponse()
        }
        fixture.outbox.persistForAtLeastOnceDelivery(play(GONE_ID, 1))
        fixture.outbox.persistForAtLeastOnceDelivery(play("behind", 2))
        val result = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics).onForeground()
        val outcome = OneOutcome(transport.ids.toList(), result.deliveredCount, result.refusedDropCount, fixture.outbox.count().toInt())
        fixture.close()
        return outcome
    }

    @Test
    fun aRefusedPlayStillExpiresAtThirtyDaysLikeAnyOther() = runTest {
        val wall = MutableWallClock(CREATED_AT)
        val fixture = fixture(wallClock = wall)
        val transport = recordingTransport(fixture.monotonic) { _, _ -> notFound() }
        fixture.outbox.persistForAtLeastOnceDelivery(play(GONE_ID, 1))
        val worker = worker(fixture.outbox, transport, fixture.monotonic, fixture.diagnostics, wall)

        assertEquals(0, worker.onForeground().refusedDropCount)
        wall.advanceBy(31.days)
        fixture.monotonic.advanceBy(1.minutes)
        val result = worker.onForeground()

        assertEquals(1, result.retentionDropCount, "Past thirty days the retention rule drops it, and says so")
        assertEquals(0, fixture.outbox.count())
        assertEquals(1, fixture.diagnostics.events.count { it is ScrobbleOutboxDiagnosticEvent.ProductRetentionDropped })
        fixture.close()
    }

    private fun TestScope.retryLoop(
        fixture: Fixture,
        transport: RecordingTransport,
        clock: OutboxMonotonicClock,
    ): ScrobbleOutboxRetryLoop {
        val worker = ScrobbleOutboxDeliveryWorker(
            serverId = SERVER_ID,
            outbox = fixture.outbox,
            sender = ScrobbleEndpointSender(transport),
            wallClock = fixture.wallClock,
            monotonicClock = clock,
            diagnosticSink = fixture.diagnostics,
        )
        return ScrobbleOutboxRetryLoop(backgroundScope, { worker.onForeground() })
    }

    private fun recordingTransport(
        clock: OutboxMonotonicClock,
        respond: (call: Int, parameters: Map<String, String>) -> AuthenticatedEndpointResponse,
    ) = RecordingTransport(clock, respond)

    private class RecordingTransport(
        private val clock: OutboxMonotonicClock,
        private val respond: (Int, Map<String, String>) -> AuthenticatedEndpointResponse,
    ) : ScrobbleEndpointTransport {
        val times = mutableListOf<Duration>()
        val ids = mutableListOf<String>()

        override suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse {
            times += clock.now()
            ids += parameters.getValue("id")
            return respond(times.size, parameters)
        }
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
        transport: ScrobbleEndpointTransport,
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
        fun setTo(value: Long) {
            now = value
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

        const val GONE_ID = "track:gone"

        fun play(rawId: String, startOffset: Long) = RecordedPlaybackEvent.SubmittedPlay(
            ProviderItemId(SERVER, rawId),
            PlaybackWallClockTime(SESSION_START + startOffset),
        )

        /** Error 70 as the answer to one play: HTTP 200 with a Subsonic error envelope. */
        fun notFound(): AuthenticatedEndpointResponse = errorOnly(70)

        fun errorOnly(code: Int): AuthenticatedEndpointResponse = envelope(200, code)

        fun envelope(status: Int, code: Int): AuthenticatedEndpointResponse = response(
            status,
            """{"subsonic-response":{"status":"failed","error":{"code":$code}}}""".encodeToByteArray(),
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

        fun rateLimited(retryAfter: String): AuthenticatedEndpointResponse = response(
            429,
            """{"subsonic-response":{"status":"failed","error":{"code":0}}}""".encodeToByteArray(),
            retryAfter,
        )

        fun response(status: Int, body: ByteArray, retryAfter: String? = null): AuthenticatedEndpointResponse =
            AuthenticatedEndpointResponse(
                statusCode = status,
                body = body,
                redactedUrl = "https://music.invalid/rest/scrobble.view?<redacted>",
                headers = AuthenticatedEndpointResponseHeaders(
                    contentType = "application/json",
                    contentLength = PlaybackContentLength.Exact(body.size.toLong()),
                    retryAfter = retryAfter,
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
