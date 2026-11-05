package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryConnectionState
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

/** A refresh that fails during use keeps the album on screen and says so, with the way to try again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port")
class PhoneAlbumRefreshNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("album-refresh-phone", server.url, "u", "p", true)
    private var session: LibrarySession? = null
    private val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {},
        playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null })

    @After fun close() {
        session?.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    private fun await(tag: String, present: Boolean = true) = compose.waitUntil(60_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() == present
    }

    @Test fun aFailedRefreshIsSaidAndStaysSaidOffline() {
        compose.setContent {
            MaterialTheme {
                val current = remember { LibrarySession(context, account, foreground = true) }.also { session = it }
                AlbumScreen(account, current, "alphabeticalByName-1", null, actions)
                LibraryLifecycle(current)
            }
        }
        await("album.title")
        await("album.tracks")
        compose.onAllNodes(hasTestTag("album.freshness"), useUnmergedTree = true).fetchSemanticsNodes().let { check(it.isEmpty()) { "a loaded album says nothing" } }
        // The server goes away and the person asks for a refresh: a moment of Failed, then the reader is offline for good.
        server.close()
        compose.onNodeWithTag("album.menu").performClick()
        compose.onNodeWithTag("album.refresh").performClick()
        await("album.freshness")
        compose.onNodeWithTag("album.freshness", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("album.title").assertIsDisplayed()
        // Trying again finds the server still away: the reader goes offline, and the page keeps saying so.
        compose.onNodeWithTag("album.retry").performClick()
        compose.waitUntil(60_000) { shadowOf(Looper.getMainLooper()).idle(); session!!.connection.value is LibraryConnectionState.Offline }
        compose.waitForIdle()
        compose.onNodeWithTag("album.freshness", useUnmergedTree = true).assertIsDisplayed()
    }
}
