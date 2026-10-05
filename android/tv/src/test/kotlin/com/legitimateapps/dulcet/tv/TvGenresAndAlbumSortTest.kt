package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.library.AlbumSort
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import com.legitimateapps.dulcet.search.conformance.PlatformNetwork
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
 * The TV library's Genres and its album orders, with the remote, over TvLibraryEntry — the real top
 * bar, routes, focus memory, and the PRODUCTION session and reader; only the server is a loopback
 * fixture. Moves that the claim is about are remote keys; a key sequence that only gets somewhere
 * already covered elsewhere (the library's row of views) starts from a requested focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvGenresAndAlbumSortTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("provider:tv-genres", server.url, "u", "p", true)
    private var controller: AndroidPlaybackController? = null

    @After fun close() {
        controller?.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        server.close()
        context.deleteDatabase("dulcet.db")
        context.getSharedPreferences("dulcet.ui", 0).edit().clear().commit()
    }

    private fun host() {
        compose.setContent { MaterialTheme { TvLibraryEntry(account) { _, _ -> } } }
        compose.waitForIdle()
        // The library's default focus is unchanged: its first card takes the remote from the bar.
        await("the first home card to take focus") { focused("library.home.0.item.0") }
    }

    @Test fun genresAreOneStepRightOfArtistsAndAGenreOpensItsSongsWithFocusOnPlay() {
        host()
        focus("library.view.artists")
        key(Key.DirectionRight)
        assertFocused("library.view.genres")
        key(Key.DirectionCenter)
        await("the genres, focus on the first") { focused("library.genres.item.0") }
        assertEquals("Jazz", texts("library.genres.item.0").first())
        key(Key.DirectionRight)
        assertFocused("library.genres.item.1")
        key(Key.DirectionCenter)
        await("Ambient's songs, focus on Play") { exists("genre.track.0") && focused("genre.play") }
        assertEquals("Ambient", texts("genre.title").first())
        assertEquals(setOf("Ambient"), server.requests("getSongsByGenre").map { it["genre"] }.toSet(),
            "the page read the genre opened, and only it")

        back()
        await("Back to the genres, on the genre that was opened") { focused("library.genres.item.1") }
        back()
        await("Back to the library, on Genres") { focused("library.view.genres") }
    }

    /**
     * A genre plays from the remote through the playback service's real controller: Play queues its
     * songs in order from the first, Shuffle queues the same songs shuffled, and a song's row queues
     * them from itself. Each is a library queue named for the genre, and each opens Now Playing.
     */
    @Test fun aGenresPlayAndShuffleQueueAllItsSongsAndARowQueuesThemFromItself() {
        // Made before the screens start, as the service makes it before it binds (TvPlayBeforeBindTest).
        server.holdStreams = true
        val playback = AndroidPlaybackController(context,
            PlaybackEndpointAccount(account.providerInstanceId, server.url, "u", "p", true)).also { controller = it }
        compose.setContent { MaterialTheme { TvLibraryEntry(account, playback = playback) { _, _ -> } } }
        compose.waitForIdle()
        await("the first home card to take focus") { focused("library.home.0.item.0") }
        focus("library.view.genres")
        key(Key.DirectionCenter)
        await("the genres, focus on Jazz") { focused("library.genres.item.0") }
        key(Key.DirectionCenter)
        await("Jazz's songs, focus on Play") { exists("genre.track.1") && focused("genre.play") }
        val songs = listOf("jazz-song-1", "jazz-song-2")
        fun queued() = playback.state.value.queue.map { it.track.rawId }
        // The server never answers the stream, so no failure moves the queue past the song a press
        // started from; the current entry stays that song. A play reaches the controller through the
        // main looper, which only waiting for Compose to idle does not run (TvPlayBeforeBindTest).
        fun awaitState(what: String, condition: () -> Boolean) = try {
            await(what) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        } catch (timeout: AssertionError) {
            val state = playback.state.value
            throw AssertionError("${timeout.message}; queue=${queued()} index=${state.currentIndex} shuffle=${state.shuffle} " +
                "phase=${state.phase} error=${state.error}", timeout)
        }
        fun current() = playback.state.value.let { state -> state.currentIndex?.let { state.queue.getOrNull(it) }?.track?.rawId }

        key(Key.DirectionCenter)
        awaitState("Play to queue Jazz from its first song") { queued() == songs && current() == "jazz-song-1" }
        assertEquals(false, playback.state.value.shuffle)
        assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, shadowOf(context).nextStartedActivity?.action, "Play opens Now Playing")

        key(Key.DirectionRight)
        assertFocused("genre.shuffle")
        key(Key.DirectionCenter)
        awaitState("Shuffle to queue Jazz shuffled") { playback.state.value.shuffle }
        assertEquals(songs.sorted(), queued().sorted(), "Shuffle queues every song of the genre: ${queued()}")
        assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, shadowOf(context).nextStartedActivity?.action, "Shuffle opens Now Playing")

        focus("genre.track.1")
        key(Key.DirectionCenter)
        awaitState("the row to queue Jazz in order from its own song") {
            !playback.state.value.shuffle && queued() == songs && current() == "jazz-song-2"
        }
        assertEquals(PlaybackIntents.ACTION_SHOW_NOW_PLAYING, shadowOf(context).nextStartedActivity?.action, "a row opens Now Playing")
        assertTrue(server.requests("getSong").all { it["id"] in songs }, "only Jazz's songs were asked for")
    }

    @Test fun theAlbumOrdersAreUpFromTheAlbumsAndChoosingOneKeepsTheRemoteOnIt() {
        host()
        focus("library.view.albums")
        key(Key.DirectionCenter)
        await("the albums by title, focus on the first") {
            focused("library.albums.item.0") && texts("library.albums.item.0").first() == "Album 1 by alphabeticalByName"
        }
        assertTrue(selected("library.albums.sort.alphabeticalbyname"), "the order showing is marked")
        key(Key.DirectionUp)
        val landed = focusedTags().single()
        assertTrue(landed.startsWith("library.albums.sort."), "UP from the first albums reaches the orders: $landed")
        focus("library.albums.sort.alphabeticalbyname")
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        assertFocused("library.albums.sort.newest")
        key(Key.DirectionCenter)
        await("the albums, newest first") { texts("library.albums.item.0").first() == "Album 1 by newest" }
        assertFocused("library.albums.sort.newest")
        assertTrue(selected("library.albums.sort.newest") && !selected("library.albums.sort.alphabeticalbyname"))
        assertEquals(AndroidAlbumListType.Newest, AlbumSort.load(context), "the choice is kept on this device")
        assertEquals(listOf("alphabeticalByName", "newest"),
            server.requests("getAlbumList2").filter { it["size"] != "20" }.map { it["type"] }.distinct(),
            "the Albums screen read by title, then newest, each its own window")
    }

    /**
     * §16.14: offline, an order never read shows what this device has seen, sorted here and labelled
     * "Available offline" — on the TV as on the phone, whose list status already said it.
     */
    @Test fun anOrderNeverReadChosenOfflineShowsWhatThisDeviceHasSeenAndSaysSo() {
        host()
        focus("library.view.albums")
        key(Key.DirectionCenter)
        await("the albums by title") { texts("library.albums.item.0").first() == "Album 1 by alphabeticalByName" }
        val network = PlatformNetwork(context)
        network.lose()
        compose.waitForIdle()
        focus("library.albums.sort.highest")
        key(Key.DirectionCenter)
        await("the local view, said to be") { exists("library.albums.order") }
        assertEquals("Available offline", texts("library.albums.order").first())
        assertTrue(exists("library.albums.item.0"), "the albums this device has seen are shown")
        assertTrue(server.requests("getAlbumList2").none { it["type"] == "highest" }, "the order chosen offline is not read")
        network.restore()
    }

    @Test fun theOrderChosenEarlierOnThisDeviceIsTheOneTheAlbumsOpenIn() {
        AlbumSort.save(context, AndroidAlbumListType.Frequent)
        host()
        focus("library.view.albums")
        key(Key.DirectionCenter)
        await("the albums, most played, focus on the first") {
            focused("library.albums.item.0") && texts("library.albums.item.0").first() == "Album 1 by frequent"
        }
        assertTrue(selected("library.albums.sort.frequent"))
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun assertFocused(tag: String) = assertTrue(focused(tag), "$tag holds focus; focused=${focusedTags()}")

    private fun selected(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun texts(tag: String): List<String> = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertFocused(tag)
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000) { runCatching(condition).getOrDefault(false) }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; focused=${focusedTags()}; requests=${server.endpoints()}", timeout)
        }
    }
}
