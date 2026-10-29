package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConnectionEnrollmentRecoveryTest {
    @Test fun freshAcceptedAccountInitializesANonceBeforeItsFirstDirectoryWithoutPreseededMedia() = runTest {
        fixture(fresh = true) { f ->
            assertNull(f.epoch())
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request()))
            assertTrue(f.authenticated.isEmpty(), "The strict fence rejects the missing epoch before credentials")
            assertTrue(initializeFreshConnectionOwnership(f.store.database, f.anchor))
            val epoch = assertNotNull(f.epoch())
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            assertEquals(epoch, f.epoch())
            assertFalse(initializeFreshConnectionOwnership(f.store.database, f.anchor))
            assertEquals(epoch, f.epoch(), "Initialization never rotates existing ownership")
        }
    }

    @Test fun freshInitializationRefusesExistingOwnedRowsAndMismatchedIdentityWithoutMutation() = runTest {
        fixture(fresh = true) { f ->
            f.store.database.libraryDepthQueries.putEntry(f.anchor.accountId, "pending.intent", "opaque", "keep", 1)
            assertFalse(initializeFreshConnectionOwnership(f.store.database, f.anchor))
            assertNull(f.epoch())
            assertEquals("keep", f.store.database.libraryDepthQueries.selectEntry(f.anchor.accountId, "pending.intent", "opaque").executeAsOne())
            assertFalse(initializeFreshConnectionOwnership(f.store.database, f.anchor.copy(serverIdentity = "foreign")))
        }
    }

    @Test fun verifyAfterHttpAdoptionUsesHttpsAndSelectedRemovalRefreshDoesNotSelectAnAlternate() = runTest {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.clearReceipts()
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            assertEquals(listOf(f.home), f.authenticated)
            assertEquals(listOf(f.home), f.fetched)
            f.service.remove(f.anchor, "home")
            f.clearReceipts()
            val refreshed = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request())).snapshot
            assertEquals(listOf(f.remote), f.authenticated)
            assertEquals(listOf(f.remote), f.fetched)
            assertNull(refreshed.selectedRouteId, "A refresh is not an automatic recovery selection")
            assertTrue(refreshed.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, refreshed.anchor)
        }
    }

    @Test fun explicitAddAuthenticatesChosenListedRouteAfterAdmissionAndKeepsOtherTombstones() = runTest {
        fixture { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            f.service.remove(f.anchor, "home")
            f.clearReceipts()
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true))).snapshot
            assertEquals(listOf(f.remote, f.home), f.authenticated)
            assertTrue(f.events.indexOf("probe:${f.home}") < f.events.indexOf("auth:${f.home}"))
            assertEquals("home", recovered.selectedRouteId)
            assertFalse(recovered.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, recovered.anchor)
        }
    }

    @Test fun unlistedExplicitAddNeverReceivesCredentialsAndDoesNotChangeSelection() = runTest {
        fixture { f ->
            val before = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request())).snapshot
            f.clearReceipts()
            val unknown = "https://unlisted.example.test/music"
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request(unknown, explicit = true)))
            assertEquals(listOf(f.home), f.authenticated)
            assertFalse(f.events.any { it.endsWith(unknown) })
            assertEquals(before, f.service.snapshot(f.anchor))
        }
    }

    @Test fun lastRemovalOnlyExplicitKnownHistoricalHttpsCanRecoverAndOnlyChosenRemovalIsCleared() = runTest {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.service.remove(f.anchor, "home"); f.service.remove(f.anchor, "remote")
            f.clearReceipts()
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request()))
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request("https://unknown.example.test/music", explicit = true)))
            assertTrue(f.authenticated.isEmpty())
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.remote, explicit = true))).snapshot
            assertEquals("probe:${f.remote}", f.events.first())
            assertEquals(listOf(f.remote), f.authenticated)
            assertEquals("remote", recovered.selectedRouteId)
            assertTrue(recovered.routes.single { it.id == "home" }.removed)
            assertFalse(recovered.routes.single { it.id == "remote" }.removed)
            assertEquals(f.anchor, recovered.anchor)
        }
    }

    private suspend fun fixture(http: Boolean = false, fresh: Boolean = false, block: suspend (Fixture) -> Unit) {
        val store = DulcetDatabaseStore.open(createTestDriver())
        val f = Fixture(store, http)
        try {
            ServerIdentityStore(store.database).accept(f.anchor.accountId, f.anchor.serverIdentity)
            if (!fresh) assertTrue(initializeFreshConnectionOwnership(store.database, f.anchor))
            block(f)
        } finally { ConnectionRouteRegistry.forget(f.anchor.accountId); store.close() }
    }

    private class Fixture(val store: DulcetDatabaseStore, http: Boolean) {
        val home = "https://home.example.test/music"
        val remote = "https://remote.example.test/music"
        val anchor = OwnershipAnchor("recovery-fixture", if (http) "http://legacy.example.test/music" else home,
            "listener", ServerIdentityPolicy.identity("dulcet", "lineage"))
        val authenticated = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        val events = mutableListOf<String>()
        fun epoch() = store.database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOneOrNull()
        fun clearReceipts() { authenticated.clear(); fetched.clear(); events.clear() }
        fun request(url: String = anchor.normalizedBaseUrl, explicit: Boolean = false) = ConnectionEnrollmentRequest(anchor,
            AccountConnectionRequest(url, anchor.username, "synthetic", false), explicit)
        val service = ConnectionEnrollmentService(store.database, connect = { request ->
            authenticated += request.serverUrl; events += "auth:${request.serverUrl}"
            AccountConnectionResult.Connected(ConnectedAccount(request.serverUrl, false, "1.16.1", true, "dulcet", "fixture",
                CapabilitySet(mapOf("dulcetRoutes" to setOf(1)), UserPermissions(true, true, false, false, false), false), emptyList(),
                anchor.username, anchor.serverIdentity))
        }, probe = ConnectionCandidateProbe { url, _ -> events += "probe:$url"; null }, fetch = { request ->
            fetched += request.serverUrl
            val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","dulcetConnections":{"version":1,"revision":1,"instanceId":"lineage","principal":{"id":"user:1","username":"listener"},"routes":[{"id":"home","baseUrl":"$home","role":"private","priority":10},{"id":"remote","baseUrl":"$remote","role":"public","priority":20}],"legacyOwnershipAnchors":["http://legacy.example.test/music"],"authAliases":[]}}}""".encodeToByteArray()
            AuthenticatedEndpointResponse(200, body, "<redacted>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
                RequestTrace.observed("getDulcetConnections", "POST", "<redacted>", AuthenticationLocation.FormBody,
                    emptySet(), emptySet(), emptySet(), "1.16.1", "fixture"))
        })
    }
}
