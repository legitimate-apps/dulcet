package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.playback.PlaybackService
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import com.legitimateapps.dulcet.search.conformance.leaveEarlierRun
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * CONF-10b for the TV's cold Now Playing entry (spec §13.1): the player opened from the notification
 * or a played track, with no library activity behind it, on a saved account the person has not
 * connected in this process. The last run left a queue whose current song is not downloaded and a play
 * it could not deliver. The player runs as TvPlaybackActivity hosts it, over the PRODUCTION playback
 * service's controller and the player's own library session; only the server is a loopback fixture that
 * records every request. Nothing reaches it -- the restored entry, the waiting play, the heart, the
 * stars, the lyrics opened -- and Play says the song needs Reconnect. Reconnect, one UP from Play on
 * the remote, then delivers the play and reads the entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [HostCredentialCipher::class], instrumentedPackages = ["com.legitimateapps.dulcet"],
    qualifiers = "w960dp-h540dp-land-television")
class TvSavedLaunchNowPlayingTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private var service: ServiceController<PlaybackService>? = null

    @After fun close() {
        service?.destroy()
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

    @Test fun aColdNowPlayingOnASavedAccountSendsNothingUntilReconnectThenDeliversThePlay() {
        val saved = AndroidAccountCredentialStore(context).save("Fixture", server.url, "u", "p", true)
        leaveEarlierRun(context, saved.id, server.url, queue = listOf("jazz-song-1", "jazz-song-2"), unsentPlays = listOf("ambient-song-1"))
        val playback = launch(SearchAccount(saved.id, server.url, "u", "p", true))

        await("the restored queue, from the device") { playback.state.value.queue.size == 2 }
        await("Play to hold focus") { focused("tv.player.playpause") }
        // The lyrics, opened: the panel reads only what this device has kept.
        repeat(6) { if (!focused("tv.player.lyrics")) key(Key.DirectionRight) }
        assertTrue(focused("tv.player.lyrics"), "the lyrics toggle, right of Play; focused=${focusedTags()}")
        key(Key.DirectionCenter)
        repeat(6) { if (!focused("tv.player.playpause")) key(Key.DirectionLeft) }
        assertTrue(focused("tv.player.playpause"), "back to Play; focused=${focusedTags()}")
        key(Key.DirectionCenter)
        settle()
        assertFalse(playback.state.value.playWhenReady, "nothing plays")
        assertEquals(emptyList(), server.endpoints(), "nothing reaches the server before Reconnect")
        await("Play to say the song needs Reconnect") { exists("tv.player.needsReconnect") }

        key(Key.DirectionUp)
        assertTrue(focused("tv.player.reconnect"), "Reconnect is one UP from Play; focused=${focusedTags()}")
        key(Key.DirectionCenter)
        await("the waiting play delivered") {
            server.requests("scrobble").any { it["submission"] == "true" && it["id"] == "ambient-song-1" }
        }
        await("the restored entry read") { server.requests("getSong").any { it["id"] == "jazz-song-1" } }
        await("the player's own session reconnected") { server.endpoints().any { it == "getScanStatus" } }
        await("the notice gone") { !exists("tv.player.reconnect") }
    }

    private fun launch(account: SearchAccount): AndroidPlaybackController {
        val controller = Robolectric.buildService(PlaybackService::class.java).create().also { service = it }
        controller.get().onBind(Intent(PlaybackService.LOCAL_BIND))
        val playback = assertNotNull(controller.get().playback, "the service builds the saved account's controller")
        // As TvPlaybackActivity hosts the player.
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by playback.state.collectAsState()
                TvNowPlaying(account, state, playback)
            }
        }
        compose.waitForIdle()
        return playback
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun key(key: Key) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Two seconds of the reader's thread, the controller's and the main looper, so anything sent has been. */
    private fun settle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        compose.waitForIdle()
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000) {
                shadowOf(Looper.getMainLooper()).idle()
                runCatching(condition).getOrDefault(false)
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; requests=${server.endpoints()}", timeout)
        }
    }
}
