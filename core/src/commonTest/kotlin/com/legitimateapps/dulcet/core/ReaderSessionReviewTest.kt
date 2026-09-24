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

    /**
     * A rebase whose anchor lies past the server's new total re-anchors on the last page that
     * exists: the list shrinks from 250 to 150 while the viewport sits at 210–220. No stored row at
     * or beyond the new total is kept, shown or labelled `live` — it used to keep album 200, which
     * the server no longer has, as a `live` one-item list.
     */
    @Test
    fun aRebasePastTheNewTotalReAnchorsOnTheLastPageThatExists() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        handle.setViewport(190, 199)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(250, 250), listOf(pubs.last.items.size, pubs.last.total), "fixture: the whole list is loaded")
        handle.setViewport(210, 220)
        env.server.base.albums.subList(150, env.server.base.albums.size).clear()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        val mark = pubs.all.size
        session.reader.refreshEpoch()
        advanceUntilIdle()
        val onServer = env.server.base.albums.map { it.id }.toSet()
        fun gone(publication: LibraryPublication) = publication.items.map { it.rawId }.filter { it !in onServer }
        val last = pubs.last
        assertEquals(150, last.total)
        assertEquals(LibraryFreshness.Live, last.freshness)
        assertEquals(emptyList(), gone(last), "a deleted album is still shown")
        assertEquals(albumId(149), last.items.last().rawId, "not re-anchored on the last page that exists")
        assertTrue(pubs.all.drop(mark).none { it.value.freshness == LibraryFreshness.Live && gone(it.value).isNotEmpty() }, "a deleted album was labelled live")
        assertTrue(env.cache().listMembers(ListRequestSpec.of(grid).listKey).none { it.member.rawId !in onServer }, "a deleted album is still stored in the window")

        handle.setViewport(0, 0)
        handle.loadBefore()
        advanceUntilIdle()
        assertEquals(emptyList(), gone(pubs.last))
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertTrue(session.reader.uncaughtFailures.isEmpty(), "uncaught: ${session.reader.uncaughtFailures}")
        handle.close()
    }

    /**
     * With no total to go by, a rebase whose anchor page comes back empty re-anchors on the top:
     * the list keeps only rows the server has just returned.
     */
    @Test
    fun aRebasePastTheEndWithNoTotalReAnchorsOnTheTop() = sessionTest { env ->
        env.server.base.sendTotalCount = false
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        handle.setViewport(190, 199)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(null, pubs.last.total, "fixture: the server sends no total")
        assertTrue(pubs.last.items.size > 200, "fixture: the list is loaded past 200 (${pubs.last.items.size})")
        handle.setViewport(pubs.last.items.size - 5, pubs.last.items.size - 1)
        env.server.base.albums.subList(150, env.server.base.albums.size).clear()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        session.reader.refreshEpoch()
        advanceUntilIdle()
        val onServer = env.server.base.albums.map { it.id }.toSet()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals((0 until 100).map(::albumId), pubs.last.items.map { it.rawId }, "not re-anchored on the top")
        assertTrue(env.cache().listMembers(ListRequestSpec.of(grid).listKey).all { it.member.rawId in onServer })
        handle.close()
    }

    /**
     * A list that has emptied, with no total, rebased at the top: the rebase read no rows, so it
     * keeps none — it used to keep the row at its anchor and show that deleted album as `live`.
     */
    @Test
    fun aRebaseOfAListThatEmptiedKeepsNothing() = sessionTest { env ->
        env.server.base.sendTotalCount = false
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(100, pubs.last.items.size, "fixture")
        env.server.base.albums.clear()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        session.reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(emptyList(), pubs.last.items.map { it.rawId }, "an album the server no longer has is still shown")
        assertEquals(emptyList(), env.cache().listMembers(ListRequestSpec.of(grid).listKey), "a deleted album is still stored")
        handle.close()
    }

    /**
     * Fuzz over every entry point that moves a window — viewports with extreme values, paging both
     * ways, refreshes, shrinks with an epoch change, reachability — never throws, records no
     * uncaught failure and publishes no internal failure. The seed is fixed: a failure reproduces.
     */
    @Test
    fun windowEntryPointFuzzNeverThrowsOrFailsInternally() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val random = kotlin.random.Random(20260924)
        val extremes = listOf(Int.MIN_VALUE, -1, 0, 1, 99, 100, 101, 249, 250, 10_000, Int.MAX_VALUE)
        fun pick() = if (random.nextBoolean()) extremes.random(random) else random.nextInt(-50, 400)
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        val throws = mutableListOf<String>()
        var shrinks = 0
        repeat(400) { step ->
            try {
                when (random.nextInt(8)) {
                    0, 1, 2 -> handle.setViewport(pick(), pick())
                    3 -> handle.loadMore()
                    4 -> handle.loadBefore()
                    5 -> handle.refresh()
                    6 -> {
                        if (random.nextBoolean()) {
                            val keep = random.nextInt(0, env.server.base.albums.size + 1)
                            env.server.base.albums.subList(keep, env.server.base.albums.size).clear()
                            shrinks += 1
                        }
                        env.server.base.lastScan = "2026-09-24T10:00:${(step % 60).toString().padStart(2, '0')}Z"
                        session.reader.refreshEpoch()
                    }
                    7 -> session.setOnline(random.nextBoolean())
                }
            } catch (thrown: Throwable) {
                throws += "step $step: $thrown"
            }
            advanceUntilIdle()
        }
        assertTrue(shrinks > 0 && pubs.all.size > 100, "fixture: the fuzz shrank the list and published (${pubs.all.size})")
        val internal = pubs.all.count {
            (it.value.freshness as? LibraryFreshness.Cached)?.reason == LibraryCachedReason.InternalFailure ||
                it.value.freshness == LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure)
        }
        assertEquals(emptyList(), throws)
        assertEquals(0, internal, "internal-failure publications")
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
