package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The separator follows the width the line really has, and never the font size alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneMetadataTextTest {
    @get:Rule val compose = createComposeRule()

    /** Lays the line out in exactly its one-line width at font scale 2, plus [spare] pixels. */
    private fun shown(line: String, spare: Int): String {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                val style = LocalTextStyle.current
                val measurer = rememberTextMeasurer()
                val width = measurer.measure(line, style, softWrap = false, maxLines = 1).size.width + spare
                Box(Modifier.width(with(LocalDensity.current) { width.toDp() })) { MetadataText(line, Modifier.testTag("line"), style = style) }
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("line").fetchSemanticsNode()
        assertEquals(line, node.config[SemanticsProperties.Text].single().text, "Semantics read the whole line")
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().layoutInput.text.text
    }

    @Test fun aLineThatFitsKeepsItsSeparatorAtTheLargestFont() =
        assertEquals("100 songs · 3 min", shown("100 songs · 3 min", spare = 1))

    @Test fun aLineThatDoesNotFitBreaksBetweenPartsAndDropsTheMark() =
        assertEquals("100 songs\n3 min", shown("100 songs · 3 min", spare = -2))

    @Test fun partsShareALineWhileTheyFit() {
        assertEquals("aa · bb\ncc", breakMetadata("aa · bb · cc") { it.length <= 7 })
        assertEquals("aa\nbb\ncc", breakMetadata("aa · bb · cc") { it.length <= 6 })
    }

    @Test fun theLastAllowedLineKeepsWhatRemainsForTheEllipsis() =
        assertEquals("aa\nbb · cc", breakMetadata("aa · bb · cc", maxLines = 2) { it.length <= 6 })

    @Test fun aCardLineLetsTheYearGoAndNeverShortensTheArtistToKeepIt() {
        assertEquals("Dulcet Fixtures", keepMetadataLead("Dulcet Fixtures · 2026") { it.length <= 16 })
        assertEquals("Dulcet Fixtures · 2026", keepMetadataLead("Dulcet Fixtures · 2026") { it.length <= 22 })
        assertEquals("a · b · c", keepMetadataLead("a · b · c") { true })
        assertEquals("a · b", keepMetadataLead("a · b · c") { it.length <= 5 }, "later parts go last first")
        assertEquals("Dulcet Fixtures", keepMetadataLead("Dulcet Fixtures") { false }, "one part is the ellipsis' job")
        assertEquals("Dulcet Fixtures", keepMetadataLead("Dulcet Fixtures · 2026") { false }, "nothing fits: the artist alone")
    }

    @Test fun aNarrowCardStaysOneLineWithItsWholeArtistAtTheLargestFont() {
        val line = "Dulcet Fixtures · 2026"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                val style = LocalTextStyle.current.copy(fontSize = 12.sp)
                val whole = rememberTextMeasurer().measure(line, style, softWrap = false, maxLines = 1).size.width
                Box(Modifier.width(with(LocalDensity.current) { (whole * 3 / 4).toDp() })) {
                    MetadataText(line, Modifier.testTag("line"), style = style, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, keepLead = true)
                }
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("line").fetchSemanticsNode()
        assertEquals(line, node.config[SemanticsProperties.Text].single().text, "Semantics read the whole line")
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val shown = results.single()
        val text = shown.layoutInput.text.text
        assertEquals(1, shown.lineCount, text)
        assertEquals(false, shown.hasVisualOverflow, text)
        assertEquals("Dulcet Fixtures", text, "the artist whole, the year given way")
    }

    @Test fun oneLineAndOnePartAreLeftAlone() {
        assertEquals("aa · bb", breakMetadata("aa · bb", maxLines = 1) { false })
        assertEquals("aaaa", breakMetadata("aaaa") { false })
    }
}
