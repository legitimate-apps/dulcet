package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fifth review round of the playlist editor and the favourites outbox (§18.6, §28 revision 104 item 21). The
 * reviewer's probes (x1, x1b, x2, x3, x4, t6, t6b, c1, p8) are kept here as tests, each asserting
 * what the maintainer decided, with one test for each further edge of a rule. A transport hook
 * stands between the editor and the fake server, so a test can act — delete, withdraw, star — while
 * a request is out, and answer with a status the fake would not.
 */
class PlaylistEditingFifthReviewTest {
    internal class Hooked(val server: FakePlaylistServer) : LibraryEndpointTransport {
        /** Runs before the fake answers; a non-null result replaces the answer, and nothing is applied. */
        var before: suspend (String, List<Pair<String, String>>) -> LibraryEndpointResponse? = { _, _ -> null }

        override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
            before(endpoint, parameters.toList())?.let { return it }
            return server.request(endpoint, parameters)
        }

        override suspend fun requestRepeated(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): LibraryEndpointResponse {
            before(endpoint, parameters)?.let { return it }
            return server.requestRepeated(endpoint, parameters, formPost)
        }
    }

    internal class Env(
        val driver: SqlDriver,
        val server: FakePlaylistServer,
        val hooked: Hooked,
        private val database: DulcetDatabaseStore,
        private val store: SeenCacheStore,
        private val scope: CoroutineScope,
    ) {
        val outcomes = mutableListOf<PlaylistEditOutcome>()
        val fav = mutableListOf<MutationOutcome>()

        fun session(
            config: LibraryReaderConfig = LibraryReaderConfig(lookAheadMaxPerViewport = 0),
            otherOutboxes: ReconnectOutboxes = ReconnectOutboxes.None,
        ): LibraryReaderSession =
            LibraryReaderSession(
                database.database, store.bind(PlaylistEnv.BINDING), hooked, scope, config,
                otherOutboxes = otherOutboxes, formPost = true, foreground = false,
            ).also { s ->
                s.playlists.addOutcomeListener(outcomes::add)
                s.favourites.addOutcomeListener { fav += it }
            }
    }

    private fun hooked(block: suspend TestScope.(Env) -> Unit) = runTest {
        val driver = createTestDriver()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val database = DulcetDatabaseStore.open(driver)
            val clock = ManualWallClock(now = 3_000_000)
            val server = FakePlaylistServer(now = { clock.now })
            block(Env(driver, server, Hooked(server), database, SeenCacheStore(database, clock), scope))
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    private fun status(code: Int, retryAfter: String? = null) =
        LibraryEndpointResponse(code, "<html>no</html>", "http://fixture.invalid/rest", retryAfter = retryAfter)

    /** A create answered with the id of a playlist the server already holds, applying nothing. */
    private fun answeredWith(id: String, name: String) = LibraryEndpointResponse(
        200,
        """{"subsonic-response":{"status":"ok","version":"1.16.1","playlist":{"id":"$id","name":"$name","songCount":1,"duration":60,"owner":"listener","public":false}}}""",
        "http://fixture.invalid/rest",
    )

    private fun TestScope.timedConfig() = LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource)

    private fun TestScope.timed(env: Env) = env.session(timedConfig())

    private val song1 = LibraryEntityRef(LibraryEntityKind.Track, "song-1")
    private val song2 = LibraryEntityRef(LibraryEntityKind.Track, "song-2")

    /**
     * Two offline creates of one name: the first answered 500 and not applied, the second applied
     * and then answered 500 — both in doubt, and the one playlist on the server is the second's.
     */
    private suspend fun TestScope.twoInDoubt(env: Env, session: LibraryReaderSession): Triple<String, String, String> {
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("New Playlist").localId)
        val b = assertNotNull(session.playlists.create("New Playlist").localId)
        var creates = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist") {
                creates++
                when (creates) {
                    1 -> status(500)
                    2 -> { env.server.applyThenStatus["createPlaylist"] = 500; null }
                    else -> null
                }
            } else {
                null
            }
        }
        session.setOnline(true)
        // Its reconnect flushes both outboxes first (§16.14 step 1): that is the flush.
        runCurrent()
        env.server.applyThenStatus.clear()
        assertEquals(2, creates, "fixture: both creates were sent")
        return Triple(a, b, env.server.playlists.single().id)
    }

    // ---- Finding 1: two creates of one name in doubt ---------------------------------------------------

    @Test
    fun x1_aPlaylistNamedToThePersonForOneCreateIsNeverAdoptedByAnother() = hooked { env ->
        val session = env.session()
        val (a, b, made) = twoInDoubt(env, session)
        // The person deletes the second, then renames the first.
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(b))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.rename(a, "Road trip"))
        session.playlists.flush()
        advanceUntilIdle()
        val offered = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossiblyCreated>().flatMap { it.candidates }
        assertEquals(listOf(made), offered, "fixture: the deleted create's possible playlist was named: ${env.outcomes}")
        val kept = session.reader.playlistOverlay.resolve(a)
        assertTrue(kept != made, "the person is offered to delete $made as the deleted create's, and the kept create IS it: ${env.outcomes}")
        assertEquals("Road trip", env.server.playlists.single { it.id == kept }.name, "the kept create is its own playlist")
    }

    @Test
    fun x1b_confirmingTheOfferNeverDeletesThePlaylistTheKeptCreateHolds() = hooked { env ->
        val session = env.session()
        val (a, b, _) = twoInDoubt(env, session)
        // The person deletes the duplicate and adds songs to the one kept.
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(b))
        assertEquals(PlaylistEditRecord.Pending, session.playlists.append(a, listOf("song-7", "song-8")))
        session.playlists.flush()
        advanceUntilIdle()
        val offered = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossiblyCreated>()
        assertEquals(1, offered.size, "fixture: ${env.outcomes}")
        // What a shell does on the person's "Delete it": an ordinary delete by id.
        offered.flatMap { it.candidates }.forEach { session.playlists.delete(it) }
        session.playlists.flush()
        advanceUntilIdle()
        val left = env.server.playlists.singleOrNull()
        assertNotNull(left, "the person kept one create and confirmed the offer; the kept one is gone: ${env.outcomes}")
        assertEquals(session.reader.playlistOverlay.resolve(a), left.id)
        assertEquals(listOf("song-7", "song-8"), left.entries)
    }

    @Test
    fun x4_whileAnotherCreateIsInDoubtALoneCandidateIsNamedNeverAdopted() = hooked { env ->
        val session = env.session()
        val (a, _, bsOwn) = twoInDoubt(env, session)
        repeat(2) { session.playlists.flush(); advanceUntilIdle() }
        assertTrue(session.reader.playlistOverlay.resolve(a) != bsOwn, "the first create adopted the playlist the second create's send made: ${env.outcomes}")
        assertTrue(
            PlaylistEditOutcome.PossibleDuplicate(a, "New Playlist", listOf(bsOwn)) in env.outcomes,
            "the lone candidate was not named to the person: ${env.outcomes}",
        )
    }

    @Test
    fun aCreateDeletedHereWhileInDoubtStillKeepsAnotherFromAdoptingItsPlaylist() = hooked { env ->
        val session = env.session()
        val (a, b, bsOwn) = twoInDoubt(env, session)
        // Deleted here, and nothing else touched: the first create is looked at before the deleted one is.
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(b))
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(session.reader.playlistOverlay.resolve(a) != bsOwn, "the first create adopted what the deleted create's send made: ${env.outcomes}")
        assertTrue(
            PlaylistEditOutcome.PossibleDuplicate(a, "New Playlist", listOf(bsOwn)) in env.outcomes,
            "the lone candidate was not named to the person: ${env.outcomes}",
        )
        // Offered for the first create, it is never offered for deletion as the deleted one's too.
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossiblyCreated && bsOwn in it.candidates }, "${env.outcomes}")
        assertEquals(1, env.server.playlists.size, "nothing was deleted or sent again")
    }

    @Test
    fun createsOfDifferentNamesInDoubtTogetherEachAdoptTheirOwnLoneCandidate() = hooked { env ->
        val session = env.session()
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("Morning", listOf("song-1")).localId)
        val b = assertNotNull(session.playlists.create("Evening", listOf("song-2")).localId)
        session.setOnline(true)
        // Both land and both answers are 500: each is in doubt, and each is its own failure.
        env.server.applyThenStatus["createPlaylist"] = 500
        session.playlists.flush()
        env.server.applyThenStatus.clear()
        assertEquals(2, env.server.playlists.size, "fixture: both creates landed")
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossibleDuplicate }, "fixture: nothing was named yet: ${env.outcomes}")
        repeat(2) { session.playlists.flush(); advanceUntilIdle() }
        val morning = env.server.playlists.single { it.name == "Morning" }.id
        val evening = env.server.playlists.single { it.name == "Evening" }.id
        // The rule that names a lone candidate is about a create that sent the SAME name: one of another
        // name cannot have made it, and asking the person about each would be a question with one answer.
        assertEquals(morning, session.reader.playlistOverlay.resolve(a), "outcomes: ${env.outcomes}")
        assertEquals(evening, session.reader.playlistOverlay.resolve(b), "outcomes: ${env.outcomes}")
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossibleDuplicate }, "a lone candidate was named: ${env.outcomes}")
        assertEquals(2, env.server.playlists.size, "nothing was sent again")
    }

    @Test
    fun anIdOfferedToThePersonForOneCreateIsNeverACandidateForAnother() = hooked { env ->
        val session = env.session()
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("Mix", listOf("song-1")).localId)
        val b = assertNotNull(session.playlists.create("Mix", listOf("song-1")).localId)
        session.setOnline(true)
        var creates = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist") {
                creates++
                // The first lands and is answered 500 (its own failure: the flush moves on); the second
                // lands and its answer is lost (the flush stops).
                if (creates == 1) env.server.applyThenStatus["createPlaylist"] = 500
                if (creates == 2) {
                    env.server.applyThenStatus.clear()
                    env.server.applyThenLose += "createPlaylist"
                }
            }
            null
        }
        session.playlists.flush()
        env.server.applyThenLose.clear()
        val (first, second) = env.server.playlists.map { it.id }
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(
            PlaylistEditOutcome.PossibleDuplicate(a, "Mix", listOf(first, second)) in env.outcomes,
            "fixture: the first create was offered both: ${env.outcomes}",
        )
        // Both were offered to the person for the first create: neither is taken for the second, which
        // is sent again rather than adopt a playlist the person may yet choose.
        val bIs = session.reader.playlistOverlay.resolve(b)
        assertTrue(bIs != first && bIs != second, "the second create took a playlist offered for the first: ${env.outcomes}")
        assertEquals(3, env.server.count("createPlaylist"))
        assertEquals(listOf(first, second), session.playlists.pendingChanges().single { it.playlistId == a }.candidates)
    }

    // ---- NIT x2: a choice another create voids is asked again ---------------------------------------------

    @Test
    fun x2_thePersonsChoiceIsAdoptedNotTheCandidateTheyPassedOver() = hooked { env ->
        val session = env.session()
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("Mix", listOf("song-1")).localId)
        session.playlists.create("Mix", listOf("song-1"))
        session.setOnline(true)
        var creates = 0
        var phase = 1
        var listings = 0
        env.hooked.before = { endpoint, _ ->
            when {
                endpoint == "createPlaylist" && phase == 1 -> {
                    creates++
                    if (creates == 1) env.server.applyThenStatus["createPlaylist"] = 500
                    if (creates == 2) {
                        env.server.applyThenStatus.clear()
                        env.server.applyThenLose += "createPlaylist"
                    }
                    null
                }
                // Second flush: the first create's lookup lists; the second's cannot reach the server.
                endpoint == "getPlaylists" && phase == 2 && ++listings == 2 -> status(502)
                else -> null
            }
        }
        session.playlists.flush()
        env.server.applyThenLose.clear()
        val (first, second) = env.server.playlists.map { it.id }
        phase = 2
        session.playlists.flush()
        assertTrue(
            PlaylistEditOutcome.PossibleDuplicate(a, "Mix", listOf(first, second)) in env.outcomes,
            "fixture: the first create waits on a choice naming both: ${env.outcomes}",
        )
        phase = 3
        assertEquals(PlaylistEditRecord.Pending, session.playlists.chooseCreated(a, second), "fixture: the person chooses")
        advanceUntilIdle()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(second, session.reader.playlistOverlay.resolve(a), "the person's choice was not adopted: ${env.outcomes}")
    }

    @Test
    fun aChoiceThatAnotherCreateSettlesIsVoidAndAskedAgainNeverReplacedSilently() = hooked { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val a = assertNotNull(session.playlists.create("Mix", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val p = env.server.playlists.single()
        // Another client's namesake, made before the first create is looked for.
        val q = env.server.add("Mix", listOf("song-1"))
        session.setOnline(false)
        val b = assertNotNull(session.playlists.create("Mix", listOf("song-1")).localId)
        var creates = 0
        // The second create's first send is refused, proving it never applied: it stays unsent.
        env.hooked.before = { endpoint, _ -> if (endpoint == "createPlaylist" && ++creates == 1) status(400) else null }
        session.setOnline(true) // its reconnect flushes both outboxes first (§16.14 step 1): that is the flush
        advanceUntilIdle()
        assertTrue(
            PlaylistEditOutcome.PossibleDuplicate(a, "Mix", listOf(p.id, q.id)) in env.outcomes,
            "fixture: the first create waits on a choice of both: ${env.outcomes}",
        )
        assertEquals(PlaylistEditRecord.Pending, session.playlists.chooseCreated(a, p.id), "fixture: the person chooses")
        // The second create is sent again, before the first (whose choice moved it last), and the server
        // answers it with the playlist the person chose — one it already holds under that name.
        env.hooked.before = { endpoint, _ -> if (endpoint == "createPlaylist") answeredWith(p.id, "Mix") else null }
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(p.id, session.reader.playlistOverlay.resolve(b), "fixture: the other create settled the chosen playlist")
        assertTrue(session.reader.playlistOverlay.resolve(a) != q.id, "adopted the playlist the person passed over: ${env.outcomes}")
        assertEquals(
            PlaylistEditOutcome.PossibleDuplicate(a, "Mix", listOf(q.id)),
            env.outcomes.last { it.playlistId == a },
            "the void choice was not asked again: ${env.outcomes}",
        )
        assertEquals(listOf(q.id), session.playlists.pendingChanges().single { it.playlistId == a }.candidates)
    }

    // ---- NIT x3: a delete arriving while the create's comment is written ---------------------------------

    @Test
    fun x3_aCommentWriteAlreadyOutWhenTheDeleteArrivesLandsTheStatedResidual() = hooked { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1"), comment = "for the drive").localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val made = env.server.playlists.single()
        var deleted = false
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "updatePlaylist" && !deleted) {
                deleted = true
                session.playlists.delete(localId)
            }
            null
        }
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(deleted, "fixture: the delete arrived while the comment write was out")
        // The residual (§18.6): that write is already out and lands. The playlist is named, never deleted.
        assertEquals("for the drive", made.comment)
        assertEquals(1, env.server.count("updatePlaylist"))
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id))), env.outcomes)
    }

    @Test
    fun aDeleteMadeWhileTheCreateWaitsForItsPlaylistsLockWritesNothingToIt() = hooked { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1"), comment = "for the drive").localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val made = env.server.playlists.single()
        // Another holder of the playlist's list lock — a read of it — keeps the settling create waiting.
        val lock = session.reader.listLock(playlistDetailListKey(made.id))
        lock.lock()
        val flushing = launch { session.playlists.flush() }
        runCurrent()
        assertTrue(flushing.isActive, "fixture: the flush waits on the lock")
        assertTrue(env.server.count("getPlaylist") >= 1, "fixture: the create was looked for and found before the lock")
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(localId))
        lock.unlock()
        advanceUntilIdle()
        assertEquals(0, env.server.count("updatePlaylist"), "written to a playlist found by inference after the create was deleted: ${env.outcomes}")
        assertEquals(null, made.comment)
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id))), env.outcomes)
    }

    // ---- Finding 2: a 429 for a change no longer queued ---------------------------------------------------

    @Test
    fun t6_aWithdrawnChangesRefusalIsNotToldAndTheChangeActuallyHeldIs() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = timed(env)
        val detail = session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var stars = 0
        var updates = 0
        var withdrawn: MutationRecord? = null
        env.hooked.before = { endpoint, _ ->
            when (endpoint) {
                "star" -> {
                    // Out for a while: the playlist flush starts (and passes its wait check) meanwhile.
                    repeat(3) { yield() }
                    stars++
                    if (stars == 1) withdrawn = session.favourites.withdraw(song1, MutationField.Starred)
                    status(429, null)
                }
                "updatePlaylist" -> {
                    // Out longer than the star: its 429 lands after the favourites' and moves the wait out.
                    repeat(30) { yield() }
                    updates++
                    status(429, "10")
                }
                else -> null
            }
        }
        session.favourites.setFavourite(song1, true)
        session.playlists.rename(p.id, "Evening")
        runCurrent()
        assertEquals(MutationRecord.AlreadySent, withdrawn, "fixture: withdrawn while its send was out")
        assertEquals(1 to 1, stars to updates, "fixture: both outboxes met a 429")
        assertEquals(0L, session.favourites.pendingCount(), "fixture: the favourites queue is empty")
        // A new star during the wait: its flush is stopped by the wait.
        advanceTimeBy(5_000); runCurrent()
        session.favourites.setFavourite(song2, true)
        runCurrent()
        assertEquals(1, stars, "fixture: sent inside the wait")
        advanceTimeBy(5_001); runCurrent()
        assertEquals(2, stars, "fixture: the star was sent when the 10 s wait ended")
        val held = env.fav.filterIsInstance<MutationOutcome.Held>().map { it.target.rawId }
        assertEquals(listOf("song-2"), held, "told for the withdrawn change, or not for the one held: ${env.fav}")
        detail.close()
    }

    @Test
    fun t6b_heldIsNeverToldForAChangeThePersonAlreadyWithdrew() = hooked { env ->
        val session = timed(env)
        var stars = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star") {
                stars++
                if (stars == 1) session.favourites.withdraw(song1, MutationField.Starred)
                status(429, null)
            } else {
                null
            }
        }
        session.favourites.setFavourite(song1, true)
        runCurrent()
        advanceTimeBy(1_000)
        session.favourites.setFavourite(song2, true)
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(stars >= 2, "fixture: the held star was sent after the wait ($stars)")
        val heldTargets = env.fav.filterIsInstance<MutationOutcome.Held>().map { it.target.rawId }
        assertEquals(listOf("song-2"), heldTargets, "the change actually held was not the one told: ${env.fav}")
    }

    @Test
    fun aPlaylistChangeWithdrawnWhileItsSendIsOutIsNotToldAndBeginsNoRun() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        session.reader.open(LibraryQuery.Playlist(q.id)) { }
        advanceUntilIdle()
        var updates = 0
        var limited = true
        var withdrawn: PlaylistEditRecord? = null
        var firstSentFor: String? = null
        env.hooked.before = { endpoint, parameters ->
            if (endpoint == "updatePlaylist") {
                updates++
                if (updates == 1) {
                    firstSentFor = parameters.firstOrNull { it.first == "playlistId" }?.second
                    withdrawn = session.playlists.withdraw(p.id, PlaylistRowKind.Details)
                }
                if (limited) status(429, "5") else null
            } else {
                null
            }
        }
        session.playlists.rename(p.id, "Evening")
        runCurrent()
        assertEquals(1, updates)
        assertEquals(p.id, firstSentFor, "fixture: the first send is the rename of ${p.id}")
        assertEquals(PlaylistEditRecord.AlreadySent, withdrawn, "fixture: withdrawn while its send was out")
        assertEquals(0, env.outcomes.count { it is PlaylistEditOutcome.Held }, "told Held for a change the person withdrew: ${env.outcomes}")
        // A change made during the wait is sent when it ends; its 429 is a run's first, and told.
        session.playlists.rename(q.id, "Morning")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, updates, "fixture: the new change was sent when the wait ended")
        assertEquals(
            listOf(q.id),
            env.outcomes.filterIsInstance<PlaylistEditOutcome.Held>().map { it.playlistId },
            "the change held was not told: ${env.outcomes}",
        )
        limited = false
    }

    // ---- T6: a replaced retry is cancelled -------------------------------------------------------------

    @Test
    fun aReplacedRetryNeverFlushesAnyOutboxBeforeTheLaterWaitEnds() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        var otherFlushes = 0
        val session = env.session(timedConfig(), otherOutboxes = ReconnectOutboxes { otherFlushes++ })
        val detail = session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var stars = 0
        var updates = 0
        var limited = true
        env.hooked.before = { endpoint, _ ->
            when (endpoint) {
                // No Retry-After: the floor, two seconds.
                "star" -> { repeat(3) { yield() }; stars++; if (limited) status(429, null) else null }
                // Out longer: its 429 lands second and moves the wait out to ten seconds.
                "updatePlaylist" -> { repeat(30) { yield() }; updates++; if (limited) status(429, "10") else null }
                else -> null
            }
        }
        session.favourites.setFavourite(song1, true)
        session.playlists.rename(p.id, "Evening")
        runCurrent()
        assertEquals(1 to 1, stars to updates, "fixture: both outboxes met a 429")
        assertEquals(0, otherFlushes)
        limited = false
        advanceTimeBy(9_999); runCurrent()
        // The retry the two-second wait scheduled was replaced: no outbox — the scrobbles among them,
        // which no 429 of these outboxes stops — is flushed inside the server's ten seconds.
        assertEquals(0, otherFlushes, "an outbox was flushed inside the server's wait by a replaced retry")
        assertEquals(1 to 1, stars to updates)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, otherFlushes, "one flush of every outbox when the wait ends")
        assertEquals(2 to 2, stars to updates)
        detail.close()
    }

    // ---- Finding 3: the wait is checked before each change is sent -----------------------------------------

    @Test
    fun c1_aRunningPlaylistFlushSendsNoFurtherChangeInsideTheFavouritesWait() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        session.reader.open(LibraryQuery.Playlist(q.id)) { }
        advanceUntilIdle()
        var stars = 0
        var updates = 0
        env.hooked.before = { endpoint, _ ->
            when (endpoint) {
                "star" -> { stars++; if (stars == 1) status(429, "60") else null }
                "updatePlaylist" -> {
                    updates++
                    if (updates == 1) {
                        // While the first rename is out, a star is made: its flush meets a 429, Retry-After 60.
                        session.favourites.setFavourite(song1, true)
                        repeat(20) { yield() }
                    }
                    null
                }
                else -> null
            }
        }
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.playlists.rename(q.id, "Morning")
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertEquals(1, stars, "fixture: the star met its 429 while the playlist flush ran")
        assertEquals(1, updates, "the playlist flush sent $updates writes inside the server's Retry-After: 60")
        assertEquals("Evening", p.name, "fixture: the write already out landed")
        assertEquals("Other", q.name)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("Morning", q.name, "sent when the wait ended")
    }

    @Test
    fun aRunningFavouritesFlushSendsNoFurtherChangeInsideThePlaylistWait() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var stars = 0
        var updates = 0
        env.hooked.before = { endpoint, _ ->
            when (endpoint) {
                "star" -> {
                    stars++
                    if (stars == 1) {
                        // While the first star is out, a rename is made: its flush meets a 429, Retry-After 60.
                        session.playlists.rename(p.id, "Evening")
                        repeat(20) { yield() }
                    }
                    null
                }
                "updatePlaylist" -> { updates++; status(429, "60") }
                else -> null
            }
        }
        session.setOnline(false)
        session.favourites.setFavourite(song1, true)
        session.favourites.setFavourite(song2, true)
        session.setOnline(true)
        // Its reconnect flushes both outboxes first (§16.14 step 1): that is the flush.
        runCurrent()
        assertEquals(1, updates, "fixture: the rename met its 429 while the favourites flush ran")
        assertEquals(1, stars, "the favourites flush sent $stars stars inside the server's Retry-After: 60")
        assertEquals(1L, session.favourites.pendingCount())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, stars, "sent when the wait ended")
    }

    // ---- NIT M8: the playlist side of "a flush stopped by the wait leaves its run alone" -------------------

    @Test
    fun p8_aPlaylistFlushStoppedByTheWaitDoesNotEndTheRun() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        session.reader.open(LibraryQuery.Playlist(q.id)) { }
        advanceUntilIdle()
        var updates = 0
        env.hooked.before = { endpoint, _ -> if (endpoint == "updatePlaylist") { updates++; status(429, "5") } else null }
        session.playlists.rename(p.id, "Evening")
        runCurrent()
        assertEquals(1, updates)
        advanceTimeBy(1_000)
        session.playlists.rename(q.id, "Morning")
        runCurrent()
        assertEquals(1, updates, "sent early")
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, updates)
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.Held }, "the same run told twice: ${env.outcomes}")
        assertFalse(env.outcomes.any { it is PlaylistEditOutcome.Saved })
    }
}
