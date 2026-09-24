package com.legitimateapps.dulcet.search.conformance

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidLibraryCachedReason
import com.legitimateapps.dulcet.core.AndroidLibraryChangeOutcome
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AndroidLibrarySeenCounts
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibraryFrame
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.search.SearchObservation
import com.legitimateapps.dulcet.search.SearchUiState
import java.time.Duration
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** How a platform's UI is driven; everything else in a scenario is shared, so phone and TV prove the same thing. */
interface ReaderAppUi {
    fun openLibrary()
    fun openSearch()
    fun backFromAlbum()
}

/**
 * The reader's conformance scenarios (CONF-76, 77, 79, 84, 86), driven through the production app —
 * its activity, its `LibrarySession`, the process's `AndroidLibraryReader` — against the disposable
 * server, counting requests at a loopback forwarder. Each platform's test class calls each scenario
 * from its own test method, so every CONF id has its own test identity per platform.
 *
 * Request counts exclude `getCoverArt`: cover art is fetched by the image pipeline when a cell is
 * drawn, not by the reader, and the reader's budget is what these tests measure. Every scenario
 * reports how many it saw.
 */
class LibraryReaderScenarios<A : ComponentActivity>(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<A>, A>,
    private val environment: ProductionLibraryEnvironment,
    private val ui: ReaderAppUi,
    private val platform: String,
) {
    private val proxy get() = environment.proxy
    private val server get() = environment.server

    // ---- CONF-76 ----------------------------------------------------------------------------------

    fun conf76CachedPaintBeforeAnyRequestAndOfflineUnavailable() {
        ui.openLibrary()
        awaitHomeLive()
        val liveCounts = HOME.map { last(it).itemCount }
        assertTrue(liveCounts[0] > 0, "setup: the newest row must have albums")
        val opened = server.albumId(OPENED_ALBUM)
        val neverOpened = server.albumId(NEVER_OPENED_ALBUM)
        openHomeAlbum(OPENED_ALBUM)
        await("the opened album live") { last("album:$opened").let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present } }
        val tracks = last("album:$opened").itemCount
        ui.backFromAlbum()
        awaitQuiet()

        // A cold relaunch while the server answers nothing: the reader's thread is stopped (a new
        // process, as far as the core can tell) and every request the new one issues is held.
        val relaunchMark = proxy.size()
        proxy.holdAll()
        closeProcessReader()
        compose.activityRule.scenario.recreate()
        ui.openLibrary()
        await("every home row to paint") { HOME.all { frames(it).isNotEmpty() } }
        HOME.forEachIndexed { index, key ->
            val first = frames(key).first()
            assertIs<AndroidLibraryFreshness.Cached>(first.freshness, "$key's first publication after relaunch")
            assertEquals(liveCounts[index], first.itemCount, "$key painted what was last seen")
            assertTrue(frames(key).none { it.freshness == AndroidLibraryFreshness.Loading }, "$key published no loading state")
        }
        assertEquals(0, proxy.since(relaunchMark).count { it.answered }, "the paint used no answer from the server")
        assertTrue(readerRequests(relaunchMark).isNotEmpty(), "control: the relaunched reader did ask the server (and was held)")
        compose.onNodeWithTag("library.home.0.item.0").assertExists()
        compose.onNodeWithTag("library.home.0.freshness").assertTextContains("Showing what you last saw", substring = true)
        openHomeAlbum(OPENED_ALBUM)
        await("the opened album to paint") { frames("album:$opened").isNotEmpty() }
        val album = frames("album:$opened").first()
        assertIs<AndroidLibraryFreshness.Cached>(album.freshness)
        assertEquals(AndroidLibraryItemsState.Present, album.itemsState)
        assertEquals(tracks, album.itemCount)
        assertEquals(0, proxy.since(relaunchMark).count { it.answered }, "the album painted with no answer either")
        ui.backFromAlbum()
        proxy.release()
        awaitHomeLive()

        // Offline, an album this device never opened says so — no spinner, and nothing is asked.
        environment.network.lose()
        await("the home rows to say offline") {
            (last("home.0").freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Offline
        }
        val mark = proxy.size()
        openHomeAlbum(NEVER_OPENED_ALBUM)
        await("the never-opened album to publish") { frames("album:$neverOpened").isNotEmpty() }
        val offline = frames("album:$neverOpened")
        assertTrue(offline.none { it.freshness == AndroidLibraryFreshness.Loading }, "no loading state offline")
        val final = offline.last()
        assertTrue(
            final.freshness == AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.NotCachedOffline) ||
                final.itemsState == AndroidLibraryItemsState.Unavailable,
            "a never-opened album offline is unavailable, was $final",
        )
        compose.onAllNodes(hasText(UNAVAILABLE_ALBUM_COPY)).onFirst().assertExists()
        assertEquals(emptyList(), readerRequests(mark).map { it.endpoint }, "nothing is requested offline")
        assertNoCredentialLeak()
        println("CONF-76 OBSERVED $platform relaunch-first-publication=cached rows=${liveCounts} album-tracks=$tracks " +
            "answered-before-paint=0 offline-never-opened=${final.freshness::class.simpleName}/${final.itemsState} " +
            "offline-requests=0 cover-art=${coverArt()}")
    }

    // ---- CONF-77 ----------------------------------------------------------------------------------

    fun conf77ReconnectFlushesReadsTheEpochRevalidatesAndNothingElse() {
        ui.openLibrary()
        awaitHomeLive()
        val album = server.albumId(OPENED_ALBUM)
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { last("album:$album").freshness == AndroidLibraryFreshness.Live }
        awaitQuiet()

        environment.network.lose()
        await("the album to say offline") { last("album:$album").freshness.isOfflineCached() }
        val offlineMark = proxy.size()
        compose.onNodeWithTag("album.favourite").performClick()
        await("the star to show offline") { last("album:$album").favourite == true }
        assertEquals(emptyList(), readerRequests(offlineMark).map { it.endpoint }, "a change made offline sends nothing")
        ui.backFromAlbum()
        await("the home rows to say offline") { HOME.all { last(it).freshness.isOfflineCached() } }

        val mark = proxy.size()
        val reconnectsBefore = observed().reconnects
        environment.network.restore()
        await("the reconnect to finish") {
            observed().reconnects > reconnectsBefore && observed().connections.last() is LibraryConnectionState.Online &&
                HOME.all { last(it).freshness == AndroidLibraryFreshness.Live }
        }
        awaitQuiet()
        val sent = readerRequests(mark)
        val names = sent.map { it.endpoint }
        assertEquals("star", names.firstOrNull(), "the outbox flush goes first: $names")
        assertEquals(1, names.count { it == "star" }, "one change, one send: $names")
        assertEquals(listOf("getMusicFolders", "getScanStatus"), names.subList(1, 3).sorted(), "then the epoch read: $names")
        assertEquals(VISIBLE_HOME_READS, names.drop(3).sorted(), "then the visible screen, and nothing else: $names")
        assertEquals(setOf("newest", "recent", "frequent"),
            sent.filter { it.endpoint == "getAlbumList2" }.mapNotNull { it.parameters["type"] }.toSet())
        assertTrue(server.albumStarred(album), "the flushed change reached the server")
        assertNoCredentialLeak()
        println("CONF-77 OBSERVED $platform reconnect requests=${names.size} flush=1 epoch=2 revalidated=4 " +
            "downloaded-recheck=0 (no downloads in this phase) other=0 order=$names cover-art=${coverArt(mark)}")
    }

    fun conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly() {
        ui.openLibrary()
        awaitHomeLive()
        awaitQuiet()
        val mark = proxy.size()
        val reconnectsBefore = observed().reconnects
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Longer than the retired fifteen-minute import cadence: a stopped app reads nothing.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(16))
        awaitQuiet(composed = false)
        assertEquals(emptyList(), readerRequests(mark).map { it.endpoint }, "nothing is read in the background")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("the foreground reconnect to finish") {
            observed().reconnects > reconnectsBefore && observed().connections.last() is LibraryConnectionState.Online &&
                HOME.all { last(it).freshness == AndroidLibraryFreshness.Live }
        }
        awaitQuiet()
        val names = readerRequests(mark).map { it.endpoint }
        assertEquals((listOf("getMusicFolders", "getScanStatus") + VISIBLE_HOME_READS).sorted(), names.sorted(),
            "the epoch read and the visible screen, nothing else: $names")
        assertNoCredentialLeak()
        println("CONF-77 OBSERVED $platform foreground-return background-requests=0 after-16min requests=${names.size} " +
            "epoch=2 revalidated=4 order=$names")
    }

    // ---- CONF-79 ----------------------------------------------------------------------------------

    fun conf79SearchPublishesEachScopeWithSeenCountsOffline() {
        ui.openLibrary()
        awaitHomeLive()
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { last("album:${server.albumId(OPENED_ALBUM)}").freshness == AndroidLibraryFreshness.Live }
        ui.backFromAlbum()
        ui.openSearch()

        // serverAndDevice: the server answered, and nothing needs saying.
        type("Double")
        await("serverAndDevice") { search().answered == "Double" && search().scope == AndroidLibrarySearchScope.ServerAndDevice }
        assertTrue(search().rows.any { it.source == AndroidLibrarySearchRowSource.Server }, "the server's rows are shown")
        assertTrue(compose.onAllNodesWithTag("search.scope").fetchSemanticsNodes().isEmpty(), "no scope line when the server answered")

        // deviceWhileServerPending: the device's rows at once, labelled, while the server is asked.
        proxy.hold { it.endpoint == "search3" }
        val answeredBefore = proxy.answered("search3")
        type("Lines")
        await("deviceWhileServerPending") { search().answered == "Lines" && search().scope == AndroidLibrarySearchScope.DeviceWhileServerPending }
        assertTrue(search().rows.isNotEmpty() && search().rows.all { it.source == AndroidLibrarySearchRowSource.Device })
        compose.onNodeWithTag("search.scope").assertTextEquals("On this device")
        assertEquals(answeredBefore, proxy.answered("search3"), "the server's answer is held")
        proxy.release()
        await("the held query answered") { search().answered == "Lines" && search().scope == AndroidLibrarySearchScope.ServerAndDevice }

        // deviceServerFailed: the device's rows stay, and the line says the server failed.
        proxy.fail { it.endpoint == "search3" }
        type("Doub")
        await("deviceServerFailed") { search().answered == "Doub" && search().scope is AndroidLibrarySearchScope.DeviceServerFailed }
        val failed = search().scope as AndroidLibrarySearchScope.DeviceServerFailed
        assertEquals(seenCounts(), failed.seen)
        compose.onNodeWithTag("search.scope").assertTextContains("On this device — ", substring = true)
        proxy.fail(null)

        // deviceOffline: the seen-cache's own counts, stated.
        environment.network.lose()
        val mark = proxy.size()
        type("Thresh")
        await("deviceOffline") { search().answered == "Thresh" && search().scope is AndroidLibrarySearchScope.DeviceOffline }
        val counts = seenCounts()
        assertEquals(AndroidLibrarySearchScope.DeviceOffline(counts), search().scope)
        assertTrue(counts.albums > 0 && counts.tracks > 0, "control: the device has seen albums and tracks")
        compose.onNodeWithTag("search.scope").assertTextEquals(
            "Searching what's available offline — ${counts.albums} albums and ${counts.tracks} tracks on this device")
        assertEquals(emptyList(), readerRequests(mark).map { it.endpoint }, "an offline search asks nothing")
        assertNoCredentialLeak()
        println("CONF-79 OBSERVED $platform scopes=serverAndDevice,deviceWhileServerPending,deviceServerFailed,deviceOffline " +
            "offline-counts=${counts.artists}/${counts.albums}/${counts.tracks} offline-requests=0 offline-cover-art=${coverArt(mark)}")
    }

    // ---- CONF-84 ----------------------------------------------------------------------------------

    fun conf84StarShowsWithTheTapSurvivesARevalidationAndAdoptsTheEcho() {
        ui.openLibrary()
        awaitHomeLive()
        val album = server.albumId(OPENED_ALBUM)
        openHomeAlbum(OPENED_ALBUM)
        await("the album live") { last("album:$album").let { it.freshness == AndroidLibraryFreshness.Live && it.favourite != true } }
        awaitQuiet()

        proxy.hold { it.endpoint == "star" }
        val before = frames("album:$album").size
        compose.onNodeWithTag("album.favourite").performClick()
        await("a publication after the tap") { frames("album:$album").size > before }
        assertEquals(true, frames("album:$album")[before].favourite, "the first publication after the tap shows the star")
        assertEquals(0, proxy.answered("star"), "before the send was answered")

        // A revalidation that lands before the send completes reads the server's old value.
        val reads = proxy.answered("getAlbum")
        compose.onNodeWithTag("album.refresh").performClick()
        await("the revalidation to land") { proxy.answered("getAlbum") > reads && last("album:$album").freshness == AndroidLibraryFreshness.Live }
        assertEquals(false, server.albumStarred(album), "the server had not been told when the revalidation read it")
        assertEquals(0, proxy.answered("star"), "the send was still held")
        assertTrue(frames("album:$album").drop(before).all { it.favourite == true }, "no publication after the tap dropped the star")

        // The send completes; the server's echo is adopted and nothing is left pending.
        proxy.release()
        await("the change to be saved") { observed().changeOutcomes.any { it is AndroidLibraryChangeOutcome.Saved && it.target.rawId == album } }
        assertTrue(server.albumStarred(album))
        val echoed = proxy.answered("getAlbum")
        compose.onNodeWithTag("album.refresh").performClick()
        await("the echo read") { proxy.answered("getAlbum") > echoed && last("album:$album").freshness == AndroidLibraryFreshness.Live }
        assertEquals(true, last("album:$album").favourite)
        assertEquals(1, databaseLong("SELECT starred FROM cache_album WHERE raw_id = ?", album), "the cache holds the server's value")
        assertEquals(0, databaseLong("SELECT count(*) FROM mutation_outbox"), "nothing is pending")

        // And back, through the app.
        compose.onNodeWithTag("album.favourite").performClick()
        await("the star removed") { last("album:$album").favourite != true }
        await("the removal saved") {
            observed().changeOutcomes.count { it is AndroidLibraryChangeOutcome.Saved && it.target.rawId == album } >= 2
        }
        assertEquals(false, server.albumStarred(album))
        assertNoCredentialLeak()
        println("CONF-84 OBSERVED $platform same-publication-as-tap=true survived-revalidation-before-send=true " +
            "echo-adopted=true outbox-after=0")
    }

    // ---- CONF-86 ----------------------------------------------------------------------------------

    fun conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive() {
        proxy.fail { it.endpoint == "getStarred2" }
        proxy.hold { it.endpoint == "getAlbumList2" && it.parameters["type"] == "frequent" }
        ui.openLibrary()
        await("rows 0 and 1 live and row 3 failed, while row 2 is held") {
            last("home.0").freshness == AndroidLibraryFreshness.Live && last("home.1").freshness == AndroidLibraryFreshness.Live &&
                last("home.3").freshness.isFailed()
        }
        assertEquals(0, proxy.log().count { it.endpoint == "getAlbumList2" && it.parameters["type"] == "frequent" && it.answered },
            "row 2's answer had not arrived")
        assertTrue(proxy.log().any { it.endpoint == "getAlbumList2" && it.parameters["type"] == "frequent" }, "control: row 2 did ask")
        assertEquals(AndroidLibraryFreshness.Loading, last("home.2").freshness, "row 2 still says it is loading")
        compose.onNodeWithTag("library.home.0.item.0").assertExists()
        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasTestTag("library.home.0")))
            .performScrollToNode(hasTestTag("library.home.3.unavailable"))
        compose.onNodeWithTag("library.home.3.unavailable").assertTextContains("Couldn't load this — ", substring = true)
        proxy.release()
        await("row 2 live") { last("home.2").freshness == AndroidLibraryFreshness.Live }
        assertEquals(AndroidLibraryFreshness.Live, last("home.0").freshness)
        assertEquals(AndroidLibraryFreshness.Live, last("home.1").freshness)
        assertTrue(last("home.3").freshness.isFailed(), "the failed row stays failed on its own")
        proxy.fail(null)
        assertNoCredentialLeak()
        println("CONF-86 OBSERVED $platform rows-live-before-held-row=0,1 failed-row=3 other-rows-live=true " +
            "row-sequences=${HOME.map { frames(it).size }}")
    }

    // ---- Instruments ------------------------------------------------------------------------------

    /** Credentials ride in the query string; nothing the app logged may carry them (CLAUDE.md trap 12). */
    private fun assertNoCredentialLeak() {
        val logged = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        for (canary in listOf(ProductionLibraryEnvironment.USERNAME, ProductionLibraryEnvironment.PASSWORD)) {
            assertTrue(!logged.contains(canary), "A credential reached the log")
        }
        assertTrue(proxy.log().none { entry -> entry.parameters.values.any { it.contains(ProductionLibraryEnvironment.PASSWORD) } })
    }

    private fun observed(): LibraryObservationState =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(LibraryObservation)).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(LibraryObservation) ?: LibraryObservationState()

    private fun frames(key: String): List<LibraryFrame> = observed().surfaces[key].orEmpty()

    private fun last(key: String): LibraryFrame = frames(key).lastOrNull() ?: fail("$key has published nothing")

    private fun search(): SearchUiState =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SearchObservation)).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(SearchObservation) ?: SearchUiState()

    private fun type(text: String) {
        compose.onNodeWithTag("search.query").performTextReplacement(text)
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(WAIT_MILLIS) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; requests=${proxy.log().map { it.endpoint }}", timeout)
        }
    }

    private fun awaitHomeLive() = await("every home row live") {
        HOME.all { frames(it).lastOrNull()?.freshness == AndroidLibraryFreshness.Live }
    }

    /** Nothing in flight, and nothing new for a while: counts taken after this are complete. */
    private fun awaitQuiet(composed: Boolean = true) {
        var stable = 0
        var size = -1
        repeat(2_000) {
            if (composed) compose.waitForIdle() else shadowOf(Looper.getMainLooper()).idle()
            val now = proxy.size()
            if (proxy.inFlight() == 0 && now == size) stable++ else stable = 0
            size = now
            if (stable >= 20) return
            Thread.sleep(25)
        }
        fail("The app never went quiet; requests=${proxy.log().map { it.endpoint }}")
    }

    private fun openHomeAlbum(title: String) {
        val row = hasTestTag("library.home.0.items")
        compose.onNode(row).performScrollToNode(hasText(title))
        compose.onAllNodes(hasText(title) and hasAnyAncestor(row)).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun readerRequests(mark: Int = 0) = proxy.since(mark).filter { it.endpoint != "getCoverArt" }

    private fun coverArt(mark: Int = 0) = proxy.since(mark).count { it.endpoint == "getCoverArt" }

    private fun seenCounts(): AndroidLibrarySeenCounts = AndroidLibrarySeenCounts(
        artists = databaseLong("SELECT count(*) FROM cache_artist WHERE server_id = ? AND gone = 0", serverId()),
        albums = databaseLong("SELECT count(*) FROM cache_album WHERE server_id = ? AND gone = 0", serverId()),
        tracks = databaseLong("SELECT count(*) FROM cache_track WHERE server_id = ? AND gone = 0 AND metadata_missing = 0", serverId()),
    )

    private fun serverId(): String =
        checkNotNull(AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).load()).id

    /** A read of the app's own database file, beside the reader: an instrument the app does not draw. */
    private fun databaseLong(sql: String, vararg arguments: String): Long {
        val app = RuntimeEnvironment.getApplication()
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            app.getDatabasePath("dulcet.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            return database.rawQuery(sql, arguments).use { cursor ->
                check(cursor.moveToFirst()) { "no row" }
                cursor.getLong(0)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        val HOME = listOf("home.0", "home.1", "home.2", "home.3")
        val VISIBLE_HOME_READS = listOf("getAlbumList2", "getAlbumList2", "getAlbumList2", "getStarred2")
        const val OPENED_ALBUM = "Double Lines"
        const val NEVER_OPENED_ALBUM = "Threshold Boundary"
        const val UNAVAILABLE_ALBUM_COPY = "You haven't opened this album on this device. Connect to your server to see it."
        const val WAIT_MILLIS = 60_000L
    }
}

private fun AndroidLibraryFreshness.isOfflineCached(): Boolean =
    (this as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Offline

private fun AndroidLibraryFreshness.isFailed(): Boolean =
    (this as? AndroidLibraryFreshness.Unavailable)?.reason is AndroidLibraryUnavailableReason.Failed ||
        (this as? AndroidLibraryFreshness.Cached)?.reason is AndroidLibraryCachedReason.Failed

/** The disposable fixture's album id for [name], read from the server itself. */
fun DisposableServer.albumId(name: String): String {
    val albums = get("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"))
        .getJSONObject("albumList2").getJSONArray("album")
    for (index in 0 until albums.length()) {
        val album = albums.getJSONObject(index)
        if (album.getString("name") == name) return album.getString("id")
    }
    error("The disposable fixture has no album named $name")
}
