package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PlaybackCoreStateMachineTest {
    @Test
    fun transitionStartPlayingCreatesDistinctOpaqueIdentitiesAndZeroAccumulator() {
        val machine = PlaybackCoreStateMachine()
        val start = start("queue:not-a-number", "session:not-a-number", "attempt:not-a-number")
        machine.startPlaying(start)

        val current = machine.currentSession!!
        assertEquals(start.queueEntryId, current.queueEntryId)
        assertEquals(start.playbackSessionId, current.playbackSessionId)
        assertEquals(start.attemptId, current.currentAttempt.attemptId)
        assertEquals("queue:not-a-number", current.queueEntryId.value)
        assertEquals("session:not-a-number", current.playbackSessionId.value)
        assertEquals("attempt:not-a-number", current.currentAttempt.attemptId.value)
        assertEquals(0.seconds, current.accumulator.accruedMediaTime)
        assertNotEquals(current.queueEntryId.toString(), current.playbackSessionId.toString())
    }

    @Test
    fun transitionPlanRefreshKeepsSessionAndAccumulatorButCreatesAttempt() {
        val machine = progressingMachine()
        accrueTwoSeconds(machine, ATTEMPT_1)
        machine.planRefresh(ATTEMPT_2)

        val current = machine.currentSession!!
        assertEquals(SESSION_1, current.playbackSessionId)
        assertEquals(ATTEMPT_2, current.currentAttempt.attemptId)
        assertEquals(2.seconds, current.accumulator.accruedMediaTime)
    }

    @Test
    fun transitionRetryAfterFailedBeforeStartKeepsZeroAccumulatorAndSession() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.FailedBeforeStart(ATTEMPT_1, DomainError.Transport.Unreachable),
        )
        machine.retryAfterFailedBeforeStart(ATTEMPT_2)

        val current = machine.currentSession!!
        assertEquals(SESSION_1, current.playbackSessionId)
        assertEquals(ATTEMPT_2, current.currentAttempt.attemptId)
        assertEquals(0.seconds, current.accumulator.accruedMediaTime)
    }

    @Test
    fun transitionServerOffsetSeekKeepsSessionAndAccruedMediaTime() {
        val machine = progressingMachine()
        accrueTwoSeconds(machine, ATTEMPT_1)
        machine.serverOffsetSeek(ATTEMPT_2)

        val current = machine.currentSession!!
        assertEquals(SESSION_1, current.playbackSessionId)
        assertEquals(ATTEMPT_2, current.currentAttempt.attemptId)
        assertEquals(2.seconds, current.accumulator.accruedMediaTime)
    }

    @Test
    fun transitionNextQueueItemFinalizesOutgoingThenStartsNewAtZero() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = null))
        val transition = machine.advanceToNext(
            start("queue-2", "session-2", "attempt-2", initialDuration = 60.seconds),
        )

        assertTrue(machine.sessionSnapshot(SESSION_1)!!.finalized)
        assertIs<PlaybackCoreEffect.AccumulatorDiagnostic>(transition.effects.single())
        assertEquals(PlaybackSessionId("session-2"), machine.currentSession!!.playbackSessionId)
        assertEquals(0.seconds, machine.currentSession!!.accumulator.accruedMediaTime)
    }

    @Test
    fun transitionRepeatOneFinalizesOutgoingAndReusesEntryWithNewSessionAndAttempt() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        machine.repeatOne(PlaybackSessionId("repeat-session"), AttemptId("repeat-attempt"))

        assertTrue(machine.sessionSnapshot(SESSION_1)!!.finalized)
        val repeated = machine.currentSession!!
        assertEquals(QUEUE_1, repeated.queueEntryId)
        assertEquals(PlaybackSessionId("repeat-session"), repeated.playbackSessionId)
        assertEquals(AttemptId("repeat-attempt"), repeated.currentAttempt.attemptId)
        assertEquals(0.seconds, repeated.accumulator.accruedMediaTime)
    }

    @Test
    fun transitionQueueReplacementFinalizesOutgoingBeforeNewHead() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        machine.replaceQueue(start("replacement-entry", "replacement-session", "replacement-attempt"))

        assertTrue(machine.sessionSnapshot(SESSION_1)!!.finalized)
        assertEquals(QueueEntryId("replacement-entry"), machine.currentSession!!.queueEntryId)
        assertEquals(AttemptId("replacement-attempt"), machine.currentSession!!.currentAttempt.attemptId)
    }

    @Test
    fun supersededAttemptTerminalRoutesToSessionWithoutMutatingCurrentAttemptState() {
        val machine = progressingMachine()
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.AttemptReplaced(ATTEMPT_1, ATTEMPT_2),
        )
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT_2, WALL_CLOCK, 100.seconds),
        )

        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.FailedAfterPartial(
                ATTEMPT_1,
                5.seconds,
                DomainError.Transport.Unreachable,
            ),
        )

        assertEquals(PlaybackEventDisposition.AcceptedSupersededSessionOnly, result.disposition)
        val current = machine.currentSession!!
        assertEquals(ATTEMPT_2, current.currentAttempt.attemptId)
        assertEquals(PlaybackAttemptPhase.Progressing, current.currentAttempt.phase)
        assertEquals(100.seconds, current.accumulator.lastPosition)
        assertTrue(current.accumulator.progressing)
        assertEquals(
            PlaybackTerminalOutcome.FailedAfterPartial(ATTEMPT_1),
            current.terminalOutcomes.single(),
        )
    }

    @Test
    fun lateAttemptReplacedFromSupersededAttemptCannotRollCurrentAttemptBackward() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        machine.planRefresh(ATTEMPT_2)
        machine.planRefresh(ATTEMPT_3)

        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.AttemptReplaced(ATTEMPT_1, ATTEMPT_2),
        )

        assertEquals(PlaybackEventDisposition.AcceptedSupersededSessionOnly, result.disposition)
        assertEquals(ATTEMPT_3, machine.currentSession!!.currentAttempt.attemptId)
    }

    @Test
    fun finalizedSessionEventIsDroppedAndCounted() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        machine.advanceToNext(start("queue-2", "session-2", "attempt-2"))

        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.Paused(ATTEMPT_1, 3.seconds),
        )

        assertEquals(PlaybackEventDisposition.DroppedFinalizedSession, result.disposition)
        assertEquals(1, machine.diagnostics.finalizedSessionDropCount)
        assertEquals(0, machine.diagnostics.unknownAttemptDropCount)
    }

    @Test
    fun neverSeenAttemptIsDroppedAndCountedSeparately() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.Preparing(AttemptId("adapter-bug-attempt")),
        )

        assertEquals(PlaybackEventDisposition.DroppedUnknownAttempt, result.disposition)
        assertEquals(1, machine.diagnostics.unknownAttemptDropCount)
        assertEquals(0, machine.diagnostics.finalizedSessionDropCount)
    }

    @Test
    fun transcodeOffsetAttemptReplacedPreservesAccrualAndReanchorsAtOffset() {
        val machine = progressingMachine()
        accrueTwoSeconds(machine, ATTEMPT_1)
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.AttemptReplaced(ATTEMPT_1, ATTEMPT_2),
        )
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT_2, WALL_CLOCK, 100.seconds),
        )
        machine.recordPlaybackEvent(positionEvent(ATTEMPT_2, 102, 2))

        assertEquals(4.seconds, machine.currentSession!!.accumulator.accruedMediaTime)
        assertEquals(SESSION_1, machine.currentSession!!.playbackSessionId)
    }

    @Test
    fun gaplessAdvancedToRegisteredPreloadFinalizesOldSessionAndActivatesNew() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        val next = start("queue-2", "session-2", "attempt-2")
        machine.registerPreloaded(next)

        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(ATTEMPT_1, next.attemptId),
        )

        assertEquals(PlaybackEventDisposition.AcceptedCurrentAttempt, result.disposition)
        assertTrue(machine.sessionSnapshot(SESSION_1)!!.finalized)
        assertEquals(next.playbackSessionId, machine.currentSession!!.playbackSessionId)
    }

    @Test
    fun observationResyncRestoresProgressionAnchorsWithoutClaimingAPlaybackStart() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = null))
        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.ObservationResynced(
                ATTEMPT_1,
                PlaybackObservationSnapshot(
                    status = PlaybackObservationStatus.Progressing,
                    mediaPosition = 40.seconds,
                    duration = 100.seconds,
                    seekability = PlaybackSeekability.Seekable,
                    rate = 2.0,
                ),
            ),
        )

        assertTrue(result.effects.isEmpty())
        val current = machine.currentSession!!
        assertEquals(PlaybackAttemptPhase.Progressing, current.currentAttempt.phase)
        assertTrue(current.accumulator.progressing)
        assertEquals(40.seconds, current.accumulator.lastPosition)
        assertEquals(100.seconds, current.accumulator.durationKnown)
        assertEquals(2.0, current.accumulator.rate)
        assertNull(current.accumulator.sessionStartWallClock)
    }

    private companion object {
        val QUEUE_1 = QueueEntryId("queue-1")
        val SESSION_1 = PlaybackSessionId("session-1")
        val ATTEMPT_1 = AttemptId("attempt-1")
        val ATTEMPT_2 = AttemptId("attempt-2")
        val ATTEMPT_3 = AttemptId("attempt-3")
        val WALL_CLOCK = PlaybackWallClockTime(1_777_777_777_000)

        fun start(
            queue: String = QUEUE_1.value,
            session: String = SESSION_1.value,
            attempt: String = ATTEMPT_1.value,
            initialDuration: kotlin.time.Duration? = 120.seconds,
        ) = PlaybackSessionStart(
            QueueEntryId(queue),
            PlaybackSessionId(session),
            AttemptId(attempt),
            ProviderItemId("provider-fixture", "track:opaque"),
            initialDuration,
        )

        fun progressingMachine(): PlaybackCoreStateMachine = PlaybackCoreStateMachine().also {
            it.startPlaying(start())
            it.recordPlaybackEvent(
                PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT_1, WALL_CLOCK, 0.seconds),
            )
        }

        fun accrueTwoSeconds(machine: PlaybackCoreStateMachine, attemptId: AttemptId) {
            machine.recordPlaybackEvent(positionEvent(attemptId, 2, 1))
        }

        fun positionEvent(
            attemptId: AttemptId,
            positionSeconds: Int,
            monotonicSeconds: Int,
        ) = PlaybackEngineEvent.PositionChanged(
            attemptId,
            positionSeconds.seconds,
            PlaybackMonotonicTime(monotonicSeconds.seconds),
        )
    }
}

class PositionCadenceCoalescerTest {
    @Test
    fun coalescesToTargetPerAttemptAndReanchorsAResetMonotonicClock() {
        val attempt = AttemptId("attempt:cadence")
        var state = PositionCadenceState()

        var result = PositionCadenceCoalescer.reduce(state, event(attempt, 0, 0))
        assertIs<PlaybackEngineEvent.PositionChanged>(result.eventToEmit)
        state = result.state

        result = PositionCadenceCoalescer.reduce(state, event(attempt, 200, 200))
        assertNull(result.eventToEmit)

        result = PositionCadenceCoalescer.reduce(state, event(attempt, 500, 500))
        assertIs<PlaybackEngineEvent.PositionChanged>(result.eventToEmit)
        state = result.state

        result = PositionCadenceCoalescer.reduce(state, event(attempt, 700, 700))
        assertNull(result.eventToEmit)

        result = PositionCadenceCoalescer.reduce(state, event(attempt, 1_100, 1_100))
        assertIs<PlaybackEngineEvent.PositionChanged>(result.eventToEmit)
        state = result.state

        result = PositionCadenceCoalescer.reduce(state, event(attempt, 100, 100))
        assertIs<PlaybackEngineEvent.PositionChanged>(result.eventToEmit)
    }

    private fun event(attempt: AttemptId, positionMs: Int, monotonicMs: Int) =
        PlaybackEngineEvent.PositionChanged(
            attempt,
            positionMs.milliseconds,
            PlaybackMonotonicTime(monotonicMs.milliseconds),
        )
}
