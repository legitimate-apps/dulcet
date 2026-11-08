package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One layout budget for shelves under a notice: the tile gives up what the notice takes, whichever page it is on. Robolectric's
 * text is taller than a television's, so the design tour measures the caption against the fade; this holds the tile each page uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvNoticeShelfBudgetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply {
        dulcetHome = true
        homePlaylists = listOf("kept" to "Kept")
    }
    private val account = SearchAccount("provider:tv-budget", server.url, "u", "p", true)
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

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) { content() }
                LibraryLifecycle(session)
            }
        }
    }

    private fun widthOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.width
    private fun px(tile: Int) = with(compose.density) { tile.dp.roundToPx() }
    private fun await(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun listenNowsTilesGiveUpTheirHeightToANotice() {
        host { TvListenNow(account, session, null, TvNavigator({}, {})) }
        await("listenNow.recent.item.0")
        assertEquals(px(TV_SHELF_TILE), widthOf("listenNow.recent.item.0"), "a page at rest")
        server.failHome = true
        compose.runOnIdle { session.refresh() }
        compose.waitUntil(30_000) { widthOf("listenNow.recent.item.0") != px(TV_SHELF_TILE) }
        assertEquals(px(TV_NOTICE_SHELF_TILE), widthOf("listenNow.recent.item.0"), "a page with a notice")
    }

    @Test fun libraryShelvesGiveUpTheSameHeightToANotice() {
        host { TvLibraryHome(account, session, null, TvNavigator({}, {}), browse = true) }
        await("library.home.0.item.0")
        assertEquals(px(TV_SHELF_TILE), widthOf("library.home.0.item.0"), "a page at rest")
        server.refuseCredentials = true
        compose.runOnIdle { session.refresh() }
        compose.waitUntil(30_000) { widthOf("library.home.0.item.0") != px(TV_SHELF_TILE) }
        assertEquals(px(TV_NOTICE_SHELF_TILE), widthOf("library.home.0.item.0"), "a page with a notice")
    }
}
