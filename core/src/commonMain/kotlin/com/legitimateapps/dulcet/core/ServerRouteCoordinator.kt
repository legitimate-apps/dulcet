package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.TimeSource

/** A request-local transport choice. This type is never an ownership binding. */
internal data class RouteCandidate(val route: ConnectionRoute, val lease: RouteLease)

/**
 * Account-scoped admission flight. Callers supply an owned scope and credential-free TLS probe.
 * There are no idle timers: topology/foreground hints invalidate admission, and demand starts work.
 * Selecting a preferred route does not revoke any other route's outstanding requests.
 *
 * [publish] must atomically check the configuration generation before accepting a winner. This
 * closes the suspension between a successful probe and a concurrent removal/directory update.
 * The configuration is immutable; its owner replaces this coordinator when authority changes.
 */
internal class ServerRouteCoordinator(
    private val candidates: List<RouteCandidate>,
    private val scope: CoroutineScope,
    private val probe: ConnectionCandidateProbe,
    private val publish: (RouteLease) -> Boolean,
    private val monotonicMillis: () -> Long = monotonicClock(),
    private val jitter: () -> Double = { Random.nextDouble(0.8, 1.2) },
    private val topologyVersion: () -> Long = { 0 },
    private val quarantine: (RouteLease) -> Unit = {},
    private val recheckVersion: () -> Long = { 0 },
) {
    private val mutex = Mutex()
    private val ordered = candidates.filter { !it.route.removed && it.route.baseUrl.startsWith("https://") }
        .sortedWith(compareBy<RouteCandidate> { it.route.priority }.thenBy { it.route.id })
    private data class Failure(val count: Int, val until: Long)
    private class Round(val topology: Long, val attempt: Long) {
        val first = CompletableDeferred<RouteLease?>()
        var job: Job? = null
        var winner: RouteCandidate? = null
        val probes = mutableMapOf<Int, Job>()
    }
    private var topology = 0L
    private var observedTopology = topologyVersion()
    private var observedRecheck = recheckVersion()
    private var nextAttempt = 0L
    private val admissions = mutableMapOf<String, RouteLease>()
    private var coalesceUntil = 0L
    private var round: Round? = null
    private var selected: RouteLease? = null
    private var lastUse = Long.MIN_VALUE
    private val failures = mutableMapOf<String, Failure>()
    private val quarantined = mutableSetOf<String>()

    suspend fun acquire(): RouteLease? {
        val pending = mutex.withLock {
            val now = monotonicMillis()
            if (observedTopology != topologyVersion()) {
                observedTopology = topologyVersion()
                observedRecheck = recheckVersion()
                invalidateLocked(coalesce = true, clearCooldowns = true)
            } else if (observedRecheck != recheckVersion()) {
                observedRecheck = recheckVersion()
                invalidateLocked(coalesce = false, clearCooldowns = false)
            }
            selected?.takeIf { now >= lastUse && now - lastUse < IDLE_ADMISSION_MILLIS }?.let {
                lastUse = now
                return it
            }
            round?.let { return@withLock it.first }
            val next = Round(topology, ++nextAttempt)
            round = next
            next.job = scope.launch { runRound(next) }
            next.first
        }
        // One cancelled waiter must not cancel admission for the other consumers.
        return pending.await().also { if (it != null) mutex.withLock { lastUse = monotonicMillis() } }
    }

    /** Meaningful link/DNS/default-network/capability change, even while still online. */
    suspend fun topologyChanged() = invalidate(coalesce = true, clearCooldowns = true)

    /** Foreground, Try Again and accepted-directory update are explicit recheck triggers. */
    suspend fun recheck() = invalidate(coalesce = false, clearCooldowns = false)

    private suspend fun invalidate(coalesce: Boolean, clearCooldowns: Boolean) {
        mutex.withLock { invalidateLocked(coalesce, clearCooldowns) }
    }

    private fun invalidateLocked(coalesce: Boolean, clearCooldowns: Boolean) {
        topology += 1
        coalesceUntil = monotonicMillis() + if (coalesce) STAGGER_MILLIS else 0
        selected = null
        admissions.clear()
        if (clearCooldowns) failures.clear()
        round?.let { it.first.complete(null); it.job?.cancel() }
        round = null
    }

    /** Only a transport failure from this captured lease may cool down its route. */
    suspend fun transportFailed(lease: RouteLease) {
        mutex.withLock {
            if (admissions[lease.routeId] != lease) return
            recordFailure(lease.routeId)
            if (selected == lease) {
                selected = null
                round?.takeIf { it.winner?.lease == lease }?.let {
                    it.job?.cancel()
                    round = null
                }
            }
            // A late failure on A cannot discard the winner B or restart its admission flight.
        }
    }

    private suspend fun runRound(current: Round) {
        try {
            val wait = mutex.withLock { (coalesceUntil - monotonicMillis()).coerceAtLeast(0) }
            delay(wait)
            val roundStarted = monotonicMillis()
            withTimeout(ROUND_MILLIS) {
                coroutineScope {
                    val slots = Semaphore(MAX_PARALLEL)
                    ordered.forEachIndexed { index, candidate ->
                        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                            delay(index * STAGGER_MILLIS)
                            slots.withPermit {
                                val eligible = mutex.withLock {
                                    round === current && current.topology == topology && observedTopology == topologyVersion() &&
                                        candidate.route.id !in quarantined &&
                                        (failures[candidate.route.id]?.until ?: Long.MIN_VALUE) <= monotonicMillis() &&
                                        (current.winner == null || ordered.indexOfFirst { it.route.id == current.winner?.route?.id } > index)
                                }
                                if (!eligible) return@withPermit
                                val error = try {
                                    // Share the remaining round across waiting pairs so eight
                                    // hung candidates cannot starve a healthy last-priority WAN.
                                    val waves = (ordered.size - index + MAX_PARALLEL - 1) / MAX_PARALLEL
                                    val remaining = (ROUND_MILLIS - (monotonicMillis() - roundStarted)).coerceAtLeast(1)
                                    val deadline = minOf(PROBE_MILLIS, remaining / waves).coerceAtLeast(1)
                                    withTimeout(deadline) { probe.verify(candidate.lease.baseUrl, candidate.lease.instanceId) }
                                } catch (_: TimeoutCancellationException) {
                                    DomainError.Transport.Timeout
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    DomainError.Transport.Unreachable
                                }
                                mutex.withLock {
                                    if (round !== current || topology != current.topology || observedTopology != topologyVersion()) return@withLock
                                    when {
                                        error == DomainError.Protocol.ServerIdentityChanged -> {
                                            quarantined.add(candidate.route.id)
                                            quarantine(candidate.lease)
                                        }
                                        error != null -> recordFailure(candidate.route.id)
                                        current.winner == null || ordered.indexOfFirst { it.route.id == current.winner?.route?.id } > index -> {
                                            val admitted = candidate.copy(lease = candidate.lease.copy(attemptGeneration = current.attempt))
                                            if (publish(admitted.lease)) {
                                                failures.remove(candidate.route.id)
                                                current.winner = admitted
                                                selected = admitted.lease
                                                admissions[candidate.route.id] = admitted.lease
                                                lastUse = monotonicMillis()
                                                current.first.complete(admitted.lease)
                                                current.probes.filterKeys { it > index }.values.forEach { it.cancel() }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        mutex.withLock { current.probes[index] = job }
                        job.start()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Round cancellation is not another refusal by any route.
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (round === current) round = null
                    current.first.complete(current.winner?.lease)
                }
            }
        }
    }

    private fun recordFailure(id: String) {
        val count = ((failures[id]?.count ?: 0) + 1).coerceAtMost(7)
        val base = (1_000L shl (count - 1)).coerceAtMost(60_000)
        val wait = (base * jitter().coerceIn(0.8, 1.2)).toLong().coerceAtMost(60_000)
        failures[id] = Failure(count, monotonicMillis() + wait)
    }

    companion object {
        const val STAGGER_MILLIS = 250L
        const val PROBE_MILLIS = 3_000L
        const val ROUND_MILLIS = 8_000L
        const val IDLE_ADMISSION_MILLIS = 60_000L
        const val MAX_PARALLEL = 2
        private fun monotonicClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
