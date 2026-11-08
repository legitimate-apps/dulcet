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
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Round 18 centred the track and queue rows' slots at every height. The other list rows (ranked albums on Recently Added
 * and Most Played, Pinned, Search and Discover media rows, the Wishlist) are the same Material ListItem, which lifts both
 * slots to the row's top once the supporting text wraps. At the largest font one app then showed two rules. Each row here
 * wraps its supporting text, which is the case Material top-aligns, and must keep its cover and More on the row's middle.
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

    private fun assertCentred(slot: String, row: String) {
        val s = bounds(slot); val r = bounds(row)
        assertTrue(abs(s.center.y - r.center.y) <= 1f, "$slot centre ${s.center.y} is not the row's ${r.center.y} (row $r, slot $s)")
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

    @Test fun pinnedRowsCentreTheirSlotsWhenTheArtistWraps() {
        val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {}, playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null },
            playTracksFrom = { _, _, _, _ -> })
        compose.setContent { FontTwo { PinnedRow(account, actions, album("pinned"), 0) {} } }
        compose.waitForIdle()
        assertWrapped("library.pinned.item.0", "library.pinned.item.0.menu")
        assertCentred("library.pinned.item.0.menu", "library.pinned.item.0")
    }

    @Test fun rankedRowsCentreTheirRankAndCoverWhenTheArtistWraps() {
        compose.setContent { FontTwo { RankedAlbumRow(account, album("ranked"), 1, Modifier.testTag("ranked.row")) {} } }
        compose.waitForIdle()
        // Ranked rows have no More: the rank and cover share the leading slot, so the rank's centre is the slot's.
        val rank = compose.onNodeWithText("1", useUnmergedTree = true).getBoundsInRoot().let {
            Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
        }
        val row = bounds("ranked.row")
        assertTrue(row.height > rank.height + 60f, "the ranked row did not wrap: row $row, rank $rank")
        assertTrue(abs(rank.center.y - row.center.y) <= 1f, "rank centre ${rank.center.y} is not the row's ${row.center.y}")
    }

    @Test fun mediaRowsCentreTheirSlotsWhenTheSupportingLineWraps() {
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
        assertCentred("media.cover", "media")
        assertCentred("media.more", "media")
    }

    @Test fun wishlistRowsCentreTheirCoverAndMore() {
        val wish = WishRow("w", DiscoverWish("w", "u", DiscoverTarget(DiscoverKind.Song, "w"), "song",
            WishSnapshot("Short", longArtist, null, "2026"), WishSource("search", null), 1000, 2000, "wanted", null, 3000, null, null,
            source.item("song").availability, "1"), null, false, null)
        source.state = source.state.copy(wishes = listOf(wish))
        source.publish(source.state)
        compose.setContent { FontTwo { PhoneWishlist(wishes, {}, {}) } }
        compose.waitForIdle()
        assertWrapped("wishlist.row.0", "wishlist.row.menu.0")
        assertCentred("wishlist.row.menu.0", "wishlist.row.0")
    }
}
