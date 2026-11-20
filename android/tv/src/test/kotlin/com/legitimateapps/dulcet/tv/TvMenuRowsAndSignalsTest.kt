package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pinned menu header, whole rows beside the scroll cues and the destructive color signal. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvMenuRowsAndSignalsTest {
    @get:Rule val compose = createComposeRule()

    private val rows = (0 until 9).map { "menu.row.$it" }

    private fun tagBounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun wordBounds(text: String) = compose.onNode(hasText(text), useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test fun artworkHeaderAndCancelShareTheActionLabelKeyline() {
        compose.setContent {
            TvTheme {
                TvQueueMenu("menu", "Album", listOf(TvQueueChoice("Row 0", "menu.row.0") {}), "Cancel",
                    supporting = "Artist", artwork = { Box { } }) {}
            }
        }
        val action = wordBounds("Row 0")
        assertEquals(action.left, wordBounds("Album").left)
        assertEquals(action.left, wordBounds("Artist").left)
        assertEquals(action.left, wordBounds("Cancel").left)
        val cancel = wordBounds("Cancel")
        assertEquals(action.bottom - action.top, cancel.bottom - cancel.top)
    }

    @Test fun theMenuStartsOnAWholeRowAndKeepsItsHeaderAboveTheRowsWhenScrolled() {
        compose.setContent {
            TvTheme { TvQueueMenu("menu", "Album", rows.mapIndexed { index, tag -> TvQueueChoice("Row $index", tag) {} }, "Cancel") {} }
        }
        val title = wordBounds("Album")
        val viewport = tagBounds("menu.rows")
        val cue = tagBounds("menu.moreBelow")
        val whole = rows.map(::tagBounds).filter { it.top >= viewport.top && it.bottom <= viewport.bottom }
        assertTrue("Condition: several rows fit, but the menu overflows", whole.size in 2 until rows.size)
        assertEquals("The list ends on a row boundary", viewport.bottom, whole.last().bottom)
        assertTrue("The cue is below the row viewport", cue.top >= viewport.bottom)

        // Walk every action in both directions. As the viewport moves, neither cue covers the focused row.
        val stops = rows + "menu.cancel"
        stops.zipWithNext().forEach { (from, to) -> pressAndCheck(from, to, Key.DirectionDown) }
        assertEquals(title, wordBounds("Album"))
        val up = tagBounds("menu.moreAbove")
        assertTrue("The up cue ends at ${up.bottom}, the viewport starts at ${viewport.top}",
            up.bottom <= viewport.top + 1.dp)
        stops.asReversed().zipWithNext().forEach { (from, to) -> pressAndCheck(from, to, Key.DirectionUp) }
    }

    private fun pressAndCheck(from: String, to: String, key: Key) {
        compose.onNodeWithTag(from).performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.onNodeWithTag(to).assertIsFocused()
        val row = tagBounds(to)
        val viewport = tagBounds("menu.rows")
        assertTrue("$to is wholly inside the viewport", row.top >= viewport.top && row.bottom <= viewport.bottom)
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
