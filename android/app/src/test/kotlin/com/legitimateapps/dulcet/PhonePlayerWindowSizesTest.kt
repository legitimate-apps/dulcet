package com.legitimateapps.dulcet

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidSkipNotice
import com.legitimateapps.dulcet.core.DomainError
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

/**
 * The full player across a grid of windows (spec §12.12 rule 8): the [WINDOWS] below, each at text
 * scale -- Android's steps, 0.85, 1, 1.15, 1.3, 1.5, 1.8 and 2 -- each with and without the error card, with the skip notice showing and playback
 * paused, so the cover is drawn at its smaller, settled size. The app locks no orientation and runs
 * in split screen and on large screens, so these span phones in both orientations, foldables,
 * tablets, a desktop-sized window, and the small windows split screen makes.
 *
 * In every cell: every control is whole, inside the window, and at least a 48 dp touch target; no
 * two controls overlap, and no text overlaps a control; the title's line is whole; the error card,
 * when there is one, is shown, whole or scrolling in its own region; the cover overlaps no control,
 * is at least 48 dp, and is absent only when the notice's banner, the title's line or the error card
 * needs its room; the layout is stacked
 * in a window taller than wide and side by side in one wider than tall; and
 * the notice is whole, at least its minimum readable size, over no control and no text, and either
 * on a cover of at least [NOTICE_MIN_COVER], the card and its margin taking at most the lower half of it as drawn, or in the banner
 * beneath the header and above the title, clear of the cover. A cover of 240 dp or more always
 * hosts it. In a window too short for the banner, the title's line and the error card at once, the
 * banner keeps its height for the seconds the notice shows and the title and error card may be cut;
 * once the notice has gone the cell is checked again and both must be whole or shown.
 *
 * One composition inside a window larger than any cell, resized per cell, so the grid runs in
 * seconds rather than one Robolectric start per cell. Native graphics, so text is measured as a
 * device measures it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1920dp-h1280dp-land")
class PhonePlayerWindowSizesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val account = SearchAccount("provider:placement", "http://127.0.0.1:9", "u", "p", true)
    private val controller = AndroidPlaybackController(context,
        PlaybackEndpointAccount("provider:placement", "http://127.0.0.1:9", "u", "p", true))

    @After fun close() { controller.close() }

    @Test fun everyWindowAtSmallTextWithoutAnError() = grid(0.85f, error = false)
    @Test fun everyWindowAtSmallTextWithTheErrorCard() = grid(0.85f, error = true)
    @Test fun everyWindowAtDefaultTextWithoutAnError() = grid(1f, error = false)
    @Test fun everyWindowAtDefaultTextWithTheErrorCard() = grid(1f, error = true)
    @Test fun everyWindowAtDoubleTextWithoutAnError() = grid(2f, error = false)
    @Test fun everyWindowAtDoubleTextWithTheErrorCard() = grid(2f, error = true)
    @Test fun everyWindowAtText115WithoutAnError() = grid(1.15f, error = false)
    @Test fun everyWindowAtText115WithTheErrorCard() = grid(1.15f, error = true)
    @Test fun everyWindowAtText130WithoutAnError() = grid(1.3f, error = false)
    @Test fun everyWindowAtText130WithTheErrorCard() = grid(1.3f, error = true)
    @Test fun everyWindowAtText150WithoutAnError() = grid(1.5f, error = false)
    @Test fun everyWindowAtText150WithTheErrorCard() = grid(1.5f, error = true)
    @Test fun everyWindowAtText180WithoutAnError() = grid(1.8f, error = false)
    @Test fun everyWindowAtText180WithTheErrorCard() = grid(1.8f, error = true)

    private class Cell(val width: Int, val height: Int, val fontScale: Float, val error: Boolean, val sequence: Long)

    private fun grid(fontScale: Float, error: Boolean) {
        compose.mainClock.autoAdvance = false
        var cell by mutableStateOf<Cell?>(null)
        compose.setContent {
            val c = cell ?: return@setContent
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, c.fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(c.width.dp, c.height.dp).testTag(WINDOW)) {
                        key(c.sequence) { NowPlayingScreen(account, state(c), controller) {} }
                    }
                }
            }
        }
        val problems = mutableListOf<String>()
        val yielded = mutableListOf<String>()
        var hostedOnCover = 0
        var hostedInBanner = 0
        var sequence = 0L
        for ((width, height) in WINDOWS) {
            val next = Cell(width, height, fontScale, error, ++sequence)
            compose.runOnUiThread { cell = next }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            // Long enough for the notice to finish entering.
            repeat(40) { compose.mainClock.advanceTimeByFrame() }
            val where = "$width x $height at ${fontScale}x ${if (error) "with" else "without"} the error card"
            val found = check(width, height, error, where)
            problems += found.problems
            if (found.onCover) hostedOnCover++ else hostedInBanner++
            if (found.whileTheBannerShows.isNotEmpty()) {
                // A window too short for the banner, the title's line and the error card at once: the
                // banner keeps its height for the seconds it shows, and once it has gone the title
                // and the error card must be whole again, with every other check as before.
                yielded += "$where: ${found.whileTheBannerShows}"
                // Measured: exactly these eight cells yield, each with and without the error card --
                // 300 x 300 at 1.5x, 1.8x and 2x, and 330 x 330 at 2x. Any other cell that yields
                // is a problem.
                if (Triple(width, height, fontScale) !in BANNER_YIELDS)
                    problems += "$where: the banner cut the text in a window it should fit: ${found.whileTheBannerShows}"
                compose.mainClock.advanceTimeBy(10_000)
                repeat(40) { compose.mainClock.advanceTimeByFrame() }
                problems += check(width, height, error, "$where, after the notice", noticeShowing = false).problems
            }
        }
        println("PLAYER GRID font=$fontScale error=$error cells=${WINDOWS.size} onCover=$hostedOnCover banner=$hostedInBanner " +
            "yieldedWhileTheBannerShows=${yielded.size}")
        yielded.forEach { println("PLAYER YIELDED $it") }
        assertEquals(emptyList(), problems, "Problems across the grid at ${fontScale}x, error=$error")
    }

    private fun state(c: Cell) = AndroidPlaybackState(title = "Playable After Skip", phase = "Preparing",
        playbackSessionId = "s", artist = "Dulcet Fixtures", album = "Skip Probe", canGoNext = true, canGoPrevious = true,
        positionMilliseconds = 12_000, durationMilliseconds = 40_000, seekable = true, playWhenReady = false,
        error = if (c.error) DomainError.Transport.Unreachable else null,
        skipNotice = AndroidSkipNotice(c.sequence, "Unplayable Probe", SystemClock.elapsedRealtime()))

    /**
     * [whileTheBannerShows]: the title or the error card cut while the notice's banner holds its
     * height -- a problem only if it outlasts the notice, which the caller then checks.
     */
    private class Found(val problems: List<String>, val onCover: Boolean, val whileTheBannerShows: List<String> = emptyList())

    private fun check(width: Int, height: Int, error: Boolean, where: String, noticeShowing: Boolean = true): Found {
        val problems = mutableListOf<String>()
        val textCut = mutableListOf<String>()
        val d = context.resources.displayMetrics.density
        fun dp(r: Rect) = "[%.0f,%.0f - %.0f,%.0f]".format(r.left / d, r.top / d, r.right / d, r.bottom / d)
        fun node(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val window = node(WINDOW)!!.boundsInRoot
        if (Math.abs(window.width / d - width) > 1 || Math.abs(window.height / d - height) > 1)
            return Found(listOf("$where: the control requires a $width x $height window, got ${dp(window)}"), false)
        fun SemanticsNode.whole() = boundsInRoot.width >= size.width - 1 && boundsInRoot.height >= size.height - 1
        // Every control whole, inside the window, and at least a 48 dp touch target; the scrubber as wide as a thumb's travel needs.
        val buttons = listOf("player.close", "player.queue", "player.shuffle", "player.previous", "player.playpause",
            "player.next", "player.repeat")
        for (tag in buttons + "player.scrubber") {
            val n = node(tag) ?: run { problems += "$where: $tag is missing"; null } ?: continue
            val minimum = if (tag == "player.scrubber") 120f to 44f else 48f to 48f
            if (!n.whole() || !window.holds(n.boundsInRoot) || n.size.width < minimum.first * d - 0.5f ||
                n.size.height < minimum.second * d - 0.5f)
                problems += "$where: $tag is not whole, inside the window and large enough: size=${n.size.width / d}x${n.size.height / d} shown=${dp(n.boundsInRoot)}"
        }
        val all = interactiveNodes(root)
        fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)
        fun SemanticsNode.isAncestorOf(other: SemanticsNode) = generateSequence(other.parent) { it.parent }.any { it.id == id }
        all.forEach { a ->
            all.filter { b -> a.id < b.id && a.boundsInRoot.overlaps(b.boundsInRoot) && !a.isAncestorOf(b) && !b.isAncestorOf(a) }
                .forEach { b -> problems += "$where: ${a.tag()}${dp(a.boundsInRoot)} overlaps ${b.tag()}${dp(b.boundsInRoot)}" }
        }
        // The title's line is whole; the position is whole; the error card is shown, whole or scrolling in its own region.
        val title = node("player.title")
        for (tag in listOf("player.title", "player.position")) {
            val n = node(tag)
            if (n == null || !n.whole() || !window.holds(n.boundsInRoot) || n.size.height <= 0)
                (if (tag == "player.title") textCut else problems) += "$where: $tag is not whole: ${n?.let { dp(it.boundsInRoot) }}"
        }
        val errorCard = node("player.error")
        if (error) {
            if (errorCard == null || errorCard.boundsInRoot.height <= 0 || !window.holds(errorCard.boundsInRoot))
                textCut += "$where: the error card is not shown: ${errorCard?.let { dp(it.boundsInRoot) }}"
            else if (!errorCard.whole() && generateSequence(errorCard.parent) { it.parent }
                    .none { SemanticsActions.ScrollBy in it.config })
                problems += "$where: the error card is cut short ${dp(errorCard.boundsInRoot)} and cannot be scrolled into view"
        } else if (errorCard != null) problems += "$where: the control requires no error card"
        val texts = listOfNotNull(title, node("player.position"), errorCard)
        for (text in texts) all.filter { it.boundsInRoot.overlaps(text.boundsInRoot) && !it.isAncestorOf(text) && !text.isAncestorOf(it) }
            .forEach { problems += "$where: ${text.tag()}${dp(text.boundsInRoot)} overlaps ${it.tag()}${dp(it.boundsInRoot)}" }
        // The cover: inside the window, over no control, and placed as the window's shape requires.
        // It is absent only when the notice's banner or the error card needs its room, and is otherwise at least 48 dp.
        val cover = node("player.artwork")!!.boundsInRoot
        val bannerShown = node(PLAYER_NOTICE_BANNER_TAG)?.let { it.boundsInRoot.height > 0 } ?: false
        if (cover.width <= 0) {
            // The title's line comes before the cover: a window too short for both, and the 4 dp gap
            // above the scrubber at its tightest, leaves no cover.
            val between = (node("player.scrubber")?.boundsInRoot?.top ?: 0f) - (node("player.close")?.boundsInRoot?.bottom ?: 0f)
            val titleLine = title?.size?.height?.toFloat() ?: 0f
            if (!bannerShown && (errorCard == null || errorCard.whole()) && between >= titleLine + (48 + 4) * d)
                problems += "$where: the cover is absent though neither the banner, the title's line nor the error card needed its room (${between / d} dp between the header and the scrubber, title ${titleLine / d} dp)"
        } else if (!window.holds(cover) || cover.width < 48 * d - 0.5f) problems += "$where: the cover ${dp(cover)} is not inside the window and at least 48 dp"
        all.filter { it.boundsInRoot.overlaps(cover) }.forEach { problems += "$where: the cover ${dp(cover)} overlaps ${it.tag()}" }
        if (title != null && cover.width > 0) {
            val t = title.boundsInRoot
            if (width > height && cover.right > t.left + 0.5f)
                problems += "$where: wider than tall, so the cover ${dp(cover)} must be beside the title ${dp(t)}"
            if (width <= height && cover.bottom > t.top + 0.5f)
                problems += "$where: taller than wide, so the cover ${dp(cover)} must be above the title ${dp(t)}"
        }
        if (!noticeShowing) {
            node(SKIP_NOTICE_TAG)?.let { problems += "$where: the notice ${dp(it.boundsInRoot)} is still showing" }
            if (bannerShown) problems += "$where: the banner still holds its height"
            return Found(problems + textCut, false)
        }
        // The notice: whole, readable, over no control and no text, and on a cover large enough or in the banner.
        val notice = node(SKIP_NOTICE_TAG) ?: return Found(problems + textCut + "$where: the notice is missing", false)
        val area = notice.boundsInRoot
        val drawn = notice.config.getOrNull(SkipNoticeDrawnSentence).orEmpty()
        if (!notice.whole() || !window.holds(area) || area.height < NOTICE_MIN_HEIGHT * d || area.width < NOTICE_MIN_WIDTH * d || drawn.isBlank())
            problems += "$where: the notice ${dp(area)} is not whole and readable (\"$drawn\")"
        val (_, covered) = interactiveNodesCoveredBy(root, notice)
        covered.forEach { problems += "$where: the notice ${dp(area)} covers ${it.tag()}${dp(it.boundsInRoot)}" }
        texts.filter { it.boundsInRoot.overlaps(area) }.forEach { problems += "$where: the notice ${dp(area)} covers ${it.tag()}" }
        val onCover = cover.holds(area)
        val sleeve = cover.scaledAboutCenter(PAUSED_COVER_SCALE)
        if (onCover) {
            if (cover.width < NOTICE_MIN_COVER.value * d - 0.5f)
                problems += "$where: the notice is on a cover ${dp(cover)} smaller than ${NOTICE_MIN_COVER.value} dp"
            if (!sleeve.holds(area)) problems += "$where: the notice ${dp(area)} is off the drawn cover ${dp(sleeve)}"
            // The card and the margin beneath it: the lower half of the drawn cover at most.
            if (sleeve.bottom - area.top > sleeve.height / 2 + 0.5f)
                problems += "$where: the notice ${dp(area)} and its margin take more than the lower half of the cover ${dp(sleeve)}"
        } else {
            val banner = node(PLAYER_NOTICE_BANNER_TAG)
            if (banner == null || !banner.boundsInRoot.holds(area)) problems += "$where: the notice ${dp(area)} is neither on the cover nor in the banner"
            if (area.overlaps(cover)) problems += "$where: the banner ${dp(area)} overlaps the cover ${dp(cover)}"
            if (title != null && area.bottom > title.boundsInRoot.top + 0.5f) problems += "$where: the banner ${dp(area)} is not above the title"
            node("player.close")?.let { if (area.top < it.boundsInRoot.bottom - 0.5f) problems += "$where: the banner ${dp(area)} is not beneath the header" }
            if (cover.width >= 240 * d) problems += "$where: a cover of ${cover.width / d} dp must host the notice"
        }
        println("PLAYER CELL $where cover=${dp(cover)} title=${title?.let { dp(it.boundsInRoot) }} notice=${dp(area)} " +
            "on=${if (onCover) "cover" else "banner"} drawn=\"$drawn\" transport=${
                listOf("player.shuffle", "player.previous", "player.playpause", "player.next", "player.repeat")
                    .joinToString(" ") { t -> node(t)?.let { dp(it.boundsInRoot) } ?: "missing" }}")
        return if (!onCover && bannerShown) Found(problems, onCover, textCut) else Found(problems + textCut, onCover)
    }

    companion object {
        private const val WINDOW = "grid.window"
        /** The notice's smallest readable card: one line of its capped text with its padding, and room for a few words. */
        private const val NOTICE_MIN_HEIGHT = 32f
        private const val NOTICE_MIN_WIDTH = 120f
        /**
         * Twenty windows. Phones portrait and landscape, a small phone, foldables inner and outer,
         * a 7-inch and a 10-inch tablet both ways, a desktop-sized window, and split-screen halves
         * and small windows down to the narrowest the transport is laid out for.
         */
        val WINDOWS = listOf(
            360 to 640, 412 to 915, 480 to 800, 320 to 480, 300 to 560, 280 to 653, 800 to 1280, 673 to 841,
            400 to 420, 411 to 440, 316 to 360,
            640 to 360, 915 to 412, 841 to 673, 960 to 600, 1280 to 800, 1920 to 1080, 360 to 320, 568 to 320, 1024 to 768,
            390 to 844, 340 to 600, 500 to 500, 330 to 330, 600 to 590, 740 to 360, 720 to 400, 412 to 480, 360 to 400,
            280 to 400, 820 to 1180, 1180 to 820, 600 to 960, 360 to 780, 432 to 360, 300 to 300,
        )
        /** The exact cells, each with and without the error card, where the banner may cut the text while it shows. */
        val BANNER_YIELDS = setOf(
            Triple(300, 300, 1.5f), Triple(300, 300, 1.8f), Triple(300, 300, 2.0f),
            Triple(330, 330, 2.0f),
        )
    }
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
