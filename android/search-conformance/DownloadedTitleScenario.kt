package com.legitimateapps.dulcet.search.conformance

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AndroidDownloadController
import com.legitimateapps.dulcet.core.AndroidDownloadItem
import com.legitimateapps.dulcet.core.AndroidDownloadState
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import com.legitimateapps.dulcet.playback.PlaybackService
import com.legitimateapps.dulcet.search.SearchAccount
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A downloaded track's title, offline, on each Android app's own Now Playing (spec §14.5, §16.13).
 *
 * The track is downloaded by its id alone — as a list whose rows carry only ids asks for it — through
 * the production registry, WorkManager task and worker, against a loopback server. The server then
 * goes away, and the production playback service is started twice: once to play the downloaded song,
 * and again as a relaunch, which restores the queue with identities and no titles. The restored
 * entry's title is what the platform's player shows, with nothing to read it from but the device.
 * Only the server is a loopback socket and only the Keystore is a host stand-in
 * ([HostCredentialCipher], which the platform's test class must name in its `@Config`).
 *
 * Each platform's test class calls [run] from its own test method, so phone and TV prove the same
 * thing under their own test identities.
 */
class DownloadedTitleScenario(
    private val compose: ComposeContentTestRule,
    /** The player's title text: `player.title` on the phone, `tv.player.title` on TV. */
    private val titleTag: String,
    private val screen: @Composable (SearchAccount, AndroidPlaybackState, AndroidPlaybackController) -> Unit,
) {
    private val context = RuntimeEnvironment.getApplication()
    private val server = SongServer()
    private val root = File(context.noBackupFilesDir, "downloads")
    private lateinit var account: StoredAccount

    /** The platform's test calls it from `@Before`. */
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        account = AndroidAccountCredentialStore(context).save("Server", server.url, "user", "password", true)
        // The host has no file system statistics; the device reports ample free space.
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
    }

    /** The platform's test calls it from `@After`. */
    fun tearDown() {
        runBlocking { AndroidDownloads.releaseForSignOut(context, account.id) }
        AndroidAccountCredentialStore(context).delete()
        server.close()
        root.deleteRecursively()
        shadowOf(Looper.getMainLooper()).idle()
        context.deleteDatabase("dulcet.db")
    }

    fun run() {
        val downloads = assertNotNull(AndroidDownloads.controller(context))
        assertTrue(runBlocking { downloads.awaitReconciled() })
        runBlocking { downloads.download(listOf(AndroidDownloadItem(RAW_ID, AudioContainer.Wav, null))) }
        val task = WorkManager.getInstance(context).getWorkInfosByTag("dulcet.download.account:${account.id}").get()
            .single { info -> info.tags.any { it.startsWith("dulcet.download.id:") } }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(task.id)
        awaitDownloaded(downloads)
        assertEquals(listOf("/rest/stream.view"), server.transfers.map { it.substringBefore('?') },
            "the download transferred its file once")

        // Offline from here on: nothing answers at the account's address.
        server.close()
        val searchAccount = SearchAccount(account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp)

        // The downloaded song is played, as a person plays it from a list; its queue is saved.
        val first = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val playback = assertNotNull(first.get().playback)
            playback.playSong(account.id, RAW_ID, SHOWN_TITLE)
            awaitMain("the downloaded song never became the current entry: ${playback.state.value}") {
                playback.state.value.queue.singleOrNull()?.track?.rawId == RAW_ID
            }
        } finally { first.destroy() }

        // A relaunch: a new service and controller, which restore the saved queue with no titles.
        val relaunched = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val playback = assertNotNull(relaunched.get().playback)
            compose.setContent {
                val state by playback.state.collectAsState()
                screen(searchAccount, state, playback)
            }
            awaitMain("the restored downloaded song has no title offline: ${playback.state.value}") {
                compose.onAllNodes(hasTestTag(titleTag) and hasText(TITLE)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(titleTag).assertTextEquals(TITLE)
            assertEquals(TITLE, playback.state.value.queue.single().track.title, "Up Next names it too")
            assertEquals(1, server.lookups.size, "the track's metadata was read once, with the download, while online")
            println("ANDROID DOWNLOADED TITLE OFFLINE OBSERVED tag=$titleTag lookups=${server.lookups.size} transfers=${server.transfers.size}")
        } finally { relaunched.destroy() }
    }

    /** Runs the main looper, where the controller's work completes, until [done]. */
    private fun awaitMain(failure: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            check(System.nanoTime() < deadline) { failure }
            Thread.sleep(10)
        }
    }

    private fun awaitDownloaded(controller: AndroidDownloadController) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (controller.statuses.value[RAW_ID]?.state != AndroidDownloadState.Downloaded) {
            check(System.nanoTime() < deadline) { "Not downloaded: ${controller.statuses.value}" }
            Thread.sleep(50)
        }
    }

    /** `getSong` answers the track's metadata; `stream` answers its file; anything else an empty `ok`. */
    private class SongServer : AutoCloseable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val url = "http://127.0.0.1:${socket.localPort}"
        val lookups = CopyOnWriteArrayList<String>()
        val transfers = CopyOnWriteArrayList<String>()
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var closed = false

        init {
            executor.submit {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val target = (reader.readLine() ?: return@use).split(' ')[1]
                            generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            val (type, body) = when {
                                target.startsWith("/rest/getSong") -> { lookups += target; "application/json" to SONG }
                                target.startsWith("/rest/stream") -> { transfers += target; "audio/wav" to AUDIO }
                                else -> "application/json" to OK
                            }
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            client.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            socket.close(); executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val RAW_ID = "song:opaque-downloaded-by-id"
        const val TITLE = "Canary Title Read With The Download"
        /** What the list showed when the song was played; it is never persisted with the queue. */
        const val SHOWN_TITLE = "Shown By The List"
        val AUDIO: ByteArray = "RIFF".toByteArray() + byteArrayOf(0x24, 0x70, 0, 0) + "WAVEfmt ".toByteArray() +
            ByteArray(20_000) { (it % 251).toByte() }
        val SONG: ByteArray = ("""{"subsonic-response":{"status":"ok","version":"1.16.1","song":{"id":"$RAW_ID",""" +
            """"title":"$TITLE","album":"Canary Album","albumId":"album:opaque","artist":"Canary Artist",""" +
            """"artistId":"artist:opaque","duration":1,"suffix":"wav","contentType":"audio/wav"}}}""").toByteArray()
        val OK: ByteArray = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
    }
}
