package com.legitimateapps.dulcet

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.playback.PlaybackService
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import com.legitimateapps.dulcet.search.conformance.leaveEarlierRun
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * CONF-10b for playback on the phone (spec §13.1): a relaunch into a saved account the person has not
 * connected, with the queue the last run left -- its current song not downloaded -- and a play that
 * run could not deliver. The phone runs as it ships: PhoneApp as MainActivity hosts it for a saved
 * account, and the PRODUCTION playback service's controller; only the server is a loopback fixture
 * that records every request. Nothing reaches it -- no song read or resolve for the restored entry, no
 * scrobble, no library read -- not at launch and not when the person presses Play, which says the song
 * needs Reconnect. Reconnect in the player then delivers the waiting play and reads the restored entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostCredentialCipher::class], instrumentedPackages = ["com.legitimateapps.dulcet"],
    qualifiers = "w411dp-h891dp-port")
class SavedLaunchPlaybackTest {
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
        context.getSharedPreferences("dulcet.ui", 0).edit().clear().commit()
    }

    @Test fun aRelaunchWithAQueueAndAnUnsentPlaySendsNothingUntilReconnectThenDeliversThePlay() {
        val saved = AndroidAccountCredentialStore(context).save("Fixture", server.url, "u", "p", true)
        leaveEarlierRun(context, saved.id, server.url, queue = listOf("jazz-song-1", "jazz-song-2"), unsentPlays = listOf("ambient-song-1"))
        val playback = launch(SearchAccount(saved.id, server.url, "u", "p", true))

        await("the restored queue, from the device") { playback.state.value.queue.size == 2 && exists("player.mini") }
        settle()
        assertFalse(playback.state.value.playWhenReady, "restored paused")
        assertEquals(emptyList(), server.endpoints(), "nothing reaches the server at launch")

        click("player.mini.playpause")
        await("the mini player to say the song needs Reconnect") { exists("player.mini.needsReconnect") }
        click("player.mini")
        await("the player to say so too, with Reconnect") { exists("player.needsReconnect") && exists("player.reconnect") }
        settle()
        assertFalse(playback.state.value.playWhenReady)
        assertEquals(emptyList(), server.endpoints(), "Play sends nothing either")

        click("player.reconnect")
        await("the waiting play delivered") {
            server.requests("scrobble").any { it["submission"] == "true" && it["id"] == "ambient-song-1" }
        }
        await("the restored entry read") { server.requests("getSong").any { it["id"] == "jazz-song-1" } }
        await("the player's notice gone") { !exists("player.saved") }
        assertFalse(playback.state.value.needsReconnect)
    }

    private fun launch(account: SearchAccount): AndroidPlaybackController {
        val controller = Robolectric.buildService(PlaybackService::class.java).create().also { service = it }
        controller.get().onBind(Intent(PlaybackService.LOCAL_BIND))
        val playback = assertNotNull(controller.get().playback, "the service builds the saved account's controller")
        val dependencies = object : SearchHostDependencies {
            override fun loadAccount(context: Context): SearchAccount = account
            override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter =
                ProductionSearchHostDependencies.createPresenter(account, context, foreground)
        }
        // As MainActivity hosts a saved account.
        compose.setContent { MaterialTheme { PhoneApp(account, dependencies, PhonePlaybackRequests(), playback, untilReconnectChosen = true) } }
        compose.waitForIdle()
        return playback
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        await(tag) { exists(tag) }
        compose.onNodeWithTag(tag).performClick()
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
