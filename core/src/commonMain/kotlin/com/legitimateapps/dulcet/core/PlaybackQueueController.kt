package com.legitimateapps.dulcet.core

import kotlin.random.Random
import kotlin.time.Duration

internal fun interface PlaybackIdentitySource {
    fun next(prefix: String): String
}

internal data class PlaybackQueueItem(
    val itemId: ProviderItemId,
    val duration: Duration?,
    /**
     * The item's album, when the owner knows it. Two consecutive entries of one album never
     * crossfade: the album may be gapless, and OpenSubsonic says nothing about which ones are.
     */
    val albumRawId: String? = null,
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
    /** AutoPlay added this entry when the person's own queue ran low; Up Next lists it apart. */
    val isAutoplay: Boolean = false,
)

internal data class PlaybackQueueSnapshot(
    val entries: List<PlaybackQueueEntrySnapshot>,
    val currentIndex: Int?,
    val repeatMode: QueueRepeatMode,
    val shuffleState: QueueShuffleState,
    val currentSession: PlaybackSessionSnapshot?,
    /**
     * Whether Skip past the current entry reaches a DIFFERENT entry (§12.12): one follows it, or
     * repeat-all wraps onto another. The one predicate for Skip -- the shells present it, and an
     * automatic skip travelling forward asks the same question.
     */
    val canSkipPastCurrent: Boolean = false,
    /** The person's AutoPlay setting, as the owner last pushed it. */
    val autoplayEnabled: Boolean = false,
    /**
     * Whether AutoPlay can run now: it is on, and neither repeat nor shuffle is (each of those
     * already says what plays after the queue). Up Next shows its section only then.
     */
    val autoplayActive: Boolean = false,
    /** Where the sleep timer stops playback, when it waits for a track or the queue to end. */
    val stopPoint: PlaybackStopPoint? = null,
    /**
     * How long the current entry overlaps the next one at their boundary: zero for a gapless
     * boundary. The owner hands it to the engine for the current attempt (§12.8).
     */
    val crossfadeIntoNext: Duration = Duration.ZERO,
)

/** Where a sleep timer that waits for the music stops it. */
internal enum class PlaybackStopPoint {
    /** When the entry playing now ends: the next one is selected, paused. */
    EndOfCurrentEntry,

    /**
     * When the person's own queue ends: no repeat-all wrap and no AutoPlay entry plays. Under
     * repeat-one the queue never ends, so it stops when the repeated track does.
     */
    EndOfQueue,
}

/**
 * The queue wants AutoPlay entries (§14.8). The owner fetches up to [count] tracks like [seed],
 * none of them in [excludedRawIds], and answers with [PlaybackQueueController.appendAutoplay]
 * under the same [key] -- with an empty list when it found nothing or the fetch failed.
 */
internal data class PlaybackAutoplayRequest(
    val key: String,
    val seed: ProviderItemId,
    val excludedRawIds: Set<String>,
    val count: Int,
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
    /**
     * The entry this transition skipped past because its failure was the track's own (§12.12).
     * The owner tells the person, naming the track; the start directive is the entry after it.
     */
    val skippedAfterFailure: QueueEntryId? = null,
    /**
     * The sleep timer's stop point was reached at this natural end, and playback stopped there
     * (the next entry, if any, starts paused). The owner ends its sleep timer.
     */
    val stopPointReached: Boolean = false,
)

/** Which way the queue travelled to reach the current entry (§12.12). */
internal enum class QueueTravel { Forward, Backward }

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
    /** Dynamic account reachability/local-file eligibility; never performs a network request. */
    private val canPlayItem: (ProviderItemId) -> Boolean = { true },
) {
    private data class RegisteredPreload(
        val queueEntryId: QueueEntryId,
        val start: PlaybackSessionStart,
    )

    private val knownDurations = mutableMapOf<QueueEntryId, Duration?>()
    private val knownAlbums = mutableMapOf<QueueEntryId, String>()
    private var autoplayEnabled = false
    /** Bumped whenever an outstanding AutoPlay request stops describing what the queue wants. */
    private var autoplayGeneration = 0L
    /** The AutoPlay request last handed out; it is not handed out again ([takeAutoplayRequest]). */
    private var takenAutoplayKey: String? = null
    /** The AutoPlay request last answered, found something or not; it is no longer outstanding. */
    private var answeredAutoplayKey: String? = null
    /**
     * The queue finished at a natural end while this AutoPlay request was outstanding: when its
     * entries arrive, the first of them starts, as if they had been there in time. Only that
     * request's answer starts anything -- a later request (the setting toggled, a track added
     * to the finished queue) is a different key and only appends.
     */
    private var finishedAwaitingAutoplayKey: String? = null
    private var stopPoint: PlaybackStopPoint? = null
    private var crossfade: Duration = Duration.ZERO
    private var registeredPreload: RegisteredPreload? = null
    /**
     * The current attempt ended naturally and the core deliberately started nothing, because the
     * registered preload was about to take over. If that preload is then discarded -- it failed,
     * or an edit made it stale -- nothing else will ever advance the queue, so the discard itself
     * must start the next entry.
     */
    private var endHeldForPreload = false

    /** How the current entry was reached; an automatic skip keeps going the same way (§12.12). */
    private var travel = QueueTravel.Forward

    /**
     * The entries that have failed since playback last progressed, in order (§12.12). A skip that
     * would reach one of them, or a fifth entry in it, stops and presents instead.
     */
    private val failureChain = mutableSetOf<QueueEntryId>()

    /**
     * The entries that failed and were skipped past automatically since the person last acted on
     * playback -- played, skipped, picked an entry, or edited the queue -- or since an entry last
     * played to its end (§12.12 rule 3). An automatic skip whose target is already here stops
     * instead: a fault that breaks every entry after it has begun, which progress would otherwise
     * keep forgiving, gets one pass through the queue and no more, even under repeat-all. An entry
     * that plays to its natural end, or hands over to its preloaded successor, shows the queue is
     * healthy, so it begins a new pass: a repeat-all album with two damaged tracks plays on lap
     * after lap. Progress alone does not: a track that plays a moment and then fails still counts.
     */
    private val skippedPastFailures = mutableSetOf<QueueEntryId>()

    /**
     * The attempt a restoration started paused, until the person plays it. A restore starts no
     * sound; once the person plays, the queue plays on past a failed entry (§12.12 rule 4). So a
     * skip past this attempt's failure starts the next entry paused too, and hands the mark on;
     * the person's Play clears it. The owner reports that Play when the person presses it
     * ([recordPlayRequested]), whether or not the engine is ready yet, since a failure can come
     * before Ready. The engine's `Resumed` or `PlaybackProgressBegan` for this attempt clear it
     * too.
     */
    private var pausedStartAttempt: AttemptId? = null

    /** The server whose queue is active, or null when none is. Owners use it to refuse a foreign queue. */
    fun activeServerId(): ServerId? = queues.activeServerId()

    fun replaceAndStart(request: PlaybackQueueRequest): PlaybackQueueTransition {
        val playable = request.items.filter { canPlayItem(it.itemId) }
        if (playable.isEmpty()) return emptyTransition()
        val selectedOriginalIndex = request.startIndex ?: 0
        val selectedItem = request.items[selectedOriginalIndex]
        // Selecting an unavailable row does not silently start a different song.
        if (!request.shuffle && !canPlayItem(selectedItem.itemId)) return emptyTransition()
        skippedPastFailures.clear()
        val serverId = ServerId(request.items.first().itemId.providerInstanceId)
        val previousRepeatMode = queues.load(serverId).repeatMode
        knownDurations.clear()
        knownAlbums.clear()
        autoplayGeneration++
        val entries = playable.map { item ->
            QueueEntry(
                queueEntryId = nextQueueEntryId(),
                providerItemId = item.itemId,
                sourceContext = request.sourceContext,
                addedBy = QueueAddedBy.PlayNow,
            ).also { remember(it, item) }
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
            request.items.take(selectedOriginalIndex).count { canPlayItem(it.itemId) }
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
        skippedPastFailures.clear()
        val entries = insertion.items.filter { canPlayItem(it.itemId) }.map { item ->
            QueueEntry(
                queueEntryId = nextQueueEntryId(),
                providerItemId = item.itemId,
                sourceContext = insertion.sourceContext,
                addedBy = when (insertion.mode) {
                    QueueInsertionMode.PlayNext -> QueueAddedBy.PlayNext
                    QueueInsertionMode.Append -> QueueAddedBy.AddToQueue
                },
            ).also { remember(it, item) }
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
        // Add to Queue appends to the person's own queue, which ends where AutoPlay begins: the
        // entries go in before the first upcoming AutoPlay entry, in the order given.
        val before = queues.load(serverId)
        val firstAutoplay = before.firstUpcomingAutoplayIndex()
            ?.takeIf { insertion.mode == QueueInsertionMode.Append }
        ordered.forEachIndexed { offset, entry ->
            queues.insert(serverId, entry, insertion.mode)
            if (firstAutoplay != null) queues.move(serverId, entry.queueEntryId, firstAutoplay + offset)
        }
        return editedTransition()
    }

    /**
     * The person's AutoPlay setting (§14.8). Turning it off removes the AutoPlay entries that
     * have not played yet; the current one, if AutoPlay chose it, plays on.
     */
    fun setAutoplay(enabled: Boolean): PlaybackQueueTransition {
        if (autoplayEnabled == enabled) return emptyTransition()
        autoplayEnabled = enabled
        autoplayGeneration++
        val serverId = queues.activeServerId() ?: return emptyTransition()
        if (!enabled) removeUpcomingAutoplay(serverId)
        return editedTransition()
    }

    /**
     * The AutoPlay request the queue wants answered now, or null. Each request is handed out
     * once: the owner answers it with [appendAutoplay], and a request that found nothing is not
     * repeated until the queue changes at its end.
     */
    fun takeAutoplayRequest(): PlaybackAutoplayRequest? {
        val serverId = queues.activeServerId() ?: return null
        val state = queues.load(serverId)
        val key = state.autoplayKey() ?: return null
        if (key == takenAutoplayKey) return null
        takenAutoplayKey = key
        return PlaybackAutoplayRequest(
            key = key,
            seed = state.entries.last().providerItemId,
            excludedRawIds = state.entries.mapTo(mutableSetOf()) { it.providerItemId.rawId },
            count = AUTOPLAY_BATCH,
        )
    }

    /**
     * The answer to the AutoPlay request [key]. Items already in the queue are dropped. A stale
     * answer -- the queue changed at its end, or AutoPlay stopped being active -- changes nothing.
     */
    fun appendAutoplay(key: String, items: List<PlaybackQueueItem>): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        if (key != takenAutoplayKey || state.autoplayKey() != key) return emptyTransition()
        answeredAutoplayKey = key
        val awaited = finishedAwaitingAutoplayKey == key
        finishedAwaitingAutoplayKey = null
        val present = state.entries.mapTo(mutableSetOf()) { it.providerItemId.rawId }
        val fresh = items
            .filter { it.itemId.providerInstanceId == serverId.value }
            .filter { canPlayItem(it.itemId) }
            .filter { present.add(it.itemId.rawId) }
        if (fresh.isEmpty()) return emptyTransition()
        val entries = fresh.map { item ->
            QueueEntry(
                queueEntryId = nextQueueEntryId(),
                providerItemId = item.itemId,
                sourceContext = QueueSourceContext(QueueSourceKind.Library, null, AUTOPLAY_SOURCE_NAME),
                addedBy = QueueAddedBy.Autoplay,
            ).also { remember(it, item) }
        }
        entries.forEach { queues.insert(serverId, it, QueueInsertionMode.Append) }
        if (awaited && playback.currentSession == null) {
            val updated = queues.load(serverId)
            val current = updated.currentIndex
            if (current != null && current + 1 in updated.entries.indices) {
                return startAt(updated, current + 1)
            }
        }
        return editedTransition()
    }

    /**
     * Sets or clears where the sleep timer stops playback. A preload for the boundary the stop
     * point now owns is discarded: that boundary no longer hands over.
     */
    fun setStopPoint(point: PlaybackStopPoint?): PlaybackQueueTransition {
        if (stopPoint == point) return emptyTransition()
        stopPoint = point
        return editedTransition()
    }

    /**
     * The person's crossfade length; zero is off. It applies at the next boundary that may
     * crossfade (see [PlaybackQueueSnapshot.crossfadeIntoNext]).
     */
    fun setCrossfade(duration: Duration): PlaybackQueueTransition {
        require(duration >= Duration.ZERO && duration <= PlaybackCrossfade.MAXIMUM)
        crossfade = duration
        return emptyTransition()
    }

    private fun remember(entry: QueueEntry, item: PlaybackQueueItem) {
        knownDurations[entry.queueEntryId] = item.duration
        item.albumRawId?.let { knownAlbums[entry.queueEntryId] = it }
    }

    private fun QueueState.firstUpcomingAutoplayIndex(): Int? {
        if (shuffleState == QueueShuffleState.Enabled) return null
        val from = (currentIndex ?: return null) + 1
        return (from until entries.size).firstOrNull { entries[it].addedBy == QueueAddedBy.Autoplay }
    }

    private fun QueueState.autoplayIsActive(): Boolean =
        autoplayEnabled && repeatMode == QueueRepeatMode.Off &&
            shuffleState == QueueShuffleState.Disabled

    /** The key of the AutoPlay request this queue wants, or null when it wants none. */
    private fun QueueState.autoplayKey(): String? {
        if (!autoplayIsActive() || stopPoint == PlaybackStopPoint.EndOfQueue) return null
        val current = currentIndex ?: return null
        if (entries.size - 1 - current >= AUTOPLAY_LOW_WATER) return null
        return "$autoplayGeneration:${entries.last().queueEntryId.value}"
    }

    private fun removeUpcomingAutoplay(serverId: ServerId) {
        val state = queues.load(serverId)
        val current = state.currentIndex ?: -1
        state.entries.drop(current + 1)
            .filter { it.addedBy == QueueAddedBy.Autoplay }
            .forEach {
                queues.remove(serverId, it.queueEntryId)
                knownDurations.remove(it.queueEntryId)
                knownAlbums.remove(it.queueEntryId)
            }
    }

    /** Moves an entry to [toIndex] in the listener-visible order. The current session continues. */
    fun move(queueEntryId: QueueEntryId, toIndex: Int): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        skippedPastFailures.clear()
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
        skippedPastFailures.clear()
        queues.remove(serverId, queueEntryId)
        knownDurations.remove(queueEntryId)
        return editedTransition()
    }

    fun clearUpcoming(): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        skippedPastFailures.clear()
        queues.removeUpcoming(serverId)
        return editedTransition()
    }

    /**
     * Play on the selected entry when no session exists. It resumes the entry's saved position
     * (§15.5): it is the same listen picked up again. Two states leave no session behind:
     * - an engine teardown (`EngineTornDown`) finalized the session part way through, saving its
     *   position, and Play continues that listen from there;
     * - a finished queue (§14.3) keeps its last entry selected, and that entry has no saved
     *   position to resume, so Play replays it from the start as a new session. A natural end
     *   clears the position (`EndedNaturally`); Next past the last entry and Previous before the
     *   first one, which finish the queue part way through a listen, clear it too ([moveBy]).
     * Previous with no session restarts the entry instead: that is [replayCurrent].
     */
    fun startCurrent(): PlaybackQueueTransition {
        if (playback.currentSession != null) return emptyTransition()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val index = state.currentIndex ?: return emptyTransition()
        skippedPastFailures.clear()
        return startAt(state, index, resume = true)
    }

    /**
     * Registers the entry that will play after the current one as a preloaded session (§12.8) and
     * returns its directive for the owner to resolve and hand to the engine's `preloadNext`.
     *
     * Declines — returns no directive — when nothing follows, under repeat-one (which restarts
     * through a fresh start), and when the named session is not current. A preloaded item begins at
     * zero, as every non-resuming start does (§15.5), and the advance onto it clears its saved
     * position the way a start does, so a next item with a saved position -- one skipped part-way
     * through, or the playing song queued again -- keeps its gapless start.
     */
    fun preloadNext(playbackSessionId: PlaybackSessionId): PlaybackQueueTransition {
        if (!acceptsCommand(playbackSessionId)) return emptyTransition()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val next = upcomingEntry(state)
        val existing = registeredPreload
        if (existing != null && existing.queueEntryId == next?.queueEntryId) return emptyTransition()
        val discarded = discardRegisteredPreload()
        if (next == null) {
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
        skippedPastFailures.clear()
        return startAt(state, index)
    }

    /**
     * Try Again on the selected entry (§12.1). A failure -- before playback started or after
     * partial playback -- is retried inside its session: a new attempt, the accumulator carried
     * across, so one listen interrupted by a failure is one play, not two and not none. A partial
     * failure resumes from the position it saved (§15.5). With no session, as a finished queue
     * leaves it, the selected entry starts. A session that has not failed changes nothing: there
     * is nothing to try again.
     */
    fun retryCurrent(allowUnavailableCurrent: Boolean = false): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val index = state.currentIndex ?: return emptyTransition()
        val entry = state.entries[index]
        // An explicit, contact-authorized probe may retry this entry without changing the
        // availability predicate used by passive continuation and preloading.
        if (!allowUnavailableCurrent && !canPlayItem(entry.providerItemId)) return emptyTransition()
        skippedPastFailures.clear()
        val session = playback.currentSession ?: return startAt(state, index, allowUnavailableCurrent = allowUnavailableCurrent)
        val failed = session.currentAttempt
        if (session.queueEntryId != entry.queueEntryId || failed.phase != PlaybackAttemptPhase.Failed) {
            return emptyTransition()
        }
        // A partial failure can leave a preload registered for the attempt that failed; the retried
        // attempt registers its own once it progresses, so this one must not outlive it.
        val discarded = discardRegisteredPreload()
        endHeldForPreload = false
        if (session.failedAtTheEnd) return replayAfterFailureAtTheEnd(entry, discarded)
        val attemptId = AttemptId(identities.next("attempt"))
        val retry = playback.retryAfterFailure(attemptId)
        check(retry is PlaybackTransitionResult.Applied)
        return PlaybackQueueTransition(
            snapshot = snapshot(),
            startDirective = PlaybackQueueStartDirective(
                queueEntryId = entry.queueEntryId,
                playbackSessionId = session.playbackSessionId,
                attemptId = attemptId,
                itemId = entry.providerItemId,
                duration = knownDurations[entry.queueEntryId],
                resumePosition = resumePositions.restore(entry.providerItemId),
                shouldAutoPlay = true,
            ),
            effects = retry.effects,
            discardedPreloadAttemptId = discarded,
        )
    }

    /**
     * Try Again after a failure at the end is a replay, not a resumption: nothing of that listen
     * is left to play, so -- as repeat-one does -- the session is finalized and a new one plays the
     * entry from the start (§12.1). Resuming inside the old session would have played the track
     * again from zero into an accumulator that had already submitted, so the second listen never
     * counted. A position saved at the end (a listen too short to be a play) is cleared rather
     * than kept for the next time the item starts.
     */
    private fun replayAfterFailureAtTheEnd(
        entry: QueueEntry,
        discarded: AttemptId?,
    ): PlaybackQueueTransition {
        val start = newStart(entry)
        val transition = playback.repeatOne(start.playbackSessionId, start.attemptId)
        check(transition is PlaybackTransitionResult.Applied)
        return PlaybackQueueTransition(
            snapshot = snapshot(),
            startDirective = start.directive(entry),
            effects = transition.effects + PlaybackCoreEffect.ClearResumePosition(entry.providerItemId),
            discardedPreloadAttemptId = discarded,
        )
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

    /**
     * Play after a stop (§12.1): restarts the selected entry, as [restartCurrent] does, only when
     * the current attempt was STOPPED -- its terminal outcome is `Skipped`. `Stopped` also follows a
     * natural end, and while an end is held for a preload (§12.8) the ended session stays current
     * until `AdvancedToPreloaded`: restarting there would discard the preload the engine is about
     * to play and replay the ended entry over it. So a natural end, a held end, a live, failed or
     * absent session all change nothing.
     */
    fun restartAfterStop(): PlaybackQueueTransition {
        if (endHeldForPreload) return emptyTransition()
        val session = playback.currentSession ?: return emptyTransition()
        val attempt = session.currentAttempt
        if (attempt.phase != PlaybackAttemptPhase.Stopped) return emptyTransition()
        val outcome = session.terminalOutcomes.lastOrNull { it.attemptId == attempt.attemptId }
        if (outcome !is PlaybackTerminalOutcome.Skipped) return emptyTransition()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        return restartCurrent(serverId)
    }

    /**
     * Transport restart keeps the persisted selection and every queue entry identity. It is the
     * same listen picked up again -- Play after a stop -- so it resumes from the saved position
     * (§15.5).
     */
    fun restartCurrent(serverId: ServerId): PlaybackQueueTransition {
        if (queues.activeServerId() != serverId) return emptyTransition()
        val state = queues.load(serverId)
        val entry = state.currentIndex?.let(state.entries::get) ?: return emptyTransition()
        return beginSession(entry, replacingQueue = false, resume = true)
    }

    /**
     * Previous that restarts the selected entry rather than moving: a new session of the same
     * entry, with or without a current one, keeping the selection and every queue entry identity.
     * Unlike [restartCurrent] it is not the same listen picked up again: it plays from the top and
     * clears the entry's saved position after the outgoing session's own write (§15.5), as every
     * other non-resuming start does. A [serverId] that is not the active queue's changes nothing;
     * without one, the active queue's selected entry restarts.
     */
    fun replayCurrent(serverId: ServerId? = null): PlaybackQueueTransition {
        val active = queues.activeServerId() ?: return emptyTransition()
        if (serverId != null && serverId != active) return emptyTransition()
        val state = queues.load(active)
        val entry = state.currentIndex?.let(state.entries::get) ?: return emptyTransition()
        skippedPastFailures.clear()
        return beginSession(entry, replacingQueue = false)
    }

    /**
     * The person pressed Play on the current session (§12.12 rule 4). The owner reports it as the
     * person presses it, before or after the engine is ready, and the core then lets a skip past
     * this attempt's failure play on: the person asked for sound. Changes nothing else, and
     * nothing for a session that is not current.
     */
    fun recordPlayRequested(playbackSessionId: PlaybackSessionId): PlaybackQueueTransition {
        if (!acceptsCommand(playbackSessionId)) return emptyTransition()
        skippedPastFailures.clear()
        if (playback.currentSession?.currentAttempt?.attemptId == pausedStartAttempt) {
            pausedStartAttempt = null
        }
        return emptyTransition()
    }

    fun restoreCurrentPaused(): PlaybackQueueTransition {
        if (playback.currentSession != null) return emptyTransition()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val entry = state.currentIndex?.let(state.entries::get) ?: return emptyTransition()
        if (!canPlayItem(entry.providerItemId)) return emptyTransition()
        val start = newStart(entry)
        val transition = playback.startPlaying(start)
        check(transition is PlaybackTransitionResult.Applied)
        travel = QueueTravel.Forward
        pausedStartAttempt = start.attemptId
        skippedPastFailures.clear()
        return PlaybackQueueTransition(
            snapshot(),
            start.directive(entry, shouldAutoPlay = false, resume = true),
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
        skippedPastFailures.clear()
        autoplayGeneration++
        if (enabled) {
            // A shuffled queue says what plays after it; AutoPlay's own picks do not belong in it.
            removeUpcomingAutoplay(serverId)
            queues.enableShuffle(serverId, shuffleRandom)
        } else {
            queues.disableShuffle(serverId)
        }
        return editedTransition()
    }

    fun cycleRepeatMode(): PlaybackQueueTransition {
        val serverId = queues.activeServerId() ?: return emptyTransition()
        skippedPastFailures.clear()
        val state = queues.load(serverId)
        val next = when (state.repeatMode) {
            QueueRepeatMode.Off -> QueueRepeatMode.All
            QueueRepeatMode.All -> QueueRepeatMode.One
            QueueRepeatMode.One -> QueueRepeatMode.Off
        }
        queues.setRepeatMode(serverId, next)
        autoplayGeneration++
        // Repeat already says what plays after the queue, so AutoPlay's picks leave it.
        if (next != QueueRepeatMode.Off) removeUpcomingAutoplay(serverId)
        return editedTransition()
    }

    fun recordPlaybackEvent(event: PlaybackEngineEvent): PlaybackQueueTransition {
        val reduction = playback.recordPlaybackEvent(event)
        val accepted = reduction.disposition == PlaybackEventDisposition.AcceptedCurrentAttempt
        // Only the current session's current attempt speaks for the entry the person reached. A
        // registered preload's attempt is accepted too, for its own session, and must never move
        // the queue: that entry has not been reached (§12.8).
        val forCurrentAttempt = accepted &&
            event.attemptId == playback.currentSession?.currentAttempt?.attemptId
        if (event is PlaybackEngineEvent.PlaybackProgressBegan && forCurrentAttempt) {
            failureChain.clear()
        }
        if (forCurrentAttempt && event.attemptId == pausedStartAttempt &&
            (event is PlaybackEngineEvent.PlaybackProgressBegan || event is PlaybackEngineEvent.Resumed)
        ) {
            pausedStartAttempt = null
        }
        if (forCurrentAttempt) {
            val error = when (event) {
                is PlaybackEngineEvent.FailedBeforeStart -> event.error
                is PlaybackEngineEvent.FailedAfterPartial -> event.error
                else -> null
            }
            if (error != null) return afterFailure(event.attemptId, error, reduction.effects)
        }
        if (event is PlaybackEngineEvent.AdvancedToPreloaded && accepted) {
            return completePreloadedAdvance(event, reduction.effects)
        }
        if (event is PlaybackEngineEvent.EndedNaturally && accepted &&
            event.attemptId == playback.currentSession?.currentAttempt?.attemptId
        ) {
            // A track played to its end: the queue is healthy, and a new pass begins (rule 3).
            skippedPastFailures.clear()
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

    /**
     * A failure of the entry the person reached (§12.12). The track's own failure moves the queue
     * one entry further in the direction that reached it -- a next-item advance, so the failed
     * entry's session ends and records no play -- unless the chain guard stops it. Anything else
     * leaves the failed session current for the owner to present, as before.
     */
    private fun afterFailure(
        attemptId: AttemptId,
        error: DomainError,
        effects: List<PlaybackCoreEffect>,
    ): PlaybackQueueTransition {
        val stop = PlaybackQueueTransition(snapshot(), null, effects)
        val owner = playbackFailureOwner(error)
        if (owner == PlaybackFailureOwner.NotAFailure) return stop
        val serverId = queues.activeServerId() ?: return stop
        val state = queues.load(serverId)
        val failed = state.currentIndex?.let(state.entries::get) ?: return stop
        if (playback.currentSession?.queueEntryId != failed.queueEntryId) return stop
        // A request can be the first observation that this account went offline while the OS
        // still has a network. The owner updates eligibility before delivering Unreachable.
        // Continue over local files without charging absent remote songs as corrupt tracks.
        if (error == DomainError.Transport.Unreachable && !canPlayItem(failed.providerItemId)) {
            val target = state.otherEntryIndex(travel) ?: return stop
            val paused = attemptId == pausedStartAttempt
            val continued = startAt(state, target, effects, travel = travel)
            if (paused) pausedStartAttempt = continued.startDirective?.attemptId
            return continued.copy(startDirective = continued.startDirective?.copy(shouldAutoPlay = !paused))
        }
        failureChain += failed.queueEntryId
        if (owner != PlaybackFailureOwner.Track) return stop
        if (failureChain.size >= MAX_CONSECUTIVE_FAILED_ENTRIES) return stop
        val target = state.otherEntryIndex(travel) ?: return stop
        if (state.entries[target].queueEntryId in failureChain) return stop
        // An entry already skipped past since the person last acted failed then too: one pass.
        if (state.entries[target].queueEntryId in skippedPastFailures) return stop
        val paused = attemptId == pausedStartAttempt
        skippedPastFailures += failed.queueEntryId
        val skipped = startAt(state, target, effects, travel = travel)
        if (paused) pausedStartAttempt = skipped.startDirective?.attemptId
        return skipped.copy(
            startDirective = skipped.startDirective?.copy(shouldAutoPlay = !paused),
            skippedAfterFailure = failed.queueEntryId,
        )
    }

    fun snapshot(): PlaybackQueueSnapshot {
        val serverId = queues.activeServerId()
            ?: return PlaybackQueueSnapshot(
                entries = emptyList(),
                currentIndex = null,
                repeatMode = QueueRepeatMode.Off,
                shuffleState = QueueShuffleState.Disabled,
                currentSession = playback.currentSession,
                canSkipPastCurrent = false,
                autoplayEnabled = autoplayEnabled,
                stopPoint = stopPoint,
            )
        return queues.load(serverId).snapshot()
    }

    /**
     * Next or Previous. Stepping off either end of the queue (no repeat-all) finishes it: the
     * session is finalized, the entry stays selected, and its saved position is cleared after the
     * outgoing session's own write. The person moved on from that listen, so Play there replays
     * the entry from the start, as after any finished queue ([startCurrent]), and a relaunch does
     * not restore the position either.
     */
    private fun moveBy(delta: Int): PlaybackQueueTransition {
        skippedPastFailures.clear()
        val serverId = queues.activeServerId() ?: return emptyTransition()
        val state = queues.load(serverId)
        val current = state.currentIndex ?: return emptyTransition()
        val direction = if (delta > 0) QueueTravel.Forward else QueueTravel.Backward
        val target = state.steppedIndex(direction) ?: return finishQueue(emptyList()).let { finished ->
            finished.copy(
                effects = finished.effects +
                    PlaybackCoreEffect.ClearResumePosition(state.entries[current].providerItemId),
            )
        }
        return startAt(state, target, travel = direction)
    }

    /**
     * The entry one step from the current one in [direction], following the repeat mode exactly
     * as Next and Previous do: repeat-all wraps, and nothing else does. Null when there is none.
     * Under repeat-all a one-entry queue steps onto itself, which is what Next does there.
     */
    private fun QueueState.steppedIndex(direction: QueueTravel): Int? {
        val current = currentIndex ?: return null
        val delta = if (direction == QueueTravel.Forward) 1 else -1
        var target = current
        // Eligibility can change after the queue was saved (for example when going offline).
        // Walk at most one lap so an entirely unavailable repeat-all queue always terminates.
        repeat(entries.size) {
            target += delta
            if (target !in entries.indices) {
                if (repeatMode != QueueRepeatMode.All || entries.isEmpty()) return null
                target = if (delta > 0) 0 else entries.lastIndex
            }
            if (canPlayItem(entries[target].providerItemId)) return target
        }
        return null
    }

    /**
     * The single Skip predicate (§12.12): the entry a skip in [direction] reaches, when it is a
     * DIFFERENT entry. Skip availability in every shell and every automatic skip ask this.
     */
    private fun QueueState.otherEntryIndex(direction: QueueTravel): Int? =
        steppedIndex(direction)?.takeIf { it != currentIndex }

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
        // A stop point owns this boundary: nothing hands over at it (§12.8).
        stopPoint?.let { if (state.stopsAfterCurrent(it)) return null }
        if (state.repeatMode == QueueRepeatMode.One) return null
        return state.steppedIndex(QueueTravel.Forward)?.let(state.entries::get)
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
        travel = QueueTravel.Forward
        pausedStartAttempt = null
        // The engine handed over to the preloaded entry: the outgoing one played to its end, and
        // the contract does not promise an `EndedNaturally` first. A new pass begins (rule 3).
        skippedPastFailures.clear()
        val serverId = queues.activeServerId()
        if (preload != null && serverId != null && preload.start.attemptId == event.newAttemptId) {
            val index = queues.load(serverId).entries.indexOfFirst {
                it.queueEntryId == preload.queueEntryId
            }
            if (index >= 0) queues.setCurrentIndex(serverId, index)
            // The handover is a start from the top (§15.5): clear the item's saved position after
            // the outgoing session's own write, as beginSession does, so a relaunch cannot restore it.
            return PlaybackQueueTransition(
                snapshot(),
                null,
                effects + PlaybackCoreEffect.ClearResumePosition(preload.start.itemId),
            )
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
        val point = stopPoint
        if (point != null && state.stopsAfterCurrent(point)) {
            // The sleep timer stops here. The entry that would have played next is selected,
            // paused, so Play carries on from it; with nothing after it the queue finishes.
            stopPoint = null
            val target = when (point) {
                PlaybackStopPoint.EndOfCurrentEntry -> state.otherEntryIndex(QueueTravel.Forward)
                PlaybackStopPoint.EndOfQueue -> state.steppedIndex(QueueTravel.Forward)?.takeIf {
                    it > current && state.repeatMode != QueueRepeatMode.One
                }
            }
            val stopped = if (target == null) {
                finishQueue(terminalEffects)
            } else {
                val started = startAt(state, target, terminalEffects)
                pausedStartAttempt = started.startDirective?.attemptId
                started.copy(startDirective = started.startDirective?.copy(shouldAutoPlay = false))
            }
            return stopped.copy(stopPointReached = true)
        }
        if (state.repeatMode == QueueRepeatMode.One) {
            val entry = state.entries[current]
            if (!canPlayItem(entry.providerItemId)) return finishQueue(terminalEffects)
            val start = newStart(entry)
            val transition = playback.repeatOne(start.playbackSessionId, start.attemptId)
            check(transition is PlaybackTransitionResult.Applied)
            travel = QueueTravel.Forward
            pausedStartAttempt = null
            return PlaybackQueueTransition(
                state.snapshot(),
                start.directive(entry),
                terminalEffects + transition.effects,
            )
        }
        state.steppedIndex(QueueTravel.Forward)?.let { nextIndex ->
            return startAt(state, nextIndex, terminalEffects)
        }
        // AutoPlay was asked for more and has not answered yet: its first entry starts on arrival.
        val outstanding = takenAutoplayKey?.takeIf { it != answeredAutoplayKey }
        finishedAwaitingAutoplayKey = outstanding?.takeIf { state.autoplayKey() == it }
        return finishQueue(terminalEffects)
    }

    /** Whether [point] stops playback at the end of the current entry. */
    private fun QueueState.stopsAfterCurrent(point: PlaybackStopPoint): Boolean = when (point) {
        PlaybackStopPoint.EndOfCurrentEntry -> true
        PlaybackStopPoint.EndOfQueue -> {
            val next = steppedIndex(QueueTravel.Forward)
            repeatMode == QueueRepeatMode.One || next == null || next <= (currentIndex ?: -1) ||
                entries[next].addedBy == QueueAddedBy.Autoplay
        }
    }

    /**
     * How long the current entry overlaps the next at their boundary. Zero -- gapless -- when
     * crossfade is off, when nothing different follows, when a stop point owns the boundary, for
     * a track too short to overlap, and between two entries of one album: OpenSubsonic does not
     * say which albums are gapless, so an album played through keeps every join it was mastered
     * with.
     */
    private fun QueueState.crossfadeIntoNext(): Duration {
        if (crossfade == Duration.ZERO) return Duration.ZERO
        val current = currentIndex?.let(entries::get) ?: return Duration.ZERO
        val next = upcomingEntry(this) ?: return Duration.ZERO
        if (next.queueEntryId == current.queueEntryId) return Duration.ZERO
        if (sameAlbum(current, next)) return Duration.ZERO
        val duration = knownDurations[current.queueEntryId]
            ?: playback.currentSession?.takeIf { it.queueEntryId == current.queueEntryId }
                ?.currentAttempt?.duration
        if (duration != null && duration < crossfade * PlaybackCrossfade.MINIMUM_TRACK_MULTIPLE) {
            return Duration.ZERO
        }
        return crossfade
    }

    private fun sameAlbum(a: QueueEntry, b: QueueEntry): Boolean {
        val albumA = knownAlbums[a.queueEntryId]
        val albumB = knownAlbums[b.queueEntryId]
        if (albumA != null && albumB != null) return albumA == albumB
        return a.sourceContext.kind == QueueSourceKind.Album &&
            a.sourceContext.sourceId != null &&
            a.sourceContext.sourceId == b.sourceContext.sourceId
    }

    private fun startAt(
        state: QueueState,
        index: Int,
        priorEffects: List<PlaybackCoreEffect> = emptyList(),
        travel: QueueTravel = QueueTravel.Forward,
        resume: Boolean = false,
        allowUnavailableCurrent: Boolean = false,
    ): PlaybackQueueTransition {
        val entry = state.entries[index]
        if (!allowUnavailableCurrent && !canPlayItem(entry.providerItemId)) return emptyTransition(priorEffects)
        queues.setCurrentIndex(state.serverId, index)
        val transition = beginSession(entry, replacingQueue = false, travel = travel, resume = resume,
            allowUnavailableCurrent = allowUnavailableCurrent)
        return transition.copy(effects = priorEffects + transition.effects)
    }

    /**
     * Only [resume] picks up the listen the saved position belongs to (§15.5). Every other start --
     * Play on an album, playlist or track, Next, Previous, an Up Next tap, an automatic advance --
     * plays the item from the top and clears its saved position, after the outgoing session's
     * own write, so a relaunch before the new session saves anything cannot restore the old one.
     */
    private fun beginSession(
        entry: QueueEntry,
        replacingQueue: Boolean,
        travel: QueueTravel = QueueTravel.Forward,
        resume: Boolean = false,
        allowUnavailableCurrent: Boolean = false,
    ): PlaybackQueueTransition {
        if (!allowUnavailableCurrent && !canPlayItem(entry.providerItemId)) return emptyTransition()
        // Anything but Previous reaches the entry going forward, and so does an automatic skip
        // that did not start from a Previous (§12.12).
        this.travel = travel
        pausedStartAttempt = null
        // A manual start is a fresh start, and the owner's stop removes the engine's preloaded
        // item with it. advanceToNext and replaceQueue discard every registered preload in the
        // state machine; startPlaying runs only with no current session, when none is registered
        // by this controller except after an engine teardown, which is discarded here.
        registeredPreload?.let { playback.discardPreloaded(it.start.attemptId) }
        registeredPreload = null
        endHeldForPreload = false
        finishedAwaitingAutoplayKey = null
        val start = newStart(entry)
        val coreTransition = when {
            playback.currentSession == null -> playback.startPlaying(start)
            replacingQueue -> playback.replaceQueue(start)
            else -> playback.advanceToNext(start)
        }
        check(coreTransition is PlaybackTransitionResult.Applied)
        return PlaybackQueueTransition(
            snapshot(),
            start.directive(entry, resume = resume),
            if (resume) {
                coreTransition.effects
            } else {
                coreTransition.effects + PlaybackCoreEffect.ClearResumePosition(entry.providerItemId)
            },
        )
    }

    private fun newStart(entry: QueueEntry): PlaybackSessionStart = PlaybackSessionStart(
        queueEntryId = entry.queueEntryId,
        playbackSessionId = PlaybackSessionId(identities.next("session")),
        attemptId = AttemptId(identities.next("attempt")),
        itemId = entry.providerItemId,
        initialDuration = knownDurations[entry.queueEntryId],
        playlistContextId = entry.sourceContext.sourceId
            .takeIf { entry.sourceContext.kind == QueueSourceKind.Playlist },
    )

    /** A start plays from the top unless it [resume]s the listen its saved position belongs to. */
    private fun PlaybackSessionStart.directive(
        entry: QueueEntry,
        shouldAutoPlay: Boolean = true,
        resume: Boolean = false,
    ) = PlaybackQueueStartDirective(
        queueEntryId = queueEntryId,
        playbackSessionId = playbackSessionId,
        attemptId = attemptId,
        itemId = itemId,
        duration = initialDuration,
        resumePosition = if (resume) resumePositions.restore(entry.providerItemId) else null,
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
                isAutoplay = it.addedBy == QueueAddedBy.Autoplay,
            )
        },
        currentIndex = currentIndex,
        repeatMode = repeatMode,
        shuffleState = shuffleState,
        currentSession = playback.currentSession,
        canSkipPastCurrent = otherEntryIndex(QueueTravel.Forward) != null,
        autoplayEnabled = autoplayEnabled,
        autoplayActive = autoplayIsActive(),
        stopPoint = stopPoint,
        crossfadeIntoNext = crossfadeIntoNext(),
    )

    private fun emptyTransition(
        effects: List<PlaybackCoreEffect> = emptyList(),
    ) = PlaybackQueueTransition(snapshot(), null, effects)

    private companion object {
        /**
         * Five entries failing in a row with nothing progressing between them says more about
         * the server than about the tracks, and each automatic skip costs a request (§12.12).
         */
        const val MAX_CONSECUTIVE_FAILED_ENTRIES = 5

        /** AutoPlay asks for more when fewer entries than this follow the current one. */
        const val AUTOPLAY_LOW_WATER = 2

        /** How many tracks one AutoPlay request asks for. */
        const val AUTOPLAY_BATCH = 10

        /** The source name AutoPlay entries carry; shells present their own localized label. */
        const val AUTOPLAY_SOURCE_NAME = "AutoPlay"
    }
}
