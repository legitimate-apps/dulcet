package com.legitimateapps.dulcet.core

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PlaybackAppleRequestPreparationTest {
    @Test
    fun preparedAppleRangeRequestsReuseCoreSigningAndRedactTheirRenderer() = runTest {
        val usernameCanary = "apple-loader-user-canary"
        val passwordCanary = "apple-loader-password-canary"
        val salts = ArrayDeque(
            listOf(
                "00112233445566778899aabbccddeeff",
                "ffeeddccbbaa99887766554433221100",
            ),
        )
        val client = AuthenticatedEndpointClient(
            credentials = AuthenticatedEndpointCredentials(
                normalizedBaseUrl = "https://music.example.invalid:443",
                username = usernameCanary,
                password = passwordCanary,
                allowLocalHttp = false,
            ),
            operationName = "test.playback.apple-request",
            saltSource = SaltSource { salts.removeFirst() },
        )
        try {
            val first = client.prepareGetRequest(
                endpoint = "stream",
                parameters = mapOf("id" to "opaque-item"),
                options = AuthenticatedEndpointRequestOptions("bytes=0-262143"),
            )
            val second = client.prepareGetRequest(
                endpoint = "stream",
                parameters = mapOf("id" to "opaque-item"),
                options = AuthenticatedEndpointRequestOptions("bytes=262144-524287"),
            )
            val firstUrl = Url(first.url)
            val secondUrl = Url(second.url)

            assertEquals(usernameCanary, firstUrl.parameters["u"])
            assertEquals("00112233445566778899aabbccddeeff", firstUrl.parameters["s"])
            assertEquals(
                AccountConnectionContract.saltedToken(
                    passwordCanary,
                    "00112233445566778899aabbccddeeff",
                ),
                firstUrl.parameters["t"],
            )
            assertEquals("bytes=0-262143", first.rangeHeader)
            assertEquals("bytes=262144-524287", second.rangeHeader)
            assertNotEquals(firstUrl.parameters["s"], secondUrl.parameters["s"])

            val rendered = first.toString()
            assertEquals("AuthenticatedEndpointPreparedRequest(<redacted>)", rendered)
            listOf(usernameCanary, passwordCanary, firstUrl.parameters["t"]!!).forEach {
                assertFalse(rendered.contains(it))
            }
        } finally {
            client.close()
        }
    }
}
