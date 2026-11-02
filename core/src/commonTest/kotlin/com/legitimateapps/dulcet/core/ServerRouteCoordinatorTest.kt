package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerRouteCoordinatorTest {
    @Test fun foregroundAndExplicitRetryHintsRecheckOnDemandWithoutClearingCooldown() = runTest {
        var hint = 0L
        val calls = mutableListOf<String>()
        val home = candidate("home", 0); val remote = candidate("remote", 1)
        val coordinator = ServerRouteCoordinator(listOf(home, remote), backgroundScope,
            ConnectionCandidateProbe { base, _ -> calls += base; null }, { true },
            { testScheduler.currentTime }, { 1.0 }, recheckVersion = { hint })
        val initial = async { coordinator.acquire() }; runCurrent()
        val first = assertNotNull(initial.await())
        coordinator.transportFailed(first)
        hint++
        runCurrent()
        assertEquals(1, calls.size, "Foreground hints alone cannot create idle traffic")
        val retried = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals("remote", retried.await()?.routeId)
        assertEquals(1, calls.count { it == home.route.baseUrl }, "Try Again must respect the failed route's existing cooldown")
        advanceTimeBy(1_001); hint++
        val recovered = async { coordinator.acquire() }; runCurrent()
        assertEquals("home", recovered.await()?.routeId, "New demand may admit the preferred route after its cooldown")
    }

    private fun candidate(id: String, priority: Int, removed: Boolean = false) = RouteCandidate(
        ConnectionRoute(id, "https://$id.example.test/music", id, "private", priority, removed = removed),
        RouteLease("https://$id.example.test/music", id, "fixture-lineage", 1, 7),
    )

    @Test fun concurrentConsumersShareOneBoundedRoundAndFirstSuccessServesImmediately() = runTest {
        val high = candidate("home", 0); val low = candidate("remote", 1)
        val calls = mutableListOf<String>(); val published = mutableListOf<String>()
        val coordinator = ServerRouteCoordinator(listOf(low, high), backgroundScope, ConnectionCandidateProbe { base, _ ->
            calls += base
            delay(if (base == high.lease.baseUrl) 900 else 100)
            null
        }, { published += it.routeId; true }, { testScheduler.currentTime }, { 1.0 })
        val waiters = List(30) { async { coordinator.acquire() } }
        runCurrent(); advanceTimeBy(351); runCurrent()
        assertTrue(waiters.all { it.isCompleted }, "First success must not wait for the preferred probe")
        assertEquals(setOf(low.lease.copy(attemptGeneration = 1)), waiters.awaitAll().toSet())
        advanceTimeBy(550); runCurrent()
        assertEquals(high.lease.copy(attemptGeneration = 1), coordinator.acquire(), "A better success in this round is used by future work")
        assertEquals(listOf(low.route.id, high.route.id), published)
        assertEquals(2, calls.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, calls.size, "No idle preference polling")
    }

    @Test fun hungRoutesHaveThreeSecondDeadlinesAtMostTwoProbesAndEightSecondRound() = runTest {
        var active = 0; var peak = 0; var started = 0
        val coordinator = ServerRouteCoordinator((0..7).map { candidate("route$it", it) }, backgroundScope,
            ConnectionCandidateProbe { _, _ ->
                active++; started++; peak = maxOf(peak, active)
                try { delay(60_000); null } finally { active-- }
            }, { true }, { testScheduler.currentTime }, { 1.0 })
        val request = async { coordinator.acquire() }
        runCurrent(); advanceTimeBy(8_001); runCurrent()
        assertNull(request.await())
        assertEquals(2, peak)
        assertEquals(8, started, "Every listed candidate gets an opportunity within the bounded round")
        assertEquals(0, active, "The round closes all losing probes")
        advanceTimeBy(100_000); runCurrent()
        assertEquals(0, active)
    }

    @Test fun eightCandidateRoundDoesNotStarveAHealthyLastPriorityWan() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = ServerRouteCoordinator((0..7).map { candidate("route$it", it) }, backgroundScope,
            ConnectionCandidateProbe { base, _ ->
                calls += base
                if (base.contains("route7.")) null else { delay(60_000); null }
            }, { true }, { testScheduler.currentTime }, { 1.0 })
        val request = async { coordinator.acquire() }
        runCurrent(); advanceTimeBy(8_001); runCurrent()
        assertEquals("route7", request.await()?.routeId)
        assertEquals(8, calls.size)
    }

    @Test fun globalTopologyHintInvalidatesIdleAdmissionWithoutStartingTraffic() = runTest {
        var version = 1L; var calls = 0
        val coordinator = ServerRouteCoordinator(listOf(candidate("home", 0)), backgroundScope,
            ConnectionCandidateProbe { _, _ -> calls++; null }, { true }, { testScheduler.currentTime }, { 1.0 }, { version })
        val first = async { coordinator.acquire() }; runCurrent(); val old = assertNotNull(first.await())
        version++
        advanceTimeBy(30_000); runCurrent(); assertEquals(1, calls)
        val next = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(251); runCurrent()
        val renewed = assertNotNull(next.await())
        assertNotEquals(old.attemptGeneration, renewed.attemptGeneration)
        assertEquals(2, calls)
    }

    @Test fun topologyHintsCoalesceWithNoIdleTrafficAndRefreshAfterSixtySecondsIdle() = runTest {
        var calls = 0
        val coordinator = ServerRouteCoordinator(listOf(candidate("home", 0)), backgroundScope,
            ConnectionCandidateProbe { _, _ -> calls++; null }, { true }, { testScheduler.currentTime }, { 1.0 })
        coordinator.topologyChanged(); advanceTimeBy(150); coordinator.topologyChanged()
        assertEquals(0, calls)
        val request = async { coordinator.acquire() }
        runCurrent(); advanceTimeBy(249); runCurrent(); assertEquals(0, calls)
        advanceTimeBy(1); runCurrent(); assertNotNull(request.await()); assertEquals(1, calls)
        advanceTimeBy(59_999); assertNotNull(coordinator.acquire()); assertEquals(1, calls)
        advanceTimeBy(60_000)
        val next = async { coordinator.acquire() }; runCurrent(); assertNotNull(next.await()); assertEquals(2, calls)
    }

    @Test fun transportFailureCoolsOnlyItsCapturedRouteAndNewTopologyAllowsImmediateRetry() = runTest {
        val home = candidate("home", 0); val remote = candidate("remote", 1)
        val calls = mutableListOf<String>()
        val coordinator = ServerRouteCoordinator(listOf(home, remote), backgroundScope,
            ConnectionCandidateProbe { base, _ -> calls += base; null }, { true }, { testScheduler.currentTime }, { 1.0 })
        val first = async { coordinator.acquire() }; runCurrent(); assertEquals(home.lease.copy(attemptGeneration = 1), first.await())
        advanceTimeBy(251); runCurrent()
        coordinator.transportFailed(home.lease.copy(attemptGeneration = 1))
        val second = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(remote.lease.copy(attemptGeneration = 2), second.await())
        coordinator.transportFailed(home.lease.copy(attemptGeneration = 1))
        assertEquals(remote.lease.copy(attemptGeneration = 2), coordinator.acquire(), "Late A failure cannot poison winner B")
        coordinator.topologyChanged()
        val third = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(home.lease.copy(attemptGeneration = 3), third.await())
        assertEquals(listOf(home.lease.baseUrl, remote.lease.baseUrl, home.lease.baseUrl), calls)
    }

    @Test fun identityMismatchQuarantinesCandidateAndRemovalDuringProbeCannotPublish() = runTest {
        val home = candidate("home", 0); val remote = candidate("remote", 1)
        var authorized = true; val published = mutableListOf<String>(); var homeCalls = 0
        val gate = CompletableDeferred<Unit>()
        val coordinator = ServerRouteCoordinator(listOf(home, remote, candidate("removed", -1, true)), backgroundScope,
            ConnectionCandidateProbe { base, _ ->
                if (base == home.lease.baseUrl) { homeCalls++; DomainError.Protocol.ServerIdentityChanged }
                else { gate.await(); null }
            }, { if (authorized) { published += it.routeId; true } else false }, { testScheduler.currentTime }, { 1.0 })
        val first = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(251); runCurrent()
        authorized = false; gate.complete(Unit); runCurrent()
        assertNull(first.await()); assertTrue(published.isEmpty())
        coordinator.topologyChanged()
        val second = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(501); runCurrent()
        assertNull(second.await()); assertEquals(1, homeCalls, "Topology cannot clear identity quarantine")
    }

    @Test fun oldTopologyFailureCannotCoolARecoveredRouteAndFailureDuringAFlightCannotReuseItsOldSuccess() = runTest {
        val home = candidate("home", 0); val remote = candidate("remote", 1)
        val coordinator = ServerRouteCoordinator(listOf(home, remote), backgroundScope,
            ConnectionCandidateProbe { _, _ -> null }, { true }, { testScheduler.currentTime }, { 1.0 })
        val first = async { coordinator.acquire() }; runCurrent()
        val original = assertNotNull(first.await())
        // Failure immediately after first success, before lower-priority jobs have settled.
        coordinator.transportFailed(original)
        val fallback = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals("remote", fallback.await()?.routeId)
        coordinator.topologyChanged()
        val recovery = async { coordinator.acquire() }; runCurrent(); advanceTimeBy(251); runCurrent()
        val recovered = assertNotNull(recovery.await())
        assertEquals("home", recovered.routeId)
        coordinator.transportFailed(original)
        assertEquals(recovered, coordinator.acquire(), "A pre-change attempt cannot poison the recovered route")
    }

    @Test fun cancellationOfOneConsumerDoesNotCancelAnotherConsumersAdmission() = runTest {
        var calls = 0
        val coordinator = ServerRouteCoordinator(listOf(candidate("home", 0)), backgroundScope,
            ConnectionCandidateProbe { _, _ -> calls++; delay(500); null }, { true }, { testScheduler.currentTime }, { 1.0 })
        val cancelled = async { coordinator.acquire() }; val retained = async { coordinator.acquire() }
        runCurrent(); cancelled.cancel(); advanceTimeBy(501); runCurrent()
        assertNotNull(retained.await()); assertEquals(1, calls)
    }
}
