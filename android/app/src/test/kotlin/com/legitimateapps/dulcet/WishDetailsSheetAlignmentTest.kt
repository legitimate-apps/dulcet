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

/** Native graphics: at the default's tiny text widths, a short label is centred inside its button's minimum width. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class WishDetailsSheetAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theSheetsActionsStartOnItsTextEdge() {
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
            val edge = left("A wished song")
            for (action in listOf("Refresh status", "Remove wish")) {
                val off = left(action) - edge
                assertTrue(abs(off.value) <= 1f, "$action starts $off from the sheet's text")
            }
        } finally { session.close() }
    }
}
