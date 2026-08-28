package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplePlaybackEstimatedLengthTest {
    @Test
    fun estimatedFullResponsePublishesTheCompletedBodyLengthToAVFoundation() {
        val bodyLength = 1_191_316L

        val publishedLength = validateAppleRangeAndTotalLength(
            statusCode = 200,
            contentRange = null,
            declaredContentLength = PlaybackContentLength.Estimated(1_218_703),
            bodyLength = bodyLength,
            requestedRange = PlaybackByteRange(0, 262_143),
        )

        assertEquals(bodyLength, publishedLength)
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
