package com.legitimateapps.dulcet.core

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Before
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** The device-held half of signing out (spec §14.7 steps 2, 5 and 6) against the production Android driver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAccountDataTest {
    private val context = RuntimeEnvironment.getApplication()
    private val databaseName = "account-data-${UUID.randomUUID()}.db"
    private val downloads = File(context.cacheDir, "downloads-${UUID.randomUUID()}")
    private val requests = mutableListOf<Map<String, String>>()
    private var answer: () -> AuthenticatedEndpointResponse = ::ok
    private val closes = mutableListOf<String>()

    // Removals are remembered for the life of the process (the sandbox outlives one test), and ids
    // are never reused in production; so each test uses fresh ones.
    private val SERVER = "account-being-removed-${UUID.randomUUID()}"
    private val OTHER = "account-that-stays-${UUID.randomUUID()}"

    // Core's host tests have no Android main dispatcher module; the app's is the main looper, so
    // this posts to it, and each test idles the looper where the app's main thread would run.
    private val mainLooper = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    @Before fun main() = Dispatchers.setMain(mainLooper)

    @After fun clean() {
        AndroidLibraryReader.closeCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        Dispatchers.resetMain()
        context.deleteDatabase(databaseName)
        File(context.cacheDir, "artwork").deleteRecursively()
        downloads.deleteRecursively()
    }

    @Test fun pendingPlaysAreTheAccountsOwnByIdentity() = runBlocking {
        seed(SERVER, plays = 3, edits = 2)
        seed(OTHER, plays = 5, edits = 4)
        val mine = assertNotNull(data(SERVER).pendingPlays())
        assertEquals(3, mine.size)
        assertEquals(5, data(OTHER).pendingPlays()?.size)
    }

    @Test fun anUnreadableOutboxIsUnknownNeverEmpty() = runBlocking {
        val broken = AndroidAccountData(SERVER, { throw IllegalStateException("database unavailable") },
            File(context.cacheDir, "unused"), File(context.cacheDir, "unused"), { error("no sender") },
            OutboxWallClock { NOW }, OutboxMonotonicClock { 0.seconds }) {}
        assertNull(broken.pendingPlays())
    }

    @Test fun submittingSendsEveryPlayOfTheAccountOnly() = runBlocking {
        seed(SERVER, plays = 2, edits = 1)
        seed(OTHER, plays = 1, edits = 0)
        val remaining = data(SERVER).submitPlays(account(SERVER))
        assertEquals(2, requests.size, "Each of the account's plays is sent once, and only its own")
        assertTrue(requests.all { it["submission"] == "true" })
        assertEquals(emptySet(), remaining)
        assertEquals(1, data(OTHER).pendingPlays()?.size)
    }

    @Test fun aPlayTheServerRefusesStaysPending() = runBlocking {
        seed(SERVER, plays = 2, edits = 0)
        val before = data(SERVER).pendingPlays()
        answer = ::refused
        val remaining = data(SERVER).submitPlays(account(SERVER))
        assertEquals(1, requests.size, "Delivery stops at the first refusal")
        assertEquals(before, remaining)
    }

    @Test fun submittingForAnotherAccountIsRefused() {
        assertFailsWith<IllegalArgumentException> { runBlocking { data(SERVER).submitPlays(account(OTHER)) } }
    }

    @Test fun removalWaitsForTheReaderThenDeletesTheArtworkDownloadsAndRowsOfTheAccountOnly() = runBlocking {
        seed(SERVER, plays = 2, edits = 2, everything = true)
        seed(OTHER, plays = 1, edits = 1, everything = true)
        val artwork = cacheArtwork(SERVER)
        val otherArtwork = cacheArtwork(OTHER)
        val download = download(SERVER)
        val otherDownload = download(OTHER)
        assertTrue(rowsFor(SERVER) > 6, "The control needs rows in several tables first")
        assertTrue(artwork.walk().any { it.isFile }, "The control needs cached artwork first")
        assertTrue(download.isFile, "The control needs a downloaded file first")

        data(SERVER).removeAccountData()

        assertEquals(listOf("reader.closed"), closes, "Nothing is deleted before the reader has terminated")
        assertEquals(0L, rowsFor(SERVER))
        assertFalse(artwork.exists(), "The account's cached artwork must be deleted")
        assertFalse(download.exists(), "The account's downloaded media must be deleted")
        assertTrue(rowsFor(OTHER) > 0, "Another account's rows must survive")
        assertTrue(otherArtwork.walk().any { it.isFile }, "Another account's artwork must survive")
        assertTrue(otherDownload.isFile, "Another account's download must survive")
        // Idempotent, so a sign-out interrupted after this step can run it again at launch.
        data(SERVER).removeAccountData()
        assertEquals(0L, rowsFor(SERVER))
    }

    /**
     * The library reader is the process's, writes seen-cache rows on its own thread, and still runs
     * what was queued there when it is closed. Here a write is queued and held; the removal must not
     * delete until the reader has terminated, so the write lands BEFORE the deletion and is deleted.
     * Deleting without waiting (the mutant) lets the held write land after, recreating a row.
     */
    @Test fun aLibraryReaderWriteRacingTheRemovalCannotRecreateTheAccountsRows() {
        val account = AndroidLibraryReaderAccount(SERVER, "https://music.example.invalid", "fixture-user", "fixture-password", false)
        val reader = AndroidLibraryReader.obtain(account) { previous ->
            AndroidLibraryReader(account, { _, _ -> error("this test composes no session") }, newLibraryReaderDispatcher(),
                Dispatchers.Main, previous, initiallyForeground = false)
        }
        val release = CountDownLatch(1)
        val wrote = CountDownLatch(1)
        reader.onReader {
            release.await(3, TimeUnit.SECONDS)
            seedBinding(SERVER)
            wrote.countDown()
        }
        val removal = AndroidAccountData(SERVER, ::open, AndroidAccountData.artworkRootFor(context, SERVER), downloads,
            { error("no sender") }, OutboxWallClock { NOW }, OutboxMonotonicClock { 0.seconds },
            AndroidAccountData::closeProcessLibraryReader)
        var failure: Throwable? = null
        val finished = CountDownLatch(1)
        thread {
            try { runBlocking { removal.removeAccountData() } } catch (thrown: Throwable) { failure = thrown }
            finished.countDown()
        }
        // The reader's close completes on the main thread, which the test owns.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!finished.await(10, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "the removal never finished" }
        }
        release.countDown()
        assertTrue(wrote.await(10, TimeUnit.SECONDS), "The control requires the reader's queued write to run")
        assertNull(failure)
        assertEquals(0L, rowsFor(SERVER), "A write the reader had queued recreated the account's rows after the removal")
    }

    @Test fun anArtworkLoadFinishingAfterTheRemovalDoesNotRecreateTheAccountsDirectory() = runBlocking {
        val repository = repository(SERVER)
        assertNotNull(repository.load("cover-before", 256))
        val root = AndroidAccountData.artworkRootFor(context, SERVER)
        assertTrue(root.walk().any { it.isFile }, "The control needs the repository to write into the account's directory")

        data(SERVER).removeAccountData()
        assertFalse(root.exists())
        // A screen still holding the repository loads another cover: it is shown, but not kept.
        assertNotNull(repository.load("cover-after", 256), "The bytes are still returned")
        assertFalse(root.exists(), "A load after the removal recreated the removed account's artwork directory")
    }

    @Test fun theLaunchSweepDeletesEveryAccountButTheSavedOne() = runBlocking {
        val orphan = "account-signed-out-earlier-${UUID.randomUUID()}"
        seed(SERVER, plays = 1, edits = 1, everything = true)
        seed(orphan, plays = 1, edits = 1, everything = true)
        seed(OTHER, plays = 1, edits = 1, everything = true)
        val kept = cacheArtwork(OTHER)
        val orphanArtwork = cacheArtwork(orphan)
        val serverArtwork = cacheArtwork(SERVER)
        val keptDownload = download(OTHER)
        val orphanDownload = download(orphan)

        val swept = sweep { OTHER }

        assertEquals(setOf(SERVER, orphan), swept)
        assertEquals(0L, rowsFor(SERVER))
        assertEquals(0L, rowsFor(orphan))
        assertFalse(orphanArtwork.exists())
        assertFalse(serverArtwork.exists())
        assertFalse(orphanDownload.exists())
        assertTrue(rowsFor(OTHER) > 6, "The saved account's rows must survive")
        assertTrue(kept.walk().any { it.isFile }, "The saved account's artwork must survive")
        assertTrue(keptDownload.isFile, "The saved account's download must survive")
    }

    @Test fun withNoAccountSavedTheSweepDeletesEveryAccount() = runBlocking {
        seed(SERVER, plays = 1, edits = 0, everything = true)
        val artwork = cacheArtwork(SERVER)
        assertEquals(setOf(SERVER), sweep { null })
        assertEquals(0L, rowsFor(SERVER))
        assertFalse(artwork.exists())
    }

    /**
     * The saved account is read after the listing, so an account saved at any moment of the sweep
     * keeps what it writes. Here the read itself stands for "a new account was saved, and wrote its
     * first rows and artwork, just after the sweep looked": read before the listing (the mutant),
     * the new account's rows are listed as orphaned and deleted.
     */
    @Test fun anAccountSavedWhileTheSweepRunsKeepsWhatItWrites() = runBlocking {
        val newcomer = "account-saved-during-the-sweep-${UUID.randomUUID()}"
        seed(SERVER, plays = 1, edits = 0)
        var read = 0
        val swept = sweep {
            read += 1
            seed(newcomer, plays = 1, edits = 1, everything = true)
            runBlocking { cacheArtwork(newcomer) }
            null
        }
        assertEquals(1, read)
        assertEquals(setOf(SERVER), swept)
        assertTrue(rowsFor(newcomer) > 0, "An account saved during the sweep lost its rows")
        assertTrue(AndroidAccountData.artworkRootFor(context, newcomer).exists(), "An account saved during the sweep lost its artwork")
    }

    /** The sweep finds an account by the tables the removal checks; a table missing from its list would hide one. */
    @Test fun theSweepListsEveryTableTheRemovalCounts() {
        val source = File("src/commonMain/sqldelight/com/legitimateapps/dulcet/database/ServerData.sq").readText()
        fun tables(query: String, pattern: String): List<String> {
            val body = source.substringAfter("$query:\n").substringBefore(";\n")
            return Regex(pattern).findAll(body).map { it.groupValues[1] }.toList()
        }
        val counted = tables("countRowsForServer", """FROM (\w+) WHERE server_id""")
        val listed = tables("selectServerIdsWithRows", """SELECT server_id FROM (\w+)""")
        assertTrue(counted.size > 30, "The control must read the counted tables: $counted")
        assertEquals(counted, listed)
    }

    private suspend fun sweep(active: () -> String?): Set<String> =
        AndroidAccountData.sweep(::open, File(context.cacheDir, "artwork"), downloads, active)

    private fun data(serverId: String) = AndroidAccountData(serverId, ::open,
        AndroidAccountData.artworkRootFor(context, serverId), downloads,
        { ScrobbleEndpointSender(ScrobbleEndpointTransport { parameters -> requests += parameters; answer() }) },
        OutboxWallClock { NOW }, OutboxMonotonicClock { 0.seconds }) { closes += "reader.closed" }

    private fun open(): DulcetDatabaseStore = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())

    private fun seed(serverId: String, plays: Int, edits: Int, everything: Boolean = false) {
        val store = open()
        try {
            val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock { NOW })
            repeat(plays) { index ->
                outbox.persistSynchronously(RecordedPlaybackEvent.SubmittedPlay(
                    ProviderItemId(serverId, "song-$index"), PlaybackWallClockTime(NOW - 60_000L * (index + 1))))
            }
            repeat(edits) { index ->
                store.database.protectedReservedDataQueries.insertPendingMutation(serverId, "album-$index",
                    "album.starred", "true", index.toLong(), NOW)
            }
            if (everything) {
                store.database.resumePositionQueries.save(serverId, "song-0", 12_000)
                store.database.seenCacheQueries.insertBinding(serverId, "https://music.example.invalid",
                    "fixture-user", NOW)
                PlaybackQueueController(PersistentQueueStore(store.database), PersistentResumePositionStore(store.database),
                    PlaybackIdentitySource { "$it:${UUID.randomUUID()}" }).replaceAndStart(PlaybackQueueRequest(
                    listOf(PlaybackQueueItem(ProviderItemId(serverId, "song-0"), 40.seconds)),
                    QueueSourceContext(QueueSourceKind.Search, null, "Search"), 0, false))
            }
        } finally {
            store.close()
        }
    }

    private fun seedBinding(serverId: String) {
        val store = open()
        try {
            store.database.seenCacheQueries.insertBinding(serverId, "https://music.example.invalid", "fixture-user", NOW)
        } finally {
            store.close()
        }
    }

    /** A downloaded file and the row that owns it, as a download executor would leave them. */
    private fun download(serverId: String): File {
        val relative = "$serverId-song.bin"
        val store = open()
        try {
            store.database.downloadsQueries.insertDownload(serverId, "song-0", "original", "download-$serverId", relative, 4L)
            store.database.downloadsQueries.insertDownloadPolicyState(serverId, "song-0", "original", "mp3", 1_000L, 4L, 0L, 0L, NOW)
        } finally {
            store.close()
        }
        return File(downloads, relative).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
    }

    private fun rowsFor(serverId: String): Long {
        val store = open()
        try {
            return store.database.serverDataQueries.countRowsForServer(serverId).executeAsOne().sum ?: 0L
        } finally {
            store.close()
        }
    }

    private fun repository(serverId: String) = AndroidArtworkRepository(context, account(serverId), ArtworkFetcher(ArtworkEndpointTransport {
        ArtworkEndpointResponse(200, PNG, "<redacted-url>")
    }), 1_000_000)

    /** Written by the production repository, so the removal is tested against the directory it really uses. */
    private suspend fun cacheArtwork(serverId: String): File {
        assertNotNull(repository(serverId).load("cover-$serverId", 256))
        return AndroidAccountData.artworkRootFor(context, serverId)
    }

    private fun account(serverId: String) =
        PlaybackEndpointAccount(serverId, "https://music.example.invalid", "fixture-user", "fixture-password", false)

    private companion object {
        const val NOW = 1_788_000_000_000L
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

        fun ok(): AuthenticatedEndpointResponse = response(200, """{"subsonic-response":{"status":"ok"}}""")
        fun refused(): AuthenticatedEndpointResponse =
            response(503, """{"subsonic-response":{"status":"failed","error":{"code":0}}}""")

        fun response(status: Int, json: String): AuthenticatedEndpointResponse {
            val body = json.encodeToByteArray()
            return AuthenticatedEndpointResponse(status, body, "https://music.invalid/rest/scrobble.view?<redacted>",
                AuthenticatedEndpointResponseHeaders("application/json", PlaybackContentLength.Exact(body.size.toLong()),
                    null, null, null),
                RequestTrace.observed(endpoint = "scrobble", method = "GET",
                    redactedUrl = "https://music.invalid/rest/scrobble.view?<redacted>",
                    authenticationLocation = AuthenticationLocation.Query, queryAuthenticationParameters = emptySet(),
                    formAuthenticationParameters = emptySet(), channels = emptySet(),
                    requestedProtocolVersion = "1.16.1", saltFingerprint = "fixture"))
        }
    }
}
