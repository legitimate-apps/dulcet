package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.LibrarySyncCompletionStability
import com.legitimateapps.dulcet.core.LibrarySyncContract
import com.legitimateapps.dulcet.core.LibrarySyncControlResult
import com.legitimateapps.dulcet.core.LibrarySyncRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class LibrarySyncConformanceTest {
    @Test
    fun conf31GenerationPinnedReads() = runTest(timeout = 5.minutes) {
        val observed = assertIs<LibrarySyncControlResult.GenerationPinnedReads>(
            LibrarySyncContract.generationPinnedReads(request("conf-31")),
            "CONF-31 must establish its own cold local database and disposable-server preconditions",
        )

        assertEquals(1, observed.generationBefore)
        assertEquals(
            observed.generationBefore,
            observed.generationObservedInsideCommit,
            "CONF-31: an independent connection observed the pending generation before commit",
        )
        assertEquals(observed.generationBefore + 1, observed.generationAfter)
        assertEquals(observed.starredCountBefore, observed.starredCountObservedInsideCommit)
        assertEquals(observed.starredCountBefore + 1, observed.starredCountAfter)
    }

    @Test
    fun conf32AtomicSyncGenerationCommit() = runTest(timeout = 5.minutes) {
        val observed = assertIs<LibrarySyncControlResult.AtomicCommit>(
            LibrarySyncContract.atomicCommit(request("conf-32")),
            "CONF-32 must establish its own cold local database and disposable-server preconditions",
        )

        assertTrue(observed.interruptedCommitFailed)
        assertTrue(observed.interruptionProbeInvoked)
        assertTrue(
            observed.oldSnapshotRemainedVisible,
            "CONF-32: interrupting immediately after the generation update exposed partial state",
        )
        assertEquals(observed.generationBefore, observed.generationAfterInterruptedCommit)
        assertEquals(observed.generationBefore + 1, observed.generationAfterRetry)
    }

    @Test
    fun conf33BoundedStabilityWitness() = runTest(timeout = 5.minutes) {
        val observed = assertIs<LibrarySyncControlResult.BoundedWitness>(
            LibrarySyncContract.boundedStabilityWitness(request("conf-33")),
            "CONF-33 must establish its own cold local database and disposable-server preconditions",
        )

        assertEquals(1, observed.generation)
        assertEquals(LibrarySyncCompletionStability.Unverified, observed.stability)
        assertEquals(
            1 + LibrarySyncContract.maximumStabilityAttempts,
            observed.albumListWalks,
            "CONF-33: witness retries were not bounded to the declared three attempts",
        )
        assertEquals(observed.albumListWalks, observed.paginationMutations)
        assertTrue(
            observed.committedSnapshotMatchesLastWitness,
            "CONF-33: the committed album set did not match the final bounded witness",
        )
        assertEquals(0, observed.danglingReferenceCount)
        assertEquals(4, LibrarySyncContract.defaultMaximumInFlightPerServer)
    }

    private fun request(controlId: String): LibrarySyncRequest = LibrarySyncRequest(
        providerInstanceId = "disposable:$controlId",
        normalizedBaseUrl = disposableConformanceBaseUrl(),
        username = environmentOrNull("DULCET_CONFORMANCE_USERNAME") ?: ADMIN_USER,
        password = environmentOrNull("DULCET_CONFORMANCE_PASSWORD") ?: ADMIN_PASSWORD,
        allowLocalHttp = true,
    )

    private companion object {
        const val ADMIN_USER = "dulcet-admin"
        const val ADMIN_PASSWORD = "dulcet-ci-canary-password"
    }
}
