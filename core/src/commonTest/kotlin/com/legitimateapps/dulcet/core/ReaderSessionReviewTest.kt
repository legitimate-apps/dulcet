package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests from the independent review of the Apple facade's first cut (R2a), for the core
 * behaviour it reached: a closed search is released, a viewport past the end of a list that shrank
 * is accepted, search rows carry playability, and a queued change that cannot be decoded is still
 * counted for the sign-out offer. Each names what it guards.
 */
class ReaderSessionReviewTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    private suspend fun TestScope.primed(env: SessionEnv, session: LibraryReaderSession = env.session()): LibraryReaderSession {
        session.reader.connect()
        session.reader.open(grid) {}.also { advanceUntilIdle() }.close()
        return session
    }

    // ---- A closed search is released ------------------------------------------------------------------

    /**
     * Closing a search unregisters everything the session registered for it — the favourites'
     * change listener and the session's own list — so a closed search is not kept for the life of
     * the session. The positive case first: while open, each is registered.
     */
    @Test
    fun closingASearchUnregistersItsChangeListenerAndItsReachabilityEntry() = sessionTest { env ->
        val session = primed(env)
        val baseListeners = session.favourites.changeListenerCount
        val searches = (1..20).map { index ->
            session.openSearch { }.also { it.updateQuery("Album 00" + (index % 10)) }
        }
        advanceUntilIdle()
        assertEquals(baseListeners + 20, session.favourites.changeListenerCount, "fixture: each open search listens for changes")
        assertEquals(20, session.openSearchCount, "fixture: each open search is told about reachability")

        searches.forEach(LibrarySearchSession::close)
        searches.first().close() // idempotent: unregisters nothing twice
        assertEquals(baseListeners, session.favourites.changeListenerCount, "a closed search is still a change listener")
        assertEquals(0, session.openSearchCount, "a closed search is still told about reachability")

        // An open search is untouched by the others' closing: it still takes a pending change.
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 0004")
        session.setOnline(false)
        val before = pubs.all.size
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true)
        assertEquals(before + 1, pubs.all.size, "the open search was not republished for the change")
        assertEquals(true, pubs.last.rows.single { it.item.id.rawId == albumId(4) }.favourite)
    }

    // ---- A viewport past the end is accepted ---------------------------------------------------------

    /**
     * The realistic trigger: the person sits near the end of a list, a revalidation shortens it,
     * and the shell's next viewport still names indexes from the longer list. Accepted and clamped,
     * never a throw — a throw here is what the facade turned into `cached(internalFailure)`.
     */
    @Test
    fun aViewportPastTheEndOfAListThatShrankIsAccepted() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(100, pubs.last.items.size, "fixture: a 100-item page")
        handle.setViewport(90, 99)

        env.server.base.albums.subList(50, env.server.base.albums.size).clear()
        env.server.base.lastScan = "2026-09-22T14:00:00+02:00"
        handle.refresh()
        advanceUntilIdle()
        assertEquals(50, pubs.last.items.size, "fixture: the revalidation shortened the list")

        handle.setViewport(95, 99) // past the end
        handle.setViewport(150, 160) // far past it
        handle.setViewport(10, 5) // reversed
        handle.setViewport(-4, -1) // before the start
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertTrue(session.reader.uncaughtFailures.isEmpty(), "uncaught: ${session.reader.uncaughtFailures}")
        handle.close()
    }

    // ---- Search rows carry playability ----------------------------------------------------------------

    /**
     * §16.14: rows carry playability, and offline rows that cannot play are dimmed and badged. A
     * search's track rows carry it by the windows' rule; album rows carry none.
     */
    @Test
    fun searchTrackRowsCarryPlayabilityOnlineAndOffline() = sessionTest { env ->
        val downloaded = "album-0001-track-0"
        val session = primed(
            env,
            LibraryReaderSession(
                env.database.database, env.cache(), env.server, env.scope,
                LibraryReaderConfig(lookAheadMaxPerViewport = 0),
                downloads = DownloadedTrackSource { setOf(downloaded) },
            ),
        )
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(LibrarySearchConfig(debounceMillis = 0), pubs)
        search.updateQuery("Song album-0001")
        advanceUntilIdle()
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope, "fixture: the server answered")
        val online = pubs.last.rows.filter { it.item.type == SearchResultType.Track }.associate { it.item.id.rawId to it.playability }
        assertEquals(
            mapOf(downloaded to LibraryPlayability.Downloaded, "album-0001-track-1" to LibraryPlayability.Streamable),
            online,
        )

        session.setOnline(false)
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope)
        val offline = pubs.last.rows.filter { it.item.type == SearchResultType.Track }.associate { it.item.id.rawId to it.playability }
        assertEquals(
            mapOf(downloaded to LibraryPlayability.Downloaded, "album-0001-track-1" to LibraryPlayability.UnavailableOffline),
            offline,
            "offline, a track that is not downloaded cannot play",
        )

        search.updateQuery("Album 0001")
        assertTrue(pubs.last.rows.any { it.item.type == SearchResultType.Album }, "fixture: album rows")
        assertTrue(pubs.last.rows.filter { it.item.type != SearchResultType.Track }.all { it.playability == null }, "only tracks carry playability")
    }

    // ---- The sign-out count counts what cannot be decoded ------------------------------------------------

    /**
     * A queued row this build cannot decode is never sent, so it is lost at sign-out like any other
     * pending change: it is counted, never silently dropped from the number the person is shown.
     */
    @Test
    fun aQueuedChangeThatCannotBeDecodedIsCounted() = sessionTest { env ->
        val session = env.session()
        session.setOnline(false)
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true)
        assertEquals(1L, session.favourites.pendingCount(), "fixture: one change is pending")
        env.database.database.protectedReservedDataQueries.insertPendingMutation(
            SessionEnv.BINDING.serverId, albumId(9), "album.unknownField", "{\"value\":1}", 999_999L, 0L,
        )
        assertEquals(1, session.outbox.all().size, "fixture: the new row cannot be decoded")
        assertEquals(2L, session.favourites.pendingCount(), "an undecodable queued change was left out of the count")
        assertNull(session.outbox.all().firstOrNull { it.target.rawId == albumId(9) })
    }
}
