package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CONF-79 and §18.1's timing, driven through the production [LibrarySearchSession] over the
 * production [LibraryReader] and seen-cache. Request counts are asserted, never wall time.
 */
class LibrarySearchSessionTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    /** Opens the alphabetical grid once so the cache holds albums 0000..0099 and their epoch. */
    private suspend fun TestScope.primed(env: SessionEnv): LibraryReaderSession {
        val session = env.session()
        session.reader.connect()
        session.reader.open(grid) {}.also { advanceUntilIdle() }.close()
        assertEquals(100L, env.cache().counts().albums, "fixture: the grid's first page is what this device has seen")
        return session
    }

    private fun List<LibrarySearchRow>.ids() = map { it.item.id.rawId }

    // ---- CONF-79: the four scopes -----------------------------------------------------------------

    @Test
    fun conf79FewerThanTwoCharactersIsDeviceWhileServerPendingAndNeverAsksTheServer() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        val before = env.server.log.size
        search.updateQuery("A")
        assertEquals(1, pubs.all.size, "the device's rows publish before updateQuery returns")
        assertEquals(SearchScope.DeviceWhileServerPending, pubs.last.scope)
        assertEquals(100, pubs.last.rows.size, "local search runs from the first character")
        assertTrue(pubs.last.rows.all { it.source == SearchResultSource.Device })
        advanceUntilIdle()
        assertEquals(before, env.server.log.size, "one character never reaches search3")
        assertEquals(1, pubs.all.size)
    }

    @Test
    fun conf79ServerAndDeviceMarksEachRowsSourceAndKeepsLocalOnlyRows() = sessionTest { env ->
        val session = primed(env)
        env.server.searchExcludes += albumId(5) // the server's matcher does not return it
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        search.updateQuery("Album 000")
        assertEquals(SearchScope.DeviceWhileServerPending, pubs.last.scope, "pending until the server answers")
        assertEquals(0, env.server.count("search3"), "nothing is sent before the debounce")
        advanceUntilIdle()
        assertEquals(1, env.server.count("search3"))
        val merged = pubs.last
        assertEquals(SearchScope.ServerAndDevice, merged.scope)
        val bySource = merged.rows.associate { it.item.id.rawId to it.source }
        assertEquals(SearchResultSource.Device, bySource[albumId(5)], "a local-only row stays, marked as from this device")
        assertEquals(SearchResultSource.Server, bySource[albumId(4)])
        assertEquals((0..9).map(::albumId).toSet(), merged.rows.ids().toSet(), "no row dropped, none duplicated")
        assertEquals(merged.rows.size, merged.rows.ids().toSet().size)
    }

    @Test
    fun conf79OfflineStatesWhatThisDeviceHasSeen() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        val before = env.server.log.size
        search.updateQuery("Album 001")
        advanceUntilIdle()
        val offline = assertIs<SearchScope.DeviceOffline>(pubs.last.scope)
        assertEquals(SeenCacheCounts(artists = 0, albums = 100, tracks = 0), offline.seen)
        assertEquals((10..19).map(::albumId), pubs.last.rows.ids())
        assertEquals(before, env.server.log.size, "offline search issues no request")
    }

    @Test
    fun conf79AServerFailureKeepsTheDeviceRowsAndNamesTheKindWithCounts() = sessionTest { env ->
        val session = primed(env)
        env.server.failWithCode["search3"] = 10
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 002")
        advanceUntilIdle()
        val failed = assertIs<SearchScope.DeviceServerFailed>(pubs.last.scope)
        assertEquals(DomainError.Server.Known(10), failed.error)
        assertEquals(100L, failed.seen.albums)
        assertEquals((20..29).map(::albumId), pubs.last.rows.ids(), "an error never replaces the device's rows")
        assertTrue(pubs.last.rows.all { it.source == SearchResultSource.Device })
    }

    @Test
    fun conf79AnUnreachableServerIsTheOfflineScope() = sessionTest { env ->
        val session = primed(env)
        env.server.failWithError["search3"] = DomainError.Transport.Unreachable
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 002")
        advanceUntilIdle()
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope)
    }

    @Test
    fun anUnreachableServerKeepsOneLabelAcrossKeystrokes() = sessionTest { env ->
        val session = primed(env)
        env.server.failWithError["search3"] = DomainError.Transport.Unreachable
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        search.updateQuery("Album 002")
        advanceUntilIdle()
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope)
        val mark = pubs.all.size
        search.updateQuery("Album 0021")
        advanceUntilIdle()
        assertTrue(pubs.all.drop(mark).all { it.value.scope is SearchScope.DeviceOffline },
            "the label must not flip to 'on this device' and back on every keystroke")
        assertEquals(2, env.server.count("search3"), "the server is still asked")
        env.server.failWithError.clear()
        search.updateQuery("Album 002")
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope, "until an answer arrives")
        advanceUntilIdle()
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope)
        search.updateQuery("Album 0021")
        assertEquals(SearchScope.DeviceWhileServerPending, pubs.last.scope, "a success clears it")
    }

    @Test
    fun goingOfflineWithASearchOpenRepublishesItsScopeWithoutAKeystroke() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 002")
        advanceUntilIdle()
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope)
        session.setOnline(false)
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope)
        assertEquals((20..29).map(::albumId), pubs.last.rows.ids())
    }

    // ---- §18.1 timing and merge ----------------------------------------------------------------

    @Test
    fun theDebounceSendsOneRequestForABurstAndOneAnswerIsPublishedPerQuery() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        for (text in listOf("Al", "Alb", "Albu", "Album")) {
            search.updateQuery(text)
            advanceTimeBy(200)
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals(listOf("Album"), env.server.log.filter { it.endpoint == "search3" }.map { it.parameters["query"] })
        search.updateQuery("Album 0")
        advanceTimeBy(300)
        runCurrent()
        search.updateQuery("Album 00")
        advanceUntilIdle()
        assertEquals(3, env.server.count("search3"), "a pause of more than the debounce sends each query")
    }

    @Test
    fun anAnswerToAnOlderQueryIsNeverPublished() = sessionTest { env ->
        val session = primed(env)
        env.server.holdAfterAnswer += "search3"
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        search.updateQuery("Album 001")
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount, "fixture: the first answer is in flight")
        search.updateQuery("Album 002")
        runCurrent() // the cancelled request observes its cancellation when it next runs
        assertEquals(1, env.server.cancelledWhileHeld, "a keystroke cancels the in-flight server request")
        env.server.holdAfterAnswer.clear()
        env.server.release()
        advanceUntilIdle()
        assertTrue(pubs.all.none { it.value.query == "Album 001" && it.value.scope == SearchScope.ServerAndDevice })
        assertEquals("Album 002", pubs.last.query)
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope)
    }

    @Test
    fun theLiveAnswerReplacesCachedRowsInPlaceWithNoFrameBetween() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 00")
        advanceUntilIdle()
        assertEquals(2, pubs.all.size, "exactly the device's rows, then the merged rows — nothing between")
        val device = pubs.all[0].value.rows
        val merged = pubs.all[1].value.rows
        assertEquals(100, device.size)
        assertEquals(device.ids(), merged.ids().take(device.size), "no row moves when the server answers")
        assertEquals(device.map { it.item }, merged.take(device.size).map { it.item },
            "a row the server confirms with the same data is drawn identically")
        assertEquals(30, merged.count { it.source == SearchResultSource.Server })
    }

    @Test
    fun serverRowsAreWrittenThroughSoTheNextKeystrokeFindsThemLocally() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        search.updateQuery("Album 015")
        advanceUntilIdle()
        assertEquals((150..159).map(::albumId), pubs.last.rows.ids(), "server-only rows append")
        assertEquals(110L, env.cache().counts().albums, "search results are cached as entities")
        search.updateQuery("Album 0155")
        assertEquals(listOf(albumId(155)), pubs.last.rows.ids(), "found on this device before any request")
        assertEquals(SearchScope.DeviceWhileServerPending, pubs.last.scope)
    }

    @Test
    fun ordersAreTotalSoKeystrokesAndArrivalOrderCannotReshuffleRows() = sessionTest { env ->
        val rows = (0 until 6).map { i ->
            SearchResultItem(ProviderItemId("p", "id-$i"), SearchResultType.Album, "Same Title", emptyList(),
                null, null, null, null, null, null, null, null)
        }
        val forward = rankResultsStably("same", rows)
        assertEquals(forward, rankResultsStably("same", rows.reversed()), "input order must not decide a tie")
        assertEquals(forward, rankResultsStably("same", rows.shuffled(kotlin.random.Random(7))))

        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        val orders = listOf("Al", "Alb", "Albu", "Album").map { search.updateQuery(it); pubs.last.rows.ids() }
        orders.zipWithNext().forEach { (a, b) ->
            val common = a.filter { it in b }
            assertEquals(common, b.filter { it in a }, "rows kept across a keystroke keep their relative order")
        }
    }

    @Test
    fun aGoneRowIsNeverShown() = sessionTest { env ->
        val session = primed(env)
        env.cache().markAlbumNotFound(env.cache().issue(), albumId(3))
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 000")
        assertEquals((0..9).map(::albumId) - albumId(3), pubs.last.rows.ids())
    }
}
