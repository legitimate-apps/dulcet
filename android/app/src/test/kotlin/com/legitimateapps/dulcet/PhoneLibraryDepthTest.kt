package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.*
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneLibraryDepthTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { librarySongs = (1..8).map { "song-$it" to "Song $it" } }
    private val account = SearchAccount("depth-account", server.url, "u", "p", true)
    private lateinit var session: LibrarySession
    private var album by mutableStateOf<String?>(null)
    private val played = mutableListOf<List<String>>()
    private val actions = PhoneActions(openAlbum = { album = it }, openArtist = {}, back = { album = null },
        playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null },
        playTracksFrom = { tracks, _, _, _ -> played += tracks.map { it.rawId } })

    @After fun close() {
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    private var browse by mutableStateOf(false)
    private fun host(browseCatalog: Boolean = false) {
        browse = browseCatalog
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                if (album == null) LibraryHome(account, session, actions, title = if (browse) com.legitimateapps.dulcet.shared.R.string.library_browse else R.string.tab_library)
                else AlbumScreen(account, session, album!!, null, actions)
                LibraryLifecycle(session)
            }
        }
    }

    @Test fun emptyDownloadedViewOffersLibraryExitWithoutNetworkRetryOrFilters() {
        host()
        compose.onNodeWithTag("library.view.downloaded").performScrollTo().performClick()
        await { exists("library.downloaded.empty") }
        compose.onNodeWithTag("library.downloaded.retry").assertDoesNotExist()
        compose.onNodeWithTag("library.downloaded.freshness").assertDoesNotExist()
        compose.onNodeWithTag("library.downloaded.filter.downloaded").assertDoesNotExist()
        compose.onNodeWithTag("library.downloaded.filter.favorites").assertDoesNotExist()
        compose.onNodeWithTag("library.downloaded.browse").assertExists()
    }

    @Test fun emptyDownloadsBrowseActionOpensTheCatalogInsteadOfLeavingLibrary() {
        var openedRoute: String? = null
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                DeviceSongs(account, session, actions.copy(openRoute = { openedRoute = it }), downloaded = true, playingRawId = null)
                LibraryLifecycle(session)
            }
        }
        await { exists("library.downloaded.empty") }
        compose.onNodeWithTag("library.downloaded.browse").performClick()
        assertEquals("browse", openedRoute)
    }

    @Test fun filteredDownloadsKeepTheirSelectionAndOfferShowAllInsteadOfSayingNoDownloads() {
        host()
        await { session.depth.value != null }
        compose.runOnIdle { session.setCategoryFilter("downloaded", "favorites") }
        await { session.depth.value?.filter("downloaded") == "favorites" }
        compose.onNodeWithTag("library.view.downloaded").performScrollTo().performClick()
        await { exists("library.downloaded.showAll") }
        compose.onNodeWithTag("library.downloaded.filter.favorites").assertIsSelected()
        compose.onNodeWithText("No favorite downloads").assertIsDisplayed()
        compose.onNodeWithTag("library.downloaded.showAll").performClick()
        await { exists("library.downloaded.browse") && session.depth.value?.filter("downloaded") == "all" }
        assertEquals("all", session.depth.value?.filter("downloaded"))
    }

    @Test fun downloadedFavoritesAndSortProjectOnlyTheSelectedPlayableRows() {
        fun track(id: String, title: String, favorite: Boolean, plays: Long) = AndroidLibraryItem.Track(
            id, title, null, null, "Artist", null, null, null, 1_000L, null, null, favorite, null, plays,
            AndroidLibraryPlayability.Downloaded, false)
        val rows = listOf(track("z", "Zulu", true, 2), track("hidden", "Middle", false, 99), track("a", "Alpha", true, 8))
        assertEquals(listOf("a", "z"), projectDownloadedTracks(rows, "title", "favorites").map { it.rawId })
        assertEquals(listOf("z", "a"), projectDownloadedTracks(rows, "titleDescending", "favorites").map { it.rawId })
        assertEquals(listOf("a", "z"), projectDownloadedTracks(rows, "mostPlayed", "favorites").map { it.rawId })
        assertEquals(listOf("hidden", "a", "z"), projectDownloadedTracks(rows, "mostPlayed", "all").map { it.rawId })
    }

    @Test fun initialSavedCategorySnapshotArrivesWithoutAnotherRead() {
        val reader = AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true)
        reader.setLibraryCategory("albums", "ByYear", "favorites")
        session = LibrarySession(context, account, true)
        await { session.depth.value != null }
        assertEquals("ByYear", session.depth.value!!.sort("albums"))
        assertEquals("favorites", session.depth.value!!.filter("albums"))
        session.close()
    }

    @Test fun pinningAnAlbumExposesThePinnedDestinationAndUnpinRemovesItWithoutChangingFavorites() {
        host(browseCatalog = true)
        compose.onNodeWithTag("library.view.albums").performClick()
        await { exists("library.albums.item.0") }
        compose.onNodeWithTag("library.albums.item.0").performClick()
        await { exists("album.menu") }
        compose.onNodeWithTag("album.menu").performClick()
        await { exists("album.pin") }
        compose.onNodeWithTag("album.pin").performClick()
        await { session.depth.value?.pinCount == 1 }
        assertEquals("album", session.depth.value!!.pinKind(0))
        assertTrue(server.requests("star").isEmpty(), "pin is distinct from a server favorite")
        compose.onNodeWithTag("album.back").performClick()
        compose.runOnIdle { browse = false }
        await { exists("library.view.pinned") }
        compose.onNodeWithTag("library.view.pinned").performScrollTo().performClick()
        await { exists("library.pinned.item.0") }
        assertTrue(text("library.pinned.item.0").contains("Album 1"))
        assertTrue(text("library.pinned.item.0").contains("Album ·"), "Pins identify their kind in supporting text")
        compose.onNodeWithTag("library.pinned.item.0.menu").performClick()
        compose.onNodeWithTag("library.pinned.unpin.0").performClick()
        await { session.depth.value?.pinCount == 0 && !exists("library.pinned.item.0") }
    }

    @Test fun aPinArrivingAfterRootLayoutExposesPinnedDestinationAndRefreshKeepsItsPosition() {
        host(browseCatalog = true)
        await { session.depth.value?.pinCount == 0 && exists("library.home.0.item.0") }
        val albums = session.openAlbums(AndroidAlbumListType.AlphabeticalByName)
        try {
            await { albums.state.value?.items?.isNotEmpty() == true }
            compose.runOnIdle { session.togglePin(albums.state.value!!.items.first()) }
            await { session.depth.value?.pinCount == 1 }
            compose.runOnIdle { browse = false }
            await { exists("library.view.pinned") }
            compose.onNodeWithTag("library.view.pinned").performScrollTo().assertIsDisplayed()
            assertTrue(text("library.view.pinned").contains("Pinned"))
            val scrolled = compose.onNodeWithTag("library.home").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.onNodeWithTag("library.pinned.item.0").assertDoesNotExist()
            compose.onNodeWithTag("library.view.albums").assertDoesNotExist()
            compose.onNodeWithTag("library.view.playlists").assertExists()
            val before = session.depth.value
            compose.runOnIdle { session.refreshDepth() }
            await { session.depth.value !== before }
            compose.waitForIdle()
            assertEquals(scrolled, compose.onNodeWithTag("library.home").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
                "Refreshing pin metadata must retain the user's position")
        } finally { albums.close() }
    }

    @Test fun rapidCategoryAndPinChangesKeepTheLastIntentBeforeSnapshotsReturn() {
        host()
        val surface = session.openAlbums(AndroidAlbumListType.AlphabeticalByName)
        try {
            await { surface.state.value?.items?.isNotEmpty() == true && session.depth.value != null }
            val album = surface.state.value!!.items.first()
            var saved: LibraryDepthState? = null
            compose.runOnIdle {
                session.setCategorySort("albums", "ByYear")
                session.setCategoryFilter("albums", "favorites")
                session.togglePin(album)
                session.togglePin(album)
                AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true).libraryDepthState { saved = it }
            }
            await { saved != null }
            assertEquals("ByYear", saved!!.sort("albums"))
            assertEquals("favorites", saved!!.filter("albums"))
            assertEquals(0, saved!!.pinCount)
            await { session.depth.value?.filter("albums") == "favorites" && session.depth.value?.pinCount == 0 }
        } finally { surface.close() }
    }

    @Test fun songsShuffleRequestsTheWholeCatalogInsteadOfShufflingTheVisiblePage() {
        host(browseCatalog = true)
        compose.onNodeWithTag("library.view.songs").performClick()
        await { exists("library.songs.track.0") }
        assertTrue(server.requests("search3").any { it["query"] == "" })
        compose.onNodeWithTag("library.songs.shuffleAll").performClick()
        await { played.isNotEmpty() }
        assertEquals((1..8).map { "song-$it" }.toSet(), played.single().toSet())
        assertTrue(server.requests("getRandomSongs").all { it["size"] == "500" })
        assertTrue(server.requests("getRandomSongs").isNotEmpty())
    }

    @Test fun anEmptyFilteredSongsListDoesNotOfferCatalogWideShuffleBesideShowAll() {
        host(browseCatalog = true)
        compose.onNodeWithTag("library.view.songs").performClick()
        await { exists("library.songs.track.0") }
        compose.onNodeWithTag("library.songs.filter.favorites").performClick()
        await { exists("library.songs.showAll") }
        compose.onNodeWithTag("library.songs.empty").assertIsDisplayed()
        compose.onNodeWithTag("library.songs.shuffleAll").assertDoesNotExist()
        compose.onNodeWithTag("library.songs.showAll").performClick()
        await { exists("library.songs.track.0") }
        compose.onNodeWithTag("library.songs.shuffleAll").assertExists()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) { compose.waitForIdle(); check(System.nanoTime() < deadline) { "Timed out with ${session.depth.value?.pinCount} pins; pinned frames=${session.observation.value.surfaces["pinned"]}: " + compose.onRoot().printToString() }; Thread.sleep(10) }
    }
}
