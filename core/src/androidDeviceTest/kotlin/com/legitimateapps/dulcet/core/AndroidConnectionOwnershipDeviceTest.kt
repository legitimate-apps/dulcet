package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Runs on ART with Android SQLite, native media datasource, worker and artwork engines. */
@RunWith(AndroidJUnit4::class)
class AndroidConnectionOwnershipDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test fun mp02FactoryRouteChangeKeepsExplicitAndAutomaticFilesAndAllRows() = runBlocking {
        val fixture = fixture()
        val seed = File(requireNotNull(arguments.getString("DULCET_CONNECTIONS_SEED")))
        assertTrue(File(seed, "database.db").isFile, "A public synthetic owned-state fixture is required")
        val name = "connection-no-loss-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, "connection-no-loss-${UUID.randomUUID()}").apply { mkdirs() }
        context.getDatabasePath(name).also { it.parentFile!!.mkdirs(); File(seed, "database.db").copyTo(it) }
        File(seed, "files").copyRecursively(root)
        val anchor = OwnershipAnchor("server:fixture-alpha", fixture.legacyHttp, fixture.username, fixture.identity)
        val account = PlaybackEndpointAccount(anchor.accountId, anchor.normalizedBaseUrl, anchor.username, fixture.password, true)
        val store = DulcetDriverFactory(context, name).openDulcetDatabase()
        val files = DownloadFileStore(root.path, FileSystem.SYSTEM).also { it.ensureDirectories() }
        // The released-schema fixture includes example route configuration for a different
        // generic topology. Replace only that configuration before seeding this fresh fixture;
        // every owned catalogue/library/mutation/download row remains the preexisting witness.
        store.database.connectionsQueries.deleteRoutes(anchor.accountId)
        store.database.connectionsQueries.deleteEnrollment(anchor.accountId)
        store.database.libraryDepthQueries.putEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY,
            encodeMediaBinding(DownloadMediaBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username)), 0)
        ServerIdentityStore(store.database).accept(anchor.accountId, anchor.serverIdentity)
        materializeConnectionOwnershipMedia(store, root.path.toPath(), fixture.routeA, fixture.username,
            fixture.password, fixture.trackId, fixture.sha256)
        // Preserve partials under the executor's real temporary names, not only fixture filenames.
        store.database.downloadsQueries.selectDownloadsForServer(anchor.accountId).executeAsList().forEach { row ->
            if (row.state !in setOf("complete", "stale")) {
                val original = File(root, row.file_relative_path)
                if (original.isFile) original.copyTo(File(root, ".tmp/${row.download_id}.partial"), overwrite = true)
            }
        }
        // Match this native adapter's existing credential generation before startup; a password
        // refresh is separate from the route transition being measured. Unknown library detail
        // cannot authorize dropping automatic retention during this offline-owned-state proof.
        store.driver.execute(null, "UPDATE download_policy_state SET auth_generation = 1", 0)
        store.driver.execute(null, "UPDATE my_library_state SET complete = 0", 0)
        val partialIds = store.database.downloadsQueries.selectDownloadsForServer(anchor.accountId).executeAsList()
            .filter { it.state !in setOf("complete", "stale") }.map { it.download_id }
        val tasks = Tasks().also { it.started.addAll(partialIds) }
        val controller = controller(account, name, root, tasks)
        val calls = CopyOnWriteArrayList<String>()
        try {
            assertTrue(controller.awaitReconciled(), "Native startup must reconcile the valid owned-media seed")
            controller.reconcileMyLibrary(allowExpansion = false)
            val before = store.driver.connectionOwnershipSnapshot()
            val byteWitness = fileWitness(root)
            assertTrue(before.getValue("mutation_outbox").isNotEmpty())
            assertTrue(before.getValue("wish_intent").isNotEmpty())
            assertTrue(before.getValue("queue_entry").isNotEmpty())
            assertTrue(before.getValue("my_library_item").isNotEmpty())
            assertTrue(before.getValue("offline_media_retention").any { it.contains("1") })
            store.assertConnectionOwnershipMediaWitness(root.path.toPath())
            assertTrue(File(root, "downloads/fixture-audio-v1.bin").isFile,
                "Valid completed audio must survive native startup before enrollment; files=${byteWitness.keys}")
            assertEquals(fixture.sha256, sha(File(root, "downloads/fixture-audio-v1.bin").readBytes()),
                "The completed native witness must retain actual disposable fixture audio")
            assertTrue(File(root, ".tmp").listFiles().orEmpty().isNotEmpty(), "Partial bytes must exist at switch")
            val epoch = store.database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne()
            OwnershipBindingProbe.observe { calls += it }
            val enrolled = assertIs<ConnectionEnrollmentResult.Enrolled>(ConnectionEnrollmentService(store.database).enroll(
                ConnectionEnrollmentRequest(anchor, AccountConnectionRequest(fixture.routeA, fixture.username, fixture.password))))
            assertEquals(before, store.driver.connectionOwnershipSnapshot(), "HTTP upgrade preserves every preexisting owned row")
            assertEquals(byteWitness, fileWitness(root), "HTTP upgrade preserves completed and partial bytes")
            val alternateId = enrolled.snapshot.routes.single { it.baseUrl == fixture.routeB }.id
            ConnectionEnrollmentStore(store.database).restore(anchor)
            val client = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(account.normalizedBaseUrl,
                account.username, account.password, true), "connection.no-loss")
            try {
                assertEquals(200, client.request("ping", emptyMap()).statusCode)
                ConnectionEnrollmentStore(store.database).selectForFixture(anchor, alternateId)
                assertEquals(200, client.request("ping", emptyMap()).statusCode)
            } finally { client.close() }
            assertEquals(emptyList(), calls, "Factory switching cannot even enter a normal ownership bind")
            assertEquals(before, store.driver.connectionOwnershipSnapshot(), "Every owned row and operation identity must be unchanged")
            assertEquals(byteWitness, fileWitness(root), "Complete and partial file hashes must be unchanged")
            assertEquals(epoch, store.database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne())
            assertEquals(fixture.legacyHttp, account.normalizedBaseUrl)
            OwnershipBindingProbe.observe(null)
            // Reopen the actual adapter using the original account, never the selected route URL.
            controller.close()
            assertEquals(alternateId, ConnectionEnrollmentStore(store.database).restore(anchor).selectedRouteId)
            val reopened = controller(account, name, root, tasks)
            try {
                assertTrue(reopened.awaitReconciled()); reopened.reconcileMyLibrary(allowExpansion = false)
                assertEquals(byteWitness, fileWitness(root), "Relaunch keeps all completed and retained partial bytes")
                assertEquals(before.getValue("mutation_outbox"), store.driver.connectionOwnershipSnapshot().getValue("mutation_outbox"))
                assertEquals(before.getValue("wish_intent"), store.driver.connectionOwnershipSnapshot().getValue("wish_intent"))
                assertEquals(before.getValue("queue_entry"), store.driver.connectionOwnershipSnapshot().getValue("queue_entry"))
            } finally { reopened.close() }
        } finally {
            OwnershipBindingProbe.observe(null); ConnectionRouteRegistry.forget(anchor.accountId)
            controller.close(); store.close(); context.deleteDatabase(name); root.deleteRecursively()
        }
    }

    @Test fun mp10PreservingHttpsEnrollmentNeverDialsHttpInPlayerDownloadArtwork() = runBlocking {
        val fixture = fixture()
        val name = "connection-upgrade-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, "connection-upgrade-${UUID.randomUUID()}").apply { mkdirs() }
        val anchor = OwnershipAnchor("upgrade:${UUID.randomUUID()}", fixture.legacyHttp, fixture.username, fixture.identity)
        val account = PlaybackEndpointAccount(anchor.accountId, anchor.normalizedBaseUrl, anchor.username, fixture.password, true)
        val store = DulcetDriverFactory(context, name).openDulcetDatabase()
        ServerIdentityStore(store.database).accept(anchor.accountId, anchor.serverIdentity)
        val tasks = Tasks()
        val controller = controller(account, name, root, tasks)
        try {
            assertTrue(controller.awaitReconciled())
            fixture.control("reset", "POST")
            val result = ConnectionEnrollmentService(store.database).enroll(ConnectionEnrollmentRequest(anchor,
                AccountConnectionRequest(fixture.routeA, fixture.username, fixture.password)))
            val enrolled = assertIs<ConnectionEnrollmentResult.Enrolled>(result).snapshot
            assertTrue(enrolled.routes.single { it.id == "home" }.enabled)
            assertTrue(enrolled.routes.single { it.id == "remote" }.enabled)
            for (id in listOf("different", "bad-name")) assertFalse(enrolled.routes.single { it.id == id }.enabled,
                "An unverified candidate must remain disabled: $id")
            assertTrue(enrolled.routes.none { it.id == "unlisted" }, "Discovery cannot enroll an unlisted listener")
            val admission = fixture.control("state")
            val admissionCounts = admission.getValue("counts").jsonObject
            assertEquals(0, admissionCounts.getValue("bad-name").jsonPrimitive.int, "Wrong TLS name must prevent all HTTP dispatch")
            assertEquals(0, admissionCounts.getValue("unlisted").jsonPrimitive.int)
            val admissionRequests = admission.getValue("requests").jsonArray.map { it.jsonObject }
            assertTrue(admissionRequests.any { it.getValue("routeId").jsonPrimitive.content == "different" },
                "The different-lineage negative probe must actually execute")
            admissionRequests.forEach { row ->
                val routeId = row.getValue("routeId").jsonPrimitive.content
                assertFalse(row.getValue("path").jsonPrimitive.content.contains("/dulcet/v1"),
                    "Native enrollment must remain on the bridge instead of direct API bootstrap")
                if (routeId in setOf("different", "bad-name", "unlisted")) {
                    assertFalse(row.getValue("queryAuth").jsonPrimitive.boolean)
                    assertFalse(row.getValue("bodyAuth").jsonPrimitive.boolean)
                    assertFalse(row.getValue("authorization").jsonPrimitive.boolean)
                }
            }
            val client = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(account.normalizedBaseUrl,
                account.username, account.password, true), "connection.player")
            try {
                val request = resolveRequest(account.providerInstanceId, fixture.trackId)
                val wire = PlaybackWireClient(account)
                val plan = try { assertIs<PlaybackResolutionResult.Resolved>(wire.resolve(request)).plan } finally { wire.close() }
                val source = AndroidPlaybackDataSourceFactory(plan, AndroidHttpPlaybackResource(account, plan, client)).createDataSource()
                val bytes = java.io.ByteArrayOutputStream()
                try {
                    source.open(DataSpec(Uri.parse("dulcet://fixture/${fixture.trackId}")))
                    val buffer = ByteArray(8192)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count == C.RESULT_END_OF_INPUT) break; bytes.write(buffer, 0, count) }
                } finally { source.close() }
                assertEquals(fixture.sha256, sha(bytes.toByteArray()), "Actual Android player datasource bytes")
                assertNotNull(AndroidArtworkRepository(context, account).load(fixture.artworkId, 96), "Actual Android artwork engine")
                controller.download(listOf(AndroidDownloadItem(fixture.trackId, AudioContainer.Mp3, null)))
                val id = tasks.started.last()
                assertEquals(AndroidDownloadRunOutcome.Downloaded, controller.runTask(id), "Actual Android download executor")
                val downloaded = assertNotNull(controller.localPlan(fixture.trackId))
                assertEquals(fixture.sha256, sha(File(downloaded.absolutePath).readBytes()))
                val state = fixture.control("state")
                assertEquals(0, state.getValue("counts").jsonObject["legacy"]?.jsonPrimitive?.int ?: 0)
                val directoryRequests = state.getValue("requests").jsonArray.map { it.jsonObject }
                    .filter { it["endpoint"]?.jsonPrimitive?.content == "getDulcetConnections" }
                assertTrue(directoryRequests.isNotEmpty(), "Native HTTPS sign-in must use the bridge")
                directoryRequests.forEach {
                    assertEquals("POST", it.getValue("method").jsonPrimitive.content)
                    assertFalse(it.getValue("queryAuth").jsonPrimitive.boolean)
                    assertTrue(it.getValue("bodyAuth").jsonPrimitive.boolean)
                }
                fixture.control("outage?routeId=allHttps&state=on", "POST")
                assertFailsWith<AndroidPlaybackIOException> { AndroidHttpPlaybackResource(account, plan, client).open(0, -1) }
                assertNull(AndroidArtworkRepository(context, account).load("uncached-during-https-outage", 96))
                controller.download(listOf(AndroidDownloadItem(fixture.secondTrackId, AudioContainer.Mp3, null)))
                assertEquals(AndroidDownloadRunOutcome.WillRetry, controller.runTask(tasks.started.last()))
                assertEquals(fixture.sha256, sha(File(downloaded.absolutePath).readBytes()), "Outage must not remove complete bytes")
                assertEquals(0, fixture.control("state").getValue("counts").jsonObject["legacy"]?.jsonPrimitive?.int ?: 0,
                    "Player, worker and artwork never dial the HTTP anchor when all HTTPS routes fail")
                fixture.control("outage?routeId=allHttps&state=off", "POST")
                ConnectionRouteRegistry.publish(anchor, null)
                assertFailsWith<AuthenticatedEndpointFailure> { client.prepareGetRequest("stream", mapOf("id" to fixture.trackId)) }
                assertFailsWith<AndroidPlaybackIOException> { AndroidHttpPlaybackResource(account, plan, client).open(0, -1) }
                assertNull(AndroidArtworkRepository(context, account).load("never-cached-after-upgrade", 96))
                val failed = ConnectionEnrollmentService(store.database).restore(anchor)
                assertEquals(anchor.normalizedBaseUrl, failed.anchor.normalizedBaseUrl)
            } finally { client.close() }
            assertEquals(fixture.legacyHttp, account.normalizedBaseUrl, "HTTP remains provenance only")
        } finally {
            runCatching { fixture.control("outage?routeId=allHttps&state=off", "POST") }
            ConnectionRouteRegistry.forget(anchor.accountId); controller.close(); store.close()
            context.deleteDatabase(name); root.deleteRecursively()
        }
    }

    private fun controller(account: PlaybackEndpointAccount, name: String, root: File, tasks: Tasks) =
        AndroidDownloadController(account, { DulcetDriverFactory(context, name).openDulcetDatabase() }, root,
            tasks, { 1_792_000_000_000L }, { Long.MAX_VALUE }, null, {})

    private class Tasks : AndroidDownloadTasks {
        val started = CopyOnWriteArrayList<String>()
        override suspend fun outstanding(): List<String> = started.toList()
        override fun start(downloadId: String) { started += downloadId }
        override fun cancel(downloadId: String) {}
        override fun wakeAt(wallClockMilliseconds: Long) {}
    }
    private data class Fixture(val routeA: String, val routeB: String, val legacyHttp: String,
        val username: String, val password: String, val instanceId: String, val identity: String,
        val trackId: String, val secondTrackId: String, val sha256: String, val artworkId: String,
        val controlBase: String, val nonce: String) {
        fun control(path: String, method: String = "GET"): JsonObject {
            val url = java.net.URL("$controlBase/$path")
            assertEquals("localhost", url.host)
            val connection = url.openConnection() as java.net.HttpURLConnection
            return try {
                connection.requestMethod = method
                connection.setRequestProperty("X-Dulcet-Fixture", nonce)
                connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                assertEquals(200, connection.responseCode)
                Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            } finally { connection.disconnect() }
        }
    }
    private suspend fun fixture(): Fixture {
        val path = requireNotNull(arguments.getString("DULCET_MULTIPATH_MANIFEST")) { "Local disposable multipath manifest required" }
        val doc = Json.parseToJsonElement(File(path).readText()).jsonObject
        assertEquals("dulcet-multipath-fixture", doc.getValue("standard").jsonPrimitive.content)
        assertTrue(doc.getValue("disposable").jsonPrimitive.boolean)
        assertTrue(doc.getValue("nonce").jsonPrimitive.content.isNotBlank())
        fun value(key: String) = doc.getValue(key).jsonPrimitive.content
        val a = value("routeA"); val b = value("routeB")
        for (base in listOf(a, b)) assertEquals("localhost", java.net.URI(base).host, "No non-fixture upstream is admitted")
        val connected = assertIs<AccountConnectionResult.Connected>(AccountConnector().connect(
            AccountConnectionRequest(a, value("username"), value("password"))))
        val track = doc.getValue("tracks").jsonArray.first().jsonObject
        return Fixture(a, b, value("legacyHttp"), value("username"), value("password"), value("instanceId"),
            connected.account.serverIdentity, track.getValue("id").jsonPrimitive.content,
            doc.getValue("tracks").jsonArray[1].jsonObject.getValue("id").jsonPrimitive.content,
            track.getValue("sourceSha256").jsonPrimitive.content, track.getValue("artworkId").jsonPrimitive.content,
            value("controlBase"), value("nonce"))
    }
    private fun fileWitness(root: File) = root.walkTopDown().filter(File::isFile).associate {
        it.relativeTo(root).path to sha(it.readBytes())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun resolveRequest(provider: String, raw: String) = PlaybackResolveRequest(PlaybackSessionId("fixture-session"),
        AttemptId("fixture-attempt"), ProviderItemId(provider, raw), AudioContainer.Mp3, false,
        PlaybackDeviceProfile("fixture", "android", 2_000_000, 2_000_000,
            listOf(DirectPlayAudioProfile(listOf(AudioContainer.Mp3), listOf("mp3"), maxAudioChannels = 2)),
            listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2))), LegacyPlaybackPreference(null, null))
}
