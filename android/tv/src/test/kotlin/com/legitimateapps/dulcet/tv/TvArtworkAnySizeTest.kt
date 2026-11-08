package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.ArtworkImages
import com.legitimateapps.dulcet.ui.rememberArtwork
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A cover asked for at one size paints a decode of the same art at another size as soon as any surface
 * has cached one, even when that decode arrives after the request began: the Now Playing cover at
 * 600 px must not sit on the placeholder while the queue rows show the same art at 80 px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvArtworkAnySizeTest {
    @get:Rule val compose = createComposeRule()

    // A closed local port: the request itself never succeeds, so only the cache can paint.
    private fun account(id: String) = SearchAccount(id, "http://127.0.0.1:9", "u", "p", true)

    private fun cover(size: Int): ImageBitmap = ImageBitmap(size, size)

    private fun paintedWidth() = compose.onNodeWithTag("cover").fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()

    @Test fun aSmallerDecodeCachedAfterTheRequestBeganPaintsAtOnce() {
        val account = account("provider:any-size-late")
        compose.mainClock.autoAdvance = false
        compose.setContent { Probe(account, "late", 1200) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("none", paintedWidth(), "Nothing is cached yet")
        store(account, "late", 160, cover(160))
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        assertEquals("160", paintedWidth(), "The row-size decode paints under the requested size")
        store(account, "late", 480, cover(480))
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        assertEquals("480", paintedWidth(), "A larger cached decode replaces a smaller one")
    }

    @Test fun aDecodeCachedBeforeTheRequestPaintsOnTheFirstFrame() {
        val account = account("provider:any-size-early")
        store(account, "early", 160, cover(160))
        compose.mainClock.autoAdvance = false
        compose.setContent { Probe(account, "early", 1200) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("160", paintedWidth())
    }

    @Test fun anotherCoversDecodeNeverPaints() {
        val account = account("provider:any-size-other")
        store(account, "other-art", 160, cover(160))
        compose.mainClock.autoAdvance = false
        compose.setContent { Probe(account, "this-art", 1200) }
        compose.mainClock.advanceTimeByFrame()
        store(account, "also-other", 160, cover(160))
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        assertEquals("none", paintedWidth())
    }

    @Test fun aNewKeyDoesNotKeepThePreviousCover() {
        val account = account("provider:any-size-switch")
        store(account, "first", 160, cover(160))
        var key by mutableStateOf("first")
        compose.mainClock.autoAdvance = false
        compose.setContent { Probe(account, key, 1200) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("160", paintedWidth())
        onUi { key = "second" }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        assertEquals("none", paintedWidth(), "The next song's cover is not the previous song's art")
        store(account, "second", 160, cover(160))
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        assertEquals("160", paintedWidth())
    }

    // Written on the UI thread, the way a loader finishing there would.
    private fun onUi(block: () -> Unit) = compose.runOnUiThread { block(); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }

    private fun store(account: SearchAccount, key: String, pixels: Int, image: ImageBitmap) =
        onUi { ArtworkImages.store(account, key, pixels, image) }

    @androidx.compose.runtime.Composable
    private fun Probe(account: SearchAccount, key: String, pixels: Int) {
        val image = rememberArtwork(account, key, pixels)
        Column { Box(Modifier.testTag("cover").semantics { contentDescription = image?.width?.toString() ?: "none" }) }
    }
}
