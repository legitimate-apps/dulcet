package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidDownloadState
import com.legitimateapps.dulcet.core.AndroidDownloadStatus
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.downloads.AlbumDownloads
import com.legitimateapps.dulcet.downloads.albumDownloadLine
import com.legitimateapps.dulcet.downloads.downloadItem
import com.legitimateapps.dulcet.downloads.downloadLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phone's download controls (spec §14.5): a track row's downloaded marker and progress line, its
 * menu's Download and Remove download, and the album's summary, drawn from the core's statuses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneDownloadControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test fun aDownloadedTrackShowsItsMarkerAndItsMenuRemovesTheDownload() {
        val removed = mutableListOf<String>()
        row(track("t1"), AndroidDownloadStatus("t1", AndroidDownloadState.Downloaded, 100, 100),
            onDownload = { error("a downloaded track is not downloaded again") }, onRemove = { removed += "t1" })
        compose.onNodeWithTag("album.track.0.downloaded", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onAllNodesWithTag("album.track.0.menu.download").assertCountEquals(0)
        compose.onNodeWithTag("album.track.0.menu.removeDownload").performClick()
        assertEquals(listOf("t1"), removed)
    }

    @Test fun aTrackWithNoDownloadHasNoMarkerAndItsMenuDownloadsIt() {
        val requested = mutableListOf<String>()
        row(track("t1"), null, onDownload = { requested += "t1" }, onRemove = { error("nothing to remove") })
        compose.onAllNodesWithTag("album.track.0.downloaded", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onAllNodesWithTag("album.track.0.menu.removeDownload").assertCountEquals(0)
        compose.onNodeWithTag("album.track.0.menu.download").performClick()
        assertEquals(listOf("t1"), requested)
    }

    @Test fun aTransferUnderWaySaysHowFarItHasGotInsteadOfTheArtist() {
        row(track("t1"), AndroidDownloadStatus("t1", AndroidDownloadState.Downloading, 50, 200), onDownload = null, onRemove = {})
        compose.onNodeWithText("Downloading 25%", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("album.track.0.downloaded", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aTrackWhoseOriginalWouldNotPlayHereOffersNoDownload() {
        val stored = track("t1", container = null)
        assertNull(stored.downloadItem())
        row(stored, null, onDownload = stored.downloadItem()?.let { { } }, onRemove = {})
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onAllNodesWithTag("album.track.0.menu.download").assertCountEquals(0)
    }

    @Test fun theAlbumCountsOnlyTracksThatCanBeDownloaded() {
        val tracks = listOf(track("a"), track("b"), track("c"), track("x", container = null))
        val statuses = mapOf(
            "a" to AndroidDownloadStatus("a", AndroidDownloadState.Downloaded),
            "b" to AndroidDownloadStatus("b", AndroidDownloadState.Stale),
            "c" to AndroidDownloadStatus("c", AndroidDownloadState.Queued),
        )
        val album = AlbumDownloads.of(tracks, statuses)
        assertEquals(AlbumDownloads(downloadable = 3, downloaded = 2, pending = 1), album)
        assertEquals("2 of 3 downloaded", resources.albumDownloadLine(album))
        assertEquals("Downloaded", resources.albumDownloadLine(AlbumDownloads.of(tracks.take(2), statuses)))
        assertNull(resources.albumDownloadLine(AlbumDownloads.of(tracks, emptyMap())))
        assertEquals("Download stopped. Reconnect your account.", resources.downloadLine(
            AndroidDownloadStatus("a", AndroidDownloadState.Interrupted, retryNotBeforeWallClock = Long.MAX_VALUE, needsAttention = true)))
        assertEquals("Download will try again",
            resources.downloadLine(AndroidDownloadStatus("a", AndroidDownloadState.Interrupted, retryNotBeforeWallClock = 1)))
    }

    private fun row(track: AndroidLibraryItem.Track, status: AndroidDownloadStatus?, onDownload: (() -> Unit)?, onRemove: () -> Unit) {
        compose.setContent {
            MaterialTheme {
                TrackRow(track, 1, false, Modifier.testTag("album.track.0"), onUnavailable = {},
                    onFavourite = {}, favouriteTag = "album.track.0.favourite", onAddToPlaylist = {},
                    download = TrackDownload(status, onDownload, onRemove)) {}
            }
        }
    }

    private fun track(rawId: String, container: AudioContainer? = AudioContainer.Flac) = AndroidLibraryItem.Track(
        rawId = rawId, title = "Title $rawId", albumRawId = "album", albumTitle = "Album", artistName = "Artist",
        artistRawId = null, discNumber = 1, trackNumber = 1, durationMilliseconds = 30_000, sourceContainer = container,
        artworkKey = null, favourite = false, rating = null, playCount = null,
        playability = AndroidLibraryPlayability.Streamable, metadataMissing = false,
    )
}
