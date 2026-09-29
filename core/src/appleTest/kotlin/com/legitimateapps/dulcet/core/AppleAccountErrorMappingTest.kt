package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AppleAccountErrorMappingTest {
    @Test
    fun anAnswerTooLargeToReadHasItsOwnKindAndIsNeverAMalformedServer() {
        val tooLarge = DomainError.Protocol.TooLarge
        assertEquals("responseTooLarge", tooLarge.toAppleErrorPresentation().kind)
        assertEquals(AppleAccountErrorKind.ProtocolTooLarge, tooLarge.toAppleErrorKind())
        // The control: the malformed answers keep the kind the too-large one no longer shares.
        val malformed = DomainError.Protocol.MalformedEnvelope
        assertEquals("malformedEnvelope", malformed.toAppleErrorPresentation().kind)
        assertEquals(AppleAccountErrorKind.ProtocolMalformedEnvelope, malformed.toAppleErrorKind())
        assertNotEquals(malformed.toAppleErrorPresentation().kind, tooLarge.toAppleErrorPresentation().kind)
    }
}
