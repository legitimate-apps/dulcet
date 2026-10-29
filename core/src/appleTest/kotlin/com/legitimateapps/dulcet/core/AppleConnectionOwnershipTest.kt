package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import kotlin.test.*

/** Real Darwin transport, native SQLite and the exported Apple media executor facade. */
@OptIn(ExperimentalForeignApi::class)
class AppleConnectionOwnershipTest {
    @Test fun mp02HttpEnrollmentAndFactoryRouteChangePreserveAllOwnedRowsAndFiles() = runBlocking {
        fixture { f ->
            val client = f.client()
            try {
                assertNull(client.reconcile(f.partialIds, 0).errorKind)
                val rows = f.store.driver.connectionOwnershipSnapshot()
                val files = f.files()
                for (table in listOf("mutation_outbox", "wish_intent", "queue_entry", "my_library_item", "offline_media_retention"))
                    assertTrue(rows.getValue(table).isNotEmpty(), "Required preexisting ownership: $table")
                assertTrue(files.keys.any { it.endsWith("fixture-audio-v1.bin") })
                assertTrue(files.keys.any { it.endsWith(".partial") }, "Partial bytes must exist before HTTP enrollment")
                val epoch = f.store.database.libraryDepthQueries.selectEntry(f.anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne()
                val calls = mutableListOf<String>()
                OwnershipBindingProbe.observe { calls += it }
                val enrolled = assertIs<ConnectionEnrollmentResult.Enrolled>(ConnectionEnrollmentService(f.store.database).enroll(
                    ConnectionEnrollmentRequest(f.anchor, AccountConnectionRequest(f.routeA, f.account.username, f.account.password))))
                assertEquals(rows, f.store.driver.connectionOwnershipSnapshot(), "Enrollment preserves every prior owned row")
                assertEquals(files, f.files(), "Enrollment preserves every complete and partial byte")
                val wire = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(f.anchor.normalizedBaseUrl,
                    f.account.username, f.account.password, true), "native.apple.connections")
                val alternate = enrolled.snapshot.routes.single { it.baseUrl == f.routeB }.id
                try {
                    assertEquals(200, wire.request("ping", emptyMap()).statusCode)
                    ConnectionEnrollmentStore(f.store.database).selectForFixture(f.anchor, alternate)
                    assertEquals(200, wire.request("ping", emptyMap()).statusCode)
                } finally { wire.close() }
                assertEquals(emptyList(), calls, "A route change must not enter any ownership bind or purge")
                assertEquals(rows, f.store.driver.connectionOwnershipSnapshot())
                assertEquals(files, f.files())
                assertEquals(epoch, f.store.database.libraryDepthQueries.selectEntry(f.anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne())
                assertEquals(f.legacyHttp, f.account.normalizedBaseUrl)
                OwnershipBindingProbe.observe(null)
                client.close()
                assertEquals(alternate, ConnectionEnrollmentStore(f.store.database).restore(f.anchor).selectedRouteId)
                val reopened = f.client()
                try {
                    assertNull(reopened.reconcile(f.partialIds, 0).errorKind)
                    assertEquals(files, f.files(), "Native relaunch keeps completed and retained partial bytes")
                    val after = f.store.driver.connectionOwnershipSnapshot()
                    for (table in listOf("mutation_outbox", "wish_intent", "queue_entry", "offline_media_retention"))
                        assertEquals(rows.getValue(table), after.getValue(table), "Relaunch retained $table")
                } finally { reopened.close() }
            } finally { client.close() }
        }
    }

    @Test fun mp10RevokedPreparedRequestsAndRetiredHttpTasksStayClosed() = runBlocking {
        fixture { f ->
            val download = f.client()
            val playback = ApplePlaybackWireClient(f.account)
            try {
                assertNull(download.reconcile(f.partialIds, 0).errorKind)
                assertIs<ConnectionEnrollmentResult.Enrolled>(ConnectionEnrollmentService(f.store.database).enroll(
                    ConnectionEnrollmentRequest(f.anchor, AccountConnectionRequest(f.routeA, f.account.username, f.account.password))))
                assertFalse(download.allowsRestoredRequest("${f.legacyHttp}/rest/download.view?id=old"), "Opaque old HTTP OS task must be cancelled")
                val wire = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(f.anchor.normalizedBaseUrl,
                    f.account.username, f.account.password, true), "native.apple.revoked")
                try {
                    val prepared = wire.prepareGetRequest("stream", mapOf("id" to "fixture-track"))
                    val generation = assertNotNull(prepared.routeGeneration)
                    assertTrue(download.isPreparedRequestCurrent(prepared.routeBaseUrl, generation))
                    assertTrue(playback.isPreparedRequestCurrent(prepared.routeBaseUrl, generation))
                    ConnectionRouteRegistry.publish(f.anchor, null)
                    assertFalse(download.isPreparedRequestCurrent(prepared.routeBaseUrl, generation))
                    assertFalse(playback.isPreparedRequestCurrent(prepared.routeBaseUrl, generation))
                    assertEquals("reject", download.evaluateScopedRedirect(prepared.routeBaseUrl, generation,
                        prepared.url, prepared.url, 0).kind)
                    assertEquals("reject", playback.evaluateScopedRedirect(prepared.routeBaseUrl, generation,
                        prepared.url, prepared.url, 0).kind)
                    assertFalse(download.allowsRestoredRequest(prepared.url))
                    assertFailsWith<AuthenticatedEndpointFailure> { wire.prepareGetRequest("stream", emptyMap()) }
                } finally { wire.close() }
            } finally { playback.close(); download.close() }
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val env = NSProcessInfo.processInfo.environment
        val manifest = requireNotNull(env["DULCET_MULTIPATH_MANIFEST"] as? String) { "Disposable TLS fixture manifest required" }
        val seed = requireNotNull(env["DULCET_CONNECTIONS_SEED"] as? String).toPath()
        val doc = Json.parseToJsonElement(FileSystem.SYSTEM.read(manifest.toPath()) { readUtf8() }).jsonObject
        assertEquals("dulcet-multipath-fixture", doc.getValue("standard").jsonPrimitive.content)
        assertTrue(doc.getValue("disposable").jsonPrimitive.boolean)
        fun value(key: String) = doc.getValue(key).jsonPrimitive.content
        val routeA = value("routeA"); val routeB = value("routeB")
        for (base in listOf(routeA, routeB)) assertTrue(base.startsWith("https://localhost:"), "No personal/production upstream")
        assertTrue(value("legacyHttp").startsWith("http://localhost:"))
        val connected = assertIs<AccountConnectionResult.Connected>(AccountConnector().connect(
            AccountConnectionRequest(routeA, value("username"), value("password"))))
        val name = "native-connections-${NSUUID().UUIDString}.db"
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "native-connections-${NSUUID().UUIDString}"
        val initial = DulcetDriverFactory(name).openDulcetDatabase()
        val databasePath = initial.driver.executeQuery(null, "PRAGMA database_list", { c ->
            check(c.next().value); QueryResult.Value(requireNotNull(c.getString(2)))
        }, 0).value
        initial.close()
        listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(databasePath + it, null) }
        FileSystem.SYSTEM.copy(seed / "database.db", databasePath.toPath())
        copyDirectory(seed / "files", root)
        val store = DulcetDriverFactory(name).openDulcetDatabase()
        val anchor = OwnershipAnchor("server:fixture-alpha", value("legacyHttp"), value("username"), connected.account.serverIdentity)
        val account = PlaybackEndpointAccount(anchor.accountId, anchor.normalizedBaseUrl, anchor.username, value("password"), true)
        store.database.libraryDepthQueries.putEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY,
            encodeMediaBinding(DownloadMediaBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username)), 0)
        ServerIdentityStore(store.database).accept(anchor.accountId, anchor.serverIdentity)
        val downloads = store.database.downloadsQueries.selectDownloadsForServer(anchor.accountId).executeAsList()
        val partialIds = downloads.filter { it.state !in setOf("complete", "stale") }.map { it.download_id }
        FileSystem.SYSTEM.createDirectories(root / ".tmp")
        downloads.filter { it.download_id in partialIds }.forEach {
            FileSystem.SYSTEM.copy(root / it.file_relative_path, root / ".tmp/${it.download_id}.partial")
        }
        val f = Fixture(name, root, store, anchor, account, routeA, routeB, value("legacyHttp"), partialIds)
        try { block(f) }
        finally {
            OwnershipBindingProbe.observe(null); ConnectionRouteRegistry.forget(anchor.accountId); store.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
            listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(databasePath + it, null) }
        }
    }
    private fun copyDirectory(source: Path, destination: Path) {
        FileSystem.SYSTEM.createDirectories(destination)
        FileSystem.SYSTEM.list(source).forEach {
            if (FileSystem.SYSTEM.metadata(it).isDirectory) copyDirectory(it, destination / it.name)
            else FileSystem.SYSTEM.copy(it, destination / it.name)
        }
    }
    private class Fixture(val name: String, val root: Path, val store: DulcetDatabaseStore,
        val anchor: OwnershipAnchor, val account: PlaybackEndpointAccount,
        val routeA: String, val routeB: String, val legacyHttp: String, val partialIds: List<String>) {
        fun client() = AppleDownloadClient(account, name, root.toString())
        fun files(): Map<String, String> = FileSystem.SYSTEM.listRecursively(root)
            .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }.associate {
                it.relativeTo(root).toString() to FileSystem.SYSTEM.read(it) { readByteString().sha256().hex() }
            }
    }
}
