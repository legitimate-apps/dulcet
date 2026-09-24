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
    val shouldAutoPlay: Boolean,
)

internal data class PlaybackQueueEntrySnapshot(
    val queueEntryId: QueueEntryId,
    val itemId: ProviderItemId,
    val sourceDisplayName: String,
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
    /**
     * A session registered for gapless preload (§12.8). It is never something to start: the engine
     * holds it behind the current item and reports `AdvancedToPreloaded` when it takes over.
     */
    val preloadDirective: PlaybackQueueStartDirective? = null,
    /**
     * A preload the core has just discarded because the entry it was prepared for is no longer
     * the one that plays next. The owner must remove it from the engine, or the engine would
     * advance into an entry the queue no longer names.
     */
    val discardedPreloadAttemptId: AttemptId? = null,
)

/** Items added to an existing queue by "Play Next" or "Play Later" (§14.1, §14.2). */
internal data class PlaybackQueueInsertion(
    val items: List<PlaybackQueueItem>,
    val sourceContext: QueueSourceContext,
    val mode: QueueInsertionMode,
) {
    init {
        require(items.isNotEmpty())
        require(items.all { it.itemId.providerInstanceId == items.first().itemId.providerInstanceId })
        require(
            sourceContext.sourceId == null ||
                sourceContext.sourceId.providerInstanceId == items.first().itemId.providerInstanceId,
        )
    }
}

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
    private data class RegisteredPreload(
        val queueEntryId: QueueEntryId,
        val start: PlaybackSessionStart,
    )

    private val knownDurations = mutableMapOf<QueueEntryId, Duration?>()
    private var registeredPreload: RegisteredPreload? = null
    /**
     * The current attempt ended naturally and the core deliberately started nothing, because the
     * registered preload was about to take over. If that preload is then discarded -- it failed,
     * or an edit made it stale -- nothing else will ever advance the queue, so the discard itself
     * must start the next entry.
     */
    private var endHeldForPreload = false

    /** The server whose queue is active, or null when none is. Owners use it to refuse a foreign queue. */
    fun activeServerId(): ServerId? = queues.activeServerId()

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

    /**
     * "Play Next" places the items immediately after the current entry, in the order given; "Play
     * Later" appends them. Both follow §14.2 while shuffled. Adding never starts playback and never
     * touches the current session: a queue edit is not a session boundary (§12.1).
     */
    fun enqueue(insertion: PlaybackQueueInsertion): PlaybackQueueTransition {
        val serverId = ServerId(insertion.items.first().itemId.providerInstanceId)
        require(queues.activeServerId() == serverId) { "No active queue for this account" }
        val entries = insertion.items.map { item ->
            QueueEntry(
                queueEntryId = nextQueueEntryId(),
                providerItemId = item.itemId,
                sourceContext = insertion.sourceContext,
                addedBy = when (insertion.mode) {
                    QueueInsertionMode.PlayNext -> QueueAddedBy.PlayNext
                    QueueInsertionMode.Append -> QueueAddedBy.AddToQueue
                },
            ).also { knownDurations[it.queueEntryId] = item.duration }
        }
        // Each PlayNext insert lands directly after the current entry, so inserting in reverse
        // keeps the caller's order: the first item given is the first one heard.
        // With no current entry every PlayNext insert appends, so reversing would reverse the batch.
        val hasCurrent = queues.load(serverId).currentIndex != null
        val ordered = if (insertion.mode == QueueInsertionMode.PlayNext && hasCurrent) {
            entries.asReversed()
        } else {
            entries
        }
        ordered.forEach { queues.insert(serverId, it, insertion.mode) }
        return editedTransition()
    }

    /** Moves an entry to [toIndex] in the listener-visible order. The current session continues. */
    fun move(queueEntryId: QueueEntryId, toIndex: Int): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        queues.move(serverId, queueEntryId, toIndex)
        return editedTransition()
    }

    /**
     * Removes a queue entry that is not playing. Removing the current entry is refused rather than
     * interpreted: it would either stop the music or silently start something, and a listener who
     * wants either has Next and Pause.
     */
    fun remove(queueEntryId: QueueEntryId): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val current = state.currentIndex?.let(state.entries::get)
        require(current?.queueEntryId != queueEntryId) { "The current entry cannot be removed" }
        require(state.entries.any { it.queueEntryId == queueEntryId }) { "Unknown queue entry" }
        queues.remove(serverId, queueEntryId)
        knownDurations.remove(queueEntryId)
        return editedTransition()
    }

    fun clearUpcoming(): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        queues.removeUpcoming(serverId)
        return editedTransition()
    }

    /**
     * Starts the selected entry when no session exists — the state a finished queue leaves behind
     * (§14.3). Play after the last track therefore replays it, from the start, as a new session.
     */
    fun startCurrent(): PlaybackQueueTransition {
        if (playback.currentSession != null) return emptyTransition()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val index = state.currentIndex ?: return emptyTransition()
        return startAt(state, index)
    }

    /**
     * Registers the entry that will play after the current one as a preloaded session (§12.8) and
     * returns its directive for the owner to resolve and hand to the engine's `preloadNext`.
     *
     * Declines — returns no directive — when nothing follows, under repeat-one (which restarts
     * through a fresh start), when the named session is not current, and when the next item has a
     * saved resume position, because a preloaded item begins at zero and the engine has no seek
     * for an item it has not started.
     */
    fun preloadNext(playbackSessionId: PlaybackSessionId): PlaybackQueueTransition {
        if (!acceptsCommand(playbackSessionId)) return emptyTransition()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val next = upcomingEntry(state)
        val existing = registeredPreload
        if (existing != null && existing.queueEntryId == next?.queueEntryId) return emptyTransition()
        val discarded = discardRegisteredPreload()
        if (next == null || resumePositions.restore(next.providerItemId) != null) {
            return emptyTransition().copy(discardedPreloadAttemptId = discarded)
        }
        val start = newStart(next)
        check(playback.registerPreloaded(start) is PlaybackTransitionResult.Applied)
        registeredPreload = RegisteredPreload(next.queueEntryId, start)
        return PlaybackQueueTransition(
            snapshot = snapshot(),
            startDirective = null,
            effects = emptyList(),
            preloadDirective = start.directive(next),
            discardedPreloadAttemptId = discarded,
        )
    }

    /**
     * The owner could not deliver a registered preload (resolution failed, the engine refused it,
     * or the server answered `Server.Busy`). Before the boundary, the next natural completion
     * then starts normally. After it -- the end was already held for this preload -- the discard
     * starts the next entry itself, because no later event will.
     */
    fun discardPreload(attemptId: AttemptId): PlaybackQueueTransition {
        val existing = registeredPreload ?: return emptyTransition()
        if (existing.start.attemptId != attemptId) return emptyTransition()
        val discarded = discardRegisteredPreload()
        return resumeHeldEnd(discarded) ?: emptyTransition().copy(discardedPreloadAttemptId = discarded)
    }

    fun next(): PlaybackQueueTransition = moveBy(1)

    fun previous(): PlaybackQueueTransition = moveBy(-1)

    /**
     * Starts the entry the user picked from Up Next — a next-item boundary, so the outgoing session
     * is finalized (§12.1). Addressed by queue-entry identity, never by index: an index captured by
     * a presentation goes stale as soon as the queue changes, and the same song may appear twice.
     * Every entry keeps its identity; only a new session begins. An entry that is no longer in the
     * queue (a tap on a row an edit just removed) changes nothing rather than throwing; a platform
     * facade that must report it as a refusal checks the returned snapshot.
     */
    fun jumpTo(queueEntryId: QueueEntryId): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val index = state.entries.indexOfFirst { it.queueEntryId == queueEntryId }
        if (index < 0) return emptyTransition()
        return startAt(state, index)
    }

    fun nextForSession(playbackSessionId: PlaybackSessionId): PlaybackQueueTransition =
        if (acceptsCommand(playbackSessionId)) moveBy(1) else emptyTransition()

    fun previousForSession(playbackSessionId: PlaybackSessionId): PlaybackQueueTransition =
        if (acceptsCommand(playbackSessionId)) moveBy(-1) else emptyTransition()

    fun restoreCurrentPausedWithCatalog(
        serverId: ServerId,
        availableRawIds: Set<String>,
    ): PlaybackQueueTransition {
        // Library refreshes must not repair or replace a session someone already started.
        if (playback.currentSession != null || queues.activeServerId() != serverId) {
            return emptyTransition()
        }
        // This catalog describes what can resolve now, not which items still exist.
        // Empty, partial, or metadata-incomplete catalogs must never delete queue entries.
        val state = queues.load(serverId)
        val selected = state.currentIndex?.let(state.entries::get)
        if (selected != null && selected.providerItemId.rawId !in availableRawIds) {
            queues.setCurrentIndex(serverId, null)
        }
        return restoreCurrentPaused()
    }

    /** Transport restart keeps the persisted selection and every queue entry identity. */
    fun restartCurrent(serverId: ServerId): PlaybackQueueTransition {
        if (queues.activeServerId() != serverId) return emptyTransition()
        val state = queues.load(serverId)
        val entry = state.currentIndex?.let(state.entries::get) ?: return emptyTransition()
        return beginSession(entry, replacingQueue = false)
    }

    fun restoreCurrentPaused(): PlaybackQueueTransition {
        if (playback.currentSession != null) return emptyTransition()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val entry = state.currentIndex?.let(state.entries::get) ?: return emptyTransition()
        val start = newStart(entry)
        val transition = playback.startPlaying(start)
        check(transition is PlaybackTransitionResult.Applied)
        return PlaybackQueueTransition(
            snapshot(),
            start.directive(entry, shouldAutoPlay = false),
            transition.effects,
        )
    }

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
        if (enabled) {
            queues.enableShuffle(serverId, shuffleRandom)
        } else {
            queues.disableShuffle(serverId)
        }
        return editedTransition()
    }

    fun cycleRepeatMode(): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val next = when (state.repeatMode) {
            QueueRepeatMode.Off -> QueueRepeatMode.All
            QueueRepeatMode.All -> QueueRepeatMode.One
            QueueRepeatMode.One -> QueueRepeatMode.Off
        }
        queues.setRepeatMode(serverId, next)
        return editedTransition()
    }

    fun recordPlaybackEvent(event: PlaybackEngineEvent): PlaybackQueueTransition {
        val reduction = playback.recordPlaybackEvent(event)
        val accepted = reduction.disposition == PlaybackEventDisposition.AcceptedCurrentAttempt
        if (event is PlaybackEngineEvent.AdvancedToPreloaded && accepted) {
            return completePreloadedAdvance(event, reduction.effects)
        }
        if (event is PlaybackEngineEvent.EndedNaturally && accepted &&
            event.attemptId == playback.currentSession?.currentAttempt?.attemptId
        ) {
            if (preloadWillTakeOver()) {
                // The engine already holds the next entry and reports `AdvancedToPreloaded` when
                // it takes over. Starting the next entry here as well would issue a stop and a
                // fresh prepare for an item that is already playing -- the gap preload removes.
                // If the preload is discarded instead, `resumeHeldEnd` starts the next entry.
                endHeldForPreload = true
                return PlaybackQueueTransition(snapshot(), null, reduction.effects)
            }
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
                finishQueue(emptyList())
            }
        }
        return startAt(state, target)
    }

    /**
     * The queue ran out. The session is finalized and the selection STAYS on the entry that just
     * finished (§14.3): the listener sees the last track, stopped, and Play replays it. Clearing
     * the selection here presented "Nothing is playing" at the end of every album, which throws
     * away the one thing the person was just looking at.
     */
    private fun finishQueue(priorEffects: List<PlaybackCoreEffect>): PlaybackQueueTransition {
        registeredPreload = null
        endHeldForPreload = false
        val finalization = playback.clearQueue()
        return PlaybackQueueTransition(snapshot(), null, priorEffects + finalization.effects)
    }

    private fun upcomingEntry(state: QueueState): QueueEntry? {
        val current = state.currentIndex ?: return null
        if (state.repeatMode == QueueRepeatMode.One) return null
        val next = current + 1
        return when {
            next in state.entries.indices -> state.entries[next]
            state.repeatMode == QueueRepeatMode.All && state.entries.isNotEmpty() -> state.entries[0]
            else -> null
        }
    }

    private fun preloadWillTakeOver(): Boolean {
        val preload = registeredPreload ?: return false
        val serverId = queues.activeServerId() ?: return false
        return upcomingEntry(queues.load(serverId))?.queueEntryId == preload.queueEntryId
    }

    private fun completePreloadedAdvance(
        event: PlaybackEngineEvent.AdvancedToPreloaded,
        effects: List<PlaybackCoreEffect>,
    ): PlaybackQueueTransition {
        val preload = registeredPreload
        registeredPreload = null
        endHeldForPreload = false
        val serverId = queues.activeServerId()
        if (preload != null && serverId != null && preload.start.attemptId == event.newAttemptId) {
            val index = queues.load(serverId).entries.indexOfFirst {
                it.queueEntryId == preload.queueEntryId
            }
            if (index >= 0) queues.setCurrentIndex(serverId, index)
        }
        return PlaybackQueueTransition(snapshot(), null, effects)
    }

    private fun discardRegisteredPreload(): AttemptId? {
        val preload = registeredPreload ?: return null
        registeredPreload = null
        playback.discardPreloaded(preload.start.attemptId)
        return preload.start.attemptId
    }

    /**
     * After any queue edit the preload may name an entry that no longer plays next. It is then
     * discarded here, and the transition tells the owner to remove it from the engine.
     */
    private fun editedTransition(): PlaybackQueueTransition {
        val discarded = if (registeredPreload != null && !preloadWillTakeOver()) {
            discardRegisteredPreload()
        } else {
            null
        }
        return resumeHeldEnd(discarded) ?: emptyTransition().copy(discardedPreloadAttemptId = discarded)
    }

    /** Starts the entry after a held natural end, once its preload is gone. */
    private fun resumeHeldEnd(discarded: AttemptId?): PlaybackQueueTransition? {
        if (discarded == null || !endHeldForPreload) return null
        endHeldForPreload = false
        return advanceAfterNaturalCompletion(emptyList()).copy(discardedPreloadAttemptId = discarded)
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
        return finishQueue(terminalEffects)
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
        // A manual start is a fresh start, and the owner's stop removes the engine's preloaded
        // item with it. advanceToNext and replaceQueue discard every registered preload in the
        // state machine; startPlaying runs only with no current session, when none is registered
        // by this controller except after an engine teardown, which is discarded here.
        registeredPreload?.let { playback.discardPreloaded(it.start.attemptId) }
        registeredPreload = null
        endHeldForPreload = false
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

    private fun PlaybackSessionStart.directive(
        entry: QueueEntry,
        shouldAutoPlay: Boolean = true,
    ) = PlaybackQueueStartDirective(
        queueEntryId = queueEntryId,
        playbackSessionId = playbackSessionId,
        attemptId = attemptId,
        itemId = itemId,
        duration = initialDuration,
        resumePosition = resumePositions.restore(entry.providerItemId),
        shouldAutoPlay = shouldAutoPlay,
    )

    private fun nextQueueEntryId(): QueueEntryId =
        QueueEntryId(identities.next("queue-entry"))

    private fun QueueState.snapshot() = PlaybackQueueSnapshot(
        entries = entries.map {
            PlaybackQueueEntrySnapshot(
                it.queueEntryId,
                it.providerItemId,
                it.sourceContext.displayName,
            )
        },
        currentIndex = currentIndex,
        repeatMode = repeatMode,
        shuffleState = shuffleState,
        currentSession = playback.currentSession,
    )

    private fun emptyTransition(
        effects: List<PlaybackCoreEffect> = emptyList(),
    ) = PlaybackQueueTransition(snapshot(), null, effects)
}
