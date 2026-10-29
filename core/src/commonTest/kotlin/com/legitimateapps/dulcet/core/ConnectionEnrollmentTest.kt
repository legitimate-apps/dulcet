package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConnectionEnrollmentTest {
    private val anchor = OwnershipAnchor("route-test-account", "https://home.example.test/music", "listener", ServerIdentityPolicy.identity("dulcet", "lineage"))
    private fun directory(revision: Long = 7, username: String = "listener", instance: String = "lineage", extra: String = "", legacy: String = "[]"): ByteArray = """{"subsonic-response":{"status":"ok","version":"1.16.1","dulcetConnections":{"version":1,"revision":$revision,"instanceId":"$instance","principal":{"id":"user:1","username":"$username"},"routes":[{"id":"home","baseUrl":"https://home.example.test/music","role":"private","priority":10},{"id":"remote","baseUrl":"https://remote.example.test/music","role":"public","priority":20}$extra],"legacyOwnershipAnchors":$legacy,"authAliases":[{"routeId":"home","issuer":"https://home.example.test/music","tokenEndpoint":"https://home.example.test/music/oauth/token"}]}}}""".encodeToByteArray()
    private fun parse(bytes: ByteArray = directory()) = ConnectionDirectoryParser.parse(bytes, "lineage", "listener")
    private fun withStore(block: (ConnectionEnrollmentStore, SeenCacheTestHandle) -> Unit) = withSeenCache { env, _ ->
        try { block(ConnectionEnrollmentStore(env.database), env) } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    @Test fun directoryRejectsWrongScopeRollbackVersionsAndMalformedPayloads() {
        assertEquals(7, parse().revision)
        listOf(directory(username = "Listener"), directory(instance = "copied"),
            directory().decodeToString().replace("\"version\":1,", "\"version\":2,").encodeToByteArray(),
            """{"subsonic-response":{"status":"ok"}}""".encodeToByteArray(),
            directory().decodeToString().replace("https://remote.example.test/music", "https://remote.example.test/music/../other").encodeToByteArray(),
            directory().decodeToString().replace("\"id\":\"remote\"", "\"id\":\"home\"").encodeToByteArray()
        ).forEach { assertFailsWith<ConnectionEnrollmentFailure> { parse(it) } }
        assertEquals(DomainError.Protocol.TooLarge,
            assertFailsWith<ConnectionEnrollmentFailure> { parse(ByteArray(65_537)) }.error)
    }

    @Test fun httpAliasIsIgnoredEvenWhenConsentExistsAndIsNeverProbed() = runTest {
        withStore { store, env ->
            val parsed = parse(directory(extra = """,{"id":"http","baseUrl":"http://192.168.1.2/music","role":"local","priority":0}"""))
            assertEquals(listOf("home", "remote"), parsed.routes.map { it.id })
            val snapshot = store.commit(anchor, parsed, anchor.normalizedBaseUrl, setOf("home"), 123)
            assertFalse(snapshot.routes.any { it.baseUrl.startsWith("http:") })
            assertFalse(ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username)!!.baseUrl.startsWith("http:"))
        }
    }

    @Test fun routeChangeAndRelaunchPreserveAnchorOwnedRowsAndTombstones() = withStore { store, env ->
        env.bind(CacheBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username))
        val original = env.database.seenCacheQueries.selectBinding(anchor.accountId).executeAsOne()
        env.database.libraryDepthQueries.putEntry(anchor.accountId, "test.outbox", "pending", "operation:original", 9)
        val dir = parse()
        store.commit(anchor, dir, anchor.normalizedBaseUrl, setOf("home", "remote"), 123)
        store.selectForFixture(anchor, "remote")
        assertEquals("https://remote.example.test/music", ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username)?.baseUrl)
        assertEquals(original, env.database.seenCacheQueries.selectBinding(anchor.accountId).executeAsOne())
        assertEquals("operation:original", env.database.libraryDepthQueries.selectEntry(anchor.accountId, "test.outbox", "pending").executeAsOne())
        ConnectionRouteRegistry.forget(anchor.accountId)
        val restored = ConnectionEnrollmentStore(env.database).restore(anchor)
        assertEquals("remote", restored.selectedRouteId)
        assertEquals(anchor, restored.anchor)
        assertFalse(env.bind(CacheBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username)).purgedOnBind)
        store.remove(anchor, "remote")
        assertTrue(ConnectionRouteRegistry.isEnrolled(anchor.normalizedBaseUrl, anchor.username))
        assertNull(ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username))
        val refreshed = store.commit(anchor, parse(directory(revision = 8)), anchor.normalizedBaseUrl, setOf("home", "remote"), 124)
        assertTrue(refreshed.routes.single { it.id == "remote" }.removed)
        assertFalse(refreshed.routes.single { it.id == "remote" }.enabled)
        store.remove(anchor, "home")
        ConnectionEnrollmentStore(env.database).restore(anchor)
        assertNull(ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username))
    }

    @Test fun rollbackAndPrincipalMismatchLeaveAcceptedDirectoryIntact() = withStore { store, _ ->
        val before = store.commit(anchor, parse(), anchor.normalizedBaseUrl, setOf("home", "remote"), 100)
        assertFailsWith<ConnectionEnrollmentFailure> { store.commit(anchor, parse(directory(revision = 6)), anchor.normalizedBaseUrl, setOf("home"), 101) }
        val other = parse(directory(revision = 8).decodeToString().replace("user:1", "user:2").encodeToByteArray())
        assertFailsWith<ConnectionEnrollmentFailure> { store.commit(anchor, other, anchor.normalizedBaseUrl, setOf("home"), 101) }
        assertEquals(before, store.restore(anchor))
    }

    @Test fun abortedEnrollmentTransactionRestoresPriorRoutesAndSelection() = withStore { store, env ->
        val before = store.commit(anchor, parse(), anchor.normalizedBaseUrl, setOf("home", "remote"), 100)
        env.driverForTest().execute(null, "CREATE TRIGGER reject_route_update BEFORE INSERT ON server_route WHEN NEW.route_id = 'remote' BEGIN SELECT RAISE(ABORT, 'fixture crash'); END", 0)
        assertFails { store.commit(anchor, parse(directory(revision = 8)), anchor.normalizedBaseUrl, setOf("home", "remote"), 101) }
        assertEquals(before, store.restore(anchor))
        env.driverForTest().execute(null, "DROP TRIGGER reject_route_update", 0)
        assertEquals(8, store.commit(anchor, parse(directory(revision = 8)), anchor.normalizedBaseUrl, setOf("home"), 101).revision)
    }

    @Test fun httpsAdoptionKeepsTheHttpAnchorAndNeverFallsBackToIt() = withStore { store, _ ->
        val old = anchor.copy(normalizedBaseUrl = "http://192.168.1.2/music")
        assertFailsWith<ConnectionEnrollmentFailure> { store.commit(old, parse(), anchor.normalizedBaseUrl, setOf("home"), 100) }
        assertFalse(ConnectionRouteRegistry.isEnrolled(old.normalizedBaseUrl, old.username))
        val attested = parse(directory(legacy = "[\"http://192.168.1.2/music\"]"))
        val upgraded = store.commit(old, attested, anchor.normalizedBaseUrl, setOf("home"), 100)
        assertEquals(old, upgraded.anchor)
        assertEquals(anchor.normalizedBaseUrl, ConnectionRouteRegistry.leaseFor(old.normalizedBaseUrl, old.username)?.baseUrl)
        store.remove(old, "home")
        assertTrue(ConnectionRouteRegistry.isEnrolled(old.normalizedBaseUrl, old.username))
        assertNull(ConnectionRouteRegistry.leaseFor(old.normalizedBaseUrl, old.username))
    }

    @Test fun sameRevisionCannotChangeDirectoryAuthorization() = withStore { store, _ ->
        val before = store.commit(anchor, parse(), anchor.normalizedBaseUrl, setOf("home"), 100)
        val edited = parse(directory().decodeToString().replace("remote.example.test", "unlisted.example.test").encodeToByteArray())
        assertFailsWith<ConnectionEnrollmentFailure> { store.commit(anchor, edited, anchor.normalizedBaseUrl, setOf("home", "remote"), 101) }
        assertEquals(before, store.restore(anchor))
    }

    @Test fun removedOrReplacedOwnershipCannotCommitAnInFlightEnrollment() = withStore { store, env ->
        env.bind(CacheBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username))
        ServerIdentityStore(env.database).accept(anchor.accountId, anchor.serverIdentity)
        val fence = ConnectionOwnershipFence.capture(env.database, anchor)
        env.database.serverDataQueries.deleteSeenCacheForServer(anchor.accountId)
        assertFailsWith<ConnectionEnrollmentFailure> {
            store.commit(anchor, parse(), anchor.normalizedBaseUrl, setOf("home"), 100, fence)
        }
        assertFalse(store.snapshot(anchor).enrolled)
        assertFalse(ConnectionRouteRegistry.isEnrolled(anchor.normalizedBaseUrl, anchor.username))
        env.bind(CacheBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username))
        ServerIdentityStore(env.database).accept(anchor.accountId, ServerIdentityPolicy.identity("dulcet", "replacement"))
        assertFailsWith<ConnectionEnrollmentFailure> {
            store.commit(anchor, parse(), anchor.normalizedBaseUrl, setOf("home"), 100, fence)
        }
        assertFalse(store.snapshot(anchor).enrolled)
    }

    @Test fun losingOrChangingAcceptedIdentityDisablesAnExistingRoutePublication() = withStore { store, _ ->
        store.commit(anchor, parse(), anchor.normalizedBaseUrl, setOf("home"), 100)
        assertFailsWith<ConnectionEnrollmentFailure> { store.restore(anchor.copy(serverIdentity = ServerIdentityPolicy.identity("dulcet", "other"))) }
        assertTrue(ConnectionRouteRegistry.isEnrolled(anchor.normalizedBaseUrl, anchor.username))
        assertNull(ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username))
    }
}
