package com.legitimateapps.dulcet.tv

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidSkipNotice
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.ui.SKIP_NOTICE_TAG
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the lean-back player draws the skip notice (spec §12.12 rules 5 and 8) with a queue long
 * enough to fill Up Next. Native graphics, so text is measured as a device measures it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvSkipNoticePlacementTest {
    @get:Rule val compose = createComposeRule()

    private val controller = AndroidPlaybackController(RuntimeEnvironment.getApplication(),
        PlaybackEndpointAccount("provider:placement", "http://127.0.0.1:9", "u", "p", true))

    @After fun close() { controller.close() }

    @Test fun theNoticeCoversNoFocusableControlWithThreeUpNextEntries() = checkPlacement(3)

    @Test fun theNoticeCoversNoFocusableControlWithEightUpNextEntries() = checkPlacement(8)

    private fun checkPlacement(count: Int) {
        val queue = (1..count).map { AndroidQueueEntry("e$it", AndroidTrack("p", "t$it", "Track $it", "Artist")) }
        val state = AndroidPlaybackState(title = "Track 2", phase = "Preparing", playbackSessionId = "s",
            queue = queue, currentIndex = 1, canGoNext = true, canGoPrevious = true,
            skipNotice = AndroidSkipNotice(1, "Track 1", SystemClock.elapsedRealtime()))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlaying(null, state, controller) } }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val notice = compose.onNodeWithTag(SKIP_NOTICE_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val (checked, covered) = interactiveNodesCoveredBy(root, notice)
        val tags = checked.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        // A check that finds no controls proves nothing: the transport and three rows must be among them.
        assertTrue(tags.containsAll(listOf("tv.player.previous", "tv.player.playpause", "tv.player.next",
            "tv.player.upnext", "tv.player.upnext.0", "tv.player.upnext.1", "tv.player.upnext.2")),
            "The control requires the player's focusable controls to be checked: $tags")
        assertTrue(notice.boundsInRoot.height > 0f, "The control requires the notice drawn")
        assertEquals(emptyList(), covered.map { describe(it) },
            "With $count entries the notice ${notice.boundsInRoot} covers these focusable controls")
        val cover = compose.onNodeWithTag("tv.player.artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val area = notice.boundsInRoot
        assertTrue(area.left >= cover.left - 0.5f && area.top >= cover.top - 0.5f && area.right <= cover.right + 0.5f &&
            area.bottom <= cover.bottom + 0.5f, "The notice $area must lie on the cover $cover")
        val list = compose.onNodeWithTag("tv.player.upnext", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        println("SKIP NOTICE PLACEMENT tv 960x540 entries=$count notice=$area cover=$cover upnext=$list " +
            "controls-checked=${checked.size} rows-checked=${tags.count { it.startsWith("tv.player.upnext.") }}")
    }

    private fun describe(node: SemanticsNode) = "${node.config.getOrNull(SemanticsProperties.TestTag)} ${node.boundsInRoot}"
}

/**
 * Every node a person can act on -- select, scroll or move focus to -- under [root], leaving out
 * [notice] and the containers it is drawn inside; and those of them [notice] overlaps.
 */
internal fun interactiveNodesCoveredBy(root: SemanticsNode, notice: SemanticsNode): Pair<List<SemanticsNode>, List<SemanticsNode>> {
    val containers = generateSequence(notice) { it.parent }.map { it.id }.toSet()
    val interactive = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        val c = node.config
        if (node.id !in containers && (SemanticsActions.OnClick in c || SemanticsActions.OnLongClick in c ||
                SemanticsActions.SetProgress in c || SemanticsActions.ScrollBy in c ||
                SemanticsActions.RequestFocus in c || SemanticsProperties.Focused in c)) interactive += node
        node.children.forEach(::walk)
    }
    walk(root)
    val area = notice.boundsInRoot
    return interactive to interactive.filter { it.boundsInRoot.overlaps(area) }
}
