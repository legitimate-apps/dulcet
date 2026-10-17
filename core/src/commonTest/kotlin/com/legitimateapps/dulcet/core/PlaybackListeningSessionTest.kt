package com.legitimateapps.dulcet.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/** Sleep-timer stop points, AutoPlay and crossfade policy in the core queue (§12.8, §12.13, §14.4). */
class PlaybackListeningSessionTest {
    // ---- Up Next: Play Next / Add to Queue around AutoPlay --------------------------------------

    @Test
    fun addToQueueLandsBeforeUpcomingAutoplayAndPlayNextDirectlyAfterTheCurrentEntry() {
        val fixture = fixture()
        fixture.controller.setAutoplay(true)
        fixture.controller.replaceAndStart(request(listOf("a", "b")))
        // Two entries: "b" is the only one after "a", below the low-water mark.
        val ask = assertNotNull(fixture.controller.takeAutoplayRequest())
        assertEquals("b", ask.seed.rawId)
        assertEquals(setOf("a", "b"), ask.excludedRawIds)
        fixture.controller.appendAutoplay(ask.key, items(listOf("x", "y")))

        val appended = fixture.controller.enqueue(insertion(listOf("q1", "q2"), QueueInsertionMode.Append))
        assertEquals(listOf("a", "b", "q1", "q2", "x", "y"), appended.snapshot.rawIds())
        assertEquals(listOf(false, false, false, false, true, true), appended.snapshot.entries.map { it.isAutoplay })

        val next = fixture.controller.enqueue(insertion(listOf("n1", "n2"), QueueInsertionMode.PlayNext))
        assertEquals(listOf("a", "n1", "n2", "b", "q1", "q2", "x", "y"), next.snapshot.rawIds())
        // Persisted, so a relaunch restores the same order and the same AutoPlay marks.
        assertEquals(next.snapshot.rawIds(), fixture.reopenedRawIds())
        assertEquals(
            listOf("x", "y"),
            PersistentQueueStore(fixture.database).load(SERVER).entries
                .filter { it.addedBy == QueueAddedBy.Autoplay }.map { it.providerItemId.rawId },
        )
    }

    @Test
    fun withoutAutoplayEntriesAddToQueueStillAppendsAtTheEnd() {
        val fixture = fixture()
        fixture.controller.replaceAndStart(request(listOf("a", "b")))
        val appended = fixture.controller.enqueue(insertion(listOf("q"), QueueInsertionMode.Append))
        assertEquals(listOf("a", "b", "q"), appended.snapshot.rawIds())
    }

    // ---- AutoPlay ---------------------------------------------------------------------------------

    @Test
    fun autoplayAsksOnlyWhenOnAndLowAndEachRequestIsHandedOutOnce() {
        val fixture = fixture()
        fixture.controller.replaceAndStart(request(listOf("a", "b", "c")))
        // Off by default: the owner pushes the person's setting.
        assertNull(fixture.controller.takeAutoplayRequest())
        fixture.controller.setAutoplay(true)
        // Two entries follow the current one: not low yet.
        assertNull(fixture.controller.takeAutoplayRequest())
        fixture.controller.next()
        val ask = assertNotNull(fixture.controller.takeAutoplayRequest())
        assertNull(fixture.controller.takeAutoplayRequest(), "a request is handed out once")
        // An answer that found nothing is not asked again until the queue's end changes.
        fixture.controller.appendAutoplay(ask.key, emptyList())
        assertNull(fixture.controller.takeAutoplayRequest())
        fixture.controller.enqueue(insertion(listOf("d"), QueueInsertionMode.Append))
        assertEquals("d", assertNotNull(fixture.controller.takeAutoplayRequest()).seed.rawId)
    }

    @Test
    fun autoplayDropsDuplicatesForeignItemsAndStaleAnswers() {
        val fixture = fixture()
        fixture.controller.setAutoplay(true)
        fixture.controller.replaceAndStart(request(listOf("a")))
        val stale = assertNotNull(fixture.controller.takeAutoplayRequest())
        // The queue changed at its end: the answer to the old request no longer applies.
        fixture.controller.enqueue(insertion(listOf("b"), QueueInsertionMode.Append))
        val ignored = fixture.controller.appendAutoplay(stale.key, items(listOf("x")))
        assertEquals(listOf("a", "b"), ignored.snapshot.rawIds())

        val ask = assertNotNull(fixture.controller.takeAutoplayRequest())
        val answered = fixture.controller.appendAutoplay(
            ask.key,
            items(listOf("a", "x", "x", "y")) + PlaybackQueueItem(ProviderItemId("other", "z"), 10.seconds),
        )
        assertEquals(listOf("a", "b", "x", "y"), answered.snapshot.rawIds())
        assertEquals("AutoPlay", answered.snapshot.entries.last().sourceDisplayName)
    }

    @Test
    fun turningAutoplayOffRepeatOrShuffleRemovesOnlyUpcomingAutoplayEntries() {
        val fixture = fixture()
        fixture.controller.setAutoplay(true)
        fixture.controller.replaceAndStart(request(listOf("a")))
        fixture.controller.appendAutoplay(assertNotNull(fixture.controller.takeAutoplayRequest()).key, items(listOf("x", "y", "z")))
        // Play into the AutoPlay entries: "x" is current and stays.
        fixture.controller.next()
        val off = fixture.controller.setAutoplay(false)
        assertEquals(listOf("a", "x"), off.snapshot.rawIds())
        assertFalse(off.snapshot.autoplayActive)

        val again = fixture()
        again.controller.setAutoplay(true)
        again.controller.replaceAndStart(request(listOf("a")))
        again.controller.appendAutoplay(assertNotNull(again.controller.takeAutoplayRequest()).key, items(listOf("x")))
        val repeat = again.controller.cycleRepeatMode()
        assertEquals(listOf("a"), repeat.snapshot.rawIds())
        assertFalse(repeat.snapshot.autoplayActive)
        assertNull(again.controller.takeAutoplayRequest(), "repeat says what plays after the queue")
        again.controller.cycleRepeatMode()
        again.controller.cycleRepeatMode()
        assertTrue(again.controller.snapshot().autoplayActive)
        again.controller.appendAutoplay(assertNotNull(again.controller.takeAutoplayRequest()).key, items(listOf("x")))
        val shuffled = again.controller.setShuffle(true)
        assertEquals(listOf("a"), shuffled.snapshot.rawIds())
    }

    @Test
    fun autoplayEntriesPlayOnAfterTheQueueAndAnAnswerArrivingLateStartsTheFirst() {
        val fixture = fixture()
        fixture.controller.setAutoplay(true)
        val first = assertNotNull(fixture.controller.replaceAndStart(request(listOf("a"))).startDirective)
        val ask = assertNotNull(fixture.controller.takeAutoplayRequest())
        fixture.playPast(first.attemptId, 3)
        // The queue runs out before AutoPlay answers.
        val ended = fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds))
        assertNull(ended.startDirective)
        assertNull(ended.snapshot.currentSession)
        val late = fixture.controller.appendAutoplay(ask.key, items(listOf("x")))
        val started = assertNotNull(late.startDirective)
        assertEquals("x", started.itemId.rawId)
        assertTrue(started.shouldAutoPlay)

        // In time, an AutoPlay entry is just the next entry: a natural end starts it.
        val inTime = fixture()
        inTime.controller.setAutoplay(true)
        val head = assertNotNull(inTime.controller.replaceAndStart(request(listOf("a"))).startDirective)
        inTime.controller.appendAutoplay(assertNotNull(inTime.controller.takeAutoplayRequest()).key, items(listOf("x")))
        inTime.playPast(head.attemptId, 3)
        val next = inTime.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(head.attemptId, 180.seconds))
        assertEquals("x", assertNotNull(next.startDirective).itemId.rawId)
    }

    @Test
    fun aStartedQueueClearsTheLateAutoplayStart() {
        val fixture = fixture()
        fixture.controller.setAutoplay(true)
        val first = assertNotNull(fixture.controller.replaceAndStart(request(listOf("a"))).startDirective)
        val ask = assertNotNull(fixture.controller.takeAutoplayRequest())
        fixture.playPast(first.attemptId, 3)
        fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds))
        // The person played something else; the late answer must not start over it.
        fixture.controller.replaceAndStart(request(listOf("c")))
        val late = fixture.controller.appendAutoplay(ask.key, items(listOf("x")))
        assertNull(late.startDirective)
        assertEquals(listOf("c"), late.snapshot.rawIds())
    }

    // ---- Sleep timer stop points ------------------------------------------------------------------

    @Test
    fun endOfTrackStopsAtTheNaturalEndWithTheNextEntrySelectedPaused() {
        val fixture = fixture()
        val first = assertNotNull(fixture.controller.replaceAndStart(request(listOf("a", "b"))).startDirective)
        val set = fixture.controller.setStopPoint(PlaybackStopPoint.EndOfCurrentEntry)
        assertEquals(PlaybackStopPoint.EndOfCurrentEntry, set.snapshot.stopPoint)
        // The boundary is the stop point's: nothing preloads across it.
        assertNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        val effects = fixture.playPast(first.attemptId, 100)
        assertEquals(listOf("a"), effects.submittedRawIds())

        val ended = fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds))
        assertTrue(ended.stopPointReached)
        val paused = assertNotNull(ended.startDirective)
        assertEquals("b", paused.itemId.rawId)
        assertFalse(paused.shouldAutoPlay)
        assertNull(ended.snapshot.stopPoint, "a stop point is used once")
        // The outgoing play was counted once, at its threshold, and not again at the stop.
        assertEquals(emptyList(), ended.effects.submittedRawIds())
    }

    @Test
    fun endOfTrackOnTheLastEntryFinishesTheQueue() {
        val fixture = fixture()
        val only = assertNotNull(fixture.controller.replaceAndStart(request(listOf("a"))).startDirective)
        fixture.controller.setStopPoint(PlaybackStopPoint.EndOfCurrentEntry)
        fixture.playPast(only.attemptId, 3)
        val ended = fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(only.attemptId, 180.seconds))
        assertTrue(ended.stopPointReached)
        assertNull(ended.startDirective)
        assertNull(ended.snapshot.currentSession)
        assertEquals(0, ended.snapshot.currentIndex)
    }

    @Test
    fun endOfQueuePlaysThePersonsQueueThenStopsBeforeAutoplayAndWithoutWrapping() {
        val fixture = fixture()
        fixture.controller.setAutoplay(true)
        val first = assertNotNull(fixture.controller.replaceAndStart(request(listOf("a", "b"))).startDirective)
        fixture.controller.appendAutoplay(assertNotNull(fixture.controller.takeAutoplayRequest()).key, items(listOf("x")))
        fixture.controller.setStopPoint(PlaybackStopPoint.EndOfQueue)
        // Within the queue, the boundary still hands over and is not the stop point's.
        val handover = assertNotNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        fixture.playPast(first.attemptId, 3)
        val mid = fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(first.attemptId, 180.seconds))
        assertFalse(mid.stopPointReached)
        val second = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(first.attemptId, handover.attemptId),
        )
        val b = assertNotNull(second.snapshot.currentSession)
        assertEquals("b", b.itemId.rawId)
        assertNull(fixture.controller.takeAutoplayRequest(), "End of Queue asks AutoPlay for nothing")
        assertNull(fixture.controller.preloadNext(b.playbackSessionId).preloadDirective)
        fixture.playPast(b.currentAttempt.attemptId, 3)
        val ended = fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(b.currentAttempt.attemptId, 180.seconds),
        )
        assertTrue(ended.stopPointReached)
        // The AutoPlay entry is selected, paused: Play goes on with AutoPlay.
        assertEquals("x", assertNotNull(ended.startDirective).itemId.rawId)
        assertFalse(ended.startDirective!!.shouldAutoPlay)

        val wrap = fixture()
        val head = assertNotNull(wrap.controller.replaceAndStart(request(listOf("a"))).startDirective)
        wrap.controller.cycleRepeatMode() // repeat-all
        wrap.controller.setStopPoint(PlaybackStopPoint.EndOfQueue)
        wrap.playPast(head.attemptId, 3)
        val stopped = wrap.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(head.attemptId, 180.seconds))
        assertTrue(stopped.stopPointReached)
        assertNull(stopped.startDirective, "repeat-all does not wrap past End of Queue")
    }

    @Test
    fun settingAStopPointDiscardsThePreloadItOwnsAndClearingItAllowsPreloadAgain() {
        val fixture = fixture()
        val first = assertNotNull(fixture.controller.replaceAndStart(request(listOf("a", "b"))).startDirective)
        val preload = assertNotNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        val set = fixture.controller.setStopPoint(PlaybackStopPoint.EndOfCurrentEntry)
        assertEquals(preload.attemptId, set.discardedPreloadAttemptId)
        fixture.controller.setStopPoint(null)
        assertNotNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
    }

    // ---- Crossfade policy ---------------------------------------------------------------------------

    @Test
    fun crossfadeAppliesBetweenDifferentAlbumsAndNeverWithinOne() {
        val fixture = fixture()
        fixture.controller.setCrossfade(6.seconds)
        // Both from the same album context: gapless.
        fixture.controller.replaceAndStart(request(listOf("a", "b")))
        assertEquals(Duration.ZERO, fixture.controller.snapshot().crossfadeIntoNext)
        // Album ids decide when known, whatever the source context says.
        val mixed = fixture()
        mixed.controller.setCrossfade(6.seconds)
        mixed.controller.replaceAndStart(
            PlaybackQueueRequest(
                items = listOf(
                    PlaybackQueueItem(ProviderItemId(SERVER.value, "a"), 180.seconds, albumRawId = "one"),
                    PlaybackQueueItem(ProviderItemId(SERVER.value, "b"), 180.seconds, albumRawId = "two"),
                    PlaybackQueueItem(ProviderItemId(SERVER.value, "c"), 180.seconds, albumRawId = "two"),
                ),
                sourceContext = QueueSourceContext(QueueSourceKind.Playlist, ProviderItemId(SERVER.value, "p"), "Mix"),
                startIndex = 0,
                shuffle = false,
            ),
        )
        assertEquals(6.seconds, mixed.controller.snapshot().crossfadeIntoNext)
        mixed.controller.next()
        assertEquals(Duration.ZERO, mixed.controller.snapshot().crossfadeIntoNext, "b and c share an album")
    }

    @Test
    fun crossfadeIsZeroWhenOffUnderRepeatOneAtAStopPointAndForShortTracks() {
        val fixture = fixture()
        fixture.controller.replaceAndStart(searchRequest(listOf("a", "b"), 180.seconds))
        assertEquals(Duration.ZERO, fixture.controller.snapshot().crossfadeIntoNext, "off by default")
        fixture.controller.setCrossfade(12.seconds)
        assertEquals(12.seconds, fixture.controller.snapshot().crossfadeIntoNext)
        fixture.controller.setStopPoint(PlaybackStopPoint.EndOfCurrentEntry)
        assertEquals(Duration.ZERO, fixture.controller.snapshot().crossfadeIntoNext)
        fixture.controller.setStopPoint(null)
        fixture.controller.cycleRepeatMode()
        fixture.controller.cycleRepeatMode() // repeat-one
        assertEquals(Duration.ZERO, fixture.controller.snapshot().crossfadeIntoNext)

        val short = fixture()
        short.controller.setCrossfade(12.seconds)
        short.controller.replaceAndStart(searchRequest(listOf("a", "b"), 30.seconds))
        assertEquals(Duration.ZERO, short.controller.snapshot().crossfadeIntoNext, "a 30 s track does not overlap 12 s")
        assertFailsWith<IllegalArgumentException> { short.controller.setCrossfade(13.seconds) }
    }

    @Test
    fun aCrossfadedEndCountsThePlayOnceAndClearsTheResumePosition() {
        // The engine ends the outgoing attempt where the overlap begins, `crossfade` before its
        // duration. Scrobble and resume follow the same rules as any natural end (§12.8).
        val fixture = fixture()
        fixture.controller.setCrossfade(10.seconds)
        val first = assertNotNull(fixture.controller.replaceAndStart(searchRequest(listOf("a", "b"), 180.seconds)).startDirective)
        assertEquals(10.seconds, fixture.controller.snapshot().crossfadeIntoNext)
        val preload = assertNotNull(fixture.controller.preloadNext(first.playbackSessionId).preloadDirective)
        val effects = fixture.playPast(first.attemptId, 170)
        effects += fixture.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(first.attemptId, 170.seconds)).effects
        effects += fixture.controller.recordPlaybackEvent(
            PlaybackEngineEvent.AdvancedToPreloaded(first.attemptId, preload.attemptId),
        ).effects
        assertEquals(listOf("a"), effects.submittedRawIds())
        assertTrue(effects.any { it == PlaybackCoreEffect.ClearResumePosition(ProviderItemId(SERVER.value, "a")) })
        assertNull(fixture.resumePositions.restore(ProviderItemId(SERVER.value, "a")))
    }

    // ---- Sleep timer countdown ----------------------------------------------------------------------

    @Test
    fun sleepCountdownFadesOverTheLastSecondsAndExpiresOnce() {
        val timer = SleepTimerCountdown(fadeMilliseconds = 10_000)
        assertFalse(timer.isRunning)
        assertEquals(1.0, timer.gain(0))
        timer.start(lengthMilliseconds = 15 * 60_000L, nowMilliseconds = 1_000)
        assertEquals(15 * 60_000L, timer.remainingMilliseconds(1_000))
        assertEquals(1.0, timer.gain(1_000 + 15 * 60_000L - 10_000))
        assertEquals(0.5, timer.gain(1_000 + 15 * 60_000L - 5_000))
        assertFalse(timer.expire(1_000 + 15 * 60_000L - 1))
        // Read late, after a suspension across the deadline: it expires on the first reading.
        assertTrue(timer.expire(1_000 + 20 * 60_000L))
        assertFalse(timer.expire(1_000 + 21 * 60_000L))
        assertFalse(timer.isRunning)
        assertEquals(1.0, timer.gain(1_000 + 21 * 60_000L), "no timer, full gain")
    }

    @Test
    fun sleepCountdownCancelsAndChoicesDecode() {
        val timer = SleepTimerCountdown()
        timer.start(60_000, 0)
        timer.cancel()
        assertNull(timer.remainingMilliseconds(10))
        assertFalse(timer.expire(120_000))
        assertEquals(45 * 60_000L, SleepTimerCountdown.lengthMilliseconds(SleepTimerChoice.Minutes45))
        assertNull(SleepTimerCountdown.lengthMilliseconds(SleepTimerChoice.EndOfQueue))
        assertEquals(SleepTimerChoice.EndOfTrack, SleepTimerChoice.fromWireName("endOfTrack"))
        assertNull(SleepTimerChoice.fromWireName("forever"))
        assertEquals(0, PlaybackCrossfade.decodeSeconds(null))
        assertEquals(12, PlaybackCrossfade.decodeSeconds(40))
        assertEquals(0, PlaybackCrossfade.decodeSeconds(-3))
    }

    // ---- AutoPlay candidate search ------------------------------------------------------------------

    @Test
    fun autoplaySearchWalksSimilarTopGenreThenRandomSkippingExclusions() = runTest {
        val requests = mutableListOf<String>()
        val transport = LibraryEndpointTransport { endpoint, parameters ->
            requests += "$endpoint ${parameters.toSortedMap()}"
            LibraryEndpointResponse(200, when (endpoint) {
                "getSong" -> ok(""""song":{"id":"seed","title":"Seed","artistId":"ar1","artist":"Artist","genre":"Jazz"}""")
                "getSimilarSongs2" -> ok(""""similarSongs2":{"song":[${song("seed")},${song("s1")},${song("queued")}]}""")
                "getSimilarSongs" -> failure(70)
                "getTopSongs" -> ok(""""topSongs":{}""")
                "getRandomSongs" -> if (parameters["genre"] == "Jazz") {
                    ok(""""randomSongs":{"song":[${song("s1")},${song("g1")}]}""")
                } else {
                    ok(""""randomSongs":{"song":[${song("r1")},${song("r2")},${song("r3")}]}""")
                }
                else -> error("unexpected $endpoint")
            }, "http://fixture.invalid/rest")
        }
        val tracks = AutoplayCandidateSource("server", transport).candidates("seed", setOf("queued"), count = 4)
        assertEquals(listOf("s1", "g1", "r1", "r2"), tracks.map { it.id.rawId })
        assertEquals("al-s1", tracks.first().albumRawId)
        assertEquals(
            listOf(
                "getSong {id=seed}",
                "getSimilarSongs2 {count=4, id=ar1}",
                "getSimilarSongs {count=4, id=seed}",
                "getTopSongs {artist=Artist, count=4}",
                "getRandomSongs {genre=Jazz, size=4}",
                "getRandomSongs {size=4}",
            ),
            requests,
        )
    }

    @Test
    fun autoplaySearchStopsOnRefusedCredentialsAndSurvivesAMissingSeed() = runTest {
        val refused = LibraryEndpointTransport { _, _ -> LibraryEndpointResponse(200, failure(40), "http://fixture.invalid/rest") }
        assertFailsWith<LibraryRequestFailure> {
            AutoplayCandidateSource("server", refused).candidates("seed", emptySet(), count = 2)
        }
        val requests = mutableListOf<String>()
        val unknownSeed = LibraryEndpointTransport { endpoint, _ ->
            requests += endpoint
            LibraryEndpointResponse(200, when (endpoint) {
                "getSong" -> failure(70)
                "getRandomSongs" -> ok(""""randomSongs":{"song":[${song("r1")}]}""")
                else -> ok(""""similarSongs":{}""")
            }, "http://fixture.invalid/rest")
        }
        val tracks = AutoplayCandidateSource("server", unknownSeed).candidates("seed", emptySet(), count = 2)
        assertEquals(listOf("r1"), tracks.map { it.id.rawId })
        assertEquals(listOf("getSong", "getSimilarSongs", "getRandomSongs"), requests)
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun ok(payload: String) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1",$payload}}"""

    private fun failure(code: Int) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"no"}}}"""

    private fun song(id: String) =
        """{"id":"$id","title":"T $id","album":"A","albumId":"al-$id","duration":200}"""

    private fun Fixture.playPast(attemptId: AttemptId, seconds: Int): MutableList<PlaybackCoreEffect> {
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

    private fun fixture(): Fixture {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        val resumePositions = PersistentResumePositionStore(database)
        var identity = 0
        val controller = PlaybackQueueController(
            queues = PersistentQueueStore(database),
            resumePositions = resumePositions,
            identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
            shuffleRandom = Random(1),
        )
        return Fixture(driver, database, resumePositions, controller)
    }

    private fun request(rawIds: List<String>) = PlaybackQueueRequest(
        items = items(rawIds),
        sourceContext = QueueSourceContext(QueueSourceKind.Album, ProviderItemId(SERVER.value, "album"), "Album"),
        startIndex = 0,
        shuffle = false,
    )

    private fun searchRequest(rawIds: List<String>, duration: Duration) = PlaybackQueueRequest(
        items = rawIds.map { PlaybackQueueItem(ProviderItemId(SERVER.value, it), duration) },
        sourceContext = QueueSourceContext(QueueSourceKind.Search, null, "Search"),
        startIndex = 0,
        shuffle = false,
    )

    private fun items(rawIds: List<String>) = rawIds.map { PlaybackQueueItem(ProviderItemId(SERVER.value, it), 180.seconds) }

    private fun insertion(rawIds: List<String>, mode: QueueInsertionMode) = PlaybackQueueInsertion(
        items = items(rawIds),
        sourceContext = QueueSourceContext(QueueSourceKind.Search, null, "Search"),
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
        val SERVER = ServerId("server:listening-session")
    }
}
