package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleLibraryBrowseDiagnosticsTest {
    @Test
    fun throwingObserverCannotEscapeStartOrSuppressCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val phases = listOf(
                "facade-start", "coroutine-entered", "browse-returned", "dto-created",
                "completion-entered", "completion-returned",
            )
            for (throwAt in phases) {
                var attempted = false
                var completions = 0
                val client = AppleLibraryBrowseClient { phase ->
                    if (phase.endsWith("phase=$throwAt")) {
                        attempted = true
                        throw IllegalStateException("invented-observer-failure")
                    }
                }
                // Invalid input exercises a completed failure without contacting any server.
                val operation = client.startBrowse(
                    AppleLibraryBrowseRequest("fixture", "invalid-url", "fixture-user", "fixture-password", false),
                ) { outcome ->
                    assertNotNull(outcome.error)
                    completions++
                }
                advanceUntilIdle()
                assertTrue(attempted, "observer phase was not exercised: $throwAt")
                assertEquals(1, completions, "completion lost at $throwAt")
                operation.cancel()
                advanceUntilIdle()
                assertEquals(1, completions, "cancellation duplicated completion")
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
