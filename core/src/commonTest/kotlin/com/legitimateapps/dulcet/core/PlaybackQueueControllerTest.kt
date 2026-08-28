package com.legitimateapps.dulcet.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class PlaybackQueueControllerTest {
    @Test
    fun replacementAndShuffleArePersistedCoreDecisions() {
        val fixture = fixture(shuffleSeed = 71)

        val started = fixture.controller.replaceAndStart(
            request(listOf("a", "b", "c", "d", "e", "f"), shuffle = true),
        )

        assertEquals(QueueShuffleState.Enabled, started.snapshot.shuffleState)
        assertEquals(0, started.snapshot.currentIndex)
        assertNotEquals(listOf("a", "b", "c", "d", "e", "f"), started.snapshot.rawIds())
        assertEquals(started.snapshot.entries.first().itemId, started.startDirective?.itemId)

        val reopened = PersistentQueueStore(DulcetDatabaseStore.open(fixture.driver).database)
        assertEquals(started.snapshot.rawIds(), reopened.load(SERVER).entries.map { it.providerItemId.rawId })
        fixture.driver.close()
    }

    @Test
    fun wholeLibraryQueueUsesANullSourceIdentity() {
        val fixture = fixture()
        val request = PlaybackQueueRequest(
            items = listOf(PlaybackQueueItem(ProviderItemId(SERVER.value, "a"), 180.seconds)),
            sourceContext = QueueSourceContext(
                kind = QueueSourceKind.Library,
                sourceId = null,
                displayName = "Library",
            ),
            startIndex = 0,
            shuffle = false,
        )

        val started = fixture.controller.replaceAndStart(request)

        assertEquals("a", started.startDirective?.itemId?.rawId)
        fixture.driver.close()
    }

    @Test
    fun rowActivationStartsTheNamedOriginalIndexAndNextUsesCoreCurrentIndex() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(
            request(listOf("a", "b", "c"), startIndex = 1),
        )
        assertEquals("b", started.startDirective?.itemId?.rawId)
        assertEquals(1, started.snapshot.currentIndex)

        val next = fixture.controller.next()

        assertEquals("c", next.startDirective?.itemId?.rawId)
        assertEquals(2, next.snapshot.currentIndex)
        assertNotEquals(
            started.startDirective?.playbackSessionId,
            next.startDirective?.playbackSessionId,
        )
        fixture.driver.close()
    }

    @Test
    fun relaunchRestoresThePersistedCurrentEntryPausedWithItsSavedPosition() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val resumePositions = PersistentResumePositionStore(database)
        var originalIdentity = 0
        val original = PlaybackQueueController(
            queues = PersistentQueueStore(database),
            resumePositions = resumePositions,
            identities = PlaybackIdentitySource { prefix -> "$prefix:original:${originalIdentity++}" },
        )
        original.replaceAndStart(request(listOf("a", "b"), startIndex = 1))
        resumePositions.save(ProviderItemId(SERVER.value, "b"), 41.seconds)
        val restoredController = PlaybackQueueController(
            queues = PersistentQueueStore(database),
            resumePositions = resumePositions,
            identities = PlaybackIdentitySource { prefix -> "$prefix:restored" },
        )

        val restored = restoredController.restoreCurrentPaused()
        val directive = assertNotNull(restored.startDirective)

        assertEquals("b", directive.itemId.rawId)
        assertEquals(41.seconds, directive.resumePosition)
        assertEquals(false, directive.shouldAutoPlay)
        assertEquals(1, restored.snapshot.currentIndex)
        assertNotNull(restored.snapshot.currentSession)
        assertNull(restoredController.restoreCurrentPaused().startDirective)
        driver.close()
    }

    @Test
    fun repeatOneNaturalCompletionStartsANewSessionForTheSameQueueEntry() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a")))
        fixture.controller.cycleRepeatMode()
        fixture.controller.cycleRepeatMode()
        val first = assertNotNull(started.startDirective)

        val repeated = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds),
        )
        val second = assertNotNull(repeated.startDirective)

        assertEquals(first.queueEntryId, second.queueEntryId)
        assertEquals(first.itemId, second.itemId)
        assertNotEquals(first.playbackSessionId, second.playbackSessionId)
        assertNotEquals(first.attemptId, second.attemptId)
        assertEquals(0, repeated.snapshot.currentIndex)
        fixture.driver.close()
    }

    @Test
    fun repeatOffNaturalCompletionLeavesThePersistedQueuePausedWithoutACurrentEntry() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a")))
        val first = assertNotNull(started.startDirective)

        val completed = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds),
        )

        assertNull(completed.startDirective)
        assertNull(completed.snapshot.currentIndex)
        assertNull(completed.snapshot.currentSession)
        assertEquals(listOf("a"), completed.snapshot.rawIds())
        fixture.driver.close()
    }

    @Test
    fun readyAndProgressBeganRemainDistinctCoreSnapshotPhases() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a")))
        val directive = assertNotNull(started.startDirective)

        val ready = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(
                directive.attemptId,
                180.seconds,
                PlaybackSeekability.Seekable,
            ),
        )
        assertEquals(PlaybackAttemptPhase.Ready, ready.snapshot.currentSession?.currentAttempt?.phase)
        assertEquals(
            PlaybackSeekability.Seekable,
            ready.snapshot.currentSession?.currentAttempt?.seekability,
        )

        val progressing = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(
                directive.attemptId,
                PlaybackWallClockTime(1_788_000_000_000),
                1.seconds,
            ),
        )
        assertEquals(
            PlaybackAttemptPhase.Progressing,
            progressing.snapshot.currentSession?.currentAttempt?.phase,
        )
        fixture.driver.close()
    }

    @Test
    fun staleSessionCommandsAreRejectedWithoutMovingTheCurrentQueue() {
        val fixture = fixture()
        val first = assertNotNull(
            fixture.controller.replaceAndStart(request(listOf("a", "b"))).startDirective,
        )
        val second = assertNotNull(fixture.controller.next().startDirective)

        val stale = fixture.controller.previousForSession(first.playbackSessionId)

        assertNull(stale.startDirective)
        assertEquals("b", stale.snapshot.entries[stale.snapshot.currentIndex!!].itemId.rawId)
        assertEquals(second.playbackSessionId, stale.snapshot.currentSession?.playbackSessionId)
        fixture.driver.close()
    }

    @Test
    fun seekCommandAcceptanceComesOnlyFromReadySeekability() {
        val fixture = fixture()
        val directive = assertNotNull(
            fixture.controller.replaceAndStart(request(listOf("a"))).startDirective,
        )

        assertEquals(false, fixture.controller.acceptsCommand(directive.playbackSessionId, true))
        fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(
                directive.attemptId,
                180.seconds,
                PlaybackSeekability.NotSeekable,
            ),
        )
        assertEquals(false, fixture.controller.acceptsCommand(directive.playbackSessionId, true))
        fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(
                directive.attemptId,
                180.seconds,
                PlaybackSeekability.Seekable,
            ),
        )
        assertEquals(true, fixture.controller.acceptsCommand(directive.playbackSessionId, true))
        fixture.driver.close()
    }

    private fun fixture(shuffleSeed: Int = 1): Fixture {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        var identity = 0
        return Fixture(
            driver = driver,
            controller = PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = PersistentResumePositionStore(database),
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

    private fun PlaybackQueueSnapshot.rawIds(): List<String> = entries.map { it.itemId.rawId }

    private data class Fixture(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val controller: PlaybackQueueController,
    )

    private companion object {
        val SERVER = ServerId("server:playback-surfaces")
    }
}
