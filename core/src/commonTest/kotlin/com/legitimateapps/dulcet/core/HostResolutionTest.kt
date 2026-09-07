package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class HostResolutionTest {
    private val local = listOf("127.0.0.1")
    private val source = "http://source.invalid"
    private val target = "http://target.invalid"

    @Test
    fun targetRejectsEmptyTimeoutCancellationAndError() = runBlocking {
        for (resolver in failedResolvers()) {
            val policy = LocalHttpConnectionPolicy(resolver, 30)
            val failure = assertFailsWith<LocalHttpPolicyFailure> { policy.targetFor(target, true) }
            assertEquals(DomainError.Security.LocalExceptionViolated, failure.error)
        }
    }

    @Test
    fun redirectLeavesLocalNetworkOnEmptyTimeoutCancellationAndError() = runBlocking {
        for (resolver in failedResolvers()) {
            assertTrue(LocalHttpConnectionPolicy(resolver, 30).leavesLocalNetwork(source, target))
        }
    }

    private fun failedResolvers(): List<HostResolver> = listOf(
        HostResolver { emptyList() },
        HostResolver { awaitCancellation() },
        HostResolver { throw CancellationException("resolver cancelled") },
        HostResolver { throw IllegalStateException("resolver failed") },
    )

    @Test
    fun parentCancellationRejectsTarget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val decision = CompletableDeferred<Boolean>()
        val policy = LocalHttpConnectionPolicy(HostResolver { entered.complete(Unit); awaitCancellation() })
        val job = launch {
            decision.complete(try { policy.targetFor(target, true); false } catch (_: LocalHttpPolicyFailure) { true })
        }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(decision.await())
    }

    @Test
    fun parentCancellationRejectsRedirect() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val decision = CompletableDeferred<Boolean>()
        val policy = LocalHttpConnectionPolicy(HostResolver { entered.complete(Unit); awaitCancellation() })
        val job = launch { decision.complete(policy.leavesLocalNetwork(source, target)) }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(decision.await())
    }

    // Deliberately non-cooperative: no suspension or cancellation checks, on JVM AND Native.
    private fun blockingLookup(): List<String> {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < 500.milliseconds) { /* simulate a blocked OS resolver */ }
        return local
    }

    @Test
    fun blockingLookupDoesNotBlockCallerAndDeadlineRejectsTarget() = runBlocking {
        val heartbeat = CompletableDeferred<Unit>()
        val pulse = launch { delay(10); heartbeat.complete(Unit) }
        val start = TimeSource.Monotonic.markNow()
        try {
            val policy = LocalHttpConnectionPolicy(HostResolver { boundedHostResolution(80, ::blockingLookup) })
            assertFailsWith<LocalHttpPolicyFailure> { policy.targetFor(target, true) }
            assertTrue(heartbeat.isCompleted, "calling dispatcher must run while lookup is blocked")
            assertTrue(start.elapsedNow() < 400.milliseconds, "must not wait for the 500ms blocking lookup")
        } finally {
            pulse.join()
            delay(1_500) // 3x margin over the 500ms busy-spin; 550ms left only 50ms of slack
        }
    }

    @Test
    fun blockingLookupDeadlineRejectsRedirect() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        try {
            val policy = LocalHttpConnectionPolicy(HostResolver { boundedHostResolution(80, ::blockingLookup) })
            assertTrue(policy.leavesLocalNetwork(source, target))
            assertTrue(start.elapsedNow() < 400.milliseconds)
        } finally {
            delay(1_500) // 3x margin over the 500ms busy-spin; 550ms left only 50ms of slack
        }
    }

    @Test
    fun saturationRejectsWithoutQueueingAndRecoversAfterWorkersReturn() = runBlocking {
        val started = List(2) { CompletableDeferred<Unit>() }
        val jobs = started.map { signal ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                boundedHostResolution(80) { signal.complete(Unit); blockingLookup() }
            }
        }
        started.forEach { it.await() }
        var ran = false
        assertEquals(emptyList(), boundedHostResolution { ran = true; local })
        assertEquals(false, ran)
        jobs.forEach { it.join() }
        delay(1_500) // 3x margin over the 500ms busy-spin; 550ms left only 50ms of slack
        assertEquals(local, boundedHostResolution { local })
    }

    @Test
    fun successMixedAddressesAndLiteralsKeepTheirPolicy() = runBlocking {
        val policy = LocalHttpConnectionPolicy(HostResolver { local })
        assertEquals("http://127.0.0.1:80", policy.targetFor(target, true).url)
        assertEquals(false, policy.leavesLocalNetwork(source, target))
        val mixed = LocalHttpConnectionPolicy(HostResolver { local + "8.8.8.8" })
        assertFailsWith<LocalHttpPolicyFailure> { mixed.targetFor(target, true) }
        assertTrue(mixed.leavesLocalNetwork(source, target))
        val unused = LocalHttpConnectionPolicy(HostResolver { error("literal must bypass DNS") })
        assertEquals("http://127.0.0.1:4533/", unused.targetFor("http://127.0.0.1:4533/", true).url)
    }

    @Test
    fun workerErrorsAndCancellationRejectAtBothCallSites() = runBlocking {
        for (failure in listOf(IllegalStateException("lookup failed"), CancellationException("lookup cancelled"))) {
            val policy = LocalHttpConnectionPolicy(HostResolver { boundedHostResolution { throw failure } })
            assertFailsWith<LocalHttpPolicyFailure> { policy.targetFor(target, true) }
            assertTrue(policy.leavesLocalNetwork(source, target))
        }
    }

    @Test
    fun platformResolverResolvesLoopbackWithoutExternalNetwork() = runBlocking {
        assertEquals(listOf("127.0.0.1"), platformResolveHost("127.0.0.1"))
    }
}
