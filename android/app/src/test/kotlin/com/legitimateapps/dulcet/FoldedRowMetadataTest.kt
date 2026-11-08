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
 * because it has no More to make room for. The line now wraps as one line and keeps its mark.
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
        assertEquals("Dulcet Fixtures · 0:31", line.layoutInput.text.text)
        assertEquals("0:31", line.layoutInput.text.text.substring(line.getLineStart(line.lineCount - 1)).trim())
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
        assertEquals("Dulcet Fixtures · 0:29", line.layoutInput.text.text)
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
        assertTrue(line.layoutInput.text.text.endsWith("· 0:29"))
        assertTrue(!line.isLineEllipsized(line.lineCount - 1), "nothing is cut")
    }
}
