package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AndroidForwardProxyAccountConnector
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.SaltSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.URL
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AndroidProxyAuthenticationConformanceTest {
    @Test
    fun conf10cProxyChallengeRejectsAmbientCredentials() = runTest {
        val previousAuthenticator = Authenticator.getDefault()
        val ambientAuthenticator = RecordingProxyAuthenticator()
        Authenticator.setDefault(ambientAuthenticator)

        val observationClient = HttpClient(CIO) { expectSuccess = false }
        try {
            val ambientCredential = Authenticator.requestPasswordAuthentication(
                PROXY_HOST,
                InetAddress.getByName(PROXY_HOST),
                PROXY_PORT,
                "http",
                PROXY_REALM,
                "basic",
                URL("http://$PROXY_HOST:$PROXY_PORT"),
                Authenticator.RequestorType.PROXY,
            )
            assertNotNull(
                ambientCredential,
                "CONF-10c did not establish a retrievable ambient proxy credential",
            )
            assertEquals("ambient-proxy-user", ambientCredential.userName)
            assertEquals(1, ambientAuthenticator.requests)
            val preconditionCalls = ambientAuthenticator.requests

            val result = AndroidForwardProxyAccountConnector(
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
                "CONF-10c expected the 407 challenge to fail closed, observed $result",
            )
            assertIs<DomainError.Auth.UnsupportedAuthenticationChallenge>(
                failure.error,
                "CONF-10c mapped the 407 proxy challenge to ${failure.error}",
            )
            assertEquals(
                preconditionCalls,
                ambientAuthenticator.requests,
                "CONF-10c allowed CIO to consult the ambient proxy authenticator",
            )

            val observation = observationClient.get(
                "http://$PROXY_HOST:$PROXY_PORT/observations/proxy-auth",
            )
            assertEquals(
                200,
                observation.status.value,
                "CONF-10c sent ambient proxy credentials below the wire boundary: " +
                    observation.bodyAsText(),
            )
        } finally {
            observationClient.close()
            Authenticator.setDefault(previousAuthenticator)
        }
    }

    private companion object {
        const val PROXY_HOST = "127.0.0.1"
        const val PROXY_PORT = 4543
        const val PROXY_REALM = "dulcet-forward-proxy"
    }
}

private class RecordingProxyAuthenticator : Authenticator() {
    var requests: Int = 0
        private set

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        if (
            requestorType != RequestorType.PROXY ||
            requestingHost != "127.0.0.1" ||
            requestingPort != 4543
        ) {
            return null
        }
        requests += 1
        return PasswordAuthentication(
            "ambient-proxy-user",
            "ambient-proxy-password".toCharArray(),
        )
    }
}
