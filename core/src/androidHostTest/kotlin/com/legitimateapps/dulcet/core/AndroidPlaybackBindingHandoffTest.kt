package com.legitimateapps.dulcet.core

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/**
 * Account replacement can first reach downloads through a worker or library screen, before the
 * playback service is rebound. Observe the real adapter's first unlink, while real controller
 * attempts hold current and preloaded files. Player and filesystem callbacks are external probes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlaybackBindingHandoffTest {
    @Before fun mainDispatcher() {
        val handler = Handler(Looper.getMainLooper())
        Dispatchers.setMain(object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() != handler.looper
            override fun dispatch(context: CoroutineContext, block: Runnable) { handler.post(block) }
        })
    }

    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun changedEndpointStopsCurrentAndPreloadBeforeTheFirstFileIsDeleted() =
        changedBinding { it.copy(normalizedBaseUrl = "http://127.0.0.1:19") }

    @Test fun changedUsernameStopsCurrentAndPreloadBeforeTheFirstFileIsDeleted() =
        changedBinding { it.copy(username = "another-user") }

    @Test fun reopeningTheSameBindingKeepsCurrentPreloadAndFiles() = unchangedBinding { it }

    @Test fun refreshingThePasswordKeepsCurrentPreloadAndFiles() =
        unchangedBinding { it.copy(password = "replacement-password") }

    @Test fun equivalentUrlSpellingKeepsCurrentPreloadAndFiles() =
        unchangedBinding { it.copy(normalizedBaseUrl = "HTTP://LOCALHOST:80/") }

    private fun changedBinding(replacement: (PlaybackEndpointAccount) -> PlaybackEndpointAccount) = runBlocking {
        Fixture().use { f ->
            f.startCurrentAndPreload()
            val before = f.currentClears
            val observations = CopyOnWriteArrayList<DeleteObservation>()
            f.beforeDelete = {
                observations += DeleteObservation(f.currentClears > before, f.current.released, f.current.requested,
                    f.incoming.released, f.incoming.listener == null, f.leaseCount, f.protectedIds.toSet())
            }

            f.open(replacement(f.account)) // Deliberately reaches downloads before service.ensurePlayback.

            assertTrue(observations.isNotEmpty(), "The control must reach a real existing media-file deletion")
            val first = observations.first()
            assertTrue(first.currentCleared || first.currentReleased,
                "Current engine item was still installed at the first media-file deletion: $first")
            assertTrue(first.currentReleased || !first.currentWantedPlay,
                "The previous binding must not retain a live player with play intent at deletion: $first")
            assertTrue(first.preloadReleased, "The preloaded player must be released before its file can be deleted: $first")
            assertTrue(first.preloadListenerDetached, "The preloaded player must not retain its engine callback: $first")
            assertEquals(0, first.leases, "Current and preload leases must be released before deletion: $first")
            assertTrue(first.protectedIds.isEmpty(), "Attempt protection must be withdrawn before deletion: $first")
            assertTrue(f.mediaFiles.none(File::exists), "An actual new binding removes the previous binding's files")
        }
    }

    private fun unchangedBinding(replacement: (PlaybackEndpointAccount) -> PlaybackEndpointAccount) = runBlocking {
        Fixture().use { f ->
            f.startCurrentAndPreload()
            val before = f.currentClears
            val attempt = f.playback.state.value.attemptId
            var deletes = 0
            f.beforeDelete = { deletes++ }

            val reopened = f.open(replacement(f.account))

            assertEquals(0, deletes, "A same-binding refresh cannot unlink either active file")
            assertEquals(before, f.currentClears, "A same-binding refresh cannot stop the installed current item")
            assertTrue(f.current.requested, "The current item keeps its play intent")
            assertFalse(f.incoming.released, "A same-binding refresh keeps the prepared incoming player")
            assertNotNull(f.incoming.listener, "The prepared incoming player keeps its engine callback")
            assertEquals(attempt, f.playback.state.value.attemptId, "The current attempt continues unchanged")
            assertEquals(2, f.leaseCount, "Current and preload retain their independent resolution leases")
            assertEquals(setOf("current", "incoming"), f.protectedIds)
            assertTrue(f.mediaFiles.all(File::exists))
            assertNotNull(reopened.localPlan("current"))
            assertNotNull(reopened.localPlan("incoming"))
            reopened.clearStreamCache()
            assertTrue(f.mediaFiles.all(File::exists),
                "The refreshed policy must honour the current and preload leases held by its predecessor")
            f.playback.stop()
            assertEquals(0, f.leaseCount, "Stop releases both attempts' leases")
            assertTrue(f.protectedIds.isEmpty())
            reopened.clearStreamCache()
            assertTrue(f.mediaFiles.none(File::exists),
                "The refreshed policy must release protection when playback ends, so Clear Cache can remove both files")
        }
    }

    private data class DeleteObservation(
        val currentCleared: Boolean,
        val currentReleased: Boolean,
        val currentWantedPlay: Boolean,
        val preloadReleased: Boolean,
        val preloadListenerDetached: Boolean,
        val leases: Int,
        val protectedIds: Set<String>,
    )

    private class Fixture : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val databaseName = "binding-handoff-${UUID.randomUUID()}.db"
        private val root = Files.createTempDirectory("dulcet-binding-handoff").toFile()
        val account = PlaybackEndpointAccount("provider:handoff", "http://localhost", "alice", "fixture", true)
        val current = PlayerProbe()
        val incoming = PlayerProbe()
        var currentClears = 0
        @Volatile var leaseCount = 0
        @Volatile var protectedIds = emptySet<String>()
        var beforeDelete: () -> Unit = {}
        val mediaFiles = mutableListOf<File>()
        private val downloads = mutableListOf<AndroidDownloadController>()
        private var playerStore: DulcetDatabaseStore? = null
        lateinit var playback: AndroidPlaybackController

        private fun store() = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())

        suspend fun open(endpoint: PlaybackEndpointAccount = account): AndroidDownloadController {
            // Construction may need to stop a player on main. Keep main available while a real
            // worker-thread caller obtains the serialized adapter and its synchronous binding.
            val pending = CoroutineScope(Dispatchers.IO).async {
                AndroidDownloadController(endpoint, ::store, root, object : AndroidDownloadTasks {
                    override suspend fun outstanding() = emptyList<String>()
                    override fun start(downloadId: String) { error("No network download is expected") }
                    override fun cancel(downloadId: String) {}
                    override fun wakeAt(wallClockMilliseconds: Long) {}
                }, { 1_000L }, { Long.MAX_VALUE }, null, {}, object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun delete(path: Path, mustExist: Boolean) {
                        if (File(path.toString()) in mediaFiles && File(path.toString()).exists()) beforeDelete()
                        super.delete(path, mustExist)
                    }
                })
            }
            eventually { pending.isCompleted }
            return pending.await().also { downloads += it; assertTrue(it.awaitReconciled()) }
        }

        suspend fun startCurrentAndPreload() {
            val adapter = open()
            for (rawId in listOf("current", "incoming")) {
                val write = assertNotNull(adapter.beginStreamCacheWrite(plan(rawId), 200, "audio/wav", AUDIO.size.toLong(), 0))
                write.append(AUDIO, 0, AUDIO.size); write.finish()
                mediaFiles += File(assertNotNull(adapter.localPlan(rawId)).absolutePath)
            }
            eventually { listOf("current", "incoming").all(adapter::isLocallyPlayable) }
            val source = object : AndroidLocalPlaybackSource {
                override fun isLocallyPlayable(rawId: String) = adapter.isLocallyPlayable(rawId)
                override suspend fun localPlan(rawId: String) = adapter.localPlan(rawId)
                override suspend fun localTrack(rawId: String) = AndroidTrack(account.providerInstanceId, rawId, rawId, null, null, 40_000, null)
                override suspend fun acquireLocalPlan(rawId: String): AndroidLocalPlaybackLease? {
                    val lease = adapter.acquireLocalPlan(rawId) ?: return null
                    leaseCount++
                    return object : AndroidLocalPlaybackLease {
                        override val plan = lease.plan
                        private var closed = false
                        override fun close() {
                            if (closed) return
                            closed = true
                            lease.close()
                            leaseCount--
                        }
                    }
                }
                override fun setProtectedRawIds(rawIds: Set<String>) {
                    protectedIds = rawIds.toSet()
                    adapter.setProtectedRawIds(rawIds)
                }
            }
            val store = store().also { playerStore = it }
            current.onClearMediaItems = { currentClears++ }
            val prepared = mutableListOf<String>()
            playback = AndroidPlaybackController(context, account, AndroidPlaybackControllerBoundaries(
                store, current.player, { fail("This control must prepare local media only") },
                { fail("This control must never read server metadata") }, null, {}, localPlans = source,
                prepareLocalSource = { prepared += it.itemId.rawId }, overlapPlayer = { incoming.player }))
            playback.setAutoplay(false)
            playback.setCrossfade(5)
            playback.playQueue(listOf("current", "incoming").map {
                AndroidTrack(account.providerInstanceId, it, it, null, null, 40_000, null)
            }, 0, AndroidQueueSource.Search, "Local search")
            eventually { prepared == listOf("current") }
            assertEquals(5, playback.state.value.crossfadeSeconds, "This mixed queue must permit overlap")
            current.state = Player.STATE_READY; current.events()
            repeat(2) {
                current.position += 1_000
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
            }
            eventually { prepared == listOf("current", "incoming") }
            assertTrue(current.requested, "The old binding must have a playing current item")
            assertFalse(incoming.released)
            assertNotNull(incoming.listener, "The old binding must have an actual engine preload")
            assertEquals(2, leaseCount)
            assertEquals(setOf("current", "incoming"), protectedIds)
            assertTrue(mediaFiles.all(File::exists))
        }

        override fun close() {
            if (::playback.isInitialized) playback.close()
            downloads.forEach { it.close() }
            playerStore?.close()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
            AndroidAccountData.artworkRootFor(context, account.providerInstanceId).deleteRecursively()
        }

        private suspend fun eventually(condition: () -> Boolean) {
            withTimeout(5_000) {
                while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
            }
        }

        private fun plan(rawId: String): RemotePlaybackWirePlan {
            val base = playbackPlan(session = "cache:$rawId", attempt = "cache:$rawId")
            val item = ProviderItemId(account.providerInstanceId, rawId)
            return RemotePlaybackWirePlan(base.playbackSessionId, base.attemptId, item, base.path,
                base.deliveryProtocol, base.expectedContainer, base.transcode,
                PlaybackContentLength.Exact(AUDIO.size.toLong()), "stream", mapOf("id" to rawId),
                base.resolutionRequest.copy(itemId = item))
        }
    }

    private companion object {
        val AUDIO = "RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray() + ByteArray(1_024) { (it % 255).toByte() }
    }
}
