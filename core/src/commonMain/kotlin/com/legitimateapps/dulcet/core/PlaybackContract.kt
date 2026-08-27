package com.legitimateapps.dulcet.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Opaque queue identity. Its value is never interpreted as a number. */
internal data class QueueEntryId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Opaque identity for one play of one queue entry. */
internal data class PlaybackSessionId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Opaque identity for one resolved plan handed to the playback engine. */
internal data class AttemptId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Opaque correlation identity for one asynchronous engine command. */
internal data class PlaybackCommandId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/**
 * A transient monotonic instant supplied by an adapter. Playback types stay internal and are not
 * part of a persistence schema; the instant's only valid use is subtraction from the same clock.
 */
internal data class PlaybackMonotonicTime(public val elapsed: Duration)

/** Wall-clock epoch milliseconds used only as a scrobble session-start timestamp. */
internal data class PlaybackWallClockTime(public val epochMilliseconds: Long)

/** Marker implemented by the resolved-plan contract in the platform-adapter slice. */
internal interface PlaybackPlan

internal sealed interface PlaybackCommand {
    public val commandId: PlaybackCommandId

    public data class Prepare(
        override val commandId: PlaybackCommandId,
        val attemptId: AttemptId,
        val plan: PlaybackPlan,
    ) : PlaybackCommand

    public data class Play(override val commandId: PlaybackCommandId) : PlaybackCommand
    public data class Pause(override val commandId: PlaybackCommandId) : PlaybackCommand
    public data class Stop(override val commandId: PlaybackCommandId) : PlaybackCommand

    public data class Seek(
        override val commandId: PlaybackCommandId,
        val position: Duration,
    ) : PlaybackCommand

    public data class SetVolume(
        override val commandId: PlaybackCommandId,
        val volume: Double,
    ) : PlaybackCommand

    public data class SetRate(
        override val commandId: PlaybackCommandId,
        val rate: Double,
    ) : PlaybackCommand

    public data class ReplaceCurrent(
        override val commandId: PlaybackCommandId,
        val attemptId: AttemptId,
        val plan: PlaybackPlan,
    ) : PlaybackCommand

    public data class PreloadNext(
        override val commandId: PlaybackCommandId,
        val attemptId: AttemptId,
        val plan: PlaybackPlan,
    ) : PlaybackCommand

    public data class Release(override val commandId: PlaybackCommandId) : PlaybackCommand
}

internal sealed interface PlaybackCommandRejectionReason {
    public data object InvalidState : PlaybackCommandRejectionReason
    public data object Unsupported : PlaybackCommandRejectionReason
    public data object EngineReleased : PlaybackCommandRejectionReason
    public data class Failed(val error: DomainError) : PlaybackCommandRejectionReason
}

/** Result details are adapter-defined until a command needs a normative result payload. */
internal interface PlaybackCommandResult

internal data object PlaybackCommandCompletedWithoutData : PlaybackCommandResult

/** Models the three normal outcomes; engine implementations must map failures instead of throwing. */
internal sealed interface PlaybackCommandOutcome {
    public val commandId: PlaybackCommandId

    public data class CommandAccepted(
        override val commandId: PlaybackCommandId,
    ) : PlaybackCommandOutcome

    public data class CommandRejected(
        override val commandId: PlaybackCommandId,
        val reason: PlaybackCommandRejectionReason,
    ) : PlaybackCommandOutcome

    public data class CommandCompleted(
        override val commandId: PlaybackCommandId,
        val result: PlaybackCommandResult,
    ) : PlaybackCommandOutcome
}

internal fun interface PlaybackEngineEventListener {
    public fun onPlaybackEngineEvent(event: PlaybackEngineEvent)
}

/** Platform adapters execute commands and publish observations; retry policy remains in the core. */
internal interface PlaybackEngine {
    public suspend fun execute(command: PlaybackCommand): PlaybackCommandOutcome
    public fun setEventListener(listener: PlaybackEngineEventListener)
}

internal enum class PlaybackSeekability {
    Seekable,
    NotSeekable,
    Unknown,
}

internal enum class PlaybackSkipReason {
    User,
    AutoAdvance,
    QueueReplacement,
}

internal enum class PlaybackRouteKind {
    BuiltIn,
    Wired,
    Bluetooth,
    Hdmi,
    Remote,
    Unknown,
}

internal enum class PlaybackEngineTeardownReason {
    BackgroundLimit,
    Lifecycle,
    SystemReclaimed,
    Released,
    Unknown,
}

internal enum class PlaybackSourceRefreshReason {
    Unauthorized,
    Expired,
    ValidationFailed,
}

internal enum class PlaybackObservationStatus {
    Preparing,
    Ready,
    Progressing,
    Buffering,
    Paused,
    Stopped,
    Failed,
}

internal data class PlaybackObservationSnapshot(
    val status: PlaybackObservationStatus,
    val mediaPosition: Duration?,
    val duration: Duration?,
    val seekability: PlaybackSeekability,
    val rate: Double,
    /** Original progression-start time, required when [status] is [PlaybackObservationStatus.Progressing]. */
    val sessionStartWallClock: PlaybackWallClockTime? = null,
)

/** Every event is correlated to an attempt, including global-looking route and lifecycle events. */
internal sealed interface PlaybackEngineEvent {
    public val attemptId: AttemptId

    public data class Preparing(override val attemptId: AttemptId) : PlaybackEngineEvent

    public data class Ready(
        override val attemptId: AttemptId,
        val duration: Duration?,
        val seekability: PlaybackSeekability,
    ) : PlaybackEngineEvent

    public data class PlaybackProgressBegan(
        override val attemptId: AttemptId,
        val wallClock: PlaybackWallClockTime,
        val mediaPosition: Duration,
    ) : PlaybackEngineEvent

    public data class Buffering(
        override val attemptId: AttemptId,
        val position: Duration,
    ) : PlaybackEngineEvent

    public data class BufferingEnded(
        override val attemptId: AttemptId,
        val position: Duration,
    ) : PlaybackEngineEvent

    public data class Paused(
        override val attemptId: AttemptId,
        val position: Duration,
    ) : PlaybackEngineEvent

    public data class Resumed(
        override val attemptId: AttemptId,
        val position: Duration,
    ) : PlaybackEngineEvent

    public data class PositionChanged(
        override val attemptId: AttemptId,
        val mediaPosition: Duration,
        val monotonicTime: PlaybackMonotonicTime,
    ) : PlaybackEngineEvent

    public data class DurationChanged(
        override val attemptId: AttemptId,
        val duration: Duration,
    ) : PlaybackEngineEvent

    public data class SeekCompleted(
        override val attemptId: AttemptId,
        val from: Duration,
        val to: Duration,
    ) : PlaybackEngineEvent

    public data class SeekFailed(
        override val attemptId: AttemptId,
        val from: Duration,
        val to: Duration,
    ) : PlaybackEngineEvent

    public data class EndedNaturally(
        override val attemptId: AttemptId,
        val finalPosition: Duration,
    ) : PlaybackEngineEvent

    public data class Skipped(
        override val attemptId: AttemptId,
        val position: Duration,
        val reason: PlaybackSkipReason,
    ) : PlaybackEngineEvent

    public data class FailedBeforeStart(
        override val attemptId: AttemptId,
        val error: DomainError,
    ) : PlaybackEngineEvent

    public data class FailedAfterPartial(
        override val attemptId: AttemptId,
        val position: Duration,
        val error: DomainError,
    ) : PlaybackEngineEvent

    public data class RouteChanged(
        override val attemptId: AttemptId,
        val old: PlaybackRouteKind,
        val new: PlaybackRouteKind,
        val didPause: Boolean,
    ) : PlaybackEngineEvent

    public data class InterruptionBegan(
        override val attemptId: AttemptId,
        val shouldResume: Boolean,
    ) : PlaybackEngineEvent

    public data class InterruptionEnded(
        override val attemptId: AttemptId,
        val shouldResume: Boolean,
    ) : PlaybackEngineEvent

    public data class AttemptReplaced(
        val oldAttemptId: AttemptId,
        val newAttemptId: AttemptId,
    ) : PlaybackEngineEvent {
        override val attemptId: AttemptId = oldAttemptId
    }

    public data class AdvancedToPreloaded(
        val oldAttemptId: AttemptId,
        val newAttemptId: AttemptId,
    ) : PlaybackEngineEvent {
        override val attemptId: AttemptId = oldAttemptId
    }

    public data class RateChanged(
        override val attemptId: AttemptId,
        val rate: Double,
    ) : PlaybackEngineEvent

    public data class EngineTornDown(
        override val attemptId: AttemptId,
        val reason: PlaybackEngineTeardownReason,
    ) : PlaybackEngineEvent

    public data class SourceRefreshRequired(
        override val attemptId: AttemptId,
        val reason: PlaybackSourceRefreshReason,
    ) : PlaybackEngineEvent

    public data class ObservationResynced(
        override val attemptId: AttemptId,
        val snapshot: PlaybackObservationSnapshot,
    ) : PlaybackEngineEvent
}

internal sealed interface PlaybackRetryDecision {
    public data class RetryAfter(val delay: Duration) : PlaybackRetryDecision
    public data object SurfaceFailure : PlaybackRetryDecision
}

/** Pure retry arithmetic. The caller supplies non-negative jitter, so this policy reads no clock or RNG. */
internal object PlaybackRetryPolicy {
    public val maximumTotalWait: Duration = 60.seconds

    public fun decide(
        error: DomainError,
        scheduledBackoff: Duration,
        jitter: Duration,
        totalWaited: Duration,
    ): PlaybackRetryDecision {
        require(!scheduledBackoff.isNegative() && scheduledBackoff.isFinite())
        require(!jitter.isNegative() && jitter.isFinite())
        require(!totalWaited.isNegative() && totalWaited.isFinite())

        val retryFloor = when (error) {
            is DomainError.Server.Busy -> error.retryAfter ?: scheduledBackoff
            DomainError.Transport.Unreachable,
            DomainError.Transport.Timeout,
            -> scheduledBackoff
            else -> return PlaybackRetryDecision.SurfaceFailure
        }
        val remaining = maximumTotalWait - totalWaited
        if (remaining <= Duration.ZERO) return PlaybackRetryDecision.SurfaceFailure
        if (retryFloor > remaining) return PlaybackRetryDecision.SurfaceFailure
        return PlaybackRetryDecision.RetryAfter(minOf(retryFloor + jitter, remaining))
    }
}
