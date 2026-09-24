package com.legitimateapps.dulcet.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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

    private fun session(scope: kotlinx.coroutines.CoroutineScope) = LibraryReaderSession(
        database.database, store.bind(SessionEnv.BINDING), server, scope, LibraryReaderConfig(lookAheadMaxPerViewport = 0),
    )

    private fun reader(main: CoroutineDispatcher = Dispatchers.Unconfined): AndroidLibraryReader = AndroidLibraryReader(
        account = null,
        compose = { scope -> AndroidLibraryReaderComposition(session(scope)) },
        readerDispatcher = newLibraryReaderDispatcher(),
        mainDispatcher = main,
    ).also { readers += it }

    /** A main thread that runs nothing until the test drains it: a publication can be caught queued. */
    private class QueuedMain : CoroutineDispatcher() {
        val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.put(block)
        }
        fun awaitQueued() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (queue.isEmpty()) {
                check(System.nanoTime() < deadline) { "nothing was queued for the main thread" }
                Thread.sleep(5)
            }
        }
        fun drain() {
            while (true) (queue.poll() ?: return).run()
        }
    }

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
    fun closeWaitsForCancelledWorkToUnwindBeforeReleasingAndForTheThreadToStop() {
        val released = AtomicBoolean(false)
        val releasedWhenCleanupRan = AtomicReference<Boolean?>(null)
        val running = CountDownLatch(1)
        val dispatcher = newLibraryReaderDispatcher()
        val reader = AndroidLibraryReader(
            account = null,
            compose = { scope ->
                // Work of the reader's own, in flight at the close, whose cleanup takes a while.
                scope.launch {
                    try {
                        running.countDown()
                        awaitCancellation()
                    } finally {
                        Thread.sleep(300)
                        releasedWhenCleanupRan.set(released.get())
                    }
                }
                AndroidLibraryReaderComposition(session(scope), release = { released.set(true) })
            },
            readerDispatcher = dispatcher,
            mainDispatcher = Dispatchers.Unconfined,
        ).also { readers += it }
        assertTrue(running.await(30, TimeUnit.SECONDS), "control: the work was running when the close began")
        reader.closeAndWait()
        assertEquals(false, releasedWhenCleanupRan.get(), "the cancelled work finished unwinding before the store was released")
        assertTrue(released.get(), "the store was released")
        val executor = (dispatcher as ExecutorCoroutineDispatcher).executor as ExecutorService
        assertTrue(executor.isTerminated, "once close has completed, no task can run on the reader's thread")
    }

    @Test
    fun aPublicationQueuedForTheMainThreadIsNotDeliveredAfterACloseThatRanFirst() {
        val main = QueuedMain()
        val reader = reader(main)
        val first = Collections.synchronizedList(mutableListOf<AndroidLibraryPublication>())
        val window = reader.openHomeRow(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest)) { first += it }
        main.awaitQueued()
        window.close()
        main.drain()
        assertEquals(emptyList(), first.toList(), "a window closed before delivery receives nothing")

        val second = Collections.synchronizedList(mutableListOf<AndroidLibraryPublication>())
        reader.openHomeRow(AndroidLibraryHomeRow.Favourites) { second += it }
        main.awaitQueued()
        var completed = false
        reader.close { completed = true }
        runBlocking { withTimeout(30_000) { reader.awaitTermination() } }
        main.drain()
        assertEquals(emptyList(), second.toList(), "a reader closed before delivery delivers nothing")
        assertTrue(completed, "control: the close's own completion was delivered by the same drain")
    }

    @Test
    fun aReaderWhoseSetupFailedIsReplacedAndItsReplacementWaitsForItsThreadToStop() {
        val account = AndroidLibraryReaderAccount("provider", "http://127.0.0.1:1", "user", "password", true)
        val failingDispatcher = newLibraryReaderDispatcher()
        val failing = AndroidLibraryReader.obtain(account) { previous ->
            AndroidLibraryReader(account, { error("setup failed") }, failingDispatcher, Dispatchers.Unconfined, previous)
        }.also { readers += it }
        val failed = CountDownLatch(1)
        failing.openWindow(AndroidLibraryQuery.Artists()) { if (it.freshness is AndroidLibraryFreshness.Unavailable) failed.countDown() }
        assertTrue(failed.await(30, TimeUnit.SECONDS), "control: the failed setup answers a screen with a failure")

        // Keep the failing reader's thread busy, so its close cannot finish until the gate opens.
        val gate = CountDownLatch(1)
        (failingDispatcher as ExecutorCoroutineDispatcher).executor.execute { gate.await(30, TimeUnit.SECONDS) }
        val composedAfterPredecessorStopped = AtomicReference<Boolean?>(null)
        val replacement = AndroidLibraryReader.obtain(account) { previous ->
            assertSame(failing, previous, "the failed reader is the one replaced")
            AndroidLibraryReader(account, { scope ->
                composedAfterPredecessorStopped.set(previous!!.hasTerminated)
                AndroidLibraryReaderComposition(session(scope))
            }, newLibraryReaderDispatcher(), Dispatchers.Unconfined, previous)
        }.also { readers += it }
        assertNotSame(failing, replacement, "a reader whose setup failed is not kept")
        Thread.sleep(300)
        assertEquals(null, composedAfterPredecessorStopped.get(), "the replacement did not open the database while its predecessor ran")
        gate.countDown()
        val live = CountDownLatch(1)
        replacement.openHomeRow(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest)) {
            if (it.freshness == AndroidLibraryFreshness.Live) live.countDown()
        }
        assertTrue(live.await(30, TimeUnit.SECONDS), "the replacement works")
        assertEquals(true, composedAfterPredecessorStopped.get(), "it composed only once its predecessor's thread had stopped")
        assertSame(replacement, AndroidLibraryReader.obtain(account) { error("a working reader is not rebuilt") })
        assertFalse(replacement.isClosed)
        runBlocking { withTimeout(30_000) { replacement.close(); replacement.awaitTermination() } }
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
