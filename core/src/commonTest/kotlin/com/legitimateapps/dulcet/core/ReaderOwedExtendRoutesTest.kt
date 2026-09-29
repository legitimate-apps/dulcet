package com.legitimateapps.dulcet.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owed extends after the round-9 review (§16.14, §28 revision 104 item 29): a screen never says
 * `live` while a "load more" it owes is still to be made, whatever route left it owed. A page a
 * rebase discarded is read once more at once; each extend keeps its own failure; a person's own
 * "load more" that fails online is owed like one asked for offline; and a window whose stamp keeps
 * moving costs no more per trigger than it did before extends were owed. The review's probes B9,
 * B10, B12, B13 and B16, and one for each outcome arm it found unpinned, as tests. Each is bounded
 * in time and in requests ([cappedSessionTest]), so a mutant that loops fails instead of holding a
 * worker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderOwedExtendRoutesTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    private fun LibraryPublication.label(): String =
        ((freshness as? LibraryFreshness.Cached)?.reason?.let { "cached(" + it::class.simpleName + ")" }
            ?: freshness::class.simpleName.orEmpty()) + "/" + items.size

    private fun offsets(env: SessionEnv, from: Int) =
        env.server.log.drop(from).filter { it.endpoint == "getAlbumList2" }.map { it.parameters["offset"] }

    private fun status500() = LibraryEndpointResponse(500, "<html>no</html>", "http://fixture.invalid/rest")

    /** A grid read live at 100 of 250 rows, viewport on its last ten, a "load more" owed offline. */
    private suspend fun kotlinx.coroutines.test.TestScope.owedAppend(
        env: SessionEnv,
    ): Triple<LibraryReaderSession, LibraryWindowHandle, Recorder<LibraryPublication>> {
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        advanceUntilIdle()
        session.setOnline(false)
        advanceUntilIdle()
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(100, pubs.last.items.size, "fixture: 100 rows, the load more owed")
        return Triple(session, handle, pubs)
    }

    /**
     * B9. The scan ends while the owed extend's page is read: the page is discarded and the window
     * rebased. The rebase settled, so the extend is read once more in the same reconnect and made,
     * which then ends `live` at 200 rows. The reconnect used to end `live` at 100 rows with the extend
     * still owed, made only by a later refresh.
     */
    @Test
    fun b9_aScanEndingMidExtendRetriesItOnceAndTheReconnectMakesIt() = cappedSessionTest { env ->
        val (session, _, pubs) = owedAppend(env)
        var moved = 0
        env.server.base.beforeRespond = { r ->
            if (moved == 0 && r.endpoint == "getAlbumList2" && r.parameters["offset"] == "100") {
                env.server.base.lastScan = "2026-09-24T10:00:00Z"
                moved++
            }
        }
        val mark = env.server.log.size
        session.setOnline(true)
        advanceUntilIdle()
        env.server.base.beforeRespond = {}
        assertEquals(1, moved, "fixture: the stamp moved while the extend's page was read")
        assertEquals(listOf<String?>("100", "0", "100"), offsets(env, mark), "the discarded extend was not read once more after the rebase")
        assertEquals("Live/200", pubs.last.label(), "the reconnect did not make the extend a rebase discarded")
    }

    /**
     * The retry is made once, never more: a page discarded a second time is owed, and the screen
     * says so — `cached(owed)`, never `live` with the extend outstanding. The next trigger makes it.
     */
    @Test
    fun aPageDiscardedTwiceIsOwedOnceAndTheScreenSaysSo() = cappedSessionTest { env ->
        val (session, handle, pubs) = owedAppend(env)
        var moved = 0
        env.server.base.beforeRespond = { r ->
            if (moved < 2 && r.endpoint == "getAlbumList2" && r.parameters["offset"] == "100") {
                moved++
                env.server.base.lastScan = "2026-09-24T10:0$moved:00Z"
            }
        }
        val mark = env.server.log.size
        session.setOnline(true)
        advanceUntilIdle()
        env.server.base.beforeRespond = {}
        assertEquals(2, moved, "fixture: the stamp moved on both reads of the extend's page")
        assertEquals(listOf<String?>("100", "0", "100", "0"), offsets(env, mark), "the retry was not made exactly once")
        assertEquals("cached(Owed)/100", pubs.last.label(), "a screen owing a load more said otherwise")
        val retry = env.server.log.size
        handle.refresh()
        advanceUntilIdle()
        assertEquals(listOf<String?>("0", "100"), offsets(env, retry), "the next trigger did not make the owed extend")
        assertEquals("Live/200", pubs.last.label())
    }

    /**
     * B10. Two owed extends in one reconnect: the append's page answers 500, the prepend's lands. The
     * append's failure is its own, and the prepend landing does not erase it: the screen says the
     * read failed. It used to end `live` with the failed append still owed. The next trigger makes it.
     */
    @Test
    fun b10_aLandedPrependDoesNotEraseAFailedOwedAppend() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99); handle.loadMore(); advanceUntilIdle()
        handle.setViewport(190, 199); advanceUntilIdle()
        env.server.base.lastScan = "2026-09-24T10:00:00Z" // rebased around the viewport: rows 100..199
        session.reader.reconnect(); advanceUntilIdle()
        handle.refresh(); advanceUntilIdle()
        assertEquals("Live/100", pubs.last.label(), "fixture: a window of rows 100..199")
        assertEquals(100, pubs.last.leadingOffset, "fixture: a window of rows 100..199")
        handle.setViewport(90, 99) // rows 190..199
        session.setOnline(false); advanceUntilIdle()
        handle.loadMore(); handle.loadBefore(); advanceUntilIdle()
        var failed = 0
        env.server.answerInstead = { e ->
            if (e == "getAlbumList2" && env.server.log.last().parameters["offset"] == "200") status500().also { failed++ } else null
        }
        val mark = env.server.log.size
        session.setOnline(true); advanceUntilIdle()
        val during = offsets(env, mark)
        env.server.answerInstead = { null }
        assertEquals(1, failed, "fixture: the append's page answered 500")
        assertEquals(listOf<String?>("200", "0"), during, "fixture: the append failed, then the prepend was read")
        assertEquals("cached(Failed)/200", pubs.last.label(), "the landed prepend erased the owed append's failure")
        val retry = env.server.log.size
        handle.refresh(); advanceUntilIdle()
        assertTrue("200" in offsets(env, retry), "the failed append was not owed: ${offsets(env, retry)}")
        assertEquals("Live/250", pubs.last.label())
    }

    /**
     * B12. A stamp that moves on every page read. The owed extend is not read on a window whose
     * rebase ended `unverified(changing)` in the same revalidation: it stays owed, the screen says
     * so, and a refresh costs 8 requests — the four reads of the rebase and their four *after*
     * readings — as it did before extends were owed, not 16. The reconnect costs 10: its two epoch
     * requests, the extend's page and the rebase's three re-reads. Nothing is read without a
     * trigger, and once the stamp holds still the next trigger makes the extend.
     */
    @Test
    fun b12_aWindowThatTearsEveryTimeCostsNoMorePerTriggerAndStaysOwed() = cappedSessionTest { env ->
        val (session, handle, pubs) = owedAppend(env)
        var n = 0
        env.server.base.beforeRespond = { r ->
            if (r.endpoint == "getAlbumList2") {
                n++
                env.server.base.lastScan = "2026-09-24T10:" + (n % 60).toString().padStart(2, '0') + ":00Z"
            }
        }
        val m0 = env.server.log.size
        session.setOnline(true); advanceUntilIdle()
        val reconnect = env.server.log.size - m0
        val reconnectOffsets = offsets(env, m0)
        val perRefresh = (1..3).map {
            val m = env.server.log.size
            handle.refresh(); advanceUntilIdle()
            env.server.log.size - m
        }
        val m1 = env.server.log.size
        advanceTimeBy(3_600_000); runCurrent()
        val idle = env.server.log.size - m1
        env.server.base.beforeRespond = {}
        assertEquals(listOf<String?>("100", "0", "0", "0"), reconnectOffsets, "fixture: the extend's page, then the rebase's three re-reads")
        assertEquals(10, reconnect, "requests per reconnect")
        assertEquals(listOf(8, 8, 8), perRefresh, "requests per refresh")
        assertEquals(0, idle, "reads with no trigger")
        assertEquals(LibraryCoverage.UnverifiedChanging, pubs.last.coverage, "fixture: the stamp never held still")
        assertEquals("cached(Owed)/100", pubs.last.label(), "a screen owing a load more said otherwise")
        val still = env.server.log.size
        handle.refresh(); advanceUntilIdle()
        assertEquals(listOf<String?>("0", "100"), offsets(env, still), "the owed extend was lost while the stamp moved")
        assertEquals("Live/200", pubs.last.label())
    }

    /**
     * B13. An owed extend whose viewport has moved more than a page away is proven unneeded and
     * dropped — not owed again — so it is not made when the person scrolls back.
     */
    @Test
    fun b13_anExtendWhoseViewportMovedAwayIsDroppedNotReOwed() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99); handle.loadMore(); advanceUntilIdle()
        handle.setViewport(190, 199); advanceUntilIdle()
        session.setOnline(false); advanceUntilIdle()
        handle.loadMore(); advanceUntilIdle()
        handle.setViewport(0, 9)
        val m0 = env.server.log.size
        session.setOnline(true); advanceUntilIdle()
        val first = offsets(env, m0)
        assertEquals("Live/200", pubs.last.label(), "an extend proven unneeded left the screen owing it")
        handle.setViewport(190, 199)
        session.setOnline(false); advanceUntilIdle()
        val m1 = env.server.log.size
        session.setOnline(true); advanceUntilIdle()
        val second = offsets(env, m1)
        assertTrue("200" !in first + second, "an extend proven unneeded was owed again, and made: $first $second")
    }

    /**
     * B16, the round-9 review's S9-2. A person's own "load more" that fails online is owed, like one
     * asked for offline, so "Try again" — which runs a revalidation — makes it. Each trigger reads
     * the viewport and the extend once, and nothing reads without one. The same screen used to
     * re-read the viewport only and end `live` at 100 rows.
     */
    @Test
    fun b16_aFailedOnlineLoadMoreIsOwedAndMadeByTryAgain() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99); advanceUntilIdle()
        env.server.answerInstead = { e ->
            if (e == "getAlbumList2" && env.server.log.last().parameters["offset"] == "100") status500() else null
        }
        val m0 = env.server.log.size
        handle.loadMore(); advanceUntilIdle()
        assertEquals(listOf<String?>("100"), offsets(env, m0), "fixture: the load more's page answered 500")
        assertEquals("cached(Failed)/100", pubs.last.label(), "the failed load more was not shown as failed")
        val perRetry = (1..2).map {
            val m = env.server.log.size
            handle.refresh(); advanceUntilIdle()
            offsets(env, m)
        }
        val m1 = env.server.log.size
        advanceTimeBy(3_600_000); runCurrent()
        val idle = env.server.log.size - m1
        env.server.answerInstead = { null }
        val m2 = env.server.log.size
        handle.refresh(); advanceUntilIdle()
        val recovered = offsets(env, m2)
        assertEquals(listOf(listOf<String?>("0", "100"), listOf<String?>("0", "100")), perRetry, "Try again did not retry the failed load more, once per trigger")
        assertEquals(0, idle, "reads with no trigger")
        assertEquals(listOf<String?>("0", "100"), recovered)
        assertEquals("Live/200", pubs.last.label())
    }

    /**
     * An owed extend whose page arrives but whose *after* reading fails is not made: the page is
     * not used, the extend stays owed with that failure, and the next trigger makes it.
     */
    /**
     * An extend that is made clears its own earlier failure. A person's "load more" fails online,
     * and their second one succeeds: nothing is owed and nothing failed, so the screen says `live`.
     * Pinned because writing an extend's failure into the window's own, as before round 10, still
     * ends at 200 rows but says `cached(failed)`.
     */
    @Test
    fun aSecondLoadMoreThatSucceedsClearsTheFirstOnesFailureAndEndsLive() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        advanceUntilIdle()
        env.server.answerInstead = { endpoint ->
            if (endpoint == "getAlbumList2" && env.server.log.last().parameters["offset"] == "100") status500() else null
        }
        handle.loadMore()
        advanceUntilIdle()
        assertEquals("cached(Failed)/100", pubs.last.label(), "fixture: the first load more failed")

        env.server.answerInstead = { null }
        val before = env.server.log.size
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("100"), offsets(env, before), "the second load more read the page once")
        assertEquals("Live/200", pubs.last.label())
    }

    @Test
    fun anOwedExtendWhoseAfterReadingFailsIsOwed() = cappedSessionTest { env ->
        val (session, handle, pubs) = owedAppend(env)
        var failed = 0
        env.server.answerInstead = { e ->
            val log = env.server.log
            val previous = log.getOrNull(log.size - 2)
            if (e == "getScanStatus" && failed == 0 && previous?.endpoint == "getAlbumList2" && previous.parameters["offset"] == "100") {
                status500().also { failed++ }
            } else {
                null
            }
        }
        val mark = env.server.log.size
        session.setOnline(true); advanceUntilIdle()
        env.server.answerInstead = { null }
        assertEquals(1, failed, "fixture: the extend's after reading answered 500")
        assertEquals(listOf<String?>("100"), offsets(env, mark), "fixture: the extend's page was read once")
        assertEquals("cached(Failed)/100", pubs.last.label(), "an extend whose after reading failed was taken as made")
        val retry = env.server.log.size
        handle.refresh(); advanceUntilIdle()
        assertEquals(listOf<String?>("0", "100"), offsets(env, retry), "the extend was dropped, not owed")
        assertEquals("Live/200", pubs.last.label())
    }
}
