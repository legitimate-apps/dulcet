package com.legitimateapps.dulcet.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class PlaybackQueueAccountSwitchTest {
    @Test
    fun switchingBackRestoresEachAccountsOrderSelectionRepeatShuffleAndResume() {
        val fixture = Fixture()
        try {
            val a = fixture.controller.replaceAndStart(request("a", startIndex = 1))
            fixture.apply(a)
            fixture.controller.cycleRepeatMode()
            val savedA = fixture.controller.setShuffle(true).snapshot
            fixture.controller.recordPlaybackEvent(
                PlaybackEngineEvent.Paused(assertNotNull(a.startDirective).attemptId, 41.seconds),
            ) // The switch must save the final position even if the event's effects have not run.

            val emptyB = fixture.controller.selectAccount("b")
            fixture.apply(emptyB)
            assertEquals(emptyList(), emptyB.snapshot.entries)
            assertNull(emptyB.snapshot.currentSession)
            assertNull(emptyB.startDirective)
            assertEquals(41.seconds, fixture.resumes.restore(ProviderItemId("a", "same")))

            val b = fixture.controller.replaceAndStart(request("b", startIndex = 1))
            fixture.apply(b)
            fixture.controller.cycleRepeatMode()
            val savedB = fixture.controller.cycleRepeatMode().snapshot
            fixture.controller.recordPlaybackEvent(
                PlaybackEngineEvent.Paused(assertNotNull(b.startDirective).attemptId, 17.seconds),
            )
            val selectedA = fixture.controller.selectAccount("a")
            fixture.apply(selectedA)
            assertQueueEquals(savedA, selectedA.snapshot)
            assertNull(selectedA.snapshot.currentSession)
            val restoredA = assertNotNull(fixture.controller.restoreCurrentPaused().startDirective)
            assertEquals(ProviderItemId("a", "same"), restoredA.itemId)
            assertEquals(41.seconds, restoredA.resumePosition)
            assertEquals(false, restoredA.shouldAutoPlay)
            assertNotEquals(a.startDirective?.attemptId, restoredA.attemptId)

            val selectedB = fixture.controller.selectAccount("b")
            fixture.apply(selectedB)
            assertQueueEquals(savedB, selectedB.snapshot)
            val restoredB = assertNotNull(fixture.controller.restoreCurrentPaused().startDirective)
            assertEquals(ProviderItemId("b", "same"), restoredB.itemId)
            assertEquals(17.seconds, restoredB.resumePosition)
            assertEquals(false, restoredB.shouldAutoPlay)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectingAnAccountAfterRelaunchLoadsItsQueueEvenWhenAnotherWasLastActive() {
        val fixture = Fixture()
        try {
            val a = fixture.controller.replaceAndStart(request("a", startIndex = 1))
            fixture.controller.setShuffle(true)
            val expectedA = fixture.controller.cycleRepeatMode().snapshot
            fixture.apply(fixture.controller.selectAccount("b"))
            fixture.controller.replaceAndStart(request("b"))
            val reopened = fixture.newController()

            val selected = reopened.selectAccount("a")
            assertQueueEquals(expectedA, selected.snapshot)
            assertEquals(ServerId("a"), fixture.queues.activeServerId())
            val restored = assertNotNull(reopened.restoreCurrentPaused().startDirective)
            assertEquals(a.startDirective?.queueEntryId, restored.queueEntryId)
            assertEquals(false, restored.shouldAutoPlay)
            assertEquals(3, fixture.queues.load(ServerId("b")).entries.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun switchingRetiresCurrentAndPreloadedEventsAndOutstandingAutoplay() {
        val fixture = Fixture()
        try {
            val startedA = assertNotNull(fixture.controller.replaceAndStart(request("a", startIndex = 1)).startDirective)
            fixture.controller.setAutoplay(true)
            val autoplay = assertNotNull(fixture.controller.takeAutoplayRequest())
            val preload = assertNotNull(fixture.controller.preloadNext(startedA.playbackSessionId).preloadDirective)
            fixture.controller.setStopPoint(PlaybackStopPoint.EndOfQueue)
            val selected = fixture.controller.selectAccount("b")
            assertEquals(preload.attemptId, selected.discardedPreloadAttemptId)
            assertNull(selected.snapshot.stopPoint)
            val startedB = fixture.controller.replaceAndStart(request("b")).snapshot

            val staleCurrent = fixture.controller.recordPlaybackEvent(
                PlaybackEngineEvent.EndedNaturally(startedA.attemptId, 180.seconds),
            )
            assertEquals(startedB, staleCurrent.snapshot)
            assertEquals(emptyList(), staleCurrent.effects)
            val stalePreload = fixture.controller.recordPlaybackEvent(
                PlaybackEngineEvent.EndedNaturally(preload.attemptId, 180.seconds),
            )
            assertEquals(startedB, stalePreload.snapshot)
            assertNull(stalePreload.startDirective)
            val staleAutoplay = fixture.controller.appendAutoplay(
                autoplay.key, listOf(PlaybackQueueItem(ProviderItemId("a", "foreign"), 180.seconds)),
            )
            assertEquals(startedB, staleAutoplay.snapshot)
            assertEquals(3, fixture.queues.load(ServerId("a")).entries.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selectingTheSameAccountPreservesItsLiveSessionAndPreload() {
        val fixture = Fixture()
        try {
            val started = assertNotNull(fixture.controller.replaceAndStart(request("a")).startDirective)
            val preloaded = fixture.controller.preloadNext(started.playbackSessionId)
            fixture.controller.setStopPoint(PlaybackStopPoint.EndOfQueue)
            val before = fixture.controller.snapshot()

            val selected = fixture.controller.selectAccount("a")
            assertEquals(before, selected.snapshot)
            assertEquals(emptyList(), selected.effects)
            assertNull(selected.discardedPreloadAttemptId)
            assertNull(selected.startDirective)
            assertNull(fixture.controller.restoreCurrentPaused().startDirective)
            assertNotNull(preloaded.preloadDirective)
        } finally {
            fixture.close()
        }
    }

    private fun assertQueueEquals(expected: PlaybackQueueSnapshot, actual: PlaybackQueueSnapshot) {
        assertEquals(expected.entries, actual.entries)
        assertEquals(expected.currentIndex, actual.currentIndex)
        assertEquals(expected.repeatMode, actual.repeatMode)
        assertEquals(expected.shuffleState, actual.shuffleState)
    }

    private fun request(account: String, startIndex: Int = 0) = PlaybackQueueRequest(
        items = listOf("first", "same", "last").map {
            PlaybackQueueItem(ProviderItemId(account, it), 180.seconds)
        },
        sourceContext = QueueSourceContext(QueueSourceKind.Library, null, "Library"),
        startIndex = startIndex,
        shuffle = false,
    )

    private class Fixture {
        val driver = createTestDriver()
        private val database = DulcetDatabaseStore.open(driver).database
        val queues = PersistentQueueStore(database)
        val resumes = PersistentResumePositionStore(database)
        private var identity = 0
        val controller = newController()

        fun newController() = PlaybackQueueController(
            queues = queues,
            resumePositions = resumes,
            identities = PlaybackIdentitySource { "$it:${identity++}" },
            shuffleRandom = Random(71),
        )

        fun apply(transition: PlaybackQueueTransition) {
            transition.effects.forEach { effect ->
                when (effect) {
                    is PlaybackCoreEffect.PersistResumePosition -> resumes.save(effect.itemId, effect.position)
                    is PlaybackCoreEffect.ClearResumePosition -> resumes.clear(effect.itemId)
                    else -> Unit
                }
            }
        }

        fun close() = driver.close()
    }
}
