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
import kotlin.test.assertNotNull
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidLibraryReaderTest {
    private val driver = createTestDriver()
    private val database = DulcetDatabaseStore.open(driver)
    private val store = SeenCacheStore(database, ManualWallClock(now = 2_000_000))
    private val server = SessionTestServer()
    private val readers = mutableListOf<AndroidLibraryReader>()

    private fun session(
        scope: kotlinx.coroutines.CoroutineScope,
        foreground: Boolean,
        config: LibraryReaderConfig = LibraryReaderConfig(lookAheadMaxPerViewport = 0),
    ) = LibraryReaderSession(
        database.database, store.bind(SessionEnv.BINDING), server, scope, config, formPost = false, foreground = foreground,
    )

    private fun reader(main: CoroutineDispatcher = Dispatchers.Unconfined): AndroidLibraryReader = AndroidLibraryReader(
        account = null,
        compose = { scope, foreground -> AndroidLibraryReaderComposition(session(scope, foreground)) },
        readerDispatcher = newLibraryReaderDispatcher(),
        mainDispatcher = main,
        initiallyForeground = false,
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

        /** Runs what is queued until [done] holds, for work queued from another thread later. */
        fun drainUntil(what: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!done()) {
                check(System.nanoTime() < deadline) { "$what was never delivered to the main thread" }
                drain()
                Thread.sleep(5)
            }
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
            compose = { scope, foreground ->
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
                AndroidLibraryReaderComposition(session(scope, foreground), release = { released.set(true) })
            },
            readerDispatcher = dispatcher,
            mainDispatcher = Dispatchers.Unconfined,
            initiallyForeground = false,
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
        // The close's completion is queued from another thread once the reader's has stopped; the
        // publication queued before the close is drained with it, and must not be delivered.
        main.drainUntil("the close's completion") { completed }
        main.drain()
        assertEquals(emptyList(), second.toList(), "a reader closed before delivery delivers nothing")
    }

    @Test
    fun aReaderWhoseSetupFailedTriesAgainAtItsNextCallAndOpensTheScreensThatWereWaiting() {
        val account = AndroidLibraryReaderAccount("provider", "http://127.0.0.1:1", "user", "password", true)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val reader = AndroidLibraryReader.obtain(account) { previous ->
            AndroidLibraryReader(account, { scope, foreground ->
                // The database cannot be opened at creation nor at the first call (a full disk, say);
                // the third time it can.
                if (attempts.incrementAndGet() <= 2) error("setup failed")
                AndroidLibraryReaderComposition(session(scope, foreground))
            }, newLibraryReaderDispatcher(), Dispatchers.Unconfined, previous, initiallyForeground = false)
        }.also { readers += it }
        val waiting = Collections.synchronizedList(mutableListOf<AndroidLibraryPublication>())
        reader.openWindow(AndroidLibraryQuery.Artists()) { waiting += it }
        pollUntil("the first screen to be told setup failed") { waiting.isNotEmpty() }
        assertEquals(AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.InternalFailure), waiting.first().freshness)
        assertEquals(2, attempts.get(), "control: setup was attempted at creation and again at the first call")

        // Platform reports while setup fails are recorded, not retried: the next call's setup applies them.
        reader.setOnline(false)
        reader.setForeground(true)
        val barrier = CountDownLatch(1)
        reader.onReader { barrier.countDown() }
        assertTrue(barrier.await(30, TimeUnit.SECONDS))
        assertEquals(2, attempts.get(), "the platform reports did not retry setup")
        val before = server.log.size
        val row = Collections.synchronizedList(mutableListOf<AndroidLibraryPublication>())
        reader.openHomeRow(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest)) { row += it }
        pollUntil("the row to publish") { row.isNotEmpty() }
        assertEquals(3, attempts.get(), "the next call built the session")
        assertEquals(AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.NotCachedOffline), row.first().freshness,
            "the offline report made while setup failed applies to the session built later")
        pollUntil("the waiting screen to be opened") { waiting.size >= 2 }
        assertEquals(AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.NotCachedOffline), waiting.last().freshness,
            "the screen opened while setup failed was opened with it, offline like the rest")
        assertEquals(before, server.log.size, "an offline reader requested nothing")

        // Back online by the one way there is: both screens, the waiting one included, read live.
        val connected = LinkedBlockingQueue<AndroidLibraryConnection>()
        reader.reconnect { connected.put(it) }
        assertEquals(true, connected.poll(30, TimeUnit.SECONDS)?.epochRead, "the recovered reader reconnects")
        pollUntil("both screens live") {
            row.last().freshness == AndroidLibraryFreshness.Live && waiting.last().freshness == AndroidLibraryFreshness.Live
        }
        assertEquals(3, attempts.get(), "setup is not attempted again once it has succeeded")
        assertSame(reader, AndroidLibraryReader.obtain(account) { error("the recovered reader is not replaced") })
    }

    @Test
    fun aSearchOpenedLateAppliesTheQueryTypedWhileSetupFailedBeforeAnyTypedAfterIt() {
        val account = AndroidLibraryReaderAccount("provider", "http://127.0.0.1:1", "user", "password", true)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val reader = AndroidLibraryReader.obtain(account) { previous ->
            AndroidLibraryReader(account, { scope, foreground ->
                if (attempts.incrementAndGet() <= 2) error("setup failed")
                AndroidLibraryReaderComposition(session(scope, foreground))
            }, newLibraryReaderDispatcher(), Dispatchers.Unconfined, previous, initiallyForeground = false)
        }.also { readers += it }
        reader.setOnline(false) // Recorded, and applied at setup: the searches below answer from this device alone.
        val published = Collections.synchronizedList(mutableListOf<AndroidLibrarySearchPublication>())
        val search = reader.openSearch { published += it }
        search.updateQuery("older")
        val typed = CountDownLatch(1)
        reader.onReader { typed.countDown() }
        assertTrue(typed.await(30, TimeUnit.SECONDS))
        assertEquals(2, attempts.get(), "control: setup failed at creation and at the search's opening")
        assertEquals(listOf(AndroidLibrarySearchScope.ReaderFailed), published.snapshot().map { it.scope },
            "control: the search was told setup failed, and nothing applied the query typed meanwhile")

        // The next call builds the session and opens the search late. A query typed right after it
        // is queued behind that call; held until both are queued, so the order is fixed.
        val gate = CountDownLatch(1)
        reader.onReader { gate.await(30, TimeUnit.SECONDS) }
        reader.openWindow(AndroidLibraryQuery.Artists()) {}
        search.updateQuery("newer")
        val settled = CountDownLatch(1)
        reader.onReader { settled.countDown() }
        gate.countDown()
        assertTrue(settled.await(30, TimeUnit.SECONDS))

        assertEquals(3, attempts.get(), "the window's call built the session")
        val queries = published.snapshot().map { it.query }
        assertEquals(listOf("older", "newer"), queries.filter { it == "older" || it == "newer" }.distinct(),
            "the late opening applied the query typed while setup failed, and the one typed after it superseded it: $queries")
        assertEquals("newer", queries.last())
        // Publications are delivered as they are made (main is Unconfined), so this is exact.
        assertTrue(published.snapshot().drop(1).all { it.scope is AndroidLibrarySearchScope.DeviceOffline },
            "the offline report made while setup failed applied to the search opened late: ${published.snapshot().map { it.scope }}")
    }

    @Test
    fun aReaderClosedWhileWaitingForItsPredecessorNeverOpensTheDatabaseAndTerminatesOnlyAfterIt() {
        val first = AndroidLibraryReaderAccount("provider", "http://127.0.0.1:1", "user", "password", true)
        val second = AndroidLibraryReaderAccount("provider", "http://127.0.0.1:1", "someone-else", "password", true)
        val firstDispatcher = newLibraryReaderDispatcher()
        val firstReader = AndroidLibraryReader.obtain(first) { previous ->
            AndroidLibraryReader(first, { scope, foreground -> AndroidLibraryReaderComposition(session(scope, foreground)) },
                firstDispatcher, Dispatchers.Unconfined, previous, initiallyForeground = false)
        }.also { readers += it }
        val opened = CountDownLatch(1)
        firstReader.openWindow(AndroidLibraryQuery.Artists()) { opened.countDown() }
        assertTrue(opened.await(30, TimeUnit.SECONDS))
        val gate = CountDownLatch(1)
        (firstDispatcher as ExecutorCoroutineDispatcher).executor.execute { gate.await(60, TimeUnit.SECONDS) }
        val composed = AtomicBoolean(false)
        val secondDispatcher = newLibraryReaderDispatcher()
        val secondReader: AndroidLibraryReader
        try {
            secondReader = AndroidLibraryReader.obtain(second) { previous ->
                assertSame(firstReader, previous)
                AndroidLibraryReader(second, { scope, foreground -> composed.set(true); AndroidLibraryReaderComposition(session(scope, foreground)) },
                    secondDispatcher, Dispatchers.Unconfined, previous, initiallyForeground = false)
            }.also { readers += it }
            Thread.sleep(200)
            assertFalse(firstReader.hasTerminated, "control: the predecessor is still running")
            secondReader.close()
            // It stops waiting at once: its own thread stops while the predecessor's is still held...
            val executor = (secondDispatcher as ExecutorCoroutineDispatcher).executor as ExecutorService
            pollUntil("the closed reader's thread to stop") { executor.isTerminated }
            Thread.sleep(200)
            // ...but it is not terminated while the predecessor is not, so whoever waits for it (the
            // next reader, a sign-out) also waits for the predecessor.
            assertFalse(firstReader.hasTerminated, "control: the predecessor is still running")
            assertFalse(secondReader.hasTerminated, "a reader is not terminated before the reader it replaced")
        } finally {
            gate.countDown()
        }
        pollUntil("the predecessor, then the closed reader, to terminate") { secondReader.hasTerminated }
        assertTrue(firstReader.hasTerminated)
        assertFalse(composed.get(), "a reader closed before it was built never opened the database")
    }

    /**
     * The facade passes the foreground state it holds to the session's construction (§16.14): a
     * reader created in the foreground retries a reconnect whose epoch read failed transiently with
     * no [AndroidLibraryReader.setForeground] call at all. One created in the background, the
     * control, reads once and no more.
     */
    @Test
    fun aReaderCreatedInTheForegroundRetriesATransientFailureWithNoOtherReport() {
        val fast = LibraryReaderConfig(lookAheadMaxPerViewport = 0, reconnectRetryInitialMillis = 50)
        for (createdInForeground in listOf(false, true)) {
            val sessions = LinkedBlockingQueue<LibraryReaderSession>()
            val reader = AndroidLibraryReader(
                account = null,
                compose = { scope, foreground -> AndroidLibraryReaderComposition(session(scope, foreground, fast).also(sessions::put)) },
                readerDispatcher = newLibraryReaderDispatcher(),
                mainDispatcher = Dispatchers.Unconfined,
                initiallyForeground = createdInForeground,
            ).also { readers += it }
            val session = assertNotNull(sessions.poll(30, TimeUnit.SECONDS), "the session was built")
            fun <T> onReader(read: () -> T): T {
                val answer = LinkedBlockingQueue<Result<T>>()
                reader.onReader { answer.put(runCatching(read)) }
                return assertNotNull(answer.poll(30, TimeUnit.SECONDS), "the reader's thread answered").getOrThrow()
            }
            fun reads() = onReader { server.count("getScanStatus") }

            val connected = LinkedBlockingQueue<AndroidLibraryConnection>()
            reader.reconnect { connected.put(it) }
            assertEquals(true, connected.poll(30, TimeUnit.SECONDS)?.epochRead, "control: the reader connects")
            reader.setOnline(false)
            pollUntil("the reader offline") { !onReader { session.reader.online } }
            onReader { server.failWithError["getScanStatus"] = DomainError.Transport.Timeout }
            val before = reads()
            // Reachable while offline: the reader starts a reconnect itself, and its epoch read fails
            // transiently. Nothing here reports the foreground, in either pass.
            reader.setOnline(true)
            if (createdInForeground) {
                pollUntil("the retries of a reader created in the foreground") { reads() - before >= 3 }
                onReader { server.failWithError.clear() }
                pollUntil("a retry to bring the reader back") { onReader { session.reader.online } }
            } else {
                pollUntil("the report's own reconnect") { reads() - before >= 1 }
                Thread.sleep(600) // twelve times the first wait: 50 + 100 + 200 ms would be three retries
                assertEquals(1, reads() - before, "a reader created in the background retried")
                assertFalse(onReader { session.reader.online }, "control: the failed read left it offline")
                onReader { server.failWithError.clear() }
            }
            reader.closeAndWait()
        }
    }

    /** A change the flush kept unsent maps to held, with its error, never to another outcome. */
    @Test
    fun aHeldOutcomeMapsToHeldWithItsError() {
        val album = LibraryEntityRef(LibraryEntityKind.Album, "album-1")
        val busy = DomainError.Server.Busy(retryAfter = null)
        assertEquals(
            AndroidLibraryChangeOutcome.Held(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "album-1"), AndroidLibraryChangeField.Favourite, busy),
            MutationOutcome.Held(album, MutationField.Starred, busy).toAndroid(),
        )
        val refused = DomainError.Auth.InvalidCredentials
        assertEquals(
            AndroidLibraryChangeOutcome.Held(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "album-1"), AndroidLibraryChangeField.Rating, refused),
            MutationOutcome.Held(album, MutationField.Rating, refused).toAndroid(),
        )
    }

    /** A copy taken under the list's lock: the reader's thread appends while the test reads. */
    private fun <T> MutableList<T>.snapshot(): List<T> = synchronized(this) { toList() }

    private fun pollUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    @Test
    fun aNewReaderOpensTheDatabaseOnlyOnceThePreviousReadersThreadHasStopped() {
        val first = AndroidLibraryReaderAccount("provider", "http://127.0.0.1:1", "user", "password", true)
        val second = AndroidLibraryReaderAccount("provider", "http://127.0.0.1:1", "someone-else", "password", true)
        fun build(account: AndroidLibraryReaderAccount, dispatcher: kotlinx.coroutines.CloseableCoroutineDispatcher, previous: AndroidLibraryReader?,
                  composedAfterPredecessor: AtomicReference<Boolean?>) =
            AndroidLibraryReader(account, { scope, foreground ->
                composedAfterPredecessor.set(previous?.hasTerminated ?: true)
                AndroidLibraryReaderComposition(session(scope, foreground))
            }, dispatcher, Dispatchers.Unconfined, previous, initiallyForeground = false).also { readers += it }
        fun AndroidLibraryReader.awaitLive() {
            val live = CountDownLatch(1)
            openHomeRow(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest)) {
                if (it.freshness == AndroidLibraryFreshness.Live) live.countDown()
            }
            assertTrue(live.await(30, TimeUnit.SECONDS), "the reader works")
        }
        /** Keeps a reader's thread busy, so its close cannot finish until the returned gate opens. */
        fun hold(dispatcher: CoroutineDispatcher) = CountDownLatch(1).also { gate ->
            (dispatcher as ExecutorCoroutineDispatcher).executor.execute { gate.await(30, TimeUnit.SECONDS) }
        }

        val firstDispatcher = newLibraryReaderDispatcher()
        val firstReader = AndroidLibraryReader.obtain(first) { previous -> build(first, firstDispatcher, previous, AtomicReference()) }
        firstReader.awaitLive()

        // The account changes: the new reader receives the old one, which is closed here.
        val firstGate = hold(firstDispatcher)
        val secondDispatcher = newLibraryReaderDispatcher()
        val secondComposed = AtomicReference<Boolean?>(null)
        val secondReader = AndroidLibraryReader.obtain(second) { previous ->
            assertSame(firstReader, previous, "the replaced reader is handed over")
            build(second, secondDispatcher, previous, secondComposed)
        }
        assertTrue(firstReader.isClosed)
        Thread.sleep(300)
        assertEquals(null, secondComposed.get(), "the new reader did not open the database while the old one's thread ran")
        firstGate.countDown()
        secondReader.awaitLive()
        assertEquals(true, secondComposed.get(), "it opened the database only once the old one's thread had stopped")

        // A reader closed on its own (a sign-out) is still waited for by the next one to be created.
        val secondGate = hold(secondDispatcher)
        AndroidLibraryReader.closeCurrent()
        val thirdComposed = AtomicReference<Boolean?>(null)
        val thirdReader = AndroidLibraryReader.obtain(first) { previous ->
            assertSame(secondReader, previous, "the reader still closing is handed over")
            build(first, newLibraryReaderDispatcher(), previous, thirdComposed)
        }
        Thread.sleep(300)
        assertEquals(null, thirdComposed.get(), "the next reader did not open the database while the closed one's thread ran")
        secondGate.countDown()
        thirdReader.awaitLive()
        assertEquals(true, thirdComposed.get())
    }

    @Test
    fun changesDiscardedAtBindingAreReportedUntilAcknowledgedAndNeverAgainByThatReader() {
        fun AndroidLibraryReader.reconnectNow(): AndroidLibraryConnection {
            val result = AtomicReference<AndroidLibraryConnection?>(null)
            val done = CountDownLatch(1)
            reconnect { result.set(it); done.countDown() }
            assertTrue(done.await(30, TimeUnit.SECONDS), "the reconnect completes")
            return checkNotNull(result.get())
        }
        // One change queued offline as the bound user.
        val first = reader()
        val seen = Seen()
        first.openRecording(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest), seen)
        assertTrue(seen.live.await(30, TimeUnit.SECONDS))
        val album = seen.all.last().first.items.first().rawId
        first.setOnline(false)
        assertTrue(first.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album)))
        val pending = LinkedBlockingQueue<Long>()
        first.pendingChangeCount { pending.put(it ?: -1) }
        assertEquals(1L, pending.poll(30, TimeUnit.SECONDS), "control: one change is pending")
        first.closeAndWait()

        // The same server, bound as someone else: the change is discarded, and a reconnect says so.
        val other = CacheBinding(SessionEnv.BINDING.serverId, SessionEnv.BINDING.normalizedBaseUrl, "someone-else")
        val second = AndroidLibraryReader(null, { scope, foreground ->
            AndroidLibraryReaderComposition(LibraryReaderSession(database.database, store.bind(other), server, scope,
                LibraryReaderConfig(lookAheadMaxPerViewport = 0), formPost = false, foreground = foreground))
        }, newLibraryReaderDispatcher(), Dispatchers.Unconfined, initiallyForeground = false).also { readers += it }
        assertEquals(1L, second.reconnectNow().discardedPendingChanges, "the discarded change is reported")
        assertEquals(1L, second.reconnectNow().discardedPendingChanges, "and again, until the person has been told")
        second.acknowledgeDiscardedChanges()
        assertEquals(0L, second.reconnectNow().discardedPendingChanges,
            "once acknowledged, no later reconnect of this reader reports it — whichever screen asks")
    }

    @Test
    fun aTrackListsUnavailableReasonIsTheCoresNotTheHeadersFreshness() {
        val reader = reader()
        val seen = Seen()
        reader.openRecording(AndroidLibraryHomeRow.Albums(AndroidAlbumListType.Newest), seen)
        assertTrue(seen.live.await(30, TimeUnit.SECONDS))
        val album = seen.all.last().first.items.first().rawId
        val detail = Collections.synchronizedList(mutableListOf<AndroidLibraryPublication>())

        // Seen in a list, never opened, and its read fails: the header stands, and the list says why.
        server.failWithCode["getAlbum"] = 0
        reader.openWindow(AndroidLibraryQuery.Album(album)) { detail += it }
        pollUntil("the album's read to fail") { detail.snapshot().any { it.itemsState == AndroidLibraryItemsState.Unavailable } }
        val failed = detail.snapshot().last { it.itemsState == AndroidLibraryItemsState.Unavailable }
        assertIs<AndroidLibraryUnavailableReason.Failed>(failed.itemsUnavailableReason, "a failed read: ${failed.freshness}")

        // Offline, the same list is unavailable because the device is offline.
        reader.setOnline(false)
        pollUntil("the album to say offline") { detail.snapshot().last().freshness is AndroidLibraryFreshness.Cached &&
            (detail.snapshot().last().freshness as AndroidLibraryFreshness.Cached).reason == AndroidLibraryCachedReason.Offline }
        assertEquals(AndroidLibraryUnavailableReason.NotCachedOffline, detail.snapshot().last().itemsUnavailableReason)

        // Back online with the read held: the header's freshness is `revalidating`, which says nothing
        // about the list. Whatever the list's state then, it is never "offline" while online.
        server.failWithCode.remove("getAlbum")
        server.holdBeforeApply += "getAlbum"
        val mark = detail.size
        reader.reconnect()
        pollUntil("the revalidation to publish") {
            detail.snapshot().drop(mark).any { (it.freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Revalidating }
        }
        val online = detail.snapshot().drop(mark).filter { (it.freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Revalidating }
        assertTrue(online.any { it.itemsState == AndroidLibraryItemsState.Unavailable },
            "control: the list was unavailable during the revalidation, so the check below has something to check")
        for (publication in online) {
            if (publication.itemsState == AndroidLibraryItemsState.Unavailable) {
                assertIs<AndroidLibraryUnavailableReason.Failed>(publication.itemsUnavailableReason,
                    "an online list unavailable during a revalidation is the earlier failure, never offline")
            } else {
                assertEquals(null, publication.itemsUnavailableReason)
            }
        }
        pollUntil("the album's read to be held") { server.heldCount > 0 }
        server.holdBeforeApply.clear()
        server.release()
        pollUntil("the track list to arrive") { detail.snapshot().last().itemsState == AndroidLibraryItemsState.Present }
        assertEquals(null, detail.snapshot().last().itemsUnavailableReason)
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
