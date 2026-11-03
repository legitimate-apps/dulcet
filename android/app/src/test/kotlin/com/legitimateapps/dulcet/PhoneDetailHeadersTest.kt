package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Scrolls the real detail screens backed by production publications, rather than toggling a mock collapsed flag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h640dp-port")
class PhoneDetailHeadersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply {
        albumSongTitles = (1..20).map { "Song $it" }
        librarySongs = (1..20).map { "song-$it" to "Song $it" }
        songsByGenre = mapOf("Jazz" to librarySongs!!)
        playlists = mutableMapOf("mix" to ("Fixture Mix" to librarySongs!!.map { it.first }))
    }
    private val account = SearchAccount("detail-header-phone", server.url, "u", "p", true)
    private var session: LibrarySession? = null
    private val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {},
        playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null })

    @After fun close() {
        session?.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aScrolledAlbumRetainsItsTitleAndSongCount() = exercise("album", "Album 1 by alphabeticalByName", "20 songs", "album.tracks", 1)
    @Test fun aScrolledArtistRetainsItsTitleAndAlbumCount() = exercise("artist", "Fixture Artist", "1 album", "artist.albums", 1)
    @Test fun aScrolledGenreKeepsItsTitleAndAddsItsSongCount() = exercise("genre", "Jazz", "20 songs", "genre.tracks", 1)
    @Test fun aScrolledPlaylistRetainsItsTitleAndSongCount() = exercise("playlist", "Fixture Mix", "20 songs", "playlist.entries", 2)
    @Test fun aCollapsedAlbumTitleAndCountRemainVisibleAtLargeFont() = exercise("album", "Album 1 by alphabeticalByName", "20 songs", "album.tracks", 1, 2f)

    private fun exercise(kind: String, title: String, count: String, listTag: String, afterHeader: Int, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    val current = remember { LibrarySession(context, account, foreground = true) }.also { session = it }
                    when (kind) {
                        "album" -> AlbumScreen(account, current, "alphabeticalByName-1", null, actions)
                        "artist" -> ArtistScreen(account, current, "artist-1", actions)
                        "genre" -> GenreScreen(account, current, "Jazz", null, actions)
                        "playlist" -> PlaylistScreen(account, current, "mix", null, actions)
                    }
                    LibraryLifecycle(current)
                }
            }
        }
        await("the expanded $kind identity") { exists("$kind.title") }
        if (kind == "genre") await("the genre count after its real tracks arrive") { exists("genre.count") }
        if (kind == "artist") await("the artist's five top songs below the hero") { exists("artist.topSongs.4") }
        compose.onNodeWithTag("$kind.title").assertTextEquals(title)
        compose.onAllNodesWithTag("$kind.collapsed.count").assertCountEquals(0)
        if (kind != "genre") compose.onAllNodesWithTag("$kind.collapsed.title").assertCountEquals(0)

        compose.onNodeWithTag(listTag).performScrollToIndex(afterHeader)
        await("the scrolled $kind count") { exists("$kind.collapsed.count") }
        compose.onNodeWithTag(if (kind == "genre") "genre.title" else "$kind.collapsed.title")
            .assertTextEquals(title).assertIsDisplayed()
        compose.onNodeWithTag("$kind.collapsed.count").assertTextEquals(count).assertIsDisplayed()

        compose.onNodeWithTag(listTag).performScrollToIndex(0)
        await("the $kind hero to return") { !exists("$kind.collapsed.count") }
        compose.onNodeWithTag("$kind.title").assertTextEquals(title)
        if (kind != "genre") compose.onAllNodesWithTag("$kind.collapsed.title").assertCountEquals(0)
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(what: String, condition: () -> Boolean) {
        compose.waitUntil(what, 30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
}
