package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
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
import org.robolectric.shadows.ShadowToast

/**
 * The phone player's heart (spec §16.20) over the PRODUCTION session and reader: only the server is a
 * loopback socket. The heart shows the playing track's state as this device knows it, fills with the
 * tap, sends `star` for that track, and — when the server refuses the change — goes hollow again and
 * says so in the library's own words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class NowPlayingFavouriteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = RecordingServer()
    private val account = SearchAccount("provider:heart", server.url, "u", "p", true)
    private val controller = AndroidPlaybackController(context,
        PlaybackEndpointAccount("provider:heart", "http://127.0.0.1:9", "u", "p", true))
    private val session = LibrarySession(context, account, foreground = true)

    @After fun close() {
        session.close()
        controller.close()
        // Waited for, so the next test's reader does not spend its first seconds waiting on this one.
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

    private fun playing(rawId: String) = AndroidPlaybackState(title = "Heart Fixture", phase = "Playing",
        playbackSessionId = "s", artist = "Dulcet Fixtures", album = "Heart Album",
        queue = listOf(AndroidQueueEntry("q1", AndroidTrack("provider:heart", rawId, "Heart Fixture"))), currentIndex = 0)

    private fun heartSelected(): Boolean =
        compose.onNodeWithTag("player.favourite").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!done()) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.all()}" }
            Thread.sleep(10)
        }
    }

    @Test fun theHeartFillsWithTheTapAndSendsStarForThePlayingTrack() {
        compose.setContent { MaterialTheme { NowPlayingScreen(account, playing(TRACK), controller, session) {} } }
        compose.waitForIdle()
        assertTrue(!heartSelected(), "control: a track never seen is not shown as a favourite")
        compose.onNodeWithTag("player.favourite").performClick()
        settle("the heart to fill") { heartSelected() }
        settle("the star to be sent") { server.requests("star").isNotEmpty() }
        assertEquals(listOf(TRACK), server.requests("star").map { it["id"] }, "star names the playing track, once")
        // Held filled once saved: the adopted value stands with no read.
        settle("the send answered") { server.answered("star") == 1 }
        compose.waitForIdle()
        assertTrue(heartSelected(), "the saved favourite stays filled")
        assertEquals(null, ShadowToast.getTextOfLatestToast(), "a saved change needs no words")
    }

    @Test fun aRefusedChangeEmptiesTheHeartAgainAndSaysSo() {
        server.refuse = true
        compose.setContent { MaterialTheme { NowPlayingScreen(account, playing(TRACK), controller, session) {} } }
        compose.waitForIdle()
        compose.onNodeWithTag("player.favourite").performClick()
        settle("the star to be sent") { server.answered("star") == 1 }
        settle("the refusal to be told") { ShadowToast.getTextOfLatestToast() != null }
        assertTrue(ShadowToast.getTextOfLatestToast().toString().startsWith("Couldn't save that change — "),
            "the library's words for a change that did not save: ${ShadowToast.getTextOfLatestToast()}")
        settle("the heart to empty") { !heartSelected() }
    }

    @Test fun theHeartFollowsThePlayingTrack() {
        val state = androidx.compose.runtime.mutableStateOf(playing(TRACK))
        compose.setContent { MaterialTheme { NowPlayingScreen(account, state.value, controller, session) {} } }
        compose.onNodeWithTag("player.favourite").performClick()
        settle("the first track's heart to fill") { heartSelected() }
        state.value = playing("track-next")
        compose.waitForIdle()
        settle("the next track's heart, which is its own") { !heartSelected() }
        compose.onNodeWithTag("player.favourite").performClick()
        settle("both sent") { server.requests("star").size == 2 }
        assertEquals(listOf(TRACK, "track-next"), server.requests("star").map { it["id"] })
    }

    /** Answers `ok` — or, when [refuse], error 70 to `star` — and records every request. */
    private class RecordingServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        @Volatile var refuse = false
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
            val envelope = if (refuse && endpoint == "star") {
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Not found"}}}"""
            } else {
                """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
            }.toByteArray()
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
        const val TRACK = "track-heart"
    }
}
