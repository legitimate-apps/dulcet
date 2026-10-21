package com.legitimateapps.dulcet.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Drives the queue with a changing local-file set, without treating absence as a failed listen. */
class PlaybackQueueOfflineTest {
    @Test
    fun shuffleAndRowSelectionUseOnlyItemsThatCanPlay() = withQueue { queue, available ->
        available += listOf("saved-a", "cached-b")
        val shuffled = queue.replaceAndStart(request(listOf("remote", "saved-a", "cached-b"), shuffle = true))
        assertEquals(setOf("saved-a", "cached-b"), shuffled.snapshot.entries.map { it.itemId.rawId }.toSet())
        assertNotNull(shuffled.startDirective)

        // Filtering preserves the selected original row's identity despite its changed index.
        val selected = queue.replaceAndStart(request(listOf("remote", "saved-a", "cached-b"), start = 2))
        assertEquals("cached-b", selected.startDirective?.itemId?.rawId)
        val refused = queue.replaceAndStart(request(listOf("remote", "saved-a"), start = 0))
        assertNull(refused.startDirective)
        assertEquals(selected.snapshot.entries, refused.snapshot.entries)
    }

    @Test
    fun selectingTheSecondOccurrenceOfADuplicateRetainsItsQueuePositionAfterFiltering() = withQueue { queue, available ->
        available += "duplicate"
        val started = queue.replaceAndStart(request(listOf("remote", "duplicate", "duplicate"), start = 2))
        assertEquals(1, started.snapshot.currentIndex)
        assertEquals(started.snapshot.entries[1].queueEntryId, started.startDirective?.queueEntryId)
    }

    @Test
    fun goingOfflineSkipsAnyNumberOfUnavailableEntriesForPreloadNextAndPrevious() = withQueue { queue, available ->
        val ids = listOf("first") + (1..8).map { "remote-$it" } + "last"
        available += ids
        val start = assertNotNull(queue.replaceAndStart(request(ids)).startDirective)
        available.retainAll(setOf("first", "last"))

        val preload = assertNotNull(queue.preloadNext(start.playbackSessionId).preloadDirective)
        assertEquals("last", preload.itemId.rawId)
        val next = queue.next()
        assertEquals("last", next.startDirective?.itemId?.rawId)
        assertEquals("first", queue.previous().startDirective?.itemId?.rawId)
        // An offline skip is eligibility policy, not eight failed playback attempts.
        assertEquals(ids, next.snapshot.entries.map { it.itemId.rawId })
    }

    @Test
    fun naturalCompletionUsesLocalSuccessorAndAllUnavailableRepeatTerminates() = withQueue { queue, available ->
        available += listOf("first", "remote", "last")
        val start = assertNotNull(queue.replaceAndStart(request(listOf("first", "remote", "last"))).startDirective)
        available -= "remote"
        val advanced = queue.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(start.attemptId, 180.seconds))
        assertEquals("last", advanced.startDirective?.itemId?.rawId)
        queue.cycleRepeatMode() // repeat-all
        available.clear()
        val finished = queue.next()
        assertNull(finished.startDirective)
        assertNull(finished.snapshot.currentSession)
        assertEquals(false, finished.snapshot.canSkipPastCurrent)
    }

    @Test
    fun aPreloadedEntryThatBecomesUnavailableIsDiscardedBeforeHandover() = withQueue { queue, available ->
        available += listOf("first", "next", "last")
        val start = assertNotNull(queue.replaceAndStart(request(listOf("first", "next", "last"))).startDirective)
        val preload = assertNotNull(queue.preloadNext(start.playbackSessionId).preloadDirective)
        available -= "next"
        val replacement = queue.preloadNext(start.playbackSessionId)
        assertEquals(preload.attemptId, replacement.discardedPreloadAttemptId)
        assertEquals("last", replacement.preloadDirective?.itemId?.rawId)
    }

    @Test
    fun anObservedServerOutageContinuesOntoLocalMusicWithoutChangingAccountFailurePolicy() = withQueue { queue, available ->
        val ids = listOf("streaming") + (1..8).map { "remote-$it" } + "local"
        available += ids
        val first = assertNotNull(queue.replaceAndStart(request(ids)).startDirective)
        // The owner observes connection refusal, updating reachability before delivering its event.
        available.retainAll(setOf("local"))
        val offline = queue.recordPlaybackEvent(PlaybackEngineEvent.FailedBeforeStart(first.attemptId, DomainError.Transport.Unreachable))
        assertEquals("local", offline.startDirective?.itemId?.rawId)
        val local = assertNotNull(offline.startDirective)
        // An account error remains owned by the account while that item is considered reachable.
        val stillOnline = queue.recordPlaybackEvent(PlaybackEngineEvent.FailedBeforeStart(local.attemptId, DomainError.Transport.Unreachable))
        assertNull(stillOnline.startDirective)
        assertEquals(local.playbackSessionId, stillOnline.snapshot.currentSession?.playbackSessionId)
    }

    @Test
    fun explicitRetryPermissionDoesNotMakeRemoteSuccessorsEligible() = withQueue { queue, available ->
        available += listOf("current", "remote", "cached")
        val first = assertNotNull(queue.replaceAndStart(request(listOf("current", "remote", "cached"))).startDirective)
        // Keep current eligible while the failure is recorded, as when its cache just completed.
        available.retainAll(setOf("current", "cached"))
        queue.recordPlaybackEvent(PlaybackEngineEvent.FailedBeforeStart(first.attemptId, DomainError.Transport.Unreachable))
        available -= "current" // A stale cache row may subsequently fail local-file validation.
        assertNull(queue.retryCurrent().startDirective)
        assertEquals(first.attemptId, queue.snapshot().currentSession?.currentAttempt?.attemptId)
        val retry = assertNotNull(queue.retryCurrent(allowUnavailableCurrent = true).startDirective)
        assertEquals(first.playbackSessionId, retry.playbackSessionId)
        assertEquals("current", retry.itemId.rawId)
        assertEquals("cached", queue.preloadNext(retry.playbackSessionId).preloadDirective?.itemId?.rawId)
        assertEquals("cached", queue.next().startDirective?.itemId?.rawId)
    }

    @Test
    fun explicitProbeCanReplayAfterFailureAtTheEndWithoutEnablingRemoteSuccessors() = withQueue { queue, available ->
        available += listOf("current", "remote", "cached")
        val first = assertNotNull(queue.replaceAndStart(request(listOf("current", "remote", "cached"))).startDirective)
        queue.recordPlaybackEvent(PlaybackEngineEvent.Ready(first.attemptId, 180.seconds, PlaybackSeekability.Seekable))
        queue.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(first.attemptId, PlaybackWallClockTime(1), 1.seconds))
        available.retainAll(setOf("current", "cached"))
        queue.recordPlaybackEvent(PlaybackEngineEvent.FailedAfterPartial(first.attemptId, 180.seconds, DomainError.Transport.Unreachable))
        available -= "current"
        assertNull(queue.retryCurrent().startDirective)
        val replay = assertNotNull(queue.retryCurrent(allowUnavailableCurrent = true).startDirective)
        assertEquals("current", replay.itemId.rawId)
        assertEquals(false, first.playbackSessionId == replay.playbackSessionId)
        assertNull(replay.resumePosition)
        assertEquals("cached", queue.preloadNext(replay.playbackSessionId).preloadDirective?.itemId?.rawId)
    }

    @Test
    fun explicitProbeCanStartSelectedEntryWithNoSessionWithoutChangingAvailability() = withQueue { queue, available ->
        available += "current"
        val first = assertNotNull(queue.replaceAndStart(request(listOf("current"))).startDirective)
        queue.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds))
        available.clear()
        assertNull(queue.retryCurrent().startDirective)
        val restarted = assertNotNull(queue.retryCurrent(allowUnavailableCurrent = true).startDirective)
        assertEquals("current", restarted.itemId.rawId)
        assertEquals(false, first.playbackSessionId == restarted.playbackSessionId)
        assertNull(queue.retryCurrent(allowUnavailableCurrent = true).startDirective, "A live attempt is not retried")
        assertNull(queue.next().startDirective)
    }

    private fun request(ids: List<String>, start: Int = 0, shuffle: Boolean = false) = PlaybackQueueRequest(
        ids.map { PlaybackQueueItem(ProviderItemId("account", it), 180.seconds) },
        QueueSourceContext(QueueSourceKind.Library, null, "My Library"), start, shuffle,
    )

    private fun withQueue(block: (PlaybackQueueController, MutableSet<String>) -> Unit) {
        val driver = createTestDriver()
        try {
            val database = DulcetDatabaseStore.open(driver).database
            val available = mutableSetOf<String>()
            var serial = 0
            val controller = PlaybackQueueController(
                PersistentQueueStore(database), PersistentResumePositionStore(database),
                PlaybackIdentitySource { "$it:${serial++}" }, shuffleRandom = Random(42),
                canPlayItem = { it.providerInstanceId == "account" && it.rawId in available },
            )
            block(controller, available)
        } finally {
            driver.close()
        }
    }
}
