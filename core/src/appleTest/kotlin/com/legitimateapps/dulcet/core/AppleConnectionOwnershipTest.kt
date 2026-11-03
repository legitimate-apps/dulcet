package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Url
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
    @Test fun mp01DarwinTlsAndLineageAdmissionKeepUnverifiedCandidatesCredentialFree() = runBlocking {
        fixture { f ->
            val download = f.client()
            val playback = ApplePlaybackWireClient(f.account)
            try {
                assertNull(download.reconcile(f.partialIds, 0).errorKind)
                f.control("reset", "POST")
                val expected = ConnectionEnrollmentStore.lineage(f.anchor.serverIdentity)
                val candidate = TlsConnectionCandidateProbe()
                assertNull(candidate.verify(f.routeA, expected), "Trusted matching Darwin TLS positive control")
                assertIs<DomainError.Security.TlsUntrusted>(candidate.verify(f.route("bad-name"), expected),
                    "A trusted root with the wrong TLS name must fail before HTTP, without a replacement alert")
                assertEquals(DomainError.Protocol.ServerIdentityChanged, candidate.verify(f.route("different"), expected),
                    "The foreign-lineage discovery must actually be read and rejected before authentication")
                val enrolled = assertIs<ConnectionEnrollmentResult.Enrolled>(ConnectionEnrollmentService(f.store.database).enroll(
                    ConnectionEnrollmentRequest(f.anchor, AccountConnectionRequest(f.routeA, f.account.username, f.account.password)))).snapshot
                for (id in listOf("home", "remote")) assertTrue(enrolled.routes.single { it.id == id }.enabled,
                    "Matching TLS and lineage must admit the positive route: $id")
                for (id in listOf("different", "bad-name")) assertFalse(enrolled.routes.single { it.id == id }.enabled,
                    "A failed candidate cannot become a credential destination: $id")
                assertTrue(enrolled.routes.none { it.id == "unlisted" }, "A valid but unlisted listener is not a route")
                val admission = f.control("state")
                assertUnverifiedCandidatesCredentialFree(admission)
                val before = admission.getValue("requests").jsonArray.map { it.jsonObject }
                assertTrue(before.any { it.getValue("routeId").jsonPrimitive.content == "different" &&
                    it.getValue("path").jsonPrimitive.content.endsWith("/.well-known/dulcet") },
                    "The foreign-lineage public discovery negative control must execute")
                val remoteProbes = before.filter { it.getValue("routeId").jsonPrimitive.content == "remote" }
                assertTrue(remoteProbes.isNotEmpty(), "The admitted alternate must have an actual public probe")
                remoteProbes.forEach { assertCredentialFree(it) }
                val directory = before.filter { it.getValue("endpoint").jsonPrimitive.content == "getDulcetConnections" }
                assertTrue(directory.isNotEmpty(), "Darwin enrollment must fetch the authenticated bridge directory")
                directory.forEach {
                    assertEquals("POST", it.getValue("method").jsonPrimitive.content)
                    assertFalse(it.getValue("queryAuth").jsonPrimitive.boolean)
                    assertTrue(it.getValue("bodyAuth").jsonPrimitive.boolean, "Salted authentication belongs in the form body")
                }
                ConnectionEnrollmentStore(f.store.database).selectForFixture(f.anchor, enrolled.routes.single { it.id == "remote" }.id)
                val wire = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(f.anchor.normalizedBaseUrl,
                    f.account.username, f.account.password, true), "native.apple.admission")
                try {
                    assertEquals(200, wire.request("ping", emptyMap()).statusCode)
                    val prepared = wire.prepareGetRequest("stream", mapOf("id" to f.trackId))
                    val generation = assertNotNull(prepared.routeGeneration)
                    assertEquals("preserve", download.evaluateScopedRedirect(prepared.routeBaseUrl, generation,
                        prepared.url, prepared.url, 0).kind)
                    assertEquals("preserve", playback.evaluateScopedRedirect(prepared.routeBaseUrl, generation,
                        prepared.url, prepared.url, 0).kind)
                    // These exported native guards are the URLSession download / player boundary.
                    // Another admitted alias still needs a newly prepared request scoped to it.
                    for (resource in listOf(f.routeA + "/rest/stream.view", f.route("unlisted") + "/rest/stream.view",
                        f.routeB.replaceFirst("https:", "http:") + "/rest/stream.view",
                        f.routeB.substringBeforeLast('/') + "/outside/stream.view", f.routeB + "/%2e%2e/outside")) {
                        assertEquals("reject", download.evaluateScopedRedirect(prepared.routeBaseUrl, generation,
                            prepared.url, resource, 0).kind, "Download route guard rejects an origin, scheme or base escape")
                        assertEquals("reject", playback.evaluateScopedRedirect(prepared.routeBaseUrl, generation,
                            prepared.url, resource, 0).kind, "Player route guard rejects an origin, scheme or base escape")
                    }
                } finally { wire.close() }
                val dispatched = f.control("state")
                assertUnverifiedCandidatesCredentialFree(dispatched)
                assertTrue(dispatched.getValue("requests").jsonArray.map { it.jsonObject }.any {
                    it.getValue("routeId").jsonPrimitive.content == "remote" &&
                        it.getValue("endpoint").jsonPrimitive.content == "ping" &&
                        it.getValue("queryAuth").jsonPrimitive.boolean
                }, "An admitted alternate must actually serve the authenticated Darwin request")
                assertEquals(f.legacyHttp, f.account.normalizedBaseUrl, "Enrollment leaves ownership anchored")
            } finally { playback.close(); download.close() }
        }
    }

    private fun assertCredentialFree(receipt: JsonObject) {
        for (field in listOf("queryAuth", "bodyAuth", "authorization"))
            assertFalse(receipt.getValue(field).jsonPrimitive.boolean, "Unverified candidate received credential material: $field")
    }

    private fun assertUnverifiedCandidatesCredentialFree(state: JsonObject) {
        val counts = state.getValue("counts").jsonObject
        assertEquals(0, counts.getValue("bad-name").jsonPrimitive.int, "TLS name failure must prevent every HTTP request")
        assertEquals(0, counts.getValue("unlisted").jsonPrimitive.int, "Unlisted origin must receive no HTTP request")
        assertEquals(0, counts.getValue("legacy").jsonPrimitive.int, "HTTP anchor is never a candidate credential destination")
        state.getValue("requests").jsonArray.map { it.jsonObject }.forEach { receipt ->
            assertFalse(receipt.getValue("path").jsonPrimitive.content.contains("/dulcet/v1"),
                "Native HTTPS REST enrollment cannot bootstrap through direct API authentication")
            if (receipt.getValue("routeId").jsonPrimitive.content in setOf("different", "bad-name", "unlisted"))
                assertCredentialFree(receipt)
        }
    }

    @Test fun mp02HttpEnrollmentAndFactoryRouteChangePreserveAllOwnedRowsAndFiles() = runBlocking {
        fixture { f ->
            val client = f.client()
            try {
                assertNull(client.reconcile(f.partialIds, 0).errorKind)
                val rows = f.store.driver.connectionOwnershipSnapshot()
                val files = f.files()
                for (table in listOf("mutation_outbox", "wish_intent", "queue_entry", "my_library_item", "offline_media_retention"))
                    assertTrue(rows.getValue(table).isNotEmpty(), "Required preexisting ownership: $table")
                f.store.assertConnectionOwnershipMediaWitness(f.root)
                assertTrue(files.keys.any { it.endsWith("fixture-audio-v1.bin") },
                    "Valid completed audio must survive native startup before enrollment; files=${files.keys}")
                assertTrue(files.keys.any { it.endsWith(".partial") }, "Partial bytes must exist before HTTP enrollment")
                val epoch = f.store.database.libraryDepthQueries.selectEntry(f.anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne()
                val calls = mutableListOf<String>()
                OwnershipBindingProbe.observe { calls += it }
                val enrolled = assertIs<ConnectionEnrollmentResult.Enrolled>(ConnectionEnrollmentService(f.store.database).enroll(
                    ConnectionEnrollmentRequest(f.anchor, AccountConnectionRequest(f.routeA, f.account.username, f.account.password))))
                assertEquals(rows, f.store.driver.connectionOwnershipSnapshot(), "Enrollment preserves every prior owned row")
                assertEquals(files, f.files(), "Enrollment preserves every complete and partial byte")
                f.control("reset", "POST")
                val service = ConnectionEnrollmentService(f.store.database)
                val original = enrolled.snapshot.routes.single { it.baseUrl == f.routeA }.id
                val removed = service.remove(f.anchor, original)
                assertNull(removed.selectedRouteId, "Removing the selected connection leaves this account offline")
                assertNull(ConnectionRouteRegistry.leaseFor(f.anchor.normalizedBaseUrl, f.anchor.username))
                val verifyStart = f.control("state").getValue("requests").jsonArray.size
                val verified = assertIs<ConnectionEnrollmentResult.Enrolled>(service.enroll(ConnectionEnrollmentRequest(f.anchor,
                    AccountConnectionRequest(f.anchor.normalizedBaseUrl, f.account.username, f.account.password, true)))).snapshot
                assertNull(verified.selectedRouteId, "Verify cannot automatically select the remaining HTTPS route")
                assertTrue(verified.routes.single { it.id == original }.removed, "Refresh preserves the local removal")
                val verificationReceipts = f.control("state").getValue("requests").jsonArray.drop(verifyStart).map { it.jsonObject }
                assertTrue(verificationReceipts.any { it.getValue("routeId").jsonPrimitive.content == "remote" &&
                    it.getValue("endpoint").jsonPrimitive.content == "getDulcetConnections" &&
                    it.getValue("bodyAuth").jsonPrimitive.boolean }, "Darwin Verify after HTTP adoption actually reads its directory through admitted HTTPS")
                assertEquals(rows, f.store.driver.connectionOwnershipSnapshot(), "Removal and Verify preserve all owned rows")
                assertEquals(files, f.files(), "Removal and Verify preserve explicit/automatic complete and partial bytes")
                val readded = assertIs<ConnectionEnrollmentResult.Enrolled>(service.enroll(ConnectionEnrollmentRequest(f.anchor,
                    AccountConnectionRequest(f.routeA, f.account.username, f.account.password), explicitAdd = true))).snapshot
                assertEquals(original, readded.selectedRouteId, "Explicit re-add selects only the person's chosen HTTPS route")
                assertFalse(readded.routes.single { it.id == original }.removed)
                assertEquals(verified.routes.filter { it.id != original }.associate { it.id to it.removed },
                    readded.routes.filter { it.id != original }.associate { it.id to it.removed }, "Re-add clears only its chosen removal")
                assertEquals(rows, f.store.driver.connectionOwnershipSnapshot(), "Explicit recovery preserves all owned rows")
                assertEquals(files, f.files(), "Explicit recovery preserves complete and partial bytes")
                assertEquals(f.anchor, readded.anchor)
                assertEquals(0, f.control("state").getValue("counts").jsonObject.getValue("legacy").jsonPrimitive.int,
                    "The frozen HTTP anchor is never dialed during removal, Verify or explicit recovery")
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
        // The released-schema fixture includes example route configuration for a different
        // generic topology. Replace only that configuration before seeding this fresh fixture;
        // every owned catalogue/library/mutation/download row remains the preexisting witness.
        store.database.connectionsQueries.deleteRoutes(anchor.accountId)
        store.database.connectionsQueries.deleteEnrollment(anchor.accountId)
        store.database.libraryDepthQueries.putEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY,
            encodeMediaBinding(DownloadMediaBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username)), 0)
        ServerIdentityStore(store.database).accept(anchor.accountId, anchor.serverIdentity)
        val track = doc.getValue("tracks").jsonArray.first().jsonObject
        materializeConnectionOwnershipMedia(store, root, routeA, account.username, account.password,
            track.getValue("id").jsonPrimitive.content, track.getValue("sourceSha256").jsonPrimitive.content)
        val downloads = store.database.downloadsQueries.selectDownloadsForServer(anchor.accountId).executeAsList()
        val partialIds = downloads.filter { it.state !in setOf("complete", "stale") }.map { it.download_id }
        FileSystem.SYSTEM.createDirectories(root / ".tmp")
        downloads.filter { it.download_id in partialIds }.forEach {
            FileSystem.SYSTEM.copy(root / it.file_relative_path, root / ".tmp/${it.download_id}.partial")
        }
        val f = Fixture(name, root, store, anchor, account, routeA, routeB, value("legacyHttp"), partialIds, doc)
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
        val routeA: String, val routeB: String, val legacyHttp: String, val partialIds: List<String>, val manifest: JsonObject) {
        val trackId: String get() = manifest.getValue("tracks").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
        fun route(id: String): String = manifest.getValue("routes").jsonObject.getValue(id).jsonPrimitive.content.also {
            val url = Url(it)
            assertEquals("https", url.protocol.name)
            assertEquals("localhost", url.host, "A candidate must belong to the disposable local fixture")
            assertNull(url.user); assertNull(url.password)
        }
        suspend fun control(path: String, method: String = "GET"): JsonObject {
            val base = manifest.getValue("controlBase").jsonPrimitive.content
            val url = Url(base)
            assertEquals("http", url.protocol.name); assertEquals("localhost", url.host)
            assertNull(url.user); assertNull(url.password)
            assertTrue(base.endsWith("/__dulcet/fixture"))
            val nonce = manifest.getValue("nonce").jsonPrimitive.content
            assertTrue(nonce.isNotBlank())
            val client = createAccountHttpClient(AccountClientTransport.Default()) {
                expectSuccess = false; followRedirects = false
                install(HttpTimeout) { requestTimeoutMillis = 5_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 5_000 }
            }
            return try {
                val response = client.request("$base/$path") {
                    this.method = HttpMethod.parse(method)
                    headers.append("X-Dulcet-Fixture", nonce)
                }
                assertEquals(200, response.status.value)
                Json.parseToJsonElement(response.bodyAsText()).jsonObject.also {
                    assertEquals(nonce, it.getValue("nonce").jsonPrimitive.content)
                }
            } finally { client.close() }
        }
        fun client() = AppleDownloadClient(account, name, root.toString())
        fun files(): Map<String, String> = FileSystem.SYSTEM.listRecursively(root)
            .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }.associate {
                it.relativeTo(root).toString() to FileSystem.SYSTEM.read(it) { readByteString().sha256().hex() }
            }
    }
}
