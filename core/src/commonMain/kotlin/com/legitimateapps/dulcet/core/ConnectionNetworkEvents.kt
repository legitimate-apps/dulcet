package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Process-local topology hints, not reachability or network-cost permission.
 * Native adapters report meaningful link/DNS/default-network/capability changes here even when
 * still online. Idle accounts do no work; their shared selector observes this on next demand.
 */
@OptIn(ExperimentalAtomicApi::class)
public object ConnectionNetworkEvents {
    private val version = AtomicLong(0)
    private val rechecks = AtomicLong(0)
    public fun topologyChanged() { version.fetchAndAdd(1) }
    public fun recheckRequested() { rechecks.fetchAndAdd(1) }
    internal fun recheckGeneration(): Long = rechecks.load()
    internal fun generation(): Long = version.load()
}

internal fun DomainError.isRouteTransportFailure(): Boolean =
    this == DomainError.Transport.Unreachable || this == DomainError.Transport.Timeout ||
        this is DomainError.Security.TlsUntrusted
