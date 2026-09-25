package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sixth review round of the playlist editor and the favourites outbox (§18.6, §28 item 20). The
 * reviewer's probes (q1, q2, q3, q4 and the control q4f) are kept as tests, each asserting what the
 * maintainer decided, with one test for each further edge of a rule. The transport hook of the fifth
 * round's tests lets a test act while a request is out and answer with a status the fake would not.
 */
class PlaylistEditingSixthReviewTest {
    private fun hooked(block: suspend TestScope.(PlaylistEditingFifthReviewTest.Env) -> Unit) = runTest {
        val driver = createTestDriver()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val database = DulcetDatabaseStore.open(driver)
            val clock = ManualWallClock(now = 3_000_000)
            val server = FakePlaylistServer(now = { clock.now })
            block(
                PlaylistEditingFifthReviewTest.Env(
                    driver, server, PlaylistEditingFifthReviewTest.Hooked(server), database, SeenCacheStore(database, clock), scope,
                ),
            )
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    private fun status(code: Int, retryAfter: String? = null) =
        LibraryEndpointResponse(code, "<html>no</html>", "http://fixture.invalid/rest", retryAfter = retryAfter)

    private fun TestScope.timed(env: PlaylistEditingFifthReviewTest.Env) =
        env.session(LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))

    private val song1 = LibraryEntityRef(LibraryEntityKind.Track, "song-1")
    private val song2 = LibraryEntityRef(LibraryEntityKind.Track, "song-2")

    // ---- SF2: every 429 a flush meets counts for its run -----------------------------------------------

    /** q1. A 429 for a change withdrawn while its send was out is a 429 the flush met: the run goes on. */
    @Test
    fun q1_aWithdrawnChangesUncounted429DoesNotEndTheRun() = hooked { env ->
        val session = timed(env)
        var stars = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star") {
                stars++
                if (stars == 2) session.favourites.withdraw(song1, MutationField.Starred)
                status(429, null)
            } else {
                null
            }
        }
        session.setOnline(false)
        session.favourites.setFavourite(song1, true)
        session.favourites.setFavourite(song2, true)
        session.setOnline(true)
        session.favourites.flush()
        runCurrent()
        assertEquals(1, stars, "fixture: first 429 (song-1, queued) begins the run")
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, stars, "fixture: retry sent song-1 again, withdrawn while out, 429")
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, stars, "fixture: song-2 sent after the 2 s wait")
        val held = env.fav.filterIsInstance<MutationOutcome.Held>().map { it.target.rawId }
        assertEquals(listOf("song-1"), held, "one run of 429s told twice: the uncounted 429 ended the run (stars=$stars) ${env.fav}")
        // The run went on, so song-2's 429 is its second counted one: the floor doubled to 4 s.
        advanceTimeBy(3_900); runCurrent()
        assertEquals(3, stars, "song-2 was asked again inside the doubled floor")
        advanceTimeBy(200); runCurrent()
        assertEquals(4, stars, "song-2 was not asked again when the doubled floor ended")
    }

    /** q1 on the playlist side: a rename withdrawn while out and answered 429 does not end the run. */
    @Test
    fun aPlaylistChangeWithdrawnWhileOutAndAnswered429DoesNotEndTheRun() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        session.reader.open(LibraryQuery.Playlist(q.id)) { }
        advanceUntilIdle()
        var updates = 0
        var withdrawn: PlaylistEditRecord? = null
        env.hooked.before = { endpoint, parameters ->
            if (endpoint == "updatePlaylist") {
                updates++
                if (updates == 2) withdrawn = session.playlists.withdraw(p.id, PlaylistRowKind.Details)
                assertEquals(if (updates <= 2) p.id else q.id, parameters.first { it.first == "playlistId" }.second, "fixture: send order")
                status(429, null)
            } else {
                null
            }
        }
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.playlists.rename(q.id, "Morning")
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertEquals(1, updates, "fixture: the first rename met a 429")
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, updates, "fixture: the retry sent the first rename again")
        assertEquals(PlaylistEditRecord.AlreadySent, withdrawn, "fixture: withdrawn while its send was out")
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, updates, "fixture: the second rename sent after the 2 s wait")
        val held = env.outcomes.filterIsInstance<PlaylistEditOutcome.Held>().map { it.playlistId }
        assertEquals(listOf(p.id), held, "one run of 429s told twice: the withdrawn change's 429 ended the run ${env.outcomes}")
    }

    // ---- SF1: a create deleted while its send is out ----------------------------------------------------

    /** q2. A 429 proves the deleted create made nothing: its row goes, untold, and nothing is looked for. */
    @Test
    fun q2_aCreateDeletedWhileItsSendIsOutIsNotToldHeldOnA429() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        var deleted: PlaylistEditRecord? = null
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist") {
                creates++
                if (creates == 1) deleted = session.playlists.delete(localId!!)
                status(429, null)
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road").localId)
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertEquals(1, creates)
        assertEquals(PlaylistEditRecord.Pending, deleted, "fixture: deleted while its send was out")
        val held = env.outcomes.filterIsInstance<PlaylistEditOutcome.Held>()
        assertEquals(0L, session.playlists.pendingCount(), "the 429 made nothing, yet the deleted create's row was kept")
        val listings = env.server.count("getPlaylists")
        advanceTimeBy(10_000); runCurrent(); advanceUntilIdle()
        assertEquals(1, creates, "the deleted create was sent again")
        assertEquals(0, env.server.playlists.size)
        assertEquals(listings, env.server.count("getPlaylists"), "the deleted create was looked for although the 429 proved it made nothing")
        assertEquals(emptyList(), held, "Held told for a create the person deleted while its send was out: ${env.outcomes}")
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossiblyCreated }, "${env.outcomes}")
    }

    /**
     * SF1 with an earlier send in doubt: the create's first send was answered 500, its re-send is out
     * when the person deletes it, and that re-send meets a 429. The 429 proves only the re-send made
     * nothing, so the tombstone stays for the next flush to look — and, not a queued change, it is
     * never told `Held` and begins no run.
     */
    @Test
    fun aCreateDeletedWhileItsResendIsOutKeepsItsTombstoneUntoldWhenAnEarlierSendIsInDoubt() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist") {
                creates++
                if (creates == 1) {
                    status(500)
                } else {
                    session.playlists.delete(localId!!)
                    status(429, null)
                }
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road").localId)
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertEquals(1, creates, "fixture: the first send's answer proves nothing")
        session.playlists.flush()
        runCurrent()
        assertEquals(2, creates, "fixture: looked for, not found, sent again, deleted while out, 429")
        assertEquals(1L, session.playlists.pendingCount(), "the first send may still land: its tombstone must stay to be looked for")
        assertEquals(emptyList(), env.outcomes.filterIsInstance<PlaylistEditOutcome.Held>(), "Held told for a create the person deleted: ${env.outcomes}")
        val listings = env.server.count("getPlaylists")
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, creates, "the deleted create was sent again")
        // The look lists the server's playlists; the list the person sees is then read again too.
        assertTrue(env.server.count("getPlaylists") > listings, "the tombstone was not looked for once more")
        assertEquals(0L, session.playlists.pendingCount())
    }

    /** SF1, the other half: the deleted create's 429 begins no run, so the next change's 429 is told. */
    @Test
    fun aDeletedCreates429BeginsNoRunAndTheNextChangesIsTold() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var localId: String? = null
        env.hooked.before = { endpoint, _ ->
            when (endpoint) {
                "createPlaylist" -> { session.playlists.delete(localId!!); status(429, null) }
                "updatePlaylist" -> status(429, null)
                else -> null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road").localId)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        advanceTimeBy(2_001); runCurrent()
        val held = env.outcomes.filterIsInstance<PlaylistEditOutcome.Held>()
        assertEquals(listOf(p.id to PlaylistRowKind.Details), held.map { it.playlistId to it.kind }, "the rename's 429 begins the run and is told: ${env.outcomes}")
    }

    // ---- NIT q3: a change already in progress finishes inside the other outbox's wait -------------------

    /**
     * q3, pinned as stated (§18.6): the wait gates the START of each change. A create already under way
     * when the favourites flush meets `Retry-After: 60` finishes its remaining request — the comment
     * write — inside the wait; nothing new begins.
     */
    @Test
    fun q3_aCreateAlreadyUnderWayFinishesItsCommentWriteInsideTheOtherOutboxsWait() = hooked { env ->
        val session = timed(env)
        var stars = 0
        var creates = 0
        env.hooked.before = { endpoint, _ ->
            when (endpoint) {
                // One 429 only: a limiter answering every retry would keep the test's clock running.
                "star" -> { stars++; if (stars == 1) status(429, "60") else null }
                "createPlaylist" -> {
                    creates++
                    if (creates == 1) {
                        session.favourites.setFavourite(song1, true)
                        repeat(20) { yield() }
                    }
                    null
                }
                else -> null
            }
        }
        session.setOnline(false)
        session.playlists.create("Road", listOf("song-1"), comment = "for the drive")
        session.playlists.create("Later")
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertEquals(1, stars, "fixture: the star met Retry-After 60 while createPlaylist was out")
        assertEquals(1, env.server.count("createPlaylist"), "a new change began inside the wait")
        assertEquals(1, env.server.count("updatePlaylist"), "the create under way did not finish its comment write")
        assertEquals("for the drive", env.server.playlists.single().comment)
        advanceTimeBy(59_000); runCurrent()
        assertEquals(1, env.server.count("createPlaylist"), "a new change began inside the wait")
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, env.server.count("createPlaylist"), "the next change was sent when the wait ended")
    }

    // ---- NIT q4: a change edited while its only send is out ---------------------------------------------

    /** q4. Every send of the change was answered 429: withdrawing it is CompactedAway, as favourites answer. */
    @Test
    fun q4_aChangeEditedWhileItsOnlySendIsOutAndAnswered429IsCompactedAway() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var updates = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "updatePlaylist") {
                updates++
                if (updates == 1) session.playlists.rename(p.id, "Evening")
                status(429, "60")
            } else {
                null
            }
        }
        session.playlists.rename(p.id, "Morning")
        runCurrent()
        assertEquals(1, updates, "fixture: one send, answered 429")
        assertEquals("Mix", p.name, "fixture: nothing applied")
        val record = session.playlists.withdraw(p.id, PlaylistRowKind.Details)
        assertEquals(PlaylistEditRecord.CompactedAway, record, "every send of the change was answered 429")
    }

    /** q4f. The favourites parity control for q4: the same sequence answers CompactedAway there. */
    @Test
    fun q4f_favouritesParityControl() = hooked { env ->
        val session = timed(env)
        var stars = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star") {
                stars++
                if (stars == 1) session.favourites.setFavourite(song1, false)
                status(429, "60")
            } else {
                null
            }
        }
        session.favourites.setFavourite(song1, true)
        runCurrent()
        assertEquals(1, stars)
        assertEquals(1L, session.favourites.pendingCount(), "fixture: the edit made while out is queued")
        assertEquals(MutationRecord.CompactedAway, session.favourites.withdraw(song1, MutationField.Starred))
    }

    /** q4 for an earlier send in doubt: its value stays recorded, so the change is still AlreadySent. */
    @Test
    fun aValueAnEarlierSendLeftInDoubtStaysRecordedWhenALaterSendIsAnswered429() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var updates = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "updatePlaylist") {
                updates++
                when (updates) {
                    1 -> status(500)
                    else -> { session.playlists.rename(p.id, "Evening"); status(429, "60") }
                }
            } else {
                null
            }
        }
        session.playlists.rename(p.id, "Morning")
        runCurrent()
        assertEquals(1, updates, "fixture: the first send's answer was lost")
        session.playlists.flush()
        runCurrent()
        assertEquals(2, updates, "fixture: sent again, edited while out, answered 429")
        assertEquals(PlaylistEditRecord.AlreadySent, session.playlists.withdraw(p.id, PlaylistRowKind.Details), "the first send may have landed")
    }

    /** q4 for a create edited here while its only send is out: the 429 made nothing, so it is never sent. */
    @Test
    fun aCreateRenamedWhileItsOnlySendIsOutAndAnswered429IsCompactedAway() = hooked { env ->
        val session = timed(env)
        var localId: String? = null
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist") {
                session.playlists.rename(localId!!, "Trip")
                status(429, "60")
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road").localId)
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.withdraw(localId!!, PlaylistRowKind.Create), "the only send was answered 429")
        assertEquals(0L, session.playlists.pendingCount())
    }

    // ---- NIT: decision 1(b), a create awaiting the person's answer --------------------------------------

    /**
     * Residual 4 of §18.6: B's own playlist X commits only after B's lookup, while B waits for the person
     * on another playlist Y of the name. B is still in doubt — its send may have made X — so the lone
     * candidate X of another create A of that name is named, never adopted.
     */
    @Test
    fun aCreateAwaitingThePersonStillKeepsAnotherFromAdoptingALateCommittedPlaylist() = hooked { env ->
        val session = env.session()
        var creates = 0
        var late: FakePlaylistServer.Playlist? = null
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist") {
                creates++
                if (creates == 2) late = env.server.add("Mix", listOf("song-1")) // B's own send, committed late
                status(500)
            } else {
                null
            }
        }
        session.setOnline(false)
        val b = assertNotNull(session.playlists.create("Mix", listOf("song-1")).localId)
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        // Another client makes Y of the name, holding other songs: B cannot adopt it and asks.
        val y = env.server.add("Mix", listOf("song-9"))
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("Mix", listOf("song-1")).localId)
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(PlaylistEditOutcome.PossibleDuplicate(b, "Mix", listOf(y.id)) in env.outcomes, "fixture: B waits for the person on Y: ${env.outcomes}")
        assertEquals(2, creates, "fixture: A was sent, its answer lost")
        val x = assertNotNull(late).id
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(session.reader.playlistOverlay.resolve(a) != x, "A adopted the playlist B's send may have made: ${env.outcomes}")
        assertTrue(PlaylistEditOutcome.PossibleDuplicate(a, "Mix", listOf(x)) in env.outcomes, "the lone candidate was not named: ${env.outcomes}")
    }
}
