package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A scrolled menu's top cue and a focused destructive row: what each must still say. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvMenuRowsAndSignalsTest {
    @get:Rule val compose = createComposeRule()

    private val rows = (0 until 9).map { "menu.row.$it" }

    private fun node(tag: String): SemanticsNode =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun gone(tag: String) = node(tag).config.getOrElse(TvMenuRowGone) { false }

    @Test fun aRowWhoseWordsWouldDimUnderTheTopCueIsNotDrawnAndTheFocusedOneAlwaysIs() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvQueueMenu("menu", "Album", rows.mapIndexed { index, tag ->
                    TvQueueChoice("Row $index", tag, null, index == rows.lastIndex) {}
                }, "Cancel") {}
            }
        }
        compose.waitForIdle()
        rows.forEach { assertFalse("$it hidden before any scroll", gone(it)) }
        // Condition: the menu must really have scrolled, or this proves nothing.
        var pressed = 0
        while (pressed++ < 14 && !node("menu.cancel").config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }) {
            compose.onNodeWithTag("menu").performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, "menu.moreAbove"), useUnmergedTree = true).assertExists()
        val menuTop = node("menu").boundsInRoot.top
        val density = node("menu").layoutInfo.density.density
        val hidden = rows.filter(::gone)
        assertTrue("Scrolled to its foot, no row was hidden under the cue", hidden.isNotEmpty())
        (rows + "menu.cancel").forEach { tag ->
            val row = node(tag)
            val middle = (row.boundsInRoot.top + row.boundsInRoot.bottom) / 2 - menuTop
            // Whatever row sits below the sheet's edge and whose words reach the 56 dp cue must not be drawn half-dimmed.
            val wordsTop = middle - 12 * density
            if (middle > 0 && wordsTop < 56 * density) assertTrue("$tag has words under the cue and still draws", gone(tag))
            if (wordsTop >= 56 * density) assertFalse("$tag is clear of the cue and was hidden", gone(tag))
        }
    }

    @Test fun theRemoteComingBackUpFindsEveryHiddenRowAndItShows() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvQueueMenu("menu", "Album", rows.mapIndexed { index, tag -> TvQueueChoice("Row $index", tag, null, false) {} }, "Cancel") {}
            }
        }
        compose.waitForIdle()
        fun focused(tag: String) = node(tag).config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
        fun press(key: Key) { compose.onNodeWithTag("menu").performKeyInput { pressKey(key) }; compose.waitForIdle() }
        var pressed = 0
        while (pressed++ < 14 && !focused("menu.cancel")) press(Key.DirectionDown)
        assertTrue("Condition: the walk down reached Cancel", focused("menu.cancel"))
        val wereHidden = rows.filter(::gone).toSet()
        assertTrue("Condition: a row was hidden at the foot", wereHidden.isNotEmpty())
        val met = mutableSetOf<String>()
        pressed = 0
        while (pressed++ < 14 && !focused(rows.first())) {
            press(Key.DirectionUp)
            rows.filter(::focused).forEach { met += it; assertFalse("$it has focus and is hidden", gone(it)) }
        }
        assertTrue("The walk up never met a row that had been hidden: $wereHidden", met.containsAll(wereHidden))
    }

    /** Text on the fill reads as the fill's own: the fill itself is judged on the tour's frames, as a host run does not paint. */
    @Test fun aDestructiveRowKeepsItsSignalFocusedAndAtRest() {
        val colors = darkColorScheme()
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Column { TvSettingRow("Remove", "row.destructive", error = true) {} }
            }
        }
        fun colour(): Color {
            compose.waitForIdle()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNode(hasText("Remove"), useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.single().layoutInput.style.color
        }
        assertEquals(colors.error, colour())
        compose.onNodeWithTag("row.destructive").requestFocus()
        assertEquals(colors.onError, colour())
    }
}
