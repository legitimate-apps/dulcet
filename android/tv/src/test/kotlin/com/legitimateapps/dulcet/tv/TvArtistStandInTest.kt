package com.legitimateapps.dulcet.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A server's stand-in artist portrait is a pale disc; a photograph is not. */
class TvArtistStandInTest {
    private fun argb(alpha: Int, grey: Int) = (alpha shl 24) or (grey shl 16) or (grey shl 8) or grey

    @Test fun aPaleDiscWithTransparentCornersIsTheServersStandIn() {
        // The Navidrome stand-in: a 238 grey disc, a white star, nothing outside the disc.
        val pixels = IntArray(100 * 100) { index ->
            val x = index % 100 - 50
            val y = index / 100 - 50
            when {
                x * x + y * y > 48 * 48 -> argb(0, 0)
                kotlin.math.abs(x) + kotlin.math.abs(y) < 20 -> argb(255, 255)
                else -> argb(255, 238)
            }
        }
        assertTrue(isStandInPortrait(pixels))
    }

    @Test fun aPhotographWithDarkPixelsIsNotReplaced() {
        val pixels = IntArray(100 * 100) { argb(255, 230) }
        // One dark stripe: even a mostly white photograph has dark pixels somewhere the sample lands.
        for (row in 40 until 60) for (column in 0 until 100) pixels[row * 100 + column] = argb(255, 40)
        assertFalse(isStandInPortrait(pixels))
    }

    @Test fun aDarkPortraitPlaceholderOrAnEmptyImageIsNotAStandIn() {
        assertFalse(isStandInPortrait(IntArray(64) { argb(255, 73) }))
        assertFalse(isStandInPortrait(IntArray(64) { argb(0, 0) }), "No opaque pixel says nothing about the picture")
        assertFalse(isStandInPortrait(IntArray(0)))
    }
}
