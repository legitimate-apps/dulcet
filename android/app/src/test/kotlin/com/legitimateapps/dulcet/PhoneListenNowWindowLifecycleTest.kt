package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.library.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneListenNowWindowLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { dulcetHome = true }
    private val account = SearchAccount("home-window-account", server.url, "u", "p", true)
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

    @Test fun refreshUpdatesAnEmptyRecentPlaylistShelfWhileItIsOffscreen() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                PhoneListenNow(account, session, PhoneActions(openAlbum = {}, openArtist = {}, back = {},
                    playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null }), AndroidPlaybackState(), null)
                LibraryLifecycle(session)
            }
        }
        await { recentReads() > 0 && exists("listenNow.recent.item.0") }
        compose.onNodeWithTag("listenNow.surface").performScrollToIndex(10)
        await { exists("listenNow.random.item.0") }
        compose.onNodeWithTag("listenNow.recentPlaylists").assertDoesNotExist()
        val before = recentReads()
        server.homePlaylists = listOf("played-playlist" to "Just Played Playlist")
        compose.runOnIdle { session.refresh() }
        // The window must receive Refresh before its lazy row is composed again.
        await { recentReads() > before }
        compose.onNodeWithTag("listenNow.surface").performScrollToIndex(2)
        await { exists("listenNow.recentPlaylists.item.0") }
        compose.onNodeWithText("Just Played Playlist").assertIsDisplayed()
    }

    private fun recentReads() = server.requests("getDulcetHome").count { it["shelf"] == "recentPlaylists" }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "Timed out; recent playlist reads=${recentReads()}: " + compose.onRoot().printToString() }
            Thread.sleep(10)
        }
    }
}
