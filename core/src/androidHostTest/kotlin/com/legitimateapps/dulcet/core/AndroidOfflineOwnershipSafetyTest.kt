package com.legitimateapps.dulcet.core

import android.os.Looper
import android.os.Handler
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidOfflineOwnershipSafetyTest {
    @Before fun mainDispatcher() {
        val handler = Handler(Looper.getMainLooper())
        Dispatchers.setMain(object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() != handler.looper
            override fun dispatch(context: CoroutineContext, block: Runnable) { handler.post(block) }
        })
    }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun changedUsernameCannotResolveThePreviousUsersCacheOrExplicitDownload() = rebind("http://127.0.0.1:9", "bob")
    @Test fun changedEndpointCannotResolveThePreviousServersCacheOrExplicitDownload() = rebind("http://127.0.0.1:19", "alice")

    private fun rebind(url: String, user: String) = runBlocking {
        Fixture().use { f ->
            val old = f.open()
            val cached = f.cache(old)
            val explicit = f.explicit()
            assertContentEquals(AUDIO, File(assertNotNull(old.localPlan("explicit")).absolutePath).readBytes())
            val replacement = f.open(url, user)
            assertNull(replacement.localPlan("song:opaque"), "same raw id under new account binding must not select prior cached bytes")
            assertNull(replacement.localPlan("explicit"), "same raw id under new account binding must not select prior explicit bytes")
            assertFalse(File(cached.absolutePath).exists())
            assertFalse(File(explicit.absolutePath).exists())
            assertNull(old.localPlan("song:opaque"), "an old adapter remains revoked after replacement")
            assertNull(old.beginStreamCacheWrite(playbackPlan(), 200, "audio/wav", AUDIO.size.toLong(), 0),
                "an old adapter cannot publish a new cache write into the replacement account")
        }
    }

    @Test fun clearCacheBetweenResolutionAndPrepareCannotRemoveTheSelectedPlaybackFile() = runBlocking {
        Fixture().use { f ->
            // The production adapter now reads real cache metadata. Admit this positive playback
            // fixture before exercising Clear Cache between local resolution and Prepare.
            val metadata = f.newStore()
            try {
                val cache = SeenCacheStore(metadata, SeenCacheWallClock { 1_000L })
                    .bind(CacheBinding(PROVIDER, f.account().normalizedBaseUrl, f.account().username))
                cache.writeEntities(CacheWriteStamp(cache.issue(), 1_000L, null), CacheEntitySource.Detail,
                    CacheEntities(tracks = listOf(CacheTrackRecord("song:opaque", "album:cached",
                        "Local song", durationMilliseconds = 6_000, sourceContainer = AudioContainer.Wav))))
            } finally { metadata.close() }
            val downloads = f.open()
            val local = f.cache(downloads)
            var clearsAtResolution = 0
            var preparations = 0
            var selectedFileSurvivedClear = false
            val source = object : AndroidLocalPlaybackSource {
                override fun isLocallyPlayable(rawId: String) = downloads.isLocallyPlayable(rawId)
                override suspend fun localTrack(rawId: String) = downloads.localTrack(rawId)
                override suspend fun localPlan(rawId: String): LocalPlaybackPlan? {
                    val plan = downloads.localPlan(rawId) ?: return null
                    downloads.clearStreamCache() // legacy controller uses this unleased path
                    selectedFileSurvivedClear = File(plan.absolutePath).exists()
                    clearsAtResolution++
                    return plan
                }
                override suspend fun acquireLocalPlan(rawId: String): AndroidLocalPlaybackLease? {
                    val lease = downloads.acquireLocalPlan(rawId) ?: return null
                    downloads.clearStreamCache() // deterministic interleaving before Prepare
                    selectedFileSurvivedClear = File(lease.plan.absolutePath).exists()
                    clearsAtResolution++
                    return lease
                }
                override fun setProtectedRawIds(rawIds: Set<String>) = downloads.setProtectedRawIds(rawIds)
            }
            val store = f.newStore()
            val probe = PlayerProbe()
            val player = AndroidPlaybackController(f.context, f.account(), AndroidPlaybackControllerBoundaries(
                store, probe.player, { fail("local playback must not prepare a remote plan") },
                { fail("local playback must not read server metadata") }, null, {}, localPlans = source,
                prepareLocalSource = {
                    preparations++
                    assertTrue(File(local.absolutePath).exists(), "Clear Cache must not delete a selected file before Prepare")
                }))
            try {
                player.setAutoplay(false)
                player.playSong(PROVIDER, "song:opaque", "Local song")
                eventually { clearsAtResolution > 0 }
                assertTrue(selectedFileSurvivedClear, "Clear Cache deleted the selected file in the resolution-to-Prepare gap")
                eventually { preparations == 1 }
                assertEquals(1, clearsAtResolution)
                assertTrue(File(local.absolutePath).exists())
                player.stop()
                downloads.clearStreamCache()
                assertFalse(File(local.absolutePath).exists(), "Stop must release the attempt lease so cache can be cleared")
            } finally { player.close(); store.close() }
        }
    }

    @Test fun downloadedSongKeepsItsRealCachedAlbumInTheQueueAndAfterRestore() = runBlocking {
        Fixture().use { f ->
            f.explicit("song:opaque")
            val seed = f.newStore()
            try {
                val cache = SeenCacheStore(seed, SeenCacheWallClock { 1_000L })
                    .bind(CacheBinding(PROVIDER, f.account().normalizedBaseUrl, f.account().username))
                cache.writeEntities(CacheWriteStamp(cache.issue(), 1_000L, null), CacheEntitySource.Detail,
                    CacheEntities(tracks = listOf(CacheTrackRecord("song:opaque", "album:downloaded",
                        "Downloaded song", durationMilliseconds = 6_000, sourceContainer = AudioContainer.Wav))))
            } finally { seed.close() }
            val downloads = f.open()
            val source = object : AndroidLocalPlaybackSource {
                override fun isLocallyPlayable(rawId: String) = downloads.isLocallyPlayable(rawId)
                override suspend fun localTrack(rawId: String) = downloads.localTrack(rawId)
                override suspend fun localPlan(rawId: String) = downloads.localPlan(rawId)
                override suspend fun acquireLocalPlan(rawId: String) = downloads.acquireLocalPlan(rawId)
                override fun setProtectedRawIds(rawIds: Set<String>) = downloads.setProtectedRawIds(rawIds)
            }
            var preparations = 0
            fun coldPlayer(store: DulcetDatabaseStore) = AndroidPlaybackController(f.context, f.account(),
                AndroidPlaybackControllerBoundaries(store, PlayerProbe().player,
                    { fail("downloaded playback must not prepare a remote plan") },
                    { fail("downloaded playback must not read server metadata") }, null, {}, localPlans = source,
                    prepareLocalSource = { preparations++ }))
            val store = f.newStore()
            val player = coldPlayer(store)
            try {
                player.setAutoplay(false)
                player.playSong(PROVIDER, "song:opaque", "Selection without album metadata")
                eventually { preparations == 1 }
                val queued = PersistentQueueStore(store.database).load(ServerId(PROVIDER)).entries.single()
                assertEquals("album:downloaded", queued.albumRawId, "The production download adapter must populate the durable row")
                assertEquals(6_000L, queued.duration?.inWholeMilliseconds)
            } finally { player.close() }

            val reopened = f.newStore()
            val restored = coldPlayer(reopened)
            try {
                eventually { preparations == 2 }
                val entry = PersistentQueueStore(reopened.database).load(ServerId(PROVIDER)).entries.single()
                assertEquals("album:downloaded", entry.albumRawId, "A new controller must restore the persisted album")
                assertEquals(6_000L, entry.duration?.inWholeMilliseconds)
                assertEquals("album:downloaded", restored.state.value.queue.single().track.albumRawId)
            } finally { restored.close() }
        }
    }

    @Test fun independentResolutionLeasesReleaseIdempotentlyWithoutUnpinningAnotherAttempt() = runBlocking {
        Fixture().use { f ->
            val downloads = f.open()
            val local = f.cache(downloads)
            val first = assertNotNull(downloads.acquireLocalPlan("song:opaque"))
            val second = assertNotNull(downloads.acquireLocalPlan("song:opaque"))
            downloads.clearStreamCache()
            assertTrue(File(local.absolutePath).exists())
            first.close(); first.close()
            downloads.clearStreamCache()
            assertTrue(File(local.absolutePath).exists(), "one attempt releasing twice must not unpin another")
            second.close()
            downloads.clearStreamCache()
            assertFalse(File(local.absolutePath).exists(), "the final lease release must make the cache evictable")
        }
    }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) } }
    }
    private class Fixture : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        private val name = "offline-ownership-${UUID.randomUUID()}.db"
        private val root = Files.createTempDirectory("dulcet-offline-ownership").toFile()
        private val controllers = mutableListOf<AndroidDownloadController>()
        fun account(url: String = "http://127.0.0.1:9", user: String = "alice") = PlaybackEndpointAccount(PROVIDER, url, user, "fixture", true)
        fun newStore() = DulcetDatabaseStore.open(DulcetDriverFactory(context, name).createDriver())
        suspend fun open(url: String = "http://127.0.0.1:9", user: String = "alice"): AndroidDownloadController = AndroidDownloadController(
            account(url, user), ::newStore, root, object : AndroidDownloadTasks {
                override suspend fun outstanding() = emptyList<String>()
                override fun start(downloadId: String) {}
                override fun cancel(downloadId: String) {}
                override fun wakeAt(wallClockMilliseconds: Long) {}
            }, { 1_000L }, { Long.MAX_VALUE }, null, {},
        ).also { controllers += it; assertTrue(it.awaitReconciled()) }
        suspend fun cache(downloads: AndroidDownloadController): LocalPlaybackPlan {
            val write = assertNotNull(downloads.beginStreamCacheWrite(playbackPlan(), 200, "audio/wav", AUDIO.size.toLong(), 0))
            write.append(AUDIO, 0, AUDIO.size); write.finish()
            val plan = assertNotNull(downloads.localPlan("song:opaque"))
            withTimeout(5_000) { while (!downloads.isLocallyPlayable("song:opaque")) delay(5) }
            return plan
        }
        fun explicit(rawId: String = "explicit"): LocalPlaybackPlan {
            val store = newStore()
            try {
                val engine = DownloadPolicyEngine(store.database, DownloadFileStore(root.path, okio.FileSystem.SYSTEM))
                engine.bindMedia(DownloadMediaBinding(PROVIDER, "http://127.0.0.1:9", "alice"))
                engine.reconcile(emptyList(), mapOf(PROVIDER to 1L))
                val row = engine.enqueue(DownloadRequest(DownloadIdentity(PROVIDER, rawId, "original"), AudioContainer.Wav,
                    PlaybackContentLength.Exact(AUDIO.size.toLong()), DownloadServerSnapshot(null, null), 1, 1))
                engine.writeCompletedTemporaryFile(row.downloadId, AUDIO)
                assertIs<DownloadPromotionResult.Promoted>(engine.promote(row.downloadId,
                    DownloadResponseMetadata("audio/wav", PlaybackContentLength.Exact(AUDIO.size.toLong()))))
                return assertIs<OfflinePlaybackPlanResult.Available>(engine.offlinePlaybackPlan(row.identity)).plan
            } finally { store.close() }
        }
        override fun close() {
            controllers.forEach { it.close() }
            context.deleteDatabase(name)
            root.deleteRecursively()
            AndroidAccountData.artworkRootFor(context, PROVIDER).deleteRecursively()
        }
    }
    private companion object {
        const val PROVIDER = "provider:opaque"
        val AUDIO = "RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray() + ByteArray(1_024) { (it % 255).toByte() }
    }
}
