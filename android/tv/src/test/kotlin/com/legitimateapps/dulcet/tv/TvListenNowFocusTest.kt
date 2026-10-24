package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.emulator.focusTvListenNowItem
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

/** Production Home, reader and remote activation against a disposable loopback Home feed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television")
class TvListenNowFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply {
        dulcetHome = true
        homePlaylists = listOf("kept" to "Before refresh")
    }
    private val account = SearchAccount("tv-home-focus", server.url, "u", "p", true)
    private lateinit var session: LibrarySession
    private val opened = mutableListOf<String>()

    @After fun close() {
        if (::session.isInitialized) session.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aFocusedPlaylistSurvivesARealRefreshThatRenamesAndReordersIt() {
        host()
        compose.focusTvListenNowItem("recentPlaylists", playlist("Before refresh"))
        val before = recentReads()
        server.homePlaylists = listOf("inserted" to "New first playlist", "kept" to "After refresh")
        compose.runOnIdle { session.refresh() }
        await { compose.onAllNodes(playlist("After refresh")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(recentReads() > before, "Control: a fresh Home response actually republished the shelf")
        compose.onNode(playlist("After refresh")).assertIsFocused()
        compose.onNodeWithTag("listenNow.recentPlaylists.item.1").assertIsFocused()
        centre("After refresh")
        assertEquals(listOf("playlist:kept"), opened, "The remote still opens the same identity after the refresh")
    }

    @Test fun aFoundButDisposedCardCannotBeFocusedUntilItsShelfIsAnchoredAgain() {
        host()
        compose.onNodeWithTag("listenNow.surface").performScrollToKey("recentPlaylists")
        val matcher = playlist("Before refresh")
        await { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val found = compose.onNode(matcher)
        found.fetchSemanticsNode()
        // A found semantics node need not be in the next viewport. Force that boundary instead
        // of relying on the timing of a real D-pad bring-into-view animation.
        compose.onNodeWithTag("listenNow.surface").performScrollToKey("random")
        found.assertDoesNotExist()
        val failure = assertFailsWith<AssertionError> { found.performSemanticsAction(SemanticsActions.RequestFocus) }
        assertTrue(failure.message.orEmpty().contains("RequestFocus"), failure.message)
        val freshFailure = assertFailsWith<AssertionError> {
            compose.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus)
        }
        assertTrue(freshFailure.message.orEmpty().contains("RequestFocus"), freshFailure.message)
        println("TV HOME FOCUS CONTROL found=true disposed-before-action=true held-and-fresh-actions=RequestFocus-failed")
        println(freshFailure.message)
        compose.focusTvListenNowItem("recentPlaylists", matcher)
        centre("Before refresh")
        assertEquals(listOf("playlist:kept"), opened)
    }

    private fun host() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                    TvListenNow(account, session, null, TvNavigator({ opened += it }, {}))
                }
                LibraryLifecycle(session)
            }
        }
        await { recentReads() > 0 && compose.onAllNodesWithTag("listenNow.recent.item.0").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun playlist(name: String) = hasText(name) and hasAnyAncestor(hasTestTag("listenNow.recentPlaylists"))
    private fun recentReads() = server.requests("getDulcetHome").count { it["shelf"] == "recentPlaylists" }
    private fun centre(name: String) {
        compose.onNode(playlist(name)).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(30_000) { condition() }
    }
}
