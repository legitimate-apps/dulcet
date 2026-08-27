package com.legitimateapps.dulcet.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public data class ScrobbleAccumulatorState(
    val accruedMediaTime: Duration,
    val lastPosition: Duration?,
    val lastMonotonic: PlaybackMonotonicTime?,
    val progressing: Boolean,
    val rate: Double,
    val submitted: Boolean,
    val sessionStartWallClock: PlaybackWallClockTime?,
    val durationKnown: Duration?,
    val progressingTimeSinceNowPlaying: Duration,
    val durationUnknownReported: Boolean,
) {
    public companion object {
        public fun initial(durationKnown: Duration? = null): ScrobbleAccumulatorState {
            requireValidMediaDuration(durationKnown)
            return ScrobbleAccumulatorState(
                accruedMediaTime = Duration.ZERO,
                lastPosition = null,
                lastMonotonic = null,
                progressing = false,
                rate = 1.0,
                submitted = false,
                sessionStartWallClock = null,
                durationKnown = durationKnown,
                progressingTimeSinceNowPlaying = Duration.ZERO,
                durationUnknownReported = false,
            )
        }
    }
}

public sealed interface ScrobbleAccumulatorEvent {
    public data class PlaybackProgressBegan(
        val wallClock: PlaybackWallClockTime,
        val mediaPosition: Duration,
    ) : ScrobbleAccumulatorEvent

    public data class PositionChanged(
        val mediaPosition: Duration,
        val monotonicTime: PlaybackMonotonicTime,
    ) : ScrobbleAccumulatorEvent

    public data class DurationChanged(val duration: Duration) : ScrobbleAccumulatorEvent
    public data class Buffering(val position: Duration) : ScrobbleAccumulatorEvent
    public data class BufferingEnded(val position: Duration) : ScrobbleAccumulatorEvent
    public data class Paused(val position: Duration) : ScrobbleAccumulatorEvent
    public data class Resumed(val position: Duration) : ScrobbleAccumulatorEvent
    public data object InterruptionBegan : ScrobbleAccumulatorEvent
    public data class InterruptionEnded(val shouldResume: Boolean) : ScrobbleAccumulatorEvent

    public data class SeekCompleted(
        val from: Duration,
        val to: Duration,
    ) : ScrobbleAccumulatorEvent

    public data class SeekFailed(
        val from: Duration,
        val to: Duration,
    ) : ScrobbleAccumulatorEvent

    public data class RateChanged(val rate: Double) : ScrobbleAccumulatorEvent
    public data object AttemptReplaced : ScrobbleAccumulatorEvent
    public data class EndedNaturally(val finalPosition: Duration) : ScrobbleAccumulatorEvent
    public data class Skipped(val position: Duration) : ScrobbleAccumulatorEvent
    public data class FailedAfterPartial(val position: Duration) : ScrobbleAccumulatorEvent
    public data object FailedBeforeStart : ScrobbleAccumulatorEvent
    public data object SessionFinalized : ScrobbleAccumulatorEvent
}

public sealed interface ScrobbleAccumulatorEffect {
    public data object NowPlaying : ScrobbleAccumulatorEffect
    public data class SubmittedPlay(val sessionStartWallClock: PlaybackWallClockTime) : ScrobbleAccumulatorEffect
    public data class DiscontinuityDiscarded(val mediaDelta: Duration) : ScrobbleAccumulatorEffect
    public data object DurationUnknownAtTerminal : ScrobbleAccumulatorEffect
}

public data class ScrobbleAccumulatorReduction(
    val state: ScrobbleAccumulatorState,
    val effects: List<ScrobbleAccumulatorEffect>,
)

/**
 * Pure scrobble arithmetic. It reads no clock and performs no I/O; every time value is event data.
 */
public object ScrobbleAccumulator {
    public val cadenceTarget: Duration = 500.milliseconds
    public val cadenceMax: Duration = 2.seconds
    public val nowPlayingInterval: Duration = 1.minutes
    public val minimumEligibleDuration: Duration = 30.seconds
    public val maximumThreshold: Duration = 4.minutes

    public fun reduce(
        state: ScrobbleAccumulatorState,
        event: ScrobbleAccumulatorEvent,
    ): ScrobbleAccumulatorReduction = when (event) {
        is ScrobbleAccumulatorEvent.PlaybackProgressBegan -> {
            requireValidPosition(event.mediaPosition)
            ScrobbleAccumulatorReduction(
                state.copy(
                    lastPosition = event.mediaPosition,
                    lastMonotonic = null,
                    progressing = true,
                    sessionStartWallClock = state.sessionStartWallClock ?: event.wallClock,
                    progressingTimeSinceNowPlaying = Duration.ZERO,
                ),
                listOf(ScrobbleAccumulatorEffect.NowPlaying),
            )
        }

        is ScrobbleAccumulatorEvent.PositionChanged -> reducePosition(state, event)
        is ScrobbleAccumulatorEvent.DurationChanged -> {
            requireValidMediaDuration(event.duration)
            evaluateSubmission(state.copy(durationKnown = event.duration))
        }

        is ScrobbleAccumulatorEvent.Buffering -> suspendAt(state, event.position)
        is ScrobbleAccumulatorEvent.Paused -> suspendAt(state, event.position)
        is ScrobbleAccumulatorEvent.BufferingEnded -> resumeAt(state, event.position)
        is ScrobbleAccumulatorEvent.Resumed -> resumeAt(state, event.position)
        ScrobbleAccumulatorEvent.InterruptionBegan -> reduction(
            state.copy(progressing = false, lastPosition = null, lastMonotonic = null),
        )
        is ScrobbleAccumulatorEvent.InterruptionEnded -> reduction(
            state.copy(
                progressing = event.shouldResume,
                lastPosition = null,
                lastMonotonic = null,
            ),
        )

        is ScrobbleAccumulatorEvent.SeekCompleted -> anchorAt(state, event.to)
        is ScrobbleAccumulatorEvent.SeekFailed -> anchorAt(state, event.from)
        is ScrobbleAccumulatorEvent.RateChanged -> reduction(
            state.copy(rate = event.rate, lastPosition = null, lastMonotonic = null),
        )
        ScrobbleAccumulatorEvent.AttemptReplaced -> reduction(
            state.copy(progressing = false, lastPosition = null, lastMonotonic = null),
        )

        is ScrobbleAccumulatorEvent.EndedNaturally -> evaluateTerminal(state, event.finalPosition)
        is ScrobbleAccumulatorEvent.Skipped -> evaluateTerminal(state, event.position)
        is ScrobbleAccumulatorEvent.FailedAfterPartial -> evaluateTerminal(state, event.position)
        ScrobbleAccumulatorEvent.FailedBeforeStart -> reduction(
            state.copy(progressing = false, lastPosition = null, lastMonotonic = null),
        )
        ScrobbleAccumulatorEvent.SessionFinalized -> evaluateTerminal(state, state.lastPosition)
    }

    public fun thresholdFor(duration: Duration?): Duration? {
        requireValidMediaDuration(duration)
        if (duration == null || duration < minimumEligibleDuration) return null
        return minOf(duration / 2, maximumThreshold)
    }

    private fun reducePosition(
        state: ScrobbleAccumulatorState,
        event: ScrobbleAccumulatorEvent.PositionChanged,
    ): ScrobbleAccumulatorReduction {
        requireValidPosition(event.mediaPosition)
        val previousPosition = state.lastPosition
        val anchored = state.copy(
            lastPosition = event.mediaPosition,
            lastMonotonic = event.monotonicTime,
        )
        if (previousPosition == null || !state.progressing || state.rate <= 0.0) {
            return reduction(anchored)
        }

        val mediaDelta = event.mediaPosition - previousPosition
        if (mediaDelta <= Duration.ZERO) return reduction(anchored)

        // cadenceMax is the adapter's hard guarantee. cadenceTarget is only nominal, so deriving
        // this limit from it would reject legitimate deltas from a conforming slower emitter.
        val maximumPlausibleDelta = cadenceMax * 2 * maxOf(state.rate, 1.0)
        if (mediaDelta > maximumPlausibleDelta) {
            return ScrobbleAccumulatorReduction(
                anchored,
                listOf(ScrobbleAccumulatorEffect.DiscontinuityDiscarded(mediaDelta)),
            )
        }

        var updated = anchored.copy(accruedMediaTime = state.accruedMediaTime + mediaDelta)
        val monotonicDelta = state.lastMonotonic?.let {
            event.monotonicTime.elapsed - it.elapsed
        }
        if (
            monotonicDelta != null &&
            monotonicDelta > Duration.ZERO &&
            monotonicDelta <= cadenceMax * 2
        ) {
            updated = updated.copy(
                progressingTimeSinceNowPlaying =
                    state.progressingTimeSinceNowPlaying + monotonicDelta,
            )
        }

        val submission = evaluateSubmission(updated)
        updated = submission.state
        val effects = submission.effects.toMutableList()
        if (updated.progressingTimeSinceNowPlaying >= nowPlayingInterval) {
            updated = updated.copy(
                progressingTimeSinceNowPlaying =
                    updated.progressingTimeSinceNowPlaying - nowPlayingInterval,
            )
            effects += ScrobbleAccumulatorEffect.NowPlaying
        }
        return ScrobbleAccumulatorReduction(updated, effects)
    }

    private fun evaluateTerminal(
        state: ScrobbleAccumulatorState,
        finalPosition: Duration?,
    ): ScrobbleAccumulatorReduction {
        finalPosition?.let(::requireValidPosition)
        val terminal = state.copy(
            progressing = false,
            lastPosition = finalPosition ?: state.lastPosition,
            lastMonotonic = null,
        )
        val submission = evaluateSubmission(terminal)
        if (submission.state.durationKnown != null || submission.state.durationUnknownReported) {
            return submission
        }
        return ScrobbleAccumulatorReduction(
            submission.state.copy(durationUnknownReported = true),
            submission.effects + ScrobbleAccumulatorEffect.DurationUnknownAtTerminal,
        )
    }

    private fun evaluateSubmission(
        state: ScrobbleAccumulatorState,
    ): ScrobbleAccumulatorReduction {
        val threshold = thresholdFor(state.durationKnown)
        val start = state.sessionStartWallClock
        if (state.submitted || threshold == null || start == null || state.accruedMediaTime < threshold) {
            return reduction(state)
        }
        return ScrobbleAccumulatorReduction(
            state.copy(submitted = true),
            listOf(ScrobbleAccumulatorEffect.SubmittedPlay(start)),
        )
    }

    private fun suspendAt(
        state: ScrobbleAccumulatorState,
        position: Duration,
    ): ScrobbleAccumulatorReduction {
        requireValidPosition(position)
        return reduction(
            state.copy(progressing = false, lastPosition = position, lastMonotonic = null),
        )
    }

    private fun resumeAt(
        state: ScrobbleAccumulatorState,
        position: Duration,
    ): ScrobbleAccumulatorReduction {
        requireValidPosition(position)
        return reduction(
            state.copy(progressing = true, lastPosition = position, lastMonotonic = null),
        )
    }

    private fun anchorAt(
        state: ScrobbleAccumulatorState,
        position: Duration,
    ): ScrobbleAccumulatorReduction {
        requireValidPosition(position)
        return reduction(state.copy(lastPosition = position, lastMonotonic = null))
    }

    private fun reduction(state: ScrobbleAccumulatorState) =
        ScrobbleAccumulatorReduction(state, emptyList())
}

private fun requireValidPosition(position: Duration) {
    require(!position.isNegative() && position.isFinite())
}

private fun requireValidMediaDuration(duration: Duration?) {
    require(duration == null || (!duration.isNegative() && duration.isFinite()))
}

public sealed interface RecordedPlaybackEvent {
    public val itemId: ProviderItemId

    public data class NowPlaying(override val itemId: ProviderItemId) : RecordedPlaybackEvent

    public data class SubmittedPlay(
        override val itemId: ProviderItemId,
        val sessionStartWallClock: PlaybackWallClockTime,
    ) : RecordedPlaybackEvent
}

/** The provider seam consumes core policy effects; v1 has no alternate timeline-reporting path. */
public fun interface PlaybackEventRecorder {
    public suspend fun recordPlaybackEvent(event: RecordedPlaybackEvent)
}
