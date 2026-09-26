package com.legitimateapps.dulcet

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.ui.SkipNoticeDrawnSentence
import com.legitimateapps.dulcet.ui.SkipNoticeRegion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidSkipNotice
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.SKIP_NOTICE_TAG
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where the full player draws the skip notice (spec §12.12 rules 5 and 8), on the smallest phone
 * the layout supports. Native graphics, so text is measured as a device measures it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port")
class PhoneSkipNoticePlacementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val account = SearchAccount("provider:placement", "http://127.0.0.1:9", "u", "p", true)
    private val controller = AndroidPlaybackController(context,
        PlaybackEndpointAccount("provider:placement", "http://127.0.0.1:9", "u", "p", true))
    private val sentence = "Couldn’t play “Unplayable Probe”. Skipped."

    @After fun close() { controller.close() }

    private fun playing() = AndroidPlaybackState(title = "Playable After Skip", phase = "Preparing",
        playbackSessionId = "s", artist = "Dulcet Fixtures", album = "Skip Probe", canGoNext = true, canGoPrevious = true,
        skipNotice = AndroidSkipNotice(1, "Unplayable Probe", SystemClock.elapsedRealtime()))

    private fun showPlayer(fontScale: Float = 1f) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme { NowPlayingScreen(account, playing(), controller) {} }
            }
        }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
    }

    @Test fun theFullPlayersNoticeIsItsOwnAccessibilityNode() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        showPlayer()
        // The merged tree is what TalkBack is handed: a notice merged into a clickable ancestor has
        // no node of its own there, and the ancestor reads it as part of its own label.
        val merged = compose.onAllNodes(hasTestTag(SKIP_NOTICE_TAG)).fetchSemanticsNodes()
        assertEquals(1, merged.size, "The notice must be its own node in the merged tree")
        val notice = merged.single()
        assertEquals(listOf(sentence), notice.config[SemanticsProperties.ContentDescription])
        assertEquals(LiveRegionMode.Polite, notice.config[SemanticsProperties.LiveRegion], "TalkBack must announce it")
        val player = compose.onNodeWithTag("player.full").fetchSemanticsNode()
        assertEquals(null, player.config.getOrNull(SemanticsProperties.LiveRegion), "The player must not carry the notice's live region")
        assertFalse(player.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("Skipped") },
            "The player's own label must not read the notice")
        // What Android's accessibility layer hands a screen reader for the notice.
        val info = composeView(compose.activity.window.decorView).accessibilityNodeProvider
            .createAccessibilityNodeInfo(notice.id)!!
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, info.liveRegion)
        assertEquals(sentence, info.contentDescription?.toString())
        assertTrue(info.isScreenReaderFocusable, "A screen reader must be able to reach the notice as its own element")
    }

    @Test fun theFullPlayersNoticeCoversNoControlOnASmallPhone() = checkPlacement(fontScale = 1f)

    /** The player's own text grows with the person's font scale; the notice still stays on the cover. */
    @Test fun theFullPlayersNoticeCoversNoControlOnASmallPhoneAtTwiceTheTextSize() = checkPlacement(fontScale = 2f)

    private fun checkPlacement(fontScale: Float) {
        showPlayer(fontScale)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        // The title is one line of headline type, 32 dp tall at the default size. Android scales
        // text non-linearly above 1.3x, so twice the size is 48 dp, not 64: it grows, not doubles.
        val title = compose.onNodeWithTag("player.title", useUnmergedTree = true).fetchSemanticsNode().size.height / density
        assertTrue(if (fontScale > 1f) title >= 40f else title in 28f..36f,
            "The control requires the text scaled by $fontScale: the title is $title dp tall")
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val notice = compose.onNodeWithTag(SKIP_NOTICE_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val (checked, covered) = interactiveNodesCoveredBy(root, notice)
        val tags = checked.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        // A check that finds no controls proves nothing: the transport must be among them.
        assertTrue(tags.containsAll(listOf("player.close", "player.queue", "player.scrubber", "player.shuffle",
            "player.previous", "player.playpause", "player.next", "player.repeat")),
            "The control requires the player's controls to be checked: $tags")
        assertTrue(notice.boundsInRoot.height > 0f, "The control requires the notice drawn")
        assertEquals(emptyList(), covered.map { describe(it) },
            "At font scale $fontScale the notice ${notice.boundsInRoot} covers these controls")
        // Over the cover, which takes no input, and inside it as it is drawn: paused, as here, the
        // cover settles back to PAUSED_COVER_SCALE of its box.
        assertEquals(listOf("Play"), compose.onNodeWithTag("player.playpause").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription), "The control requires playback paused")
        val cover = compose.onNodeWithTag("player.artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inset = cover.width * (1 - PAUSED_COVER_SCALE) / 2
        val drawn = Rect(cover.left + inset, cover.top + inset, cover.right - inset, cover.bottom - inset)
        assertTrue(drawn.contains(notice.boundsInRoot), "The notice ${notice.boundsInRoot} must lie on the drawn cover $drawn")
        // The sentence naming the track fits a third of the cover at the default size; at twice
        // the size it would not, and the shorter sentence stands in.
        assertEquals(if (fontScale > 1f) "Couldn’t play a track. Skipped." else sentence, notice.config[SkipNoticeDrawnSentence],
            "At font scale $fontScale")
        val nearest = checked.filter { it.boundsInRoot.top >= notice.boundsInRoot.bottom }.minOfOrNull { it.boundsInRoot.top }
        println("SKIP NOTICE PLACEMENT phone 360x640 font-scale=$fontScale title-height=${title}dp " +
            "notice=${notice.boundsInRoot} cover=$cover drawn-cover=$drawn controls-checked=${checked.size} " +
            "nearest-control-top-below=$nearest drawn=${notice.config[SkipNoticeDrawnSentence]}")
    }

    @Test fun aTapOnThePlayerDoesNotReachThePagesBeneathIt() {
        var beneath = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("beneath").clickable { beneath++ })
                    NowPlayingScreen(account, playing(), controller) {}
                }
            }
        }
        compose.onNodeWithTag("player.title", useUnmergedTree = true).performTouchInput { click(center) }
        compose.onNodeWithTag(SKIP_NOTICE_TAG, useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(0, beneath, "The player covers the pages beneath it; a tap on it must not fall through")
    }

    /**
     * The frame shows the notice on the page above the now-playing bar and the tabs, and while the
     * full player is open only the player's own: one notice, announced once, where the person looks.
     */
    @Test fun theNoticeIsOnThePageUntilThePlayerOpensAndThenOnlyOnThePlayer() {
        var open by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                PhoneFrame(account, playing(), controller, open, { open = it },
                    tabs = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("tabs")) }) {
                    Box(Modifier.fillMaxSize().testTag("page"))
                }
            }
        }
        compose.waitForIdle()
        val onPage = compose.onAllNodes(hasTestTag(SKIP_NOTICE_TAG), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, onPage.size, "The page must show the notice while the player is closed")
        val notice = onPage.single().boundsInRoot
        val bar = compose.onNodeWithTag("player.mini", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("tabs", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(notice.bottom <= bar.top && notice.bottom <= tabs.top,
            "The notice $notice must end above the now-playing bar $bar and the tabs $tabs")
        assertTrue(!inside(onPage.single(), "player.full"), "The control requires the page's notice, not the player's")

        open = true
        compose.waitForIdle()
        val shown = compose.onAllNodes(hasTestTag(SKIP_NOTICE_TAG), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, shown.size, "With the player open exactly one notice may exist, or two live regions announce it")
        assertTrue(inside(shown.single(), "player.full"), "The notice shown must be the player's own")
    }

    /**
     * While the full player is open a screen reader reaches nothing beneath it -- the page, the
     * now-playing bar or the tabs. An accessibility action bypasses touch hit testing, so a covered
     * row it could reach would start a queue the person cannot see. Once the player closes, all of it
     * is reachable again.
     */
    @Test fun aScreenReaderReachesNothingBeneathTheOpenPlayer() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        var open by mutableStateOf(false)
        var beneath = 0
        compose.setContent {
            MaterialTheme {
                PhoneFrame(account, playing(), controller, open, { open = it },
                    tabs = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("tabs").clickable { beneath++ }) }) {
                    Box(Modifier.fillMaxSize().testTag("page.row").semantics { contentDescription = "Library row" }
                        .clickable { beneath++ })
                }
            }
        }
        compose.waitForIdle()
        val provider = composeView(compose.activity.window.decorView).accessibilityNodeProvider
        val beneathTags = listOf("page.row", "player.mini", "tabs")
        fun ids(tags: List<String>) = tags.associateWith { tag ->
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().single().id
        }
        fun reachable(id: Int) = provider.createAccessibilityNodeInfo(id)?.isVisibleToUser == true
        val closed = ids(beneathTags)
        assertTrue(closed.values.all(::reachable), "The control requires the page, the bar and the tabs reachable: $closed")

        open = true
        compose.waitForIdle()
        val player = ids(listOf("player.close", "player.playpause", "player.next", "player.scrubber", SKIP_NOTICE_TAG))
        assertTrue(player.values.all(::reachable), "The control requires the player's controls and notice reachable")
        assertEquals(emptyList(), closed.filterValues { provider.createAccessibilityNodeInfo(it) != null }.keys.toList(),
            "A screen reader must reach nothing beneath the open player")
        assertEquals(emptyList(), beneathTags.filter { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() },
            "Nothing beneath the open player may remain in the merged tree a screen reader is handed")
        assertFalse(provider.performAction(closed.getValue("page.row"), AccessibilityNodeInfo.ACTION_CLICK, null),
            "An accessibility click must not reach the covered row")
        compose.waitForIdle()
        assertEquals(0, beneath, "Nothing beneath the open player may be activated")

        open = false
        compose.waitForIdle()
        val again = ids(beneathTags)
        assertTrue(again.values.all(::reachable), "Once the player closes, the page, the bar and the tabs are reachable again")
        assertTrue(provider.performAction(again.getValue("page.row"), AccessibilityNodeInfo.ACTION_CLICK, null))
        compose.waitForIdle()
        assertEquals(1, beneath, "The control requires an accessibility click to reach the page once the player closes")
    }

    /**
     * Back closes the player only while it covers the page. A player flagged open with no playback
     * to show covers nothing, so Back goes back on the page, or -- with nowhere to go back to there
     * -- reaches the system, instead of being spent closing a player nobody can see.
     */
    @Test fun backClosesThePlayerOnlyWhileItCoversThePage() {
        var open by mutableStateOf(true)
        var playback by mutableStateOf<AndroidPlaybackController?>(null)
        var pageHasBack by mutableStateOf(true)
        val asked = mutableListOf<Boolean>()
        var pageBacks = 0
        var systemBacks = 0
        // Registered before the frame composes, so the frame's handlers are consulted first.
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { systemBacks++ }
            })
        }
        compose.setContent {
            MaterialTheme {
                PhoneFrame(account, playing(), playback, open, { asked += it; open = it },
                    back = if (pageHasBack) { { pageBacks++ } } else null,
                    tabs = { Box(Modifier.fillMaxWidth().height(80.dp)) }) { Box(Modifier.fillMaxSize()) }
            }
        }
        fun back() {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
        compose.waitForIdle()
        assertEquals(emptyList(), compose.onAllNodes(hasTestTag("player.full"), useUnmergedTree = true).fetchSemanticsNodes(),
            "The control requires no player drawn: open is flagged, but there is no playback")
        back()
        assertEquals(1, pageBacks, "With no player covering it, Back goes back on the page")
        assertEquals(emptyList(), asked, "Back must not be spent closing a player that covers nothing")
        pageHasBack = false
        compose.waitForIdle()
        back()
        assertEquals(1, systemBacks, "With nowhere to go back to on the page, Back reaches the system")
        assertEquals(emptyList(), asked)

        playback = controller
        pageHasBack = true
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasTestTag("player.full"), useUnmergedTree = true).fetchSemanticsNodes().size,
            "The control requires the player drawn once there is playback")
        back()
        assertEquals(listOf(false), asked, "While the player covers the page, Back closes it")
        assertEquals(1, pageBacks, "...and does not also go back on the page beneath")
        assertEquals(1, systemBacks)
    }

    /**
     * The page is hidden from a screen reader until the player's exit slide has finished: while the
     * player is still on screen there is only one layer to reach. Once it has gone, the page is
     * reachable again.
     */
    @Test fun thePageStaysHiddenFromAScreenReaderUntilThePlayerHasSlidAway() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        var open by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                PhoneFrame(account, playing(), controller, open, { open = it },
                    tabs = { Box(Modifier.fillMaxWidth().height(80.dp)) }) {
                    Box(Modifier.fillMaxSize().testTag("page.row").semantics { contentDescription = "Library row" }
                        .clickable { })
                }
            }
        }
        compose.waitForIdle()
        val provider = composeView(compose.activity.window.decorView).accessibilityNodeProvider
        val row = compose.onAllNodes(hasTestTag("page.row"), useUnmergedTree = true).fetchSemanticsNodes().single().id
        fun reachable() = provider.createAccessibilityNodeInfo(row)?.isVisibleToUser == true
        assertTrue(reachable(), "The control requires the row reachable while the player is closed")
        open = true
        compose.waitForIdle()
        assertFalse(reachable(), "The control requires the row hidden while the player is open")

        compose.mainClock.autoAdvance = false
        open = false
        val window = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun player() = compose.onAllNodes(hasTestTag("player.full"), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
        var frames = 0
        while (frames < 60 && (player()?.boundsInRoot?.top ?: 0f) <= window.top) { compose.mainClock.advanceTimeByFrame(); frames++ }
        val sliding = assertNotNull(player(), "The control requires the player still on screen, sliding away")
        assertTrue(sliding.boundsInRoot.top > window.top && sliding.boundsInRoot.top < window.bottom,
            "The control requires the player part way through its slide: ${sliding.boundsInRoot} after $frames frames")
        assertFalse(reachable(), "While the player is still on screen the page beneath must not be reachable")

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(emptyList(), compose.onAllNodes(hasTestTag("player.full"), useUnmergedTree = true).fetchSemanticsNodes(),
            "The control requires the slide finished")
        assertTrue(reachable(), "Once the player has gone, the page is reachable again")
    }

    private fun inside(node: SemanticsNode, tag: String) =
        generateSequence(node.parent) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun describe(node: SemanticsNode) =
        "${node.config.getOrNull(SemanticsProperties.TestTag) ?: node.config.getOrNull(SemanticsProperties.ContentDescription)} ${node.boundsInRoot}"

    private fun composeView(view: View): View {
        if (view.javaClass.name.endsWith("AndroidComposeView")) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) runCatching { return composeView(view.getChildAt(i)) }
        error("No compose view under $view")
    }
}

/** The notice's own text, measured as a device measures it (spec §12.12 rules 5 and 8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SkipNoticeMeasuredTextTest {
    @get:Rule val compose = createComposeRule()
    private val now = 50_000L

    /** Four regions at font scales 1, 1.5, 2 and 3 in one composition: the card stops growing at 1.5. */
    @Test fun theTextStopsGrowingAtOneAndAHalfTimesItsSize() {
        compose.mainClock.autoAdvance = false
        val scales = listOf(1f, 1.5f, 2f, 3f)
        compose.setContent {
            val base = LocalDensity.current
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (scale in scales) CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                    MaterialTheme {
                        SkipNoticeRegion(AndroidSkipNotice(1, "Unplayable Probe", now), true,
                            MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface,
                            MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth().requiredHeight(460.dp)) { now }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        val heights = compose.onAllNodes(hasTestTag(SKIP_NOTICE_TAG)).fetchSemanticsNodes().map { it.size.height }
        assertEquals(4, heights.size, "The control requires four notices")
        assertTrue(heights[1] > heights[0], "The control requires the text to grow up to the cap: $heights")
        assertEquals(heights[1], heights[2], "The text must stop growing at 1.5x, not reach 2x: $heights")
        assertEquals(heights[1], heights[3], "The text must stop growing at 1.5x, not reach 3x: $heights")
    }

    /**
     * A region narrower than 240 dp keeps its width for the words: 8 dp margins instead of 16, and
     * no glyph -- measured from where the card sits in a 200 dp and a 300 dp region side by side.
     */
    @Test fun aRegionNarrowerThan240DpTightensTheMargins() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column {
                    for ((tag, width) in listOf("region.narrow" to 200, "region.wide" to 300)) {
                        SkipNoticeRegion(AndroidSkipNotice(1, "Unplayable Probe", now), true,
                            MaterialTheme.colorScheme.inverseSurface, MaterialTheme.colorScheme.inverseOnSurface,
                            MaterialTheme.typography.bodyMedium, Modifier.width(width.dp).height(300.dp).testTag(tag)) { now }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val cards = compose.onAllNodes(hasTestTag(SKIP_NOTICE_TAG)).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(2, cards.size, "The control requires two notices")
        val insets = listOf("region.narrow", "region.wide").mapIndexed { i, tag ->
            val region = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(region.contains(cards[i]), "The control requires each card inside its own region")
            listOf(cards[i].left - region.left, region.right - cards[i].right, region.bottom - cards[i].bottom).map { it / density }
        }
        insets[0].forEach { assertEquals(8f, it, 0.5f, "A region under 240 dp keeps 8 dp margins: ${insets[0]}") }
        insets[1].forEach { assertEquals(16f, it, 0.5f, "The control requires 16 dp margins in a 300 dp region: ${insets[1]}") }
    }

    /** The sentence naming the track gives way when its card and margin would need more than a third of the region. */
    @Test fun theNamedSentenceGivesWayAtAThirdOfTheRegion() {
        compose.mainClock.autoAdvance = false
        val long = "Unplayable Long Probe -- Concerto for Two Violins in D minor, BWV 1043"
        var height by mutableStateOf(460)
        compose.setContent {
            MaterialTheme {
                SkipNoticeRegion(AndroidSkipNotice(1, long, now), true, MaterialTheme.colorScheme.inverseSurface,
                    MaterialTheme.colorScheme.inverseOnSurface, MaterialTheme.typography.bodyMedium,
                    Modifier.fillMaxWidth().height(height.dp)) { now }
            }
        }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        val node = { compose.onNodeWithTag(SKIP_NOTICE_TAG).fetchSemanticsNode() }
        val named = "Couldn’t play “$long”. Skipped."
        assertEquals(named, node().config[SkipNoticeDrawnSentence], "The control requires the named sentence in 460 dp")
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        // The card, plus the 16 dp margin below it.
        val needed = node().size.height / density + 16f
        var smallest = 460
        for (h in 460 downTo 40 step 2) {
            height = h
            compose.mainClock.advanceTimeByFrame()
            if (node().config[SkipNoticeDrawnSentence] == named) smallest = h else break
        }
        assertTrue(smallest < 460, "The control requires the scan to reach the switch")
        val ratio = smallest / needed
        assertTrue(ratio in 2.9f..3.1f, "The named sentence must need a region three times its card: $smallest dp for $needed dp")
    }
}

private fun Rect.contains(other: Rect) =
    other.left >= left - 0.5f && other.top >= top - 0.5f && other.right <= right + 0.5f && other.bottom <= bottom + 0.5f

/**
 * Every node a person can act on -- tap, long-press, drag, scroll or move focus to -- under [root],
 * leaving out [notice] and the containers it is drawn inside; and those of them [notice] overlaps.
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
