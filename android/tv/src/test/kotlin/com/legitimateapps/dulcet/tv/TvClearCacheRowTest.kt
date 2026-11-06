package com.legitimateapps.dulcet.tv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.OfflineLibraryStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The TV row must never promise what Clear can't do: a playing or queued song's bytes stay after it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvClearCacheRowTest {
    @get:Rule val compose = createComposeRule()

    private var clears = 0

    private fun show(cacheBytes: Long, artworkBytes: Long, clearable: Long) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvClearCacheRow(OfflineLibraryStats(0, cacheBytes, 0, 0, artworkBytes, clearable), clearing = false, cleared = false, clearFailed = false) { clears++ }
            }
        }
    }

    private fun press() {
        compose.onNodeWithTag("offline.cache.clear").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("offline.cache.clear").assertIsFocused()
        compose.onNodeWithTag("offline.cache.clear").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test fun itCountsOnlyWhatClearCanRemove() {
        show(cacheBytes = 5_000_000, artworkBytes = 1_000_000, clearable = 2_000_000)
        compose.onNodeWithText("Frees 2", substring = true, useUnmergedTree = true).assertExists()
        press()
        assertEquals(1, clears)
    }

    @Test fun whatIsLeftAfterAClearIsNotOfferedAgainAndTheRowKeepsTheRemote() {
        show(cacheBytes = 5_000_000, artworkBytes = 1_000_000, clearable = 0)
        compose.onNodeWithText("Nothing to clear", substring = true, useUnmergedTree = true).assertExists()
        press()
        assertEquals(0, clears)
    }

    @Test fun anEmptyCacheSaysOnlyThatThereIsNothingToClear() {
        show(cacheBytes = 0, artworkBytes = 0, clearable = 0)
        compose.onNodeWithText("Nothing to clear", useUnmergedTree = true).assertExists()
    }
}
