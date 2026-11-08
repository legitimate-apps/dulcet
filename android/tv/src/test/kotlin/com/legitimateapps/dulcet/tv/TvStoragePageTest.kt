package com.legitimateapps.dulcet.tv

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Free space is said on the title's line: as a row of its own it pushed Clear past the fold. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvStoragePageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freeSpaceSitsOnTheTitlesLineAndCostsThePageNoHeight() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvStorageScreen() } }
        compose.waitForIdle()
        val title = compose.onNodeWithText("Downloads & storage", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val free = compose.onNodeWithTag("offline.usage", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("free $free must not start below the title $title", free.top < title.bottom)
        assertTrue("free $free must not add height under the title $title", free.bottom <= title.bottom)
        assertTrue("free $free must stand after the title $title", free.left >= title.right)
    }
}
