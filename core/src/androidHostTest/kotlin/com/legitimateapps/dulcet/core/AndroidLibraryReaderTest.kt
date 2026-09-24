package com.legitimateapps.dulcet.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Android composition root over the production [LibraryReaderSession], with the in-process test
 * server as its transport. The main dispatcher is `Unconfined`, so a listener runs on the reader's
 * thread at the moment of emission: the request count it records is exact, which is what makes
 * "before any request" (CONF-76) provable here rather than only plausible.
 */
class AndroidLibraryReaderTest {
    private val driver = createTestDriver()
    private val database = DulcetDatabaseStore.open(driver)
    private val store = SeenCacheStore(database, ManualWallClock(now = 2_000_000))
    private val server = SessionTestServer()
    private val readers = mutableListOf<AndroidLibraryReader>()

    private fun reader(main: CoroutineDispatcher = Dispatchers.Unconfined): AndroidLibraryReader = AndroidLibraryReader(
        account = null,
        compose = { scope ->
            AndroidLibraryReaderComposition(
                LibraryReaderSession(database.database, store.bind(SessionEnv.BINDING), server, scope,
                    LibraryReaderConfig(lookAheadMaxPerViewport = 0)),
            )
        },
        readerDispatcher = newLibraryReaderDispatcher(),
        mainDispatcher = main,
    ).also { readers += it }

    private fun AndroidLibraryReader.closeAndWait() = runBlocking {
        close()
        withTimeout(30_000) { awaitTermination() }
    }

    @AfterTest
    fun tearDown() {
        readers.forEach { it.closeAndWait() }
        driver.close()
    }

    /** Every publication with the number of requests the server had received when it was delivered. */
    private class Seen {
        val all: MutableList<Pair<AndroidLibraryPublication, Int>> = Collections.synchronizedList(mutableListOf())
        val live = CountDownLatch(1)
    }

    private fun AndroidLibraryReader.openRecording(row: AndroidLibraryHomeRow, seen: Seen): AndroidLibraryWindow =
        openHomeRow(row) { publication ->
            seen.all += publication to server.log.size
            if (publication.freshness == AndroidLibraryFreshness.Live) seen.live.countDown()
        }

    @Test
    fun conf76ARelaunchPublishesTheCacheBeforeAnyRequestAndNeverALoadingState() {
        val newest = AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest)
        val first = reader()
        val firstRun = Seen()
        first.openRecording(newest, firstRun)
        assertTrue(firstRun.live.await(30, TimeUnit.SECONDS), "the first launch reads the row live")
        val liveItems = firstRun.all.last().first.items
        assertTrue(liveItems.isNotEmpty(), "control: the row has content to cache")
        assertEquals(AndroidLibraryFreshness.Loading, firstRun.all.first().first.freshness,
            "control: with nothing cached, the first launch does show loading")
        first.closeAndWait()

        // A relaunch: a new reader, a new thread, the same database.
        val before = server.log.size
        val second = reader()
        val relaunch = Seen()
        second.openRecording(newest, relaunch)
        assertTrue(relaunch.live.await(30, TimeUnit.SECONDS))
        val (publication, requestsAtDelivery) = relaunch.all.first()
        assertIs<AndroidLibraryFreshness.Cached>(publication.freshness)
        assertEquals(liveItems.map { it.rawId }, publication.items.map { it.rawId })
        assertEquals(before, requestsAtDelivery, "the cached content was delivered before any request was issued")
        assertTrue(relaunch.all.none { it.first.freshness == AndroidLibraryFreshness.Loading }, "no loading state")
        assertTrue(server.log.size > before, "control: the relaunch did then read the server")
        assertEquals(relaunch.all.indices.map { it + 1 }, relaunch.all.map { it.first.sequence }, "sequences are dense")
    }

    @Test
    fun conf86AFailingRowLeavesTheOtherLive() {
        server.failWithCode["getStarred2"] = 0
        val reader = reader()
        val newest = Seen()
        val favourites = Seen()
        reader.openRecording(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest), newest)
        val failed = CountDownLatch(1)
        reader.openHomeRow(AndroidLibraryHomeRow.Favourites) { publication ->
            favourites.all += publication to server.log.size
            if (publication.freshness is AndroidLibraryFreshness.Unavailable) failed.countDown()
        }
        assertTrue(newest.live.await(30, TimeUnit.SECONDS), "the newest row is live")
        assertTrue(failed.await(30, TimeUnit.SECONDS), "the favourites row fails on its own")
        val reason = (favourites.all.last().first.freshness as AndroidLibraryFreshness.Unavailable).reason
        assertIs<AndroidLibraryUnavailableReason.Failed>(reason)
        assertEquals(AndroidLibraryFreshness.Live, newest.all.last().first.freshness)
        assertTrue(server.count("getStarred2") >= 1, "control: the failing row did ask")
    }

    @Test
    fun closeStopsTheThreadAndNothingIsDeliveredAfterItReturns() {
        server.holdBeforeApply += "getAlbumList2"
        val reader = reader()
        val seen = Seen()
        reader.openRecording(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest), seen)
        val opened = CountDownLatch(1)
        reader.openWindow(AndroidLibraryQuery.Artists()) { opened.countDown() }
        assertTrue(opened.await(30, TimeUnit.SECONDS))
        val delivered = seen.all.size
        assertTrue(delivered >= 1, "control: the row published before the close")
        val completed = CountDownLatch(1)
        reader.close { completed.countDown() }
        runBlocking { withTimeout(30_000) { reader.awaitTermination() } }
        assertTrue(completed.await(30, TimeUnit.SECONDS), "the completion runs once the thread has stopped")
        assertEquals(delivered, seen.all.size, "nothing is delivered after close returns")
        assertEquals(emptyList(), reader.uncaughtFailures.toList())
        // A window opened on the closed reader says so, once.
        val closed = mutableListOf<AndroidLibraryPublication>()
        reader.openWindow(AndroidLibraryQuery.Artists()) { closed += it }
        assertEquals(listOf<AndroidLibraryFreshness>(AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.Closed)),
            closed.map { it.freshness })
    }

    @Test
    fun aViewportIsCarriedToTheLatestPublicationByIdentity() {
        fun album(id: String) = AndroidLibraryItem.Album(id, id, null, null, null, null, null, null, null, null, null, null, false)
        fun publication(sequence: Int, vararg ids: String) = AndroidLibraryPublication(
            sequence, AndroidLibraryFreshness.Live, AndroidLibraryCoverage.Complete, ids.size, 0, null,
            ids.map(::album), AndroidLibraryItemsState.Present, AndroidLibraryItemsOrder.Server, null, null,
        )
        val seen = publication(1, "a", "b", "c", "d")
        val range = SeenRange(1, "Album\u0000b", "Album\u0000c")
        // Two rows were inserted above: the same items are now two further down.
        assertEquals(3 to 4, translateRange(1, 2, range, publication(2, "x", "y", "a", "b", "c", "d")))
        // Unchanged publication: the indexes stand.
        assertEquals(1 to 2, translateRange(1, 2, range, seen))
        // The list shrank past the range: clamped to what exists.
        assertEquals(0 to 0, translateRange(5, 9, SeenRange(1, "Album\u0000z", null), publication(3, "q")))
    }
}
