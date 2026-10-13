package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

class AppleAccountErrorMappingTest {
    @Test
    fun savedAccountNegotiationExportsTheReadersTransientRetryFloor() {
        fun floor(error: DomainError): Long = AppleAccountConnectOutcome(null, error, null, null)
            .automaticRetryFloorMilliseconds
        assertEquals(0L, floor(DomainError.Transport.Unreachable))
        assertEquals(0L, floor(DomainError.Transport.Timeout))
        assertEquals(300_000L, floor(DomainError.Server.Busy(400.seconds)))
        assertEquals(-1L, floor(DomainError.Auth.InvalidCredentials))
        assertEquals(-1L, floor(DomainError.Protocol.NotASubsonicServer))
        assertEquals(-1L, floor(DomainError.Security.LocalExceptionViolated))
    }

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
