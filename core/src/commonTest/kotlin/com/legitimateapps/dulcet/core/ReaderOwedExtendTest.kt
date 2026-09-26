package com.legitimateapps.dulcet.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owed extends — a "load more" the reader could not make — after the round-8 review (§16.14, §28
 * item 29). An owed extend leaves the owed list only once it is made or proven unneeded: never on a
 * failed read, never on a window this session has not yet read live. A reconnect that makes one ends
 * `live`. It is never made twice, never owed forever, and never made for a closed screen. The
 * reviewer's probes B1–B5, as tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderOwedExtendTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    private fun LibraryFreshness.label(): String =
        (this as? LibraryFreshness.Cached)?.reason?.let { "cached(" + it::class.simpleName + ")" } ?: this::class.simpleName.orEmpty()

    private fun offsets(env: SessionEnv, from: Int) =
        env.server.log.drop(from).filter { it.endpoint == "getAlbumList2" }.map { it.parameters["offset"] }

    private fun status500() = LibraryEndpointResponse(500, "<html>no</html>", "http://fixture.invalid/rest")

    /**
     * B1. A relaunched session — its grid cached at 100 of 250 rows, never read live in this session
     * — owes a "load more" asked for offline. The reconnect's read of the grid is answered with a bare
     * 500. The extend is not made, and is owed: the screen says the read failed, never `live` at 100
     * rows, and the same page is not re-read at once. It used to re-read offset 0 back to back, drop
     * the extend as if made, and end `live` at 100 of 250 with offset 100 never read, even after a
     * refresh. The next reconnect makes it.
     */
    @Test
    fun b1_anOwedLoadMoreWhoseWindowsFirstLiveReadFailsIsOwedNotDropped() = cappedSessionTest { env ->
        val first = env.session()
        first.reader.connect()
        first.reader.open(grid) {}.also { advanceUntilIdle() }.close()

        val session = env.session()
        session.setOnline(false)
        advanceUntilIdle()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(100, pubs.last.items.size, "fixture: 100 cached rows, the load more owed")
        var answered = 0
        env.server.answerInstead = { endpoint -> if (endpoint == "getAlbumList2" && answered == 0) status500().also { answered++ } else null }
        val mark = env.server.log.size
        session.setOnline(true)
        advanceUntilIdle()
        env.server.answerInstead = { null }
        assertEquals(1, answered, "fixture: the reconnect's read of the grid met the 500")
        assertTrue(session.reader.online, "fixture: the reconnect completed")
        assertEquals(listOf<String?>("0"), offsets(env, mark), "the failed page was re-read at once, or the extend made unread")
        assertEquals("cached(Failed)/100", pubs.last.freshness.label() + "/" + pubs.last.items.size, "the screen did not say its read failed")

        session.setOnline(false)
        advanceUntilIdle()
        val again = env.server.log.size
        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(listOf<String?>("0", "100"), offsets(env, again), "the owed load more was dropped with the failed read")
        assertEquals(200, pubs.last.items.size)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    /**
     * The same window, online: a "load more" on a window this session has not read live reads the
     * window first (tear rule 2), then extends it — one request each, in that order. It used to read
     * the window and stop, so the person's "load more" did nothing.
     */
    @Test
    fun aLoadMoreOnAWindowNotYetReadLiveReadsItThenExtends() = cappedSessionTest { env ->
        val first = env.session()
        first.reader.connect()
        first.reader.open(grid) {}.also { advanceUntilIdle() }.close()

        val session = env.session()
        session.setOnline(false)
        advanceUntilIdle()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        var answered = 0
        env.server.answerInstead = { endpoint -> if (endpoint == "getAlbumList2" && answered == 0) status500().also { answered++ } else null }
        session.setOnline(true)
        advanceUntilIdle()
        env.server.answerInstead = { null }
        assertEquals(1, answered, "fixture: the reconnect's read of the grid met the 500")
        assertTrue(session.reader.online, "fixture: online")
        assertEquals("cached(Failed)", pubs.last.freshness.label(), "fixture: the grid was not read live")
        val mark = env.server.log.size
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(listOf<String?>("0", "100"), offsets(env, mark), "the load more read the window and stopped")
        assertEquals(200, pubs.last.items.size)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    /**
     * B2. The owed extend's page lands and the confirming read's write fails, so the extend is owed
     * again. The next revalidation reads on from the page that landed and never fetches it twice.
     */
    @Test
    fun b2_aReOwedExtendWhosePageLandedIsNotFetchedTwice() = cappedSessionTest { env ->
        env.server.base.sendTotalCount = false
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(200, pubs.last.items.size, "fixture: 200 rows")
        handle.setViewport(190, 199)
        session.setOnline(false)
        advanceUntilIdle()
        handle.loadMore()
        advanceUntilIdle()
        var armed = false
        var fired = 0
        env.server.base.beforeRespond = { r -> if (r.endpoint == "getAlbumList2" && r.parameters["offset"] == "250") armed = true }
        env.driver.failWrite = { sql ->
            val s = sql.trimStart().uppercase()
            val hit = armed && fired == 0 && sql.contains("cache_list", ignoreCase = true) &&
                (s.startsWith("INSERT") || s.startsWith("UPDATE") || s.startsWith("DELETE"))
            if (hit) fired++
            hit
        }
        val mark = env.server.log.size
        session.setOnline(true)
        advanceUntilIdle()
        env.driver.failWrite = null
        env.server.base.beforeRespond = {}
        val during = offsets(env, mark)
        val retry = env.server.log.size
        handle.refresh()
        advanceUntilIdle()
        val after = offsets(env, retry)
        val ids = pubs.last.items.map { it.rawId }
        assertEquals(1, fired, "fixture: the confirming read's write failure never fired")
        assertEquals(1, (during + after).count { it == "200" }, "the page that landed was fetched again: $during / $after")
        assertEquals(250, ids.size)
        assertEquals(250, ids.toSet().size)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    /** B3. A screen closed while it owes an extend: nothing is read for it, now or at a later reconnect. */
    @Test
    fun b3_aClosedScreenOwesNothing() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val handle = session.reader.open(grid) {}
        advanceUntilIdle()
        handle.setViewport(90, 99)
        session.setOnline(false)
        advanceUntilIdle()
        handle.loadMore()
        handle.close()
        val mark = env.server.log.size
        session.setOnline(true)
        advanceUntilIdle()
        val reopened = session.reader.open(grid) {}
        advanceUntilIdle()
        session.setOnline(false); advanceUntilIdle(); session.setOnline(true); advanceUntilIdle()
        val all = offsets(env, mark)
        reopened.close()
        assertTrue("100" !in all, "a closed screen's owed extend was made: $all")
        assertTrue(session.reader.online)
    }

    /**
     * B4, the round-8 review's S8-2. The owed extend's own page write fails once, so it is owed again
     * and the screen says `internalFailure`. The next reconnect — not a refresh — makes it, publishes
     * no `live` before it is requested (round-6 decision 3), and ends `live` at 200 rows. The page
     * write landing is the screen's success; it used to end `cached(internalFailure)` at 200 rows.
     */
    @Test
    fun b4_theReconnectThatMakesAReOwedExtendEndsLive() = cappedSessionTest { env ->
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
        var writes = 0
        env.driver.failWrite = { sql -> sql.contains("cache_list_member", ignoreCase = true) && sql.trimStart().startsWith("INSERT", ignoreCase = true) && writes++ == 0 }
        session.setOnline(true)
        advanceUntilIdle()
        env.driver.failWrite = null
        assertEquals(1, writes, "fixture: the owed extend's write was attempted once, and failed")
        assertEquals("cached(InternalFailure)", pubs.last.freshness.label(), "fixture: the owed extend's write failed")
        session.setOnline(false)
        advanceUntilIdle()
        val mark = env.server.log.size
        val pubMark = pubs.all.size
        session.setOnline(true)
        advanceUntilIdle()
        val extendAt = env.server.log.withIndex().drop(mark).firstOrNull { it.value.endpoint == "getAlbumList2" && it.value.parameters["offset"] == "100" }?.index
        val seen = pubs.all.drop(pubMark).map { it.value.freshness.label() + "/" + it.value.items.size + "@" + it.requestsIssued }
        assertTrue(extendAt != null, "the re-owed extend was not made by the next reconnect: $seen")
        val earlyLive = pubs.all.drop(pubMark).firstOrNull { it.value.freshness == LibraryFreshness.Live && it.requestsIssued <= extendAt }
        assertEquals(null, earlyLive, "live published before the owed extend was requested: $seen")
        assertEquals(200, pubs.last.items.size)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "the reconnect that made the extend did not end live: $seen")
    }

    /** B5. An extend owed on a complete window is dropped, not owed forever: no read, and no `revalidating` for it. */
    @Test
    fun b5_anOwedExtendPastTheEndIsNotOwedForever() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99); handle.loadMore(); advanceUntilIdle()
        handle.setViewport(190, 199); handle.loadMore(); advanceUntilIdle()
        handle.setViewport(240, 249)
        assertEquals(250, pubs.last.items.size, "fixture: complete")
        session.setOnline(false); advanceUntilIdle()
        handle.loadMore(); advanceUntilIdle()
        val mark = env.server.log.size
        session.setOnline(true); advanceUntilIdle()
        val first = offsets(env, mark)
        session.setOnline(false); advanceUntilIdle()
        val pubMark = pubs.all.size
        val mark2 = env.server.log.size
        session.setOnline(true); advanceUntilIdle()
        val second = offsets(env, mark2)
        val labels = pubs.all.drop(pubMark).map { it.value.freshness.label() }
        assertTrue("250" !in first + second, "read past the end: $first $second")
        assertTrue("cached(Revalidating)" !in labels, "a dropped extend still said a read was coming: $labels")
    }
}
