package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidStreamingCacheTest {
    private val context = RuntimeEnvironment.getApplication()
    private val directory = Files.createTempDirectory("dulcet-stream-cache").toFile()
    private val databaseName = "stream-cache-${UUID.randomUUID()}.db"
    private var controller: AndroidDownloadController? = null
    private var now = 123456L
    private fun controller(): AndroidDownloadController = controller ?: AndroidDownloadController(
        PlaybackEndpointAccount("provider:opaque", "http://127.0.0.1:9", "fixture", "fixture", true),
        { DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver()) }, directory,
        object : AndroidDownloadTasks {
            override suspend fun outstanding() = emptyList<String>()
            override fun start(downloadId: String) { error("cache writes must not start download workers") }
            override fun cancel(downloadId: String) {}
            override fun wakeAt(wallClockMilliseconds: Long) {}
        }, { now }, { Long.MAX_VALUE }, null, {},
    ).also { controller = it; runBlocking { assertTrue(it.awaitReconciled()) } }

    @After fun cleanup() {
        controller?.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
        AndroidAccountData.artworkRootFor(context, "provider:opaque").deleteRecursively()
    }

    @Test fun oneActualValidatedResponseBecomesIdenticalLocalPlaybackWithoutAnotherFetch() = runBlocking {
        val cache = controller()
        val audio = audio()
        var requests = 0
        var playbackBytes = 0L
        val resource = AndroidCachingPlaybackResource({ _, _ -> requests++; response(audio) }, playbackPlan(), source(cache))
        val data = AndroidPlaybackDataSourceFactory(playbackPlan(), resource) { playbackBytes += it }.createDataSource()
        data.open(spec())
        assertContentEquals(audio, drain(data))
        data.close()
        val local = assertNotNull(cache.localPlan("song:opaque"))
        assertEquals(1, requests, "the cache tees the engine response, never fetches again")
        assertEquals(audio.size.toLong(), playbackBytes, "cache bytes cannot enter scrobble byte accounting twice")
        val localData = AndroidPlaybackDataSourceFactory(local.container,
            AndroidLocalFilePlaybackResource(File(local.absolutePath), local.exactByteLength)).createDataSource()
        localData.open(spec())
        assertContentEquals(audio, drain(localData))
        localData.close()
        assertEquals(1, requests, "local replay invokes no upstream")
        cache.clearStreamCache()
        assertNull(cache.localPlan("song:opaque"))
    }

    @Test fun stoppedReadTruncatedResponseAndRangeNeverBecomePlayableCache() = runBlocking {
        val cache = controller()
        for (mode in listOf("stopped", "truncated", "range")) {
            val audio = audio()
            val body = if (mode == "truncated") audio.copyOf(audio.size / 2) else audio
            val response = response(body, audio.size.toLong(), if (mode == "range") 206 else 200)
            val resource = AndroidCachingPlaybackResource({ _, _ -> response }, playbackPlan(), source(cache))
            val data = AndroidPlaybackDataSourceFactory(playbackPlan(), resource).createDataSource()
            if (mode == "range") assertFailsWith<AndroidPlaybackIOException> { data.open(spec()) }
            else {
                data.open(spec())
                if (mode == "stopped") data.read(ByteArray(100), 0, 100)
                else assertFailsWith<AndroidPlaybackIOException> { drain(data) }
            }
            data.close()
            assertNull(cache.localPlan("song:opaque"), "$mode must leave no playable cache")
            assertFalse(directory.walkTopDown().any { it.isFile }, "$mode removes its partial bytes")
        }
    }

    @Test fun tailBeforeMiddleRangesUseOnlyConsumedBytesAndPromoteOnlyAfterFullCoverage() = runBlocking {
        val cache = controller()
        val audio = audio()
        var requests = 0
        val resource = AndroidCachingPlaybackResource({ position, length ->
            requests++
            val end = position + length - 1
            AndroidPlaybackResponse(206, AuthenticatedEndpointResponseHeaders("audio/wav",
                PlaybackContentLength.Exact(length), null, "bytes", "bytes $position-$end/${audio.size}"),
                ByteArrayInputStream(audio.copyOfRange(position.toInt(), end.toInt() + 1)), {})
        }, playbackPlan(), source(cache))
        fun read(start: Int, size: Int) {
            val response = resource.open(start.toLong(), size.toLong())
            assertContentEquals(audio.copyOfRange(start, start + size), response.input.readBytes())
            response.close()
        }
        read(0, 12)
        read(audio.size - 32, 32)
        assertNull(cache.localPlan("song:opaque"), "a prefix and tail leave a hole, never a playable file")
        read(12, audio.size - 44)
        val local = assertNotNull(cache.localPlan("song:opaque"))
        assertContentEquals(audio, File(local.absolutePath).readBytes())
        assertEquals(3, requests, "only the playback engine's three actual range requests run")
        resource.close()
        cache.clearStreamCache()
        val partial = AndroidCachingPlaybackResource({ _, _ -> response(audio) }, playbackPlan(), source(cache))
        val opened = partial.open(0, -1)
        opened.input.read(ByteArray(256))
        partial.close(); opened.close()
        assertNull(cache.localPlan("song:opaque"))
        assertFalse(directory.walkTopDown().any { it.isFile }, "track teardown discards incomplete resource bytes")
    }

    @Test fun artworkExecutorTrimsBeforeStreamingReservationAndCountsSharedStorage() = runBlocking {
        val cache = controller()
        val audio = audio()
        cache.setCacheBudgetBytes(audio.size.toLong())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val artwork = AndroidArtworkRepository(context,
            PlaybackEndpointAccount("provider:opaque", "http://127.0.0.1:9", "fixture", "fixture", true),
            ArtworkFetcher(ArtworkEndpointTransport { ArtworkEndpointResponse(200, png, "<redacted>") }), Long.MAX_VALUE)
        assertContentEquals(png, artwork.load("unretained-cover", 256))
        val artworkRoot = AndroidAccountData.artworkRootFor(context, "provider:opaque")
        assertTrue(artworkRoot.listFiles().orEmpty().any { it.isFile })
        val resource = AndroidCachingPlaybackResource({ _, _ -> response(audio) }, playbackPlan(), source(cache))
        val data = AndroidPlaybackDataSourceFactory(playbackPlan(), resource).createDataSource()
        data.open(spec()); assertContentEquals(audio, drain(data)); data.close()
        assertNotNull(cache.localPlan("song:opaque"), "stream reservation must trim evictable artwork first")
        assertFalse(artworkRoot.listFiles().orEmpty().any { it.isFile })
        // A newly inserted cover is also subject to the same full stream+artwork budget.
        assertContentEquals(png, artwork.load("another-unretained-cover", 256))
        assertFalse(artworkRoot.listFiles().orEmpty().any { it.isFile })
        assertNotNull(cache.localPlan("song:opaque"), "artwork insertion cannot evict the stream first")
        cache.setCacheBudgetBytes(audio.size + 1024L)
        assertContentEquals(png, artwork.load("clearable-cover", 256))
        assertTrue(artworkRoot.listFiles().orEmpty().any { it.isFile })
        cache.setCacheBudgetBytes(audio.size.toLong())
        assertFalse(artworkRoot.listFiles().orEmpty().any { it.isFile }, "lowering the budget trims artwork before media eviction")
        assertNotNull(cache.localPlan("song:opaque"), "lowering the shared budget preserves the stream that fits after artwork trim")
        cache.setCacheBudgetBytes(audio.size + 1024L)
        assertContentEquals(png, artwork.load("clearable-cover-again", 256))
        assertTrue(artworkRoot.listFiles().orEmpty().any { it.isFile })
        cache.clearStreamCache()
        assertNull(cache.localPlan("song:opaque"))
        assertFalse(artworkRoot.listFiles().orEmpty().any { it.isFile }, "Clear Cache also clears evictable artwork reported in shared usage")
        Unit
    }

    @Test fun availabilityProbesPreserveLruAndIncludeEveryCompletedOrStaleRepresentation() = runBlocking {
        val cache = controller()
        val audio = audio()
        val data = AndroidPlaybackDataSourceFactory(playbackPlan(), AndroidCachingPlaybackResource(
            { _, _ -> response(audio) }, playbackPlan(), source(cache))).createDataSource()
        data.open(spec()); drain(data); data.close()
        // Publish deterministically after the tee's asynchronous completion notification.
        cache.setCacheBudgetBytes(1024 * 1024)
        val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())
        try {
            val engine = DownloadPolicyEngine(store.database, DownloadFileStore(directory.path, okio.FileSystem.SYSTEM))
            engine.reconcile(emptyList(), mapOf("provider:opaque" to 1L))
            val stream = engine.records("provider:opaque").single()
            fun access() = store.database.offlineLibraryQueries.selectRetention("provider:opaque", "song:opaque",
                stream.identity.transcodeProfile).executeAsOne().last_access_wall
            val before = access()
            // A pending original wins the collapsed status, but must not hide the complete cache.
            engine.enqueue(DownloadRequest(DownloadIdentity("provider:opaque", "song:opaque", "original"),
                AudioContainer.Wav, PlaybackContentLength.Exact(audio.size.toLong()), DownloadServerSnapshot(null, null), 1, now))
            cache.setCacheBudgetBytes(1024 * 1024)
            assertEquals(AndroidDownloadState.Queued, cache.statuses.value["song:opaque"]?.state)
            now += 50_000
            repeat(1000) { assertTrue(cache.isLocallyPlayable("song:opaque")); assertFalse(cache.isLocallyPlayable("missing")) }
            assertEquals(setOf("song:opaque"), cache.downloadedRawIds())
            assertEquals(before, access(), "queue eligibility never touches streaming-cache LRU")
            store.database.downloadsQueries.markDownloadStale(stream.downloadId.value)
            cache.setCacheBudgetBytes(1024 * 1024)
            assertTrue(cache.isLocallyPlayable("song:opaque"), "a stale representation is still locally playable")
            assertNotNull(cache.localPlan("song:opaque"))
            assertEquals(now, access(), "actual playback resolution still refreshes LRU")
            engine.remove(stream.identity)
            cache.setCacheBudgetBytes(1024 * 1024)
            assertFalse(cache.isLocallyPlayable("song:opaque"), "publication removes availability when the last complete profile is removed")
            assertTrue(engine.records("provider:opaque").any { it.state == DownloadState.Queued })
        } finally { store.close() }
    }

    @Test fun removingExplicitOriginalPreservesTheOtherRepresentationAndItsMetadataPin() = runBlocking {
        val audio = audio()
        val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())
        try {
            val engine = DownloadPolicyEngine(store.database, DownloadFileStore(directory.path, okio.FileSystem.SYSTEM))
            engine.reconcile(emptyList(), mapOf("provider:opaque" to 1L))
            val original = engine.enqueue(DownloadRequest(DownloadIdentity("provider:opaque", "song:opaque", "original"),
                AudioContainer.Wav, PlaybackContentLength.Exact(audio.size.toLong()), DownloadServerSnapshot(null, null), 1, 100))
            File(engine.temporaryFilePath(original.downloadId)).apply { parentFile!!.mkdirs(); writeBytes(audio) }
            assertIs<DownloadPromotionResult.Promoted>(engine.promote(original.downloadId,
                DownloadResponseMetadata("audio/wav", PlaybackContentLength.Exact(audio.size.toLong()))))
            val cache = controller()
            val data = AndroidPlaybackDataSourceFactory(playbackPlan(), AndroidCachingPlaybackResource(
                { _, _ -> response(audio) }, playbackPlan(), source(cache))).createDataSource()
            data.open(spec()); drain(data); data.close()
            assertTrue(store.database.seenCacheQueries.selectPins("provider:opaque").executeAsList().any { it.raw_id == "song:opaque" })
            cache.remove(listOf("song:opaque"))
            val held = assertNotNull(cache.localPlan("song:opaque"))
            assertTrue(held.identity.transcodeProfile.startsWith("stream:"))
            assertContentEquals(audio, File(held.absolutePath).readBytes())
            assertTrue(store.database.seenCacheQueries.selectPins("provider:opaque").executeAsList().any { it.raw_id == "song:opaque" },
                "a surviving cached representation must keep its playback metadata pinned")
        } finally { store.close() }
    }

    @Test fun mislabeledServerEnvelopeAndMalformedAudioNeverBecomePlayableCache() = runBlocking {
        val cache = controller()
        for (body in listOf("<subsonic-response status=\"failed\"><error code=\"70\"/></subsonic-response>".encodeToByteArray(), "RIFFxxxxBAD!".encodeToByteArray())) {
            val resource = AndroidCachingPlaybackResource({ _, _ -> response(body) }, playbackPlan(), source(cache))
            val data = AndroidPlaybackDataSourceFactory(playbackPlan(), resource).createDataSource()
            assertFailsWith<AndroidPlaybackIOException> { data.open(spec()) }
            assertNull(cache.localPlan("song:opaque"))
            assertFalse(directory.walkTopDown().any { it.isFile })
        }
    }

    @Test fun explicitClickOnCompletedAutomaticDownloadSurvivesLibraryRemovalAndDisablingAutomaticDownloads() = runBlocking {
        val audio = audio()
        val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())
        try {
            val engine = DownloadPolicyEngine(store.database, DownloadFileStore(directory.path, okio.FileSystem.SYSTEM))
            engine.reconcile(emptyList(), mapOf("provider:opaque" to 1L))
            val policy = OfflineLibraryPolicy(store.database, engine)
            val request = DownloadRequest(DownloadIdentity("provider:opaque", "song:opaque", "original"),
                AudioContainer.Wav, PlaybackContentLength.Exact(audio.size.toLong()), DownloadServerSnapshot(null, null), 1, 100)
            val row = policy.reconcileAutomaticDownloads("provider:opaque", listOf(AutomaticDownloadMember(request, 100))).queued.single()
            File(engine.temporaryFilePath(row.downloadId)).apply { parentFile!!.mkdirs(); writeBytes(audio) }
            assertIs<DownloadPromotionResult.Promoted>(engine.promote(row.downloadId,
                DownloadResponseMetadata("audio/wav", PlaybackContentLength.Exact(audio.size.toLong()))))
            val controller = controller()
            // This is the platform action. A completed automatic row must still gain explicit ownership.
            controller.download(listOf(AndroidDownloadItem("song:opaque", AudioContainer.Wav, null)))
            policy.reconcileAutomaticDownloads("provider:opaque", emptyList())
            assertContentEquals(audio, File(assertNotNull(controller.localPlan("song:opaque")).absolutePath).readBytes(),
                "removing My Library membership cannot remove an explicit download")
            controller.setAutomaticDownloadsEnabled(false)
            assertContentEquals(audio, File(assertNotNull(controller.localPlan("song:opaque")).absolutePath).readBytes(),
                "turning off Automatic Downloads cannot remove an explicit download")
        } finally { store.close() }
    }

    @Test fun cacheFailureDoesNotChangeDeliveredBytesOrPlaybackAccounting() {
        val audio = audio()
        var bytes = 0L
        var aborted = false
        val source = object : AndroidLocalPlaybackSource {
            override suspend fun localPlan(rawId: String): LocalPlaybackPlan? = null
            override fun beginStreamCacheWrite(plan: RemotePlaybackWirePlan, status: Int, contentType: String?, exactLength: Long?, position: Long) =
                object : AndroidStreamCacheWrite {
                    override fun append(bytes: ByteArray, offset: Int, length: Int) { error("fixture disk failure") }
                    override fun finish() { error("a failed writer must not finish") }
                    override fun abort() { aborted = true }
                }
        }
        val data = AndroidPlaybackDataSourceFactory(playbackPlan(), AndroidCachingPlaybackResource({ _, _ -> response(audio) }, playbackPlan(), source)) { bytes += it }.createDataSource()
        data.open(spec())
        assertContentEquals(audio, drain(data))
        data.close()
        assertTrue(aborted)
        assertEquals(audio.size.toLong(), bytes)
    }

    private fun source(cache: AndroidDownloadController) = object : AndroidLocalPlaybackSource {
        override suspend fun localPlan(rawId: String) = cache.localPlan(rawId)
        override fun beginStreamCacheWrite(plan: RemotePlaybackWirePlan, status: Int, contentType: String?, exactLength: Long?, position: Long) =
            cache.beginStreamCacheWrite(plan, status, contentType, exactLength, position)
    }
    private fun response(body: ByteArray, exact: Long = body.size.toLong(), status: Int = 200) = AndroidPlaybackResponse(status,
        AuthenticatedEndpointResponseHeaders("audio/wav", PlaybackContentLength.Exact(exact), null, null, null), ByteArrayInputStream(body), {})
    private fun audio() = "RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray() + ByteArray(100_000) { (it % 255).toByte() }
    private fun spec() = DataSpec.Builder().setUri(Uri.parse("dulcet://resource")).build()
    private fun drain(data: androidx.media3.datasource.DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = data.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
