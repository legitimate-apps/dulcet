package com.legitimateapps.dulcet

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidSkipNotice
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.SKIP_NOTICE_TAG
import com.legitimateapps.dulcet.ui.SkipNoticeDrawnSentence
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
 * The full player in every window shape the phone app can be given -- it locks no orientation and
 * runs in split screen -- with the skip notice showing (spec §12.12 rule 8). Native graphics, so
 * text is measured as a device measures it. Paused, so the cover is drawn at its smaller, settled
 * size, the tighter case for a notice that must lie on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhonePlayerWindowSizesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val account = SearchAccount("provider:placement", "http://127.0.0.1:9", "u", "p", true)
    private val controller = AndroidPlaybackController(context,
        PlaybackEndpointAccount("provider:placement", "http://127.0.0.1:9", "u", "p", true))

    @After fun close() { controller.close() }

    @Test @Config(qualifiers = "w640dp-h360dp-land")
    fun aPhoneInLandscapeShowsEveryControlAndTheNoticeOnTheCover() = check(640, 360)

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun aTabletInLandscapeShowsEveryControlAndTheNoticeOnTheCover() = check(1280, 800)

    @Test @Config(qualifiers = "w360dp-h320dp-land")
    fun aShortSplitScreenWindowShowsEveryControlAndTheNoticeOnTheCover() = check(360, 320)

    @Test @Config(qualifiers = "w320dp-h480dp-port")
    fun aSmallPortraitWindowShowsEveryControlAndTheNoticeOnTheCover() = check(320, 480)

    @Test @Config(qualifiers = "w800dp-h1280dp-port")
    fun aTabletInPortraitShowsEveryControlAndTheNoticeOnTheCover() = check(800, 1280)

    private fun check(width: Int, height: Int) {
        compose.mainClock.autoAdvance = false
        val state = AndroidPlaybackState(title = "Playable After Skip", phase = "Preparing", playbackSessionId = "s",
            artist = "Dulcet Fixtures", album = "Skip Probe", canGoNext = true, canGoPrevious = true,
            positionMilliseconds = 12_000, durationMilliseconds = 40_000, seekable = true, playWhenReady = false,
            skipNotice = AndroidSkipNotice(1, "Unplayable Probe", SystemClock.elapsedRealtime()))
        compose.setContent { MaterialTheme { NowPlayingScreen(account, state, controller) {} } }
        // Long enough for the cover to settle at its paused size.
        repeat(90) { compose.mainClock.advanceTimeByFrame() }
        val density = context.resources.displayMetrics.density
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val window = root.boundsInRoot
        assertEquals(width.toFloat(), window.width / density, 1f, "The control requires a $width dp wide window")
        assertEquals(height.toFloat(), window.height / density, 1f, "The control requires a $height dp tall window")
        fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        fun dp(r: Rect) = "[%.0f,%.0f - %.0f,%.0f]".format(r.left / density, r.top / density, r.right / density, r.bottom / density)
        // Every control is whole: drawn at its full size, none of it clipped away, inside the window,
        // and large enough to touch. A control squeezed out of the layout measures 0 x 0.
        val controls = listOf("player.close", "player.queue", "player.scrubber", "player.shuffle", "player.previous",
            "player.playpause", "player.next", "player.repeat")
        val broken = controls.map { it to node(it) }.filter { (_, n) ->
            val shown = n.boundsInRoot
            n.size.width < 40 * density || n.size.height < 24 * density ||
                shown.width < n.size.width - 1 || shown.height < n.size.height - 1 || !window.holds(shown)
        }.map { (tag, n) -> "$tag size=${n.size} shown=${dp(n.boundsInRoot)}" }
        assertEquals(emptyList(), broken, "In $width x $height every control must be whole and inside the window")
        for (tag in listOf("player.title", "player.position")) {
            val n = node(tag)
            assertTrue(n.size.height > 0 && window.holds(n.boundsInRoot), "In $width x $height $tag must be shown: ${dp(n.boundsInRoot)}")
        }
        val all = interactiveNodes(root)
        assertTrue(all.map { it.tag() }.containsAll(controls), "The control requires every control among those checked")
        val overlapping = all.flatMap { a -> all.filter { b -> a.id < b.id && a.boundsInRoot.overlaps(b.boundsInRoot) }.map { a to it } }
            .filterNot { (a, b) -> a.isAncestorOf(b) || b.isAncestorOf(a) }
            .map { (a, b) -> "${a.tag()}${dp(a.boundsInRoot)} / ${b.tag()}${dp(b.boundsInRoot)}" }
        assertEquals(emptyList(), overlapping, "In $width x $height no two controls may overlap")
        // The cover takes no input and overlaps none of the controls.
        val cover = node("player.artwork").boundsInRoot
        assertTrue(cover.width > 0 && window.holds(cover), "In $width x $height the cover must be inside the window: ${dp(cover)}")
        assertEquals(emptyList(), all.filter { it.boundsInRoot.overlaps(cover) }.map { "${it.tag()}${dp(it.boundsInRoot)}" },
            "In $width x $height the cover ${dp(cover)} overlaps these controls")
        // The notice: whole, inside the window, on the cover as it is drawn, and over no control.
        val notice = node(SKIP_NOTICE_TAG)
        val area = notice.boundsInRoot
        assertTrue(area.height > 0 && area.height >= notice.size.height - 1 && window.holds(area),
            "In $width x $height the notice ${dp(area)} must be whole and inside the window ${dp(window)}")
        val sleeve = cover.scaledAboutCenter(PAUSED_COVER_SCALE)
        assertTrue(sleeve.holds(area), "In $width x $height the notice ${dp(area)} must lie on the drawn cover ${dp(sleeve)}")
        assertTrue(area.height <= sleeve.height / 2 + 0.5f,
            "In $width x $height the notice ${dp(area)} must leave most of the cover ${dp(sleeve)} in view")
        val (_, covered) = interactiveNodesCoveredBy(root, notice)
        assertEquals(emptyList(), covered.map { "${it.tag()}${dp(it.boundsInRoot)}" },
            "In $width x $height the notice ${dp(area)} covers these controls")
        println("PLAYER WINDOW $width x $height cover=${dp(cover)} notice=${dp(area)} " +
            "drawn=\"${notice.config[SkipNoticeDrawnSentence]}\" transport=${
                listOf("player.shuffle", "player.previous", "player.playpause", "player.next", "player.repeat")
                    .joinToString(" ") { dp(node(it).boundsInRoot) }}")
    }

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)
    private fun SemanticsNode.isAncestorOf(other: SemanticsNode) = generateSequence(other.parent) { it.parent }.any { it.id == id }
}

private fun Rect.holds(other: Rect) =
    other.left >= left - 0.5f && other.top >= top - 0.5f && other.right <= right + 0.5f && other.bottom <= bottom + 0.5f

private fun Rect.scaledAboutCenter(scale: Float): Rect {
    val dx = width * (1 - scale) / 2
    val dy = height * (1 - scale) / 2
    return Rect(left + dx, top + dy, right - dx, bottom - dy)
}

/** Every node a person can tap, long-press, drag, scroll or move focus to, under [root]. */
internal fun interactiveNodes(root: SemanticsNode): List<SemanticsNode> {
    val found = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        val c = node.config
        if (androidx.compose.ui.semantics.SemanticsActions.OnClick in c ||
            androidx.compose.ui.semantics.SemanticsActions.OnLongClick in c ||
            androidx.compose.ui.semantics.SemanticsActions.SetProgress in c ||
            androidx.compose.ui.semantics.SemanticsActions.ScrollBy in c ||
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus in c || SemanticsProperties.Focused in c) found += node
        node.children.forEach(::walk)
    }
    walk(root)
    return found
}
