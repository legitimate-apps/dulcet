package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rules behind where a title's dash and a status line's phrases may break. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class TextBreakRulesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aDashHoldsToTheWordBeforeIt() {
        assertEquals("a — b", "a — b".dashKeptWithWordBefore())
        assertEquals("a – b", "a – b".dashKeptWithWordBefore())
        assertEquals("a - b", "a - b".dashKeptWithWordBefore())
        assertEquals("a⁠—b", "a—b".dashKeptWithWordBefore())
    }

    @Test fun aNameWithNoFreeDashIsLeftAlone() {
        assertEquals("late-night favorites", "late-night favorites".dashKeptWithWordBefore())
        assertEquals("Plain name", "Plain name".dashKeptWithWordBefore())
    }

    @Test fun onlyAPhraseWiderThanTheLineLetsGoOfItsGlue() {
        val glued = "Busy. Last updated 2 hours ago. Try again."
        assertEquals(glued, keepPhrasesThatFit(glued) { true })
        assertEquals("Busy. Last updated 2 hours ago. Try again.", keepPhrasesThatFit(glued) { it.length < 20 })
    }

    /** At a width the phrase can't fill, it wraps at its spaces: never inside a word. */
    @Test fun aPhraseTooWideForTheLineNeverBreaksInsideAWord() {
        val line = "Offline. Last updated 59 minutes ago"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(200.dp)) { MetadataText(line, Modifier.testTag("line"), style = MaterialTheme.typography.bodyMedium, wholePhrases = true) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("line").assertTextEquals(line)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("line").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        val shown = (0 until layout.lineCount).flatMap { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it, visibleEnd = true)).trim().split(' ') }
        assertTrue(layout.lineCount >= 2, "control: the phrase does not fit")
        assertEquals(listOf("Offline.", "Last", "updated", "59", "minutes", "ago"), shown)
    }
}
