package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.DomainError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StallDiagnosticsTest {
    @Test
    fun outstandingPhaseSurvivesUntilCancellation() = runTest {
        withStallDiagnostics("cancellation-control") { diagnostics ->
            val entered = CompletableDeferred<Unit>()
            val waiting = launch {
                diagnostics.phase("controlled-suspension") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            assertContains(diagnostics.snapshot(), "controlled-suspension:")
            waiting.cancelAndJoin()
            assertFalse(diagnostics.snapshot().contains("controlled-suspension:"))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun watchdogUsesWallTimeWithoutAdvancingTestClock() = runTest {
        withStallDiagnostics("watchdog-control", pulseIntervalMillis = 20, watchdogIntervalMillis = 50) { diagnostics ->
            diagnostics.phase("controlled-real-delay") {
                withContext(Dispatchers.Default) { delay(150) }
            }
            assertEquals(0, currentTime)
        }
    }

    @Test
    fun localAuthenticationFailureCompletesInstrumentedRequests() = runTest {
        withStallDiagnostics("local-auth-control") { diagnostics ->
            val baseUrl = disposableConformanceBaseUrl()
            check(baseUrl == "http://127.0.0.1:4533")
            val result = AccountConnector(logSink = diagnostics).connect(
                AccountConnectionRequest(
                    serverUrl = baseUrl,
                    username = "diagnostic-nonexistent-user",
                    password = "diagnostic-invalid-password",
                    allowLocalHttp = true,
                ),
            )
            assertIs<DomainError.Auth.InvalidCredentials>(
                assertIs<AccountConnectionResult.Failed>(result).error,
            )
            assertFalse(diagnostics.snapshot().contains("connect:"))
            assertContains(diagnostics.snapshot(), "completed=true")
        }
    }
}
