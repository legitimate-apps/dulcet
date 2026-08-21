package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionContract
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.AuthenticationLocation
import com.legitimateapps.dulcet.core.ConnectedAccount
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.LogSink
import com.legitimateapps.dulcet.core.SaltSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountConnectConformanceTest {
    private val knownSalts = listOf(
        "00112233445566778899aabbccddeeff",
        "102132435465768798a9bacbdcedfe0f",
        "ffeeddccbbaa99887766554433221100",
        "0ffedcba98765432100123456789abcd",
    )

    private fun fixture(password: String = ADMIN_PASSWORD): Fixture {
        val logs = mutableListOf<String>()
        var saltIndex = 0
        val saltSource = SaltSource {
            knownSalts.getOrElse(saltIndex++) {
                error("conformance connector requested more salts than the fixture supplied")
            }
        }
        val connector = AccountConnector(
            saltSource = saltSource,
            logSink = LogSink(logs::add),
        )
        return Fixture(connector, password, logs)
    }

    private suspend fun Fixture.connect(): AccountConnectionResult = connector.connect(
        AccountConnectionRequest(
            serverUrl = conformanceBaseUrl(),
            username = ADMIN_USER,
            password = password,
        )
    )

    private suspend fun Fixture.requireConnected(confId: String): ConnectedAccount {
        val result = connect()
        return assertIs<AccountConnectionResult.Connected>(
            result,
            "$confId: deterministic server preconditions passed, but account.connect production behavior is absent: $result",
        ).account
    }

    @Test
    fun conf01UnauthenticatedExtensionDiscovery() = runTest {
        val connected = fixture().requireConnected("CONF-01")
        val discovery = connected.requests.first()

        assertEquals("getOpenSubsonicExtensions", discovery.endpoint)
        assertEquals("GET", discovery.method)
        assertEquals(AuthenticationLocation.None, discovery.authenticationLocation)
        assertFalse(discovery.redactedUrl.contains("u="))
        assertFalse(discovery.redactedUrl.contains("t="))
        assertFalse(discovery.redactedUrl.contains("s="))
    }

    @Test
    fun conf02ReferenceServerExtensionSet() = runTest {
        val extensions = fixture().requireConnected("CONF-02").capabilities.extensions
        val required = mapOf(
            "transcodeOffset" to setOf(1),
            "formPost" to setOf(1),
            "songLyrics" to setOf(1, 2),
            "indexBasedQueue" to setOf(1),
            "transcoding" to setOf(1),
            "playbackReport" to setOf(1),
        )

        required.forEach { (name, versions) ->
            assertEquals(versions, extensions[name], "CONF-02 required extension drifted: $name")
        }
        assertFalse("sonicSimilarity" in extensions)
        assertFalse("apiKeyAuthentication" in extensions)
    }

    @Test
    fun conf03BaselinePingAuthentication() = runTest {
        val connected = fixture().requireConnected("CONF-03")
        val authenticated = connected.requests.filter {
            it.authenticationLocation != AuthenticationLocation.None
        }

        assertTrue(authenticated.any { it.endpoint == "ping" })
        assertTrue(authenticated.all { it.authenticationLocation == AuthenticationLocation.FormBody })
        assertTrue(authenticated.all { it.saltFingerprint != null })
        assertEquals(authenticated.size, authenticated.map { it.saltFingerprint }.toSet().size)

        val secureSaltSource = AccountConnectionContract.secureSaltSource()
        val generated = List(8) { secureSaltSource.nextSalt() }
        assertTrue(generated.all { salt -> salt.length == 32 && salt.all { it.isLowerCaseHexDigit() } })
        assertEquals(generated.size, generated.toSet().size)
    }

    @Test
    fun conf04VersionCompatibility() = runTest {
        val connected = fixture().requireConnected("CONF-04")

        assertEquals(AccountConnectionContract.protocolVersion, connected.protocolVersion)
        assertTrue(
            connected.requests.all {
                it.requestedProtocolVersion == AccountConnectionContract.protocolVersion
            },
        )
    }

    @Test
    fun conf05OpenSubsonicEnvelopeFields() = runTest {
        val connected = fixture().requireConnected("CONF-05")

        assertTrue(connected.openSubsonic)
        assertEquals("navidrome", connected.serverType.lowercase())
        assertEquals("0.63.2", connected.serverVersion.removePrefix("v").substringBefore(' '))
        assertEquals("1.16.1", connected.protocolVersion)
    }

    @Test
    fun conf06DistinguishesAuthenticationAndTransportFailures() = runTest {
        val badCredentials = fixture(password = "definitely-wrong-password").connect()
        val badCredentialsError = assertIs<AccountConnectionResult.Failed>(badCredentials).error
        assertIs<DomainError.Auth.InvalidCredentials>(badCredentialsError)
        assertFalse(badCredentialsError is DomainError.Transport.Unreachable)

        val unreachable = AccountConnector().connect(
            AccountConnectionRequest(
                serverUrl = "http://127.0.0.1:1",
                username = ADMIN_USER,
                password = ADMIN_PASSWORD,
            ),
        )
        assertIs<DomainError.Transport.Unreachable>(
            assertIs<AccountConnectionResult.Failed>(unreachable).error,
        )

        val unknown = AccountConnectionContract.mapSubsonicError(
            code = 999,
            message = "future server error",
            requestUrl = "https://music.invalid/rest/ping.view?u=canary&t=token&s=salt",
        )
        val unknownError = assertIs<DomainError.Server.Unknown>(unknown)
        assertEquals(999, unknownError.code)
        assertFalse(unknownError.toString().contains("u=canary"))
    }

    @Test
    fun conf07RedactsCredentialsFromDiagnostics() = runTest {
        val fixture = fixture()
        val connected = fixture.requireConnected("CONF-07")
        val diagnosticText = fixture.logs.joinToString("\n") + "\n" + connected.requests.joinToString("\n")
        val issuedSalts = knownSalts.take(
            connected.requests.count { it.authenticationLocation != AuthenticationLocation.None },
        )
        val derivedTokens = issuedSalts.map {
            AccountConnectionContract.saltedToken(ADMIN_PASSWORD, it)
        }

        assertTrue(
            connected.requests.filter { it.authenticationLocation != AuthenticationLocation.None }
                .all { it.method == "POST" && "?<redacted>" in it.redactedUrl },
        )
        assertFalse(diagnosticText.contains(ADMIN_PASSWORD))
        assertFalse(diagnosticText.contains(ADMIN_USER))
        issuedSalts.forEach { assertFalse(diagnosticText.contains(it)) }
        derivedTokens.forEach { assertFalse(diagnosticText.contains(it)) }

        val synthetic = AccountConnectionContract.mapSubsonicError(
            code = 999,
            message = "synthetic",
            requestUrl = "https://music.invalid/rest/ping.view?u=$ADMIN_USER&t=${derivedTokens.first()}&s=${issuedSalts.first()}",
        )
        val renderedError = synthetic.toString()
        assertFalse(renderedError.contains(ADMIN_USER))
        assertFalse(renderedError.contains(derivedTokens.first()))
        assertFalse(renderedError.contains(issuedSalts.first()))
        assertTrue("?<redacted>" in renderedError)

        val signedUrl =
            "https://music.invalid/rest/ping.view?u=$ADMIN_USER&t=${derivedTokens.first()}&s=${issuedSalts.first()}"
        val echoedCredentials =
            "u=$ADMIN_USER&t=${derivedTokens.first()}&s=${issuedSalts.first()}"
        listOf("server echoed $signedUrl", "server echoed $echoedCredentials").forEach { message ->
            listOf(0, 999).forEach { code ->
                val everyRenderedField = AccountConnectionContract.mapSubsonicError(
                    code = code,
                    message = message,
                    requestUrl = signedUrl,
                ).toString()
                assertFalse(everyRenderedField.contains(ADMIN_USER))
                assertFalse(everyRenderedField.contains(derivedTokens.first()))
                assertFalse(everyRenderedField.contains(issuedSalts.first()))
            }
        }
    }

    private fun conformanceBaseUrl(): String =
        requiredEnvironment("DULCET_CONFORMANCE_BASE_URL").also { baseUrl ->
            check(baseUrl == "http://127.0.0.1:4533") {
                "conformance suite is restricted to the disposable loopback server, observed: $baseUrl"
            }
        }

    private data class Fixture(
        val connector: AccountConnector,
        val password: String,
        val logs: MutableList<String>,
    )

    private companion object {
        const val ADMIN_USER = "dulcet-admin"
        const val ADMIN_PASSWORD = "dulcet-ci-canary-password"

        fun Char.isLowerCaseHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
    }
}
