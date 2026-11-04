package com.legitimateapps.dulcet

import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.toReaderAccount
import com.legitimateapps.dulcet.library.AlbumSort
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import com.legitimateapps.dulcet.search.conformance.PlatformNetwork
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone library's Genres and its album orders, at parity with the Apple shells, over the
 * PRODUCTION session and reader: only the server is a loopback fixture, so each request asserted
 * here is one the reader made for the screen (spec §16.9: `getGenres`, `getSongsByGenre`, and
 * `getAlbumList2` with the chosen type).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneGenresAndAlbumSortTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("provider:genres", server.url, "u", "p", true)
    private var genre by mutableStateOf<String?>(null)
    private val played = mutableListOf<Played>()

    private data class Played(val ids: List<String>, val from: String?, val source: String, val shuffle: Boolean)

    private val actions = PhoneActions(
        openAlbum = {}, openArtist = {}, back = { genre = null },
        playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null },
        openGenre = { genre = it },
        playTracksFrom = { items, from, source, shuffle -> played += Played(items.map { it.rawId }, from, source, shuffle) },
    )

    @After fun close() {
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        server.close()
        context.deleteDatabase("dulcet.db")
        context.getSharedPreferences("dulcet.ui", 0).edit().clear().commit()
    }

    private fun host() {
        compose.setContent {
            MaterialTheme {
                val session = androidx.compose.runtime.remember { LibrarySession(context, account, foreground = true) }
                // As PhoneApp composes them: the library keeps its view while a page is pushed over it.
                val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                val shown = genre
                if (shown == null) saved.SaveableStateProvider("library") { LibraryHome(account, session, actions, title = com.legitimateapps.dulcet.shared.R.string.library_browse) }
                else GenreScreen(account, session, shown, null, actions)
                LibraryLifecycle(session)
            }
        }
        compose.waitForIdle()
    }

    // ---- Genres -------------------------------------------------------------------------------------

    @Test fun theLibraryListsGenresAndAGenreOpensItsSongsFromTheSameReader() {
        host()
        click("library.view.genres")
        await("the genres listed") { text("library.genres.item.1") == "Ambient" }
        assertEquals("Jazz", text("library.genres.item.0"))
        assertTrue(server.requests("getGenres").isNotEmpty(), "the list is getGenres")
        assertTrue(server.requests("getSongsByGenre").isEmpty(), "no genre's songs are read before one is opened")
        assertTrue("genres" in observed("library.surface").surfaces, "the list is the session's genres window")

        click("library.genres.item.0")
        await("Jazz's songs") { exists("genre.track.1") }
        assertEquals("Jazz", text("genre.title"))
        assertEquals("2 songs", text("genre.count"))
        // The page may be read twice: a window opened while the session's first reconnect runs is also
        // revalidated by it (§16.14 step 3). A short page is only a candidate end, so the window may also
        // confirm it with one read just past it (LibraryWindow's confirming append), here at offset 2;
        // whether that read has landed yet is timing. Every read is of the genre opened, from its start
        // or that confirmation -- never a read that skips rows.
        assertEquals(setOf("Jazz"), server.requests("getSongsByGenre").map { it["genre"] }.toSet(), "reads only of the genre opened")
        val offsets = server.requests("getSongsByGenre").map { it["offset"] }
        assertEquals("0", offsets.first(), "the first read is from the start: ${server.requests("getSongsByGenre")}")
        assertTrue(offsets.toSet() - setOf("0", "2") == emptySet<String?>(), "only the start and its end confirmation are read: ${server.requests("getSongsByGenre")}")
        assertTrue("genre:Jazz" in observed("genre.surface").surfaces, "the page is the session's genre window")

        click("genre.track.1")
        click("genre.shuffle")
        click("genre.play")
        val songs = listOf("jazz-song-1", "jazz-song-2")
        assertEquals(listOf(
            Played(songs, "jazz-song-2", "Jazz", false),
            Played(songs, null, "Jazz", true),
            Played(songs, null, "Jazz", false),
        ), played, "a row plays the genre from itself; Play and Shuffle play all of it")

        click("genre.back")
        await("back on the genres") { exists("library.genres.item.0") }
    }

    @Test fun aGenreListStillBeingReadSaysSoUntilItArrives() {
        server.holdGenres = true
        host()
        click("library.view.genres")
        await("the read to start") { server.requests("getGenres").isNotEmpty() }
        await("the loading statement") { exists("library.genres.loading") }
        assertTrue(!exists("library.genres.item.0"))
        server.releaseGenres()
        await("the genres after the answer") { exists("library.genres.item.0") }
        assertTrue(!exists("library.genres.loading"))
    }

    @Test fun anEmptyGenreListSaysNothingIsThere() {
        server.genres = emptyList()
        host()
        click("library.view.genres")
        await("the empty statement") { exists("library.genres.empty") }
        compose.onNodeWithText("Nothing here yet").assertExists()
    }

    @Test fun aGenreListTheServerFailedToReadSaysWhy() {
        server.failGenres = true
        host()
        click("library.view.genres")
        await("the failure statement") { exists("library.genres.unavailable") }
        assertTrue(text("library.genres.unavailable").startsWith("Couldn't load this — "), text("library.genres.unavailable"))
        assertTrue(!exists("library.genres.item.0"))
    }

    /**
     * Rows from this device leave a list's status item empty at the first layout, and a list treats
     * an empty first item as scrolled past. A failure arriving later must still be on the screen.
     */
    /** Every shared row offers its actions at the same place; an artist row used to have none. */
    @Test fun aListedArtistCarriesItsMore() {
        compose.setContent {
            MaterialTheme {
                val session = androidx.compose.runtime.remember { LibrarySession(context, account, foreground = true) }
                ArtistsList(session, actions)
                LibraryLifecycle(session)
            }
        }
        await("the listed artists") { exists("library.artists.item.0") }
        compose.onNodeWithTag("library.artists.item.0.menu").assertExists()
    }

    /** As Apple's Library: the history and the ranking are rows to full lists, and Browse keeps one shelf. */
    @Test fun browseKeepsOneShelfAndLibraryListsTheHistory() {
        var browse by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                val session = androidx.compose.runtime.remember { LibrarySession(context, account, foreground = true) }
                LibraryHome(account, session, actions, title = if (browse) R.string.library_discover_entry else R.string.tab_library)
                LibraryLifecycle(session)
            }
        }
        await("Browse's one shelf") { exists("library.home.0") }
        compose.onAllNodesWithTag("library.home.1").assertCountEquals(0)
        compose.onAllNodesWithTag("library.home.2").assertCountEquals(0)
        browse = false
        await("Library's history row") { exists("library.view.recentlyPlayed") }
        compose.onNodeWithTag("library.view.mostPlayed").assertExists()
    }

    private fun aLateFailureIsOnTheScreen(
        list: String,
        status: String,
        endpoint: String,
        firstRow: String = "$list.item.0",
        fontScale: Float = 1f,
        fail: () -> Unit,
        page: @androidx.compose.runtime.Composable (LibrarySession) -> Unit,
    ) {
        var shown by androidx.compose.runtime.mutableStateOf(true)
        var refresh: (() -> Unit)? = null
        compose.setContent {
            MaterialTheme {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
                ) {
                    val session = androidx.compose.runtime.remember { LibrarySession(context, account, foreground = true) }
                    refresh = session::refresh
                    if (shown) page(session)
                    LibraryLifecycle(session)
                }
            }
        }
        await("the rows listed") { exists(firstRow) }
        shown = false
        compose.waitForIdle()
        shown = true
        await("the saved rows listed") { exists(firstRow) }
        compose.waitForIdle()
        assertTrue(!exists(status), "control: nothing has failed yet")
        fail()
        val reads = server.requests(endpoint).size
        compose.runOnUiThread { refresh!!() }
        await("the failing read went out") { server.requests(endpoint).size > reads }
        val onScreen = runCatching { await("the status on screen") { exists(status) } }.isSuccess
        if (!onScreen) {
            compose.onNodeWithTag(list).performScrollToIndex(0)
            compose.waitForIdle()
            error("The status is not on screen; after scrolling to the top it exists=${exists(status)}")
        }
        compose.onNodeWithTag(status).assertIsDisplayed()
    }

    @Test fun aFailureArrivingAfterSavedGenresShowedIsOnTheScreenWithoutScrolling() {
        server.genres = (1..40).map { "Genre $it" }
        aLateFailureIsOnTheScreen("library.genres", "library.genres.retry", "getGenres", fail = { server.failGenres = true }) {
            GenresList(it, actions)
        }
    }

    @Test fun aFailureArrivingAfterSavedArtistsShowedIsOnTheScreenWithoutScrolling() {
        server.artistCount = 40
        aLateFailureIsOnTheScreen("library.artists", "library.artists.retry", "getArtists", fail = { server.failArtists = true }) {
            ArtistsList(it, actions)
        }
    }

    @Test fun aFailureArrivingAfterSavedMostPlayedShowedIsOnTheScreenWithoutScrolling() {
        server.albumListSize = 40
        aLateFailureIsOnTheScreen("mostPlayed.list", "mostPlayed.retry", "getAlbumList2", firstRow = "mostPlayed.item.0",
            fail = { server.failAlbumLists = true }) { MostPlayedPage(account, it, actions) }
    }

    @Test fun aFailureArrivingAfterSavedRecentlyPlayedShowedIsOnTheScreenWithoutScrolling() {
        server.albumListSize = 40
        aLateFailureIsOnTheScreen("recentlyPlayed.list", "recentlyPlayed.retry", "getAlbumList2", firstRow = "recentlyPlayed.item.0",
            fail = { server.failAlbumLists = true }) { RecentAlbumsPage(account, it, actions, com.legitimateapps.dulcet.core.AndroidAlbumListType.Recent) }
    }

    @Test fun aFailureArrivingAfterSavedRecentlyPlayedShowedAtTheLargestFontIsOnTheScreenWithoutScrolling() {
        server.albumListSize = 40
        aLateFailureIsOnTheScreen("recentlyPlayed.list", "recentlyPlayed.retry", "getAlbumList2", firstRow = "recentlyPlayed.item.0",
            fontScale = 2f, fail = { server.failAlbumLists = true }) { RecentAlbumsPage(account, it, actions, com.legitimateapps.dulcet.core.AndroidAlbumListType.Recent) }
    }

    // ---- Album orders -------------------------------------------------------------------------------

    @Test fun theAlbumsOfferApplesOrdersAndTheChosenOneIsReadFromItsStart() {
        host()
        click("library.view.albums")
        await("albums by title") { text("library.albums.item.0") == "Album 1 by alphabeticalByName" }
        assertEquals("Title", text("library.albums.sort"))

        click("library.albums.sort")
        val offered = AlbumSort.CHOICES.map { AlbumSort.tag(it) }
        for (tag in offered) assertTrue(exists("library.albums.sort.$tag"), "the menu offers $tag")
        assertEquals(listOf("alphabeticalbyname", "alphabeticalbyartist", "newest", "recent", "frequent", "highest", "random", "byyear"),
            offered, "the Apple shells' sortChoices, in their order")
        assertTrue(selected("library.albums.sort.alphabeticalbyname"), "the order showing is marked")

        click("library.albums.sort.newest")
        await("albums, newest first") { text("library.albums.item.0") == "Album 1 by newest" }
        assertEquals("Recently added", text("library.albums.sort"))
        assertEquals(listOf("alphabeticalByName", "newest"), albumsScreenReads().map { it["type"] }.distinct(),
            "the Albums screen read by title, then newest")
        assertEquals("0", albumsScreenReads().first { it["type"] == "newest" }["offset"], "read from its start")
        assertTrue("albums:Newest" in observed("library.surface").surfaces)
        assertEquals("Newest", savedSort(), "the choice is kept for this account on this device")
    }

    /** §16.14: offline, an order never read shows what this device has seen, sorted here and labelled. */
    @Test fun anOrderNeverReadChosenOfflineShowsWhatThisDeviceHasSeenAndSaysSo() {
        host()
        click("library.view.albums")
        await("albums by title") { text("library.albums.item.0") == "Album 1 by alphabeticalByName" }
        val network = PlatformNetwork(context)
        network.lose()
        compose.waitForIdle()
        click("library.albums.sort")
        click("library.albums.sort.highest")
        await("the authoritative local offline projection") {
            observed("library.surface").surfaces["albums:Highest"]?.lastOrNull()?.order == com.legitimateapps.dulcet.core.AndroidLibraryItemsOrder.LocalView
        }
        assertEquals("Top rated", text("library.albums.sort"))
        assertEquals("Highest", savedSort())
        assertTrue(exists("library.albums.item.0"), "the albums this device has seen are shown")
        assertTrue(server.requests("getAlbumList2").none { it["type"] == "highest" }, "the order chosen offline is not read")
        network.restore()
    }

    @Test fun theOrderChosenEarlierOnThisDeviceIsTheOneTheAlbumsOpenIn() {
        saveSort("Highest")
        host()
        click("library.view.albums")
        await("albums, top rated") { text("library.albums.item.0") == "Album 1 by highest" }
        assertEquals("Top rated", text("library.albums.sort"))
        assertEquals(listOf("highest"), albumsScreenReads().map { it["type"] }.distinct(),
            "no other order was read for the Albums screen")
    }

    @Test fun onlyTheOffersAreKeptAndAnythingElseStoredOpensByTitle() {
        assertFailsWith<IllegalArgumentException> { AlbumSort.save(context, AndroidAlbumListType.ByGenre) }
        context.getSharedPreferences("dulcet.ui", 0).edit().putString("library.albumSort", "ByGenre").commit()
        assertEquals(AndroidAlbumListType.AlphabeticalByName, AlbumSort.load(context))
        context.getSharedPreferences("dulcet.ui", 0).edit().putString("library.albumSort", "Random").commit()
        assertEquals(AndroidAlbumListType.Random, AlbumSort.load(context), "control: a stored offer is read back")
    }

    private fun saveSort(sort: String) {
        val reader = AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true)
        assertTrue(reader.setLibraryCategory("albums", sort, "all"))
        assertEquals(sort, savedSort())
    }

    private fun savedSort(): String {
        var value: String? = null
        AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true).libraryDepthState { value = it.sort("albums") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (value == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        return checkNotNull(value)
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    /**
     * The Albums screen's `getAlbumList2` reads: a window's page, not a home row's single page of 20
     * (§16.9), which a reconnect may re-read at any time, `newest` among them.
     */
    private fun albumsScreenReads() = server.requests("getAlbumList2").filter { it["size"] != "20" }

    private fun click(tag: String) {
        if (tag.startsWith("library.view.")) compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** The first text of the node with [tag], its descendants' merged in. */
    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.first().text

    private fun selected(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun observed(tag: String): LibraryObservationState =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[LibraryObservation]

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.endpoints()}" }
            Thread.sleep(10)
        }
    }
}
