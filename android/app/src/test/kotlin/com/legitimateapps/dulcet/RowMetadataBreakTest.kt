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
 * At the largest font a track, queue or search row's "artist · duration" is wider than its line. The row breaks between
 * parts and drops the mark at the break, so every line starts and ends with a part: never a leading "· 0:29" (AND-02,
 * B08-05, which the earlier prose fold produced) and never a trailing "Dulcet Fixtures ·".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class RowMetadataBreakTest {
    @get:Rule val compose = createComposeRule()

    private fun laidOut(node: androidx.compose.ui.test.SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun TextLayoutResult.lines(): List<String> =
        (0 until lineCount).map { layoutInput.text.text.substring(getLineStart(it), getLineEnd(it)).trim() }

    private fun assertNoBareMark(line: TextLayoutResult) {
        line.lines().forEachIndexed { n, text ->
            assertTrue(!text.startsWith("·") && !text.endsWith("·"), "line $n is \"$text\": it opens or ends on a bare mark")
        }
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

    @Test fun aTrackRowsArtistAndDurationBreakBetweenPartsAndDropTheMark() {
        compose.setContent {
            // A row 371 dp wide: narrow enough at this font that the artist and duration cannot share a line.
            FontTwo { Box(Modifier.requiredWidth(371.dp)) {
                TrackRow(track, 1, playing = false, modifier = Modifier, onUnavailable = {}, onFavourite = {}, favouriteTag = "row.favourite",
                    artwork = { Box(Modifier.size(56.dp)) }) {} } }
        }
        compose.waitForIdle()
        val line = laidOut(compose.onNodeWithText("Dulcet Fixtures · 0:31", useUnmergedTree = true))
        // The condition this test is about: the whole line does not fit its width at this font.
        assertTrue(line.lineCount > 1, "the line fits on one line, so nothing here wraps: ${line.layoutInput.text}")
        assertEquals(listOf("Dulcet Fixtures", "0:31"), line.lines())
        assertNoBareMark(line)
    }

    @Test fun aQueueRowsLineBreaksTheSameWay() {
        compose.setContent {
            FontTwo {
                // Wide enough for the artist, too narrow for the artist and the duration.
                val measurer = rememberTextMeasurer()
                val width = measurer.measure("Dulcet Fixtures", androidx.compose.material3.LocalTextStyle.current, softWrap = false, maxLines = 1).size.width + 2
                Box(Modifier.requiredWidth(with(LocalDensity.current) { width.toDp() })) {
                    QueueTrackSupporting(AndroidQueueEntry("q", AndroidTrack("p", "t", "Title", artist = "Dulcet Fixtures", durationMilliseconds = 29_000)),
                        Modifier.testTag("line"))
                }
            }
        }
        compose.waitForIdle()
        val line = laidOut(compose.onNodeWithTag("line"))
        assertTrue(line.lineCount > 1, "the line fits on one line, so nothing here wraps: ${line.layoutInput.text}")
        assertEquals(listOf("Dulcet Fixtures", "0:29"), line.lines())
    }

    /**
     * An artist too wide for a line wraps inside itself; breaking after it would spend the second of two lines on the
     * duration's line and cut the artist. The duration rides the artist's last line instead, held to it by its mark.
     */
    @Test fun aShortLastPartJoinsTheWrappedLineBeforeIt() {
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
        assertTrue(line.lines().last().endsWith("0:29"), "the duration is not on the last line: ${line.lines()}")
        assertNoBareMark(line)
        assertTrue(!line.isLineEllipsized(line.lineCount - 1), "nothing is cut")
    }

    /** A Search row's "type · artist" follows the same rule: "Song" above "Dulcet Fixtures", no mark on either line. */
    @Test fun aSearchRowsKindAndArtistBreakBetweenParts() {
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
        assertTrue(line.lineCount > 1, "the line fits on one line, so nothing here wraps: ${line.layoutInput.text}")
        assertEquals(listOf("Song", "Dulcet Fixtures"), line.lines())
    }
}
