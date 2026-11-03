package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryItemsOrder
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.library.toReaderAccount
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
        server.releaseAlbumPages()
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
        reachBrowseCategories()
    }

    private fun reachBrowseCategories() {
        // Catalog destinations belong to Browse; its first category is the initial content focus.
        await("the Browse tab") { exists("browse.open") }
        focus("browse.open")
        key(Key.DirectionCenter)
        await("Browse categories and a loaded home card") {
            focused("library.view.artists") && exists("library.home.0.item.0")
        }
        assertTrue(exists("library.view.albums"), "Browse exposes the Albums category beside its shelves")
    }

    @Test fun genresAreOneStepRightOfArtistsAndAGenreOpensItsSongsWithFocusOnPlay() {
        host()
        focus("library.view.artists")
        var downs = 0
        while (!focused("library.view.genres") && downs < 6) { key(Key.DirectionDown); downs += 1 }
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
        reachBrowseCategories()
        focusSection("library.view.genres")
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

    @Test fun theAlbumFiltersStayAvailableBesideSortAndCanBeCleared() {
        host()
        focus("library.view.albums")
        key(Key.DirectionCenter)
        await("the album grid") { exists("library.albums.item.0") }
        assertTrue(exists("library.albums.filter.favorites"), "Album filters remain available beside sort")
        focus("library.albums.filter.favorites")
        key(Key.DirectionCenter)
        await("Favorites selected and its empty result painted") {
            selected("library.albums.filter.favorites") && exists("library.albums.empty")
        }
        focus("library.albums.filter.all")
        key(Key.DirectionCenter)
        await("All restores the filter") { selected("library.albums.filter.all") }
    }

    @Test fun filteredDownloadsKeepTheirSavedFavoriteChoiceAndShowAllClearsItDurably() {
        val reader = AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true)
        assertTrue(reader.setLibraryCategory("downloaded", "titleDescending", "favorites"))
        assertEquals("favorites", savedDownloadFilter(), "setup: Favorites is saved before the production host starts")
        host()
        focus("library.open")
        key(Key.DirectionCenter)
        fun downloadedCategory() = if (exists("mylibrary.view.downloaded")) "mylibrary.view.downloaded" else "library.view.downloaded"
        await("Library's Downloads category") { exists(downloadedCategory()) }
        focus(downloadedCategory())
        key(Key.DirectionCenter)
        await("the saved Favorites filter loaded with its filtered-empty result") {
            exists("library.downloaded.showAll") && selected("library.downloaded.filter.favorites") &&
                !exists("library.downloaded.loading")
        }
        assertEquals("favorites", savedDownloadFilter(), "opening Downloads preserves the saved filter")
        assertTrue(exists("library.downloaded.empty"), "the filtered empty state is shown")
        assertTrue(!exists("library.downloaded.goLibrary"), "filtered empty offers Show all before the unfiltered Library exit")
        assertTrue(!exists("library.downloaded.track.0"), "there are no downloaded favorite rows")

        focus("library.downloaded.showAll")
        key(Key.DirectionCenter)
        await("Show all restores the unfiltered Downloads empty state and saves All") {
            exists("library.downloaded.goLibrary") && savedDownloadFilter() == "all"
        }
        assertTrue(!exists("library.downloaded.showAll"), "the cleared filter no longer offers Show all")
        assertTrue(!exists("library.downloaded.filter.favorites"), "ordinary empty Downloads hides filter controls")
        back()
        await("Back restores Downloads in Library") { focused(downloadedCategory()) }
        key(Key.DirectionCenter)
        await("reopening Downloads keeps the cleared selection") {
            exists("library.downloaded.goLibrary") && !exists("library.downloaded.showAll")
        }
        assertEquals("all", savedDownloadFilter(), "the cleared choice survives leaving and reopening the route")
    }

    @Test fun theAlbumOrdersAreUpFromTheAlbumsAndChoosingOneKeepsTheRemoteOnIt() {
        server.holdAlbumPages = true
        host()
        focus("library.view.albums")
        key(Key.DirectionCenter)
        await("the albums by title, focus on the first") {
            focused("library.albums.item.0") && texts("library.albums.item.0").first() == "Album 1 by alphabeticalByName"
        }
        // The sort button names the showing order; the dialog marks it selected, independent of focus.
        focus("library.albums.sort")
        key(Key.DirectionCenter)
        await("the sort dialog, the showing order marked and focused") {
            focused("library.albums.sort.alphabeticalbyname") && selected("library.albums.sort.alphabeticalbyname")
        }
        dismissDialog()
        focus("library.albums.item.0")
        key(Key.DirectionUp)
        var landed = focusedTags().single()
        assertEquals("library.albums.loadMore", landed, "Open coverage exposes paging above the albums")
        val frame = compose.onNodeWithTag("library.albums").fetchSemanticsNode().config[
            com.legitimateapps.dulcet.library.LibraryObservation].surfaces.getValue("albums:AlphabeticalByName").last()
        assertEquals(com.legitimateapps.dulcet.core.AndroidLibraryCoverage.Open, frame.coverage)
        assertEquals(2, frame.itemCount)
        assertTrue(exists("library.albums.loadMore"), "The focused paging action is composed")
        repeat(4) {
            if (!landed.startsWith("library.albums.sort") && !landed.startsWith("library.albums.filter.")) {
                key(Key.DirectionUp)
                landed = focusedTags().single()
            }
        }
        assertTrue(landed.startsWith("library.albums.sort") || landed.startsWith("library.albums.filter."),
            "UP through the paging header reaches its sort/filter controls: $landed")
        server.releaseAlbumPages()
        focus("library.albums.sort")
        key(Key.DirectionCenter)
        await("the sort dialog") { focused("library.albums.sort.alphabeticalbyname") }
        keyAt("library.albums.sort.alphabeticalbyname", Key.DirectionDown)
        keyAt("library.albums.sort.alphabeticalbyartist", Key.DirectionDown)
        assertFocused("library.albums.sort.newest")
        keyAt("library.albums.sort.newest", Key.DirectionCenter)
        await("the albums, newest first") { texts("library.albums.item.0").first() == "Album 1 by newest" }
        assertFocused("library.albums.sort")
        focus("library.albums.sort")
        keyAt("library.albums.sort", Key.DirectionCenter)
        await("the dialog marks the showing order") { selected("library.albums.sort.newest") && !selected("library.albums.sort.alphabeticalbyname") }
        dismissDialog()
        assertEquals("Newest", savedSort(), "the choice is kept for this account on this device")
        assertEquals(listOf("alphabeticalByName", "newest"),
            server.requests("getAlbumList2").filter { it["size"] != "20" }.map { it["type"] }.distinct(),
            "the Albums screen read by title, then newest, each its own window")
    }

    /**
     * §16.14: offline, an order never read shows what this device has seen, sorted locally. The
     * reader observation proves LocalView without requiring data-source prose in the page body.
     */
    @Test fun anOrderNeverReadChosenOfflineShowsWhatThisDeviceHasSeenAndSaysSo() {
        host()
        focus("library.view.albums")
        key(Key.DirectionCenter)
        await("the albums by title") { texts("library.albums.item.0").first() == "Album 1 by alphabeticalByName" }
        val seenIds = compose.onNodeWithTag("library.albums").fetchSemanticsNode().config[
            com.legitimateapps.dulcet.library.LibraryObservation].surfaces.getValue("albums:AlphabeticalByName").last().itemRawIds
        assertTrue(seenIds.isNotEmpty(), "The online order populated the device's seen albums")
        val network = PlatformNetwork(context)
        network.lose()
        compose.waitForIdle()
        focus("library.albums.sort")
        key(Key.DirectionCenter)
        await("the sort dialog") { focused("library.albums.sort.alphabeticalbyname") }
        focus("library.albums.sort.highest")
        keyAt("library.albums.sort.highest", Key.DirectionCenter)
        await("the chosen order to publish a local view of the seen albums") {
            val frame = compose.onNodeWithTag("library.albums").fetchSemanticsNode().config[
                com.legitimateapps.dulcet.library.LibraryObservation].surfaces["albums:Highest"]?.lastOrNull()
            frame != null && frame.order == AndroidLibraryItemsOrder.LocalView && frame.itemRawIds.containsAll(seenIds)
        }
        assertEquals("Highest", savedSort(), "The offline choice remains the selected album order")
        focus("library.albums.sort")
        key(Key.DirectionCenter)
        await("the sort dialog marks the offline choice") { selected("library.albums.sort.highest") }
        dismissDialog()
        assertTrue(exists("library.albums.item.0"), "the albums this device has seen are shown")
        assertTrue(server.requests("getAlbumList2").none { it["type"] == "highest" }, "the order chosen offline is not read")
        network.restore()
    }

    @Test fun theOrderChosenEarlierOnThisDeviceIsTheOneTheAlbumsOpenIn() {
        saveSort("Frequent")
        host()
        focus("library.view.albums")
        key(Key.DirectionCenter)
        await("the albums, most played, focus on the first") {
            focused("library.albums.item.0") && texts("library.albums.item.0").first() == "Album 1 by frequent"
        }
        focus("library.albums.sort")
        key(Key.DirectionCenter)
        await("the sort dialog marks Most Played") { selected("library.albums.sort.frequent") }
        dismissDialog()
    }

    private fun saveSort(sort: String) {
        val reader = AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true)
        assertTrue(reader.setLibraryCategory("albums", sort, "all"))
        assertEquals(sort, savedSort())
    }

    private fun savedSort(): String {
        var value: String? = null
        AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true).libraryDepthState { value = it.sort("albums") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (value == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        return checkNotNull(value)
    }

    private fun savedDownloadFilter(): String {
        var value: String? = null
        AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true).libraryDepthState { value = it.filter("downloaded") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (value == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        return checkNotNull(value)
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

    /** A category row past the pane's viewport composes only once the pane scrolls to it. */
    private fun focusSection(tag: String) {
        compose.onNodeWithTag("library.nav").performScrollToNode(hasTestTag(tag))
        focus(tag)
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The remote's [key] delivered where [tag] is: a dialog is a window of its own, with its own root. */
    private fun keyAt(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Back inside the sort dialog: to the dialog window's own dispatcher, as the remote's Back goes. */
    private fun dismissDialog() {
        compose.runOnIdle {
            (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
        }
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
