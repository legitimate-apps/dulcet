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
import com.legitimateapps.dulcet.core.UserPermissions
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
    fun successfulEnvelopeFieldsWithWrongJsonTypesAreMalformed() = runTest {
        val redirectRoot = redirectConformanceRoot()
        listOf("open-subsonic", "type", "download-role").forEach { field ->
            assertEquals(
                DomainError.Protocol.MalformedEnvelope,
                assertIs<AccountConnectionResult.Failed>(
                    fixture().connect("$redirectRoot/malformed-envelope-$field"),
                ).error,
                field,
            )
        }
    }

    @Test
    fun openSubsonicStringBooleanRemainsMalformed() = runTest {
        assertEquals(
            DomainError.Protocol.MalformedEnvelope,
            assertIs<AccountConnectionResult.Failed>(
                fixture().connect("${redirectConformanceRoot()}/malformed-envelope-open-subsonic-string"),
            ).error,
        )
    }

    @Test
    fun funkwhale209StringBooleanRolesRemainConnectable() = runTest {
        val connected = assertIs<AccountConnectionResult.Connected>(
            fixture().connect("${redirectConformanceRoot()}/funkwhale-2-0-9"),
            "Funkwhale 2.0.9 string-encoded roles must remain compatible",
        ).account

        assertEquals(
            UserPermissions(
                download = true,
                playlist = true,
                share = false,
                jukebox = true,
                admin = false,
            ),
            connected.capabilities.permissions,
        )
    }

    @Test
    fun knownStringBooleanSpellingsAreCaseInsensitive() = runTest {
        val connected = assertIs<AccountConnectionResult.Connected>(
            fixture().connect("${redirectConformanceRoot()}/mixed-case-string-roles"),
        ).account

        assertEquals(
            UserPermissions(
                download = true,
                playlist = true,
                share = false,
                jukebox = false,
                admin = true,
            ),
            connected.capabilities.permissions,
        )
    }

    @Test
    fun malformedExtensionPayloadCannotSilentlyDisableCapabilities() = runTest {
        listOf("object", "entry", "name", "versions", "version", "duplicate").forEach { shape ->
            assertEquals(
                DomainError.Protocol.MalformedEnvelope,
                assertIs<AccountConnectionResult.Failed>(
                    fixture().connect("${redirectConformanceRoot()}/malformed-extensions-$shape"),
                ).error,
                shape,
            )
        }
    }

    @Test
    fun slowSelfHostedServerCanCompleteAccountNegotiation() = runTest {
        assertIs<AccountConnectionResult.Connected>(
            fixture().connect("${redirectConformanceRoot()}/slow-account"),
            "a self-hosted server responding after 10.5 seconds must remain connectable",
        )
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
            DomainError.Auth.UnsupportedAuthenticationChallenge,
            DomainError.Auth.CrossOriginRedirectRejected(),
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
            "illegal-reg-name-character" to
                "https://credential-canary|evil.invalid/rest/ping.view?u=credential-canary",
            "invalid-percent-escape" to
                "https://credential-canary%zz.invalid/rest/ping.view?u=credential-canary",
            "invalid-bracketed-zone-escape" to
                "https://[fe80::1%25zone%zz]/rest/ping.view?u=credential-canary",
            "illegal-bracketed-zone-character" to
                "https://[fe80::1%zone|metadata]/rest/ping.view?u=credential-canary",
            "empty-bracketed-zone" to
                "https://[fe80::1%]/rest/ping.view?u=credential-canary",
            "empty-encoded-bracketed-zone" to
                "https://[fe80::1%25]/rest/ping.view?u=credential-canary",
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

        val generatedMalformedAuthorities = buildList {
            charArrayOf('|', '^', '`', '{', '}', '<', '>', '"').forEach { illegal ->
                add("https://credential-canary$illegal.invalid/rest/ping.view?u=credential-canary")
            }
            listOf("%", "%z", "%zz", "%0z", "%z0").forEach { invalidEscape ->
                add(
                    "https://credential-canary$invalidEscape.invalid/" +
                        "rest/ping.view?u=credential-canary",
                )
            }
        }
        generatedMalformedAuthorities.forEach { input ->
            assertEquals(
                "<unrenderable-url>",
                Redactor.redactUrl(input),
                input,
            )
        }

        val bracketedZoneSuffixes = listOf(
            "%",
            "%25",
            "%zone|metadata",
            "%25zone%zz",
            "%zone%other",
            "%%25zone",
        )
        bracketedZoneSuffixes.forEach { zoneSuffix ->
            listOf("", ":443", ":443:444").forEach { portSuffix ->
                val input =
                    "https://[fe80::1$zoneSuffix]$portSuffix/" +
                        "rest/ping.view?u=credential-canary"
                assertEquals("<unrenderable-url>", Redactor.redactUrl(input), input)
            }
        }

        assertEquals(
            "https://music.invalid:443/rest/ping.view?<redacted>",
            Redactor.redactUrl(
                "https://music.invalid/rest/ping.view?u=credential-canary#credential-fragment",
            ),
        )
    }

    @Test
    fun structuralUrlRendererRejectsOutOfRangePort() {
        assertEquals(
            "<unrenderable-url>",
            Redactor.redactUrl(
                "https://music.invalid:70000/rest/ping.view?u=credential-canary",
            ),
        )
    }

    @Test
    fun structuralUrlRendererRejectsUnclosedIpv6Bracket() {
        assertEquals(
            "<unrenderable-url>",
            Redactor.redactUrl("https://[::1/rest/ping.view?u=credential-canary"),
        )
    }

    @Test
    fun outOfRangePortIsInvalidServerUrlNotTransportFailure() = runTest {
        assertEquals(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.MalformedHost),
            assertIs<AccountConnectionResult.Failed>(
                fixture().connect(serverUrl = "https://127.0.0.1:70000", allowLocalHttp = false),
            ).error,
        )
    }

    @Test
    fun unclosedIpv6AuthorityIsInvalidServerUrlNotTransportFailure() = runTest {
        assertEquals(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.MalformedHost),
            assertIs<AccountConnectionResult.Failed>(
                fixture().connect(serverUrl = "https://[::1", allowLocalHttp = false),
            ).error,
        )
    }

    @Test
    fun internationalizedHostnameIsUnsupportedNotMalformed() = runTest {
        assertEquals(
            DomainError.Input.InvalidServerUrl(
                InvalidServerUrlReason.UnsupportedInternationalizedHost,
            ),
            assertIs<AccountConnectionResult.Failed>(
                fixture().connect(serverUrl = "https://müsic.example", allowLocalHttp = false),
            ).error,
        )
    }

    @Test
    fun percentEncodedInternationalizedHostnameIsUnsupportedNotTransport() = runTest {
        assertEquals(
            DomainError.Input.InvalidServerUrl(
                InvalidServerUrlReason.UnsupportedInternationalizedHost,
            ),
            assertIs<AccountConnectionResult.Failed>(
                fixture().connect(
                    serverUrl = "https://m%C3%BCsic.example",
                    allowLocalHttp = false,
                ),
            ).error,
        )
    }

    @Test
    fun everyMalformedAuthorityIsInvalidBeforeTransport() = runTest {
        val malformedAuthorities = listOf(
            "empty-host" to "https://:443",
            "empty-ipv6-literal" to "https://[]",
            "unclosed-ipv6-literal" to "https://[::1",
            "nonnumeric-port" to "https://127.0.0.1:not-a-port",
            "empty-port" to "https://127.0.0.1:",
            "out-of-range-port" to "https://127.0.0.1:70000",
            "ambiguous-port" to "https://127.0.0.1:443:444",
            "authority-whitespace" to "https://127.0.0.1 :443",
            "illegal-reg-name-character" to "https://bad|host.invalid",
            "invalid-percent-escape" to "https://bad%zz.invalid",
            "invalid-bracketed-zone-escape" to "https://[fe80::1%25zone%zz]",
            "illegal-bracketed-zone-character" to "https://[fe80::1%zone|metadata]",
            "empty-bracketed-zone" to "https://[fe80::1%]",
            "empty-encoded-bracketed-zone" to "https://[fe80::1%25]",
        )
        malformedAuthorities.forEach { (caseName, serverUrl) ->
            assertEquals(
                DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.MalformedHost),
                assertIs<AccountConnectionResult.Failed>(
                    fixture().connect(serverUrl = serverUrl, allowLocalHttp = false),
                ).error,
                caseName,
            )
        }
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
        assertEquals(
            0,
            targetRequestCount(redirectRoot, "cross-observe", "getOpenSubsonicExtensions"),
            "CONF-08 sent an unauthenticated account-connect request across an origin boundary",
        )
        assertIs<DomainError.Auth.CrossOriginRedirectRejected>(
            assertIs<AccountConnectionResult.Failed>(crossOrigin).error,
        )

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
            RedirectPolicyDecision.Reject(RedirectRejectionReason.CrossOrigin),
            AccountConnectionContract.redirectDecision(
                currentUrl = "http://127.0.0.1/rest/ping.view",
                targetUrl = "https://music.invalid/rest/ping.view",
                redirectsAlreadyFollowed = 0,
            ),
        )
        val crossOriginDecision = assertIs<RedirectPolicyDecision.Reject>(
            AccountConnectionContract.redirectDecision(
                currentUrl = "http://127.0.0.1:4540/rest/ping.view",
                targetUrl = "http://127.0.0.1:4541/rest/ping.view",
                redirectsAlreadyFollowed = 0,
            ),
        )
        assertEquals(RedirectRejectionReason.CrossOrigin, crossOriginDecision.reason)
        assertEquals(
            RedirectPolicyDecision.PreserveCredentials,
            AccountConnectionContract.redirectDecision(
                currentUrl = "http://music.invalid:80/rest/ping.view",
                targetUrl = "https://music.invalid:443/rest/ping.view",
                redirectsAlreadyFollowed = 0,
            ),
        )
        listOf(
            Triple(
                "http://music.invalid:4533/rest/ping.view",
                "https://music.invalid:4533/rest/ping.view",
                RedirectPolicyDecision.PreserveCredentials,
            ),
            Triple(
                "http://music.invalid:80/rest/ping.view",
                "https://music.invalid:80/rest/ping.view",
                RedirectPolicyDecision.Reject(RedirectRejectionReason.CrossOrigin),
            ),
            Triple(
                "http://music.invalid:443/rest/ping.view",
                "https://music.invalid:443/rest/ping.view",
                RedirectPolicyDecision.Reject(RedirectRejectionReason.CrossOrigin),
            ),
        ).forEach { (current, target, expected) ->
            assertEquals(
                expected,
                AccountConnectionContract.redirectDecision(current, target, redirectsAlreadyFollowed = 0),
                "$current -> $target",
            )
        }
    }

    @Test
    fun conf08RedirectHostCanonicalizationIsPlatformInvariant() {
        listOf(
            Triple(
                "https://i.invalid/rest/ping.view",
                "https://ı.invalid/collect",
                RedirectPolicyDecision.Reject(
                    RedirectRejectionReason.UnsupportedInternationalizedHost,
                ),
            ),
            Triple(
                "https://I.invalid/rest/ping.view",
                "https://İ.invalid/collect",
                RedirectPolicyDecision.Reject(
                    RedirectRejectionReason.UnsupportedInternationalizedHost,
                ),
            ),
            Triple(
                "https://i.invalid/rest/ping.view",
                "https://%C4%B1.invalid/collect",
                RedirectPolicyDecision.Reject(
                    RedirectRejectionReason.UnsupportedInternationalizedHost,
                ),
            ),
            Triple(
                "https://MUSIC.INVALID/rest/ping.view",
                "https://music.invalid/collect",
                RedirectPolicyDecision.PreserveCredentials,
            ),
            Triple(
                "https://music.invalid/rest/ping.view",
                "https://music.invalid./collect",
                RedirectPolicyDecision.PreserveCredentials,
            ),
            Triple(
                "https://%69.invalid/rest/ping.view",
                "https://i.invalid/collect",
                RedirectPolicyDecision.PreserveCredentials,
            ),
            Triple(
                "https://%2569.invalid/rest/ping.view",
                "https://i.invalid/collect",
                RedirectPolicyDecision.Reject(RedirectRejectionReason.CrossOrigin),
            ),
            Triple(
                "https://[::1]/rest/ping.view",
                "https://[0:0:0:0:0:0:0:1]/collect",
                RedirectPolicyDecision.PreserveCredentials,
            ),
            Triple(
                "https://[ABCD::1]/rest/ping.view",
                "https://[abcd::1]/collect",
                RedirectPolicyDecision.PreserveCredentials,
            ),
            Triple(
                "https://xn--bcher-kva.invalid/rest/ping.view",
                "https://bücher.invalid/collect",
                RedirectPolicyDecision.Reject(
                    RedirectRejectionReason.UnsupportedInternationalizedHost,
                ),
            ),
        ).forEach { (current, target, expected) ->
            assertEquals(
                expected,
                AccountConnectionContract.redirectDecision(
                    currentUrl = current,
                    targetUrl = target,
                    redirectsAlreadyFollowed = 0,
                ),
                "$current -> $target",
            )
        }
    }

    @Test
    fun conf08RedirectHostIpv6AndPercentGrammarIsClosed() {
        listOf(
            "https://[fe80::1%25eth0]/rest/ping.view" to
                "https://[fe80:0:0:0:0:0:0:1%25eth0]/collect",
            "https://[::ffff:127.0.0.1]/rest/ping.view" to
                "https://[::ffff:7f00:1]/collect",
        ).forEach { (current, target) ->
            assertEquals(
                RedirectPolicyDecision.PreserveCredentials,
                AccountConnectionContract.redirectDecision(current, target, redirectsAlreadyFollowed = 0),
                "$current -> $target",
            )
        }

        listOf(
            "https://[fe80::1%25]/collect",
            "https://[fe80::1%25eth%7C0]/collect",
            "https://[fe80::1%25eth0%2525nested]/collect",
            "https://[1::2::3]/collect",
            "https://[1:2:3:4:5:6:7:8:9]/collect",
            "https://[12345::1]/collect",
            "https://[1.2.3.4::]/collect",
            "https://[1.2.3.4::5]/collect",
            "https://%zz.invalid/collect",
        ).forEach { target ->
            assertEquals(
                RedirectPolicyDecision.Reject(RedirectRejectionReason.InvalidLocation),
                AccountConnectionContract.redirectDecision(
                    currentUrl = "https://music.invalid/rest/ping.view",
                    targetUrl = target,
                    redirectsAlreadyFollowed = 0,
                ),
                target,
            )
        }
    }

    @Test
    fun conf08RedirectHostIpv6ZonesRemainCaseSensitive() {
        assertEquals(
            RedirectPolicyDecision.Reject(RedirectRejectionReason.CrossOrigin),
            AccountConnectionContract.redirectDecision(
                currentUrl = "https://[fe80::1%25ETH0]/rest/ping.view",
                targetUrl = "https://[fe80::1%25eth0]/collect",
                redirectsAlreadyFollowed = 0,
            ),
        )
    }

    @Test
    fun conf08RedirectHostEmbeddedIpv4OctetsRejectNonDigits() {
        assertEquals(
            RedirectPolicyDecision.Reject(RedirectRejectionReason.InvalidLocation),
            AccountConnectionContract.redirectDecision(
                currentUrl = "https://[::ffff:127.0.0.1]/rest/ping.view",
                targetUrl = "https://[::ffff:127.0.0.+1]/collect",
                redirectsAlreadyFollowed = 0,
            ),
        )
    }

    @Test
    fun conf08EnforcesQueryAuthenticationRedirectCredentialPolicy() = runTest {
        val redirectRoot = redirectConformanceRoot()
        val crossOrigin = fixture().connect("$redirectRoot/cross-observe-query")
        assertEquals(
            0,
            targetRequestCount(redirectRoot, "cross-observe-query", "getOpenSubsonicExtensions"),
            "CONF-08 sent the query-auth scenario across an origin boundary",
        )
        assertIs<DomainError.Auth.CrossOriginRedirectRejected>(
            assertIs<AccountConnectionResult.Failed>(crossOrigin).error,
        )
    }

    @Test
    fun conf08RejectsCredentialBearingRedirectPathBeforeTheTargetWire() = runTest {
        val redirectRoot = redirectConformanceRoot()
        val result = fixture().connect("$redirectRoot/cross-reflected-get-user")
        assertEquals(
            0,
            targetRequestCount(redirectRoot, "cross-reflected-get-user", "getUser"),
            "credential-bearing redirect path reached the cross-origin target wire",
        )
        assertIs<DomainError.Auth.CrossOriginRedirectRejected>(
            assertIs<AccountConnectionResult.Failed>(result).error,
        )
    }

    @Test
    fun conf08RejectsSecondHopOriginChangeBeforeTheTargetWire() = runTest {
        val redirectRoot = redirectConformanceRoot()
        val result = fixture().connect("$redirectRoot/two-hop-get-user")
        assertEquals(
            0,
            targetRequestCount(redirectRoot, "two-hop-get-user", "getUser"),
            "second-hop origin change reached the cross-origin target wire",
        )
        assertIs<DomainError.Auth.CrossOriginRedirectRejected>(
            assertIs<AccountConnectionResult.Failed>(result).error,
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

    private suspend fun targetRequestCount(
        redirectRoot: String,
        scenario: String,
        endpoint: String,
    ): Int {
        val client = HttpClient()
        try {
            val response = client.get(
                "$redirectRoot/observations/target-count?scenario=$scenario&endpoint=$endpoint",
            )
            assertEquals(200, response.status.value, "target wire counter is unavailable")
            val document = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: error("target wire counter response is not an object")
            return (document["requests"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: error("target wire counter response has no integer request count")
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
