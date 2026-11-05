package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibraryLifecycle
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

/** An album's notice is the page's: above both columns, where a remote resting on Play cannot scroll it under the tabs. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvAlbumNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { refuseCredentials = true }
    private val account = SearchAccount("tv-album-notice", server.url, "u", "p", true)
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

    /** The room under the tab bar on a 1080p television. */
    private val page = 466.dp

    private fun openedAlbum() {
        server.refuseCredentials = false
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                    Box(Modifier.height(page)) { TvAlbumScreen(account, session, null, null, "album-1", TvNavigator({}, {})) }
                }
                LibraryLifecycle(session)
            }
        }
        await("album.title")
        await("album.play")
    }

    private fun await(tag: String, present: Boolean = true) = compose.waitUntil(60_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() == present
    }

    /**
     * The room a notice takes comes out of the cover, so a remote resting on Play cannot slide the cover under the notice's
     * edge. Robolectric's text is taller than a television's, so the captures measure the column; this holds the cover.
     */
    private fun coverHeight() = compose.onNodeWithTag("album.cover", useUnmergedTree = true).getBoundsInRoot().let { it.bottom - it.top }

    @Test fun aRefusedPasswordLeavesTheCoverRoomUnderTheNotice() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                    Box(Modifier.height(page)) { TvAlbumScreen(account, session, null, null, "album-1", TvNavigator({}, {})) }
                }
                LibraryLifecycle(session)
            }
        }
        await("library.connection")
        compose.waitForIdle()
        assertEquals(136.dp, coverHeight())
    }

    @Test fun aFailedRefreshIsSaidAboveTheColumnsAndStaysSaidOffline() {
        openedAlbum()
        assertEquals(180.dp, coverHeight())
        // The server goes away and the person asks for a refresh: a moment of Failed, then the reader is offline for good.
        server.close()
        compose.onNodeWithTag("album.more").performSemanticsAction(SemanticsActions.OnClick)
        await("album.refresh")
        compose.onNodeWithTag("album.refresh").performSemanticsAction(SemanticsActions.OnClick)
        await("album.freshness")
        compose.waitForIdle()
        val line = compose.onNodeWithTag("album.freshness", useUnmergedTree = true).getBoundsInRoot()
        val columns = compose.onNodeWithTag("album.surface").getBoundsInRoot()
        assertTrue(columns.top >= line.bottom, "the columns start at ${columns.top}, under the notice ending at ${line.bottom}")
        assertEquals(136.dp, coverHeight())
        // Trying again finds the server still away: the reader goes offline, and the page keeps saying so.
        compose.onNodeWithTag("album.retry").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(60_000) { shadowOf(Looper.getMainLooper()).idle(); session.connection.value is LibraryConnectionState.Offline }
        compose.waitForIdle()
        await("album.freshness")
        assertEquals(136.dp, coverHeight())
    }

    @Test fun aRefusedPasswordIsSaidAcrossThePageAboveItsColumns() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                    TvAlbumScreen(account, session, null, null, "album-1", TvNavigator({}, {}))
                }
                LibraryLifecycle(session)
            }
        }
        compose.waitUntil(60_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasTestTag("library.connection"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val line = compose.onNodeWithTag("library.connection", useUnmergedTree = true).getBoundsInRoot()
        val page = compose.onNodeWithTag("album.surface").getBoundsInRoot()
        assertTrue(line.width > 440.dp, "the notice runs across the page; its line is ${line.width} wide")
        assertTrue(page.top >= line.bottom, "the columns start at ${page.top}, under the notice ending at ${line.bottom}")
    }
}
