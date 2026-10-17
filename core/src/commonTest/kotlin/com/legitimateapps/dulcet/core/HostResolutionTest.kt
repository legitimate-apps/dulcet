package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.EmptyCoroutineContext
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
        val dispatcherEntered = List(2) { CompletableDeferred<Unit>() }
        val dispatcherReturned = List(2) { CompletableDeferred<Unit>() }
        val releaseDispatcher = CompletableDeferred<Unit>()
        val releaseLookups = CompletableDeferred<Unit>()
        val started = List(2) { CompletableDeferred<Unit>() }
        var jobs = emptyList<Deferred<List<String>>>()
        try {
            // A worker can start after an 80ms waiter has expired, in which case the resolver
            // correctly skips the lookup. Reproduce that schedule rather than depending on CI
            // load: neither dispatcher thread can take the probes during their first 200ms.
            dispatcherEntered.indices.forEach { index ->
                hostResolutionDispatcher().dispatch(EmptyCoroutineContext, Runnable {
                    try {
                        runBlocking {
                            dispatcherEntered[index].complete(Unit)
                            releaseDispatcher.await()
                        }
                    } finally {
                        dispatcherReturned[index].complete(Unit)
                    }
                })
            }
            withTimeout(5_000) {
                dispatcherEntered.forEach { it.await() }
                jobs = started.map { signal ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        // Capacity needs admitted workers, not a timed-out callback that may
                        // never run. Deadline behavior is covered by the 80ms tests above;
                        // this entire coordination is bounded by the outer five-second limit.
                        boundedHostResolution(Long.MAX_VALUE) {
                            signal.complete(Unit)
                            runBlocking { releaseLookups.await() }
                            local
                        }
                    }
                }
                launch { delay(200); releaseDispatcher.complete(Unit) }
                started.forEach { it.await() }
                var ran = false
                val rejected = async(start = CoroutineStart.UNDISPATCHED) {
                    boundedHostResolution { ran = true; local }
                }
                assertTrue(rejected.isCompleted, "saturation must reject without waiting for a permit or deadline")
                assertEquals(emptyList(), rejected.await())
                assertEquals(false, ran)
                releaseLookups.complete(Unit)
                jobs.forEach { assertEquals(local, it.await()) }
            }
        } finally {
            // Assertion or coordination failure must release both blocking stages. Cancel the
            // waiter jobs too: a resolver regression must report a test failure, not hang CI.
            releaseDispatcher.complete(Unit)
            releaseLookups.complete(Unit)
            jobs.forEach { it.cancel() }
            withTimeout(5_000) {
                jobs.joinAll()
                dispatcherReturned.forEach { it.await() }
            }
        }
        // A waiter can resume just before the worker's finally releases its permit. Observe
        // actual recovery instead of assuming an elapsed sleep means both permits returned.
        val recovered = withTimeout(5_000) {
            var addresses = boundedHostResolution { local }
            while (addresses.isEmpty()) {
                yield()
                addresses = boundedHostResolution { local }
            }
            addresses
        }
        assertEquals(local, recovered)
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
