package com.legitimateapps.dulcet

import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.legitimateapps.dulcet.core.AndroidDownloadItem
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import com.legitimateapps.dulcet.library.SavedAccountConnection
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import kotlinx.coroutines.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import java.security.MessageDigest
import java.util.UUID
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.legitimateapps.dulcet.playback.PlaybackService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.*

/**
 * The real service must preserve its already-installed engine item and session when refreshing
 * credentials for the same media owner. Adapter tests separately cover current/preload leases.
 * An ExoPlayer item is installed directly here to avoid making any server or decoder claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [SwitchableAccountStore::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"])
class PlaybackBindingContinuityTest {
    @Test fun theSameSavedAccountKeepsItsInstalledPlayerAndSession() = preservesPlayback { it }

    @Test fun refreshingTheSavedPasswordKeepsItsInstalledPlayerAndSession() =
        preservesPlayback { it.copy(password = "replacement-password") }

    @Test fun anEquivalentSavedUrlKeepsItsInstalledPlayerAndSession() =
        preservesPlayback { it.copy(serverUrl = "HTTP://LOCALHOST:80/") }

    @Test fun aRetainedDownloadOwnerAuthenticatesItsNextMetadataRequestWithTheRefreshedPassword() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.noBackupFilesDir, "downloads")
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
        LoopbackLibraryServer().use { server ->
            val original = StoredAccount("binding-auth:${UUID.randomUUID()}", "Fixture", server.url, "alice", "old-password", true)
            SwitchableAccountStore.current = original
            SwitchableAccountStore.unreadable = false
            SavedAccountConnection.connectedOnTheForm(original.id)
            val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
            val service = lifecycle.get()
            val file = File.createTempFile("dulcet-auth-current", ".wav")
            try {
                val playback = assertNotNull(service.ensurePlayback())
                val session = service.sessions.single()
                val engine = enginePlayer(playback.sessionPlayer)
                val item = MediaItem.Builder().setMediaId("installed-current").setUri(file.toURI().toString()).build()
                engine.setMediaItem(item); engine.playWhenReady = true
                val pending = async(Dispatchers.IO) { AndroidDownloads.controller(context) }
                settle("download owner created before password refresh") { pending.isCompleted }
                val downloads = assertNotNull(pending.await())
                assertTrue(downloads.awaitReconciled())
                val refreshedPassword = "new-password"
                SwitchableAccountStore.current = original.copy(password = refreshedPassword)
                assertSame(playback, service.ensurePlayback())
                assertSame(downloads, AndroidDownloads.existingController(original.id), "The existing download owner stays installed")
                assertSame(session, service.sessions.single())
                assertSame(engine, enginePlayer(playback.sessionPlayer))
                assertEquals(item, engine.currentMediaItem)
                assertTrue(engine.playWhenReady)
                assertFalse(engine.isReleased)

                // A track never seen by this account forces the retained adapter's real getSong.
                val rawId = "future-metadata:${UUID.randomUUID()}"
                downloads.download(listOf(AndroidDownloadItem(rawId, AudioContainer.Wav, 1_000)))
                val manager = WorkManager.getInstance(context)
                settle("the new explicit download registered") {
                    manager.getWorkInfosByTag("dulcet.download.account:${original.id}").get().isNotEmpty()
                }
                val task = manager.getWorkInfosByTag("dulcet.download.account:${original.id}").get().single()
                WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(task.id)
                settle("the retained adapter's metadata request reached the real server") {
                    server.requests("getSong").any { it["id"] == rawId }
                }
                val request = server.requests("getSong").single { it["id"] == rawId }
                val salt = assertNotNull(request["s"])
                fun token(password: String) = MessageDigest.getInstance("MD5")
                    .digest((password + salt).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals("alice", request["u"])
                assertEquals(token(refreshedPassword), request["t"], "Future metadata uses the refreshed password")
                assertNotEquals(token(original.password), request["t"], "The previous password must no longer sign new requests")
            } finally {
                lifecycle.destroy()
                AndroidDownloads.releaseForSignOut(context, original.id)
                SwitchableAccountStore.current = null
                SwitchableAccountStore.unreadable = false
                file.delete()
                root.deleteRecursively()
                context.deleteDatabase("dulcet.db")
            }
        }
    }

    private fun settle(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $description" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun preservesPlayback(replacement: (StoredAccount) -> StoredAccount) {
        val original = StoredAccount("stable-media-owner", "Fixture", "http://localhost", "alice", "fixture", true)
        SwitchableAccountStore.current = original
        SwitchableAccountStore.unreadable = false
        val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        val service = lifecycle.get()
        val file = File.createTempFile("dulcet-binding-current", ".wav").apply {
            writeBytes("RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray() + ByteArray(1_024))
        }
        try {
            val first = assertNotNull(service.ensurePlayback())
            val session = service.sessions.single()
            val engine = enginePlayer(first.sessionPlayer)
            val item = MediaItem.Builder().setMediaId("installed-local-current").setUri(file.toURI().toString()).build()
            engine.setMediaItem(item)
            engine.playWhenReady = true
            assertEquals(item, engine.currentMediaItem, "The control requires an installed current engine item")
            assertTrue(engine.playWhenReady, "The control requires existing play intent")
            assertFalse(engine.isReleased)

            SwitchableAccountStore.current = replacement(original)
            val refreshed = assertNotNull(service.ensurePlayback())

            assertSame(session, service.sessions.single(), "The existing media session must stay registered")
            assertSame(engine, enginePlayer(refreshed.sessionPlayer), "The existing engine must remain installed")
            assertFalse(engine.isReleased, "A credential refresh must not release the current engine")
            assertEquals(item, engine.currentMediaItem, "A credential refresh must not clear the current item")
            assertTrue(engine.playWhenReady, "A credential refresh must preserve existing play intent")
        } finally {
            SwitchableAccountStore.current = null
            SwitchableAccountStore.unreadable = false
            lifecycle.destroy()
            file.delete()
        }
    }

    private fun enginePlayer(sessionPlayer: Player): ExoPlayer {
        val bridge = (sessionPlayer as ForwardingPlayer).wrappedPlayer
        val active = if (bridge is ForwardingSimpleBasePlayer)
            ForwardingSimpleBasePlayer::class.java.getDeclaredMethod("getPlayer").apply { isAccessible = true }
                .invoke(bridge) as Player else bridge
        return active as ExoPlayer
    }
}
