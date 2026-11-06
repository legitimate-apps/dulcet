package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A card's context actions open on long-Select, with the remote alone: the menu's first choice is
 * focused, a choice runs and closes it with focus back on the card, and Cancel adds nothing. There
 * is no "More options" pill to stop on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvCardOptionsTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun longSelectOpensTheCardsMenuInvokesSelectedActionAndRestoresFocus() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                var menu by remember { mutableStateOf(false) }
                Column {
                    Card(onClick = { calls += "open" }, onLongClick = { menu = true },
                        modifier = Modifier.testTag("card")) { Text("Album") }
                }
                if (menu) TvQueueMenu("card.actions", "Album", listOf(
                    TvQueueChoice("Add to library", "card.actions.membership") { calls += "membership" },
                    TvQueueChoice("Play Next", "card.actions.playNext") { calls += "next" },
                ), "Cancel") { menu = false }
            }
        }
        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("card").assertIsFocused()
        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag("card.actions.membership").assertIsFocused()
        key("card.actions.membership", Key.DirectionCenter)
        assertEquals(listOf("membership"), calls)
        compose.onNodeWithTag("card.actions").assertDoesNotExist()
        compose.onNodeWithTag("card").assertIsFocused()

        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        key("card.actions.membership", Key.DirectionDown)
        key("card.actions.playNext", Key.DirectionCenter)
        assertEquals(listOf("membership", "next"), calls)
        compose.onNodeWithTag("card").assertIsFocused()

        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        key("card.actions.membership", Key.DirectionDown)
        key("card.actions.playNext", Key.DirectionDown)
        key("card.actions.cancel", Key.DirectionCenter)
        assertEquals(listOf("membership", "next"), calls, "Cancel invokes no action and no accidental card activation")
        compose.onNodeWithTag("card").assertIsFocused()

        // A plain centre press still opens the card; it never opens the menu.
        key("card", Key.DirectionCenter)
        assertEquals(listOf("membership", "next", "open"), calls)
    }

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
