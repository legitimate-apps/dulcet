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
        // Android's java.net.Authenticator has no getDefault() -- that is a Java 9 addition the
        // Android API surface does not carry, so reading the previous value is not available here.
        // This test installs the only authenticator it cares about and clears it again, which is
        // the same guarantee for a host-test JVM that starts with none.
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

            // Taxonomy is asserted LAST, after the security properties above, so a divergence in the
            // error label can never mask a failure to reject the challenge. Those are different
            // claims and the security one is the requirement.
            //
            // 🚨 ANDROID DIVERGES FROM DARWIN HERE, and this pins the real behaviour rather than the
            // one we would prefer. Darwin's URLSession delegate sees the challenge and marks the
            // tracker, so it reports Auth.UnsupportedAuthenticationChallenge. Ktor's CIO engine
            // surfaces a 407 as `java.io.IOException: Can not establish tunnel connection` -- OBSERVED
            // -- carrying no status, so `mapAccountConnectionFailure` cannot tell it apart from a
            // proxy that is simply down and lands on Transport.Unreachable.
            //
            // Matching that message to produce the auth error was considered and rejected: the same
            // string covers a genuinely unreachable proxy, so it would report an authentication
            // challenge for a dead one. A wrong error is not better than a coarse one.
            //
            // The security properties asserted above are identical on both platforms; only the label
            // differs. That gap is user-visible -- Android tells someone their server is unreachable
            // when a proxy demanded credentials -- and is recorded as a known divergence rather than
            // silently accepted. Tightening it needs an engine that reports the status, not a
            // message match here.
            assertIs<DomainError.Transport.Unreachable>(
                failure.error,
                "CONF-10c on Android expected the documented CIO granularity, observed ${failure.error}. " +
                    "If this now reports the auth challenge, the engine gained a status signal and " +
                    "this assertion plus the divergence note should be tightened to match Darwin.",
            )
        } finally {
            observationClient.close()
            Authenticator.setDefault(null)
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
