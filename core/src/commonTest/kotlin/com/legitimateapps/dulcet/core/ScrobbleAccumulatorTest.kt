package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ScrobbleAccumulatorTest {
    @Test
    fun positiveDeltasAccrueAndBackwardDeltaOnlyResetsTheAnchor() {
        var reduction = started(duration = 120.seconds, position = 10.seconds)
        reduction = reduce(reduction.state, position(12, 1))
        assertEquals(2.seconds, reduction.state.accruedMediaTime)

        reduction = reduce(reduction.state, position(8, 2))
        assertEquals(2.seconds, reduction.state.accruedMediaTime)
        assertEquals(8.seconds, reduction.state.lastPosition)

        reduction = reduce(reduction.state, position(10, 3))
        assertEquals(4.seconds, reduction.state.accruedMediaTime)
    }

    @Test
    fun discontinuityBoundaryAccruesAtFourSecondsAndDiscardsImmediatelyAboveIt() {
        val atBoundary = reduce(started(120.seconds).state, position(4, 1))
        assertEquals(4.seconds, atBoundary.state.accruedMediaTime)
        assertTrue(atBoundary.effects.none { it is ScrobbleAccumulatorEffect.DiscontinuityDiscarded })

        val aboveBoundary = reduce(
            started(120.seconds).state,
            ScrobbleAccumulatorEvent.PositionChanged(
                4_001.milliseconds,
                PlaybackMonotonicTime(1.seconds),
            ),
        )
        assertEquals(Duration.ZERO, aboveBoundary.state.accruedMediaTime)
        assertEquals(
            4_001.milliseconds,
            assertIs<ScrobbleAccumulatorEffect.DiscontinuityDiscarded>(
                aboveBoundary.effects.single(),
            ).mediaDelta,
        )
    }

    @Test
    fun bufferingPauseAndInterruptionGapsNeverAccrueRetroactively() {
        var reduction = reduce(started(120.seconds).state, position(2, 1))
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.Buffering(2.seconds))
        reduction = reduce(reduction.state, position(20, 20))
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.BufferingEnded(20.seconds))
        reduction = reduce(reduction.state, position(22, 21))

        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.Paused(22.seconds))
        reduction = reduce(reduction.state, position(40, 40))
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.Resumed(40.seconds))
        reduction = reduce(reduction.state, position(42, 41))

        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.InterruptionBegan)
        reduction = reduce(reduction.state, position(60, 60))
        reduction = reduce(
            reduction.state,
            ScrobbleAccumulatorEvent.InterruptionEnded(shouldResume = true),
        )
        reduction = reduce(reduction.state, position(60, 61))
        reduction = reduce(reduction.state, position(62, 62))

        assertEquals(8.seconds, reduction.state.accruedMediaTime)
    }

    @Test
    fun mediaTimeAccrualHonorsRateAndNonPositiveRatesAccrueNothing() {
        var reduction = started(120.seconds)
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.RateChanged(2.0))
        reduction = reduce(reduction.state, position(0, 0))
        reduction = reduce(reduction.state, position(8, 4))
        assertEquals(8.seconds, reduction.state.accruedMediaTime)

        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.RateChanged(0.0))
        reduction = reduce(reduction.state, position(10, 5))
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.RateChanged(-1.0))
        reduction = reduce(reduction.state, position(12, 6))
        assertEquals(8.seconds, reduction.state.accruedMediaTime)
    }

    @Test
    fun thresholdRequiresThirtySecondsAndIsHalfDurationCappedAtFourMinutes() {
        assertEquals(null, ScrobbleAccumulator.thresholdFor(null))
        assertEquals(null, ScrobbleAccumulator.thresholdFor(29.seconds))
        assertEquals(15.seconds, ScrobbleAccumulator.thresholdFor(30.seconds))
        assertEquals(2.minutes, ScrobbleAccumulator.thresholdFor(4.minutes))
        assertEquals(4.minutes, ScrobbleAccumulator.thresholdFor(20.minutes))

        val ineligible = eligibleState(duration = 29.seconds, accrued = 29.seconds)
        assertFalse(reduce(ineligible, ScrobbleAccumulatorEvent.SessionFinalized).state.submitted)
    }

    @Test
    fun durationChangingDownwardCanSubmitAndChangingUpwardAfterSubmissionNeverRetracts() {
        val beforeChange = eligibleState(duration = 60.seconds, accrued = 16.seconds)
        val downward = reduce(
            beforeChange,
            ScrobbleAccumulatorEvent.DurationChanged(30.seconds),
        )
        assertTrue(downward.state.submitted)
        assertIs<ScrobbleAccumulatorEffect.SubmittedPlay>(downward.effects.single())

        val upward = reduce(
            downward.state,
            ScrobbleAccumulatorEvent.DurationChanged(120.seconds),
        )
        assertTrue(upward.state.submitted)
        assertTrue(upward.effects.isEmpty())
    }

    @Test
    fun decodedDurationCrossingThirtySecondsChangesEligibilityInBothDirectionsPreSubmission() {
        val serverSaidShort = eligibleState(duration = 29.seconds, accrued = 16.seconds)
        val decodedLonger = reduce(
            serverSaidShort,
            ScrobbleAccumulatorEvent.DurationChanged(31.seconds),
        )
        assertTrue(decodedLonger.state.submitted)

        val serverSaidEligible = eligibleState(duration = 31.seconds, accrued = 14.seconds)
        val decodedShorter = reduce(
            serverSaidEligible,
            ScrobbleAccumulatorEvent.DurationChanged(29.seconds),
        )
        val terminal = reduce(decodedShorter.state, ScrobbleAccumulatorEvent.SessionFinalized)
        assertFalse(terminal.state.submitted)
        assertTrue(terminal.effects.none { it is ScrobbleAccumulatorEffect.SubmittedPlay })
    }

    @Test
    fun durationUnknownTerminalIsDiagnosedOnceAndNeverSubmits() {
        var reduction = started(duration = null)
        reduction = reduce(reduction.state, position(4, 1))
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.EndedNaturally(4.seconds))
        assertFalse(reduction.state.submitted)
        assertEquals(listOf(ScrobbleAccumulatorEffect.DurationUnknownAtTerminal), reduction.effects)

        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.SessionFinalized)
        assertTrue(reduction.effects.isEmpty())
    }

    @Test
    fun everyEligibleTerminalEvaluatesThresholdButFailedBeforeStartNeverDoes() {
        val terminals = listOf<ScrobbleAccumulatorEvent>(
            ScrobbleAccumulatorEvent.EndedNaturally(20.seconds),
            ScrobbleAccumulatorEvent.Skipped(20.seconds),
            ScrobbleAccumulatorEvent.FailedAfterPartial(20.seconds),
            ScrobbleAccumulatorEvent.SessionFinalized,
        )
        terminals.forEach { event ->
            val result = reduce(eligibleState(30.seconds, 15.seconds), event)
            assertTrue(result.state.submitted, event.toString())
            assertIs<ScrobbleAccumulatorEffect.SubmittedPlay>(result.effects.single())
        }

        val failedBeforeStart = reduce(
            eligibleState(30.seconds, 15.seconds),
            ScrobbleAccumulatorEvent.FailedBeforeStart,
        )
        assertFalse(failedBeforeStart.state.submitted)
        assertTrue(failedBeforeStart.effects.isEmpty())
    }

    @Test
    fun suspensionMidTrackIsARejectedDiscontinuityThenAccrualResumesFromNewAnchor() {
        var reduction = reduce(started(120.seconds).state, position(2, 1))
        reduction = reduce(reduction.state, position(50, 100))
        assertIs<ScrobbleAccumulatorEffect.DiscontinuityDiscarded>(reduction.effects.single())
        reduction = reduce(reduction.state, position(52, 102))
        assertEquals(4.seconds, reduction.state.accruedMediaTime)
    }

    @Test
    fun seekToNinetyNinePercentCannotCheatCompletion() {
        var reduction = reduce(started(100.seconds).state, position(2, 1))
        reduction = reduce(
            reduction.state,
            ScrobbleAccumulatorEvent.SeekCompleted(2.seconds, 99.seconds),
        )
        reduction = reduce(reduction.state, position(100, 2))
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.EndedNaturally(100.seconds))

        assertEquals(3.seconds, reduction.state.accruedMediaTime)
        assertFalse(reduction.state.submitted)
    }

    @Test
    fun nowPlayingStartsOnProgressAndRepeatsAfterSixtyObservedProgressingSeconds() {
        var reduction = started(10.minutes)
        assertEquals(listOf(ScrobbleAccumulatorEffect.NowPlaying), reduction.effects)
        for (sample in 1..16) {
            reduction = reduce(
                reduction.state,
                position(sample * 4, sample * 4),
            )
        }
        assertEquals(listOf(ScrobbleAccumulatorEffect.NowPlaying), reduction.effects)
        assertEquals(Duration.ZERO, reduction.state.progressingTimeSinceNowPlaying)
    }

    @Test
    fun nowPlayingCadenceExcludesPausedAndBufferingMonotonicGaps() {
        var state = eligibleState(10.minutes, Duration.ZERO).copy(
            progressingTimeSinceNowPlaying = 59.seconds,
            lastPosition = Duration.ZERO,
            lastMonotonic = PlaybackMonotonicTime(Duration.ZERO),
        )
        var reduction = reduce(state, ScrobbleAccumulatorEvent.Paused(Duration.ZERO))
        reduction = reduce(reduction.state, ScrobbleAccumulatorEvent.Resumed(Duration.ZERO))
        reduction = reduce(reduction.state, position(1, 100))
        assertTrue(reduction.effects.isEmpty())
        reduction = reduce(reduction.state, position(2, 101))
        assertEquals(listOf(ScrobbleAccumulatorEffect.NowPlaying), reduction.effects)
    }

    private companion object {
        val START_WALL_CLOCK = PlaybackWallClockTime(1_777_777_777_000)

        fun started(
            duration: Duration?,
            position: Duration = Duration.ZERO,
        ): ScrobbleAccumulatorReduction = ScrobbleAccumulator.reduce(
            ScrobbleAccumulatorState.initial(duration),
            ScrobbleAccumulatorEvent.PlaybackProgressBegan(
                START_WALL_CLOCK,
                position,
            ),
        )

        fun eligibleState(duration: Duration, accrued: Duration) =
            ScrobbleAccumulatorState.initial(duration).copy(
                accruedMediaTime = accrued,
                progressing = true,
                sessionStartWallClock = START_WALL_CLOCK,
            )

        fun position(positionSeconds: Int, monotonicSeconds: Int) =
            ScrobbleAccumulatorEvent.PositionChanged(
                positionSeconds.seconds,
                PlaybackMonotonicTime(monotonicSeconds.seconds),
            )

        fun reduce(
            state: ScrobbleAccumulatorState,
            event: ScrobbleAccumulatorEvent,
        ) = ScrobbleAccumulator.reduce(state, event)
    }
}
