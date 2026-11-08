package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.AudioContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At the largest font a track or queue row's "artist · duration" is wider than its line. Broken between parts, the duration
 * took a line of its own and lost its mark, and the row grew by a line; the playing row kept "Dulcet Fixtures · 0:29" only
 * because it has no More to make room for. The line now wraps as one line, and its mark goes down with the duration.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class FoldedRowMetadataTest {
    @get:Rule val compose = createComposeRule()

    private fun laidOut(node: androidx.compose.ui.test.SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Composable private fun FontTwo(content: @Composable () -> Unit) {
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density, 2f)) {
            MaterialTheme { Column(Modifier.requiredWidth(411.dp)) { content() } }
        }
    }

    private val track = AndroidLibraryItem.Track(
        rawId = "t", title = "Thirty One Seconds", albumRawId = "album", albumTitle = "Album", artistName = "Dulcet Fixtures",
        artistRawId = null, discNumber = 1, trackNumber = 1, durationMilliseconds = 31_000, sourceContainer = AudioContainer.Flac,
        artworkKey = null, favourite = false, rating = null, playCount = null,
        playability = AndroidLibraryPlayability.Streamable, metadataMissing = false,
    )

    @Test fun aTrackRowsArtistAndDurationWrapAsOneLineAndKeepTheirMark() {
        compose.setContent {
            FontTwo { TrackRow(track, 1, playing = false, modifier = Modifier, onUnavailable = {}, onFavourite = {}, favouriteTag = "row.favourite",
                artwork = { Box(Modifier.size(56.dp)) }) {} }
        }
        compose.waitForIdle()
        val line = laidOut(compose.onNodeWithText("Dulcet Fixtures · 0:31", useUnmergedTree = true))
        // The condition this test is about: the whole line does not fit its width at this font.
        assertTrue(line.lineCount > 1, "the line fits on one line, so nothing here wraps: ${line.layoutInput.text}")
        assertEquals("Dulcet Fixtures ·\u00A00:31", line.layoutInput.text.text)
        // The artist fits its line and the whole line does not: the mark goes down with the duration, never left at a line's end.
        val text = line.layoutInput.text.text
        assertEquals("Dulcet Fixtures", text.substring(0, line.getLineEnd(0)).trim())
        assertEquals("·\u00A00:31", text.substring(line.getLineStart(line.lineCount - 1)).trim())
    }

    @Test fun aQueueRowsLineWrapsAsOneLineToo() {
        compose.setContent {
            FontTwo {
                Box(Modifier.requiredWidth(180.dp)) {
                    QueueTrackSupporting(AndroidQueueEntry("q", AndroidTrack("p", "t", "Title", artist = "Dulcet Fixtures", durationMilliseconds = 29_000)),
                        Modifier.testTag("line"))
                }
            }
        }
        compose.waitForIdle()
        val line = laidOut(compose.onNodeWithTag("line"))
        assertTrue(line.lineCount > 1, "the line fits on one line, so nothing here wraps: ${line.layoutInput.text}")
        assertEquals("Dulcet Fixtures ·\u00A00:29", line.layoutInput.text.text)
    }

    /** A long artist keeps the duration on its own last line instead of adding one: two lines where breaking took three. */
    @Test fun aShortLastPartJoinsTheLineItFitsOn() {
        compose.setContent {
            FontTwo {
                // Just narrower than the artist alone, so the artist itself takes two lines.
                val measurer = rememberTextMeasurer()
                val width = measurer.measure("Several Album Artists", androidx.compose.material3.LocalTextStyle.current, softWrap = false, maxLines = 1).size.width - 2
                Box(Modifier.requiredWidth(with(LocalDensity.current) { width.toDp() })) {
                    QueueTrackSupporting(AndroidQueueEntry("q", AndroidTrack("p", "t", "Title", artist = "Several Album Artists", durationMilliseconds = 29_000)),
                        Modifier.testTag("line"))
                }
            }
        }
        compose.waitForIdle()
        val line = laidOut(compose.onNodeWithTag("line"))
        assertEquals(2, line.lineCount, "laid out as ${line.layoutInput.text}")
        assertTrue(line.layoutInput.text.text.endsWith("·\u00A00:29"))
        for (n in 0 until line.lineCount) assertTrue(!line.layoutInput.text.text.substring(line.getLineStart(n), line.getLineEnd(n)).trim().endsWith("·"),
            "line $n ends on a bare mark")
        assertTrue(!line.isLineEllipsized(line.lineCount - 1), "nothing is cut")
    }

    /**
     * A Search row's "type · artist" follows the same rule (round 20, A22 at font 2 read "Song" above "Dulcet Fixtures" with
     * no mark): the line wraps as one, and the mark goes down with the whole artist, never splitting it as "Song · Dulcet".
     */
    @Test fun aSearchRowsKindAndArtistWrapAsOneLineAndKeepTheirMark() {
        val result = com.legitimateapps.dulcet.core.SearchResultItem(com.legitimateapps.dulcet.core.ProviderItemId("provider::fold", "song::fold"),
            com.legitimateapps.dulcet.core.SearchResultType.Track, "Thirty One Seconds",
            credits = listOf(com.legitimateapps.dulcet.core.Credit(com.legitimateapps.dulcet.core.CreditRole.Artist, "Dulcet Fixtures", null)),
            albumTitle = null, year = null, duration = null, discNumber = null, trackNumber = null, sourceContainer = null,
            mediaSourceId = null, artworkKey = null)
        compose.setContent {
            FontTwo { MobileSearchResult(result, 0, com.legitimateapps.dulcet.search.SearchAccount("provider:fold", "http://127.0.0.1:9", "u", "p", true),
                deviceOnly = false, unavailableOffline = false, onActivate = {}, onPlay = {}) }
        }
        compose.waitForIdle()
        val line = laidOut(compose.onNodeWithText("Song · Dulcet Fixtures", useUnmergedTree = true))
        val text = line.layoutInput.text.text
        assertTrue(line.lineCount > 1, "the line fits on one line, so nothing here wraps: $text")
        assertEquals("Song", text.substring(0, line.getLineEnd(0)).trim(), "laid out as $text")
        assertEquals("·\u00A0Dulcet\u00A0Fixtures", text.substring(line.getLineStart(line.lineCount - 1)).trim(), "laid out as $text")
    }
}
