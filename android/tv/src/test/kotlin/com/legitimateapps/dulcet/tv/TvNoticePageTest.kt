package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** At rest a TV page shows a row whole or not at all: nothing stays in the bands that fade its top and bottom. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television")
class TvNoticePageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { dulcetHome = true }
    private val account = SearchAccount("tv-notice-page", server.url, "u", "p", true)
    private var session: LibrarySession? = null

    /** The room under the tab bar on a 1080p television. */
    private val page = 466.dp

    @After fun close() {
        session?.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aNoticeLeavesTheFirstShelfsLabelsClearOfTheFoot() {
        compose.setContent {
            MaterialTheme {
                val current = remember { LibrarySession(context, account, true) }.also { session = it }
                CompositionLocalProvider(tvSteadyBringIntoView(), LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                    Box(Modifier.height(page)) { TvListenNow(account, current, null, TvNavigator({}, {})) }
                }
                LibraryLifecycle(current)
            }
        }
        compose.waitUntil(60_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasTestTag(TILE), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val density = compose.density.density
        fun tileTop() = compose.onNodeWithTag(TILE, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y / density
        val without = tileTop()
        server.failHome = true
        compose.runOnUiThread { session!!.refresh() }
        compose.waitUntil(60_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasTestTag("listenNow.recent.retry"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        // Robolectric's text is taller than a television's, so this measures the rhythm and not the foot itself: the card, with
        // its own 16 dp of padding around its action, costs the page, and the page gives back 26 dp more than the card's height
        // less the 22 dp that leaves the labels above the fade on a 1080p screen. The captures measure the foot.
        val card = compose.onNodeWithTag("listenNow.recent.retry", useUnmergedTree = true).fetchSemanticsNode().size.height / density + 16f
        val shift = tileTop() - without
        assertTrue(shift - card <= -26f, "the tiles moved $shift dp for a $card dp notice")
    }

    @Test fun anAccountAtItsFootLeavesNoRowInTheTopFade() {
        compose.setContent {
            MaterialTheme {
                val current = remember { LibrarySession(context, account, true) }.also { session = it }
                CompositionLocalProvider(tvSteadyBringIntoView(), LocalTvRouteFocus provides remember { TvRouteFocus() },
                    LocalTvAccountActions provides TvAccountActions(saved = true, requestSignOut = {},
                        savedServers = listOf(SERVER_ROW), addServer = {}, activeServerId = SERVER_ROW.id)) {
                    Box(Modifier.height(page)) { TvAccountScreen(account, TvNavigator({}, {})) }
                }
                LibraryLifecycle(current)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("account.surface").performScrollToIndex(5)
        compose.waitForIdle()
        compose.onNodeWithTag("tv.account.signout").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val surface = compose.onNodeWithTag("account.surface").getBoundsInRoot()
        val range = compose.onNodeWithTag("account.surface").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f, "the page overflows, or this proves nothing")
        assertTrue(range.value() >= range.maxValue() - 1f, "the page rests at its foot: ${range.value()} of ${range.maxValue()}")
        // Robolectric's rows are taller than a television's, so this holds the foot's room, not one row's place: the captures
        // measure where Add server lands. 27 dp of edge band and the 20 dp that takes the row above it out of the top fade.
        val density = compose.density.density
        val row = compose.onNodeWithTag("tv.account.signout").fetchSemanticsNode().unclippedBoundsInRoot().second / density
        assertTrue(row <= surface.bottom.value - 47f, "the last row ends at $row of ${surface.bottom}")
    }

    private companion object {
        const val TILE = "listenNow.recent.item.0"
        val SERVER_ROW = StoredAccount("tv-notice-page", "Fixture server", "https://example.invalid", "u", "p", false)
    }

    private fun SemanticsNode.unclippedBoundsInRoot(): Pair<Float, Float> =
        positionInRoot.y to positionInRoot.y + size.height
}
