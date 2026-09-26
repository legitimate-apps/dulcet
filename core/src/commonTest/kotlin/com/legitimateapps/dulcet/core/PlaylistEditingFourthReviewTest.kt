package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
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
 * The fourth review round of the playlist editor and the favourites outbox (§18.6, §28 revision 104 item 21). The
 * reviewer's probes r1–r12 are kept here as tests: each asserts what the maintainer decided, and each
 * rule that changed was shown failing on the round before first. A transport hook stands between the
 * editor and the fake server, so a test can act — delete, withdraw, commit late — while a request is
 * out, and answer with a status the fake would not.
 */
class PlaylistEditingFourthReviewTest {
    internal class Hooked(val server: FakePlaylistServer) : LibraryEndpointTransport {
        /** Runs before the fake answers; a non-null result replaces the answer, and nothing is applied. */
        var before: suspend (String, List<Pair<String, String>>) -> LibraryEndpointResponse? = { _, _ -> null }
        val seen = mutableListOf<String>()

        override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
            seen += endpoint
            before(endpoint, parameters.toList())?.let { return it }
            return server.request(endpoint, parameters)
        }

        override suspend fun requestRepeated(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): LibraryEndpointResponse {
            seen += endpoint
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
        val clock: ManualWallClock,
    ) {
        val outcomes = mutableListOf<PlaylistEditOutcome>()
        val fav = mutableListOf<MutationOutcome>()

        fun session(config: LibraryReaderConfig = LibraryReaderConfig(lookAheadMaxPerViewport = 0)): LibraryReaderSession =
            LibraryReaderSession(database.database, store.bind(PlaylistEnv.BINDING), hooked, scope, config, formPost = true, foreground = false).also { s ->
                s.playlists.addOutcomeListener(outcomes::add)
                s.favourites.addOutcomeListener { fav += it }
            }
    }

    private fun hooked(user: String = "listener", block: suspend TestScope.(Env) -> Unit) = runTest {
        val driver = createTestDriver()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val database = DulcetDatabaseStore.open(driver)
            val clock = ManualWallClock(now = 3_000_000)
            val server = FakePlaylistServer(user = user, now = { clock.now })
            block(Env(driver, server, Hooked(server), database, SeenCacheStore(database, clock), scope, clock))
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    private fun status(code: Int, retryAfter: String? = null) =
        LibraryEndpointResponse(code, "<html>no</html>", "http://fixture.invalid/rest", retryAfter = retryAfter)

    private fun TestScope.timed(env: Env) = env.session(LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))

    // ---- S1: this device's own creates are never candidates for each other ------------------------------

    @Test
    fun r2a_twoCreatesOfOneNameTheFirstAnswered500AndNotAppliedAreNeverMerged() = hooked { env ->
        val session = env.session()
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("New Playlist").localId)
        val b = assertNotNull(session.playlists.create("New Playlist").localId)
        session.setOnline(true)
        var creates = 0
        env.hooked.before = { endpoint, _ -> if (endpoint == "createPlaylist" && creates++ == 0) status(500) else null }
        repeat(3) { session.playlists.flush(); advanceUntilIdle() }
        val ra = session.reader.playlistOverlay.resolve(a)
        val rb = session.reader.playlistOverlay.resolve(b)
        assertTrue(ra != rb, "two creates made on this device merged into one playlist: a->$ra b->$rb ${env.outcomes}")
        assertEquals(2, env.server.playlists.size)
        assertEquals(setOf(ra, rb), env.server.playlists.map { it.id }.toSet())
    }

    @Test
    fun r2b_theSecondCreatesOwnPlaylistIsNeverACandidateForTheFirst() = hooked { env ->
        val session = env.session()
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("New Playlist").localId)
        val b = assertNotNull(session.playlists.create("New Playlist").localId)
        session.setOnline(true)
        // The first create lands and is answered 500; the second lands and is answered.
        env.server.applyThenStatus["createPlaylist"] = 500
        var creates = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist" && creates++ == 1) env.server.applyThenStatus.clear()
            null
        }
        session.playlists.flush(); advanceUntilIdle()
        val createdB = env.outcomes.filterIsInstance<PlaylistEditOutcome.Created>().single { it.localId == b }
        session.playlists.flush(); advanceUntilIdle()
        val offered = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossibleDuplicate>().filter { it.localId == a }.flatMap { it.candidates }
        assertTrue(createdB.playlistId !in offered, "the first create was offered the second create's own playlist: $offered")
        val firstsOwn = env.server.playlists.single { it.id != createdB.playlistId }.id
        assertEquals(firstsOwn, session.reader.playlistOverlay.resolve(a), "the first create adopts the playlist it made: ${env.outcomes}")
        assertEquals(2, env.server.count("createPlaylist"))
    }

    @Test
    fun anotherCreatesPlaylistStaysOutOfTheCandidatesAcrossARelaunch() = hooked { env ->
        // The record, not this session's memory, keeps it out: the relaunched editor maps nothing.
        val first = env.session()
        first.setOnline(false)
        val a = assertNotNull(first.playlists.create("New Playlist").localId)
        val b = assertNotNull(first.playlists.create("New Playlist").localId)
        first.setOnline(true)
        var creates = 0
        env.hooked.before = { endpoint, _ -> if (endpoint == "createPlaylist" && creates++ == 0) status(500) else null }
        first.playlists.flush()
        val createdB = env.outcomes.filterIsInstance<PlaylistEditOutcome.Created>().single { it.localId == b }
        // The first session can reach nothing from here on; its reconnect flush stops at once.
        env.hooked.before = { _, _ -> throw LibraryRequestFailure(DomainError.Transport.Unreachable) }
        advanceUntilIdle()
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.Created && it.localId == a }, "fixture: the first create is still in doubt")
        env.hooked.before = { _, _ -> null }
        val relaunched = env.session()
        relaunched.playlists.flush()
        advanceUntilIdle()
        val createdA = env.outcomes.filterIsInstance<PlaylistEditOutcome.Created>().single { it.localId == a }
        assertTrue(createdA.playlistId != createdB.playlistId, "adopted the other create's playlist after a relaunch: ${env.outcomes}")
        assertEquals(2, env.server.playlists.size)
    }

    @Test
    fun aPlaylistMappedToAnotherCreateHereIsNeverACandidateEvenWithoutARecord() = hooked { env ->
        val session = env.session()
        session.setOnline(false)
        val a = assertNotNull(session.playlists.create("New Playlist").localId)
        val b = assertNotNull(session.playlists.create("New Playlist").localId)
        session.setOnline(true)
        var creates = 0
        env.hooked.before = { endpoint, _ -> if (endpoint == "createPlaylist" && creates++ == 0) status(500) else null }
        session.playlists.flush()
        val createdB = env.outcomes.filterIsInstance<PlaylistEditOutcome.Created>().single { it.localId == b }
        // A row from before the pre-send record existed: only this session's mapping keeps b's out.
        // The record is stripped whether or not it has been given b's id.
        env.driver.execute(
            null,
            "UPDATE mutation_outbox SET value = replace(replace(value, ',\"seenBeforeSend\":[\"${createdB.playlistId}\"]', ''), " +
                "',\"seenBeforeSend\":[]', '') WHERE target_id = '$a'",
            0,
        )
        val stored = env.driver.executeQuery(
            identifier = null,
            sql = "SELECT value FROM mutation_outbox WHERE target_id = '$a'",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            parameters = 0,
        ).value
        assertTrue(stored != null && "seenBeforeSend" !in stored, "fixture: the record is gone: $stored")
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(
            env.outcomes.none { it is PlaylistEditOutcome.PossibleDuplicate && createdB.playlistId in it.candidates },
            "offered the other create's playlist: ${env.outcomes}",
        )
        val createdA = env.outcomes.filterIsInstance<PlaylistEditOutcome.Created>().single { it.localId == a }
        assertTrue(createdA.playlistId != createdB.playlistId)
        assertEquals(2, env.server.playlists.size)
    }

    // A fourth-round test here had a create waiting on a choice look again once another create settled
    // a playlist the choice named. The fifth round superseded it: a playlist offered to the person for
    // one create is never a candidate for another, so the other create is sent again instead
    // (PlaylistEditingFifthReviewTest.anIdOfferedToThePersonForOneCreateIsNeverACandidateForAnother).

    // ---- S2: an inferred id never produces a delete ----------------------------------------------------

    @Test
    fun r1_aDeleteMadeDuringTheAdoptionLookupDeletesNothingAndNamesThePlaylist() = hooked { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val made = env.server.playlists.single()
        var deleted = false
        env.hooked.before = { endpoint, _ ->
            // The person deletes the pending playlist while the flush reads its lone candidate.
            if (endpoint == "getPlaylist" && !deleted) {
                deleted = true
                session.playlists.delete(localId)
            }
            null
        }
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(deleted, "fixture: the delete happened during the lookup")
        assertEquals(0, env.server.count("deletePlaylist"), "a playlist identified by inference was deleted: ${env.outcomes}")
        assertEquals(listOf(made.id), env.server.playlists.map { it.id })
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id))), env.outcomes)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aDeleteDuringTheLookupWritesNothingToThePlaylistFound() = hooked { env ->
        // A create carrying a comment: that is written with a separate request after the create's own,
        // and never to a playlist found by inference once the person has deleted the create.
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1"), comment = "for the drive").localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val made = env.server.playlists.single()
        var deleted = false
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "getPlaylist" && !deleted) {
                deleted = true
                session.playlists.delete(localId)
            }
            null
        }
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(deleted, "fixture: the delete happened during the lookup")
        assertEquals(0, env.server.count("updatePlaylist") + env.server.count("deletePlaylist"), "${env.outcomes}")
        assertEquals(null, made.comment)
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id))), env.outcomes)
    }

    @Test
    fun aDeleteDuringTheLookupAfterAnEmptyOkDeletesNothing() = hooked { env ->
        // The server answers the create with an empty `ok`, naming nothing: the playlist it made is
        // found by looking, never by an answer, so a delete made meanwhile names it and deletes nothing.
        val session = env.session()
        env.server.createAnswersWithPlaylist = false
        var deleted = false
        lateinit var localId: String
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "getPlaylist" && !deleted) {
                deleted = true
                session.playlists.delete(localId)
            }
            null
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(deleted, "fixture: the delete happened during the lookup")
        assertEquals(0, env.server.count("deletePlaylist"), "a playlist found by looking was deleted: ${env.outcomes}")
        val made = env.server.playlists.single()
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id))), env.outcomes)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aDeleteOfACreateWhoseAnswerNamedItsIdStillDeletesIt() = hooked { env ->
        // The control for r1: an id the server ANSWERED with is certain, and a delete made while the
        // create is in flight follows it as an ordinary delete by id.
        val session = env.session()
        var deleted = false
        lateinit var localId: String
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist" && !deleted) {
                deleted = true
                session.playlists.delete(localId)
            }
            null
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(deleted, "fixture: the delete happened while the create was in flight")
        assertEquals(1, env.server.count("deletePlaylist"))
        assertTrue(env.server.playlists.isEmpty())
    }

    // ---- S3: an owner never excludes a candidate --------------------------------------------------------

    @Test
    fun r11_anOwnerStatedInAnotherFormIsNamedNeverExcludedAndThePersonCanChooseIt() = hooked(user = "Listener Display Name") { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val made = env.server.playlists.single()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"), "sent again beside its own playlist: ${env.outcomes}")
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossibleDuplicate(localId, "Road", listOf(made.id))), env.outcomes)
        // Named, never adopted on its own; the person says it is theirs.
        assertEquals(PlaylistEditRecord.Pending, session.playlists.chooseCreated(localId, made.id))
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(PlaylistEditOutcome.Created(localId, made.id), env.outcomes.last())
        assertEquals(1, env.server.playlists.size)
    }

    // ---- S4: every listed id is recorded, not only the namesakes ----------------------------------------

    @Test
    fun r4a_anOldPlaylistRenamedIntoTheNameIsNeverOfferedForDeletion() = hooked { env ->
        val old = env.server.add("Road trip 2019", listOf("song-7", "song-8"), created = env.clock.now - 3L * 365 * 86_400_000)
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.failWithError.clear()
        old.name = "Road" // renamed into the name elsewhere, while the create is in doubt
        session.playlists.delete(localId)
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(
            env.outcomes.none { it is PlaylistEditOutcome.PossiblyCreated && old.id in it.candidates },
            "an old playlist, listed before the send, offered for deletion: ${env.outcomes}",
        )
        assertEquals(0, env.server.count("deletePlaylist"))
    }

    @Test
    fun r4b_anOldEmptyPlaylistRenamedIntoTheNameIsNotAdopted() = hooked { env ->
        val old = env.server.add("Draft", emptyList(), created = env.clock.now - 3L * 365 * 86_400_000)
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        val localId = assertNotNull(session.playlists.create("New Playlist").localId)
        advanceUntilIdle()
        env.server.failWithError.clear()
        old.name = "New Playlist"
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.Created && it.playlistId == old.id }, "adopted a playlist made years before the send: ${env.outcomes}")
        val made = env.server.playlists.single { it.id != old.id }
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.Created(localId, made.id)), env.outcomes)
    }

    // ---- S5: the stated residual — a create the server commits only after the next listing ---------------

    @Test
    fun r3_aTimedOutCreateTheServerCommitsAfterTheNextListingIsSentAgainTheStatedResidual() = hooked { env ->
        val session = env.session()
        var first = true
        var late: (suspend () -> Unit)? = null
        env.hooked.before = { endpoint, parameters ->
            when {
                endpoint == "createPlaylist" && first -> {
                    first = false
                    // The request reached the server, which is still working on it when the client times out...
                    late = { env.server.requestRepeated(endpoint, parameters, true) }
                    throw LibraryRequestFailure(DomainError.Transport.Timeout)
                }
                endpoint == "createPlaylist" && late != null -> {
                    // ...and commits it only after the next flush's listing found nothing.
                    late!!.invoke()
                    late = null
                    null
                }
                else -> null
            }
        }
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        session.playlists.flush()
        advanceUntilIdle()
        // §18.6 states this residual: nothing of that name was listed, so the create was sent again,
        // and the late commit is a second playlist nobody names.
        assertEquals(2, env.server.playlists.size)
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.Created(localId, env.server.playlists.last().id)), env.outcomes)
    }

    // ---- S6: a run of 429s ends when a flush meets none, or the queue empties — not on one delivery ----

    @Test
    fun r5_aSteadyLimiterIsOneRunTheFloorDoublesAndHeldIsToldOnce() = hooked { env ->
        val session = timed(env)
        session.setOnline(false)
        repeat(8) { session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-${it + 1}"), true) }
        session.setOnline(true)
        var stars = 0
        // A limiter that admits one request per window: every second request is a 429, Retry-After 5.
        env.hooked.before = { endpoint, _ -> if (endpoint == "star" && stars++ % 2 == 1) status(429, "5") else null }
        session.favourites.flush()
        runCurrent()
        assertEquals(2, stars)
        // The waits, in one run: 5 s (Retry-After over the 2 s floor), 5 s (over 4 s), then the floor
        // itself — 8, 16, 32 s. Retries at 5, 10, 18 and 34 s, two requests each: 10 by the minute.
        advanceTimeBy(60_000); runCurrent()
        assertEquals(10, stars, "the floor doubled across the run")
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(15, stars, "eight delivered, seven refused")
        assertEquals(0L, session.favourites.pendingCount())
        assertEquals(1, env.fav.count { it is MutationOutcome.Held }, "one run of 429s is told once: ${env.fav}")
    }

    @Test
    fun aRunEndsWhenAFlushMeetsNo429AndTheNextIsToldAgainFromTheFloor() = hooked { env ->
        val session = timed(env)
        session.setOnline(false)
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        session.setOnline(true)
        var limited = true
        var stars = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star") stars++
            if (endpoint == "star" && limited) status(429) else null
        }
        session.favourites.flush()
        runCurrent()
        limited = false
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0L, session.favourites.pendingCount(), "delivered after the two-second floor")
        assertEquals(2, stars)
        // That flush met no 429: the run is over. A new 429 is a new run — told, and from 2 s.
        limited = true
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-2"), true)
        runCurrent()
        assertEquals(3, stars)
        assertEquals(2, env.fav.count { it is MutationOutcome.Held }, "${env.fav}")
        limited = false
        advanceTimeBy(1_999); runCurrent()
        assertEquals(3, stars, "the new run waits the floor again")
        advanceTimeBy(1); runCurrent()
        assertEquals(4, stars)
        assertEquals(0L, session.favourites.pendingCount())
    }

    // ---- S7: a new 429 never shortens a wait ------------------------------------------------------------

    @Test
    fun r6_aConcurrent429NeverShortensTheServersRetryAfterAndEachOutboxIsTold() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = timed(env)
        val detail = session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var limited = true
        var stars = 0
        var updates = 0
        env.hooked.before = { endpoint, _ ->
            yield() // a real request is out for a while: the other outbox's flush runs meanwhile
            when (endpoint) {
                "star" -> { stars++; if (limited) status(429, "60") else null }
                "updatePlaylist" -> { updates++; if (limited) status(429, null) else null }
                else -> null
            }
        }
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        session.playlists.rename(p.id, "Evening")
        runCurrent()
        assertEquals(1 to 1, stars to updates, "fixture: both flushes met their 429")
        limited = false
        advanceTimeBy(59_999); runCurrent()
        assertEquals(1 to 1, stars to updates, "sent again inside the server's Retry-After: 60")
        advanceTimeBy(1); runCurrent()
        assertEquals(2 to 2, stars to updates)
        assertEquals("Evening", p.name)
        assertEquals(1, env.fav.count { it is MutationOutcome.Held })
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.Held }, "each outbox is told of its own run: ${env.outcomes}")
        detail.close()
    }

    // ---- S8: a withdrawn change is no longer shown --------------------------------------------------------

    @Test
    fun r7_aWithdrawnHeldRenameIsNoLongerShown() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val shown = mutableListOf<LibraryPublication>()
        val handle = session.reader.open(LibraryQuery.Playlists) { shown += it }
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 403
        env.server.httpStatus["ping"] = 403
        session.playlists.flush()
        assertEquals("Evening", shown.last().items.filterIsInstance<LibraryItem.Playlist>().single().name, "fixture: shown while held")
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.withdraw(p.id, PlaylistRowKind.Details))
        val after = shown.last().items.filterIsInstance<LibraryItem.Playlist>().single()
        handle.close()
        assertEquals("Mix", after.name)
        assertTrue(!after.pendingChanges)
    }

    @Test
    fun r7b_aWithdrawnHeldFavouriteIsNoLongerShown() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val shown = mutableListOf<LibraryPublication>()
        val handle = session.reader.open(LibraryQuery.Playlist(p.id)) { shown += it }
        advanceUntilIdle()
        session.setOnline(false)
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        session.setOnline(true)
        env.hooked.before = { endpoint, _ -> if (endpoint == "star" || endpoint == "ping") status(403) else null }
        session.favourites.flush()
        fun starred() = shown.last().items.filterIsInstance<LibraryItem.Track>().single().starred
        assertEquals(true, starred(), "fixture: the pending star is shown while held")
        assertEquals(MutationRecord.CompactedAway, session.favourites.withdraw(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), MutationField.Starred))
        val after = starred()
        handle.close()
        assertTrue(after != true, "the withdrawn star is still shown")
    }

    // ---- NIT r12: a change withdrawn while its send is out is AlreadySent, and the display follows the server

    @Test
    fun r12_aRenameWithdrawnWhileItsSendIsOutIsAlreadySentAndTheServersAnswerIsShown() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val shown = mutableListOf<LibraryPublication>()
        val handle = session.reader.open(LibraryQuery.Playlists) { shown += it }
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        var record: PlaylistEditRecord? = null
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "updatePlaylist" && record == null) record = session.playlists.withdraw(p.id, PlaylistRowKind.Details)
            null
        }
        session.playlists.flush()
        advanceUntilIdle()
        handle.close()
        assertEquals(PlaylistEditRecord.AlreadySent, record, "too late to undo, and said so")
        assertEquals("Evening", env.server.playlists.single().name, "fixture: the send went out")
        assertEquals("Evening", shown.last().items.filterIsInstance<LibraryItem.Playlist>().single().name, "the display follows the server's answer")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun r12b_aFavouriteWithdrawnWhileItsSendIsOutIsAlreadySentAndTheServersAnswerIsShown() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val shown = mutableListOf<LibraryPublication>()
        val handle = session.reader.open(LibraryQuery.Playlist(p.id)) { shown += it }
        advanceUntilIdle()
        val track = LibraryEntityRef(LibraryEntityKind.Track, "song-1")
        session.setOnline(false)
        session.favourites.setFavourite(track, true)
        session.setOnline(true)
        var record: MutationRecord? = null
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star" && record == null) record = session.favourites.withdraw(track, MutationField.Starred)
            null
        }
        session.favourites.flush()
        advanceUntilIdle()
        handle.close()
        assertEquals(MutationRecord.AlreadySent, record, "too late to undo, and said so")
        assertEquals(1, env.server.count("star"), "fixture: the send went out")
        assertEquals(true, shown.last().items.filterIsInstance<LibraryItem.Track>().single().starred, "the display follows the server's answer")
        assertEquals(0L, session.favourites.pendingCount())
    }

    @Test
    fun aChangeNeverSentIsStillCompactedAwayWhenWithdrawn() = hooked { env ->
        // The control for r12: nothing went out, so the withdraw is a clean undo.
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.withdraw(p.id, PlaylistRowKind.Details))
        assertEquals(MutationRecord.CompactedAway, session.favourites.withdraw(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), MutationField.Starred))
        session.setOnline(true)
        session.playlists.flush()
        session.favourites.flush()
        advanceUntilIdle()
        assertEquals(0, env.server.count("updatePlaylist") + env.server.count("star"))
    }

    // ---- NIT r10a: "holds the songs sent" — a subset, in order, is adopted and the difference told ------

    @Test
    fun r10a_aCandidateHoldingSomeOfTheSongsSentInOrderIsAdoptedAndTheDifferenceIsTold() = hooked { env ->
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout // never arrived
        val songs = (1..5).map { "song-$it" }
        val localId = assertNotNull(session.playlists.create("Road", songs).localId)
        advanceUntilIdle()
        env.server.failWithError.clear()
        val elsewhere = env.server.add("Road", listOf("song-1", "song-3"))
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(
            listOf(
                PlaylistEditOutcome.Created(localId, elsewhere.id),
                PlaylistEditOutcome.Diverged(elsewhere.id, PlaylistRowKind.Create, listOf("song-1", "song-3"), songs),
            ),
            env.outcomes,
        )
    }

    @Test
    fun aCandidateHoldingTheSongsSentInAnotherOrderIsNamedNotAdopted() = hooked { env ->
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        val songs = (1..5).map { "song-$it" }
        val localId = assertNotNull(session.playlists.create("Road", songs).localId)
        advanceUntilIdle()
        env.server.failWithError.clear()
        val elsewhere = env.server.add("Road", listOf("song-3", "song-1"))
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossibleDuplicate(localId, "Road", listOf(elsewhere.id))), env.outcomes)
        assertEquals(1, env.server.count("createPlaylist"), "sent again on a guess")
    }

    @Test
    fun r10b_aCandidateTheServerListsWithNoEntriesIsNamedNotAdopted() = hooked { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1", "song-2")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val made = env.server.playlists.single()
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "getPlaylist") {
                LibraryEndpointResponse(
                    200,
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","playlist":{"id":"${made.id}","name":"Road","owner":"listener","songCount":2}}}""",
                    "http://fixture.invalid/rest",
                )
            } else {
                null
            }
        }
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"), "sent again on a guess")
        assertEquals(PlaylistEditOutcome.PossibleDuplicate(localId, "Road", listOf(made.id)), env.outcomes.last())
    }

    // ---- 11: origin errors of a CDN in front of the server stop the flush like 502–504 --------------------

    @Test
    fun anOriginErrorStopsThePlaylistFlushAndCountsNothing() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        for (code in listOf(520, 521, 522, 523, 524, 530)) {
            session.setOnline(false)
            session.playlists.rename(p.id, "Evening $code")
            session.setOnline(true)
            env.server.httpStatus["updatePlaylist"] = code
            repeat(PlaylistEditor.MAX_FAILURES + 1) {
                assertEquals<DomainError?>(DomainError.Server.HttpStatus(code), session.playlists.flush().stoppedBy, "$code stops the flush")
            }
            assertEquals(1L, session.playlists.pendingCount(), "$code counted toward a drop: ${env.outcomes}")
            env.server.httpStatus.clear()
            session.playlists.flush()
            assertEquals("Evening $code", p.name)
        }
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.NotSaved || it is PlaylistEditOutcome.Held }, "${env.outcomes}")
    }

    @Test
    fun anOriginErrorStopsTheFavouritesFlushAndCountsNothing() = hooked { env ->
        val session = env.session()
        val track = LibraryEntityRef(LibraryEntityKind.Track, "song-1")
        listOf(520, 521, 522, 523, 524, 530).forEachIndexed { index, code ->
            session.setOnline(false)
            // Starred and unstarred in turn, so each is a change against what the server now holds.
            session.favourites.setFavourite(track, index % 2 == 0)
            session.setOnline(true)
            env.hooked.before = { endpoint, _ -> if (endpoint == "star" || endpoint == "unstar") status(code) else null }
            repeat(LibraryFavourites.MAX_FAILURES + 1) {
                assertEquals<DomainError?>(DomainError.Server.HttpStatus(code), session.favourites.flush().stoppedBy, "$code stops the flush")
            }
            assertEquals(1L, session.favourites.pendingCount(), "$code counted toward a drop: ${env.fav}")
            env.hooked.before = { _, _ -> null }
            session.favourites.flush()
            assertEquals(0L, session.favourites.pendingCount())
        }
        assertTrue(env.fav.none { it is MutationOutcome.NotSaved || it is MutationOutcome.Held }, "${env.fav}")
    }

    @Test
    fun aCreateAnsweredWithAnOriginErrorAfterLandingIsSettledByThePreSendSet() = hooked { env ->
        val session = env.session()
        env.server.applyThenStatus["createPlaylist"] = 522
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        assertEquals<DomainError?>(DomainError.Server.HttpStatus(522), session.playlists.flush().stoppedBy)
        env.server.applyThenStatus.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.Created(localId, env.server.playlists.single().id)), env.outcomes)
    }

    // ---- More of S2 and S6: the rules' other edges ------------------------------------------------------

    @Test
    fun aDeleteWhileTheFoundPlaylistIsReadIsNamedNotDeleted() = hooked { env ->
        // The delete lands after the lookup, while the playlist it found is read into the cache: the
        // settling of the create sees it, and an id found without the server's answer is still not deleted.
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val made = env.server.playlists.single()
        var reads = 0
        env.hooked.before = { endpoint, _ ->
            // The first read of it is the lookup; the second is the read that follows its adoption.
            if (endpoint == "getPlaylist" && ++reads == 2) session.playlists.delete(localId)
            null
        }
        session.playlists.flush()
        advanceUntilIdle()
        assertTrue(reads >= 2, "fixture: the delete happened during the second read ($reads)")
        assertEquals(0, env.server.count("deletePlaylist"), "a playlist identified by inference was deleted: ${env.outcomes}")
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id))), env.outcomes)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun r5b_aSteadyLimiterIsOneRunForPlaylistsToo() = hooked { env ->
        val lists = (1..4).map { env.server.add("List $it", listOf("song-1")) }
        val session = timed(env)
        lists.forEach { session.reader.open(LibraryQuery.Playlist(it.id)) { } }
        advanceUntilIdle()
        session.setOnline(false)
        lists.forEach { session.playlists.rename(it.id, "${it.name} renamed") }
        session.setOnline(true)
        var updates = 0
        env.hooked.before = { endpoint, _ -> if (endpoint == "updatePlaylist" && updates++ % 2 == 1) status(429, "5") else null }
        session.playlists.flush()
        runCurrent()
        assertEquals(2, updates)
        // Waits of 5 s (over the 2 s floor), 5 s (over 4 s), then the floor itself, 8 s: retries at
        // 5, 10 and 18 s. One delivery each time, and the run does not end on it.
        advanceTimeBy(17_999); runCurrent()
        assertEquals(6, updates, "the floor doubled across the run")
        advanceTimeBy(1); runCurrent()
        assertEquals(7, updates, "four delivered, three refused")
        assertEquals((1..4).map { "List $it renamed" }, lists.map { it.name })
        assertEquals(0L, session.playlists.pendingCount())
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.Held }, "one run of 429s is told once: ${env.outcomes}")
    }

    @Test
    fun aFlushStoppedByTheWaitDoesNotEndTheRun() = hooked { env ->
        val session = timed(env)
        session.setOnline(false)
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        session.setOnline(true)
        var limited = true
        var stars = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star") stars++
            if (endpoint == "star" && limited) status(429, "5") else null
        }
        session.favourites.flush()
        runCurrent()
        assertEquals(1, stars)
        // A change made during the wait starts a flush the wait stops: it sends nothing, and ends nothing.
        advanceTimeBy(1_000)
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-2"), true)
        runCurrent()
        assertEquals(1, stars, "sent early")
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, stars)
        assertEquals(1, env.fav.count { it is MutationOutcome.Held }, "the same run told twice: ${env.fav}")
        limited = false
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0L, session.favourites.pendingCount())
    }

    @Test
    fun aChangeUndoneDuringTheWaitEmptiesTheQueueAndEndsTheRun() = hooked { env ->
        val session = timed(env)
        val song1 = LibraryEntityRef(LibraryEntityKind.Track, "song-1")
        val p = env.server.add("Mix", listOf("song-1"))
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var stars = 0
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star") stars++
            if (endpoint == "star") status(429, "5") else null
        }
        session.favourites.setFavourite(song1, true)
        runCurrent()
        assertEquals(1, env.fav.count { it is MutationOutcome.Held })
        // Undone offline during the wait, so no flush runs: the undo alone empties the queue, and
        // ends the run.
        advanceTimeBy(1_000)
        session.setOnline(false)
        assertEquals(MutationRecord.CompactedAway, session.favourites.setFavourite(song1, false))
        session.setOnline(true)
        // A new change before the wait ends: its flush is stopped by the wait, and its 429 at the
        // end of the wait is a new run, told.
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-2"), true)
        runCurrent()
        assertEquals(1, stars, "sent early")
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, stars)
        assertEquals(2, env.fav.count { it is MutationOutcome.Held }, "${env.fav}")
    }

    @Test
    fun aWithdrawalThatEmptiesTheQueueEndsTheRun() = hooked { env ->
        val session = timed(env)
        val song1 = LibraryEntityRef(LibraryEntityKind.Track, "song-1")
        var stars = 0
        var limited = true
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "star") stars++
            if (endpoint == "star" && limited) status(429, "5") else null
        }
        session.favourites.setFavourite(song1, true)
        runCurrent()
        assertEquals(1, env.fav.count { it is MutationOutcome.Held })
        // Withdrawn during the wait (the 429 proved it never applied, so nothing went out), then a
        // new change before the wait ends: its 429 is a new run.
        advanceTimeBy(1_000)
        assertEquals(MutationRecord.CompactedAway, session.favourites.withdraw(song1, MutationField.Starred))
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-2"), true)
        runCurrent()
        assertEquals(1, stars, "sent early")
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, stars)
        assertEquals(2, env.fav.count { it is MutationOutcome.Held }, "${env.fav}")
        limited = false
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0L, session.favourites.pendingCount())
    }

    @Test
    fun aPlaylistWithdrawalOfflineThatEmptiesTheQueueEndsTheRun() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = timed(env)
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        session.reader.open(LibraryQuery.Playlist(q.id)) { }
        advanceUntilIdle()
        var updates = 0
        var limited = true
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "updatePlaylist") updates++
            if (endpoint == "updatePlaylist" && limited) status(429, "5") else null
        }
        session.playlists.rename(p.id, "Evening")
        runCurrent()
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.Held })
        // Offline, so no flush runs: the withdrawal alone empties the queue, and ends the run.
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.withdraw(p.id, PlaylistRowKind.Details))
        session.playlists.rename(q.id, "Morning")
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, updates, "fixture: nothing is sent offline, the retry included")
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertEquals(2, updates, "fixture: the flush on reconnecting sent the new change")
        assertEquals(2, env.outcomes.count { it is PlaylistEditOutcome.Held }, "a new run, told: ${env.outcomes}")
        limited = false
        advanceTimeBy(5_000); runCurrent()
        assertEquals("Morning", q.name)
        assertEquals("Mix", p.name)
    }

    @Test
    fun aRunEndsWhenItsLastChangeIsFoundAlreadyAppliedAndNothingIsSent() = hooked { env ->
        // The queue can empty with nothing sent: a read issued during the wait shows the server
        // already holds the change, so the flush at the end of the wait drops it, sending nothing and
        // meeting no 429. Nothing is left, so the run is over, and the next 429 is a new run, told.
        val p = env.server.add("Mix", listOf("song-1"))
        val session = timed(env)
        val detail = session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        var stars = 0
        var starredOnServer = false
        env.hooked.before = { endpoint, parameters ->
            when {
                endpoint == "star" -> {
                    stars++
                    status(429, "5")
                }
                endpoint == "getPlaylist" && starredOnServer -> env.server.request(endpoint, parameters.toMap()).let {
                    it.copy(body = it.body.replace("\"id\":\"song-1\",", "\"id\":\"song-1\",\"starred\":\"2026-09-24T10:00:00Z\","))
                }
                else -> null
            }
        }
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        runCurrent()
        assertEquals(1, env.fav.count { it is MutationOutcome.Held })
        advanceTimeBy(1_000)
        starredOnServer = true
        detail.refresh()
        runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertEquals(1, stars, "fixture: the change left the queue without being sent again")
        assertEquals(0L, session.favourites.pendingCount(), "fixture: the change was found already applied")
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-2"), true)
        runCurrent()
        assertEquals(2, stars)
        assertEquals(2, env.fav.count { it is MutationOutcome.Held }, "a new run, told: ${env.fav}")
    }

    // ---- The reviewer's other probes, kept: each held on the round before and holds now ------------------

    @Test
    fun r8_aWrongPasswordHoldsBothOutboxesAcrossFlushes() = hooked { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        session.reader.open(LibraryQuery.Playlist(p.id)) { }
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        session.setOnline(true)
        env.server.failWithCode["updatePlaylist"] = 40
        env.server.failWithCode["star"] = 40
        env.server.failWithCode["ping"] = 40
        repeat(PlaylistEditor.MAX_FAILURES + 2) { session.playlists.flush(); session.favourites.flush(); advanceUntilIdle() }
        assertEquals(1L, session.playlists.pendingCount())
        assertEquals(1L, session.favourites.pendingCount())
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.NotSaved } && env.fav.none { it is MutationOutcome.NotSaved })
    }

    @Test
    fun r9a_aKillBetweenMarkAndSendThenRelaunchSendsOnceAndAdoptsNothingOld() = hooked { env ->
        val older = env.server.add("Road", listOf("song-1"))
        val first = env.session()
        var kill = true
        env.hooked.before = { endpoint, _ ->
            if (endpoint == "createPlaylist" && kill) {
                kill = false
                throw CancellationException("the app is killed before the request leaves")
            }
            null
        }
        first.setOnline(false)
        val localId = assertNotNull(first.playlists.create("Road", listOf("song-1")).localId)
        first.setOnline(true)
        try { first.playlists.flush() } catch (_: CancellationException) {}
        advanceUntilIdle()
        assertEquals(0, env.server.count("createPlaylist"), "fixture: killed before the send")
        val relaunched = env.session()
        relaunched.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"))
        val created = env.outcomes.filterIsInstance<PlaylistEditOutcome.Created>().single()
        assertTrue(created.playlistId != older.id && created.localId == localId)
    }

    @Test
    fun r9b_aListingThatFailsNeverLetsTheCreateOutWithoutItsSet() = hooked { env ->
        val session = env.session()
        env.hooked.before = { endpoint, _ -> if (endpoint == "getPlaylists") status(500) else null }
        session.setOnline(false)
        session.playlists.create("Road", listOf("song-1"))
        session.setOnline(true)
        repeat(4) { session.playlists.flush(); advanceUntilIdle() }
        assertEquals(0, env.hooked.seen.count { it == "createPlaylist" })
    }
}
