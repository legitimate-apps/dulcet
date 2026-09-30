package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The TV player's heart over the PRODUCTION session and reader (spec §16.20); only the server is a
 * loopback socket. The playing track has no cache row, so after a saved star the cache reads it as
 * unknown: the heart must send the opposite of what it shows, never a toggle of that unknown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvPlayerFavouriteSessionTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val server = RecordingServer()
    private val account = SearchAccount("provider:tvheart", server.url, "u", "p", true)
    // The library activity's session, which the app has open behind the player: it holds the
    // foreground, so the reader may send. The player's own session is never started.
    private val library = LibrarySession(context, account, foreground = true)

    @After fun close() {
        library.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aSavedHeartOnAnUncachedTrackPressedAgainSendsUnstar() {
        val state = AndroidPlaybackState(title = "Heart", phase = "Playing", playbackSessionId = "s",
            queue = listOf(AndroidQueueEntry("q1", AndroidTrack("provider:tvheart", TRACK, "Heart"))), currentIndex = 0)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlaying(account, state, null) } }
        compose.waitForIdle()
        assertTrue(!heartSelected(), "control: a track never seen is not shown as a favourite")
        press()
        settle("the heart to fill with the press") { heartSelected() }
        settle("the star saved") { server.answered("star") == 1 }
        settle("the saved star shown") { heartSelected() }
        press()
        settle("the unstar sent") { server.answered("unstar") == 1 }
        assertEquals(listOf("star", "unstar"), server.all().filter { it == "star" || it == "unstar" },
            "the second press removes the favourite; it does not star again")
        assertEquals(listOf(TRACK), server.requests("unstar").map { it["id"] })
        settle("the heart to empty") { !heartSelected() }
    }

    /**
     * The stars over the same session: a track this device has never seen has an unknown rating —
     * no star selected, said "Rating unknown", no relative adjust — never a made-up 0. The first star
     * then rates it 1; it does not "remove" a rating nobody knows.
     */
    @Test fun anUncachedTracksRatingIsUnknownAndTheFirstStarRatesIt() {
        val state = AndroidPlaybackState(title = "Heart", phase = "Playing", playbackSessionId = "s",
            queue = listOf(AndroidQueueEntry("q1", AndroidTrack("provider:tvheart", TRACK, "Heart"))), currentIndex = 0)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlaying(account, state, null) } }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("tv.player.rating").fetchSemanticsNode().config
        assertEquals("Rating unknown", row[SemanticsProperties.StateDescription])
        assertTrue(SemanticsActions.SetProgress !in row, "no relative adjust from an unknown rating")
        assertTrue((1..5).none { starSelected(it) }, "no star selected while unknown")
        compose.onNodeWithTag("tv.player.rating.1").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("tv.player.rating.1").performKeyInput { pressKey(Key.DirectionCenter) }
        settle("the rating sent") { server.answered("setRating") == 1 }
        assertEquals("1", server.requests("setRating").single()["rating"], "the first star rates 1 from unknown")
        settle("one star shown") { starSelected(1) && (2..5).none { starSelected(it) } }
    }

    private fun starSelected(star: Int): Boolean =
        compose.onNodeWithTag("tv.player.rating.$star").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    /** Focuses the heart and presses the remote's centre key on it, as a person does. */
    private fun press() {
        compose.onNodeWithTag("tv.player.favourite").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("tv.player.favourite").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun heartSelected(): Boolean =
        compose.onNodeWithTag("tv.player.favourite").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!done()) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.all()}" }
            Thread.sleep(10)
        }
    }

    /** Answers `ok` to everything and records every request. */
    private class RecordingServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        private val log = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        private val done = CopyOnWriteArrayList<String>()
        private val connections = Executors.newCachedThreadPool()

        init {
            connections.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    connections.submit { answer(client) }
                }
            }
        }

        fun requests(endpoint: String): List<Map<String, String>> = log.filter { it.first == endpoint }.map { it.second }
        fun all(): List<String> = log.map { it.first }
        fun answered(endpoint: String): Int = done.count { it == endpoint }

        private fun answer(client: java.net.Socket) = client.use {
            client.soTimeout = 5_000
            val input = client.getInputStream().bufferedReader()
            val line = input.readLine()?.split(' ') ?: return@use
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
            val uri = URI(line[1])
            val endpoint = uri.path.substringAfterLast('/').removeSuffix(".view")
            val parameters = listOfNotNull(uri.rawQuery, String(body).takeIf { it.isNotEmpty() })
                .flatMap { it.split('&') }.filter { it.isNotEmpty() }.associate {
                    val pair = it.split('=', limit = 2)
                    URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
            log += endpoint to parameters
            val envelope = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${envelope.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                write(envelope)
                flush()
            }
            done += endpoint
        }

        override fun close() {
            socket.close()
            connections.shutdownNow()
        }
    }

    private companion object {
        const val TRACK = "track-tv-heart"
    }
}
