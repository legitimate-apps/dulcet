package com.legitimateapps.dulcet

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverResult
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import com.legitimateapps.dulcet.ui.ArtworkImages
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A Discover cover that failed to read keeps being asked for, and every cover counts with the rest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneDiscoverArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = FakeDiscoverWishlistSource()
    private val session = DiscoverWishlistSession(source)
    private val cover = ByteArrayOutputStream().also {
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun png(width: Int, height: Int) = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** A small picture whose header claims [width] by [height], with the header's checksum made right. */
    private fun claiming(width: Int, height: Int): ByteArray {
        val bytes = png(1, 1)
        fun put(at: Int, value: Int) { for (i in 0 until 4) bytes[at + i] = (value ushr (24 - 8 * i)).toByte() }
        put(16, width); put(20, height)
        put(29, CRC32().apply { update(bytes, 12, 17) }.value.toInt())
        return bytes
    }

    private lateinit var read: DiscoverCover

    private fun read(bytes: ByteArray, pixels: Int = 96, decode: ((ByteArray, Int) -> ImageBitmap?)? = null) {
        source.artworkAnswer = { DiscoverResult.Value(bytes) }
        compose.setContent {
            read = if (decode == null) rememberDiscoverCover(session, "art", pixels) else rememberDiscoverCover(session, "art", pixels, decode)
        }
        compose.waitUntil(15_000) { read.settled }
    }

    @After fun close() = session.close()

    private fun show() {
        compose.setContent { MaterialTheme { DiscoverArtwork(session, "art", "Cover", loadTag = "cover") } }
        compose.waitForIdle()
    }

    @Test fun aFailedReadIsAskedForAgainUntilTheCoverArrives() {
        val before = ArtworkImages.activity().painted
        source.artworkAnswer = {
            if (source.artworkReads.size == 1) DiscoverResult.Failed(DiscoverFailure("server-unavailable", null, true))
            else DiscoverResult.Value(cover)
        }
        show()
        compose.waitUntil(15_000) { source.artworkReads.size == 2 }
        compose.waitForIdle()
        assertEquals(before + 1, ArtworkImages.activity().painted, "the second read's cover is on screen")
    }

    @Test fun anAnswerThatThereIsNoCoverIsNotAskedAgain() {
        show()
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertEquals(listOf("art"), source.artworkReads)
        assertEquals(0, ArtworkImages.activity().waiting, "a cover the server does not have is not still out")
    }

    @Test fun anOversizedPictureDecodesBoundedOnItsLongerSide() {
        read(png(16_000, 200))
        val decoded = assertNotNull(read.bitmap)
        assertTrue(maxOf(decoded.width, decoded.height) <= 96 * 4, "decoded ${decoded.width}x${decoded.height}")
    }

    @Test fun aSquareCoverIsNotDecodedFarLargerThanItsTile() {
        read(png(2_000, 2_000))
        val decoded = assertNotNull(read.bitmap)
        assertTrue(decoded.width in 96 until 192, "decoded ${decoded.width}x${decoded.height}")
    }

    @Test fun aPictureClaimingAnAbsurdSizeIsAMissingCover() {
        val before = ArtworkImages.activity().painted
        read(claiming(40_000, 40_000))
        assertNull(read.bitmap)
        assertEquals(before, ArtworkImages.activity().painted)
        assertEquals(listOf("art"), source.artworkReads, "bytes that cannot show are not read again")
    }

    @Test fun undecodableBytesAreAMissingCover() {
        read(ByteArray(64) { 7 })
        assertNull(read.bitmap)
        assertEquals(listOf("art"), source.artworkReads)
    }

    @Test fun theDecodeRunsOffTheMainThread() {
        var onMain: Boolean? = null
        read(png(8, 8)) { bytes, wanted ->
            onMain = Looper.myLooper() == Looper.getMainLooper()
            ArtworkImages.decodeForDisplay(bytes, wanted)?.asImageBitmap()
        }
        assertFalse(assertNotNull(onMain))
        assertNotNull(read.bitmap)
    }

    @Test fun aTileGoneBeforeTheDecodeFinishesPaintsNothing() {
        val before = ArtworkImages.activity().painted
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        var showing by mutableStateOf(true)
        var cover: DiscoverCover? = null
        source.artworkAnswer = { DiscoverResult.Value(png(8, 8)) }
        compose.setContent {
            if (showing) cover = rememberDiscoverCover(session, "art", 96) { bytes, wanted ->
                entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                ArtworkImages.decodeForDisplay(bytes, wanted)?.asImageBitmap()
            }
        }
        assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
        showing = false
        compose.waitForIdle()
        release.countDown()
        compose.waitForIdle()
        assertNull(assertNotNull(cover).bitmap)
        assertEquals(before, ArtworkImages.activity().painted)
    }
}
