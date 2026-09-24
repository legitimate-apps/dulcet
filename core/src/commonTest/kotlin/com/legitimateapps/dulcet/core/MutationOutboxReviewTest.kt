package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests from the independent review of the first R1c/R1d cut: a1–a5 are the reviewer's
 * attack tests, adapted; the rest pin S1, S2, S5 and S6 and the nits. Each names what it guards.
 */
class MutationOutboxReviewTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val album4 = LibraryEntityRef(LibraryEntityKind.Album, albumId(4))
    private val artist4 = LibraryEntityRef(LibraryEntityKind.Artist, albumId(4)) // same opaque id, other kind

    private fun sends(env: SessionEnv) = env.server.log.filter { it.endpoint in setOf("star", "unstar", "setRating") }

    private suspend fun TestScope.online(env: SessionEnv, session: LibraryReaderSession = env.session()): Pair<LibraryReaderSession, Recorder<LibraryPublication>> {
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        return session to pubs
    }

    // ---- B1: the kind is part of the key ------------------------------------------------------------

    @Test
    fun a1_anArtistsStarNeverShowsOnTheAlbumWithTheSameId() = sessionTest { env ->
        val (session, pubs) = online(env)
        session.setOnline(false)
        session.favourites.setFavourite(artist4, true)
        assertEquals(false, pubs.last.album(albumId(4)).starred, "artist star must not show on the album")
        assertEquals(true, session.favourites.isFavourite(artist4))
        assertEquals(false, session.favourites.isFavourite(album4))
    }

    @Test
    fun a2_starringAnArtistAndAnAlbumWithTheSameIdQueuesBothAndSendsBoth() = sessionTest { env ->
        val (session, _) = online(env)
        session.setOnline(false)
        assertEquals(MutationRecord.Pending, session.favourites.setFavourite(artist4, true))
        assertEquals(MutationRecord.Pending, session.favourites.setFavourite(album4, true))
        assertEquals(2L, session.favourites.pendingCount())
        session.setOnline(true)
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(
            listOf(mapOf("artistId" to albumId(4)), mapOf("albumId" to albumId(4))),
            sends(env).map { it.parameters },
        )
    }

    @Test
    fun searchRowsTakeTheOverlayOfTheirOwnKindOnly() = sessionTest { env ->
        val (session, _) = online(env)
        session.setOnline(false)
        session.favourites.setFavourite(artist4, true)
        val rows = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = rows).updateQuery("Album 0004")
        assertEquals(false, rows.last.rows.single().favourite)
    }

    @Test
    fun anArtistAndAnAlbumWithTheSameIdInOneResultListEachShowTheirOwnStar() = sessionTest { env ->
        val (session, _) = online(env)
        val cache = env.cache()
        cache.writeEntities(CacheWriteStamp(cache.issue(), 1, null), CacheEntitySource.Search,
            CacheEntities(artists = listOf(CacheArtistRecord(albumId(4), "Album 0004 Players"))))
        session.setOnline(false)
        session.favourites.setFavourite(artist4, true)
        val rows = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = rows).updateQuery("Album 0004")
        val byType = rows.last.rows.associate { it.item.type to it.favourite }
        assertEquals(setOf(SearchResultType.Artist, SearchResultType.Album), byType.keys, "fixture: both kinds, one id")
        assertEquals(true, byType[SearchResultType.Artist])
        assertEquals(false, byType[SearchResultType.Album])
    }

    @Test
    fun noEntryPointThrowsWhenTheDatabaseFails() = sessionTest { env ->
        val driver = createTestDriver()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store = DulcetDatabaseStore.open(driver)
            val session = LibraryReaderSession(
                store.database, SeenCacheStore(store, ManualWallClock()).bind(SessionEnv.BINDING), env.server, scope,
                formPost = false,
            )
            val outcomes = mutableListOf<MutationOutcome>()
            session.favourites.addOutcomeListener { outcomes += it }
            driver.close() // every statement now fails
            assertEquals(MutationRecord.NotRecorded, session.favourites.setFavourite(album4, true))
            assertEquals(MutationRecord.NotRecorded, session.favourites.setRating(album4, 3))
            assertEquals(false, session.favourites.toggleFavourite(album4), "a toggle that was not recorded reports the old state")
            assertEquals(null, session.favourites.isFavourite(album4))
            assertEquals(0L, session.favourites.pendingCount())
            assertEquals(MutationRecord.Invalid, session.favourites.setRating(album4, 9))
            assertTrue(outcomes.isNotEmpty() && outcomes.all { it is MutationOutcome.NotRecorded })
            session.openSearch { }.updateQuery("Album") // must not throw either
        } finally {
            scope.cancel()
        }
    }

    // ---- S4: confinement ----------------------------------------------------------------------------

    @Test
    fun everyFavouritesAndSearchEntryPointRefusesACallerOffTheReadersThread() = sessionTest { env ->
        val (session, _) = online(env)
        val search = session.openSearch { }
        val favourites = session.favourites
        val calls: List<Pair<String, suspend () -> Unit>> = listOf(
            "setFavourite" to { favourites.setFavourite(album4, true) },
            "toggleFavourite" to { favourites.toggleFavourite(album4) },
            "setRating" to { favourites.setRating(album4, 3) },
            "isFavourite" to { favourites.isFavourite(album4) },
            "rating" to { favourites.rating(album4) },
            "pendingCount" to { favourites.pendingCount() },
            "flush" to { favourites.flush() },
            "addOutcomeListener" to { favourites.addOutcomeListener { } },
            "addChangeListener" to { favourites.addChangeListener { } },
            "openSearch" to { session.openSearch { } },
            "session.setOnline" to { session.setOnline(false) },
            "updateQuery" to { search.updateQuery("Al") },
            "refresh" to { search.refresh() },
            "republishPendingChanges" to { search.republishPendingChanges(setOf(albumId(4))) },
            "close" to { search.close() },
        )
        val refused = mutableListOf<String>()
        for ((name, call) in calls) {
            val thrown = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { call() }.exceptionOrNull()
            }
            if (thrown is IllegalStateException) refused += name
        }
        assertEquals(calls.map { it.first }, refused)
        assertEquals(0L, favourites.pendingCount(), "nothing a refused call did reached the outbox")
        // The positive control: the same calls on the reader's own thread are accepted.
        assertEquals(MutationRecord.Pending, favourites.setFavourite(album4, true))
        search.updateQuery("Al")
    }

    // ---- S3 -----------------------------------------------------------------------------------------

    @Test
    fun a3_anUnstarTappedWhileTheStarIsInFlightReachesTheServer() = sessionTest { env ->
        val (session, _) = online(env)
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        env.server.holdBeforeApply += "star"
        session.favourites.setFavourite(album4, true)
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount, "fixture: star in flight")
        assertEquals(MutationRecord.Pending, session.favourites.setFavourite(album4, false),
            "the star may land, so the unstar must be kept and sent")
        env.server.holdBeforeApply.clear()
        env.server.release()
        advanceUntilIdle()
        assertTrue(outcomes.none { it is MutationOutcome.Superseded }, "outcomes=$outcomes")
        assertEquals(listOf("star", "unstar"), sends(env).map { it.endpoint })
        assertEquals(false, env.server.base.albums[4].starred, "the person's last value wins")
        assertEquals(0L, session.favourites.pendingCount())
    }

    // ---- S1 -----------------------------------------------------------------------------------------

    @Test
    fun a4_aCrashBetweenBindAndSessionCannotSendTheOldAccountsChanges() = sessionTest { env ->
        val first = env.session()
        first.setOnline(false)
        first.favourites.setFavourite(album4, true)
        val other = CacheBinding(SessionEnv.BINDING.serverId, "https://music.example", "someone-else")
        val bound = env.store.bind(other) // the process dies here: no outbox is ever built over this bind
        assertEquals(1L, bound.discardedPendingChanges, "discarded in the bind's own transaction, and counted")
        val relaunched = env.session(binding = other)
        assertEquals(0L, relaunched.favourites.pendingCount())
        relaunched.reader.connect()
        relaunched.reader.reconnect()
        advanceUntilIdle()
        assertEquals(emptyList(), sends(env))
    }

    // ---- S2 -----------------------------------------------------------------------------------------

    @Test
    fun aSendThatNeverReachedTheServerDoesNotShieldTheChangeFromANewerServerValue() = sessionTest { env ->
        env.server.ratings[albumId(4)] = 3
        val (session, pubs) = online(env)
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        env.server.failWithError["setRating"] = DomainError.Transport.Unreachable
        session.favourites.setRating(album4, 4)
        advanceUntilIdle()
        assertEquals(1, env.server.count("setRating"), "fixture: the send was tried and never arrived")
        session.setOnline(false)
        session.favourites.setRating(album4, 5)
        env.server.failWithError.clear()
        env.server.ratings[albumId(4)] = 4 // another client, after this device's change
        session.setOnline(true)
        session.reader.open(grid) {}.also { it.refresh() } // a read issued after the change
        advanceUntilIdle()
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(1, env.server.count("setRating"), "4 was never delivered by this device, so 4 is another client's")
        assertEquals(listOf<MutationOutcome>(MutationOutcome.Superseded(album4, MutationField.Rating, 4)), outcomes)
        assertEquals(4, env.server.ratings[albumId(4)])
        assertEquals(4, pubs.last.album(albumId(4)).userRating)
    }

    // ---- S5 -----------------------------------------------------------------------------------------

    @Test
    fun oneFailingChangeNeitherBlocksTheQueueNorStaysSilent() = sessionTest { env ->
        val (session, _) = online(env)
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        env.server.failWithCode["star"] = 0 // the server's generic, busy-style answer: this change only
        session.setOnline(false)
        session.favourites.setFavourite(album4, true)
        val track = LibraryEntityRef(LibraryEntityKind.Track, "album-0004-track-0")
        session.favourites.setRating(track, 4)
        session.setOnline(true)
        val first = session.favourites.flush()
        assertEquals(listOf("star", "setRating"), sends(env).map { it.endpoint }, "the later change is sent past the failing one")
        assertEquals(1, first.deferred)
        assertEquals(1L, session.favourites.pendingCount())
        repeat(LibraryFavourites.MAX_FAILURES - 1) { session.favourites.flush() }
        assertEquals(LibraryFavourites.MAX_FAILURES, env.server.count("star"), "retries are capped")
        val refused = assertIs<MutationOutcome.NotSaved>(outcomes.last())
        assertEquals(album4, refused.target)
        assertEquals(0L, session.favourites.pendingCount())
        session.favourites.flush()
        assertEquals(LibraryFavourites.MAX_FAILURES, env.server.count("star"))
    }

    @Test
    fun anUnreachableServerStopsTheFlushAndKeepsOrder() = sessionTest { env ->
        val (session, _) = online(env)
        env.server.failWithError["star"] = DomainError.Transport.Unreachable
        session.setOnline(false)
        session.favourites.setFavourite(album4, true)
        session.favourites.setRating(album4, 2)
        session.setOnline(true)
        val report = session.favourites.flush()
        assertEquals(DomainError.Transport.Unreachable, report.stoppedBy)
        assertEquals(listOf("star"), sends(env).map { it.endpoint }, "nothing is sent past an unreachable server")
        assertEquals(2L, session.favourites.pendingCount())
    }

    // ---- S6 -----------------------------------------------------------------------------------------

    @Test
    fun forbiddenIsARefusalNotARetry() = sessionTest { env ->
        val (session, pubs) = online(env)
        env.server.failWithCode["star"] = 50
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        session.favourites.setFavourite(album4, true)
        advanceUntilIdle()
        assertEquals(MutationOutcome.NotSaved(album4, MutationField.Starred, DomainError.Auth.Forbidden), outcomes.single())
        assertEquals(0L, session.favourites.pendingCount())
        assertEquals(false, pubs.last.album(albumId(4)).starred)
        session.favourites.flush()
        assertEquals(1, env.server.count("star"))
    }

    @Test
    fun anUnknownServerCodeIsARefusal() = sessionTest { env ->
        val (session, _) = online(env)
        env.server.failWithCode["setRating"] = 99
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        session.favourites.setRating(album4, 3)
        advanceUntilIdle()
        assertEquals(MutationOutcome.NotSaved(album4, MutationField.Rating, DomainError.Server.Unknown(99)), outcomes.single())
        assertEquals(0L, session.favourites.pendingCount())
    }

    // ---- HTTP statuses with no envelope (spec §18.6, "Failures") ------------------------------------

    @Test
    fun aGatewayThatCannotReachTheServerStopsTheFavouritesFlush() = sessionTest { env ->
        val (session, _) = online(env)
        env.server.failWithStatus["star"] = 502
        session.setOnline(false)
        session.favourites.setFavourite(album4, true)
        session.favourites.setRating(album4, 2)
        session.setOnline(true)
        val report = session.favourites.flush()
        assertEquals(DomainError.Server.HttpStatus(502), report.stoppedBy)
        assertEquals(listOf("star"), sends(env).map { it.endpoint }, "nothing is sent past a gateway that cannot reach the server")
        assertEquals(2L, session.favourites.pendingCount())
    }

    @Test
    fun aRequestTooLargeIsARefusalNotARetry() = sessionTest { env ->
        val (session, _) = online(env)
        env.server.failWithStatus["setRating"] = 414
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        session.favourites.setRating(album4, 3)
        advanceUntilIdle()
        assertEquals(MutationOutcome.NotSaved(album4, MutationField.Rating, DomainError.Server.HttpStatus(414)), outcomes.single())
        assertEquals(0L, session.favourites.pendingCount())
    }

    @Test
    fun anotherErrorStatusIsThisChangesFailureAndTheFlushMovesOn() = sessionTest { env ->
        val (session, _) = online(env)
        env.server.failWithStatus["star"] = 500
        session.setOnline(false)
        session.favourites.setFavourite(album4, true)
        session.favourites.setRating(LibraryEntityRef(LibraryEntityKind.Track, "album-0004-track-0"), 4)
        session.setOnline(true)
        val report = session.favourites.flush()
        assertEquals(listOf("star", "setRating"), sends(env).map { it.endpoint })
        assertEquals(1, report.deferred)
        assertEquals(1L, session.favourites.pendingCount())
    }

    @Test
    fun aServerErrorStatusDoesNotProveTheChangeWasNotApplied() = sessionTest { env ->
        // A 500 may come after the change landed; only a 4xx proves it did not. The server's 4 here
        // is this device's own write, so the later 5 is sent, not superseded by it.
        env.server.ratings[albumId(4)] = 3
        val (session, _) = online(env)
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        env.server.applyThenStatus["setRating"] = 500
        session.favourites.setRating(album4, 4)
        advanceUntilIdle()
        assertEquals(4, env.server.ratings[albumId(4)], "fixture: the change landed, the answer was an error status")
        env.server.applyThenStatus.clear()
        session.setOnline(false)
        session.favourites.setRating(album4, 5)
        session.setOnline(true)
        session.reader.open(grid) {}.also { it.refresh() }
        advanceUntilIdle()
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(5, env.server.ratings[albumId(4)])
        assertTrue(outcomes.none { it is MutationOutcome.Superseded }, "$outcomes")
    }

    @Test
    fun aClientErrorStatusProvesTheChangeWasNotApplied() = sessionTest { env ->
        // The positive side of the rule above: a 4xx is proof, so the server's 4 — written by
        // another client after this device's refused send — wins over this device's later 5.
        env.server.ratings[albumId(4)] = 3
        val (session, _) = online(env)
        val outcomes = mutableListOf<MutationOutcome>()
        session.favourites.addOutcomeListener { outcomes += it }
        env.server.failWithStatus["setRating"] = 429
        session.favourites.setRating(album4, 4)
        advanceUntilIdle()
        env.server.failWithStatus.clear()
        session.setOnline(false)
        session.favourites.setRating(album4, 5)
        env.server.ratings[albumId(4)] = 4 // another client, after this device's change
        session.setOnline(true)
        session.reader.open(grid) {}.also { it.refresh() }
        advanceUntilIdle()
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(4, env.server.ratings[albumId(4)])
        assertEquals(MutationOutcome.Superseded(album4, MutationField.Rating, 4), outcomes.last())
    }

    @Test
    fun reconnectFlushesFavouritesBeforeTheScrobbleOutboxAndTheEpoch() = sessionTest { env ->
        val session = env.session(otherOutboxes = ReconnectOutboxes { env.server.log += SessionTestServer.Request("scrobble-outbox", emptyMap()) })
        online(env, session)
        session.setOnline(false)
        session.favourites.setFavourite(album4, true)
        session.setOnline(true)
        val before = env.server.log.size
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(listOf("star", "scrobble-outbox", "getScanStatus"), env.server.log.drop(before).take(3).map { it.endpoint })
    }

    @Test
    fun flushDoesNothingOffline() = sessionTest { env ->
        val (session, _) = online(env)
        session.setOnline(false)
        session.favourites.setFavourite(album4, true)
        val before = env.server.log.size
        val report = session.favourites.flush()
        assertEquals(0, report.sent)
        assertEquals(before, env.server.log.size)
        assertEquals(1L, session.favourites.pendingCount())
    }

    // ---- Nits ---------------------------------------------------------------------------------------

    @Test
    fun aStarOnAnIdentityOnlyTrackStaysAfterTheOverlayGoes() = sessionTest { env ->
        val (session, _) = online(env)
        val pinned = LibraryEntityRef(LibraryEntityKind.Track, "pinned-not-yet-read")
        env.cache().pin(CacheItemKind.Track, pinned.rawId, CachePinReason.Queue)
        val seqBefore = env.cache().track(pinned.rawId)!!.row.issueSeq
        session.favourites.setFavourite(pinned, true)
        advanceUntilIdle()
        assertEquals(0L, session.favourites.pendingCount(), "fixture: saved and removed from the outbox")
        assertEquals(true, session.favourites.isFavourite(pinned), "the published value does not vanish with the overlay")
        assertEquals(seqBefore, env.cache().track(pinned.rawId)!!.row.issueSeq, "the metadata refill read can still land")
    }

    @Test
    fun a5_aStaleAnswerFromANonCancellableTransportIsNeverPublished() = sessionTest { env ->
        @OptIn(ExperimentalCoroutinesApi::class)
        val transport = LibraryEndpointTransport { e, p ->
            val d = env.scope.async { env.server.request(e, p) }
            suspendCoroutine { c -> d.invokeOnCompletion { c.resume(d.getCompleted()) } }
        }
        val session = LibraryReaderSession(env.database.database, env.store.bind(SessionEnv.BINDING), transport, env.scope,
            LibraryReaderConfig(lookAheadMaxPerViewport = 0), formPost = false)
        session.reader.connect()
        session.reader.open(grid) {}.also { advanceUntilIdle() }.close()
        env.server.holdAfterAnswer += "search3"
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        search.updateQuery("Album 001")
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount)
        env.server.holdAfterAnswer.clear()
        search.updateQuery("Album 002")
        env.server.release()
        advanceUntilIdle()
        assertTrue(pubs.all.none { it.value.query == "Album 002" && it.value.rows.any { r -> r.item.id.rawId == albumId(15) } },
            "rows answering Album 001 were published under Album 002")
        assertTrue(pubs.all.none { it.value.query == "Album 001" && it.value.scope == SearchScope.ServerAndDevice })
    }
}
