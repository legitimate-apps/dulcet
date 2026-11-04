package com.legitimateapps.dulcet.tv

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
}
