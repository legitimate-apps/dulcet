package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.SavedAccountConnection
import com.legitimateapps.dulcet.search.SearchAccount
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
 * The TV player's lyrics panel as the app arranges it: the library activity's session brought the
 * reader online and was then stopped behind the player, whose own session is never started. Over
 * the PRODUCTION session and reader; only the server is a loopback socket, which holds the lyrics
 * answer so the moment before it lands can be looked at.
 *
 * Nothing stored for the track while the live read is in flight is "loading", never "connect to
 * your server": the stored miss is not published over a read that is still coming (Apple's panel
 * shows it as loading too). And the reader behind a stopped library still reads live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvLyricsBehindTheLibraryTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LyricsServer()
    private val account = SearchAccount("provider:tvlyrics", server.url, "u", "p", true)
    // The person connected this account in this process, as the library behind the player was: the
    // player's own session then reads too (spec §13.1).
    init { SavedAccountConnection.connectedOnTheForm(account.providerInstanceId) }
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

    @Test fun aTrackNeverSeenShowsLoadingUntilTheLiveReadAnswersBehindAStoppedLibrary() {
        library.start()
        settle("the library online") { library.connection.value is LibraryConnectionState.Online }
        // The player covers the library activity: its session stops, and the player's never starts.
        library.stop()
        val state = AndroidPlaybackState(title = "Words", phase = "Playing", playbackSessionId = "s",
            queue = listOf(AndroidQueueEntry("q1", AndroidTrack("provider:tvlyrics", TRACK, "Words"))), currentIndex = 0)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlaying(account, state, null) } }
        compose.waitForIdle()
        compose.onNodeWithTag("tv.player.lyrics").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        settle("the lyrics asked for") { server.all().contains("getLyricsBySongId") }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("tv.player.lyrics.statement").fetchSemanticsNodes().isEmpty(),
            "online with the answer on its way, the panel states nothing; frames=${observed().lyrics}")
        assertTrue(compose.onAllNodesWithTag("tv.player.lyrics.loading").fetchSemanticsNodes().isNotEmpty(), "it is loading")
        server.release()
        settle("a live lyrics answer") { observed().lyrics.any { it.freshness == AndroidLibraryFreshness.Live } }
        assertEquals(listOf(AndroidLibraryFreshness.Live to AndroidLyricsState.Lyrics),
            observed().lyrics.map { it.freshness to it.state }, "the live answer is the only thing the panel published")
        compose.waitForIdle()
        compose.onNodeWithTag("tv.player.lyrics.line.0").assertTextEquals("Line one")
    }

    private fun observed(): LibraryObservationState =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(LibraryObservation)).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(LibraryObservation) ?: LibraryObservationState()

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!runCatching(done).getOrDefault(false)) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.all()} lyrics=${runCatching { observed().lyrics }.getOrNull()}" }
            Thread.sleep(10)
        }
    }

    private class LyricsServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        private val log = CopyOnWriteArrayList<String>()
        private val connections = Executors.newCachedThreadPool()
        private val held = CountDownLatch(1)

        /** Lets the held lyrics answer go. */
        fun release() = held.countDown()

        init {
            connections.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    connections.submit { answer(client) }
                }
            }
        }

        fun all(): List<String> = log.toList()

        private fun answer(client: java.net.Socket) = client.use {
            client.soTimeout = 25_000
            val input = client.getInputStream().bufferedReader()
            val line = input.readLine()?.split(' ') ?: return@use
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
            val endpoint = URI(line[1]).path.substringAfterLast('/').removeSuffix(".view")
            log += endpoint
            if (endpoint == "getLyricsBySongId") held.await(20, TimeUnit.SECONDS)
            val payload = when (endpoint) {
                "getScanStatus" -> ""","scanStatus":{"scanning":false,"count":1,"lastScan":"2026-01-01T00:00:00Z"}"""
                "getMusicFolders" -> ""","musicFolders":{"musicFolder":[{"id":1,"name":"Music"}]}"""
                "getOpenSubsonicExtensions" -> ""","openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]"""
                "getLyricsBySongId" -> ""","lyricsList":{"structuredLyrics":[{"lang":"eng","synced":true,"line":[{"start":0,"value":"Line one"},{"start":5000,"value":"Line two"}]}]}"""
                else -> ""
            }
            val envelope = """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true$payload}}""".toByteArray()
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${envelope.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                write(envelope)
                flush()
            }
        }

        override fun close() {
            socket.close()
            connections.shutdownNow()
        }
    }

    private companion object {
        const val TRACK = "track-tv-lyrics"
    }
}
