package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.DarwinForwardProxyAccountConnector
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.SaltSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
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

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class DarwinProxyAuthenticationConformanceTest {
    @Test
    fun proxyChallengeFailsClosedWithoutAmbientCredentials() = runTest {
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

        // Bound this observation client explicitly. Without an HttpTimeout it inherits
        // NSURLSession's 60-second timeoutIntervalForRequest, which EQUALS runTest's default
        // 60-second bound -- so a hung observation cannot produce a timeout, only
        // `UncompletedCoroutinesError: After waiting for 1m`, which names the test scope and not
        // the request. OBSERVED 2026-09-09 in apple-ci on iosSimulatorArm64.
        //
        // 10s is 46x the slowest healthy proxy-auth phase measured in CI job logs (217ms, n=29)
        // and 6x below the runTest bound, so it cannot fire spuriously and a real hang is
        // reported as a request timeout. This is a test-only observation client; no product
        // timeout changes.
        val observationClient = HttpClient(Darwin) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = OBSERVATION_TIMEOUT_MILLIS
                socketTimeoutMillis = OBSERVATION_TIMEOUT_MILLIS
            }
        }
        try {
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
            val failure = assertIs<AccountConnectionResult.Failed>(
                result,
                "connecting through the forward proxy was expected to fail closed, but returned $result",
            )
            assertIs<DomainError.Auth.UnsupportedAuthenticationChallenge>(
                failure.error,
                "expected the proxy challenge to surface as UnsupportedAuthenticationChallenge, " +
                    "observed ${failure.error}",
            )

            val observation = observationClient.get(
                "http://$PROXY_HOST:$PROXY_PORT/observations/proxy-auth",
            )
            assertEquals(
                200,
                observation.status.value,
                "Darwin sent ambient proxy credentials below the wire boundary: " +
                    observation.bodyAsText(),
            )
        } finally {
            observationClient.close()
            storage.removeCredential(credential, protectionSpace)
        }
    }

    private companion object {
        const val PROXY_HOST = "127.0.0.1"
        const val PROXY_PORT = 4543
        const val OBSERVATION_TIMEOUT_MILLIS: Long = 10_000
        const val PROXY_REALM = "dulcet-forward-proxy"
    }
}
