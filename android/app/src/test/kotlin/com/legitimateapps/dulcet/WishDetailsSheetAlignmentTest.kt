package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import com.legitimateapps.dulcet.core.DiscoverWish
import com.legitimateapps.dulcet.core.WishRow
import com.legitimateapps.dulcet.core.WishSnapshot
import com.legitimateapps.dulcet.core.WishSource
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sheet's action rows keep a common icon edge, label column and touch height. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class WishDetailsSheetAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actionIconsShareTheSheetEdgeAndLabelsShareTheirColumn() {
        val source = FakeDiscoverWishlistSource()
        val held = source.item("song")
        source.state = source.state.copy(wishes = listOf(WishRow("one", DiscoverWish("one", "u", held.target, "song",
            WishSnapshot("A wished song", held.artist, null, null), WishSource("search", null), 1000, 1000,
            "wanted", null, null, null, null, held.availability, "1"), null, false, null)))
        val session = DiscoverWishlistSession(source)
        try {
            compose.setContent { MaterialTheme { PhoneWishlist(session, {}, {}) } }
            compose.onNodeWithTag("wishlist.row.0").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("wishlist.details").fetchSemanticsNodes().isNotEmpty() }
            fun left(text: String): Dp = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("wishlist.details")), useUnmergedTree = true).getUnclippedBoundsInRoot().left
            fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val edge = left("A wished song")
            for (action in listOf("refresh", "remove")) {
                val row = bounds("wishlist.details.$action")
                val off = bounds("wishlist.details.$action.icon").left - edge
                assertTrue(abs(off.value) <= 1f, "$action icon starts $off from the sheet's text")
                assertTrue(row.bottom - row.top >= DulcetSizes.rowArt, "$action tap target is $row")
            }
            val refreshLabel = left("Refresh status")
            val removeLabel = left("Remove wish")
            assertTrue(refreshLabel > edge, "The action label should follow its icon")
            assertTrue(abs((refreshLabel - removeLabel).value) <= 1f, "The labels start at $refreshLabel and $removeLabel")

        } finally { session.close() }
    }
}
