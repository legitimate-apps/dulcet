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

    private fun host() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                if (album == null) LibraryHome(account, session, actions)
                else AlbumScreen(account, session, album!!, null, actions)
                LibraryLifecycle(session)
            }
        }
    }

    @Test fun downloadedViewDoesNotOfferNetworkRetryOrARedundantDownloadedFilter() {
        host()
        compose.onNodeWithTag("library.view.downloaded").performScrollTo().performClick()
        await { exists("library.downloaded.empty") }
        compose.onNodeWithTag("library.downloaded.retry").assertDoesNotExist()
        compose.onNodeWithTag("library.downloaded.freshness").assertDoesNotExist()
        compose.onNodeWithTag("library.downloaded.filter.downloaded").assertDoesNotExist()
        compose.onNodeWithTag("library.downloaded.filter.favorites").assertExists()
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

    @Test fun pinningAnAlbumPublishesAtTheTopAndUnpinRemovesItWithoutChangingFavorites() {
        host()
        compose.onNodeWithTag("library.view.albums").performClick()
        await { exists("library.albums.item.0") }
        compose.onNodeWithTag("library.albums.item.0").performClick()
        await { exists("album.pin") }
        compose.onNodeWithTag("album.pin").performClick()
        await { session.depth.value?.pinCount == 1 }
        assertEquals("album", session.depth.value!!.pinKind(0))
        assertTrue(server.requests("star").isEmpty(), "pin is distinct from a server favorite")
        compose.onNodeWithTag("album.back").performClick()
        compose.onNodeWithTag("library.view.home").performClick()
        await { exists("library.pinned.item.0") }
        assertTrue(text("library.pinned.item.0").contains("Album 1"))
        compose.onNodeWithTag("library.pinned.unpin.0").performClick()
        await { session.depth.value?.pinCount == 0 && !exists("library.pinned.item.0") }
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
        host()
        compose.onNodeWithTag("library.view.songs").performClick()
        await { exists("library.songs.track.0") }
        assertTrue(server.requests("search3").any { it["query"] == "" })
        compose.onNodeWithTag("library.songs.shuffleAll").performClick()
        await { played.isNotEmpty() }
        assertEquals((1..8).map { "song-$it" }.toSet(), played.single().toSet())
        assertTrue(server.requests("getRandomSongs").all { it["size"] == "500" })
        assertTrue(server.requests("getRandomSongs").isNotEmpty())
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) { compose.waitForIdle(); check(System.nanoTime() < deadline) { "Timed out with ${session.depth.value?.pinCount} pins; pinned frames=${session.observation.value.surfaces["pinned"]}: " + compose.onRoot().printToString() }; Thread.sleep(10) }
    }
}
