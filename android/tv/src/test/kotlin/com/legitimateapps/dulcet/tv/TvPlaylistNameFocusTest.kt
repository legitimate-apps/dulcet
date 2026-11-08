package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Create is disabled while the name is blank. tv-material keeps a disabled button focusable, so Done
 * on an empty name used to put the remote on a control that cannot act and paints no focus (round 4's
 * Playlist create-empty sheet). The remote passes it to Cancel instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television")
class TvPlaylistNameFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { playlists = mutableMapOf() }
    private val account = SearchAccount("tv-name-focus", server.url, "u", "p", true)
    private lateinit var session: LibrarySession

    @After fun close() {
        if (::session.isInitialized) session.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    private fun focused(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun focusedTags() = compose.onAllNodes(SemanticsMatcher("focused") { it.config.getOrNull(SemanticsProperties.Focused) == true },
        useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) ?: "?" }

    @Test fun doneOnAnEmptyNameSkipsTheDisabledCreateToCancel() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                TvAddToPlaylist(session, TvPlaylistAddition.Songs(listOf("s1"), "A song")) {}
                LibraryLifecycle(session)
            }
        }
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); compose.onAllNodesWithTag("playlists.add.new").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); focused("playlists.add.new") }
        compose.onNodeWithTag("playlists.add.new").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); focused("playlists.add.name") }
        // The name starts as the song's title; the person clears it and presses Done.
        compose.onNodeWithTag("playlists.add.name").selectWithRemote().performTextReplacement("")
        compose.onNodeWithTag("playlists.add.name").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertTrue(focused("playlists.add.name.cancel"), "Cancel holds focus; focused=${focusedTags()}")
        assertFalse(focused("playlists.add.name.confirm"), "the disabled Create takes no focus; focused=${focusedTags()}")

        // With a name the same press lands on Create.
        compose.onNodeWithTag("playlists.add.name.cancel").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithTag("playlists.add.name").selectWithRemote().performTextReplacement("Road trip")
        compose.onNodeWithTag("playlists.add.name").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertTrue(focused("playlists.add.name.confirm"), "an enabled Create is where Down lands; focused=${focusedTags()}")
    }
}
