package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.DarwinForwardProxyAccountConnector
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.SaltSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSURLAuthenticationMethodHTTPBasic
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.NSURLCredentialStorage
import platform.Foundation.NSURLProtectionSpace
import platform.Foundation.NSURLProtectionSpaceHTTPProxy
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.TimeSource

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class DarwinProxyAuthenticationConformanceTest {
    @Test
    fun proxyChallengeFailsClosedWithoutAmbientCredentials() = traceProxyTest { mark ->
        mark("writing ambient credential")
        val protectionSpace = NSURLProtectionSpace(
            proxyHost = PROXY_HOST,
            port = PROXY_PORT.toLong(),
            type = NSURLProtectionSpaceHTTPProxy,
            realm = PROXY_REALM,
            authenticationMethod = NSURLAuthenticationMethodHTTPBasic,
        )
        val credential = NSURLCredential.create(
            user = "ambient-proxy-user",
            password = "ambient-proxy-password",
            persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceForSession,
        )
        val storage = NSURLCredentialStorage.sharedCredentialStorage
        storage.setCredential(credential, protectionSpace)
        storage.setDefaultCredential(credential, protectionSpace)
        assertNotNull(
            storage.defaultCredentialForProtectionSpace(protectionSpace),
            "shared credential storage did not return the ambient proxy credential just written; " +
                "the fixture precondition never held, so the rest of this test proves nothing",
        )

        mark("ambient credential precondition satisfied")
        val observationClient = HttpClient(Darwin) { expectSuccess = false }
        try {
            mark("connector started")
            val result = DarwinForwardProxyAccountConnector(
                proxyHost = PROXY_HOST,
                proxyPort = PROXY_PORT,
                saltSource = SaltSource { "0123456789abcdef0123456789abcdef" },
            ).connect(
                AccountConnectionRequest(
                    serverUrl = "https://proxy-target.example.invalid/account",
                    username = "dulcet-proxy-auth",
                    password = "fixture-password",
                    allowLocalHttp = false,
                ),
            )
            mark("connector returned")
            val failure = assertIs<AccountConnectionResult.Failed>(
                result,
                "connecting through the forward proxy was expected to fail closed, but returned $result",
            )
            assertIs<DomainError.Auth.UnsupportedAuthenticationChallenge>(
                failure.error,
                "expected the proxy challenge to surface as UnsupportedAuthenticationChallenge, " +
                    "observed ${failure.error}",
            )

            mark("fetching proxy wire observation")
            val observation = observationClient.get(
                "http://$PROXY_HOST:$PROXY_PORT/observations/proxy-auth",
            )
            mark("proxy wire observation headers received")
            assertEquals(
                200,
                observation.status.value,
                "Darwin sent ambient proxy credentials below the wire boundary: " +
                    observation.bodyAsText(),
            )
        } finally {
            mark("cleanup started")
            observationClient.close()
            storage.removeCredential(credential, protectionSpace)
            mark("cleanup completed")
        }
    }

    // Keep the wall-clock timeline outside runTest: its timeout cancels the connector, which can
    // return Cancelled and fail a later assertion. That assertion alone loses where time was spent.
    private fun traceProxyTest(block: suspend ((String) -> Unit) -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        val timeline = mutableListOf<String>()
        try {
            runTest {
                block { phase -> timeline += "${started.elapsedNow()}: $phase" }
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Proxy authentication timeline (${started.elapsedNow()} total): " +
                    timeline.joinToString("; "),
                failure,
            )
        }
    }

    private companion object {
        const val PROXY_HOST = "127.0.0.1"
        const val PROXY_PORT = 4543
        const val PROXY_REALM = "dulcet-forward-proxy"
    }
}
