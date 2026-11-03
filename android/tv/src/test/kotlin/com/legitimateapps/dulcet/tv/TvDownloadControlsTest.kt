package com.legitimateapps.dulcet.tv

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidDownloadState
import com.legitimateapps.dulcet.core.AndroidDownloadStatus
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AudioContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The TV album row's download (spec §14.5): a status on the row — the downloaded marker and a
 * transfer's progress — and the action in the row's long-Select menu, which downloads the track or
 * removes its download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvDownloadControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theRowsDownloadButtonDownloadsATrackThatHasNone() {
        var toggled = 0
        row(null) { toggled++ }
        compose.onAllNodesWithTag("album.track.0.downloaded", useUnmergedTree = true).assertCountEquals(0)
        pressWithRemote("album.track.0.actions.download")
        assertEquals(1, toggled)
    }

    @Test fun aDownloadedRowShowsItsMarkerAndItsButtonRemovesIt() {
        var toggled = 0
        row(AndroidDownloadStatus("t1", AndroidDownloadState.Downloaded, 10, 10)) { toggled++ }
        compose.onNodeWithTag("album.track.0.downloaded", useUnmergedTree = true).assertExists()
        pressWithRemote("album.track.0.actions.download")
        assertEquals(1, toggled)
    }

    @Test fun aTransferUnderWayShowsItsProgressOnTheRow() {
        row(AndroidDownloadStatus("t1", AndroidDownloadState.Downloading, 30, 40)) {}
        compose.onNodeWithText("Downloading 75%", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun aTrackWithNoDownloadAvailableHasNoMenuItem() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvTrackRow(track(), 0, false, onPlay = {}, onUnavailable = {}, onFavourite = {}, download = null, onDownloadToggle = null)
            }
        }
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("album.track.0").assertIsFocused()
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        // No download and no queue: the row opens no menu.
        compose.onAllNodesWithTag("album.track.0.actions.download").assertCountEquals(0)
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
        compose.onNodeWithTag("album.track.0.favourite").assertExists()
    }

    /** The remote's way: focus the row, hold Select, then the centre key on the menu item. */
    @OptIn(ExperimentalTestApi::class)
    private fun pressWithRemote(item: String) {
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("album.track.0").assertIsFocused()
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag(item).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun row(status: AndroidDownloadStatus?, onToggle: () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvTrackRow(track(), 0, false, onPlay = {}, onUnavailable = {}, onFavourite = {},
                    download = status, onDownloadToggle = onToggle)
            }
        }
    }

    private fun track() = AndroidLibraryItem.Track(
        rawId = "t1", title = "Title", albumRawId = "album", albumTitle = "Album", artistName = "Artist",
        artistRawId = null, discNumber = 1, trackNumber = 1, durationMilliseconds = 30_000, sourceContainer = AudioContainer.Flac,
        artworkKey = null, favourite = false, rating = null, playCount = null,
        playability = AndroidLibraryPlayability.Streamable, metadataMissing = false,
    )
}
