package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reader's one reachability model (§16.14), driven through the production
 * [LibraryReaderSession]: a reconnect is the only way back online, so for a reader coming back
 * from offline, in every order a shell can report reachability and call reconnect, nothing reads
 * the server before the outbox flush and the epoch read have finished (a reader that is already
 * online keeps reading, §18.3); a reconnect that cannot read the epoch says so and strands nothing;
 * and a surface that is current is not re-read. Request counts and orders are asserted, never wall
 * time.
 */
class ReaderReachabilityTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val album3 = LibraryEntityRef(LibraryEntityKind.Album, albumId(3))
    private val debounce = LibrarySearchConfig().debounceMillis

    /** Opens the alphabetical grid once so the cache holds albums 0000..0099 and their epoch. */
    private suspend fun TestScope.primed(env: SessionEnv): LibraryReaderSession {
        val session = env.session()
        session.reader.connect()
        session.reader.open(grid) {}.also { advanceUntilIdle() }.close()
        assertEquals(100L, env.cache().counts().albums, "fixture: the grid's first page is what this device has seen")
        return session
    }

    private fun List<LibrarySearchRow>.ids() = map { it.item.id.rawId }

    // ---- (a) Every calling order: nothing before the flush --------------------------------------------

    private enum class CallingOrder { SetOnlineThenReconnect, ReconnectAlone, SetOnlineAlone }

    /**
     * Offline, a search is open and a favourite is pending; the server holds the flush's `star` for
     * four search debounces. Whatever the order the shell reports reachability and reconnects in,
     * no `search3` goes out while the flush is held — including one a keystroke made during it —
     * and exactly one goes out after the epoch read, carrying the latest text.
     */
    private suspend fun TestScope.nothingOvertakesTheFlush(env: SessionEnv, order: CallingOrder) {
        val session = primed(env)
        session.setOnline(false)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(listener = pubs)
        search.updateQuery("Album 002")
        advanceUntilIdle()
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope, "fixture: the search opened offline")
        session.favourites.setFavourite(album3, true)
        env.server.holdBeforeApply += "star"
        val mark = env.server.log.size

        val reconnect: Job? = when (order) {
            CallingOrder.SetOnlineThenReconnect -> {
                session.setOnline(true)
                env.scope.launch { session.reader.reconnect() }
            }
            CallingOrder.ReconnectAlone -> env.scope.launch { session.reader.reconnect() }
            CallingOrder.SetOnlineAlone -> {
                session.setOnline(true)
                null
            }
        }
        advanceTimeBy(debounce * 4)
        runCurrent()
        assertEquals(1, env.server.heldCount, "fixture: the flush's star is in flight ($order)")

        // A keystroke during the flush: the device's rows at once, the offline scope, no request.
        search.updateQuery("Album 0021")
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope, "a keystroke during the flush was given a server scope ($order)")
        advanceTimeBy(debounce * 4)
        runCurrent()
        assertEquals(
            0,
            env.server.endpoints().drop(mark).count { it == "search3" },
            "a search was sent during the outbox flush ($order): ${env.server.endpoints().drop(mark)}",
        )

        env.server.holdBeforeApply.clear()
        env.server.release()
        advanceUntilIdle()
        reconnect?.let { assertTrue(it.isCompleted, "fixture: reconnect finished ($order)") }
        val sent = env.server.endpoints().drop(mark)
        assertEquals(1, sent.count { it == "search3" }, "exactly one search after the reconnect ($order): $sent")
        assertEquals(1, sent.count { it == "star" }, "one flush ($order): $sent")
        assertTrue(sent.indexOf("star") in 0 until sent.indexOf("getScanStatus"), "the flush comes first ($order): $sent")
        assertTrue(sent.lastIndexOf("getScanStatus") < sent.indexOf("search3"), "the search follows the epoch read ($order): $sent")
        assertEquals("Album 0021", env.server.log.drop(mark).single { it.endpoint == "search3" }.parameters["query"])
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope, "the search still says offline after reconnect ($order)")
        assertTrue(session.reader.online, "the reader is not online after its reconnect ($order)")
    }

    @Test
    fun reportingReachabilityThenReconnectingSendsNothingBeforeTheFlush() = sessionTest { env ->
        nothingOvertakesTheFlush(env, CallingOrder.SetOnlineThenReconnect)
    }

    @Test
    fun reconnectingAloneSendsNothingBeforeTheFlush() = sessionTest { env ->
        nothingOvertakesTheFlush(env, CallingOrder.ReconnectAlone)
    }

    @Test
    fun reportingReachabilityAloneReconnectsAndSendsNothingBeforeTheFlush() = sessionTest { env ->
        nothingOvertakesTheFlush(env, CallingOrder.SetOnlineAlone)
    }

    // ---- (b) A failed epoch read ----------------------------------------------------------------------

    /**
     * Offline with a window and a search open, a reconnect whose `getScanStatus` fails leaves the
     * reader offline — which is what the window and the search already say — and publishes nothing.
     * The next reachability report is not swallowed: it reconnects, and both come back.
     */
    @Test
    fun aReconnectWhoseEpochReadFailsStrandsNothingAndTheNextReportRetries() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        val windows = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, windows)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 002")
        advanceUntilIdle()
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope, "fixture: the search opened offline")
        assertEquals(LibraryCachedReason.Offline, (windows.last.freshness as LibraryFreshness.Cached).reason)
        val windowsBefore = windows.all.size
        val searchesBefore = pubs.all.size
        env.server.failWithError["getScanStatus"] = DomainError.Transport.Timeout
        val mark = env.server.log.size

        session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(env.server.log.drop(mark).any { it.endpoint == "getScanStatus" }, "fixture: the epoch read was attempted")
        assertFalse(session.reader.online, "a reconnect that could not read the epoch left the reader online")
        assertEquals(searchesBefore, pubs.all.size, "a failed reconnect republished the search")
        assertEquals(windowsBefore, windows.all.size, "a failed reconnect republished the window")
        assertEquals(0, env.server.endpoints().drop(mark).count { it == "search3" || it == "getAlbumList2" })

        env.server.failWithError.clear()
        session.setOnline(true)
        advanceUntilIdle()
        assertTrue(session.reader.online, "the next reachability report was swallowed")
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope)
        assertEquals((20..29).map(::albumId), pubs.last.rows.ids())
        assertEquals(LibraryFreshness.Live, windows.last.freshness)
    }

    /** The same failure, retried by calling reconnect again. */
    @Test
    fun aReconnectWhoseEpochReadFailsIsRetriedByTheNextReconnect() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 002")
        advanceUntilIdle()
        env.server.failWithError["getScanStatus"] = DomainError.Transport.Unreachable
        session.reader.reconnect()
        advanceUntilIdle()
        assertFalse(session.reader.online, "a reconnect that could not read the epoch left the reader online")
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope)

        env.server.failWithError.clear()
        val searches = env.server.count("search3")
        session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope)
        assertEquals(searches + 1, env.server.count("search3"))
    }

    // ---- (c) A list never read ------------------------------------------------------------------------

    /**
     * A list never read, opened offline, then brought online by a reachability report alone: its
     * read is issued, and `loading` is published only with that read in flight — never a spinner
     * with nothing behind it.
     */
    @Test
    fun aListNeverReadOpenedOfflineIsReadWhenReachabilityReturns() = sessionTest { env ->
        val session = env.session()
        session.setOnline(false)
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.NotCachedOffline), pubs.last.freshness, "fixture")
        env.server.base.holdMatching = { it.endpoint == "getAlbumList2" }

        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "the list was never read: ${pubs.all.map { it.value.freshness }}")
        assertEquals(LibraryFreshness.Loading, pubs.last.freshness, "the read is in flight, so the list says loading")
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals(100, pubs.last.items.size)
        handle.close()
    }

    /**
     * A list whose last read failed, brought back by a reconnect, says `loading` while the retry is
     * in flight — not the old failure, as a cached list says `revalidating` rather than `failed`.
     */
    @Test
    fun aListWhoseReadFailedSaysLoadingWhileReconnectRetriesIt() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        env.server.failWithError["getAlbumList2"] = DomainError.Transport.Timeout
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(
            LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(DomainError.Transport.Timeout)),
            pubs.last.freshness,
            "fixture: the first read failed",
        )
        session.setOnline(false)
        env.server.failWithError.clear()
        env.server.base.holdMatching = { it.endpoint == "getAlbumList2" }

        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: the retry is in flight")
        assertEquals(LibraryFreshness.Loading, pubs.last.freshness, "a retry in flight still said the last failure")
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    /**
     * The flush runs while the server is reported reachable, before the reader is online again: a
     * change tapped while a reconnect reads the epoch is sent then, not left for the next reconnect.
     */
    @Test
    fun aChangeTappedWhileAReconnectReadsTheEpochIsSent() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        env.server.base.holdMatching = { it.endpoint == "getScanStatus" }
        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: the reconnect is reading the epoch")
        assertFalse(session.reader.online, "fixture: not connected yet")

        session.favourites.setFavourite(album3, true)
        advanceUntilIdle()
        assertEquals(1, env.server.count("star"), "a change tapped while reachable was not sent")
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertEquals(1, env.server.count("star"))
        assertEquals(0L, session.favourites.pendingCount())
    }

    /**
     * The platform reporting the server unreachable while a reconnect reads the epoch cancels that
     * reconnect: the reading is never used to go online, nothing is revalidated, and the caller
     * hears `unreachable`. Without the cancel, the reading answered after the report and marked the
     * reader online while the platform said the server was gone.
     */
    @Test
    fun goingOfflineDuringAReconnectCancelsItAndTheReaderStaysOffline() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(LibrarySearchConfig(debounceMillis = 0), pubs).updateQuery("Album 002")
        advanceUntilIdle()
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope, "fixture: the search opened offline")
        env.server.base.holdMatching = { it.endpoint == "getScanStatus" }
        var outcome: ReaderConnectionOutcome? = null
        env.scope.launch { outcome = session.reader.reconnect() }
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: the reconnect is reading the epoch")
        val mark = env.server.log.size
        val published = pubs.all.size

        session.setOnline(false)
        advanceUntilIdle()
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertEquals(ReaderConnectionOutcome.Failed(DomainError.Transport.Unreachable), outcome, "the caller was not told the reconnect stopped")
        assertFalse(session.reader.online, "a reconnect cancelled by an unreachable report put the reader online")
        assertEquals(emptyList<String>(), env.server.log.drop(mark).map { it.endpoint }, "the cancelled reconnect went on to read")
        assertEquals(published, pubs.all.size, "the search was revalidated by a cancelled reconnect")
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope)
    }

    // ---- Order within the revalidation step --------------------------------------------------------------

    /**
     * The visible screen is revalidated windows first, then the other surfaces (open searches): the
     * window's re-read has been answered before the search publishes anything. A search re-run
     * publishes its device rows synchronously, so revalidating it first shows up as a search
     * publication ahead of the window's read. (The window's `live` frame is no marker: since the
     * round-5 review no screen says `live` until the whole reconnect has run, searches included.)
     */
    @Test
    fun reconnectRevalidatesTheWindowsBeforeTheSearches() = sessionTest { env ->
        val session = primed(env)
        env.clock.now += LibraryReaderConfig().revalidateWithinMillis + 1 // the grid is due a re-read
        session.setOnline(false)
        val events = mutableListOf<String>()
        session.reader.open(grid) {}
        session.openSearch(LibrarySearchConfig(debounceMillis = 0)) { events += "search" }.updateQuery("Album 002")
        advanceUntilIdle()
        events.clear()
        env.server.base.beforeRespond = { if (it.endpoint == "getAlbumList2") events += "window read" }

        session.reader.reconnect()
        advanceUntilIdle()
        assertTrue("window read" in events && "search" in events, "fixture: both were revalidated: $events")
        assertEquals("window read", events.first(), "the search was revalidated before the window: $events")
    }

    // ---- A current search is not re-read ----------------------------------------------------------------

    /**
     * A foreground return reconnects while a search shows the server's rows. Read within the
     * windows' re-read interval under the same epoch, it is left alone: no request, no publication.
     * Older, it is re-read once — and its label and its rows' sources never flip to the device's.
     */
    @Test
    fun aReconnectLeavesACurrentSearchAloneAndReReadsAnOlderOneWithoutAFlip() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(listener = pubs).updateQuery("Album 002")
        advanceUntilIdle()
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope, "fixture: a live search")
        val serverRows = pubs.last.rows.filter { it.source == SearchResultSource.Server }.ids()
        assertTrue(serverRows.isNotEmpty(), "fixture: the server answered with rows")
        val searches = env.server.count("search3")
        val published = pubs.all.size

        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(searches, env.server.count("search3"), "a current search was re-read by reconnect")
        assertEquals(published, pubs.all.size, "a current search was republished by reconnect")

        env.clock.now += LibraryReaderConfig().revalidateWithinMillis + 1
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(searches + 1, env.server.count("search3"), "an older search was not re-read")
        val after = pubs.all.drop(published).map { it.value }
        assertTrue(after.isNotEmpty(), "the re-read published nothing")
        assertTrue(after.all { it.scope == SearchScope.ServerAndDevice }, "the label flipped during the re-read: ${after.map { it.scope }}")
        assertTrue(
            after.all { publication -> publication.rows.filter { it.item.id.rawId in serverRows }.all { it.source == SearchResultSource.Server } },
            "a server row's source flipped to the device during the re-read",
        )
    }
}
