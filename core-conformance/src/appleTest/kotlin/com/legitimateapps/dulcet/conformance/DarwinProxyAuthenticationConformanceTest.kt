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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.time.TimeSource

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class DarwinProxyAuthenticationConformanceTest {
    @Test
    fun proxyChallengeFailsClosedWithoutAmbientCredentials() = traceProxyTest { mark ->
        withStallDiagnostics("proxy-auth") { diagnostics ->
            diagnostics.write("account.phase begin credential-fixture")
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

            diagnostics.write("account.phase end credential-fixture")
            mark("ambient credential precondition satisfied")
            val observationClient = diagnostics.phase("observation-client-create") {
                HttpClient(Darwin) { expectSuccess = false }
            }
            try {
                mark("connector started")
                val result = DarwinForwardProxyAccountConnector(
                    proxyHost = PROXY_HOST,
                    proxyPort = PROXY_PORT,
                    logSink = diagnostics,
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
                val observation = diagnostics.phase("observation-get") {
                    observationClient.get("http://$PROXY_HOST:$PROXY_PORT/observations/proxy-auth")
                }
                mark("proxy wire observation response received (buffered)")
                // Only render closed fields, never a raw observation body on an assertion failure.
                val observationText = diagnostics.phase("observation-body") {
                    observation.bodyAsText()
                }
                val body = try {
                    Json.parseToJsonElement(observationText).jsonObject
                } catch (_: Throwable) {
                    mark("invalid observation JSON bytes=${observationText.encodeToByteArray().size}")
                    throw AssertionError("proxy wire observation was not a JSON object")
                }
                mark("proxy wire observation body received")
                assertEquals(200, observation.status.value, "proxy wire observation rejected")
                val challengeCount = body["challenge_count"]?.jsonPrimitive?.longOrNull
                assertTrue(challengeCount != null && challengeCount > 0, "handler never observed a challenge")
                val authorizationValues = body["proxy_authorization_values"]?.jsonArray
                assertNotNull(authorizationValues, "handler did not report authorization observations")
                assertTrue(authorizationValues.isEmpty(), "proxy authorization reached the wire")
                mark("handler challenge asserted count=$challengeCount; Proxy-Authorization absent")
            } finally {
                mark("cleanup started")
                diagnostics.phase("observation-client-close") { observationClient.close() }
                diagnostics.phase("credential-cleanup") {
                    storage.removeCredential(credential, protectionSpace)
                }
                mark("cleanup completed")
            }
        }
    }

    @Test
    fun diagnosticFailuresNeverRenderUntrustedText() {
        val canaries = listOf("invented-user-Q97", "invented-token-R97", "invented-salt-S97", "invented-password-T97")
        val url = "https://invalid.example/rest/ping?u=${canaries[0]}&t=${canaries[1]}&s=${canaries[2]}&p=${canaries[3]}"
        val failures = listOf<() -> Unit>(
            { Json.parseToJsonElement("{\"echo\":\"$url\",broken}") },
            { Json.parseToJsonElement("[\"$url\"]").jsonObject },
            { throw IllegalStateException(url, IllegalArgumentException(url)).also {
                it.addSuppressed(IllegalStateException(url))
            } },
        )
        for (fail in failures) {
            val renderedFailure = kotlin.test.assertFailsWith<AssertionError> {
                traceProxyTest(observeFailure = { "observation unavailable" }) { fail() }
            }
            val surfaces = buildList {
                add(renderedFailure.stackTraceToString())
                var current: Throwable? = renderedFailure
                while (current != null) {
                    add(current.message.orEmpty())
                    add(current.toString())
                    current = current.cause
                }
            }
            for (canary in canaries) {
                assertTrue(surfaces.none { canary in it }, "credential canary escaped diagnostic boundary")
            }
            kotlin.test.assertNull(renderedFailure.cause)
            assertTrue(renderedFailure.suppressedExceptions.isEmpty())
        }
    }

    // Keep the wall-clock timeline outside runTest: its timeout cancels the connector, which can
    // return Cancelled and fail a later assertion. That assertion alone loses where time was spent.
    private fun traceProxyTest(
        observeFailure: () -> String = ::failureObservation,
        block: suspend TestScope.((String) -> Unit) -> Unit,
    ) {
        val started = TimeSource.Monotonic.markNow()
        val timeline = mutableListOf<String>()
        try {
            runTest {
                block { phase ->
                    val entry = "${started.elapsedNow()}: $phase"
                    timeline += entry
                    println("PROXY AUTH TEST $entry")
                }
            }
        } catch (_: Throwable) {
            // The original throwable may retain response text in its message, cause or suppressed
            // exceptions. Discard it completely; only closed phase evidence crosses this boundary.
            throw AssertionError(
                "Proxy authentication timeline (${started.elapsedNow()} total): " +
                    timeline.joinToString("; ") + "; proxy wire observation: " + observeFailure(),
            )
        }
    }

    // A fresh scope uses a real dispatcher, independent of runTest's cancelled scope and virtual
    // clock. Bound the entire request (including the body); never echo response/exception text,
    // which could contain credentials. The original throwable must never leave this boundary.
    private fun failureObservation(): String = try {
        runBlocking(Dispatchers.Default) {
            withTimeout(3_000) {
                val client = HttpClient(Darwin) { expectSuccess = false }
                try {
                    val response = client.get("http://$PROXY_HOST:$PROXY_PORT/observations/proxy-auth")
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    when {
                        response.status.value == 409 && body["error"]?.jsonPrimitive?.content ==
                            "proxy authentication challenge was not reached" ->
                            "challenge_count=0; proxy handler did not record a challenge; Proxy-Authorization absent"
                        response.status.value == 409 && body["error"]?.jsonPrimitive?.content ==
                            "proxy authorization reached the wire" ->
                            "Proxy-Authorization reached the wire; challenge_count unavailable"
                        response.status.value == 200 -> {
                            val count = body["challenge_count"]?.jsonPrimitive?.longOrNull
                            val values = body["proxy_authorization_values"]?.jsonArray
                            if (count == null || count < 1 || values == null || values.isNotEmpty()) {
                                "observation fetch returned an unexpected response"
                            } else {
                                val outcome = if (count == 1L) "one challenge observed" else "repeated challenges observed"
                                "challenge_count=$count; $outcome; Proxy-Authorization absent"
                            }
                        }
                        else -> "observation fetch returned an unexpected response"
                    }
                } finally {
                    client.close()
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        "observation fetch timed out after 3s; proxy outcome unavailable"
    } catch (_: Throwable) {
        "observation fetch failed; proxy outcome unavailable"
    }

    private companion object {
        const val PROXY_HOST = "127.0.0.1"
        const val PROXY_PORT = 4543
        const val PROXY_REALM = "dulcet-forward-proxy"
    }
}
