package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
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
 * Two things the album split and Library's shelves each changed, over TvLibraryEntry with the production
 * session and reader and a loopback server: how the remote gets from an album's actions to its tracks, now
 * beside them rather than below, and which rows Library's root reads, now that it draws two of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvAlbumEntryAndLibraryRootReadsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("provider:tv-album-entry", server.url, "u", "p", true)

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

    @Test fun libraryReadsTheTwoShelvesItDrawsAndNotTheRowsOnlyBrowseDraws() {
        compose.setContent { MaterialTheme { TvLibraryEntry(account) { _, _ -> } } }
        compose.waitForIdle()
        await("the Library tab") { exists("library.open") }
        focus("library.open")
        key(Key.DirectionCenter)
        await("Library's root and both its shelves' reads") {
            exists("library.view.favourites") && albumListReads("newest") > 0 && albumListReads("recent") > 0
        }
        await("Library's first shelf") { exists("library.home.0.item.0") }
        settle()
        assertEquals(0, albumListReads("frequent"), "Library draws no frequent shelf, so it reads none")
        assertEquals(0, server.requests("getStarred2").size, "Library draws no favourites shelf, so it reads none")

        // The control: the same instruments count these two reads where Browse makes them.
        key(Key.Back)
        await("the Browse tab") { exists("browse.open") }
        focus("browse.open")
        key(Key.DirectionCenter)
        await("Browse's read of the frequent row and the favourites row") {
            albumListReads("frequent") > 0 && server.requests("getStarred2").isNotEmpty()
        }
    }

    @Test fun downFromAnyActionAndRightFromTheLastEnterTheTracksAtTheTopRow() {
        server.albumSongTitles = (1..12).map { "Track $it" }
        reachBrowse()
        key(Key.DirectionRight)
        await("the first album card holding focus") { focused("library.home.0.item.0") }
        key(Key.DirectionCenter)
        await("the album, focus on Play") { exists("album.track.0") && focused("album.play") }

        for (action in listOf("album.play", "album.shuffle", "album.favourite", "album.more")) {
            focus(action)
            key(Key.DirectionDown)
            assertFocused("album.track.0")
        }
        focus("album.more")
        key(Key.DirectionRight)
        assertFocused("album.track.0")
        focus("album.play")
        key(Key.DirectionRight)
        assertFocused("album.shuffle")

        // Scrolled down the list, the way in is the row at the list's top now, never one scrolled away.
        focus("album.track.0")
        repeat(9) { key(Key.DirectionDown) }
        val top = composedTracks().min()
        assertTrue(top > 0, "the list scrolled: rows ${composedTracks()}")
        focus("album.more")
        key(Key.DirectionRight)
        assertFocused("album.track.$top")
    }

    private fun reachBrowse() {
        compose.setContent { MaterialTheme { TvLibraryEntry(account) { _, _ -> } } }
        compose.waitForIdle()
        await("the Browse tab") { exists("browse.open") }
        focus("browse.open")
        key(Key.DirectionCenter)
        await("Browse categories and a loaded home card") { focused("library.view.artists") && exists("library.home.0.item.0") }
    }

    private fun albumListReads(type: String) = server.requests("getAlbumList2").count { it["type"] == type }

    /** Long enough for a read the screen issued as it opened to have reached the server. */
    private fun settle() = repeat(20) {
        compose.waitForIdle()
        Thread.sleep(50)
    }

    private fun composedTracks(): List<Int> = compose.onAllNodes(SemanticsMatcher("a track row") {
        TRACK_ROW.matches(it.config.getOrElse(SemanticsProperties.TestTag) { "" })
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("album.track.").toInt() }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun assertFocused(tag: String) = assertTrue(focused(tag), "$tag holds focus; focused=${focusedTags()}")

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertFocused(tag)
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(60_000) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; focused=${focusedTags()}", timeout)
        }
    }

    private companion object {
        val TRACK_ROW = Regex("album\\.track\\.\\d+")
    }
}
