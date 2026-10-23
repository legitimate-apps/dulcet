package com.legitimateapps.dulcet.tv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.MyLibraryAlbum
import com.legitimateapps.dulcet.library.MyLibrarySession
import com.legitimateapps.dulcet.library.MyLibrarySong
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.FakeMyLibrarySource
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * The TV's My Library / Browse split (spec §29.4) over TvLibraryEntry — the real top bar, routes,
 * focus memory and production session/reader; the server is a loopback fixture and My Library is
 * seated from a fake standing where the core's `AndroidMyLibrary` facade lands. Every move is a
 * remote key; nothing is clicked or focused semantically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvMyLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("provider:tv-mylibrary", server.url, "u", "p", true)
    private val myLibrarySessions = mutableListOf<MyLibrarySession>()

    @After fun close() {
        myLibrarySessions.forEach(MyLibrarySession::close)
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        server.close()
        context.deleteDatabase("dulcet.db")
        context.getSharedPreferences("dulcet.ui", 0).edit().clear().commit()
    }

    @Test fun tvCompositionDoesNotInstantiateFeature() {
        server.dulcetDiscoverWishlist = true
        host(FakeMyLibrarySource(available = false))
        await("positive baseline catalog response") { server.requests("getAlbumList2").isNotEmpty() }
        assertTrue(server.requests("getOpenSubsonicExtensions").isNotEmpty())
        for (endpoint in listOf("searchDulcetDiscover", "getDulcetWishlist", "addToDulcetWishlist")) {
            assertTrue(server.requests(endpoint).isEmpty(), "TV must not create Discover: $endpoint")
        }
    }

    private fun host(source: FakeMyLibrarySource) {
        val myLibrary = MyLibrarySession(source).also { myLibrarySessions += it }
        compose.setContent { MaterialTheme { TvLibraryEntry(account, playback = null, myLibrary = myLibrary) { _, _ -> } } }
        compose.waitForIdle()
        // These tests exercise Library; enter it from the new Listen Now landing tab by remote.
        await("Listen Now") { exists("listenNow.surface") }
        val tabs=listOf("listenNow.open","search.open","library.open","browse.open","tv.account.open")
        repeat(6) { if(tabs.none(::focused)) key(Key.DirectionUp) }
        repeat(8) { if(!focused("listenNow.open")) key(Key.DirectionLeft) }
        assertFocused("listenNow.open")
        key(Key.DirectionRight); key(Key.DirectionRight); key(Key.DirectionCenter)
    }

    // ---- The split -----------------------------------------------------------------------------------

    @Test fun aServerWithMyLibrarySplitsTheBarAndBrowseKeepsTheCatalog() {
        host(FakeMyLibrarySource(albums = listOf(album("album-kept", "Kind of Blue"))))
        await("My Library's home, the remote on its first card") { focused("mylibrary.recent.album.0") }
        assertTrue(exists("mylibrary.surface"))
        assertTrue(exists("browse.open"), "the bar has the Browse tab")
        assertTrue(selected("library.open"), "the bar's Library tab is lit")

        // UP from the card crosses to the bar; where it lands on the bar is geometric, so walk
        // along the bar to Browse from whichever tab the remote first rests on.
        var ups = 0
        while (!focused("listenNow.open") && !focused("library.open") && !focused("search.open") && !focused("browse.open") && ups < 4) { key(Key.DirectionUp); ups += 1 }
        assertTrue(focused("listenNow.open") || focused("library.open") || focused("search.open") || focused("browse.open"),
            "UP from the card reaches the bar; focused=${focusedTags()}")
        var rights = 0
        while (!focused("browse.open") && rights < 6) { key(Key.DirectionRight); rights += 1 }
        assertFocused("browse.open")
        key(Key.DirectionCenter)
        await("Browse, the catalog home") { exists("library.surface") }
        await("the catalog's cards read from the server") { focused("library.home.0.item.0") }
        assertTrue(server.requests("getAlbumList2").isNotEmpty(), "Browse reads the whole catalog")
        assertTrue(selected("browse.open"), "the bar's Browse tab is lit")
    }

    @Test fun withoutTheExtensionTheBarAndTheLibraryAreAsToday() {
        host(FakeMyLibrarySource(available = false))
        await("the catalog home, as the library has always opened") { focused("library.home.0.item.0") }
        assertTrue(exists("library.surface"))
        assertFalse(exists("browse.open"), "no Browse tab without the extension")
        assertFalse(exists("mylibrary.surface"), "no My Library screen")
        // The album screen offers no membership control.
        key(Key.DirectionCenter)
        await("the album") { exists("album.surface") }
        assertFalse(exists("album.library"), "no Add to Library without the extension")
    }

    @Test fun theEmptyLibraryPointsToBrowseWhichTheRemoteOpens() {
        host(FakeMyLibrarySource())
        await("the empty state, the remote on its Browse action") { focused("mylibrary.browse") }
        key(Key.DirectionCenter)
        await("Browse") { exists("library.surface") }
        assertTrue(selected("browse.open"))
    }

    // ---- Add and remove, by remote -------------------------------------------------------------------

    @Test fun anAlbumAddsAndRemovesFromItsScreenByRemote() {
        val source = FakeMyLibrarySource()
        host(source)
        await("the empty state's Browse") { focused("mylibrary.browse") }
        key(Key.DirectionCenter)
        await("Browse") { exists("library.surface") && focused("library.home.0.item.0") }

        // The first home card opens its album; its actions are one RIGHT walk from Play.
        key(Key.DirectionCenter)
        await("the album, the remote on Play") { exists("album.surface") && focused("album.play") }
        var rights = 0
        while (!focused("album.library") && rights < 8) { key(Key.DirectionRight); rights += 1 }
        assertFocused("album.library")
        assertEquals("Add to Library", text("album.library"))

        key(Key.DirectionCenter)
        assertEquals(listOf(true to AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "newest-1")), source.writes.toList())
        await("Remove, the album now a member") { text("album.library") == "Remove from Library" }

        key(Key.DirectionCenter)
        assertEquals(listOf(
            true to AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "newest-1"),
            false to AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "newest-1"),
        ), source.writes.toList())
        await("Add again, the album removed") { text("album.library") == "Add to Library" }
    }

    @Test fun aSongRemovesFromItsRowInTheLibraryByRemote() {
        val source = FakeMyLibrarySource(songs = listOf(
            MyLibrarySong("song-kept", "Flamenco Sketches", "album-kept", "Kind of Blue", "Miles Davis", 326_000, null, pending = false)))
        host(source)
        await("the song in Recently Added") { focused("mylibrary.recent.track.0") }
        var rights = 0
        while (!focused("mylibrary.recent.track.0.library") && rights < 6) { key(Key.DirectionRight); rights += 1 }
        assertFocused("mylibrary.recent.track.0.library")
        key(Key.DirectionCenter)
        assertEquals(listOf(false to AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "song-kept")), source.writes.toList())
        await("the song gone") { exists("mylibrary.empty") }
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private fun album(id: String, title: String) =
        MyLibraryAlbum(id, title, "Fixture Artist", "artist-1", 1959, null, 6, explicitlyAdded = true, memberSongCount = 0, pending = false)

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; focused=${focusedTags()}" }
            Thread.sleep(10)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun selected(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Selected) { false } }

    private fun text(tag: String): String = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .first().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("\n") { it.text }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

    private fun assertFocused(tag: String) = assertTrue(focused(tag), "$tag holds focus; focused=${focusedTags()}")

    private fun key(key: Key) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
