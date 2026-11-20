package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.tv.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A menu capped to the screen scrolls, and a scrolling list that looks complete hides its last rows
 * (round 4 lost Refresh that way). Rows below the sheet's edge say so, and the remote reaches the last one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvMenuScrollCueTest {
    @get:Rule val compose = createComposeRule()

    private fun choices(count: Int) = (0 until count).map { TvQueueChoice("Action $it", "menu.$it") {} }

    @Test fun aLongMenuSaysMoreLiesBelowAndTheRemoteReachesItsLastRow() {
        compose.setContent { MaterialTheme { TvQueueMenu("menu", "Album", choices(9), "Cancel") {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("menu.moreBelow").assertExists()
        compose.onNodeWithTag("menu.moreAbove").assertDoesNotExist()
        for (index in 0 until 8) {
            compose.onNodeWithTag("menu.$index").performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("menu.8").assertIsFocused()
        compose.onNodeWithTag("menu.moreAbove").assertExists()
        compose.onNodeWithTag("menu.8").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag("menu.cancel").assertIsFocused()
        compose.onNodeWithTag("menu.moreBelow").assertDoesNotExist()
    }

    @Test fun aMenuThatFitsShowsNoCue() {
        compose.setContent { MaterialTheme { TvQueueMenu("menu", "Album", choices(2), "Cancel") {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("menu.moreBelow").assertDoesNotExist()
        compose.onNodeWithTag("menu.moreAbove").assertDoesNotExist()
    }

    /** As the library's scaffold gives it. Round 9's album menu stopped with Cancel clipped at the bottom of the list. */
    @Test fun theRowBeforeCancelBringsCancelWhollyIntoViewAndEndsTheCue() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(tvSteadyBringIntoView()) { TvQueueMenu("menu", "Album", choices(9), "Cancel") {} }
            }
        }
        compose.waitForIdle()
        for (index in 0 until 8) {
            compose.onNodeWithTag("menu.$index").performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("menu.8").assertIsFocused()
        compose.onNodeWithTag("menu.moreBelow").assertDoesNotExist()
        val viewport = compose.onNodeWithTag("menu.rows").getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag("menu.cancel").getUnclippedBoundsInRoot()
        kotlin.test.assertTrue(cancel.bottom <= viewport.bottom, "Cancel ends at ${cancel.bottom}, the list at ${viewport.bottom}")
    }

    /** Coming back up, the focused row is never left under the pinned header. */
    @Test fun aRowReachedGoingUpIsClearOfTheTopFade() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(tvSteadyBringIntoView()) { TvQueueMenu("menu", "Album", choices(9), "Cancel") {} }
            }
        }
        compose.waitForIdle()
        for (index in 0 until 8) {
            compose.onNodeWithTag("menu.$index").performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        for (index in 8 downTo 2) {
            compose.onNodeWithTag("menu.$index").performKeyInput { pressKey(Key.DirectionUp) }
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("menu.rows").getUnclippedBoundsInRoot()
            val row = compose.onNodeWithTag("menu.${index - 1}").getUnclippedBoundsInRoot()
            kotlin.test.assertTrue(row.top >= viewport.top, "row ${index - 1} starts at ${row.top}, the list at ${viewport.top}")
        }
    }
}
