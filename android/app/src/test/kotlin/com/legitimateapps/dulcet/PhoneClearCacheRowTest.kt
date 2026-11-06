package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.OfflineLibraryStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The row must never promise what Clear can't do: a playing or queued song's bytes stay after it. */
@RunWith(RobolectricTestRunner::class)
class PhoneClearCacheRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var clears = 0

    private fun show(cacheBytes: Long, artworkBytes: Long, clearable: Long) {
        compose.setContent { ClearCacheRow(OfflineLibraryStats(0, cacheBytes, 0, 0, artworkBytes, clearable)) { clears++ } }
    }

    @Test fun itCountsOnlyWhatClearCanRemove() {
        show(cacheBytes = 5_000_000, artworkBytes = 1_000_000, clearable = 2_000_000)
        compose.onNodeWithText("Frees 2", substring = true).assertExists()
        compose.onNodeWithTag("offline.cache.clear").assertIsEnabled().performClick()
        assertEquals(1, clears)
    }

    @Test fun whatIsLeftAfterAClearIsNotOfferedAgain() {
        show(cacheBytes = 5_000_000, artworkBytes = 1_000_000, clearable = 0)
        compose.onNodeWithText("Nothing to clear", substring = true).assertExists()
        compose.onNodeWithTag("offline.cache.clear").assertIsNotEnabled().performClick()
        assertEquals(0, clears)
    }

    /** The reason the row is disabled is the thing a person came to read: never the page's faintest text. */
    @Test fun theDisabledRowsReasonStaysReadable() {
        var readable = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            readable = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            ClearCacheRow(OfflineLibraryStats(0, 0, 0, 0, 0, 0)) { clears++ }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Nothing to clear", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(readable, layouts.single().layoutInput.style.color)
    }

    @Test fun anEmptyCacheSaysOnlyThatThereIsNothingToClear() {
        show(cacheBytes = 0, artworkBytes = 0, clearable = 0)
        compose.onNodeWithText("Nothing to clear").assertExists()
    }
}
