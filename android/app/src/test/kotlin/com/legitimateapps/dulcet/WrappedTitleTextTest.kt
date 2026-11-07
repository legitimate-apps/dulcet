package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where a row's or a tile's name breaks at the accessibility font size. Native graphics, because only its text
 * layout really wraps; each width comes from a control measurement that proves ordinary wrapping does what the test is about.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class WrappedTitleTextTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var measurer: TextMeasurer
    private lateinit var measuringStyle: androidx.compose.ui.text.TextStyle

    private fun TextLayoutResult.lines() = (0 until lineCount).map {
        layoutInput.text.text.substring(getLineStart(it), getLineEnd(it, visibleEnd = true)).trim()
    }

    private fun shownLines(tag: String): List<String> {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.last().lines()
    }

    private fun ordinary(title: String, widthPx: Int) =
        measurer.measure(title, measuringStyle, TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = widthPx)).lines()

    /** Finds the widest width where ordinary wrapping does what [condition] says, shows the title there and returns it. */
    private fun showWhere(title: String, fontScale: Float, condition: (List<String>) -> Boolean): Int {
        var width by mutableStateOf<Dp?>(null)
        var density = 1f
        compose.setContent {
            val outer = LocalDensity.current
            density = outer.density
            CompositionLocalProvider(LocalDensity provides Density(outer.density, fontScale)) {
                MaterialTheme {
                    measurer = rememberTextMeasurer()
                    measuringStyle = MaterialTheme.typography.bodyLarge
                    width?.let { w ->
                        Box(Modifier.requiredWidth(w)) { androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                            WrappedTitleText(title, Modifier.testTag("title"), maxLines = 2)
                        } }
                    }
                }
            }
        }
        compose.waitForIdle()
        val found = compose.runOnIdle { assertNotNull((1400 downTo 40 step 2).firstOrNull { condition(ordinary(title, it)) }, "control: no width meets the condition") }
        compose.runOnIdle { width = (found / density).dp }
        compose.waitForIdle()
        return found
    }

    private fun words(line: String) = line.split(' ').count { word -> word.any(Char::isLetterOrDigit) }

    @Test fun aNameThatWouldEndOnOneWordMovesAWordDown() {
        showWhere("Paging Track 130", 2f) { it.size == 2 && words(it.last()) == 1 }
        val lines = shownLines("title")
        assertEquals(2, lines.size, lines.toString())
        assertTrue(words(lines.last()) > 1, lines.toString())
        compose.onNodeWithTag("title").assertTextEquals("Paging Track 130")
    }

    @Test fun aNameThatEndsWellKeepsItsOrdinaryBreaks() {
        val title = "A rather long fixture name for a song"
        val width = showWhere(title, 2f) { it.size == 2 && words(it.last()) > 1 }
        val ordinary = compose.runOnIdle { ordinary(title, width) }
        assertEquals(ordinary, shownLines("title"))
    }

    /** Two words have one place to break: the second stays alone, in the ordinary layout. */
    @Test fun aTwoWordNameKeepsItsOnlyBreak() {
        showWhere("Fixture Name", 2f) { it.size == 2 }
        assertEquals(listOf("Fixture", "Name"), shownLines("title"))
    }

    @Test fun aNameThatIsCutOffIsNotReshuffled() {
        val title = "A very long fixture name that cannot fit in the two lines a row allows it at all"
        val width = showWhere(title, 2f) { it.size == 2 && it.joinToString(" ").length < title.length }
        assertEquals(compose.runOnIdle { ordinary(title, width) }, shownLines("title"))
    }

    @Test fun aLongNameMovesAWordDownAndLosesNone() {
        val title = "A very long fixture name that cannot fit in the two lines a row allows it at all"
        showWhere(title, 2f) { it.size == 2 && words(it.last()) == 1 && it.joinToString(" ") == title }
        val lines = shownLines("title")
        assertEquals(title, lines.joinToString(" "))
        assertTrue(words(lines.last()) > 1, lines.toString())
    }

    /** Below the accessibility size nothing changes: a row that wraps there wraps as plain text does. */
    @Test fun anOrdinaryFontSizeNeverRebalances() {
        val title = "Paging Track 130"
        val width = showWhere(title, 1f) { it.size == 2 && words(it.last()) == 1 }
        val lines = shownLines("title")
        assertEquals(compose.runOnIdle { ordinary(title, width) }, lines)
        assertEquals(1, words(lines.last()), lines.toString())
    }

    /** A list row asks its text for intrinsic sizes: the real row, the real title, the rule still holds. */
    @Test fun inAListRowTheRuleHolds() {
        val title = "Paging Track 130"
        compose.setContent {
            val outer = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(outer.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(220.dp)) {
                        ListItem(headlineContent = { WrappedTitleText(title, Modifier.testTag("row.title"), maxLines = 2) },
                            supportingContent = { Text("Dulcet Fixtures") })
                    }
                }
            }
        }
        compose.waitForIdle()
        val lines = shownLines("row.title")
        assertEquals(2, lines.size, "control: at this width the row's title wraps: $lines")
        assertTrue(words(lines.last()) > 1, lines.toString())
    }
}
