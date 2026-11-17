package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
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

    /**
     * The downloaded glyph has its own 24 dp box before More (AND-03, B07-06), never inline in the supporting line: a
     * downloaded row and one that is not keep their duration and More in one column, because the box is reserved on both.
     */
    @Test fun theDownloadedGlyphSitsInAReservedBoxAndNothingElseMoves() {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                    TrackRow(track("t1"), 1, false, Modifier.testTag("album.track.0"), onUnavailable = {},
                        favouriteTag = "album.track.0.favourite",
                        download = TrackDownload(AndroidDownloadStatus("t1", AndroidDownloadState.Downloaded), null, {})) {}
                    TrackRow(track("t2"), 2, false, Modifier.testTag("album.track.1"), onUnavailable = {},
                        favouriteTag = "album.track.1.favourite", download = TrackDownload(null, {}, {})) {}
                }
            }
        }
        compose.waitForIdle()
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun dp(px: Float) = px / compose.density.density
        val glyph = bounds("album.track.0.downloaded")
        val more0 = bounds("album.track.0.menu"); val more1 = bounds("album.track.1.menu")
        // The condition this test is about: one row has the status, the other has none.
        compose.onAllNodesWithTag("album.track.1.downloaded", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(24f, dp(glyph.width), 0.5f, "the glyph is a 24 dp icon")
        // More's node is its 40 dp surface, centred in the 48 dp target the xs gap is measured to.
        assertEquals(DulcetSpacing.xs.value + 4f, dp(more0.left - glyph.right), 0.5f, "the box sits xs before More")
        assertEquals(more0.left, more1.left, 0.5f, "More moved with the download state")
        val lines = compose.onAllNodes(androidx.compose.ui.test.hasText("Artist · 0:30"), useUnmergedTree = true)
        lines.assertCountEquals(2)
        assertEquals(lines[0].fetchSemanticsNode().boundsInRoot.left, lines[1].fetchSemanticsNode().boundsInRoot.left, 0.5f,
            "the supporting line moved with the download state")
    }

    /** Inside Downloads every row is downloaded, so the glyph says nothing there (B08-16). */
    @Test fun downloadsCanLeaveTheGlyphOut() {
        compose.setContent {
            MaterialTheme {
                TrackRow(track("t1"), 1, false, Modifier.testTag("album.track.0"), onUnavailable = {},
                    favouriteTag = "album.track.0.favourite", showDownloaded = false,
                    download = TrackDownload(AndroidDownloadStatus("t1", AndroidDownloadState.Downloaded), null, {})) {}
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("album.track.0.downloaded", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aTransferUnderWaySaysHowFarItHasGotInsteadOfTheArtist() {
        row(track("t1"), AndroidDownloadStatus("t1", AndroidDownloadState.Downloading, 50, 200), onDownload = null, onRemove = {})
        compose.onNodeWithText("Downloading 25%", substring = true, useUnmergedTree = true).assertExists()
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
