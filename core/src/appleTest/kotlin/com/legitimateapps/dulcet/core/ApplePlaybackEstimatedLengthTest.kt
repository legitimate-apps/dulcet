package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplePlaybackEstimatedLengthTest {
    /**
     * Nothing on the Apple path produces an estimate any more (§28 2026-10-04): every declared
     * length is mapped to Exact. Were one to arrive, a body short of it is still not the resource.
     */
    @Test
    fun aFullResponseShortOfAnEstimateIsNeverPublishedAsTheResource() {
        val publishedLength = validateAppleRangeAndTotalLength(
            statusCode = 200,
            contentRange = null,
            declaredContentLength = PlaybackContentLength.Estimated(1_218_703),
            bodyLength = 1_191_316,
            requestedRange = PlaybackByteRange(0, 262_143),
        )

        assertEquals(null, publishedLength)
    }

    @Test
    fun exactFullResponseShorterThanItsDeclarationStillFailsClosed() {
        val publishedLength = validateAppleRangeAndTotalLength(
            statusCode = 200,
            contentRange = null,
            declaredContentLength = PlaybackContentLength.Exact(1_218_703),
            bodyLength = 1_191_316,
            requestedRange = PlaybackByteRange(0, 262_143),
        )

        assertEquals(null, publishedLength)
    }
}
