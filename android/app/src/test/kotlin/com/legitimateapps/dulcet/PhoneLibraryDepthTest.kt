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
        while (!runCatching(condition).getOrDefault(false)) { compose.waitForIdle(); check(System.nanoTime() < deadline); Thread.sleep(10) }
    }
}
