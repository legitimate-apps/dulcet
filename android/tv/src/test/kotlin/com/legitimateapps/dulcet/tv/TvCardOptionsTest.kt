package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.tv.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A grid's explicit secondary button is reachable and reversible using only a D-pad. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvCardOptionsTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun moreOptionsOpensWithRemoteInvokesSelectedActionAndRestoresFocus() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    TvAction("Open album", "card") { calls += "open" }
                    TvCardOptions("card", "Album", listOf(
                        TvQueueChoice("Add to Library", "card.actions.membership") { calls += "membership" },
                        TvQueueChoice("Play Next", "card.actions.playNext") { calls += "next" },
                    ))
                }
            }
        }
        compose.onNodeWithTag("card").performSemanticsAction(SemanticsActions.RequestFocus)
        key("card", Key.DirectionDown)
        compose.onNodeWithTag("card.more").assertIsFocused()
        key("card.more", Key.DirectionCenter)
        compose.onNodeWithTag("card.actions.membership").assertIsFocused()
        key("card.actions.membership", Key.DirectionCenter)
        assertEquals(listOf("membership"), calls)
        compose.onNodeWithTag("card.actions").assertDoesNotExist()
        compose.onNodeWithTag("card.more").assertIsFocused()
        key("card.more", Key.DirectionCenter)
        key("card.actions.membership", Key.DirectionDown)
        key("card.actions.playNext", Key.DirectionCenter)
        assertEquals(listOf("membership", "next"), calls)
        compose.onNodeWithTag("card.more").assertIsFocused()
        key("card.more", Key.DirectionCenter)
        key("card.actions.membership", Key.DirectionDown)
        key("card.actions.playNext", Key.DirectionDown)
        key("card.actions.cancel", Key.DirectionCenter)
        assertEquals(listOf("membership", "next"), calls, "Cancel invokes no action and no accidental card activation")
        compose.onNodeWithTag("card.more").assertIsFocused()
    }

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
