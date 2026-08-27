package com.legitimateapps.dulcet.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Opaque queue identity. Its value is never interpreted as a number. */
public data class QueueEntryId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Opaque identity for one play of one queue entry. */
public data class PlaybackSessionId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Opaque identity for one resolved plan handed to the playback engine. */
public data class AttemptId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** Opaque correlation identity for one asynchronous engine command. */
public data class PlaybackCommandId(public val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/**
 * A monotonic instant supplied by an adapter. This type is deliberately not serializable: its only
 * valid use is subtraction from another instant originating from the same clock.
 */
public data class PlaybackMonotonicTime(public val elapsed: Duration)

/** Wall-clock epoch milliseconds used only as a scrobble session-start timestamp. */
public data class PlaybackWallClockTime(public val epochMilliseconds: Long)

/** Marker implemented by the resolved-plan contract in the platform-adapter slice. */
public interface PlaybackPlan

public sealed interface PlaybackCommand {
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

public sealed interface PlaybackCommandRejectionReason {
    public data object InvalidState : PlaybackCommandRejectionReason
    public data object Unsupported : PlaybackCommandRejectionReason
    public data object EngineReleased : PlaybackCommandRejectionReason
    public data class Failed(val error: DomainError) : PlaybackCommandRejectionReason
}

/** Result details are adapter-defined until a command needs a normative result payload. */
public interface PlaybackCommandResult

public data object PlaybackCommandCompletedWithoutData : PlaybackCommandResult

/** Returning one sealed value makes multiple or missing command outcomes impossible at this seam. */
public sealed interface PlaybackCommandOutcome {
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

public fun interface PlaybackEngineEventListener {
    public fun onPlaybackEngineEvent(event: PlaybackEngineEvent)
}

/** Platform adapters execute commands and publish observations; retry policy remains in the core. */
public interface PlaybackEngine {
    public suspend fun execute(command: PlaybackCommand): PlaybackCommandOutcome
    public fun setEventListener(listener: PlaybackEngineEventListener)
}

public enum class PlaybackSeekability {
    Seekable,
    NotSeekable,
    Unknown,
}

public enum class PlaybackSkipReason {
    User,
    AutoAdvance,
    QueueReplacement,
}

public enum class PlaybackRouteKind {
    BuiltIn,
    Wired,
    Bluetooth,
    Hdmi,
    Remote,
    Unknown,
}

public enum class PlaybackEngineTeardownReason {
    BackgroundLimit,
    Lifecycle,
    SystemReclaimed,
    Released,
    Unknown,
}

public enum class PlaybackSourceRefreshReason {
    Unauthorized,
    Expired,
    ValidationFailed,
}

public enum class PlaybackObservationStatus {
    Preparing,
    Ready,
    Progressing,
    Buffering,
    Paused,
    Stopped,
    Failed,
}

public data class PlaybackObservationSnapshot(
    val status: PlaybackObservationStatus,
    val mediaPosition: Duration?,
    val duration: Duration?,
    val seekability: PlaybackSeekability,
    val rate: Double,
)

/** Every event is correlated to an attempt, including global-looking route and lifecycle events. */
public sealed interface PlaybackEngineEvent {
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

public sealed interface PlaybackRetryDecision {
    public data class RetryAfter(val delay: Duration) : PlaybackRetryDecision
    public data object SurfaceFailure : PlaybackRetryDecision
}

/** Pure retry arithmetic. The caller supplies non-negative jitter, so this policy reads no clock or RNG. */
public object PlaybackRetryPolicy {
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

        val remaining = maximumTotalWait - totalWaited
        if (remaining <= Duration.ZERO) return PlaybackRetryDecision.SurfaceFailure
        val floor = (error as? DomainError.Server.Busy)?.retryAfter ?: scheduledBackoff
        if (floor > remaining) return PlaybackRetryDecision.SurfaceFailure
        return PlaybackRetryDecision.RetryAfter(minOf(floor + jitter, remaining))
    }
}
