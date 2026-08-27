package com.legitimateapps.dulcet.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class PositionCadenceState(
    val lastEmittedByAttempt: Map<AttemptId, PlaybackMonotonicTime> = emptyMap(),
)

internal data class PositionCadenceReduction(
    val state: PositionCadenceState,
    val eventToEmit: PlaybackEngineEvent.PositionChanged?,
)

/** Pure core-side coalescing. Adapter samples remain timestamped by the adapter's monotonic clock. */
internal object PositionCadenceCoalescer {
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

internal data class PlaybackSessionStart(
    val queueEntryId: QueueEntryId,
    val playbackSessionId: PlaybackSessionId,
    val attemptId: AttemptId,
    val itemId: ProviderItemId,
    val initialDuration: Duration? = null,
)

internal enum class PlaybackAttemptPhase {
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

internal data class PlaybackAttemptSnapshot(
    val attemptId: AttemptId,
    val phase: PlaybackAttemptPhase,
    val position: Duration?,
    val duration: Duration?,
    val seekability: PlaybackSeekability,
    val rate: Double,
)

internal sealed interface PlaybackTerminalOutcome {
    public val attemptId: AttemptId

    public data class EndedNaturally(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class Skipped(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class FailedBeforeStart(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class FailedAfterPartial(override val attemptId: AttemptId) : PlaybackTerminalOutcome
    public data class EngineTornDown(override val attemptId: AttemptId) : PlaybackTerminalOutcome
}

internal data class PlaybackSessionSnapshot(
    val queueEntryId: QueueEntryId,
    val playbackSessionId: PlaybackSessionId,
    val itemId: ProviderItemId,
    val currentAttempt: PlaybackAttemptSnapshot,
    val accumulator: ScrobbleAccumulatorState,
    val terminalOutcomes: List<PlaybackTerminalOutcome>,
)

internal data class PlaybackCoreDiagnostics(
    val unknownAttemptDropCount: Long = 0,
    val finalizedSessionDropCount: Long = 0,
    val discontinuityCount: Long = 0,
    val durationUnknownTerminalCount: Long = 0,
    val terminalEvaluationCount: Long = 0,
    val finalizedSessionCount: Long = 0,
    val unregisteredPreloadAdvanceCount: Long = 0,
    val invalidPreloadAdvanceCount: Long = 0,
    val discardedPreloadEventCount: Long = 0,
    val attemptCollisionCount: Long = 0,
    val invalidProgressingResyncCount: Long = 0,
    val retiredAttemptTombstoneEvictionCount: Long = 0,
)

internal sealed interface PlaybackCoreEffect {
    public data class RecordPlaybackEvent(val event: RecordedPlaybackEvent) : PlaybackCoreEffect
    public data class PersistResumePosition(
        val itemId: ProviderItemId,
        val position: Duration,
    ) : PlaybackCoreEffect
    public data class ClearResumePosition(val itemId: ProviderItemId) : PlaybackCoreEffect
    public data class AccumulatorDiagnostic(val effect: ScrobbleAccumulatorEffect) : PlaybackCoreEffect
}

internal enum class PlaybackEventDisposition {
    AcceptedCurrentAttempt,
    AcceptedSupersededSessionOnly,
    AcceptedCoalesced,
    DroppedUnknownAttempt,
    DroppedFinalizedSession,
    DroppedDiscardedPreload,
    RejectedUnregisteredPreload,
    RejectedInvalidPreload,
    RejectedAttemptCollision,
}

internal data class PlaybackEventRecordResult(
    val disposition: PlaybackEventDisposition,
    val effects: List<PlaybackCoreEffect>,
)

internal enum class PlaybackTransitionRejection {
    CurrentSessionExists,
    NoCurrentSession,
    DuplicatePlaybackSessionId,
    AttemptIdAlreadyUsed,
    PreloadNotFound,
    PreloadDoesNotMatch,
}

internal sealed interface PlaybackTransitionResult {
    public val effects: List<PlaybackCoreEffect>

    public data class Applied(
        override val effects: List<PlaybackCoreEffect>,
    ) : PlaybackTransitionResult

    public data class Rejected(
        val reason: PlaybackTransitionRejection,
    ) : PlaybackTransitionResult {
        override val effects: List<PlaybackCoreEffect> = emptyList()
    }
}

/**
 * Deterministic playback coordinator. It is intentionally synchronous: its owner serializes calls
 * on one dispatcher, and this class reads neither clock nor randomness.
 */
internal class PlaybackCoreStateMachine {
    private data class MutableSession(
        val start: PlaybackSessionStart,
        var currentAttempt: PlaybackAttemptSnapshot,
        var accumulator: ScrobbleAccumulatorState,
        val terminalOutcomes: MutableList<PlaybackTerminalOutcome> = mutableListOf(),
        val attemptIds: MutableSet<AttemptId> = mutableSetOf(start.attemptId),
        var resumeCadenceElapsed: Duration = Duration.ZERO,
        var resumeCadenceAnchor: PlaybackMonotonicTime? = null,
        var resumePositionClearedAtEnd: Boolean = false,
    )

    private enum class RetiredAttemptKind { FinalizedSession, DiscardedPreload }

    private val sessions = mutableMapOf<PlaybackSessionId, MutableSession>()
    private val attemptOwners = mutableMapOf<AttemptId, PlaybackSessionId>()
    private val preloadedSessions = mutableMapOf<AttemptId, PlaybackSessionId>()
    private val retiredAttempts = mutableMapOf<AttemptId, RetiredAttemptKind>()
    private val retiredAttemptOrder = ArrayDeque<AttemptId>()
    private var cadenceState = PositionCadenceState()
    private var currentSessionId: PlaybackSessionId? = null

    public var diagnostics: PlaybackCoreDiagnostics = PlaybackCoreDiagnostics()
        private set

    public val currentSession: PlaybackSessionSnapshot?
        get() = currentSessionId?.let(::sessionSnapshot)

    internal val retainedSessionStateCount: Int get() = sessions.size
    internal val trackedAttemptOwnerCount: Int get() = attemptOwners.size
    internal val trackedCadenceAttemptCount: Int get() = cadenceState.lastEmittedByAttempt.size
    internal val retiredAttemptTombstoneCount: Int get() = retiredAttempts.size

    public fun sessionSnapshot(sessionId: PlaybackSessionId): PlaybackSessionSnapshot? =
        sessions[sessionId]?.snapshot()

    public fun startPlaying(start: PlaybackSessionStart): PlaybackTransitionResult {
        if (currentSessionId != null) {
            return PlaybackTransitionResult.Rejected(
                PlaybackTransitionRejection.CurrentSessionExists,
            )
        }
        validateNewSession(start)?.let { return PlaybackTransitionResult.Rejected(it) }
        createSessionUnchecked(start)
        currentSessionId = start.playbackSessionId
        return PlaybackTransitionResult.Applied(emptyList())
    }

    public fun planRefresh(newAttemptId: AttemptId): PlaybackTransitionResult =
        replaceCurrentAttempt(newAttemptId)

    public fun retryAfterFailedBeforeStart(newAttemptId: AttemptId): PlaybackTransitionResult =
        replaceCurrentAttempt(newAttemptId)

    public fun serverOffsetSeek(newAttemptId: AttemptId): PlaybackTransitionResult =
        replaceCurrentAttempt(newAttemptId)

    public fun advanceToNext(start: PlaybackSessionStart): PlaybackTransitionResult {
        val preloaded = sessions[start.playbackSessionId]
        if (preloaded != null) {
            if (
                preloaded.start != start ||
                preloadedSessions[start.attemptId] != start.playbackSessionId
            ) {
                return PlaybackTransitionResult.Rejected(
                    PlaybackTransitionRejection.PreloadDoesNotMatch,
                )
            }
            val effects = finalizeCurrentSession()
            preloadedSessions.remove(start.attemptId)
            discardAllPreloads(exceptSessionId = start.playbackSessionId)
            currentSessionId = start.playbackSessionId
            return PlaybackTransitionResult.Applied(effects)
        }

        validateNewSession(start)?.let { return PlaybackTransitionResult.Rejected(it) }
        discardAllPreloads()
        val effects = finalizeCurrentSession()
        createSessionUnchecked(start)
        currentSessionId = start.playbackSessionId
        return PlaybackTransitionResult.Applied(effects)
    }

    public fun repeatOne(
        playbackSessionId: PlaybackSessionId,
        attemptId: AttemptId,
    ): PlaybackTransitionResult {
        val outgoing = currentSession ?: return PlaybackTransitionResult.Rejected(
            PlaybackTransitionRejection.NoCurrentSession,
        )
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
        PlaybackTransitionResult.Applied(
            finalizeCurrentSession().also { discardAllPreloads() },
        )

    public fun registerPreloaded(start: PlaybackSessionStart): PlaybackTransitionResult {
        validateNewSession(start)?.let { return PlaybackTransitionResult.Rejected(it) }
        createSessionUnchecked(start)
        preloadedSessions[start.attemptId] = start.playbackSessionId
        return PlaybackTransitionResult.Applied(emptyList())
    }

    public fun discardPreloaded(attemptId: AttemptId): PlaybackTransitionResult {
        val sessionId = preloadedSessions.remove(attemptId)
            ?: return PlaybackTransitionResult.Rejected(
                PlaybackTransitionRejection.PreloadNotFound,
            )
        val session = sessions[sessionId]
            ?: return PlaybackTransitionResult.Rejected(
                PlaybackTransitionRejection.PreloadNotFound,
            )
        retireSession(session, RetiredAttemptKind.DiscardedPreload)
        return PlaybackTransitionResult.Applied(emptyList())
    }

    public fun recordPlaybackEvent(event: PlaybackEngineEvent): PlaybackEventRecordResult {
        val ownerId = attemptOwners[event.attemptId] ?: return when (
            retiredAttempts[event.attemptId]
        ) {
            RetiredAttemptKind.FinalizedSession -> droppedFinalizedSession()
            RetiredAttemptKind.DiscardedPreload -> droppedDiscardedPreload()
            null -> droppedUnknownAttempt()
        }
        val session = sessions.getValue(ownerId)

        // A gapless boundary belongs to the outgoing session, even when oldAttemptId was superseded
        // by a same-session refresh before the adapter delivered the boundary event.
        if (event is PlaybackEngineEvent.AdvancedToPreloaded) {
            return recordAdvancedToPreloaded(session, event)
        }

        val isCurrentAttempt = session.currentAttempt.attemptId == event.attemptId
        if (!isCurrentAttempt) {
            return recordSupersededAttemptEvent(session, event)
        }

        if (event is PlaybackEngineEvent.AttemptReplaced) {
            return recordAttemptReplaced(session, event)
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
        if (event is PlaybackEngineEvent.EngineTornDown) {
            val effects = finalizeSession(session)
            return PlaybackEventRecordResult(
                PlaybackEventDisposition.AcceptedCurrentAttempt,
                effects,
            )
        }
        val effects = if (event is PlaybackEngineEvent.ObservationResynced) {
            applyObservationSnapshot(session, event.snapshot)
        } else {
            event.toAccumulatorEvent()?.let { applyAccumulator(session, it) }.orEmpty()
        }
        val resumeEffects = resumeEffectsForEvent(session, event)
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.AcceptedCurrentAttempt,
            effects + resumeEffects,
        )
    }

    private fun replaceCurrentAttempt(newAttemptId: AttemptId): PlaybackTransitionResult {
        val session = currentSessionId?.let(sessions::get)
            ?: return PlaybackTransitionResult.Rejected(
                PlaybackTransitionRejection.NoCurrentSession,
            )
        if (attemptIdWasUsed(newAttemptId)) {
            return PlaybackTransitionResult.Rejected(
                PlaybackTransitionRejection.AttemptIdAlreadyUsed,
            )
        }
        val oldAttemptId = session.currentAttempt.attemptId
        registerAttemptUnchecked(newAttemptId, session)
        cadenceState = PositionCadenceCoalescer.forget(cadenceState, oldAttemptId)
        session.accumulator = ScrobbleAccumulator.reduce(
            session.accumulator,
            ScrobbleAccumulatorEvent.AttemptReplaced,
        ).state
        session.currentAttempt = newAttemptSnapshot(
            newAttemptId,
            session.accumulator.durationKnown,
            session.accumulator.rate,
        )
        return PlaybackTransitionResult.Applied(emptyList())
    }

    private fun recordAttemptReplaced(
        session: MutableSession,
        event: PlaybackEngineEvent.AttemptReplaced,
    ): PlaybackEventRecordResult {
        val existingOwner = attemptOwners[event.newAttemptId]
        if (
            retiredAttempts.containsKey(event.newAttemptId) ||
            existingOwner != null
        ) {
            diagnostics = diagnostics.copy(
                attemptCollisionCount = diagnostics.attemptCollisionCount + 1,
            )
            return PlaybackEventRecordResult(
                PlaybackEventDisposition.RejectedAttemptCollision,
                emptyList(),
            )
        }
        val oldAttemptId = session.currentAttempt.attemptId
        registerAttemptUnchecked(event.newAttemptId, session)
        cadenceState = PositionCadenceCoalescer.forget(cadenceState, oldAttemptId)
        session.accumulator = ScrobbleAccumulator.reduce(
            session.accumulator,
            ScrobbleAccumulatorEvent.AttemptReplaced,
        ).state
        session.currentAttempt = newAttemptSnapshot(
            event.newAttemptId,
            session.accumulator.durationKnown,
            session.accumulator.rate,
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
        if (outgoing.start.playbackSessionId != currentSessionId) {
            diagnostics = diagnostics.copy(
                invalidPreloadAdvanceCount = diagnostics.invalidPreloadAdvanceCount + 1,
            )
            return PlaybackEventRecordResult(
                PlaybackEventDisposition.RejectedInvalidPreload,
                emptyList(),
            )
        }
        val nextSessionId = preloadedSessions[event.newAttemptId]
        if (nextSessionId == null) {
            diagnostics = diagnostics.copy(
                unregisteredPreloadAdvanceCount =
                    diagnostics.unregisteredPreloadAdvanceCount + 1,
            )
            return PlaybackEventRecordResult(
                PlaybackEventDisposition.RejectedUnregisteredPreload,
                emptyList(),
            )
        }
        val next = sessions[nextSessionId]
        if (next == null || next.currentAttempt.attemptId != event.newAttemptId) {
            diagnostics = diagnostics.copy(
                invalidPreloadAdvanceCount = diagnostics.invalidPreloadAdvanceCount + 1,
            )
            return PlaybackEventRecordResult(
                PlaybackEventDisposition.RejectedInvalidPreload,
                emptyList(),
            )
        }
        preloadedSessions.remove(event.newAttemptId)
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
            diagnostics = diagnostics.copy(
                terminalEvaluationCount = diagnostics.terminalEvaluationCount + 1,
            )
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
                duration = event.duration ?: current.duration,
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
                duration = event.snapshot.duration ?: current.duration,
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
        if (event.isTerminalEvaluation()) {
            diagnostics = diagnostics.copy(
                terminalEvaluationCount = diagnostics.terminalEvaluationCount + 1,
            )
        }
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
                PlaybackObservationStatus.Progressing -> {
                    val sessionStart = snapshot.sessionStartWallClock
                    if (sessionStart == null) {
                        diagnostics = diagnostics.copy(
                            invalidProgressingResyncCount =
                                diagnostics.invalidProgressingResyncCount + 1,
                        )
                        null
                    } else {
                        ScrobbleAccumulatorEvent.ProgressObservationResynced(
                            sessionStart,
                            position,
                        )
                    }
                }
                PlaybackObservationStatus.Buffering -> ScrobbleAccumulatorEvent.Buffering(position)
                PlaybackObservationStatus.Paused,
                PlaybackObservationStatus.Stopped,
                PlaybackObservationStatus.Failed,
                -> ScrobbleAccumulatorEvent.Paused(position)
                PlaybackObservationStatus.Preparing,
                PlaybackObservationStatus.Ready,
                -> ScrobbleAccumulatorEvent.SeekFailed(position, position)
            }
            if (observation != null) effects += applyAccumulator(session, observation)
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
        return finalizeSession(session)
    }

    private fun finalizeSession(session: MutableSession): List<PlaybackCoreEffect> {
        val effects = applyAccumulator(session, ScrobbleAccumulatorEvent.SessionFinalized)
            .toMutableList()
        val position = session.currentAttempt.position
        if (session.resumePositionClearedAtEnd) {
            effects += PlaybackCoreEffect.ClearResumePosition(session.start.itemId)
        } else if (position != null) {
            if (session.accumulator.submitted && session.hasReachedEnd(position)) {
                session.resumePositionClearedAtEnd = true
                effects += PlaybackCoreEffect.ClearResumePosition(session.start.itemId)
            } else {
                effects += PlaybackCoreEffect.PersistResumePosition(session.start.itemId, position)
            }
        }
        diagnostics = diagnostics.copy(
            finalizedSessionCount = diagnostics.finalizedSessionCount + 1,
        )
        retireSession(session, RetiredAttemptKind.FinalizedSession)
        return effects
    }

    private fun resumeEffectsForEvent(
        session: MutableSession,
        event: PlaybackEngineEvent,
    ): List<PlaybackCoreEffect> = when (event) {
        is PlaybackEngineEvent.EndedNaturally -> {
            session.resumePositionClearedAtEnd = true
            session.resetResumeCadence()
            listOf(PlaybackCoreEffect.ClearResumePosition(session.start.itemId))
        }
        is PlaybackEngineEvent.Paused -> {
            session.resetResumeCadence()
            listOf(PlaybackCoreEffect.PersistResumePosition(session.start.itemId, event.position))
        }
        is PlaybackEngineEvent.Skipped -> session.persistOrClearTerminalPosition(event.position)
        is PlaybackEngineEvent.FailedAfterPartial ->
            session.persistOrClearTerminalPosition(event.position)
        is PlaybackEngineEvent.PositionChanged -> session.resumeCadenceEffect(event)
        is PlaybackEngineEvent.PlaybackProgressBegan,
        is PlaybackEngineEvent.BufferingEnded,
        is PlaybackEngineEvent.Resumed,
        -> {
            session.resumeCadenceAnchor = null
            emptyList()
        }
        is PlaybackEngineEvent.Buffering,
        is PlaybackEngineEvent.InterruptionBegan,
        -> {
            session.resumeCadenceAnchor = null
            emptyList()
        }
        else -> emptyList()
    }

    private fun MutableSession.resumeCadenceEffect(
        event: PlaybackEngineEvent.PositionChanged,
    ): List<PlaybackCoreEffect> {
        if (currentAttempt.phase != PlaybackAttemptPhase.Progressing) {
            resumeCadenceAnchor = null
            return emptyList()
        }
        val previous = resumeCadenceAnchor
        resumeCadenceAnchor = event.monotonicTime
        if (previous == null) return emptyList()
        val delta = event.monotonicTime.elapsed - previous.elapsed
        if (delta <= Duration.ZERO || delta > RESUME_CADENCE_MAX_SAMPLE_GAP) return emptyList()
        resumeCadenceElapsed += delta
        if (resumeCadenceElapsed < RESUME_PERSIST_CADENCE) return emptyList()
        resumeCadenceElapsed = Duration.ZERO
        return listOf(
            PlaybackCoreEffect.PersistResumePosition(start.itemId, event.mediaPosition),
        )
    }

    private fun MutableSession.persistOrClearTerminalPosition(
        position: Duration,
    ): List<PlaybackCoreEffect> {
        resetResumeCadence()
        return if (accumulator.submitted && hasReachedEnd(position)) {
            resumePositionClearedAtEnd = true
            listOf(PlaybackCoreEffect.ClearResumePosition(start.itemId))
        } else {
            listOf(PlaybackCoreEffect.PersistResumePosition(start.itemId, position))
        }
    }

    private fun MutableSession.hasReachedEnd(position: Duration): Boolean =
        accumulator.durationKnown?.let { position >= it } == true

    private fun MutableSession.resetResumeCadence() {
        resumeCadenceElapsed = Duration.ZERO
        resumeCadenceAnchor = null
    }

    private fun validateNewSession(start: PlaybackSessionStart): PlaybackTransitionRejection? =
        when {
            start.playbackSessionId in sessions ->
                PlaybackTransitionRejection.DuplicatePlaybackSessionId
            attemptIdWasUsed(start.attemptId) -> PlaybackTransitionRejection.AttemptIdAlreadyUsed
            else -> null
        }

    private fun createSessionUnchecked(start: PlaybackSessionStart) {
        val session = MutableSession(
            start = start,
            currentAttempt = newAttemptSnapshot(start.attemptId, start.initialDuration, 1.0),
            accumulator = ScrobbleAccumulatorState.initial(start.initialDuration),
        )
        sessions[start.playbackSessionId] = session
        attemptOwners[start.attemptId] = start.playbackSessionId
    }

    private fun registerAttemptUnchecked(attemptId: AttemptId, session: MutableSession) {
        attemptOwners[attemptId] = session.start.playbackSessionId
        session.attemptIds += attemptId
    }

    private fun attemptIdWasUsed(attemptId: AttemptId): Boolean =
        attemptId in attemptOwners || attemptId in retiredAttempts

    private fun discardAllPreloads(exceptSessionId: PlaybackSessionId? = null) {
        preloadedSessions.values.toSet().forEach { sessionId ->
            if (sessionId != exceptSessionId) {
                sessions[sessionId]?.let {
                    retireSession(it, RetiredAttemptKind.DiscardedPreload)
                }
            }
        }
    }

    private fun retireSession(session: MutableSession, kind: RetiredAttemptKind) {
        val sessionId = session.start.playbackSessionId
        sessions.remove(sessionId)
        if (currentSessionId == sessionId) currentSessionId = null
        preloadedSessions.entries.removeAll { it.value == sessionId }
        session.attemptIds.forEach { attemptId ->
            attemptOwners.remove(attemptId)
            cadenceState = PositionCadenceCoalescer.forget(cadenceState, attemptId)
            rememberRetiredAttempt(attemptId, kind)
        }
    }

    private fun rememberRetiredAttempt(attemptId: AttemptId, kind: RetiredAttemptKind) {
        if (retiredAttempts.put(attemptId, kind) == null) retiredAttemptOrder.addLast(attemptId)
        while (retiredAttemptOrder.size > MAX_RETIRED_ATTEMPT_TOMBSTONES) {
            val evicted = retiredAttemptOrder.removeFirst()
            retiredAttempts.remove(evicted)
            diagnostics = diagnostics.copy(
                retiredAttemptTombstoneEvictionCount =
                    diagnostics.retiredAttemptTombstoneEvictionCount + 1,
            )
        }
    }

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

    private fun droppedDiscardedPreload(): PlaybackEventRecordResult {
        diagnostics = diagnostics.copy(
            discardedPreloadEventCount = diagnostics.discardedPreloadEventCount + 1,
        )
        return PlaybackEventRecordResult(
            PlaybackEventDisposition.DroppedDiscardedPreload,
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
    )

    private companion object {
        const val MAX_RETIRED_ATTEMPT_TOMBSTONES = 256
        val RESUME_PERSIST_CADENCE: Duration = 30.seconds
        val RESUME_CADENCE_MAX_SAMPLE_GAP: Duration = 4.seconds
    }
}

private fun newAttemptSnapshot(
    attemptId: AttemptId,
    duration: Duration?,
    rate: Double,
) = PlaybackAttemptSnapshot(
    attemptId = attemptId,
    phase = PlaybackAttemptPhase.Created,
    position = null,
    duration = duration,
    seekability = PlaybackSeekability.Unknown,
    rate = rate,
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
    is PlaybackEngineEvent.Preparing,
    is PlaybackEngineEvent.EngineTornDown,
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

private fun ScrobbleAccumulatorEvent.isTerminalEvaluation(): Boolean = when (this) {
    is ScrobbleAccumulatorEvent.EndedNaturally,
    is ScrobbleAccumulatorEvent.Skipped,
    is ScrobbleAccumulatorEvent.FailedAfterPartial,
    ScrobbleAccumulatorEvent.SessionFinalized,
    -> true
    else -> false
}
