package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.legitimateapps.dulcet.ui.DulcetIcons
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geometry the shared phone components promise (docs/DESIGN-CONVENTIONS, Android; AND-01, 04, 06, 07, 08), asserted
 * where a later screen would otherwise drift: the empty state's gaps and width cap, the section header's keyline and
 * heading role, the notice's padding, the hero's fractions and the rail tile's growth with the font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneComponentGeometryTest {
    @get:Rule val compose = createComposeRule()

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
    private fun text(text: String): DpRect = compose.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()

    @Composable private fun At(fontScale: Float = 1f, width: Int = 411, content: @Composable () -> Unit) {
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density, fontScale)) {
            MaterialTheme { Column(Modifier.requiredWidth(width.dp)) { content() } }
        }
    }

    @Test fun anEmptyStateKeepsItsGapsIconToTitleToBodyToAction() {
        compose.setContent {
            At { Box(Modifier.testTag("block")) {
                EmptyState(DulcetIcons.FavouriteBorder, "Empty title", body = "Music you favorite appears here.",
                    actionLabel = "Browse", onAction = {}, actionTag = "action")
            } }
        }
        compose.waitForIdle()
        val block = bounds("block"); val title = text("Empty title"); val body = text("Music you favorite appears here.")
        val action = bounds("action")
        // The icon has no node of its own: it is the block's padding plus the 48 dp glyph above the 16 dp gap.
        assertEquals((DulcetSpacing.xxl + DulcetSizes.emptyIcon + DulcetSpacing.l).value, (title.top - block.top).value, 0.5f, "icon to title")
        assertEquals(DulcetSpacing.s.value, (body.top - title.bottom).value, 0.5f, "title to body")
        // A Button's 48 dp touch box holds its 40 dp surface centred: the visible gap is the spacer, the node's 4 dp more at most.
        assertTrue((action.top - body.bottom).value in DulcetSpacing.xl.value - 0.5f..DulcetSpacing.xl.value + 4.5f, "body to action ${action.top - body.bottom}")
    }

    @Test fun anEmptyStateNeverGrowsWiderThanItsCap() {
        val long = "Music you favorite appears here, and this sentence keeps going so that it would fill a wide window. ".repeat(3)
        compose.setContent { At(width = 900) { EmptyState(DulcetIcons.FavouriteBorder, "Empty title", body = long) } }
        compose.waitForIdle()
        val body = text(long)
        // The condition: the window is wider than the cap plus the block's padding, so the cap is what limits the line.
        assertTrue(900 > DulcetSizes.emptyMaxWidth.value + 2 * DulcetSpacing.xxl.value)
        assertTrue(body.width <= DulcetSizes.emptyMaxWidth + 0.5.dp, "the body is ${body.width} wide")
        assertTrue(body.width > DulcetSizes.emptyMaxWidth - 40.dp, "the body did not fill its cap, so the cap was never met: ${body.width}")
    }

    @Test fun aSectionHeadersSeeAllEndsItsTextOnTheKeylineAndItsTitleIsAHeading() {
        compose.setContent {
            At { DulcetSectionHeader("Recently played", Modifier.testTag("header"), onSeeAll = {}, seeAllTag = "seeAll", seeAllLabel = "See all") }
        }
        compose.waitForIdle()
        val header = bounds("header"); val label = text("See all"); val title = text("Recently played")
        assertEquals(DulcetSpacing.pageGutter.value, (title.left - header.left).value, 0.5f, "the title starts on the gutter")
        // The label's node is its text box; the text ink ends at its end.
        assertEquals(DulcetSpacing.pageGutter.value, (header.right - label.right).value, 0.5f, "See all's text ends ${header.right - label.right} from the edge")
        // The button's node is its 40 dp surface; the row holds the 48 dp target it is centred in.
        assertTrue(header.height >= DulcetSizes.touchTarget, "the header is shorter than See all's touch target")
        val heading = compose.onNodeWithText("Recently played", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(heading.config.contains(SemanticsProperties.Heading), "the title is not a heading")
    }

    @Test fun aShelfHeaderIsTitleLargeAndADetailHeaderTitleMedium() {
        var large = 0f; var medium = 0f
        compose.setContent {
            At {
                large = MaterialTheme.typography.titleLarge.lineHeight.value
                medium = MaterialTheme.typography.titleMedium.lineHeight.value
                DulcetSectionHeader("Shelf", topGap = 0.dp)
                DulcetSectionHeader("Detail", role = SectionRole.Detail, topGap = 0.dp)
            }
        }
        compose.waitForIdle()
        assertTrue(large > medium, "the two roles' styles are not distinct, so this test cannot tell them apart")
        assertEquals(large, text("Shelf").height.value, 0.5f)
        assertEquals(medium, text("Detail").height.value, 0.5f)
    }

    @Test fun aNoticePadsItsTextSixteenAndPutsItsActionAtTheEnd() {
        compose.setContent {
            At { DulcetNotice(NoticeTone.Error, "Can't reach the server", Modifier.testTag("notice"),
                body = "Showing what this phone already has.", actionLabel = "Try again", onAction = {}, actionTag = "retry") }
        }
        compose.waitForIdle()
        val notice = bounds("notice"); val title = text("Can't reach the server"); val retry = bounds("retry")
        assertEquals((DulcetSpacing.l + DulcetSizes.icon + DulcetSpacing.m).value, (title.left - notice.left).value, 0.5f, "padding, glyph and gap before the text")
        assertTrue((title.top - notice.top).value >= DulcetSpacing.l.value - 0.5f, "the title is inside the padding")
        assertEquals(DulcetSpacing.l.value, (notice.right - retry.right).value, 0.5f, "the action ends on the notice's padding")
        // The action's 48 dp target ends on the padding; its node is the 40 dp surface centred in it, 4 dp higher.
        assertEquals(DulcetSpacing.l.value + 4f, (notice.bottom - retry.bottom).value, 0.5f, "the action is the last line")
    }

    @Test fun theDetailHeroIsAFixedFractionCappedAndSmallerAtLargeText() {
        compose.setContent {
            Column {
                At { Box(Modifier.detailHeroArt().testTag("hero")) }
                At(fontScale = 2f) { Box(Modifier.detailHeroArt().testTag("heroLarge")) }
                At(width = 900) { Box(Modifier.detailHeroArt().testTag("heroWide")) }
            }
        }
        compose.waitForIdle()
        assertEquals(411 * DulcetSizes.heroFraction, bounds("hero").width.value, 0.5f)
        assertEquals(bounds("hero").width, bounds("hero").height)
        assertEquals(411 * DulcetSizes.heroFractionLarge, bounds("heroLarge").width.value, 0.5f)
        assertEquals(DulcetSizes.heroMax, bounds("heroWide").width)
    }

    /** The optical offset gives its 12 dp back to the row: the slot is laid out narrower, the button keeps its size. */
    @Test fun theTrailingOpticalSlotTakesTwelveDpLessThanItsButton() {
        compose.setContent {
            At { androidx.compose.foundation.layout.Row {
                Box(Modifier.weight(1f).height(8.dp).testTag("text"))
                Box(Modifier.trailingOptical()) { Box(Modifier.size(DulcetSizes.touchTarget).testTag("button")) }
            } }
        }
        compose.waitForIdle()
        assertEquals(DulcetSizes.touchTarget, bounds("button").width)
        assertEquals(411f - (DulcetSizes.touchTarget - DulcetSizes.trailingOptical).value, bounds("text").width.value, 0.5f)
        assertEquals(bounds("text").right.value, bounds("button").left.value, 0.5f)
        assertEquals(411f + DulcetSizes.trailingOptical.value, bounds("button").right.value, 0.5f, "the button runs into the end padding")
    }

    @Test fun aRailTileWidensWithTheFontUpToItsCap() {
        compose.setContent {
            Column {
                At { Box(Modifier.railTileWidth().height(8.dp).testTag("tile")) }
                At(fontScale = 1.3f) { Box(Modifier.railTileWidth().height(8.dp).testTag("tile13")) }
                At(fontScale = 2f) { Box(Modifier.railTileWidth().height(8.dp).testTag("tile2")) }
            }
        }
        compose.waitForIdle()
        assertEquals(DulcetSizes.tileWidth, bounds("tile").width)
        assertEquals(DulcetSizes.tileWidth.value * 1.3f, bounds("tile13").width.value, 0.5f)
        assertEquals(DulcetSizes.tileWidth.value * DulcetSizes.tileMaxScale, bounds("tile2").width.value, 0.5f)
    }
}
