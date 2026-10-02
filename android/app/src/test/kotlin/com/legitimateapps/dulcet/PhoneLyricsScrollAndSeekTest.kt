package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
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

/**
 * The phone's lyrics panel promises that the lit line is kept in view and that a tap on a synced line
 * seeks to it (spec §18.4). Over the PRODUCTION session and reader — only the server is a loopback
 * socket — with a layer of [LINES] lines in a panel far too short to show them all.
 *
 * Every claim carries its control: the lit line is first off screen (so a panel that never scrolls
 * cannot pass), a second line seeks to its own start (so a hard-coded value cannot pass), and the
 * cases that must not seek are tapped exactly as the ones that must.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneLyricsScrollAndSeekTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LyricsServer()
    private val account = SearchAccount("provider:lyrics-scroll", server.url, "u", "p", true)
    private val session = LibrarySession(context, account, foreground = true)
    private val seeks = CopyOnWriteArrayList<Long>()

    @After fun close() {
        session.close()
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

    private fun state(positionMilliseconds: Long = 0, seekable: Boolean = true) = AndroidPlaybackState(
        title = "Words", phase = "Paused", positionMilliseconds = positionMilliseconds, durationMilliseconds = LINES * STEP,
        playbackSessionId = "s", queueEntryId = "q1", seekable = seekable,
        queue = listOf(AndroidQueueEntry("q1", AndroidTrack("provider:lyrics-scroll", TRACK, "Words"))), currentIndex = 0)

    /** The panel in a box a few lines tall, so most of the layer is below the fold. */
    private fun show(playback: androidx.compose.runtime.MutableState<AndroidPlaybackState>, drawn: String = "lyrics.line.0") {
        session.start()
        settle("the session online") { session.connection.value is LibraryConnectionState.Online }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(PANEL_HEIGHT.dp)) { LyricsPanel(session, playback.value) { seeks += it } }
            }
        }
        settle("the lyrics drawn") { exists(drawn) }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** On screen: drawn at all (a lazy list composes only what it shows) and within the window. */
    private fun displayed(tag: String): Boolean = exists(tag) && runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(done).getOrDefault(false)) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.all()}" }
            Thread.sleep(10)
        }
    }

    @Test fun theLitLineIsScrolledIntoViewWhenPlaybackReachesIt() {
        val playback = mutableStateOf(state(0))
        show(playback)
        assertTrue(displayed("lyrics.line.0"), "fixture: the first line is lit and in view at the start")
        assertTrue(!displayed("lyrics.line.$FAR"), "control: line $FAR is below the fold before playback reaches it")

        playback.value = state(FAR * STEP)
        settle("line $FAR in view") { displayed("lyrics.line.$FAR") }
        assertTrue(!displayed("lyrics.line.0"), "an early line has scrolled out of view")
        assertTrue(compose.onNodeWithTag("lyrics.line.$FAR").fetchSemanticsNode().config
            .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false }, "the line in view is the lit one")
    }

    @Test fun aTapOnASyncedLineSeeksToThatLinesOwnStart() {
        show(mutableStateOf(state(0)))
        compose.onNodeWithTag("lyrics.line.1").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1 * STEP), seeks.toList(), "line 1 seeks to its start")
        compose.onNodeWithTag("lyrics.line.2").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1 * STEP, 2 * STEP), seeks.toList(), "control: line 2 seeks to its own, different, start")
    }

    @Test fun aTapOnALineScrolledToFromFarBelowSeeksToItToo() {
        val playback = mutableStateOf(state(FAR * STEP))
        show(playback, drawn = "lyrics.line.$FAR")
        settle("line $FAR in view") { displayed("lyrics.line.$FAR") }
        compose.onNodeWithTag("lyrics.line.$FAR").performClick()
        compose.waitForIdle()
        assertEquals(listOf(FAR * STEP), seeks.toList())
    }

    @Test fun aTapDoesNotSeekWhenTheStreamIsNotSeekable() {
        show(mutableStateOf(state(0, seekable = false)))
        compose.onNodeWithTag("lyrics.line.1").performClick()
        compose.waitForIdle()
        assertEquals(emptyList(), seeks.toList(), "an unseekable stream is never asked to seek")
    }

    @Test fun aTapOnPlainLyricsDoesNotSeek() {
        server.synced = false
        show(mutableStateOf(state(0)))
        assertTrue(exists("lyrics.plain") && !exists("lyrics.synced"), "fixture: the layer is drawn as plain")
        compose.onNodeWithTag("lyrics.line.1").performClick()
        compose.waitForIdle()
        assertEquals(emptyList(), seeks.toList(), "plain lyrics have no times to seek to")
    }

    @Test fun controlTheSameTapSeeksOnceTheStreamIsSeekableAndTheLyricsAreSynced() {
        show(mutableStateOf(state(0, seekable = true)))
        assertTrue(exists("lyrics.synced"), "fixture: the layer is drawn as synced")
        compose.onNodeWithTag("lyrics.line.1").performClick()
        compose.waitForIdle()
        assertEquals(listOf(STEP), seeks.toList(), "the negative cases above differ from this only in what they turn off")
    }

    /** Answers the library's start-up calls and a [LINES]-line layer, synced unless [synced] is cleared. */
    private class LyricsServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        @Volatile var synced = true
        private val log = CopyOnWriteArrayList<String>()
        private val connections = Executors.newCachedThreadPool()

        init {
            connections.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    connections.submit { answer(client) }
                }
            }
        }

        fun all(): List<String> = log.toList()

        private fun layer(): String {
            val lines = (0 until LINES.toInt()).joinToString(",") { index ->
                if (synced) """{"start":${index * STEP},"value":"Line $index"}""" else """{"value":"Line $index"}"""
            }
            return """{"lang":"eng","synced":$synced,"line":[$lines]}"""
        }

        private fun answer(client: java.net.Socket) = client.use {
            client.soTimeout = 25_000
            val input = client.getInputStream().bufferedReader()
            val line = input.readLine()?.split(' ') ?: return@use
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
            val endpoint = URI(line[1]).path.substringAfterLast('/').removeSuffix(".view")
            log += endpoint
            val payload = when (endpoint) {
                "getScanStatus" -> ""","scanStatus":{"scanning":false,"count":1,"lastScan":"2026-01-01T00:00:00Z"}"""
                "getMusicFolders" -> ""","musicFolders":{"musicFolder":[{"id":1,"name":"Music"}]}"""
                "getOpenSubsonicExtensions" -> ""","openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]"""
                "getLyricsBySongId" -> ""","lyricsList":{"structuredLyrics":[${layer()}]}"""
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
        const val TRACK = "track-lyrics-scroll"
        const val LINES = 60L
        /** Milliseconds between one line's start and the next's. */
        const val STEP = 4_000L
        /** A line far below the fold of a panel [PANEL_HEIGHT]dp tall. */
        const val FAR = 50
        const val PANEL_HEIGHT = 240
    }
}
