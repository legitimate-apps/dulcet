package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Drives the production [LibraryReader] against [FakeReaderServer] over a private test database.
 * The reader's coroutines run on the test scheduler (virtual time), and the driver is closed in
 * `finally` so a failing test cannot leak its database into the next one on Kotlin/Native.
 */
internal fun readerTest(
    ceilings: SeenCacheCeilings = SeenCacheCeilings.DEFAULT,
    block: suspend TestScope.(ReaderEnv) -> Unit,
) = runTest {
    val driver = CountingSqlDriver(createTestDriver())
    val uncaught = mutableListOf<Throwable>()
    // Not backgroundScope: advanceUntilIdle does not wait for background work, so reads launched
    // there would never run. A scope on the test scheduler runs under virtual time and is
    // cancelled at the end, so a held response cannot hang the test.
    // The handler only RECORDS what escapes: a test asserts `uncaught` is empty, because on
    // Kotlin/Native an exception escaping the reader's scope would terminate the process.
    val readerScope = CoroutineScope(
        StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, t -> uncaught += t },
    )
    try {
        val database = DulcetDatabaseStore.open(driver)
        val clock = ManualWallClock(now = 1_000_000)
        val env = ReaderEnv(FakeReaderServer(), SeenCacheStore(database, clock, ceilings), clock, readerScope, driver, uncaught)
        block(env)
    } finally {
        readerScope.cancel()
        driver.close()
    }
}

internal class ReaderEnv(
    val server: FakeReaderServer,
    val store: SeenCacheStore,
    val clock: ManualWallClock,
    private val readerScope: CoroutineScope,
    val driver: CountingSqlDriver,
    val uncaught: List<Throwable>,
) {
    /** A new reader is a new session over the same device cache. */
    fun reader(
        config: LibraryReaderConfig = LibraryReaderConfig(),
        overlay: LibraryMutationOverlay = LibraryMutationOverlay.None,
        downloads: DownloadedTrackSource = DownloadedTrackSource.None,
        outboxes: ReconnectOutboxes = ReconnectOutboxes.None,
    ): LibraryReader = LibraryReader(
        cache = store.bind(BINDING),
        transport = server,
        scope = readerScope,
        config = config,
        overlay = overlay,
        downloads = downloads,
        outboxes = outboxes,
    )

    companion object {
        val BINDING = CacheBinding("server:reader", "https://music.example", "listener")
    }
}

/** Every publication, with how many requests had been issued when it was delivered. */
internal class Publications(private val server: FakeReaderServer) : (LibraryPublication) -> Unit {
    data class Seen(val publication: LibraryPublication, val requestsIssued: Int)

    val all = mutableListOf<Seen>()
    val last: LibraryPublication get() = all.last().publication

    override fun invoke(publication: LibraryPublication) {
        all += Seen(publication, server.log.size)
    }

    fun ids(publication: LibraryPublication = last): List<String> = publication.items.map { it.rawId }

    fun freshness(): List<LibraryFreshness> = all.map { it.publication.freshness }
}

internal fun albumId(index: Int) = "album-${index.toString().padStart(4, '0')}"
