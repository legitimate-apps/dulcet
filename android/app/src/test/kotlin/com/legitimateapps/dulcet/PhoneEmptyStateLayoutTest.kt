package com.legitimateapps.dulcet

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.Dp
import com.legitimateapps.dulcet.ui.DulcetIcons
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The statement of a page with nothing on it, and glyphs that read beside text at large font scales. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class PhoneEmptyStateLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun show(fontScale: Float = 1f, fillsPage: Boolean = true, body: String = "Music you favorite appears here.") {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    LazyColumn(Modifier.testTag("list")) {
                        item {
                            EmptyState(DulcetIcons.FavouriteBorder, "Empty title", body = body, actionLabel = "Browse",
                                onAction = {}, actionTag = "action", fillsPage = fillsPage)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun Dp.fractionOf(height: Dp) = value / height.value

    @Test fun aPageWithNothingOnItCentresItsStatementInTheWindow() {
        show()
        val title = compose.onNodeWithText("Empty title").getBoundsInRoot()
        val centre = ((title.top + title.bottom) / 2).fractionOf(800.dp)
        // The window less a top bar and a tab bar, whose middle sits a little above the window's own.
        assertTrue(centre in 0.28f..0.5f, "the title's centre is at $centre of the window")
    }

    @Test fun aStatementUnderPageContentKeepsItsOwnHeight() {
        show(fillsPage = false)
        val height = compose.onNodeWithTag("action", useUnmergedTree = true).getBoundsInRoot().bottom
        assertTrue(height.fractionOf(800.dp) < 0.35f, "a statement under a hero stays compact, its action ended at $height")
    }

    /** At large text the statement outgrows the minimum: its action is still reached by scrolling, never clipped. */
    @Test fun aStatementTallerThanThePageStillReachesItsAction() {
        show(fontScale = 3f, body = "Music you favorite appears here. ".repeat(30))
        compose.onNodeWithTag("list").performScrollToNode(hasTestTag("action"))
        compose.onNodeWithTag("action").assertIsDisplayed()
    }

    @Test fun aDetailButtonsGlyphGrowsWithItsTextUpToTheLimit() {
        fun width(fontScale: Float): Dp {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    MaterialTheme { DetailActionButtons(DetailAction(R.string.action_play, DulcetIcons.Play, "play", onClick = {})) }
                }
            }
            compose.waitForIdle()
            return compose.onNodeWithTag("play.icon", useUnmergedTree = true).getUnclippedBoundsInRoot().width
        }
        assertEquals(30f, width(2f).value, 0.5f, "font scale 2 draws the glyph at the capped 1.5 times")
    }

    @Test fun aDetailButtonsGlyphIsUnchangedAtDefaultText() {
        compose.setContent {
            MaterialTheme { DetailActionButtons(DetailAction(R.string.action_play, DulcetIcons.Play, "play", onClick = {})) }
        }
        compose.waitForIdle()
        assertEquals(20f, compose.onNodeWithTag("play.icon", useUnmergedTree = true).getUnclippedBoundsInRoot().width.value, 0.5f)
    }
}
