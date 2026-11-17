package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every list row places its slots by one rule (CentredSlot): centred at ordinary sizes, and at large text top-aligned
 * 4 dp below the content's top, the Material three-line rule (lead decision D1, B08-06). The other list rows (ranked
 * albums on Recently Added and Most Played, Pinned, Search and Discover media rows, the Wishlist) are the same Material
 * ListItem, which on its own centres a row whose title wraps and lifts one whose supporting text wraps. Each row here
 * wraps its supporting text at the largest font and must put its cover and More at the top, as a track row does.
 * Native graphics, because only real text layout wraps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-port")
class SharedRowCentringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = SearchAccount("centring", "https://music.example.invalid", "u", "p", false)
    private val longArtist = "Several Album Artists With Rather Long Names Together"
    private val source = FakeDiscoverWishlistSource()
    private val wishes = DiscoverWishlistSession(source)
    @After fun close() = wishes.close()

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    /**
     * ListItem's own top padding (8, or 12 on a three-line item), the slot's 4 dp offset and at most 4 dp more for a
     * 40 dp button surface inside its 48 dp target.
     */
    private fun assertAtTop(slot: String, row: String) {
        val s = bounds(slot); val r = bounds(row)
        assertTrue(s.top - r.top in 11f..21f, "$slot sits ${s.top - r.top} dp below its row's top, not at the content's top (row $r, slot $s)")
    }

    /** The condition: the row is taller than a one-line-title, one-line-supporting row would be at this font. */
    private fun assertWrapped(row: String, slot: String) {
        val r = bounds(row); val s = bounds(slot)
        assertTrue(r.height > s.height + 60f, "$row did not wrap: row $r, slot $s")
    }

    @Composable private fun FontTwo(content: @Composable () -> Unit) {
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density, 2f)) {
            MaterialTheme { Column(Modifier.requiredWidth(411.dp)) { content() } }
        }
    }

    private fun album(id: String) = AndroidLibraryItem.Album(rawId = id, title = "Short", artistName = longArtist, artistRawId = null,
        year = 2026, genre = null, durationMilliseconds = null, songCount = null, artworkKey = null, favourite = null, rating = null,
        playCount = 3, detailComplete = true)

    @Test fun pinnedRowsTopAlignTheirSlotsWhenTheArtistWraps() {
        val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {}, playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null },
            playTracksFrom = { _, _, _, _ -> })
        compose.setContent { FontTwo { PinnedRow(account, actions, album("pinned"), 0) {} } }
        compose.waitForIdle()
        assertWrapped("library.pinned.item.0", "library.pinned.item.0.menu")
        assertAtTop("library.pinned.item.0.menu", "library.pinned.item.0")
    }

    @Test fun rankedRowsTopAlignTheirRankAndCoverWhenTheArtistWraps() {
        compose.setContent { FontTwo { RankedAlbumRow(account, album("ranked"), 1, Modifier.testTag("ranked.row")) {} } }
        compose.waitForIdle()
        // Ranked rows have no More: the rank is centred on the 56 dp cover beside it, so its centre is the cover's.
        val rank = compose.onNodeWithText("1", useUnmergedTree = true).getBoundsInRoot().let {
            Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
        }
        val row = bounds("ranked.row")
        assertTrue(row.height > rank.height + 60f, "the ranked row did not wrap: row $row, rank $rank")
        assertTrue(rank.center.y - row.top in 39f..45f, "rank centre ${rank.center.y - row.top} dp below the row's top: the cover is not at the top")
    }

    @Test fun mediaRowsTopAlignTheirSlotsWhenTheSupportingLineWraps() {
        compose.setContent {
            FontTwo {
                PhoneMediaRow("Short", Modifier.testTag("media"),
                    supporting = { MetadataText("Song · $longArtist", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leading = { Box(Modifier.size(48.dp).testTag("media.cover")) },
                    trailing = { IconButton({}, Modifier.testTag("media.more")) { Icon(DulcetIcons.MoreVert, null) } })
            }
        }
        compose.waitForIdle()
        assertWrapped("media", "media.more")
        assertAtTop("media.cover", "media")
        assertAtTop("media.more", "media")
    }

    @Test fun wishlistRowsTopAlignTheirCoverAndMore() {
        val wish = WishRow("w", DiscoverWish("w", "u", DiscoverTarget(DiscoverKind.Song, "w"), "song",
            WishSnapshot("Short", longArtist, null, "2026"), WishSource("search", null), 1000, 2000, "wanted", null, 3000, null, null,
            source.item("song").availability, "1"), null, false, null)
        source.state = source.state.copy(wishes = listOf(wish))
        source.publish(source.state)
        compose.setContent { FontTwo { PhoneWishlist(wishes, {}, {}) } }
        compose.waitForIdle()
        assertWrapped("wishlist.row.0", "wishlist.row.menu.0")
        assertAtTop("wishlist.row.menu.0", "wishlist.row.0")
    }
}
