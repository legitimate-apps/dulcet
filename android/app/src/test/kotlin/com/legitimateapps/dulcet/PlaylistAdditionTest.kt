package com.legitimateapps.dulcet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The actual sheet, session and editor; only the HTTP server is controlled by the test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistAdditionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = PlaylistServer()
    private val account = SearchAccount("provider:playlist-add", server.url, "u", "p", true)
    private val session = LibrarySession(context, account, foreground = true)
    private val outcomes = CopyOnWriteArrayList<AndroidPlaylistOutcome>()
    private val registration = session.playlists.addOutcomeListener { outcomes += it }
    private val presentation = mutableStateOf(0)
    private var dismissals = 0

    @After fun close() {
        server.albumGate.countDown()
        registration.close()
        session.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        server.close()
        context.deleteDatabase("dulcet.db")
        assertTrue(closed, "reader closed before the next test")
    }

    private fun show(addition: PlaylistAddition) {
        compose.setContent {
            MaterialTheme {
                key(presentation.value) {
                    // Deliberately retain the sheet after dismissal to cover the outgoing frame.
                    AddToPlaylistSheet(account, session, addition) { dismissals++ }
                }
            }
        }
        settle("playlist choices") { compose.onNodeWithTag(CHOICE).fetchSemanticsNode().config.contains(SemanticsActions.OnClick) }
    }

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            compose.waitForIdle()
            if (runCatching(done).getOrDefault(false)) return
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.requests}" }
            Thread.sleep(10)
        }
    }

    /** Two already-delivered taps before recomposition, including a stale semantics callback. */
    private fun rapidDoubleTap(tag: String): () -> Boolean {
        val tap = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread { tap(); tap() }
        return tap
    }

    private fun saved(count: Int) = settle("$count successful playlist writes and read-backs") {
        outcomes.count { it is AndroidPlaylistOutcome.Saved || it is AndroidPlaylistOutcome.Created } == count
    }

    @Test fun rapidSongTapsAppendOnceAndALaterPresentationCanAddTheSameSongAgain() {
        show(PlaylistAddition.Songs(listOf("song-a"), "Song A"))
        val oldTap = rapidDoubleTap(CHOICE)
        saved(1)
        assertEquals(listOf("song-a"), server.entries(), "the duplicate tap did not become a duplicate entry")
        assertEquals(1, server.writes.size)
        assertEquals(1, dismissals)
        compose.onNodeWithTag(CHOICE).assertIsNotEnabled()
        compose.onNodeWithTag("playlists.add.new").assertIsNotEnabled()
        // A queued click from the outgoing frame must remain ignored even after the core answered.
        compose.runOnUiThread { oldTap() }
        var recorded = false
        session.playlists.pendingChanges { recorded = true }
        settle("all operations after the outgoing tap recorded") { recorded }
        assertEquals(1, dismissals, "the successful sheet cannot accept a queued outgoing tap")
        compose.runOnUiThread { presentation.value++ }
        compose.onNodeWithTag(CHOICE).assertIsEnabled().performClick()
        saved(2)
        assertEquals(listOf("song-a", "song-a"), server.entries(), "a deliberate later addition keeps legitimate duplicates")
        assertEquals(2, server.writes.size)
    }

    @Test fun albumReadDisablesEveryDestinationAndAddsItsTracksOnlyOnce() {
        server.holdAlbum = true
        show(PlaylistAddition.Album("album-a", "Album A", listOf("song-a", "song-b")))
        rapidDoubleTap(CHOICE)
        settle("held album read") { server.requests.contains("getAlbum") }
        compose.onNodeWithTag(CHOICE).assertIsNotEnabled()
        compose.onNodeWithTag("playlists.add.item.1").assertIsNotEnabled()
        compose.onNodeWithTag("playlists.add.new").assertIsNotEnabled()
        compose.onNodeWithTag("playlists.add.submitting").assertExists()
        assertEquals(emptyList(), server.writes, "no write before the album has answered")
        System.getenv("DULCET_TEST_EVIDENCE_DIR")?.let { directory ->
            // Draw the dialog's own root; PixelCopy requires a device window redraw absent on Robolectric.
            val view = (compose.onNodeWithTag("playlists.add").fetchSemanticsNode().root as ViewRootForTest).view
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { view.draw(Canvas(image)) }
            File(directory).mkdirs()
            File(directory, "playlist-adding.jpg").outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        server.albumGate.countDown()
        saved(1)
        assertEquals(listOf("song-a", "song-b"), server.entries())
        assertEquals(1, server.writes.size)
        assertEquals(1, dismissals)
    }

    @Test fun aRefusedAlbumReadExplainsTheFailureAndAllowsAnIntentionalRetry() {
        server.refuseAlbum = true
        show(PlaylistAddition.Album("album-a", "Album A", listOf("song-a", "song-b")))
        compose.onNodeWithTag(CHOICE).performClick()
        settle("failure message") {
            compose.onNodeWithTag("playlists.add.note").fetchSemanticsNode()
                .config[SemanticsProperties.Text].any { it.text.isNotBlank() }
        }
        compose.onNodeWithTag(CHOICE).assertIsEnabled()
        compose.onNodeWithTag("playlists.add.new").assertIsEnabled()
        compose.onNodeWithTag("playlists.add.submitting").assertDoesNotExist()
        assertEquals(0, dismissals)
        assertEquals(emptyList(), server.writes)
        server.refuseAlbum = false
        compose.onNodeWithTag(CHOICE).performClick()
        saved(1)
        assertEquals(listOf("song-a", "song-b"), server.entries())
        assertEquals(1, server.writes.size)
        assertEquals(1, dismissals)
    }

    @Test fun rapidCreateConfirmationCreatesOnePlaylistWithTheIntendedSongs() {
        show(PlaylistAddition.Songs(listOf("song-a", "song-a"), "New mix"))
        compose.onNodeWithTag("playlists.add.new").performClick()
        compose.onNodeWithTag("playlists.add.name").performTextReplacement("New mix")
        rapidDoubleTap("playlists.add.name.confirm")
        saved(1)
        assertEquals(listOf("song-a", "song-a"), server.entries("created"), "duplicates intentionally supplied are retained")
        assertEquals(listOf("createPlaylist"), server.writes.toList())
        assertEquals(1, dismissals)
    }

    /** Stateful protocol fixture: final server entries are the oracle, not a count of UI callbacks. */
    private class PlaylistServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        val writes = CopyOnWriteArrayList<String>()
        val albumGate = CountDownLatch(1)
        @Volatile var holdAlbum = false
        @Volatile var refuseAlbum = false
        private val songs = mutableMapOf("mix" to emptyList<String>(), "other" to emptyList())
        private val names = mutableMapOf("mix" to "Road mix", "other" to "Weekend mix")
        private var creates = 0
        private val workers = Executors.newCachedThreadPool()
        init {
            workers.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    workers.submit { answer(client) }
                }
            }
        }
        @Synchronized fun entries(id: String = "mix"): List<String> = songs.getValue(id).toList()
        private fun track(id: String) = """{"id":"$id","title":"$id","albumId":"album-a","album":"Album A","artist":"Fixture","duration":10,"isDir":false}"""
        @Synchronized private fun playlist(id: String) = """{"id":"$id","name":"${names.getValue(id)}","owner":"u","readonly":false,"songCount":${songs.getValue(id).size},"entry":[${songs.getValue(id).joinToString(",", transform = ::track)}]}"""

        private fun answer(client: Socket) = client.use {
            client.soTimeout = 5_000
            val input = client.getInputStream().bufferedReader()
            val first = input.readLine()?.split(' ') ?: return@use
            val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = CharArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                check(count > 0) { "truncated request body" }
                read += count
            }
            val uri = URI(first[1])
            val endpoint = uri.path.substringAfterLast('/').removeSuffix(".view")
            val parameters = listOfNotNull(uri.rawQuery, String(body).takeIf(String::isNotEmpty))
                .flatMap { it.split('&') }.filter(String::isNotEmpty).map {
                    val pair = it.split('=', limit = 2)
                    URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
            fun all(name: String) = parameters.filter { it.first == name }.map { it.second }
            fun one(name: String) = all(name).single()
            requests += endpoint
            if (endpoint == "getAlbum" && holdAlbum) check(albumGate.await(30, TimeUnit.SECONDS)) { "album gate not released" }
            val field = synchronized(this) {
                when (endpoint) {
                    "getMusicFolders" -> "\"musicFolders\":{\"musicFolder\":[]}"
                    "getPlaylists" -> "\"playlists\":{\"playlist\":[${songs.keys.joinToString(",", transform = ::playlist)}]}"
                    "getPlaylist" -> "\"playlist\":${playlist(one("id"))}"
                    "getAlbum" -> "\"album\":{\"id\":\"album-a\",\"name\":\"Album A\",\"songCount\":2,\"song\":[${track("song-a")},${track("song-b")}] }"
                    "updatePlaylist" -> {
                        val id = one("playlistId")
                        songs[id] = songs.getValue(id) + all("songIdToAdd")
                        writes += endpoint
                        ""
                    }
                    "createPlaylist" -> {
                        val id = all("playlistId").firstOrNull() ?: if (++creates == 1) "created" else "created-$creates"
                        songs[id] = all("songId")
                        if (all("name").isNotEmpty()) names[id] = one("name")
                        writes += endpoint
                        "\"playlist\":${playlist(id)}"
                    }
                    "getScanStatus" -> "\"scanStatus\":{\"scanning\":false,\"count\":2,\"lastScan\":1234}"
                    else -> ""
                }
            }
            val envelope = if (endpoint == "getAlbum" && refuseAlbum) {
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Not found"}}}"""
            } else """{"subsonic-response":{"status":"ok","version":"1.16.1"${if (field.isEmpty()) "" else ",$field"}}}"""
            val bytes = envelope.toByteArray()
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes)
                flush()
            }
        }
        override fun close() { albumGate.countDown(); socket.close(); workers.shutdownNow() }
    }

    private companion object { const val CHOICE = "playlists.add.item.0" }
}
