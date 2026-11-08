package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AudioContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A TV track row and the words around it read as the phone's do, with its state glyphs in fixed columns. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvTrackRowTest {
    @get:Rule val compose = createComposeRule()

    private fun show(block: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) { Column { block() } }
    }

    @Test fun aRankedRowSaysItsRankAheadOfItsCoverAndAnUnrankedOneSaysNone() {
        show {
            TvTrackRow(track("t0"), 0, false, onPlay = {}, onUnavailable = {}, tagPrefix = "artist.topSongs", rank = 1)
            TvTrackRow(track("t1"), 1, false, onPlay = {}, onUnavailable = {})
        }
        compose.waitForIdle()
        val rank = compose.onNodeWithTag("artist.topSongs.0.rank", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("1", rank.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text })
        val row = compose.onNodeWithTag("artist.topSongs.0", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the rank sits inside its row", rank.boundsInRoot.left >= row.boundsInRoot.left && rank.boundsInRoot.right <= row.boundsInRoot.right)
        compose.onNodeWithTag("album.track.1.rank", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anAlbumsOwnArtistIsNotRepeatedAndAGuestAndTheDurationStillRead() {
        show {
            TvTrackRow(track("t0", artist = "Page Artist"), 0, false, onPlay = {}, onUnavailable = {}, pageArtist = "Page Artist")
            TvTrackRow(track("t1", artist = "Guest"), 1, false, onPlay = {}, onUnavailable = {}, pageArtist = "Page Artist")
        }
        compose.waitForIdle()
        val own = rowText("album.track.0")
        assertTrue(own, "0:30" in own)
        assertTrue(own, "Page Artist" !in own)
        val guest = rowText("album.track.1")
        assertTrue(guest, "Guest · 0:30" in guest)
    }

    @Test fun anOfflineReasonKeepsTheDurationAndStandsWhereTheArtistStood() {
        show {
            TvTrackRow(track("t0", artist = "Someone", playability = AndroidLibraryPlayability.UnavailableOffline),
                0, false, onPlay = {}, onUnavailable = {})
        }
        compose.waitForIdle()
        val line = rowText("album.track.0")
        assertTrue(line, "· 0:30" in line)
        assertTrue(line, "Someone" !in line)
    }

    @Test fun theStateGlyphHoldsOneColumnWhetherOrNotTheRowIsAFavourite() {
        show {
            TvTrackRow(track("t0", favourite = false), 0, true, onPlay = {}, onUnavailable = {})
            TvTrackRow(track("t1", favourite = true), 1, true, onPlay = {}, onUnavailable = {})
        }
        compose.waitForIdle()
        val plain = compose.onNodeWithTag("album.track.0.playing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val favourite = compose.onNodeWithTag("album.track.1.playing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(plain.left, favourite.left, 0.5f)
    }

    @Test fun aLongRunningTimeReadsAsThePhonesDoes() {
        val read = mutableMapOf<Long, String?>()
        show { listOf(0L, 30_000L, 300_000L, 3_900_000L).forEach { read[it] = tvLongDuration(it); Text(read[it].orEmpty()) } }
        compose.waitForIdle()
        assertNull(read[0L])
        assertEquals("1 min", read[30_000L])
        assertEquals("5 min", read[300_000L])
        assertEquals("1 hr 5 min", read[3_900_000L])
    }

    @Test fun mostPlayedAndPinnedCaptionsSayWhatThePhoneSays() {
        var ranked: String? = null
        var pinned: String? = null
        show {
            val album = album()
            ranked = tvRankedCaption(album)
            pinned = tvPinnedCaption(album)
        }
        compose.waitForIdle()
        assertEquals("Artist · 3 plays", ranked)
        assertEquals("Album · Artist", pinned)
    }

    @Test fun myLibrarySaysNothingIsSavedOnlyWhenAFailedSyncLeftNothing() {
        fun publication(state: com.legitimateapps.dulcet.library.MyLibrarySyncState, albums: Int = 0) = com.legitimateapps.dulcet.library.MyLibraryPublication(
            com.legitimateapps.dulcet.library.MyLibraryAvailability.Unknown, state, null, null,
            List(albums) { com.legitimateapps.dulcet.library.MyLibraryAlbum("a", "A", null, null, 0, null, -1, true, 0, false) }, emptyList(), emptyList())
        assertTrue(publication(com.legitimateapps.dulcet.library.MyLibrarySyncState.Failed).tvHasNothingSaved())
        assertTrue(!publication(com.legitimateapps.dulcet.library.MyLibrarySyncState.Failed, albums = 1).tvHasNothingSaved())
        assertTrue(!publication(com.legitimateapps.dulcet.library.MyLibrarySyncState.Current).tvHasNothingSaved())
    }

    private fun rowText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString(" | ") { it.text }

    private fun album() = AndroidLibraryItem.Album(
        rawId = "a", title = "Album title", artistName = "Artist", artistRawId = null, year = 2020, genre = null,
        durationMilliseconds = null, songCount = 3, artworkKey = null, favourite = false, rating = null, playCount = 3,
        detailComplete = false,
    )

    private fun track(rawId: String, artist: String? = "Artist", favourite: Boolean = false,
                      playability: AndroidLibraryPlayability = AndroidLibraryPlayability.Streamable) = AndroidLibraryItem.Track(
        rawId = rawId, title = "Title", albumRawId = "album", albumTitle = "Album", artistName = artist,
        artistRawId = null, discNumber = 1, trackNumber = 1, durationMilliseconds = 30_000, sourceContainer = AudioContainer.Flac,
        artworkKey = null, favourite = favourite, rating = null, playCount = null,
        playability = playability, metadataMissing = false,
    )
}
