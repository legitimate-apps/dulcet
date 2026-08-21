package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.AuthenticationLocation
import com.legitimateapps.dulcet.core.SaltSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DarwinURLCacheIsolationConformanceTest {
    @Test
    fun cacheableExtensionProbeIsRefetchedAcrossAccountConnectors() = runTest {
        val root = requiredEnvironment("DULCET_REDIRECT_CONFORMANCE_ROOT")
        val serverUrl = "$root/cacheable-account"

        val first = connect(serverUrl)
        assertTrue(
            first.requests.drop(1).all {
                it.authenticationLocation == AuthenticationLocation.FormBody
            },
        )

        val second = connect(serverUrl)
        assertTrue(
            second.requests.drop(1).all {
                it.authenticationLocation == AuthenticationLocation.Query
            },
            "the second account connector reused the first connector's cached extension response",
        )

        val observationClient = HttpClient(Darwin)
        try {
            val observation = observationClient.get("$root/observations/cacheable-account")
            assertEquals(200, observation.status.value)
            assertEquals(
                "{\"extension_requests\":2}",
                observation.bodyAsText(),
                "both account connectors must reach the extension-probe wire",
            )
        } finally {
            observationClient.close()
        }
    }

    private suspend fun connect(serverUrl: String) =
        assertIs<AccountConnectionResult.Connected>(
            AccountConnector(
                saltSource = SaltSource { "0123456789abcdef0123456789abcdef" },
            ).connect(
                AccountConnectionRequest(
                    serverUrl = serverUrl,
                    username = "dulcet-admin",
                    password = "subsonic-password",
                    allowLocalHttp = true,
                ),
            ),
        ).account
}
