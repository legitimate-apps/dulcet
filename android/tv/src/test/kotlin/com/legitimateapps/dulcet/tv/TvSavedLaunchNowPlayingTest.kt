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

/** A saved account reconnects and delivers its outbox with the restored queue still paused. */
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

    @Test fun aSavedLaunchAutomaticallyReconnectsAndDeliversTheWaitingPlayWithTheQueuePaused() {
        val saved = AndroidAccountCredentialStore(context).save("Fixture", server.url, "u", "p", true)
        leaveEarlierRun(context, saved.id, server.url, queue = listOf("jazz-song-1", "jazz-song-2"), unsentPlays = listOf("ambient-song-1"))
        val playback = launch(SearchAccount(saved.id, server.url, "u", "p", true))
        await("the restored queue") { playback.state.value.queue.size == 2 }
        await("the waiting play delivered without a tap") {
            server.requests("scrobble").any { it["submission"] == "true" && it["id"] == "ambient-song-1" }
        }
        await("the restored entry read") { server.requests("getSong").any { it["id"] == "jazz-song-1" } }
        await("automatic epoch read") { server.requests("getScanStatus").isNotEmpty() }
        assertFalse(playback.state.value.playWhenReady, "restored queue stays paused")
        assertFalse(playback.state.value.needsReconnect)
        await("Play focused") { focused("tv.player.playpause") }
        repeat(6) { if (!focused("tv.player.lyrics")) key(Key.DirectionRight) }
        assertTrue(focused("tv.player.lyrics"), "Lyrics stays reachable from Play")
        key(Key.DirectionCenter)
        repeat(6) { if (!focused("tv.player.playpause")) key(Key.DirectionLeft) }
        assertTrue(focused("tv.player.playpause"), "Return to Play")
        assertFalse(exists("tv.player.reconnect"))
    }

    @Test fun aSavedPlayerShowsAuthenticationFailureAndManualReconnect() {
        server.scanFailureCode = 40
        val saved = AndroidAccountCredentialStore(context).save("Fixture", server.url, "u", "p", true)
        leaveEarlierRun(context, saved.id, server.url, queue = listOf("jazz-song-1"), unsentPlays = emptyList())
        launch(SearchAccount(saved.id, server.url, "u", "p", true))
        await("authentication words and reconnect in the player") {
            exists("tv.player.saved") && exists("tv.player.reconnect")
        }
        val node = compose.onAllNodesWithTag("tv.player.saved", useUnmergedTree = true).fetchSemanticsNodes().single()
        assertTrue(node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any {
            it.text.contains(context.getString(com.legitimateapps.dulcet.shared.R.string.library_error_credentials))
        }, "The authentication failure is stated in the player")
    }

    @Test fun aColdPlayerWithoutLibraryWindowsShowsAuthenticationFailureAfterAnAutomaticRetry() {
        server.scanFailureCode = 54321 // Unknown server errors retry with the reader's backoff.
        val saved = AndroidAccountCredentialStore(context).save("Fixture", server.url, "u", "p", true)
        launch(SearchAccount(saved.id, server.url, "u", "p", true))
        await("first transient reconnect answered") { server.requests("getScanStatus").isNotEmpty() && playerSays(com.legitimateapps.dulcet.shared.R.string.library_error_server) }
        server.scanFailureCode = 40
        await("automatic retry shows authentication without opening lyrics") {
            server.requests("getScanStatus").size >= 2 && playerSaysCredentials()
        }
        assertTrue(exists("tv.player.reconnect"))
    }

    @Test fun aColdPlayerWithoutLibraryWindowsRemovesItsNoticeAfterAnAutomaticRetrySucceeds() {
        server.scanFailureCode = 54321
        val saved = AndroidAccountCredentialStore(context).save("Fixture", server.url, "u", "p", true)
        launch(SearchAccount(saved.id, server.url, "u", "p", true))
        await("first transient reconnect answered") { server.requests("getScanStatus").isNotEmpty() && playerSays(com.legitimateapps.dulcet.shared.R.string.library_error_server) }
        server.scanFailureCode = null
        await("automatic retry succeeds without opening lyrics") {
            server.requests("getScanStatus").size >= 2 && !exists("tv.player.saved") && !exists("tv.player.reconnect")
        }
    }

    private fun playerSaysCredentials(): Boolean = playerSays(com.legitimateapps.dulcet.shared.R.string.library_error_credentials)

    private fun playerSays(resource: Int): Boolean = compose.onAllNodesWithTag("tv.player.saved", useUnmergedTree = true)
        .fetchSemanticsNodes().any { node -> node.config[SemanticsProperties.Text].any {
            it.text.contains(context.getString(resource))
        } }

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
