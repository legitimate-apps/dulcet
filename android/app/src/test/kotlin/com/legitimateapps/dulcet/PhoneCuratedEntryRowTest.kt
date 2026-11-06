package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A curated row has one shape: its height follows its lines and its number stays centred. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneCuratedEntryRowTest {
    @get:Rule val compose = createComposeRule()

    private fun rows() = compose.setContent {
        MaterialTheme {
            Column {
                // Two lines each, with titles of different lengths: a list item's guess told these apart.
                Column(Modifier.testTag("short")) { CuratedEntryRow(19, "Song", { Text("Artist") }, null) {} }
                Column(Modifier.testTag("long")) {
                    CuratedEntryRow(20, "Fixture Song 20 with a somewhat long title", { Text("Dulcet Fixtures") }, {}) {}
                }
                Column(Modifier.testTag("status")) {
                    CuratedEntryRow(21, "Song", { Column { Text("Artist"); Text("In your library") } }, {}) {}
                }
            }
        }
    }

    private fun height(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test fun rowsWithTheSameLinesMatchAndAStatusLineGrowsARow() {
        rows()
        assertTrue(abs((height("short") - height("long")).value) < 1f, "${height("short")} against ${height("long")}")
        assertTrue(height("status") > height("short") + 8.dp, "${height("status")} against ${height("short")}")
    }

    @Test fun theNumberIsCentredOnEveryRow() {
        rows()
        val numbers = compose.onAllNodesWithTag("curated.number", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(3, numbers.size)
        for ((tag, number) in listOf("short", "long", "status").zip(numbers)) {
            val row = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val density = compose.density.density
            val middle = (number.boundsInRoot.top + number.boundsInRoot.bottom) / 2 / density
            val rowMiddle = (row.top.value + row.bottom.value) / 2
            assertTrue(abs(middle - rowMiddle) < 1f, "$tag: number at $middle, row at $rowMiddle")
        }
    }
}
