package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A track row's number and More sit in one place in every row whichever of its lines wraps. Material's ListItem centres
 * them when the title takes two lines and lifts them to the top when the supporting line does, so two rows of one list,
 * each three lines tall, put them in two places. Ours centre them at ordinary sizes and, at large text (lead decision
 * D1, the Material three-line rule), top-align them 4 dp below the content's top in every row. Native graphics,
 * because only real text layout wraps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-port") // Tall enough that all eight rows at the largest font are on screen.
class CentredRowSlotsTest {
    @get:Rule val compose = createComposeRule()

    private val longTitle = "A deliberately long title that has to wrap"
    private val longLine = "Fixture Artist With A Long Name · Another Long Credit · 3:45"

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun assertCentred(slot: String, row: String) {
        val s = bounds(slot); val r = bounds(row)
        assertTrue(abs(s.center.y - r.center.y) <= 1f, "$slot centre ${s.center.y} is not the row's ${r.center.y} (row $r, slot $s)")
    }

    @Composable private fun More(tag: String) {
        IconButton({}, Modifier.testTag(tag)) { Icon(DulcetIcons.MoreVert, null) }
    }

    @Composable private fun Rows(fontScale: Float, content: @Composable () -> Unit) {
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density, fontScale)) {
            MaterialTheme { Column(Modifier.requiredWidth(411.dp)) { content() } }
        }
    }

    /**
     * At the content's top: ListItem pads a two-line row 8 dp and a three-line one 12 dp, so two rows' slots may differ by
     * those 4 dp (the Material rule itself), never by the half-row a centred slot moves.
     */
    private fun assertTopAligned(slot: String, row: String, reference: String) {
        val s = bounds(slot); val r = bounds(row); val ref = bounds(reference); val refRow = bounds(reference.removeSuffix(".more"))
        assertTrue(abs((s.top - r.top) - (ref.top - refRow.top)) <= 4.5f, "$slot sits ${s.top - r.top} below its row's top, $reference ${ref.top - refRow.top}")
        assertTrue(s.center.y < r.center.y - 10f, "$slot is not in the row's upper part (row $r, slot $s)")
    }

    @Test fun atLargeTextARowWhoseSupportingLineWrapsPutsItsMoreWhereARowWhoseTitleWrapsDoes() {
        compose.setContent {
            Rows(2f) {
                TrackRowVisual(longTitle, { Text("3:45") }, 1, Modifier.testTag("titleWraps"),
                    trailing = { More("titleWraps.more") })
                TrackRowVisual("Short", { Text(longLine, maxLines = 2, overflow = TextOverflow.Ellipsis) }, 2,
                    Modifier.testTag("lineWraps"), trailing = { More("lineWraps.more") })
                TrackRowVisual("Short", { Text("3:45") }, 3, Modifier.testTag("control"))
            }
        }
        compose.waitForIdle()
        // The condition this test is about: both rows are taller than two lines, and the second is so by its supporting line.
        val oneLine = bounds("control").height
        assertTrue(bounds("lineWraps").height > oneLine + 20f, "the supporting line did not wrap: ${bounds("lineWraps")} vs $oneLine")
        assertTrue(bounds("titleWraps").height > oneLine + 20f, "the title did not wrap: ${bounds("titleWraps")} vs $oneLine")
        assertTopAligned("lineWraps.more", "lineWraps", "titleWraps.more")
    }

    @Test fun atOrdinaryTextBothRowsCentreTheirMore() {
        compose.setContent {
            Rows(1f) {
                TrackRowVisual(longTitle, { Text("3:45") }, 1, Modifier.testTag("titleWraps"), trailing = { More("titleWraps.more") })
                TrackRowVisual("Short", { Text(longLine, maxLines = 2, overflow = TextOverflow.Ellipsis) }, 2,
                    Modifier.testTag("lineWraps"), trailing = { More("lineWraps.more") })
            }
        }
        compose.waitForIdle()
        assertCentred("titleWraps.more", "titleWraps")
        assertCentred("lineWraps.more", "lineWraps")
    }

    /** A trailing More's glyph ends on the 16 dp keyline, the top bar's action column (AND-03, B04-06). */
    @Test fun aTrailingMoresInkEndsOnTheKeyline() {
        compose.setContent {
            Rows(1f) { TrackRowVisual("Short", { Text("3:45") }, 1, Modifier.testTag("row"), trailing = { More("row.more") }) }
        }
        compose.waitForIdle()
        val box = bounds("row.more"); val row = bounds("row")
        // The 24 dp glyph is centred on the button, so its ink ends 12 dp past the button's centre.
        val inkEnd = box.center.x + DulcetSizes.icon.value / 2
        assertEquals(DulcetSpacing.l.value, row.right - inkEnd, 0.5f, "the glyph's ink ends ${row.right - inkEnd} dp from the edge")
        assertTrue(box.right <= row.right, "the button's surface runs past the row's edge: $box")
    }

    /** Placing the slots moves them only: each row stays exactly as tall as Material's own row with the same content. */
    @Test fun rowsKeepMaterialsHeightAtEveryShape() {
        val shapes = listOf("Short" to "3:45", longTitle to "3:45", "Short" to longLine, longTitle to longLine)
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
                Rows(scale) {
                    shapes.forEachIndexed { index, (title, line) ->
                        TrackRowVisual(title, { Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis) }, index + 1,
                            Modifier.testTag("ours.$index"), trailing = { More("ours.$index.more") })
                        ListItem(headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Text("${index + 1}", Modifier.requiredWidth(28.dp)) },
                            trailingContent = { More("material.$index.more") }, modifier = Modifier.testTag("material.$index"))
                    }
                }
        }
        for (each in listOf(1f, 2f)) {
            scale = each
            compose.waitForIdle()
            shapes.indices.forEach { index ->
                assertEquals(bounds("material.$index").height, bounds("ours.$index").height, "row $index at font $scale")
                if (scale < LARGE_TEXT_SCALE) assertCentred("ours.$index.more", "ours.$index")
                else assertTopAligned("ours.$index.more", "ours.$index", "ours.0.more")
            }
        }
    }
}
