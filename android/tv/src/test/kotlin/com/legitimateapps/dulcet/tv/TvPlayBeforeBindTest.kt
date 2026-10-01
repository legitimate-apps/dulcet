package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * The TV album's Play pressed before the playback service binds is held and made once it binds, and
 * Now Playing opens only then (spec §14.1). Over TvLibraryEntry as it ships -- the production session
 * and reader, a real controller -- with only the server a loopback fixture and the binding handed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvPlayBeforeBindTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val provider = "provider:tv-bind"
    private val account = SearchAccount(provider, server.url, "u", "p", true)
    private val controllers = mutableListOf<AndroidPlaybackController>()

    private fun controller() = AndroidPlaybackController(context,
        PlaybackEndpointAccount(provider, server.url, "u", "p", true)).also { controllers += it }

    @After fun close() {
        controllers.forEach { it.close() }
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

    /**
     * Before the fix the press found no controller and was lost: nothing was asked for and Now Playing
     * never opened. Now nothing happens until the service binds, then the album plays and Now Playing
     * opens -- once.
     */
    @Test fun theAlbumsPlayPressedBeforeTheServiceBindsIsMadeOnceItBinds() {
        // Made before the screens start, as the service makes it before it can bind, and handed in
        // only later: opening the database while the library session writes to it would block the
        // main thread on SQLite's busy wait, which is not what this test is about.
        val controller = controller()
        var bound by mutableStateOf<AndroidPlaybackController?>(null)
        compose.setContent { MaterialTheme { TvLibraryEntry(account, playback = bound) { _, _ -> } } }
        openFirstAlbum()
        key(Key.DirectionCenter)
        settle()
        assertTrue(server.requests("getSong").isEmpty(), "setup: nothing can play before the service binds")
        assertNull(shadowOf(context).nextStartedActivity, "Now Playing does not open for a Play not yet made")

        compose.runOnIdle { bound = controller }
        await("the held Play made once the service binds") { server.requests("getSong").isNotEmpty() }
        assertAlbumSongAsked()
        assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, shadowOf(context).nextStartedActivity?.action,
            "Now Playing opens once the Play is made")
        assertNull(shadowOf(context).nextStartedActivity, "and only once")
    }

    /** With the service bound, the album's Play is made at once and opens Now Playing, as it always did. */
    @Test fun theAlbumsPlayPressedOnceBoundIsMadeAtOnce() {
        val controller = controller()
        compose.setContent { MaterialTheme { TvLibraryEntry(account, playback = controller) { _, _ -> } } }
        openFirstAlbum()
        key(Key.DirectionCenter)
        await("the Play made") { server.requests("getSong").isNotEmpty() }
        assertAlbumSongAsked()
        assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, shadowOf(context).nextStartedActivity?.action)
    }

    /** Opens the first home card, an album, and waits for its Play to hold focus. */
    private fun openFirstAlbum() {
        await("the first home card to take focus") { focused("library.home.0.item.0") }
        key(Key.DirectionCenter)
        await("the album and focus on Play") { exists("album.play") && focused("album.play") }
    }

    /** Exactly one song was asked for: the one song of an album the screen read. */
    private fun assertAlbumSongAsked() {
        val asked = server.requests("getSong").map { it["id"].orEmpty() }.toSet()
        val albums = server.requests("getAlbum").map { it["id"].orEmpty() }.toSet()
        assertEquals(1, asked.size, "one song asked for: $asked")
        assertTrue(asked.single().removeSuffix("-song-1") in albums, "an album's song: $asked of $albums")
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        compose.waitForIdle()
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000) {
                shadowOf(Looper.getMainLooper()).idle()
                runCatching(condition).getOrDefault(false)
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; focused=${focusedTags()}; requests=${server.endpoints()}", timeout)
        }
    }
}
