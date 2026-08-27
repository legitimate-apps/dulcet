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

        assertNull(machine.sessionSnapshot(SESSION_1))
        assertEquals(1, machine.diagnostics.finalizedSessionCount)
        assertIs<PlaybackCoreEffect.AccumulatorDiagnostic>(transition.effects.single())
        assertEquals(PlaybackSessionId("session-2"), machine.currentSession!!.playbackSessionId)
        assertEquals(0.seconds, machine.currentSession!!.accumulator.accruedMediaTime)
    }

    @Test
    fun transitionRepeatOneFinalizesOutgoingAndReusesEntryWithNewSessionAndAttempt() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        machine.repeatOne(PlaybackSessionId("repeat-session"), AttemptId("repeat-attempt"))

        assertNull(machine.sessionSnapshot(SESSION_1))
        assertEquals(1, machine.diagnostics.finalizedSessionCount)
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

        assertNull(machine.sessionSnapshot(SESSION_1))
        assertEquals(1, machine.diagnostics.finalizedSessionCount)
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
        assertNull(machine.sessionSnapshot(SESSION_1))
        assertEquals(1, machine.diagnostics.finalizedSessionCount)
        assertEquals(next.playbackSessionId, machine.currentSession!!.playbackSessionId)
    }

    @Test
    fun observationResyncCarriesSessionStartAndSubmitsAfterAccruingPastThreshold() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = 100.seconds))
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.ObservationResynced(
                ATTEMPT_1,
                PlaybackObservationSnapshot(
                    status = PlaybackObservationStatus.Progressing,
                    mediaPosition = 40.seconds,
                    duration = 100.seconds,
                    seekability = PlaybackSeekability.Seekable,
                    rate = 2.0,
                    sessionStartWallClock = WALL_CLOCK,
                ),
            ),
        )
        val effects = mutableListOf<PlaybackCoreEffect>()
        repeat(13) { index ->
            effects += machine.recordPlaybackEvent(
                positionEvent(ATTEMPT_1, 44 + index * 4, index + 1),
            ).effects
        }
        effects += machine.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(ATTEMPT_1, 92.seconds),
        ).effects

        val current = machine.currentSession!!
        assertEquals(52.seconds, current.accumulator.accruedMediaTime)
        assertTrue(current.accumulator.submitted)
        assertEquals(WALL_CLOCK, current.accumulator.sessionStartWallClock)
        val submitted = effects.single {
            it is PlaybackCoreEffect.RecordPlaybackEvent &&
                it.event is RecordedPlaybackEvent.SubmittedPlay
        } as PlaybackCoreEffect.RecordPlaybackEvent
        assertEquals(
            WALL_CLOCK,
            (submitted.event as RecordedPlaybackEvent.SubmittedPlay).sessionStartWallClock,
        )
    }

    @Test
    fun advancedToPreloadedFromSupersededAttemptStillCreatesSessionBoundary() {
        val machine = progressingMachine()
        machine.recordPlaybackEvent(positionEvent(ATTEMPT_1, 2, 1))
        val next = start("queue-preload", "session-preload", "attempt-preload", 30.seconds)
        machine.registerPreloaded(next)
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(next.attemptId, WALL_CLOCK, 0.seconds),
        )
        listOf(4, 8, 12, 16).forEachIndexed { index, position ->
            machine.recordPlaybackEvent(positionEvent(next.attemptId, position, index + 1))
        }
        assertEquals(2.seconds, machine.currentSession!!.accumulator.accruedMediaTime)
        assertFalse(machine.currentSession!!.accumulator.submitted)
        assertTrue(machine.sessionSnapshot(next.playbackSessionId)!!.accumulator.submitted)
        machine.planRefresh(ATTEMPT_2)

        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(ATTEMPT_1, next.attemptId),
        )

        assertEquals(PlaybackEventDisposition.AcceptedCurrentAttempt, result.disposition)
        assertEquals(next.playbackSessionId, machine.currentSession!!.playbackSessionId)
        assertEquals(16.seconds, machine.currentSession!!.accumulator.accruedMediaTime)
        assertTrue(machine.currentSession!!.accumulator.submitted)
    }

    @Test
    fun readyWithoutDurationPreservesKnownAttemptDuration() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = 100.seconds))
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(ATTEMPT_1, null, PlaybackSeekability.NotSeekable),
        )

        assertEquals(100.seconds, machine.currentSession!!.currentAttempt.duration)
        assertEquals(PlaybackSeekability.NotSeekable, machine.currentSession!!.currentAttempt.seekability)
    }

    @Test
    fun observationResyncWithoutDurationPreservesKnownAttemptDuration() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = 100.seconds))
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.ObservationResynced(
                ATTEMPT_1,
                PlaybackObservationSnapshot(
                    PlaybackObservationStatus.Ready,
                    0.seconds,
                    null,
                    PlaybackSeekability.Seekable,
                    1.0,
                ),
            ),
        )

        assertEquals(100.seconds, machine.currentSession!!.currentAttempt.duration)
    }

    @Test
    fun progressingObservationWithoutOriginalStartTimeIsDiagnosedAndCannotAccrue() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = 100.seconds))
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.ObservationResynced(
                ATTEMPT_1,
                PlaybackObservationSnapshot(
                    PlaybackObservationStatus.Progressing,
                    40.seconds,
                    100.seconds,
                    PlaybackSeekability.Seekable,
                    1.0,
                    sessionStartWallClock = null,
                ),
            ),
        )
        machine.recordPlaybackEvent(positionEvent(ATTEMPT_1, 44, 1))

        assertEquals(1, machine.diagnostics.invalidProgressingResyncCount)
        assertEquals(0.seconds, machine.currentSession!!.accumulator.accruedMediaTime)
        assertFalse(machine.currentSession!!.accumulator.progressing)
        assertNull(machine.currentSession!!.accumulator.sessionStartWallClock)
    }

    @Test
    fun sameSessionAttemptReplacementPreservesSnapshotRate() {
        val machine = progressingMachine()
        machine.recordPlaybackEvent(PlaybackEngineEvent.RateChanged(ATTEMPT_1, 2.0))
        machine.planRefresh(ATTEMPT_2)

        assertEquals(2.0, machine.currentSession!!.accumulator.rate)
        assertEquals(2.0, machine.currentSession!!.currentAttempt.rate)
    }

    @Test
    fun manualAdvancePromotesRegisteredPreloadWithoutThrowingOrDuplicatingSession() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        val next = start("queue-manual", "session-manual", "attempt-manual")
        machine.registerPreloaded(next)

        val result = machine.advanceToNext(next)

        assertIs<PlaybackTransitionResult.Applied>(result)
        assertEquals(next.playbackSessionId, machine.currentSession!!.playbackSessionId)
        assertEquals(1, machine.diagnostics.terminalEvaluationCount)
    }

    @Test
    fun discardedPreloadCannotBecomeAZombieAndUsesDedicatedDiagnostics() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start())
        val next = start("queue-discard", "session-discard", "attempt-discard")
        assertIs<PlaybackTransitionResult.Applied>(machine.registerPreloaded(next))
        assertIs<PlaybackTransitionResult.Applied>(machine.discardPreloaded(next.attemptId))
        assertNull(machine.sessionSnapshot(next.playbackSessionId))

        val advance = machine.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(ATTEMPT_1, next.attemptId),
        )
        val latePreloadEvent = machine.recordPlaybackEvent(
            PlaybackEngineEvent.Preparing(next.attemptId),
        )

        assertEquals(PlaybackEventDisposition.RejectedUnregisteredPreload, advance.disposition)
        assertEquals(1, machine.diagnostics.unregisteredPreloadAdvanceCount)
        assertEquals(0, machine.diagnostics.unknownAttemptDropCount)
        assertEquals(PlaybackEventDisposition.DroppedDiscardedPreload, latePreloadEvent.disposition)
        assertEquals(1, machine.diagnostics.discardedPreloadEventCount)
    }

    @Test
    fun finalizedSessionsAndSupersededCadenceEntriesDoNotAccumulateForever() {
        val machine = progressingMachine()
        var attempt = ATTEMPT_1
        repeat(40) { index ->
            machine.recordPlaybackEvent(positionEvent(attempt, index + 1, index + 1))
            attempt = AttemptId("attempt-refresh-$index")
            machine.planRefresh(attempt)
            machine.recordPlaybackEvent(
                PlaybackEngineEvent.PlaybackProgressBegan(attempt, WALL_CLOCK, 0.seconds),
            )
        }
        machine.recordPlaybackEvent(positionEvent(attempt, 1, 100))
        assertEquals(1, machine.trackedCadenceAttemptCount)
        machine.clearQueue()
        assertEquals(0, machine.retainedSessionStateCount)
        assertEquals(0, machine.trackedAttemptOwnerCount)
        assertEquals(0, machine.trackedCadenceAttemptCount)
    }

    @Test
    fun retiredAttemptDiagnosticsAreBoundedWhileFullSessionStateIsReleased() {
        val machine = PlaybackCoreStateMachine()
        repeat(300) { index ->
            assertIs<PlaybackTransitionResult.Applied>(
                machine.startPlaying(start("queue-$index", "session-$index", "attempt-$index")),
            )
            machine.clearQueue()
        }

        assertEquals(0, machine.retainedSessionStateCount)
        assertEquals(0, machine.trackedAttemptOwnerCount)
        assertEquals(256, machine.retiredAttemptTombstoneCount)
        assertEquals(44, machine.diagnostics.retiredAttemptTombstoneEvictionCount)
    }

    @Test
    fun supersededTerminalEvaluatesSessionDiagnosticsWithoutChangingCurrentAnchors() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = null))
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT_1, WALL_CLOCK, 0.seconds),
        )
        machine.planRefresh(ATTEMPT_2)
        machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT_2, WALL_CLOCK, 100.seconds),
        )

        val result = machine.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(ATTEMPT_1, 4.seconds),
        )

        assertEquals(PlaybackEventDisposition.AcceptedSupersededSessionOnly, result.disposition)
        assertIs<PlaybackCoreEffect.AccumulatorDiagnostic>(result.effects.single())
        assertEquals(1, machine.diagnostics.durationUnknownTerminalCount)
        assertEquals(1, machine.diagnostics.terminalEvaluationCount)
        assertEquals(100.seconds, machine.currentSession!!.accumulator.lastPosition)
        assertTrue(machine.currentSession!!.accumulator.progressing)
    }

    @Test
    fun engineTornDownFinalizesSessionAndEvaluatesTerminalExactlyOnce() {
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(start(initialDuration = null))

        val tornDown = machine.recordPlaybackEvent(
            PlaybackEngineEvent.EngineTornDown(
                ATTEMPT_1,
                PlaybackEngineTeardownReason.SystemReclaimed,
            ),
        )
        val laterFinalize = machine.clearQueue()

        assertNull(machine.currentSession)
        assertEquals(1, machine.diagnostics.terminalEvaluationCount)
        assertEquals(1, machine.diagnostics.durationUnknownTerminalCount)
        assertEquals(1, tornDown.effects.size)
        assertTrue(laterFinalize.effects.isEmpty())
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
