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
    fun repeatOffNaturalCompletionKeepsTheLastEntrySelectedWithoutASession() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a")))
        val first = assertNotNull(started.startDirective)

        val completed = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds),
        )

        // §14.3: the finished entry stays selected, so the shell can show the last track
        // stopped instead of "Nothing is playing"; the session itself is finalized.
        assertNull(completed.startDirective)
        assertEquals(0, completed.snapshot.currentIndex)
        assertNull(completed.snapshot.currentSession)
        assertEquals(listOf("a"), completed.snapshot.rawIds())

        // Play after the end replays the selected entry as a NEW session (§12.1).
        val replay = assertNotNull(fixture.controller.startCurrent().startDirective)
        assertEquals("a", replay.itemId.rawId)
        assertEquals(true, replay.shouldAutoPlay)
        assertNotEquals(first.playbackSessionId, replay.playbackSessionId)
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

    @Test
    fun restorationClearsUnresolvedSelectionWithoutDeletingEntries() {
        for (available in listOf(setOf("a", "c"), emptySet())) {
            val fixture = fixture()
            val seeded = fixture.controller.replaceAndStart(request(listOf("a", "missing", "c"), 1))
            assertEquals("missing", seeded.startDirective?.itemId?.rawId)
            val database = DulcetDatabaseStore.open(fixture.driver).database
            repeat(2) {
                val reopened = PlaybackQueueController(
                    PersistentQueueStore(database), PersistentResumePositionStore(database),
                    PlaybackIdentitySource { error("Repair must not create a session or attempt") },
                )
                val repaired = reopened.restoreCurrentPausedWithCatalog(SERVER, available)
                assertEquals(listOf("a", "missing", "c"), repaired.snapshot.rawIds())
                assertEquals(
                    seeded.snapshot.entries,
                    repaired.snapshot.entries,
                )
                assertNull(repaired.snapshot.currentIndex)
                assertNull(repaired.snapshot.currentSession)
                assertNull(repaired.startDirective)
                assertEquals(emptyList(), repaired.effects)
            }
            fixture.driver.close()
        }
    }

    @Test
    fun partialCatalogPreservesEntireShuffledQueueAndValidPausedSelection() {
        val fixture = fixture(shuffleSeed = 71)
        val seeded = fixture.controller.replaceAndStart(request(listOf("a", "b", "c", "d"), shuffle = true))
        val selected = assertNotNull(seeded.startDirective).itemId.rawId
        val missing = seeded.snapshot.rawIds().last()
        val available = seeded.snapshot.rawIds().filter { it != missing }.toSet()
        val database = DulcetDatabaseStore.open(fixture.driver).database
        var identity = 0
        val reopened = PlaybackQueueController(
            PersistentQueueStore(database), PersistentResumePositionStore(database),
            PlaybackIdentitySource { "$it:restored:${identity++}" },
        )
        val restored = reopened.restoreCurrentPausedWithCatalog(SERVER, available)
        assertEquals(seeded.snapshot.entries, restored.snapshot.entries)
        assertEquals(QueueShuffleState.Enabled, restored.snapshot.shuffleState)
        assertEquals(selected, restored.startDirective?.itemId?.rawId)
        assertEquals(false, restored.startDirective?.shouldAutoPlay)
        assertEquals(listOf("a", "b", "c", "d"),
            reopened.setShuffle(false).snapshot.rawIds())
        fixture.driver.close()
    }

    @Test
    fun catalogRestorationDoesNotRepairALiveSessionOrAnotherAccountsQueue() {
        val fixture = fixture()
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b")))
        val live = fixture.controller.restoreCurrentPausedWithCatalog(SERVER, emptySet())
        assertEquals(started.snapshot, live.snapshot)
        assertNull(live.startDirective)
        val database = DulcetDatabaseStore.open(fixture.driver).database
        val reopened = PlaybackQueueController(
            PersistentQueueStore(database), PersistentResumePositionStore(database),
            PlaybackIdentitySource { error("Another account must not restore this queue") },
        )
        val other = reopened.restoreCurrentPausedWithCatalog(ServerId("other"), emptySet())
        assertEquals(started.snapshot.entries, other.snapshot.entries)
        assertEquals(started.snapshot.currentIndex, other.snapshot.currentIndex)
        assertNull(other.startDirective)
        fixture.driver.close()
    }

    @Test
    fun jumpToStartsTheNamedEntryOfADuplicatedSongAndKeepsEveryIdentity() {
        val fixture = fixture()
        // "b" appears twice. A jump addressed by song or by a stale index would be ambiguous.
        val started = fixture.controller.replaceAndStart(request(listOf("a", "b", "c", "b")))
        val before = started.snapshot.entries.map { it.queueEntryId }
        val secondB = started.snapshot.entries[3].queueEntryId

        val jumped = fixture.controller.jumpTo(secondB)

        assertEquals("b", jumped.startDirective?.itemId?.rawId)
        assertEquals(secondB, jumped.startDirective?.queueEntryId)
        assertEquals(3, jumped.snapshot.currentIndex)
        assertEquals(before, jumped.snapshot.entries.map { it.queueEntryId })
        assertNotEquals(started.startDirective?.playbackSessionId, jumped.startDirective?.playbackSessionId)

        val unknown = fixture.controller.jumpTo(QueueEntryId("queue-entry:absent"))
        assertNull(unknown.startDirective)
        assertEquals(3, unknown.snapshot.currentIndex)
        fixture.driver.close()
    }

    @Test
    fun retryAfterAFailureBeforeStartKeepsTheSessionAndReplacesOnlyTheAttempt() {
        val fixture = fixture()
        val started = assertNotNull(
            fixture.controller.replaceAndStart(request(listOf("a", "b"))).startDirective,
        )
        fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.FailedBeforeStart(started.attemptId, DomainError.Transport.Unreachable),
        )

        val retried = fixture.controller.retryCurrent()

        val directive = assertNotNull(retried.startDirective)
        assertEquals(started.playbackSessionId, directive.playbackSessionId, "§12.1: same session")
        assertNotEquals(started.attemptId, directive.attemptId, "§12.1: new attempt")
        assertEquals(started.queueEntryId, directive.queueEntryId)
        val session = assertNotNull(retried.snapshot.currentSession)
        assertEquals(started.playbackSessionId, session.playbackSessionId)
        assertEquals(directive.attemptId, session.currentAttempt.attemptId)
        assertEquals(0, retried.snapshot.currentIndex)
        fixture.driver.close()
    }

    @Test
    fun retryAfterAPartialFailureKeepsTheSessionAndResumesFromTheSavedPosition() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val resumePositions = PersistentResumePositionStore(database)
        var identity = 0
        val fixture = Fixture(
            driver = driver,
            controller = PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = resumePositions,
                identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
            ),
        )
        val started = assertNotNull(
            fixture.controller.replaceAndStart(request(listOf("a", "b"))).startDirective,
        )
        fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(started.attemptId, 180.seconds, PlaybackSeekability.Seekable),
        )
        fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(started.attemptId, PlaybackWallClockTime(1_788_000_000_000), 1.seconds),
        )
        val dropped = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.FailedAfterPartial(started.attemptId, 40.seconds, DomainError.Transport.Unreachable),
        )
        // The owner executes the effects in order, as the platform facades do.
        PlaybackCoreEffectHandlerForTest(resumePositions).apply(dropped.effects)

        val retried = fixture.controller.retryCurrent()

        // Any retry keeps the session (§12.1): its accumulator carries across the attempts, so
        // one listen interrupted by a failure is still one play.
        val directive = assertNotNull(retried.startDirective)
        assertEquals(started.queueEntryId, directive.queueEntryId)
        assertEquals(started.playbackSessionId, directive.playbackSessionId)
        assertNotEquals(started.attemptId, directive.attemptId)
        assertEquals(directive.attemptId, assertNotNull(retried.snapshot.currentSession).currentAttempt.attemptId)
        assertEquals(40.seconds, directive.resumePosition, "from where it stopped (§15.5)")
        fixture.driver.close()
    }

    @Test
    fun retryWithNothingFailedChangesNothingAndWithNoSessionStartsTheSelectedEntry() {
        val fixture = fixture()
        val started = assertNotNull(
            fixture.controller.replaceAndStart(request(listOf("a"))).startDirective,
        )
        assertNull(fixture.controller.retryCurrent().startDirective, "a live session is not retried")

        fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(started.attemptId, 180.seconds),
        )
        val replay = assertNotNull(fixture.controller.retryCurrent().startDirective)
        assertEquals(started.queueEntryId, replay.queueEntryId)
        assertNotEquals(started.playbackSessionId, replay.playbackSessionId)
        fixture.driver.close()
    }

    @Test fun routeRecoveryHoldsTheSelectedEntryAndUsesConfirmedPositionBeforePersistence() {
        val driver = createTestDriver()
        try {
            val database = DulcetDatabaseStore.open(driver).database
            val positions = PersistentResumePositionStore(database)
            var online = true; var identity = 0
            val controller = PlaybackQueueController(PersistentQueueStore(database), positions,
                PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
                canPlayItem = { online || it.rawId == "b" })
            val started = assertNotNull(controller.replaceAndStart(request(listOf("a", "b"))).startDirective)
            controller.recordPlaybackEvent(PlaybackEngineEvent.Ready(started.attemptId, 180.seconds, PlaybackSeekability.Seekable))
            controller.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(started.attemptId,
                PlaybackWallClockTime(1_788_000_000_000), 1.seconds))
            online = false
            val failed = controller.recordPlaybackEvent(PlaybackEngineEvent.FailedAfterPartial(started.attemptId,
                40.seconds, DomainError.Transport.Unreachable), awaitingRouteRecovery = true)
            assertNull(failed.startDirective, "Bounded handoff cannot skip to the cached next entry")
            assertEquals("a", failed.snapshot.currentSession?.itemId?.rawId)
            assertNull(positions.restore(started.itemId), "Do not execute the asynchronous persist effect in this scenario")
            val retried = controller.retryCurrentForRouteRecovery(shouldAutoPlay = false)
            val directive = assertNotNull(retried.startDirective)
            assertEquals(started.queueEntryId, directive.queueEntryId)
            assertEquals(started.playbackSessionId, directive.playbackSessionId)
            assertNotEquals(started.attemptId, directive.attemptId)
            assertEquals(40.seconds, directive.resumePosition)
            assertEquals(false, directive.shouldAutoPlay)
            val retained = assertNotNull(retried.snapshot.currentSession?.accumulator)
            assertEquals(failed.snapshot.currentSession?.accumulator?.accruedMediaTime, retained.accruedMediaTime)
            assertEquals(failed.snapshot.currentSession?.accumulator?.sessionStartWallClock, retained.sessionStartWallClock)
            assertEquals(failed.snapshot.currentSession?.accumulator?.submitted, retained.submitted)
            assertNull(retained.lastPosition, "A new decoder attempt clears its sampling baseline so recovery time cannot count")
        } finally { driver.close() }
    }

    @Test fun routeRecoveryRetainsConfirmedPositionAcrossPreparationsThatFailBeforeTheDecoderStarts() {
        val fixture = fixture()
        try {
            val controller = fixture.controller
            val positions = PersistentResumePositionStore(DulcetDatabaseStore.open(fixture.driver).database)
            val started = assertNotNull(controller.replaceAndStart(request(listOf("a", "b"))).startDirective)
            controller.recordPlaybackEvent(PlaybackEngineEvent.Ready(started.attemptId,
                180.seconds, PlaybackSeekability.Seekable))
            controller.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(started.attemptId,
                PlaybackWallClockTime(1_788_000_000_000), 1.seconds))
            controller.recordPlaybackEvent(PlaybackEngineEvent.FailedAfterPartial(started.attemptId,
                40.seconds, DomainError.Transport.Unreachable), awaitingRouteRecovery = true)

            var priorAttempt = started.attemptId
            var retry = assertNotNull(controller.retryCurrentForRouteRecovery(true).startDirective)
            assertEquals(40.seconds, retry.resumePosition)
            repeat(3) { preparation ->
                assertEquals(started.queueEntryId, retry.queueEntryId)
                assertEquals(started.playbackSessionId, retry.playbackSessionId)
                assertNotEquals(priorAttempt, retry.attemptId)
                assertNull(positions.restore(started.itemId), "Persistence effects have not run")
                // The new route's getSong or source preparation can fail before any decoder
                // observation. Its fresh zero-position attempt is not a new song checkpoint.
                val failed = controller.recordPlaybackEvent(PlaybackEngineEvent.FailedBeforeStart(
                    retry.attemptId, DomainError.Transport.Unreachable), awaitingRouteRecovery = true)
                assertNull(failed.startDirective, "A preparation failure cannot skip the held entry")
                priorAttempt = retry.attemptId
                retry = assertNotNull(controller.retryCurrentForRouteRecovery(true).startDirective)
                assertEquals(40.seconds, retry.resumePosition,
                    "Preparation failure ${preparation + 1} must retain the last confirmed decoder position")
            }

            // Once a new decoder actually progresses, it supplies the checkpoint. A later
            // backward seek is authoritative too: recovery must not keep a maximum-ever value.
            controller.recordPlaybackEvent(PlaybackEngineEvent.Ready(retry.attemptId,
                180.seconds, PlaybackSeekability.Seekable))
            controller.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(retry.attemptId,
                PlaybackWallClockTime(1_788_000_010_000), 40.seconds))
            controller.recordPlaybackEvent(PlaybackEngineEvent.PositionChanged(retry.attemptId,
                67.seconds, PlaybackMonotonicTime(30.seconds)))
            controller.recordPlaybackEvent(PlaybackEngineEvent.FailedAfterPartial(retry.attemptId,
                67.seconds, DomainError.Transport.Unreachable), awaitingRouteRecovery = true)
            priorAttempt = retry.attemptId
            retry = assertNotNull(controller.retryCurrentForRouteRecovery(true).startDirective)
            assertNotEquals(priorAttempt, retry.attemptId)
            assertEquals(67.seconds, retry.resumePosition)
            controller.recordPlaybackEvent(PlaybackEngineEvent.Ready(retry.attemptId,
                180.seconds, PlaybackSeekability.Seekable))
            controller.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(retry.attemptId,
                PlaybackWallClockTime(1_788_000_040_000), 67.seconds))
            controller.recordPlaybackEvent(PlaybackEngineEvent.SeekCompleted(retry.attemptId, 67.seconds, 9.seconds))
            controller.recordPlaybackEvent(PlaybackEngineEvent.FailedAfterPartial(retry.attemptId,
                9.seconds, DomainError.Transport.Unreachable), awaitingRouteRecovery = true)
            val afterBackwardSeek = assertNotNull(controller.retryCurrentForRouteRecovery(false).startDirective)
            assertEquals(started.queueEntryId, afterBackwardSeek.queueEntryId)
            assertEquals(started.playbackSessionId, afterBackwardSeek.playbackSessionId)
            assertNotEquals(retry.attemptId, afterBackwardSeek.attemptId)
            assertEquals(9.seconds, afterBackwardSeek.resumePosition)
            assertEquals(false, afterBackwardSeek.shouldAutoPlay)
            assertNull(positions.restore(started.itemId), "No asynchronous resume persistence is needed")
        } finally { fixture.driver.close() }
    }

    @Test fun automaticRouteRecoveryAtTheKnownEndDoesNotReplayAsAnotherListen() {
        val fixture = fixture()
        try {
            val started = assertNotNull(fixture.controller.replaceAndStart(request(listOf("a", "b"))).startDirective)
            fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.Ready(started.attemptId, 180.seconds, PlaybackSeekability.Seekable))
            fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(started.attemptId,
                PlaybackWallClockTime(1_788_000_000_000), 1.seconds))
            fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.FailedAfterPartial(started.attemptId,
                180.seconds, DomainError.Transport.Unreachable), awaitingRouteRecovery = true)
            assertNull(fixture.controller.retryCurrentForRouteRecovery(true).startDirective)
            assertEquals(started.playbackSessionId, fixture.controller.snapshot().currentSession?.playbackSessionId)
        } finally { fixture.driver.close() }
    }

    @Test fun honestZeroRouteRestartsCreditProgressingReplayButSubmitTheSameListenOnlyOnce() {
        val fixture = fixture()
        try {
            val controller = fixture.controller
            val request = request(listOf("a")).copy(items = listOf(
                PlaybackQueueItem(ProviderItemId(SERVER.value, "a"), 240.seconds)))
            val first = assertNotNull(controller.replaceAndStart(request).startDirective)
            val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
            var clock = 0
            fun collect(effects: List<PlaybackCoreEffect>) {
                submitted += effects.filterIsInstance<PlaybackCoreEffect.RecordPlaybackEvent>()
                    .mapNotNull { it.event as? RecordedPlaybackEvent.SubmittedPlay }
            }
            fun record(event: PlaybackEngineEvent) {
                collect(controller.recordPlaybackEvent(event).effects)
            }
            fun hearFirstMinute(attempt: AttemptId, wall: Long) {
                record(PlaybackEngineEvent.Ready(attempt, 240.seconds, PlaybackSeekability.NotSeekable))
                record(PlaybackEngineEvent.PlaybackProgressBegan(attempt, PlaybackWallClockTime(wall), Duration.ZERO))
                for (position in 1..60) record(PlaybackEngineEvent.PositionChanged(
                    attempt, position.seconds, PlaybackMonotonicTime((++clock).seconds)))
            }
            val startWall = 1_788_000_000_000L
            hearFirstMinute(first.attemptId, startWall)
            assertEquals(60.seconds, controller.snapshot().currentSession?.accumulator?.accruedMediaTime)
            assertTrue(submitted.isEmpty(), "A 240-second song needs 120 progressing seconds")
            var attempt = first.attemptId
            repeat(2) { recovery ->
                collect(controller.recordPlaybackEvent(PlaybackEngineEvent.FailedAfterPartial(
                    attempt, 60.seconds, DomainError.Transport.Unreachable), awaitingRouteRecovery = true).effects)
                val recoveryTransition = controller.retryCurrentForRouteRecovery(true)
                collect(recoveryTransition.effects)
                val next = assertNotNull(recoveryTransition.startDirective)
                assertEquals(first.playbackSessionId, next.playbackSessionId)
                assertEquals(first.queueEntryId, next.queueEntryId)
                assertNotEquals(attempt, next.attemptId)
                assertEquals(60.seconds, next.resumePosition)
                // Unsupported transcodeOffset is an honest new decoder clock at zero. The
                // recovery delay/jump gets no credit; its genuinely progressing replay does.
                clock += 30
                hearFirstMinute(next.attemptId, startWall + clock * 1_000L)
                assertEquals((120 + recovery * 60).seconds,
                    controller.snapshot().currentSession?.accumulator?.accruedMediaTime)
                assertEquals(1, submitted.size, "Replacement attempts must retain the submitted guard")
                assertEquals(PlaybackWallClockTime(startWall), submitted.single().sessionStartWallClock)
                attempt = next.attemptId
            }
            record(PlaybackEngineEvent.EndedNaturally(attempt, 60.seconds))
            assertEquals(1, submitted.size)
        } finally { fixture.driver.close() }
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

    /** The resume-position half of what an owner does with a transition's effects. */
    private class PlaybackCoreEffectHandlerForTest(private val store: ResumePositionStore) {
        fun apply(effects: List<PlaybackCoreEffect>) = effects.forEach { effect ->
            when (effect) {
                is PlaybackCoreEffect.PersistResumePosition -> store.save(effect.itemId, effect.position)
                is PlaybackCoreEffect.ClearResumePosition -> store.clear(effect.itemId)
                else -> Unit
            }
        }
    }

    private data class Fixture(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val controller: PlaybackQueueController,
    )

    private companion object {
        val SERVER = ServerId("server:playback-surfaces")
    }
}
