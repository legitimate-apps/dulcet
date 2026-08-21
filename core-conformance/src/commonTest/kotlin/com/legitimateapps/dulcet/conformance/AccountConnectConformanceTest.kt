package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionContract
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.AuthenticationLocation
import com.legitimateapps.dulcet.core.AuthenticationParameter
import com.legitimateapps.dulcet.core.CapabilityFeature
import com.legitimateapps.dulcet.core.ConnectedAccount
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.InvalidServerUrlReason
import com.legitimateapps.dulcet.core.LogSink
import com.legitimateapps.dulcet.core.HostResolver
import com.legitimateapps.dulcet.core.ProtocolVersionLevel
import com.legitimateapps.dulcet.core.RequestTrace
import com.legitimateapps.dulcet.core.RequestObservationBoundary
import com.legitimateapps.dulcet.core.RequestChannelLocation
import com.legitimateapps.dulcet.core.RedirectPolicyDecision
import com.legitimateapps.dulcet.core.RedirectRejectionReason
import com.legitimateapps.dulcet.core.Redactor
import com.legitimateapps.dulcet.core.SaltSource
import com.legitimateapps.dulcet.core.TlsTrustFailure
import com.legitimateapps.dulcet.core.toDiagnosticJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountConnectConformanceTest {
    private val saltedTokenAuthentication = setOf(
        AuthenticationParameter.Username,
        AuthenticationParameter.SaltedToken,
        AuthenticationParameter.Salt,
    )
    private val knownSalts = listOf(
        "00112233445566778899aabbccddeeff",
        "102132435465768798a9bacbdcedfe0f",
        "ffeeddccbbaa99887766554433221100",
        "0ffedcba98765432100123456789abcd",
    )

    private fun fixture(
        password: String = ADMIN_PASSWORD,
        hostResolver: HostResolver = HostResolver { listOf("127.0.0.1") },
    ): Fixture {
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
            hostResolver = hostResolver,
        )
        return Fixture(connector, password, logs)
    }

    private suspend fun Fixture.connect(
        serverUrl: String = conformanceBaseUrl(),
        allowLocalHttp: Boolean = true,
    ): AccountConnectionResult = connector.connect(
        AccountConnectionRequest(
            serverUrl = serverUrl,
            username = ADMIN_USER,
            password = password,
            allowLocalHttp = allowLocalHttp,
        )
    )

    private suspend fun Fixture.requireConnected(
        confId: String,
        serverUrl: String = conformanceBaseUrl(),
    ): ConnectedAccount {
        val result = connect(serverUrl)
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
        assertEquals(RequestObservationBoundary.KtorSendingRequest, discovery.observationBoundary)
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

        assertEquals(listOf("ping", "getUser"), authenticated.map { it.endpoint })
        assertTrue(authenticated.size > 1)
        assertTrue(authenticated.any { it.endpoint == "ping" })
        assertTrue(authenticated.all { it.authenticationLocation == AuthenticationLocation.FormBody })
        assertTrue(authenticated.all { it.saltFingerprint != null })
        assertEquals(authenticated.size, authenticated.map { it.saltFingerprint }.toSet().size)

        val secureSaltSource = AccountConnectionContract.secureSaltSource()
        val generated = List(8) { secureSaltSource.nextSalt() }
        assertTrue(generated.all { salt -> salt.length == 32 && salt.all { it.isLowerCaseHexDigit() } })
        assertEquals(generated.size, generated.toSet().size)

        val badCredentials = fixture(password = "definitely-wrong-password").connect()
        val badCredentialsError = assertIs<AccountConnectionResult.Failed>(badCredentials).error
        assertIs<DomainError.Auth.InvalidCredentials>(badCredentialsError)
        assertFalse(badCredentialsError is DomainError.Transport.Unreachable)
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
        val compatibilityCases = listOf(
            Triple("1.16.1", "1.16.1", true),
            Triple("1.15.99", "1.16.0", true),
            Triple("1.16.99", "1.16.0", true),
            Triple("1.17.0", "1.16.99", false),
            Triple("2.0.0", "1.99.0", false),
            Triple("malformed", "1.16.1", false),
            Triple("1.16.1", "malformed", false),
        )
        compatibilityCases.forEach { (clientVersion, serverVersion, expected) ->
            assertEquals(
                expected,
                AccountConnectionContract.isCompatibleVersion(clientVersion, serverVersion),
                "CONF-04 client=$clientVersion server=$serverVersion",
            )
        }
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
        val unreachable = AccountConnector().connect(
            AccountConnectionRequest(
                serverUrl = "http://127.0.0.1:1",
                username = ADMIN_USER,
                password = ADMIN_PASSWORD,
                allowLocalHttp = true,
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
        val diagnosticFixture = fixture()
        val redirectRoot = redirectConformanceRoot()
        val connected = diagnosticFixture.requireConnected("CONF-07", "$redirectRoot/observe")
        val diagnosticText =
            diagnosticFixture.logs.joinToString("\n") + "\n" + connected.requests.joinToString("\n")
        val observedCredentials = observedWireCredentials(redirectRoot)
        assertEquals(setOf("u", "t", "s"), observedCredentials.keys)
        assertTrue(observedCredentials.values.flatten().all(String::isNotEmpty))
        val observedUsername = observedCredentials.getValue("u").first()
        val observedToken = observedCredentials.getValue("t").first()
        val observedSalt = observedCredentials.getValue("s").first()

        assertTrue(
            connected.requests.all {
                it.observationBoundary == RequestObservationBoundary.KtorSendingRequest
            },
        )
        connected.requests.forEach(::assertEveryRequestChannelAccountedFor)
        assertTrue(
            connected.requests.filter { it.authenticationLocation != AuthenticationLocation.None }
                .all {
                    it.method == "POST" &&
                        '?' !in it.redactedUrl &&
                        it.queryAuthenticationParameters.isEmpty() &&
                        it.formAuthenticationParameters == saltedTokenAuthentication
                },
        )
        assertFalse(diagnosticText.contains(ADMIN_PASSWORD))
        observedCredentials.values.flatten().forEach { observed ->
            assertFalse(diagnosticText.contains(observed))
        }

        val synthetic = AccountConnectionContract.mapSubsonicError(
            code = 999,
            message = "synthetic",
            requestUrl = "https://music.invalid/rest/ping.view?u=$observedUsername&t=$observedToken&s=$observedSalt",
        )
        val renderedError = synthetic.toString()
        observedCredentials.values.flatten().forEach { observed ->
            assertFalse(renderedError.contains(observed))
        }
        assertTrue("?<redacted>" in renderedError)

        val signedUrl =
            "https://music.invalid/rest/ping.view?u=$observedUsername&t=$observedToken&s=$observedSalt"
        val echoedCredentials =
            "u=$observedUsername&t=$observedToken&s=$observedSalt"
        listOf("server echoed $signedUrl", "server echoed $echoedCredentials").forEach { message ->
            listOf(0, 999).forEach { code ->
                val everyRenderedField = AccountConnectionContract.mapSubsonicError(
                    code = code,
                    message = message,
                    requestUrl = signedUrl,
                ).toString()
                observedCredentials.values.flatten().forEach { observed ->
                    assertFalse(everyRenderedField.contains(observed))
                }
            }
        }

        assertTrue(connected.requests.first().queryAuthenticationParameters.isEmpty())
        assertTrue(connected.requests.first().formAuthenticationParameters.isEmpty())
        assertTrue(
            connected.requests.drop(1).all {
                it.queryAuthenticationParameters.isEmpty() &&
                    it.formAuthenticationParameters == saltedTokenAuthentication
            },
        )
    }

    @Test
    fun domainErrorCannotRenderBareServerControlledText() {
        val bareCredential = "opaque-bare-server-credential"
        val userInfoCredential = "userinfo-server-credential"
        val mappedError = AccountConnectionContract.mapSubsonicError(
            code = 999,
            message = "authentication rejected: $bareCredential",
            requestUrl = "https://attacker:$userInfoCredential@music.invalid/rest/ping.view",
        )
        val everySubtype = listOf<DomainError>(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.MalformedHost),
            DomainError.Transport.Unreachable,
            DomainError.Transport.Timeout,
            DomainError.Transport.Cancelled,
            DomainError.Security.TlsUntrusted(TlsTrustFailure.CertificateChain),
            DomainError.Security.LocalExceptionViolated,
            DomainError.Security.RedirectRejected(RedirectRejectionReason.InvalidLocation),
            DomainError.Protocol.MalformedEnvelope,
            DomainError.Protocol.Incompatible(
                clientVersion = ProtocolVersionLevel(1, 16),
                serverVersion = ProtocolVersionLevel(2, 0),
            ),
            DomainError.Protocol.NotASubsonicServer,
            DomainError.Server.Known(0),
            mappedError,
            DomainError.Auth.InvalidCredentials,
            DomainError.Auth.TokenAuthUnsupported,
            DomainError.Auth.Forbidden,
            DomainError.Auth.RedirectCredentialLoss(),
            DomainError.CapabilityUnsupported(CapabilityFeature.AccountConnect),
        )
        val logging = mutableListOf<String>()
        val logSink = LogSink(logging::add)
        val everyRenderingPath = everySubtype.flatMap { error ->
            logSink.write(error.toString())
            listOf(
                error.toString(),
                AccountConnectionResult.Failed(error).toString(),
                IllegalStateException(error.toString()).toString(),
                error.toDiagnosticJson(),
            )
        } + logging

        everyRenderingPath.forEach { rendered ->
            assertFalse(rendered.contains(bareCredential))
            assertFalse(rendered.contains(userInfoCredential))
        }
    }

    @Test
    fun structuralUrlRendererIsTotalForMalformedInputs() {
        val malformed = listOf(
            "empty" to "",
            "delimiter-only" to "://",
            "colon-only" to ":",
            "empty-authority" to "https://",
            "missing-authority" to "https:///missing-authority",
            "opening-bracket-only" to "https://[",
            "empty-ipv6-brackets" to "https://[]/rest/ping.view?u=credential-canary",
            "unclosed-ipv6-bracket" to "https://[::1",
            "nonnumeric-port" to
                "https://music.invalid:not-a-port/rest/ping.view?u=credential-canary",
            "out-of-range-port" to
                "https://music.invalid:70000/rest/ping.view?u=credential-canary",
            "authority-whitespace" to
                "https://music invalid/rest/ping.view?u=credential-canary",
            "leading-control-character" to
                "\u0000https://music.invalid/rest/ping.view?u=credential-canary",
        )
        val unsafeCases = malformed.mapNotNull { (caseName, input) ->
            val outcome = runCatching { Redactor.redactUrl(input) }
            val rendered = outcome.getOrNull()
            when {
                outcome.isFailure -> "$caseName:threw"
                rendered != "<unrenderable-url>" -> "$caseName:rendered"
                rendered?.contains("credential-canary") == true -> "$caseName:leaked"
                else -> null
            }
        }
        assertEquals(emptyList(), unsafeCases, "every malformed case must use the safe rendering")

        val alphabet = charArrayOf(':', '/', '?', '#', '@', '[', ']', '%', 'a', '0', ' ', '\u0000')
        val generated = buildList {
            add("")
            alphabet.forEach { first ->
                add(first.toString())
                alphabet.forEach { second ->
                    add("$first$second")
                    alphabet.forEach { third -> add("$first$second$third") }
                }
            }
        }
        generated.forEach { input ->
            assertTrue(Redactor.redactUrl(input).isNotEmpty())
        }

        assertEquals(
            "https://music.invalid:443/rest/ping.view?<redacted>",
            Redactor.redactUrl(
                "https://music.invalid/rest/ping.view?u=credential-canary#credential-fragment",
            ),
        )
    }

    @Test
    fun localHttpRequiresExplicitConsent() = runTest {
        val result = fixture().connect(allowLocalHttp = false)
        val error = assertIs<AccountConnectionResult.Failed>(
            result,
            "plaintext local HTTP connected without an explicit per-server opt-in: $result",
        ).error

        assertIs<DomainError.Security.LocalExceptionViolated>(error)

        val consented = assertIs<AccountConnectionResult.Connected>(fixture().connect()).account
        assertTrue(consented.allowsLocalHttp)

        val schemeLess = assertIs<AccountConnectionResult.Connected>(
            fixture().connect(serverUrl = "127.0.0.1:4533"),
        ).account
        assertEquals("http://127.0.0.1:4533", schemeLess.normalizedBaseUrl)

        val localName = assertIs<AccountConnectionResult.Connected>(
            fixture(
                hostResolver = HostResolver { host ->
                    assertEquals("library.local", host)
                    listOf("127.0.0.1")
                },
            ).connect(serverUrl = "http://library.local:4533"),
        ).account
        assertTrue(localName.requests.all { "127.0.0.1" in it.redactedUrl })
        assertTrue(localName.requests.none { "library.local" in it.redactedUrl })

        val mixedResolution = fixture(
            hostResolver = HostResolver { listOf("127.0.0.1", "203.0.113.10") },
        ).connect(serverUrl = "http://library.local:4533")
        assertIs<DomainError.Security.LocalExceptionViolated>(
            assertIs<AccountConnectionResult.Failed>(mixedResolution).error,
        )

        var resolutionCount = 0
        val rebound = fixture(
            hostResolver = HostResolver {
                resolutionCount += 1
                if (resolutionCount == 1) listOf("127.0.0.1") else listOf("203.0.113.10")
            },
        ).connect(serverUrl = "http://library.local:4533")
        assertEquals(2, resolutionCount)
        assertIs<DomainError.Security.LocalExceptionViolated>(
            assertIs<AccountConnectionResult.Failed>(rebound).error,
        )

        listOf(
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "::1",
            "fc00::1",
            "fd12:3456:789a::1",
        ).forEach { address ->
            assertTrue(AccountConnectionContract.isPermittedLocalHttpAddress(address), address)
        }
        listOf(
            "8.8.8.8",
            "172.32.0.1",
            "169.254.1.1",
            "fe80::1",
            "2001:db8::1",
            "fc00:not-an-address",
        ).forEach { address ->
            assertFalse(AccountConnectionContract.isPermittedLocalHttpAddress(address), address)
        }
    }

    @Test
    fun tlsTrustFailureProducesTlsUntrusted() = runTest {
        val result = fixture().connect(
            serverUrl = requiredEnvironment("DULCET_UNTRUSTED_TLS_URL").also { url ->
                check(url == "https://127.0.0.1:4542") {
                    "TLS trust fixture is restricted to loopback, observed: $url"
                }
            },
            allowLocalHttp = false,
        )
        val error = assertIs<DomainError.Security.TlsUntrusted>(
            assertIs<AccountConnectionResult.Failed>(result).error,
        )
        assertEquals(TlsTrustFailure.CertificateChain, error.reason)
    }

    @Test
    fun conf08EnforcesRedirectCredentialPolicy() = runTest {
        val redirectRoot = redirectConformanceRoot()
        val sameOrigin = fixture().requireConnected("CONF-08", "$redirectRoot/same")
        sameOrigin.requests.forEach(::assertEveryRequestChannelAccountedFor)
        val sameOriginPairs = sameOrigin.requests.groupBy { it.endpoint }

        assertEquals(
            setOf("getOpenSubsonicExtensions", "ping", "getUser"),
            sameOriginPairs.keys,
        )
        sameOriginPairs.forEach { (endpoint, pair) ->
            assertEquals(2, pair.size, "CONF-08 expected one redirect for $endpoint")
            assertEquals(pair[0].method, pair[1].method)
            assertEquals(pair[0].authenticationLocation, pair[1].authenticationLocation)
            assertEquals(pair[0].queryAuthenticationParameters, pair[1].queryAuthenticationParameters)
            assertEquals(pair[0].formAuthenticationParameters, pair[1].formAuthenticationParameters)
            assertEquals(pair[0].saltFingerprint, pair[1].saltFingerprint)
        }
        assertTrue(
            sameOriginPairs.getValue("ping").all {
                it.authenticationLocation == AuthenticationLocation.FormBody &&
                    it.queryAuthenticationParameters.isEmpty() &&
                    it.formAuthenticationParameters == saltedTokenAuthentication
            },
        )

        val crossOrigin = fixture().connect("$redirectRoot/cross-observe")
        val credentialLoss = assertIs<DomainError.Auth.RedirectCredentialLoss>(
            assertIs<AccountConnectionResult.Failed>(crossOrigin).error,
        )
        assertFalse(credentialLoss.redactedUrl.value.contains('?'))

        val redirectLoop = fixture().connect("$redirectRoot/loop")
        val loopRejection = assertIs<DomainError.Security.RedirectRejected>(
            assertIs<AccountConnectionResult.Failed>(redirectLoop).error,
        )
        assertEquals(RedirectRejectionReason.TooManyRedirects, loopRejection.reason)

        assertEquals(
            RedirectPolicyDecision.Reject(RedirectRejectionReason.HttpsDowngrade),
            AccountConnectionContract.redirectDecision(
                currentUrl = "https://127.0.0.1/rest/ping.view",
                targetUrl = "http://127.0.0.1/rest/ping.view",
                redirectsAlreadyFollowed = 0,
            ),
        )
        assertEquals(
            RedirectPolicyDecision.Reject(RedirectRejectionReason.LocalToPublic),
            AccountConnectionContract.redirectDecision(
                currentUrl = "http://127.0.0.1/rest/ping.view",
                targetUrl = "https://music.invalid/rest/ping.view",
                redirectsAlreadyFollowed = 0,
            ),
        )
    }

    private fun assertEveryRequestChannelAccountedFor(trace: RequestTrace) {
        val accountedNames = mapOf(
            RequestChannelLocation.Header to setOf(
                "accept",
                "accept-charset",
                "accept-encoding",
                "accept-language",
                "connection",
                "content-length",
                "content-type",
                "host",
                "user-agent",
            ),
            RequestChannelLocation.Query to setOf("c", "f", "p", "s", "t", "u", "username", "v"),
            RequestChannelLocation.FormBody to setOf("c", "f", "p", "s", "t", "u", "username", "v"),
        )
        val unaccounted = trace.channels.filter { channel ->
            channel.name !in accountedNames.getValue(channel.location)
        }
        assertTrue(
            unaccounted.isEmpty(),
            "request added unaccounted header/query/form channels: $unaccounted",
        )
    }

    private suspend fun observedWireCredentials(redirectRoot: String): Map<String, List<String>> {
        val client = HttpClient()
        try {
            val response = client.get("$redirectRoot/observations/observe")
            assertEquals(200, response.status.value, "CONF-07 wire observation endpoint is unavailable")
            val document = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: error("CONF-07 wire observation response is not an object")
            val channels = document["channels"] as? JsonArray
                ?: error("CONF-07 wire observation response has no channels")
            return channels.map { element ->
                val channel = element as? JsonObject
                    ?: error("CONF-07 wire observation channel is not an object")
                val name = (channel["name"] as? JsonPrimitive)?.contentOrNull
                    ?: error("CONF-07 wire observation channel has no name")
                val values = (channel["values"] as? JsonArray)?.map { value ->
                    (value as? JsonPrimitive)?.contentOrNull
                        ?: error("CONF-07 wire observation value is not a string")
                } ?: error("CONF-07 wire observation channel has no values")
                name to values
            }.groupBy({ it.first }, { it.second }).mapValues { (_, grouped) -> grouped.flatten() }
        } finally {
            client.close()
        }
    }

    private fun conformanceBaseUrl(): String =
        requiredEnvironment("DULCET_CONFORMANCE_BASE_URL").also { baseUrl ->
            check(baseUrl == "http://127.0.0.1:4533") {
                "conformance suite is restricted to the disposable loopback server, observed: $baseUrl"
            }
        }

    private fun redirectConformanceRoot(): String =
        requiredEnvironment("DULCET_REDIRECT_CONFORMANCE_ROOT").also { root ->
            check(root == "http://127.0.0.1:4540") {
                "redirect suite is restricted to the disposable loopback server, observed: $root"
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
