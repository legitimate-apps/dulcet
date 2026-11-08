package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a person focusing down a settings page sees, in a container of the screen's size: the page moves by what it
 * overflows and no further, and it says so while its last row lies below the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvPageScrollTest {
    @get:Rule val compose = createComposeRule()

    /** A title, then [rows] rows of 64 dp in one group, with the spacing a settings page has. */
    private fun page(rows: Int, container: Dp) {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(container)) {
                    CompositionLocalProvider(tvSteadyBringIntoView()) {
                        TvSettingsPage("page") {
                            item { Text("Title", Modifier.testTag("title").height(44.dp)) }
                            items(rows) { index ->
                                androidx.tv.material3.ListItem(selected = false, onClick = {},
                                    headlineContent = { Text("Row $index") },
                                    modifier = Modifier.height(64.dp).testTag("row.$index"))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun focusDownTo(last: Int) {
        compose.onNodeWithTag("row.0").requestFocus()
        compose.waitForIdle()
        for (index in 0 until last) {
            compose.onNodeWithTag("row.$index").performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("row.$last").assertIsFocused()
    }

    private fun titleTop() = compose.onNodeWithTag("title", useUnmergedTree = true).getUnclippedBoundsInRoot().top

    /**
     * The nearly-fitting page, round 9's Storage: 24 + 44 + 16 + 4 x (64 + 16) - 16 = 388 dp of rows, in a 381 dp
     * container whose margin starts at 354: the last row crosses the screen's edge by 7 dp. With the page's bottom padding
     * as its scroll range, it moves by 34 dp and no more, however much room the rule asks for.
     */
    @Test fun aPageThatNearlyFitsMovesByWhatItOverflowsNotByTheRoomTheRuleWants() {
        page(rows = 4, container = 381.dp)
        val before = titleTop()
        focusDownTo(3)
        val moved = before - titleTop()
        assertEquals(34.dp.value, moved.value, 0.6f, "the page moved $moved")
    }

    /** A page that fits with its margin does not move for any row. */
    @Test fun aPageThatFitsDoesNotMoveEvenWithFocusOnItsLastRow() {
        page(rows = 4, container = 415.dp)
        val before = titleTop()
        focusDownTo(3)
        assertEquals(before, titleTop())
    }

    /** The other way round: a long page always moves enough to show the row after the focused one. */
    @Test fun aLongPageShowsTheNextRowAboveTheFold() {
        page(rows = 12, container = 440.dp)
        focusDownTo(6)
        val next = compose.onNodeWithTag("row.7").getUnclippedBoundsInRoot()
        assertTrue(next.top < 440.dp, "the next row starts at ${next.top}, below the 440 dp fold")
    }

    @Test fun theChevronShowsWhileTheLastRowIsBelowTheScreenAndGoesAtTheEnd() {
        page(rows = 12, container = 440.dp)
        compose.onNodeWithTag("page.moreBelow", useUnmergedTree = true).assertExists()
        focusDownTo(11)
        compose.onNodeWithTag("page.moreBelow", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aPageThatFitsHasNoChevron() {
        page(rows = 4, container = 415.dp)
        compose.onNodeWithTag("page.moreBelow", useUnmergedTree = true).assertDoesNotExist()
    }
}
