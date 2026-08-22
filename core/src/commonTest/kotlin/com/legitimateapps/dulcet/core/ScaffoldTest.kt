package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaffoldTest {
    @Test
    fun coreLinksOnTheCurrentTarget() {
        assertEquals("Dulcet", scaffoldName())
    }

    @Test
    fun accountConnectionRequestCannotPrintCredentials() {
        val credentialValues =
            listOf(
                "https://listener:request-secret@music.example.invalid",
                "print-canary-username",
                "print-canary-password",
            )
        val rendered =
            AccountConnectionRequest(
                serverUrl = credentialValues[0],
                username = credentialValues[1],
                password = credentialValues[2],
                allowLocalHttp = false,
            ).toString()

        credentialValues.forEach { assertFalse(rendered.contains(it)) }
        assertTrue(rendered.contains("<redacted>"))
    }
}
