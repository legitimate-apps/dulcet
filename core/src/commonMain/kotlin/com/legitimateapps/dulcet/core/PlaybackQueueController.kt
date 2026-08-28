package com.legitimateapps.dulcet.core

import kotlin.random.Random
import kotlin.time.Duration

internal fun interface PlaybackIdentitySource {
    fun next(prefix: String): String
}

internal data class PlaybackQueueItem(
    val itemId: ProviderItemId,
    val duration: Duration?,
)

internal data class PlaybackQueueRequest(
    val items: List<PlaybackQueueItem>,
    val sourceContext: QueueSourceContext,
    /** The original-order index to activate. Null means the head after optional shuffling. */
    val startIndex: Int?,
    val shuffle: Boolean,
) {
    init {
        require(items.isNotEmpty())
        require(startIndex == null || startIndex in items.indices)
        require(items.all { it.itemId.providerInstanceId == items.first().itemId.providerInstanceId })
        require(
            sourceContext.sourceId == null ||
                sourceContext.sourceId.providerInstanceId == items.first().itemId.providerInstanceId,
        )
    }
}

internal data class PlaybackQueueStartDirective(
    val queueEntryId: QueueEntryId,
    val playbackSessionId: PlaybackSessionId,
    val attemptId: AttemptId,
    val itemId: ProviderItemId,
    val duration: Duration?,
    val resumePosition: Duration?,
)

internal data class PlaybackQueueEntrySnapshot(
    val queueEntryId: QueueEntryId,
    val itemId: ProviderItemId,
)

internal data class PlaybackQueueSnapshot(
    val entries: List<PlaybackQueueEntrySnapshot>,
    val currentIndex: Int?,
    val repeatMode: QueueRepeatMode,
    val shuffleState: QueueShuffleState,
    val currentSession: PlaybackSessionSnapshot?,
)

internal data class PlaybackQueueTransition(
    val snapshot: PlaybackQueueSnapshot,
    val startDirective: PlaybackQueueStartDirective?,
    val effects: List<PlaybackCoreEffect>,
)

/**
 * The single core-owned queue decision point used by every presentation and system-media surface.
 * It is synchronous and deterministic apart from injected identities and shuffle randomness; its
 * owner serializes calls and executes returned effects in order.
 */
internal class PlaybackQueueController(
    private val queues: PersistentQueueStore,
    private val resumePositions: ResumePositionStore,
    private val identities: PlaybackIdentitySource,
    private val shuffleRandom: Random = Random.Default,
    private val playback: PlaybackCoreStateMachine = PlaybackCoreStateMachine(),
) {
    private val knownDurations = mutableMapOf<QueueEntryId, Duration?>()

    fun replaceAndStart(request: PlaybackQueueRequest): PlaybackQueueTransition {
        val serverId = ServerId(request.items.first().itemId.providerInstanceId)
        val previousRepeatMode = queues.load(serverId).repeatMode
        knownDurations.clear()
        val entries = request.items.map { item ->
            QueueEntry(
                queueEntryId = nextQueueEntryId(),
                providerItemId = item.itemId,
                sourceContext = request.sourceContext,
                addedBy = QueueAddedBy.PlayNow,
            ).also { knownDurations[it.queueEntryId] = item.duration }
        }
        queues.replaceQueue(
            QueueState(
                serverId = serverId,
                entries = entries,
                currentIndex = null,
                repeatMode = previousRepeatMode,
                shuffleState = QueueShuffleState.Disabled,
            ),
        )
        queues.activate(serverId)
        val ordered = if (request.shuffle) {
            queues.enableShuffle(serverId, shuffleRandom)
        } else {
            queues.load(serverId)
        }
        val currentIndex = if (request.shuffle) {
            0
        } else {
            request.startIndex ?: 0
        }
        val selected = if (request.shuffle) {
            ordered.entries[currentIndex]
        } else {
            entries[currentIndex]
        }
        val persisted = ordered.entries.indexOfFirst { it.queueEntryId == selected.queueEntryId }
        check(persisted >= 0)
        queues.setCurrentIndex(serverId, persisted)
        return beginSession(selected, replacingQueue = true)
    }

    fun next(): PlaybackQueueTransition = moveBy(1)

    fun previous(): PlaybackQueueTransition = moveBy(-1)

    fun nextForSession(playbackSessionId: PlaybackSessionId): PlaybackQueueTransition =
        if (acceptsCommand(playbackSessionId)) moveBy(1) else emptyTransition()

    fun previousForSession(playbackSessionId: PlaybackSessionId): PlaybackQueueTransition =
        if (acceptsCommand(playbackSessionId)) moveBy(-1) else emptyTransition()

    fun acceptsCommand(
        playbackSessionId: PlaybackSessionId,
        requiresSeekable: Boolean = false,
    ): Boolean {
        val session = playback.currentSession ?: return false
        return session.playbackSessionId == playbackSessionId &&
            (!requiresSeekable || session.currentAttempt.seekability == PlaybackSeekability.Seekable)
    }

    fun setShuffle(enabled: Boolean): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = if (enabled) {
            queues.enableShuffle(serverId, shuffleRandom)
        } else {
            queues.disableShuffle(serverId)
        }
        return PlaybackQueueTransition(state.snapshot(), null, emptyList())
    }

    fun cycleRepeatMode(): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val next = when (state.repeatMode) {
            QueueRepeatMode.Off -> QueueRepeatMode.All
            QueueRepeatMode.All -> QueueRepeatMode.One
            QueueRepeatMode.One -> QueueRepeatMode.Off
        }
        return PlaybackQueueTransition(
            queues.setRepeatMode(serverId, next).snapshot(),
            null,
            emptyList(),
        )
    }

    fun recordPlaybackEvent(event: PlaybackEngineEvent): PlaybackQueueTransition {
        val reduction = playback.recordPlaybackEvent(event)
        if (event is PlaybackEngineEvent.EndedNaturally &&
            reduction.disposition == PlaybackEventDisposition.AcceptedCurrentAttempt
        ) {
            return advanceAfterNaturalCompletion(reduction.effects)
        }
        return PlaybackQueueTransition(snapshot(), null, reduction.effects)
    }

    fun snapshot(): PlaybackQueueSnapshot {
        val serverId = queues.activeServerId()
            ?: return PlaybackQueueSnapshot(
                entries = emptyList(),
                currentIndex = null,
                repeatMode = QueueRepeatMode.Off,
                shuffleState = QueueShuffleState.Disabled,
                currentSession = playback.currentSession,
            )
        return queues.load(serverId).snapshot()
    }

    private fun moveBy(delta: Int): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val current = state.currentIndex ?: return emptyTransition()
        val target = current + delta
        if (target !in state.entries.indices) {
            return if (state.repeatMode == QueueRepeatMode.All && state.entries.isNotEmpty()) {
                startAt(state, if (delta > 0) 0 else state.entries.lastIndex)
            } else {
                val effects = playback.clearQueue().effects
                PlaybackQueueTransition(
                    queues.setCurrentIndex(serverId, null).snapshot(),
                    null,
                    effects,
                )
            }
        }
        return startAt(state, target)
    }

    private fun advanceAfterNaturalCompletion(
        terminalEffects: List<PlaybackCoreEffect>,
    ): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition(terminalEffects)
        val state = queues.load(serverId)
        val current = state.currentIndex ?: return emptyTransition(terminalEffects)
        if (state.repeatMode == QueueRepeatMode.One) {
            val entry = state.entries[current]
            val start = newStart(entry)
            val transition = playback.repeatOne(start.playbackSessionId, start.attemptId)
            check(transition is PlaybackTransitionResult.Applied)
            return PlaybackQueueTransition(
                state.snapshot(),
                start.directive(entry),
                terminalEffects + transition.effects,
            )
        }
        val nextIndex = current + 1
        if (nextIndex in state.entries.indices) {
            return startAt(state, nextIndex, terminalEffects)
        }
        if (state.repeatMode == QueueRepeatMode.All && state.entries.isNotEmpty()) {
            return startAt(state, 0, terminalEffects)
        }
        val finalization = playback.clearQueue()
        return PlaybackQueueTransition(
            queues.setCurrentIndex(serverId, null).snapshot(),
            null,
            terminalEffects + finalization.effects,
        )
    }

    private fun startAt(
        state: QueueState,
        index: Int,
        priorEffects: List<PlaybackCoreEffect> = emptyList(),
    ): PlaybackQueueTransition {
        val entry = state.entries[index]
        queues.setCurrentIndex(state.serverId, index)
        val transition = beginSession(entry, replacingQueue = false)
        return transition.copy(effects = priorEffects + transition.effects)
    }

    private fun beginSession(
        entry: QueueEntry,
        replacingQueue: Boolean,
    ): PlaybackQueueTransition {
        val start = newStart(entry)
        val coreTransition = when {
            playback.currentSession == null -> playback.startPlaying(start)
            replacingQueue -> playback.replaceQueue(start)
            else -> playback.advanceToNext(start)
        }
        check(coreTransition is PlaybackTransitionResult.Applied)
        return PlaybackQueueTransition(
            snapshot(),
            start.directive(entry),
            coreTransition.effects,
        )
    }

    private fun newStart(entry: QueueEntry): PlaybackSessionStart = PlaybackSessionStart(
        queueEntryId = entry.queueEntryId,
        playbackSessionId = PlaybackSessionId(identities.next("session")),
        attemptId = AttemptId(identities.next("attempt")),
        itemId = entry.providerItemId,
        initialDuration = knownDurations[entry.queueEntryId],
    )

    private fun PlaybackSessionStart.directive(entry: QueueEntry) = PlaybackQueueStartDirective(
        queueEntryId = queueEntryId,
        playbackSessionId = playbackSessionId,
        attemptId = attemptId,
        itemId = itemId,
        duration = initialDuration,
        resumePosition = resumePositions.restore(entry.providerItemId),
    )

    private fun nextQueueEntryId(): QueueEntryId =
        QueueEntryId(identities.next("queue-entry"))

    private fun QueueState.snapshot() = PlaybackQueueSnapshot(
        entries = entries.map { PlaybackQueueEntrySnapshot(it.queueEntryId, it.providerItemId) },
        currentIndex = currentIndex,
        repeatMode = repeatMode,
        shuffleState = shuffleState,
        currentSession = playback.currentSession,
    )

    private fun emptyTransition(
        effects: List<PlaybackCoreEffect> = emptyList(),
    ) = PlaybackQueueTransition(snapshot(), null, effects)
}
