package com.legitimateapps.dulcet.core

import kotlin.time.Duration

public data class PositionCadenceState(
    val lastEmittedByAttempt: Map<AttemptId, PlaybackMonotonicTime> = emptyMap(),
)

public data class PositionCadenceReduction(
    val state: PositionCadenceState,
    val eventToEmit: PlaybackEngineEvent.PositionChanged?,
)

/** Pure core-side coalescing. Adapter samples remain timestamped by the adapter's monotonic clock. */
public object PositionCadenceCoalescer {
    public fun reduce(
        state: PositionCadenceState,
        event: PlaybackEngineEvent.PositionChanged,
    ): PositionCadenceReduction {
        val previous = state.lastEmittedByAttempt[event.attemptId]
        val elapsed = previous?.let { event.monotonicTime.elapsed - it.elapsed }
        val shouldEmit = previous == null || elapsed!! < Duration.ZERO ||
            elapsed >= ScrobbleAccumulator.cadenceTarget
        if (!shouldEmit) return PositionCadenceReduction(state, null)
        return PositionCadenceReduction(
            state.copy(
                lastEmittedByAttempt = state.lastEmittedByAttempt +
                    (event.attemptId to event.monotonicTime),
            ),
            event,
        )
    }

    public fun forget(state: PositionCadenceState, attemptId: AttemptId): PositionCadenceState =
        state.copy(lastEmittedByAttempt = state.lastEmittedByAttempt - attemptId)
}

public data class PlaybackSessionStart(
    val queueEntryId: QueueEntryId,
    val playbackSessionId: PlaybackSessionId,
    val attemptId: AttemptId,
    val itemId: ProviderItemId,
    val initialDuration: Duration? = null,
)

public enum class PlaybackAttemptPhase {
    Created,
    Preparing,
    Ready,
    Progressing,
    Buffering,
    Paused,
    Stopped,
    Failed,
    TornDown,
}

public data class PlaybackAttemptSnapshot(
    val attemptId: AttemptId,
    val phase: PlaybackAttemptPhase,
    val position: Duration?,
    val duration: Duration?,
    val seekability: PlaybackSeekability,
    val rate: Double,
)

public sealed interface PlaybackTerminalOutcome {
    public val attemptId: AttemptId

    public data class EndedNaturally(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class Skipped(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class FailedBeforeStart(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class FailedAfterPartial(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class EngineTornDown(override val attemptId: AttemptId) : PlaybackTerminalOutcome
}

public data class PlaybackSessionSnapshot(
    val queueEntryId: QueueEntryId,
    val playbackSessionId: PlaybackSessionId,
    val itemId: ProviderItemId,
    val currentAttempt: PlaybackAttemptSnapshot,
    val accumulator: ScrobbleAccumulatorState,
    val terminalOutcomes: List<PlaybackTerminalOutcome>,
    val finalized: Boolean,
)

public data class PlaybackCoreDiagnostics(
    val unknownAttemptDropCount: Long = 0,
    val finalizedSessionDropCount: Long = 0,
    val discontinuityCount: Long = 0,
    val durationUnknownTerminalCount: Long = 0,
)

public sealed interface PlaybackCoreEffect {
    public data class RecordPlaybackEvent(val event: RecordedPlaybackEvent) : PlaybackCoreEffect
    public data class AccumulatorDiagnostic(val effect: ScrobbleAccumulatorEffect) : PlaybackCoreEffect
}

public enum class PlaybackEventDisposition {
    AcceptedCurrentAttempt,
    AcceptedSupersededSessionOnly,
    AcceptedCoalesced,
    DroppedUnknownAttempt,
    DroppedFinalizedSession,
}

public data class PlaybackEventRecordResult(
    val disposition: PlaybackEventDisposition,
    val effects: List<PlaybackCoreEffect>,
)

public data class PlaybackTransitionResult(val effects: List<PlaybackCoreEffect>)

/**
 * Deterministic playback coordinator. It is intentionally synchronous: its owner serializes calls
 * on one dispatcher, and this class reads neither clock nor randomness.
 */
public class PlaybackCoreStateMachine {
    private data class MutableSession(
        val start: PlaybackSessionStart,
        var currentAttempt: PlaybackAttemptSnapshot,
        var accumulator: ScrobbleAccumulatorState,
        val terminalOutcomes: MutableList<PlaybackTerminalOutcome> = mutableListOf(),
        var finalized: Boolean = false,
    )

    private val sessions = mutableMapOf<PlaybackSessionId, MutableSession>()
    private val attemptOwners = mutableMapOf<AttemptId, PlaybackSessionId>()
    private val preloadedSessions = mutableMapOf<AttemptId, PlaybackSessionId>()
    private var cadenceState = PositionCadenceState()
    private var currentSessionId: PlaybackSessionId? = null

    public var diagnostics: PlaybackCoreDiagnostics = PlaybackCoreDiagnostics()
        private set

    public val currentSession: PlaybackSessionSnapshot?
        get() = currentSessionId?.let(::sessionSnapshot)

    public fun sessionSnapshot(sessionId: PlaybackSessionId): PlaybackSessionSnapshot? =
        sessions[sessionId]?.snapshot()

    public fun startPlaying(start: PlaybackSessionStart): PlaybackTransitionResult {
        check(currentSessionId == null) { "Finalize the current session before starting another" }
        createSession(start)
        currentSessionId = start.playbackSessionId
        return PlaybackTransitionResult(emptyList())
    }

    public fun planRefresh(newAttemptId: AttemptId): PlaybackTransitionResult =
        replaceCurrentAttempt(newAttemptId)

    public fun retryAfterFailedBeforeStart(newAttemptId: AttemptId): PlaybackTransitionResult =
        replaceCurrentAttempt(newAttemptId)

    public fun serverOffsetSeek(newAttemptId: AttemptId): PlaybackTransitionResult =
        replaceCurrentAttempt(newAttemptId)

    public fun advanceToNext(start: PlaybackSessionStart): PlaybackTransitionResult {
        val effects = finalizeCurrentSession()
        createSession(start)
        currentSessionId = start.playbackSessionId
        return PlaybackTransitionResult(effects)
    }

    public fun repeatOne(
        playbackSessionId: PlaybackSessionId,
        attemptId: AttemptId,
    ): PlaybackTransitionResult {
        val outgoing = currentSession ?: error("No current session")
        return advanceToNext(
            PlaybackSessionStart(
                queueEntryId = outgoing.queueEntryId,
                playbackSessionId = playbackSessionId,
                attemptId = attemptId,
                itemId = outgoing.itemId,
                initialDuration = outgoing.accumulator.durationKnown,
            ),
        )
    }

    /** A wholesale non-empty replacement finalizes the outgoing session before activating its head. */
    public fun replaceQueue(replacement: PlaybackSessionStart): PlaybackTransitionResult =
        advanceToNext(replacement)

    public fun clearQueue(): PlaybackTransitionResult =
        PlaybackTransitionResult(finalizeCurrentSession())

    public fun registerPreloaded(start: PlaybackSessionStart) {
        createSession(start)
        preloadedSessions[start.attemptId] = start.playbackSessionId
    }

    public fun recordPlaybackEvent(event: PlaybackEngineEvent): PlaybackEventRecordResult {
        val ownerId = attemptOwners[event.attemptId]
            ?: return droppedUnknownAttempt()
        val session = sessions.getValue(ownerId)
        if (session.finalized) return droppedFinalizedSession()

        val isCurrentAttempt = session.currentAttempt.attemptId == event.attemptId
        if (!isCurrentAttempt) {
            return recordSupersededAttemptEvent(session, event)
        }

        if (event is PlaybackEngineEvent.AttemptReplaced) {
            return recordAttemptReplaced(session, event)
        }
        if (event is PlaybackEngineEvent.AdvancedToPreloaded) {
            return recordAdvancedToPreloaded(session, event)
        }

        if (event is PlaybackEngineEvent.PositionChanged) {
            val cadence = PositionCadenceCoalescer.reduce(cadenceState, event)
            cadenceState = cadence.state
            if (cadence.eventToEmit == null) {
                return PlaybackEventRecordResult(
                    PlaybackEventDisposition.AcceptedCoalesced,
                    emptyList(),
                )
            }
        }

        updateCurrentAttempt(session, event)
        terminalOutcome(event)?.let(session.terminalOutcomes::add)
        val effects = if (event is PlaybackEngineEvent.ObservationResynced) {
            applyObservationSnapshot(session, event.snapshot)
        } else {
            event.toAccumulatorEvent()?.let { applyAccumulator(session, it) }.orEmpty()
        }
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.AcceptedCurrentAttempt,
            effects,
        )
    }

    private fun replaceCurrentAttempt(newAttemptId: AttemptId): PlaybackTransitionResult {
        val session = currentMutableSession()
        registerAttempt(newAttemptId, session.start.playbackSessionId)
        session.accumulator = ScrobbleAccumulator.reduce(
            session.accumulator,
            ScrobbleAccumulatorEvent.AttemptReplaced,
        ).state
        session.currentAttempt = newAttemptSnapshot(newAttemptId, session.accumulator.durationKnown)
        return PlaybackTransitionResult(emptyList())
    }

    private fun recordAttemptReplaced(
        session: MutableSession,
        event: PlaybackEngineEvent.AttemptReplaced,
    ): PlaybackEventRecordResult {
        val existingOwner = attemptOwners[event.newAttemptId]
        if (existingOwner != null && existingOwner != session.start.playbackSessionId) {
            return droppedUnknownAttempt()
        }
        registerAttempt(event.newAttemptId, session.start.playbackSessionId)
        session.accumulator = ScrobbleAccumulator.reduce(
            session.accumulator,
            ScrobbleAccumulatorEvent.AttemptReplaced,
        ).state
        session.currentAttempt = newAttemptSnapshot(
            event.newAttemptId,
            session.accumulator.durationKnown,
        )
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.AcceptedCurrentAttempt,
            emptyList(),
        )
    }

    private fun recordAdvancedToPreloaded(
        outgoing: MutableSession,
        event: PlaybackEngineEvent.AdvancedToPreloaded,
    ): PlaybackEventRecordResult {
        val nextSessionId = preloadedSessions.remove(event.newAttemptId)
            ?: return droppedUnknownAttempt()
        val next = sessions.getValue(nextSessionId)
        if (next.finalized || next.currentAttempt.attemptId != event.newAttemptId) {
            return droppedFinalizedSession()
        }
        val effects = finalizeSession(outgoing)
        currentSessionId = nextSessionId
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.AcceptedCurrentAttempt,
            effects,
        )
    }

    private fun recordSupersededAttemptEvent(
        session: MutableSession,
        event: PlaybackEngineEvent,
    ): PlaybackEventRecordResult {
        terminalOutcome(event)?.let(session.terminalOutcomes::add)
        val terminalEvent = event.toTerminalAccumulatorEvent()
        val effects = if (terminalEvent == null) {
            emptyList()
        } else {
            val before = session.accumulator
            val reduction = ScrobbleAccumulator.reduce(before, terminalEvent)
            // A late terminal event may finalize submission state, but its old position/transport
            // state cannot replace the anchors belonging to the current attempt.
            session.accumulator = before.copy(
                submitted = reduction.state.submitted,
                durationUnknownReported = reduction.state.durationUnknownReported,
            )
            mapAccumulatorEffects(session, reduction.effects)
        }
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.AcceptedSupersededSessionOnly,
            effects,
        )
    }

    private fun updateCurrentAttempt(session: MutableSession, event: PlaybackEngineEvent) {
        val current = session.currentAttempt
        session.currentAttempt = when (event) {
            is PlaybackEngineEvent.Preparing -> current.copy(phase = PlaybackAttemptPhase.Preparing)
            is PlaybackEngineEvent.Ready -> current.copy(
                phase = PlaybackAttemptPhase.Ready,
                duration = event.duration,
                seekability = event.seekability,
            )
            is PlaybackEngineEvent.PlaybackProgressBegan -> current.copy(
                phase = PlaybackAttemptPhase.Progressing,
                position = event.mediaPosition,
            )
            is PlaybackEngineEvent.Buffering -> current.copy(
                phase = PlaybackAttemptPhase.Buffering,
                position = event.position,
            )
            is PlaybackEngineEvent.BufferingEnded -> current.copy(
                phase = PlaybackAttemptPhase.Progressing,
                position = event.position,
            )
            is PlaybackEngineEvent.Paused -> current.copy(
                phase = PlaybackAttemptPhase.Paused,
                position = event.position,
            )
            is PlaybackEngineEvent.Resumed -> current.copy(
                phase = PlaybackAttemptPhase.Progressing,
                position = event.position,
            )
            is PlaybackEngineEvent.PositionChanged -> current.copy(position = event.mediaPosition)
            is PlaybackEngineEvent.DurationChanged -> current.copy(duration = event.duration)
            is PlaybackEngineEvent.SeekCompleted -> current.copy(position = event.to)
            is PlaybackEngineEvent.SeekFailed -> current.copy(position = event.from)
            is PlaybackEngineEvent.EndedNaturally -> current.copy(
                phase = PlaybackAttemptPhase.Stopped,
                position = event.finalPosition,
            )
            is PlaybackEngineEvent.Skipped -> current.copy(
                phase = PlaybackAttemptPhase.Stopped,
                position = event.position,
            )
            is PlaybackEngineEvent.FailedBeforeStart -> current.copy(phase = PlaybackAttemptPhase.Failed)
            is PlaybackEngineEvent.FailedAfterPartial -> current.copy(
                phase = PlaybackAttemptPhase.Failed,
                position = event.position,
            )
            is PlaybackEngineEvent.RouteChanged -> if (event.didPause) {
                current.copy(phase = PlaybackAttemptPhase.Paused)
            } else {
                current
            }
            is PlaybackEngineEvent.InterruptionBegan -> current.copy(phase = PlaybackAttemptPhase.Paused)
            is PlaybackEngineEvent.InterruptionEnded -> current
            is PlaybackEngineEvent.RateChanged -> current.copy(rate = event.rate)
            is PlaybackEngineEvent.EngineTornDown -> current.copy(phase = PlaybackAttemptPhase.TornDown)
            is PlaybackEngineEvent.SourceRefreshRequired -> current
            is PlaybackEngineEvent.ObservationResynced -> current.copy(
                phase = event.snapshot.status.toAttemptPhase(),
                position = event.snapshot.mediaPosition,
                duration = event.snapshot.duration,
                seekability = event.snapshot.seekability,
                rate = event.snapshot.rate,
            )
            is PlaybackEngineEvent.AttemptReplaced,
            is PlaybackEngineEvent.AdvancedToPreloaded,
            -> current
        }
    }

    private fun applyAccumulator(
        session: MutableSession,
        event: ScrobbleAccumulatorEvent,
    ): List<PlaybackCoreEffect> {
        val reduction = ScrobbleAccumulator.reduce(session.accumulator, event)
        session.accumulator = reduction.state
        return mapAccumulatorEffects(session, reduction.effects)
    }

    private fun applyObservationSnapshot(
        session: MutableSession,
        snapshot: PlaybackObservationSnapshot,
    ): List<PlaybackCoreEffect> {
        val effects = mutableListOf<PlaybackCoreEffect>()
        snapshot.duration?.let {
            effects += applyAccumulator(session, ScrobbleAccumulatorEvent.DurationChanged(it))
        }
        if (session.accumulator.rate != snapshot.rate) {
            effects += applyAccumulator(session, ScrobbleAccumulatorEvent.RateChanged(snapshot.rate))
        }
        val position = snapshot.mediaPosition
        if (position != null) {
            val observation = when (snapshot.status) {
                PlaybackObservationStatus.Progressing -> ScrobbleAccumulatorEvent.Resumed(position)
                PlaybackObservationStatus.Buffering -> ScrobbleAccumulatorEvent.Buffering(position)
                PlaybackObservationStatus.Paused,
                PlaybackObservationStatus.Stopped,
                PlaybackObservationStatus.Failed,
                -> ScrobbleAccumulatorEvent.Paused(position)
                PlaybackObservationStatus.Preparing,
                PlaybackObservationStatus.Ready,
                -> ScrobbleAccumulatorEvent.SeekFailed(position, position)
            }
            effects += applyAccumulator(session, observation)
        }
        return effects
    }

    private fun mapAccumulatorEffects(
        session: MutableSession,
        effects: List<ScrobbleAccumulatorEffect>,
    ): List<PlaybackCoreEffect> = effects.map { effect ->
        when (effect) {
            ScrobbleAccumulatorEffect.NowPlaying -> PlaybackCoreEffect.RecordPlaybackEvent(
                RecordedPlaybackEvent.NowPlaying(session.start.itemId),
            )
            is ScrobbleAccumulatorEffect.SubmittedPlay -> PlaybackCoreEffect.RecordPlaybackEvent(
                RecordedPlaybackEvent.SubmittedPlay(
                    session.start.itemId,
                    effect.sessionStartWallClock,
                ),
            )
            is ScrobbleAccumulatorEffect.DiscontinuityDiscarded -> {
                diagnostics = diagnostics.copy(
                    discontinuityCount = diagnostics.discontinuityCount + 1,
                )
                PlaybackCoreEffect.AccumulatorDiagnostic(effect)
            }
            ScrobbleAccumulatorEffect.DurationUnknownAtTerminal -> {
                diagnostics = diagnostics.copy(
                    durationUnknownTerminalCount = diagnostics.durationUnknownTerminalCount + 1,
                )
                PlaybackCoreEffect.AccumulatorDiagnostic(effect)
            }
        }
    }

    private fun finalizeCurrentSession(): List<PlaybackCoreEffect> {
        val session = currentSessionId?.let(sessions::get) ?: return emptyList()
        val effects = finalizeSession(session)
        currentSessionId = null
        return effects
    }

    private fun finalizeSession(session: MutableSession): List<PlaybackCoreEffect> {
        if (session.finalized) return emptyList()
        val effects = applyAccumulator(session, ScrobbleAccumulatorEvent.SessionFinalized)
        session.finalized = true
        cadenceState = PositionCadenceCoalescer.forget(
            cadenceState,
            session.currentAttempt.attemptId,
        )
        return effects
    }

    private fun createSession(start: PlaybackSessionStart) {
        check(start.playbackSessionId !in sessions) { "PlaybackSessionId must be unique" }
        registerAttempt(start.attemptId, start.playbackSessionId)
        sessions[start.playbackSessionId] = MutableSession(
            start = start,
            currentAttempt = newAttemptSnapshot(start.attemptId, start.initialDuration),
            accumulator = ScrobbleAccumulatorState.initial(start.initialDuration),
        )
    }

    private fun registerAttempt(attemptId: AttemptId, sessionId: PlaybackSessionId) {
        val previous = attemptOwners[attemptId]
        check(previous == null || previous == sessionId) { "AttemptId must belong to one session" }
        if (previous == null) attemptOwners[attemptId] = sessionId
    }

    private fun currentMutableSession(): MutableSession =
        currentSessionId?.let(sessions::get) ?: error("No current session")

    private fun droppedUnknownAttempt(): PlaybackEventRecordResult {
        diagnostics = diagnostics.copy(
            unknownAttemptDropCount = diagnostics.unknownAttemptDropCount + 1,
        )
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.DroppedUnknownAttempt,
            emptyList(),
        )
    }

    private fun droppedFinalizedSession(): PlaybackEventRecordResult {
        diagnostics = diagnostics.copy(
            finalizedSessionDropCount = diagnostics.finalizedSessionDropCount + 1,
        )
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.DroppedFinalizedSession,
            emptyList(),
        )
    }

    private fun MutableSession.snapshot() = PlaybackSessionSnapshot(
        queueEntryId = start.queueEntryId,
        playbackSessionId = start.playbackSessionId,
        itemId = start.itemId,
        currentAttempt = currentAttempt,
        accumulator = accumulator,
        terminalOutcomes = terminalOutcomes.toList(),
        finalized = finalized,
    )
}

private fun newAttemptSnapshot(
    attemptId: AttemptId,
    duration: Duration?,
) = PlaybackAttemptSnapshot(
    attemptId = attemptId,
    phase = PlaybackAttemptPhase.Created,
    position = null,
    duration = duration,
    seekability = PlaybackSeekability.Unknown,
    rate = 1.0,
)

private fun PlaybackObservationStatus.toAttemptPhase(): PlaybackAttemptPhase = when (this) {
    PlaybackObservationStatus.Preparing -> PlaybackAttemptPhase.Preparing
    PlaybackObservationStatus.Ready -> PlaybackAttemptPhase.Ready
    PlaybackObservationStatus.Progressing -> PlaybackAttemptPhase.Progressing
    PlaybackObservationStatus.Buffering -> PlaybackAttemptPhase.Buffering
    PlaybackObservationStatus.Paused -> PlaybackAttemptPhase.Paused
    PlaybackObservationStatus.Stopped -> PlaybackAttemptPhase.Stopped
    PlaybackObservationStatus.Failed -> PlaybackAttemptPhase.Failed
}

private fun PlaybackEngineEvent.toAccumulatorEvent(): ScrobbleAccumulatorEvent? = when (this) {
    is PlaybackEngineEvent.Ready -> duration?.let(ScrobbleAccumulatorEvent::DurationChanged)
    is PlaybackEngineEvent.PlaybackProgressBegan -> ScrobbleAccumulatorEvent.PlaybackProgressBegan(
        wallClock,
        mediaPosition,
    )
    is PlaybackEngineEvent.Buffering -> ScrobbleAccumulatorEvent.Buffering(position)
    is PlaybackEngineEvent.BufferingEnded -> ScrobbleAccumulatorEvent.BufferingEnded(position)
    is PlaybackEngineEvent.Paused -> ScrobbleAccumulatorEvent.Paused(position)
    is PlaybackEngineEvent.Resumed -> ScrobbleAccumulatorEvent.Resumed(position)
    is PlaybackEngineEvent.PositionChanged -> ScrobbleAccumulatorEvent.PositionChanged(
        mediaPosition,
        monotonicTime,
    )
    is PlaybackEngineEvent.DurationChanged -> ScrobbleAccumulatorEvent.DurationChanged(duration)
    is PlaybackEngineEvent.SeekCompleted -> ScrobbleAccumulatorEvent.SeekCompleted(from, to)
    is PlaybackEngineEvent.SeekFailed -> ScrobbleAccumulatorEvent.SeekFailed(from, to)
    is PlaybackEngineEvent.EndedNaturally -> ScrobbleAccumulatorEvent.EndedNaturally(finalPosition)
    is PlaybackEngineEvent.Skipped -> ScrobbleAccumulatorEvent.Skipped(position)
    is PlaybackEngineEvent.FailedBeforeStart -> ScrobbleAccumulatorEvent.FailedBeforeStart
    is PlaybackEngineEvent.FailedAfterPartial -> ScrobbleAccumulatorEvent.FailedAfterPartial(position)
    is PlaybackEngineEvent.InterruptionBegan -> ScrobbleAccumulatorEvent.InterruptionBegan
    is PlaybackEngineEvent.InterruptionEnded -> ScrobbleAccumulatorEvent.InterruptionEnded(shouldResume)
    is PlaybackEngineEvent.RateChanged -> ScrobbleAccumulatorEvent.RateChanged(rate)
    is PlaybackEngineEvent.RouteChanged -> if (didPause) {
        ScrobbleAccumulatorEvent.InterruptionBegan
    } else {
        null
    }
    is PlaybackEngineEvent.EngineTornDown -> ScrobbleAccumulatorEvent.SessionFinalized
    is PlaybackEngineEvent.Preparing,
    is PlaybackEngineEvent.AttemptReplaced,
    is PlaybackEngineEvent.AdvancedToPreloaded,
    is PlaybackEngineEvent.SourceRefreshRequired,
    is PlaybackEngineEvent.ObservationResynced,
    -> null
}

private fun PlaybackEngineEvent.toTerminalAccumulatorEvent(): ScrobbleAccumulatorEvent? = when (this) {
    is PlaybackEngineEvent.EndedNaturally -> ScrobbleAccumulatorEvent.EndedNaturally(finalPosition)
    is PlaybackEngineEvent.Skipped -> ScrobbleAccumulatorEvent.Skipped(position)
    is PlaybackEngineEvent.FailedBeforeStart -> ScrobbleAccumulatorEvent.FailedBeforeStart
    is PlaybackEngineEvent.FailedAfterPartial -> ScrobbleAccumulatorEvent.FailedAfterPartial(position)
    is PlaybackEngineEvent.EngineTornDown -> ScrobbleAccumulatorEvent.SessionFinalized
    else -> null
}

private fun terminalOutcome(event: PlaybackEngineEvent): PlaybackTerminalOutcome? = when (event) {
    is PlaybackEngineEvent.EndedNaturally -> PlaybackTerminalOutcome.EndedNaturally(event.attemptId)
    is PlaybackEngineEvent.Skipped -> PlaybackTerminalOutcome.Skipped(event.attemptId)
    is PlaybackEngineEvent.FailedBeforeStart -> PlaybackTerminalOutcome.FailedBeforeStart(event.attemptId)
    is PlaybackEngineEvent.FailedAfterPartial -> PlaybackTerminalOutcome.FailedAfterPartial(event.attemptId)
    is PlaybackEngineEvent.EngineTornDown -> PlaybackTerminalOutcome.EngineTornDown(event.attemptId)
    else -> null
}
