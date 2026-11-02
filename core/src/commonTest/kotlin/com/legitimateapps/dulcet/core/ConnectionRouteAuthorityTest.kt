package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConnectionRouteAuthorityTest {
    private val anchor = OwnershipAnchor("route-authority-fixture", "https://bootstrap.example.test/music", "listener", "fixture")
    private fun candidate(id: String, generation: Long, priority: Int = 0, removed: Boolean = false) = RouteCandidate(
        ConnectionRoute(id, "https://$id.example.test/music", id, "private", priority, removed = removed),
        RouteLease("https://$id.example.test/music", id, "fixture", 1, generation))
    private fun current(lease: RouteLease) = ConnectionRequestAuthorization.isCurrent(
        anchor.normalizedBaseUrl, anchor.username, lease.baseUrl, lease.generation)

    @Test fun automaticPreferenceChangeRetainsHealthyOldLeaseAndNeverChangesOwnershipAnchor() = runTest {
        val home = candidate("home", 1); val remote = candidate("remote", 1, 1)
        try {
            ConnectionRouteRegistry.publishConfiguration(anchor, remote.lease, 1, listOf(home, remote), ConnectionCandidateProbe { _, _ -> null })
            assertTrue(current(remote.lease))
            val selected = ConnectionRouteRegistry.acquireSelection(anchor.normalizedBaseUrl, anchor.username)
            assertEquals(home.lease.copy(attemptGeneration = 1), selected.lease)
            assertTrue(current(remote.lease), "Healthy old work remains admitted after preference changes")
            assertTrue(current(home.lease))
            assertEquals("https://bootstrap.example.test/music", anchor.normalizedBaseUrl)
        } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    @Test fun directoryRefreshPreservesUnchangedAuthorityButRemovalAndReaddCannotReviveOldLease() = runTest {
        val home = candidate("home", 1); val remote = candidate("remote", 1, 1)
        try {
            ConnectionRouteRegistry.publishConfiguration(anchor, home.lease, 1, listOf(home, remote))
            val next = candidate("home", 2)
            ConnectionRouteRegistry.publishConfiguration(anchor, next.lease, 2, listOf(next, candidate("remote", 2, 1)))
            assertTrue(current(home.lease), "An unchanged directory route does not interrupt healthy work")
            ConnectionRouteRegistry.publishConfiguration(anchor, null, 3, listOf(candidate("home", 3, removed = true), candidate("remote", 3, 1)))
            assertFalse(current(home.lease)); assertFalse(current(next.lease))
            val readded = candidate("home", 4)
            ConnectionRouteRegistry.publishConfiguration(anchor, readded.lease, 4, listOf(readded))
            assertTrue(current(readded.lease)); assertFalse(current(home.lease)); assertFalse(current(next.lease))
            assertFalse(ConnectionRouteRegistry.publishConfiguration(anchor, home.lease, 1, listOf(home, remote)))
            assertFalse(current(home.lease), "A delayed restore cannot recertify a tombstoned lease")
        } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    @Test fun removedDuringProbeRejectsPublicationAndUsesTheNewConfiguration() = runTest {
        val home = candidate("home", 1)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            ConnectionRouteRegistry.publishConfiguration(anchor, home.lease, 1, listOf(home), ConnectionCandidateProbe { _, _ ->
                started.complete(Unit); release.await(); null
            })
            val request = async { ConnectionRouteRegistry.acquireSelection(anchor.normalizedBaseUrl, anchor.username) }
            started.await()
            ConnectionRouteRegistry.publishConfiguration(anchor, null, 2, listOf(candidate("home", 2, removed = true)))
            release.complete(Unit)
            assertNull(request.await().lease)
            assertFalse(current(home.lease))
        } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    @Test fun obsoleteDirectoryRowsNeverBecomeAutomaticCandidatesAndDuplicateRestoreDoesNotResetWinner() = runTest {
        val old = candidate("old", 1)
        val home = candidate("home", 2); val remote = candidate("remote", 2, 1)
        val failHome = true
        val probed = mutableListOf<String>()
        val receipts = Mutex()
        val probe = ConnectionCandidateProbe { base, _ ->
            receipts.withLock { probed.add(base) }
            if (failHome && base == home.lease.baseUrl) DomainError.Transport.Unreachable else null
        }
        try {
            ConnectionRouteRegistry.publishConfiguration(anchor, old.lease, 1, listOf(old))
            ConnectionRouteRegistry.publishConfiguration(anchor, home.lease, 2, listOf(home, remote), probe)
            assertFalse(current(old.lease))
            assertEquals(remote.lease.copy(attemptGeneration = 1), ConnectionRouteRegistry.acquireSelection(anchor.normalizedBaseUrl, anchor.username).lease)
            assertTrue(ConnectionRouteRegistry.publishConfiguration(anchor, home.lease, 2, listOf(home, remote), probe))
            assertEquals(remote.lease.copy(attemptGeneration = 1), ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username))
            assertFalse(probed.contains(old.lease.baseUrl))
        } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }
}
