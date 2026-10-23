package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerIdentityGateConcurrencyTest {
    @Test fun lateProbeCannotOverwriteAnExplicitlyAcceptedReplacement() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val identities = ServerIdentityStore(store.database)
            val old = ServerIdentityPolicy.identity("Navidrome")
            val accepted = ServerIdentityPolicy.identity("dulcet", "new-history")
            identities.accept("server", old)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val probe = async { heldGate(store, old, entered, release).validate() }
            entered.await()
            identities.accept("server", accepted)
            release.complete(Unit)
            val result = probe.await()
            assertEquals(accepted, identities.read("server"), "a suspended A probe must not overwrite accepted B")
            assertEquals(DomainError.Transport.Cancelled, result)
        } finally { store.close() }
    }

    @Test fun lateProbeCannotRecreateRemovedAcceptedLineage() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val identities = ServerIdentityStore(store.database)
            val old = ServerIdentityPolicy.identity("Navidrome")
            identities.accept("server", old)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val probe = async { heldGate(store, old, entered, release).validate() }
            entered.await()
            store.database.libraryDepthQueries.deleteEntriesForServer("server")
            release.complete(Unit)
            val result = probe.await()
            assertEquals("", identities.read("server"), "a removed account must not regain accepted lineage")
            assertEquals(DomainError.Transport.Cancelled, result)
        } finally { store.close() }
    }

    @Test fun matchingConcurrentLearningDoesNotReportASwap() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val identities = ServerIdentityStore(store.database)
            val learned = ServerIdentityPolicy.identity("Navidrome")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val probe = async { heldGate(store, learned, entered, release).validate() }
            entered.await()
            identities.accept("server", learned)
            release.complete(Unit)
            assertNull(probe.await(), "another matching probe learning the identity is harmless")
            assertEquals(learned, identities.read("server"))
        } finally { store.close() }
    }

    private fun heldGate(store: DulcetDatabaseStore, identity: String,
        entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) = ServerIdentityGate(
        store.database, "server", AccountConnectionRequest("https://music.example", "alice", "fixture"),
    ) {
        entered.complete(Unit)
        release.await()
        AccountConnectionResult.Connected(ConnectedAccount("https://music.example", false, "1.16.1", true,
            "fixture", "1", CapabilitySet(emptyMap(), UserPermissions(false, false, false, false, false), false),
            emptyList(), serverIdentity = identity))
    }
}
