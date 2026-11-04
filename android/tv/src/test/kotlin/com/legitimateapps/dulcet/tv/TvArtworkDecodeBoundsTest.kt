package com.legitimateapps.dulcet.tv

import android.graphics.Bitmap
import com.legitimateapps.dulcet.ui.ArtworkImages
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A server chooses the picture it sends. The decode is bounded by the longer side, so a very long, thin
 * picture is sampled down like any other, and one whose header claims an absurd size is never decoded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvArtworkDecodeBoundsTest {
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** The same picture with its header claiming [width] by [height], and the header's checksum made right. */
    private fun claiming(width: Int, height: Int): ByteArray {
        val bytes = png(1, 1)
        fun put(at: Int, value: Int) { for (i in 0 until 4) bytes[at + i] = (value ushr (24 - 8 * i)).toByte() }
        put(16, width); put(20, height)
        val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        put(29, crc)
        return bytes
    }

    @Test fun aLongThinPictureIsSampledDownOnItsLongerSide() {
        val decoded = assertNotNull(ArtworkImages.decodeForDisplay(png(16_000, 200), 160))
        assertTrue(maxOf(decoded.width, decoded.height) <= 160 * 4, "decoded ${decoded.width}x${decoded.height}")
    }

    @Test fun aSquareCoverKeepsTheSizeItAlwaysDid() {
        val decoded = assertNotNull(ArtworkImages.decodeForDisplay(png(1000, 1000), 320))
        assertEquals(500, decoded.width)
    }

    @Test fun anAbsurdHeaderIsRefusedBeforeDecoding() {
        assertNull(ArtworkImages.decodeForDisplay(claiming(60_000, 60_000), 160))
        assertNull(ArtworkImages.decodeForDisplay(claiming(30_000, 30_000), 160), "900 million pixels")
        assertNull(ArtworkImages.decodeForDisplay(byteArrayOf(1, 2, 3), 160), "not a picture")
    }
}
