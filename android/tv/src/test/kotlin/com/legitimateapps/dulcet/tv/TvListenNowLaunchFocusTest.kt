package com.legitimateapps.dulcet.tv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
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

/**
 * Listen Now's shelves arrive after launch, several at once. None may take focus from the bar: the
 * remote stays on the Listen Now tab, selected and focused, until the person moves down, and UP
 * from a card returns to the tab and stays there. Real top bar, routes and production reader over
 * a loopback Home feed; every move is a remote key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvListenNowLaunchFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply {
        dulcetHome = true
    }
    private val account = SearchAccount("provider:tv-listen-now-launch", server.url, "u", "p", true)

    @After fun close() {
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        server.close()
        context.deleteDatabase("dulcet.db")
        context.getSharedPreferences("dulcet.ui", 0).edit().clear().commit()
    }

    @Test fun theShelvesArrivingAtLaunchLeaveTheRemoteOnTheListenNowTab() {
        compose.setContent { MaterialTheme { TvLibraryEntry(account, playback = null) { _, _ -> } } }
        await("Listen Now's first shelf, with cards") { exists("listenNow.recent.item.0") }
        settle()
        assertTrue(selected("listenNow.open"), "the bar's Listen Now tab is lit")
        assertFocused("listenNow.open")

        // Down to a card, then UP back to the bar: whichever bar control UP reaches keeps focus; no
        // card or action takes it back.
        var downs = 0
        while (!focusedTags().any { it.matches(CARD) } && downs < 8) { key(Key.DirectionDown); downs += 1 }
        assertTrue(focusedTags().any { it.matches(CARD) }, "DOWN reaches a card; focused=${focusedTags()}")
        var ups = 0
        while (focusedTags().none { it in BAR } && ups < 8) { key(Key.DirectionUp); ups += 1 }
        val reached = focusedTags().singleOrNull { it in BAR }
        assertTrue(reached != null, "UP reaches the bar; focused=${focusedTags()}")
        settle()
        assertFocused(reached)
    }

    private fun settle() {
        repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10) }
        compose.waitForIdle()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; focused=${focusedTags()}" }
            Thread.sleep(10)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun selected(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Selected) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun assertFocused(tag: String) = assertTrue(focused(tag), "$tag holds focus; focused=${focusedTags()}")

    private fun key(key: Key) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private companion object {
        val CARD = Regex("listenNow\\.\\w+\\.item\\.\\d+")
        val BAR = setOf("listenNow.open", "library.open", "browse.open", "search.open", "tv.nav.nowplaying", "tv.account.open")
    }
}
