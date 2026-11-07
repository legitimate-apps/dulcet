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
 * A track row's number and More sit on the row's middle whichever of its lines wraps. Material's ListItem centres them
 * when the title takes two lines and lifts them to the top when the supporting line does, so at the largest font two rows
 * of one list, each three lines tall, put them in two places. Native graphics, because only real text layout wraps.
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

    @Test fun aRowWhoseSupportingLineWrapsCentresItsNumberAndMoreAsARowWhoseTitleWraps() {
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
        assertCentred("titleWraps.more", "titleWraps")
        assertCentred("lineWraps.more", "lineWraps")
    }

    /** Centring moves the slots only: each row stays exactly as tall as Material's own row with the same content. */
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
                assertCentred("ours.$index.more", "ours.$index")
            }
        }
    }
}
