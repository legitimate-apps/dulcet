package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.*
import kotlinx.coroutines.*
import kotlin.test.*

class ConnectionSettingsTest {
    @Test fun lateEnrollmentCannotReplaceNewAccountsRoutesOrBusyState() = runBlocking {
        val backend = Backend()
        val settings = ConnectionSettings(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), backend)
        settings.restore()
        val original = backend.anchor(backend.current)
        settings.enroll(original)
        assertTrue(settings.state.value.busy)
        backend.current = backend.second
        settings.restore()
        val next = backend.anchor(backend.current)
        settings.enroll(next)
        assertTrue(settings.state.value.busy)
        backend.pending[0].complete(ConnectionEnrollmentResult.Enrolled(backend.snapshot(original)))
        yield()
        assertEquals(next, settings.state.value.snapshot?.anchor)
        assertTrue(settings.state.value.busy, "The old completion cannot clear the new operation")
        backend.pending[1].complete(ConnectionEnrollmentResult.Enrolled(backend.snapshot(next)))
        yield()
        assertEquals(next, settings.state.value.snapshot?.anchor)
        assertFalse(settings.state.value.busy)
        settings.close()
    }

    @Test fun staleDisplayedRouteIdCannotRemoveSameNamedRouteFromNewAccount() {
        val backend = Backend()
        val settings = ConnectionSettings(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), backend)
        settings.restore()
        val displayed = assertNotNull(settings.state.value.snapshot).anchor
        backend.current = backend.second
        settings.remove(displayed, "home")
        assertTrue(backend.removals.isEmpty())
        assertEquals(backend.anchor(backend.second), settings.state.value.snapshot?.anchor)
        settings.remove(backend.anchor(backend.second), "home")
        assertEquals(listOf(backend.anchor(backend.second) to "home"), backend.removals)
        settings.close()
    }

    @Test fun changedLineageOnSameAccountRejectsDisplayedMutation() {
        val backend = Backend()
        val settings = ConnectionSettings(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), backend)
        settings.restore()
        val displayed = assertNotNull(settings.state.value.snapshot).anchor
        backend.current = backend.current.copy(serverIdentity = "new-lineage")
        settings.remove(displayed, "home")
        assertTrue(backend.removals.isEmpty())
        assertEquals("new-lineage", settings.state.value.snapshot?.anchor?.serverIdentity)
        settings.close()
    }

    private class Backend : ConnectionSettingsBackend {
        var current = StoredAccount("first", "First", "https://first.example.test", "listener", "fixture", false, "lineage-a")
        val second = StoredAccount("second", "Second", "https://second.example.test", "listener", "fixture", false, "lineage-b")
        val pending = mutableListOf<CompletableDeferred<ConnectionEnrollmentResult>>()
        val removals = mutableListOf<Pair<OwnershipAnchor, String>>()
        override fun account() = current
        override fun anchor(account: StoredAccount) = OwnershipAnchor(account.id, account.serverUrl, account.username, account.serverIdentity)
        fun snapshot(anchor: OwnershipAnchor) = ConnectionSnapshot(anchor,
            listOf(ConnectionRoute("home", "https://route.example.test", "Home", "private", 0, true, false)), "home", 1, true)
        override fun restore(anchor: OwnershipAnchor) = snapshot(anchor)
        override suspend fun enroll(request: ConnectionEnrollmentRequest): ConnectionEnrollmentResult =
            CompletableDeferred<ConnectionEnrollmentResult>().also { pending += it }.await()
        override fun remove(anchor: OwnershipAnchor, routeId: String): ConnectionSnapshot {
            removals += anchor to routeId
            return snapshot(anchor)
        }
    }
}
