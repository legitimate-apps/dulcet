package com.legitimateapps.dulcet.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Queue editing (§14.1, §14.2) and gapless preload registration (§12.8) in the core. */
class PlaybackQueueEditingTest {
    @Test
    fun playNextInsertsAfterTheCurrentEntryInTheGivenOrderWithoutTouchingTheSession() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b", "c"), startIndex = 1))
        val session = assertNotNull(started.startDirective).playbackSessionId

        val edited = fixture.controller.enqueue(insertion(listOf("x", "y"), QueueInsertionMode.PlayNext))

        assertEquals(listOf("a", "b", "x", "y", "c"), edited.snapshot.rawIds())
        assertEquals(1, edited.snapshot.currentIndex)
        assertNull(edited.startDirective)
        assertEquals(session, edited.snapshot.currentSession?.playbackSessionId)
        assertEquals(listOf("a", "b", "x", "y", "c"), fixture.reopenedRawIds())
        fixture.driver.close()
    }

    @Test
    fun playLaterAppendsInTheGivenOrder() {
        val fixture = fixture()
        fixture.controller.replaceAndStart(request(listOf("a", "b")))

        val edited = fixture.controller.enqueue(insertion(listOf("x", "y"), QueueInsertionMode.Append))

        assertEquals(listOf("a", "b", "x", "y"), edited.snapshot.rawIds())
        assertEquals(0, edited.snapshot.currentIndex)
        fixture.driver.close()
    }

    @Test
    fun playNextWhileShuffledGoesImmediatelyAfterTheCurrentEntryAndUnshuffleKeepsIt() {
        val fixture = fixture(shuffleSeed = 71)
        val started = fixture.controller.replaceAndStart(
            request(listOf("a", "b", "c", "d"), shuffle = true),
        )
        val current = started.snapshot.rawIds()[0]

        val edited = fixture.controller.enqueue(insertion(listOf("x"), QueueInsertionMode.PlayNext))

        assertEquals(listOf(current, "x"), edited.snapshot.rawIds().take(2))
        val unshuffled = fixture.controller.setShuffle(false)
        assertEquals(5, unshuffled.snapshot.entries.size)
        assertEquals(current, unshuffled.snapshot.rawIds()[unshuffled.snapshot.currentIndex!!])
        fixture.driver.close()
    }

    @Test
    fun enqueueWithoutAnActiveQueueIsAnInputError() {
        val fixture = fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.controller.enqueue(insertion(listOf("x"), QueueInsertionMode.Append))
        }
        fixture.driver.close()
    }

    @Test
    fun moveReordersPersistsAndTheCurrentIndexFollowsTheCurrentEntry() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b", "c", "d"), startIndex = 1))
        val session = assertNotNull(started.startDirective).playbackSessionId
        val d = started.snapshot.entries[3].queueEntryId

        val movedBeforeCurrent = fixture.controller.move(d, 0)

        assertEquals(listOf("d", "a", "b", "c"), movedBeforeCurrent.snapshot.rawIds())
        assertEquals(2, movedBeforeCurrent.snapshot.currentIndex)
        assertEquals(session, movedBeforeCurrent.snapshot.currentSession?.playbackSessionId)
        assertEquals(listOf("d", "a", "b", "c"), fixture.reopenedRawIds())

        val a = started.snapshot.entries[0].queueEntryId
        val movedAfterCurrent = fixture.controller.move(a, 3)
        assertEquals(listOf("d", "b", "c", "a"), movedAfterCurrent.snapshot.rawIds())
        assertEquals(1, movedAfterCurrent.snapshot.currentIndex)
        fixture.driver.close()
    }

    @Test
    fun moveWhileShuffledChangesOnlyThePlaybackOrder() {
        val fixture = fixture(shuffleSeed = 71)
        val started = fixture.controller.replaceAndStart(
            request(listOf("a", "b", "c", "d", "e"), shuffle = true),
        )
        val order = started.snapshot.rawIds()
        val last = started.snapshot.entries.last().queueEntryId

        val moved = fixture.controller.move(last, 1)

        assertEquals(listOf(order[0], order[4], order[1], order[2], order[3]), moved.snapshot.rawIds())
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            fixture.controller.setShuffle(false).snapshot.rawIds(),
        )
        fixture.driver.close()
    }

    @Test
    fun moveRejectsAnUnknownEntryAndAnOutOfRangeTarget() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b")))
        assertFailsWith<IllegalArgumentException> {
            fixture.controller.move(QueueEntryId("queue-entry:missing"), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.controller.move(started.snapshot.entries[1].queueEntryId, 2)
        }
        assertEquals(listOf("a", "b"), fixture.reopenedRawIds())
        fixture.driver.close()
    }

    @Test
    fun removeClosesTheGapAndRefusesTheCurrentEntry() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b", "c"), startIndex = 1))

        val removed = fixture.controller.remove(started.snapshot.entries[0].queueEntryId)

        assertEquals(listOf("b", "c"), removed.snapshot.rawIds())
        assertEquals(0, removed.snapshot.currentIndex)
        assertFailsWith<IllegalArgumentException> {
            fixture.controller.remove(started.snapshot.entries[1].queueEntryId)
        }
        assertEquals(listOf("b", "c"), fixture.reopenedRawIds())
        fixture.driver.close()
    }

    @Test
    fun clearUpcomingKeepsPlayedEntriesAndTheCurrentOne() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b", "c", "d"), startIndex = 1))
        val session = assertNotNull(started.startDirective).playbackSessionId

        val cleared = fixture.controller.clearUpcoming()

        assertEquals(listOf("a", "b"), cleared.snapshot.rawIds())
        assertEquals(1, cleared.snapshot.currentIndex)
        assertEquals(session, cleared.snapshot.currentSession?.playbackSessionId)
        assertEquals(listOf("a", "b"), fixture.reopenedRawIds())
        fixture.driver.close()
    }

    @Test
    fun jumpToStartsANewSessionAndFinalizesTheOutgoingOne() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b", "c")))
        val first = assertNotNull(started.startDirective)
        val listened = fixture.playPast(first.attemptId, 100)

        val jumped = fixture.controller.jumpTo(started.snapshot.entries[2].queueEntryId)
        val directive = assertNotNull(jumped.startDirective)

        assertEquals("c", directive.itemId.rawId)
        assertEquals(2, jumped.snapshot.currentIndex)
        assertNotEquals(first.playbackSessionId, directive.playbackSessionId)
        // The outgoing session crossed its threshold once; finalizing it must not submit again.
        assertEquals(listOf("a"), (listened + jumped.effects).submittedRawIds())
        fixture.driver.close()
    }

    @Test
    fun aRegisteredPreloadTakesOverAtTheBoundaryWithExactlyOnePlayPerSession() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b")))
        val first = assertNotNull(started.startDirective)
        val listenedA = fixture.playPast(first.attemptId, 100)

        val preload = fixture.controller.preloadNext(first.playbackSessionId)
        val next = assertNotNull(preload.preloadDirective)
        assertNull(preload.startDirective)
        assertEquals("b", next.itemId.rawId)
        assertNotEquals(first.playbackSessionId, next.playbackSessionId)
        // Registering is not starting: the current session is still the first one.
        assertEquals(first.playbackSessionId, preload.snapshot.currentSession?.playbackSessionId)
        // A second request for the same next entry registers nothing new.
        assertNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)

        val ended = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds),
        )
        // The engine owns the boundary: nothing is started, so nothing is stopped and re-prepared.
        assertNull(ended.startDirective)
        assertEquals(0, ended.snapshot.currentIndex)

        val advanced = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(first.attemptId, next.attemptId),
        )
        assertEquals(1, advanced.snapshot.currentIndex)
        assertEquals(next.playbackSessionId, advanced.snapshot.currentSession?.playbackSessionId)

        // The preloaded session is a real session: it accrues and submits on its own.
        val listenedB = fixture.playPast(next.attemptId, 100)
        val finished = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(next.attemptId, 180.seconds),
        )
        val submitted = (listenedA + ended.effects + advanced.effects + listenedB + finished.effects)
            .submittedRawIds()
        assertEquals(listOf("a", "b"), submitted, "exactly one play per session across the boundary")
        fixture.driver.close()
    }

    @Test
    fun anEditThatChangesTheNextEntryDiscardsThePreloadAndTheBoundaryStartsNormally() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b", "c")))
        val first = assertNotNull(started.startDirective)
        val next = assertNotNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        assertEquals("b", next.itemId.rawId)

        // An edit that does NOT change what plays next keeps the preload.
        val appended = fixture.controller.enqueue(insertion(listOf("z"), QueueInsertionMode.Append))
        assertNull(appended.discardedPreloadAttemptId)

        val c = started.snapshot.entries[2].queueEntryId
        val moved = fixture.controller.move(c, 1)
        assertEquals(next.attemptId, moved.discardedPreloadAttemptId)

        val ended = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds),
        )
        val directive = assertNotNull(ended.startDirective, "without a preload the core starts the next entry")
        assertEquals("c", directive.itemId.rawId)
        // A late advance into the discarded preload cannot move the queue.
        val late = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(first.attemptId, next.attemptId),
        )
        assertEquals(1, late.snapshot.currentIndex)
        assertEquals(directive.playbackSessionId, late.snapshot.currentSession?.playbackSessionId)
        fixture.driver.close()
    }

    @Test
    fun anOwnerDiscardFallsBackToAFreshStartAtTheBoundary() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b")))
        val first = assertNotNull(started.startDirective)
        val next = assertNotNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)

        val discarded = fixture.controller.discardPreload(next.attemptId)
        assertEquals(next.attemptId, discarded.discardedPreloadAttemptId)

        val ended = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds),
        )
        assertEquals("b", assertNotNull(ended.startDirective).itemId.rawId)
        fixture.driver.close()
    }

    @Test
    fun preloadDeclinesWhenNothingFollowsUnderRepeatOneAndForAResumePosition() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b")))
        val first = assertNotNull(started.startDirective)

        fixture.resumePositions.save(ProviderItemId(SERVER.value, "b"), 41.seconds)
        assertNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        fixture.resumePositions.clear(ProviderItemId(SERVER.value, "b"))

        fixture.controller.cycleRepeatMode() // all
        fixture.controller.cycleRepeatMode() // one
        assertNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        fixture.controller.cycleRepeatMode() // off

        val last = assertNotNull(fixture.controller.next().startDirective)
        assertNull(fixture.controller.preloadNext(last.playbackSessionId).preloadDirective)
        // A stale session cannot register a preload.
        assertNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        fixture.driver.close()
    }

    @Test
    fun repeatAllPreloadsTheHeadAfterTheLastEntry() {
        val fixture = fixture()
        fixture.controller.replaceAndStart(request(listOf("a", "b")))
        fixture.controller.cycleRepeatMode() // all
        val last = assertNotNull(fixture.controller.next().startDirective)

        val preload = assertNotNull(fixture.controller.preloadNext(last.playbackSessionId).preloadDirective)
        assertEquals("a", preload.itemId.rawId)
        fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(last.attemptId, 180.seconds))
        val advanced = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(last.attemptId, preload.attemptId),
        )
        assertEquals(0, advanced.snapshot.currentIndex)
        fixture.driver.close()
    }

    @Test
    fun manualNextDiscardsTheRegisteredPreloadAndStartsFresh() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b")))
        val first = assertNotNull(started.startDirective)
        val preload = assertNotNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)

        val next = assertNotNull(fixture.controller.next().startDirective)

        assertEquals("b", next.itemId.rawId)
        assertNotEquals(preload.playbackSessionId, next.playbackSessionId)
        val late = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(first.attemptId, preload.attemptId),
        )
        assertEquals(next.playbackSessionId, late.snapshot.currentSession?.playbackSessionId)
        fixture.driver.close()
    }

    /** Drives [seconds] of progressing playback and returns every effect the core produced. */
    private fun Fixture.playPast(attemptId: AttemptId, seconds: Int): List<PlaybackCoreEffect> {
        val effects = mutableListOf<PlaybackCoreEffect>()
        effects += controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(attemptId, 180.seconds, PlaybackSeekability.Seekable),
        ).effects
        effects += controller.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(
                attemptId,
                PlaybackWallClockTime(1_788_000_000_000 + monotonicSeconds * 1_000L),
                Duration.ZERO,
            ),
        ).effects
        for (second in 1..seconds) {
            effects += controller.recordPlaybackEvent(
                PlaybackEngineEvent.PositionChanged(
                    attemptId,
                    second.seconds,
                    PlaybackMonotonicTime((monotonicSeconds++).seconds),
                ),
            ).effects
        }
        return effects
    }

    private fun List<PlaybackCoreEffect>.submittedRawIds(): List<String> = mapNotNull { effect ->
        ((effect as? PlaybackCoreEffect.RecordPlaybackEvent)?.event as? RecordedPlaybackEvent.SubmittedPlay)
            ?.itemId
            ?.rawId
    }

    private fun fixture(shuffleSeed: Int = 1): Fixture {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val resumePositions = PersistentResumePositionStore(database)
        var identity = 0
        return Fixture(
            driver = driver,
            database = database,
            resumePositions = resumePositions,
            controller = PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = resumePositions,
                identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
                shuffleRandom = Random(shuffleSeed),
            ),
        )
    }

    private fun request(
        rawIds: List<String>,
        startIndex: Int? = 0,
        shuffle: Boolean = false,
    ) = PlaybackQueueRequest(
        items = rawIds.map { PlaybackQueueItem(ProviderItemId(SERVER.value, it), 180.seconds) },
        sourceContext = QueueSourceContext(
            kind = QueueSourceKind.Album,
            sourceId = ProviderItemId(SERVER.value, "album"),
            displayName = "Album",
        ),
        startIndex = if (shuffle) null else startIndex,
        shuffle = shuffle,
    )

    private fun insertion(rawIds: List<String>, mode: QueueInsertionMode) = PlaybackQueueInsertion(
        items = rawIds.map { PlaybackQueueItem(ProviderItemId(SERVER.value, it), 180.seconds) },
        sourceContext = QueueSourceContext(
            kind = QueueSourceKind.Search,
            sourceId = null,
            displayName = "Search",
        ),
        mode = mode,
    )

    private fun PlaybackQueueSnapshot.rawIds(): List<String> = entries.map { it.itemId.rawId }

    private class Fixture(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val database: com.legitimateapps.dulcet.database.DulcetDatabase,
        val resumePositions: PersistentResumePositionStore,
        val controller: PlaybackQueueController,
    ) {
        var monotonicSeconds = 1

        fun reopenedRawIds(): List<String> =
            PersistentQueueStore(database).load(SERVER).entries.map { it.providerItemId.rawId }
    }

    private companion object {
        val SERVER = ServerId("server:queue-editing")
    }
}
