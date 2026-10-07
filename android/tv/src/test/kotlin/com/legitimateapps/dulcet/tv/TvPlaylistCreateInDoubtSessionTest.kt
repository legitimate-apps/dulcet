package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
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
 * A create in doubt on the TV over TvLibraryEntry and the PRODUCTION session, reader and playlist
 * editor (spec §18.6); only the server is a loopback fixture. The server makes the playlist without
 * the song it was sent and answers 502, so the answer is lost; at the next flush the core finds one
 * new playlist of that name that is not what it sent, adopts nothing, and asks. The question is presented by the library entry over the
 * screen showing; the server's playlists are the oracle for what the answers wrote, and a restore
 * after the process ends shows the page followed the local id to the server's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvPlaylistCreateInDoubtSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply {
        playlists = mutableMapOf()
        loseCreateAnswers = true
        holdStreams = true
    }
    private val account = SearchAccount("provider:tv-create-in-doubt", server.url, "u", "p", true)
    private var controller: AndroidPlaybackController? = null

    @After fun close() {
        controller?.close()
        closeReader()
        server.close()
        context.deleteDatabase("dulcet.db")
        context.getSharedPreferences("dulcet.ui", 0).edit().clear().commit()
    }

    private fun closeReader() {
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test fun aCreateWhoseAnswerWasLostIsAskedOverTheScreenAndKeepingItWritesNoSecondPlaylist() {
        val playback = AndroidPlaybackController(context,
            PlaybackEndpointAccount(account.providerInstanceId, server.url, "u", "p", true)).also { controller = it }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { TvLibraryEntry(account, playback = playback) { _, _ -> } } }
        compose.waitForIdle()
        await("the first home card to take focus") { focused("library.home.0.item.0") }
        keyAt("library.home.0.item.0", Key.DirectionCenter)
        await("the album's first track") { exists("album.track.0") }
        val song = server.requests("getAlbum").last()["id"] + "-song-1"

        // Add to Playlist… → New Playlist… → a name → Create, from the track row's queue button.
        focus("album.track.0")
        repeat(4) { if (!focused("album.track.0.queue")) keyAt(focusedTag(), Key.DirectionRight) }
        assertFocused("album.track.0.queue")
        keyAt("album.track.0.queue", Key.DirectionCenter)
        await("the queue dialog") { focused("queue.add.playNext") }
        stepTo("queue.add.playlist", Key.DirectionDown, from = "queue.add.playNext")
        keyAt("queue.add.playlist", Key.DirectionCenter)
        await("the chooser on New Playlist…") { focused("playlists.add.new") }
        keyAt("playlists.add.new", Key.DirectionCenter)
        await("the name field") { focused("playlists.add.name") }
        compose.onNodeWithTag("playlists.add.name").selectWithRemote().performTextReplacement(NAME)
        keyAt("playlists.add.name", Key.DirectionDown)
        assertFocused("playlists.add.name.confirm")
        keyAt("playlists.add.name.confirm", Key.DirectionCenter)

        await("the create to reach the server and lose its answer") { server.requests("createPlaylist").size == 1 }
        assertEquals(mapOf("created-1" to emptyList()), server.playlistsNamed(NAME), "landed without its song")
        await("the chooser closed") { !exists("playlists.add") }
        val before = server.endpoints().size

        // The core looks at a create whose answer was lost when it next reconnects: here, the TV app
        // going to the background and coming back, which stops the session and starts it again.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        // The core finds one new playlist of the name, not holding what was sent: it asks.
        await("the question, over the album, Keep focused", 60) {
            shadowOf(Looper.getMainLooper()).idle()
            exists("playlists.question") && focused("playlists.question.keep")
        }
        assertTrue(texts("playlists.question.line").any { NAME in it }, "the question names the playlist: ${texts("playlists.question.line")}")
        assertTrue(exists("album.track.0"), "asked over the screen that was showing")
        assertTrue("getPlaylists" in server.endpoints().drop(before), "the core listed the server's playlists to look for it")

        // Decide later: the playlist's page says it waits, and asks again on Choose….
        stepTo("playlists.question.later", Key.DirectionDown, from = "playlists.question.keep")
        keyAt("playlists.question.later", Key.DirectionCenter)
        await("the question put off") { !exists("playlists.question") }
        assertEquals(1, server.requests("createPlaylist").size, "Decide later sends nothing")
        back()
        await("Back on the library") { exists("library.view.playlists") }
        focus("library.view.playlists")
        keyAt("library.view.playlists", Key.DirectionCenter)
        await("the playlist in the grid") { cardWithText("library.playlists.item.", NAME) != null }
        val card = cardWithText("library.playlists.item.", NAME)!!
        focus(card)
        keyAt(card, Key.DirectionCenter)
        await("the page says it waits") { exists("playlist.question.waiting") }
        focus("playlist.question.choose")
        keyAt("playlist.question.choose", Key.DirectionCenter)
        await("asked again, Keep focused") { focused("playlists.question.keep") }
        keyAt("playlists.question.keep", Key.DirectionCenter)

        // Kept: the server's playlist is this one, no second create is sent, and the page follows it
        // -- it opens the server's playlist as its surface once the core says the local one is it.
        await("the question answered and the page on the server's playlist") {
            shadowOf(Looper.getMainLooper()).idle()
            val seen = observation()
            !exists("playlists.question") && !exists("playlist.question.waiting") &&
                seen.playlistOutcomes.any { it is AndroidPlaylistOutcome.Created && it.playlistId == "created-1" } &&
                seen.surfaces.keys.any { it.endsWith("created-1") }
        }
        assertEquals(1, server.requests("createPlaylist").size, "keeping it writes no second playlist")
        assertEquals(setOf("created-1"), server.playlistsNamed(NAME).keys, "one playlist of that name on the server")
        assertTrue(texts("playlist.title").any { it == NAME }, "the page shows the kept playlist: ${texts("playlist.title")}")
        // The page followed the local id to the server's: the route it saves survives the process,
        // whose reader alone knew the local id stood for this playlist.
        closeReader()
        restoration.emulateSavedInstanceStateRestore()
        await("the page restored by a new process on the kept playlist") {
            shadowOf(Looper.getMainLooper()).idle()
            texts("playlist.title").any { it == NAME } && exists("playlist.empty") &&
                observation().surfaces.keys.any { it.endsWith("created-1") }
        }
        assertTrue(!exists("playlist.unavailable"), "the restored page is not a playlist that is gone")
        assertEquals(1, observation().surfaces.size, "the restored session opened the server's playlist and nothing else: " +
            "${observation().surfaces.keys}")
        println("TV CREATE IN DOUBT OBSERVED song=$song kept=created-1 creates=${server.requests("createPlaylist").size} " +
            "server=${server.playlistsNamed(NAME)} outcome=${if (exists("playlist.outcome")) texts("playlist.outcome") else "none"}")
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private fun observation(): LibraryObservationState =
        compose.onNodeWithTag("playlist.surface").fetchSemanticsNode().config[LibraryObservation]

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun focusedTag(): String = focusedTags().last { it != "?" }

    private fun assertFocused(tag: String) = assertTrue(focused(tag), "$tag holds focus; focused=${focusedTags()}")

    private fun texts(tag: String): List<String> = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }

    private fun cardWithText(prefix: String, title: String): String? = compose.onAllNodes(hasText(title) and SemanticsMatcher("a card") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }).fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.TestTag)

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertFocused(tag)
    }

    /** A key to the window [tag] is in -- a dialog's, not the screen beneath it. */
    private fun keyAt(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun stepTo(tag: String, key: Key, from: String) {
        repeat(8) {
            if (focused(tag)) return
            keyAt(from, key)
        }
        assertFocused(tag)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun await(what: String, seconds: Long = 30, condition: () -> Boolean) {
        try {
            compose.waitUntil(seconds * 1_000) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: Throwable) {
            val tags = runCatching { compose.onAllNodes(SemanticsMatcher("tagged") { SemanticsProperties.TestTag in it.config })
                .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] } }.getOrNull()
            throw AssertionError("Timed out waiting for $what; tags=$tags; focused=${focusedTags()}; requests=${server.endpoints()}; " +
                "playlists=${server.playlistsNamed(NAME)}", timeout)
        }
    }

    private companion object { const val NAME = "Road Trip" }
}
