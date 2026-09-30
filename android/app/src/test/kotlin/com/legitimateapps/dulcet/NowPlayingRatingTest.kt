package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.legitimateapps.dulcet.core.AndroidLibraryItem
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
 * The phone player's stars (spec §16.20) over the PRODUCTION session and reader: only the server is
 * a loopback socket. The stars show the playing track's rating as this device knows it, change with
 * the tap, send `setRating` for that track, take the rating away when the shown star is tapped again,
 * and — when the server refuses — go back and say so in the library's words. For TalkBack they are
 * one adjustable element.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class NowPlayingRatingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = RecordingServer()
    private val account = SearchAccount("provider:stars", server.url, "u", "p", true)
    private val controller = AndroidPlaybackController(context,
        PlaybackEndpointAccount("provider:stars", "http://127.0.0.1:9", "u", "p", true))
    private val session = LibrarySession(context, account, foreground = true)

    @After fun close() {
        session.close()
        controller.close()
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

    private fun playing(rawId: String) = AndroidPlaybackState(title = "Star Fixture", phase = "Playing",
        playbackSessionId = "s", artist = "Dulcet Fixtures", album = "Star Album",
        queue = listOf(AndroidQueueEntry("q1", AndroidTrack("provider:stars", rawId, "Star Fixture"))), currentIndex = 0)

    private fun range(tag: String): ProgressBarRangeInfo =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

    private fun shown(tag: String = "player.rating"): Int = range(tag).current.toInt()

    private fun unknown(tag: String = "player.rating"): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.let {
            SemanticsProperties.ProgressBarRangeInfo !in it && it[SemanticsProperties.StateDescription] == "Rating unknown"
        }

    private fun state(tag: String = "player.rating"): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    /** A touch on star [star] of five, as a finger would. */
    private fun tapStar(star: Int, tag: String = "player.rating") {
        compose.onNodeWithTag(tag).performTouchInput { click(Offset(width * (star - 0.5f) / 5f, height / 2f)) }
    }

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!done()) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.all()}" }
            Thread.sleep(10)
        }
    }

    @Test fun theStarsChangeWithTheTapSendSetRatingAndTheShownStarTakesItAway() {
        compose.setContent { MaterialTheme { NowPlayingScreen(account, playing(TRACK), controller, session) {} } }
        compose.waitForIdle()
        assertTrue(unknown(), "control: a track this device has never seen has an unknown rating, not a 0")
        tapStar(4)
        settle("four stars shown") { shown() == 4 }
        settle("the rating sent") { server.answered("setRating") == 1 }
        assertEquals(listOf(mapOf("id" to TRACK, "rating" to "4")),
            server.requests("setRating").map { it.filterKeys { key -> key == "id" || key == "rating" } },
            "setRating names the playing track and the stars, once")
        compose.waitForIdle()
        assertEquals(4, shown(), "the saved rating stays, for a track with no cache row")
        assertEquals(null, ShadowToast.getTextOfLatestToast(), "a saved change needs no words")

        tapStar(4)
        settle("the rating taken away") { shown() == 0 }
        settle("the clear sent") { server.answered("setRating") == 2 }
        assertEquals("0", server.requests("setRating").last()["rating"], "the shown star again sends 0, which removes it")
    }

    @Test fun aRefusedRatingGoesBackAndSaysSo() {
        server.refuse = true
        compose.setContent { MaterialTheme { NowPlayingScreen(account, playing(TRACK), controller, session) {} } }
        compose.waitForIdle()
        tapStar(3)
        settle("the rating sent") { server.answered("setRating") == 1 }
        settle("the refusal to be told") { ShadowToast.getTextOfLatestToast() != null }
        assertTrue(ShadowToast.getTextOfLatestToast().toString().startsWith("Couldn't save that change — "),
            "the library's words for a change that did not save: ${ShadowToast.getTextOfLatestToast()}")
        settle("the stars to go back to what was known: nothing — unknown, never a made-up 0") { unknown() }
    }

    /**
     * An outcome is kept per target AND field: a heart saved on the same track after its rating was
     * refused does not erase the refusal before a screen says it. Composed only once both outcomes
     * have arrived, so whether the refusal survives is decided by the session, not by frame timing.
     */
    @Test fun aRefusedRatingThenASavedHeartOnTheSameTrackAreBothKeptAndTheRefusalIsSaid() {
        server.refuse = true
        val target = com.legitimateapps.dulcet.core.AndroidLibraryEntity(com.legitimateapps.dulcet.core.AndroidLibraryEntityKind.Track, TRACK)
        session.setRating(target, 3)
        settle("the rating refused") { server.answered("setRating") == 1 && session.observation.value.changeOutcomes.isNotEmpty() }
        session.setFavourite(target, true)
        settle("both outcomes told") { session.observation.value.changeOutcomes.size == 2 }
        val told = session.observation.value.changeOutcomes
        assertEquals(listOf(com.legitimateapps.dulcet.core.AndroidLibraryChangeField.Rating, com.legitimateapps.dulcet.core.AndroidLibraryChangeField.Favourite),
            told.map { it.field }, "setup: the refusal, then the saved heart")
        assertTrue(told[1] is com.legitimateapps.dulcet.core.AndroidLibraryChangeOutcome.Saved, "setup: the heart saved: $told")
        compose.setContent { MaterialTheme { NowPlayingScreen(account, playing(TRACK), controller, session) {} } }
        settle("the refusal to be said") { ShadowToast.getTextOfLatestToast() != null }
        assertTrue(ShadowToast.getTextOfLatestToast().toString().startsWith("Couldn't save that change — "),
            "the refused rating is said, though a heart was saved after it: ${ShadowToast.getTextOfLatestToast()}")
        assertEquals(1, ShadowToast.shownToastCount(), "said once: the saved heart needs no words")
    }

    /**
     * TalkBack: one element, named Rating, stating its value. Unknown, it offers only absolute sets —
     * no range and no setProgress, since a step from an unknown rating would overwrite the server's
     * with 1. Known, it is adjusted in whole stars through setProgress.
     */
    @Test fun forTalkBackTheStarsAreOneAdjustableElement() {
        compose.setContent { MaterialTheme { NowPlayingScreen(account, playing(TRACK), controller, session) {} } }
        compose.waitForIdle()
        var node = compose.onNodeWithTag("player.rating").fetchSemanticsNode()
        assertEquals(listOf("Rating"), node.config[SemanticsProperties.ContentDescription])
        assertTrue(node.children.isEmpty(), "the five stars are not five screen-reader stops")
        assertEquals("Rating unknown", state())
        assertTrue(SemanticsActions.SetProgress !in node.config, "no relative adjust from an unknown rating")
        val offered = node.config[SemanticsActions.CustomActions]
        assertEquals((1..5).map { "Rate $it of 5 stars" }, offered.map { it.label })
        compose.runOnIdle { offered[1].action() }
        settle("two stars shown") { SemanticsProperties.ProgressBarRangeInfo in compose.onNodeWithTag("player.rating").fetchSemanticsNode().config && shown() == 2 }
        settle("the absolute rating sent") { server.answered("setRating") == 1 }
        assertEquals("2", server.requests("setRating").single()["rating"])

        node = compose.onNodeWithTag("player.rating").fetchSemanticsNode()
        assertEquals(0f..5f, range("player.rating").range)
        assertEquals(4, range("player.rating").steps, "whole stars: four steps between 0 and 5")
        assertEquals("2 of 5 stars", state())
        compose.onNodeWithTag("player.rating").performSemanticsAction(SemanticsActions.SetProgress) { it(2.6f) }
        settle("three stars shown") { shown() == 3 }
        assertEquals("3 of 5 stars", state())
        settle("the rating sent") { server.answered("setRating") == 2 }
        assertEquals("3", server.requests("setRating").last()["rating"])
        compose.onNodeWithTag("player.rating").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        settle("the rating taken away") { shown() == 0 }
        assertEquals("Not rated", state(), "a known 0 is Not rated, distinct from unknown")
    }

    /** The row menu's Rate… opens the stars; a tap rates the song, and the row's value is what it shows. */
    @Test fun aTrackRowsMenuRatesTheSong() {
        var rating by mutableStateOf(2)
        val rated = mutableListOf<Int>()
        val track = AndroidLibraryItem.Track(TRACK, "Star Fixture", "album-stars", "Star Album", "Dulcet Fixtures", null, 1, 1,
            200_000, null, null, favourite = false, rating = 2, playCount = null,
            playability = com.legitimateapps.dulcet.core.AndroidLibraryPlayability.Streamable, metadataMissing = false)
        compose.setContent {
            MaterialTheme {
                TrackRow(trackWith(track, rating), 1, false, androidx.compose.ui.Modifier, onUnavailable = {},
                    favouriteTag = "album.track.0.favourite", onAddToPlaylist = {},
                    onRate = { rated += it; rating = it }) {}
            }
        }
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onNodeWithTag("album.track.0.menu.rate").performClick()
        compose.waitForIdle()
        assertEquals(2, shown("album.track.0.rating"), "the stars open at the row's rating")
        tapStar(5, "album.track.0.rating")
        compose.waitForIdle()
        assertEquals(listOf(5), rated)
        assertEquals(5, shown("album.track.0.rating"), "the stars show the change at once")
        tapStar(5, "album.track.0.rating")
        compose.waitForIdle()
        assertEquals(listOf(5, 0), rated, "the shown star takes the rating away")
        compose.onNodeWithTag("album.track.0.rating.done").performClick()
    }

    private fun trackWith(track: AndroidLibraryItem.Track, rating: Int) = track.copy(rating = rating)

    /** Answers `ok` — or, when [refuse], error 70 to `setRating` — and records every request. */
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
            val envelope = if (refuse && endpoint == "setRating") {
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
        const val TRACK = "track-stars"
    }
}
