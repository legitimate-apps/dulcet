package com.legitimateapps.dulcet

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.MyLibraryAlbum
import com.legitimateapps.dulcet.library.MyLibraryArtist
import com.legitimateapps.dulcet.library.MyLibrarySession
import com.legitimateapps.dulcet.library.MyLibrarySong
import com.legitimateapps.dulcet.library.MyLibrarySort
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchPresenter
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
 * The phone's My Library / Browse split (spec §29.4): the production PhoneApp, session and reader,
 * with only the server a loopback fixture and the account's My Library seated from a fake standing
 * where the core's `AndroidMyLibrary` facade lands — it publishes, and answers add/remove with the
 * optimistic republication the core makes. The catalog side of every test is the real reader's,
 * so the requests asserted are the ones the screen made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneMyLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("provider:mylibrary", server.url, "u", "p", true)
    private val myLibrarySessions = mutableListOf<MyLibrarySession>()

    @After fun close() {
        myLibrarySessions.forEach(MyLibrarySession::close)
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

    private fun host(source: FakeMyLibrarySource) {
        val myLibrary = MyLibrarySession(source).also { myLibrarySessions += it }
        val dependencies = object : SearchHostDependencies {
            override fun loadAccount(context: Context): SearchAccount = account
            override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter =
                ProductionSearchHostDependencies.createPresenter(account, context, foreground)
        }
        compose.setContent { MaterialTheme { PhoneApp(account, dependencies, PhonePlaybackRequests(), playback = null, myLibrary = myLibrary) } }
        compose.waitForIdle()
    }

    // ---- The split -----------------------------------------------------------------------------------

    @Test fun aServerWithMyLibrarySplitsTheTabsAndBrowseKeepsTheCatalog() {
        host(FakeMyLibrarySource(albums = listOf(album("album-kept", "Kind of Blue"))))
        await("the Browse tab of the split") { exists("browse.open") }
        click("library.open")
        await("My Library's home") { exists("mylibrary.surface") }
        await("the kept album, recently added") { exists("mylibrary.recent.album.0") }
        assertTrue(text("mylibrary.recent.album.0").startsWith("Kind of Blue"), text("mylibrary.recent.album.0"))

        click("browse.open")
        await("Browse is the catalog") { exists("library.surface") }
        await("the server's albums read") { server.requests("getAlbumList2").isNotEmpty() }
        assertTrue(exists("library.view.albums"), "the catalog home keeps its views")
    }

    @Test fun withoutTheExtensionThereIsNoSplitAndNoMembershipControls() {
        host(FakeMyLibrarySource(available = false))
        click("library.open")
        await("the catalog as the Library tab, as today") { exists("library.surface") }
        assertFalse(exists("browse.open"), "no Browse tab without the extension")
        assertFalse(exists("mylibrary.surface"), "no My Library screen")

        click("library.view.albums")
        await("the catalog's albums") { exists("library.albums.item.0") }
        click("library.albums.item.0")
        await("the album") { exists("album.surface") && exists("album.favourite") }
        assertFalse(exists("album.library"), "no Add to Library without the extension")
        await("the album's tracks") { exists("album.track.0") }
        click("album.track.0.menu")
        assertFalse(exists("album.track.0.menu.library"), "the track's menu has no membership item")
        assertTrue(exists("album.track.0.menu.addToPlaylist"), "control: the menu opened")
    }

    @Test fun theEmptyLibraryPointsToBrowseWhichOpensTheCatalog() {
        host(FakeMyLibrarySource())
        click("library.open")
        await("the empty state") { exists("mylibrary.empty") }
        assertEquals("Nothing in your library yet.", text("mylibrary.empty"))
        click("mylibrary.browse")
        await("Browse, from the empty state") { exists("library.surface") }
        assertTrue(selected("browse.open"), "the Browse tab is the one lit")
    }

    // ---- Add and remove ------------------------------------------------------------------------------

    @Test fun anAlbumAddsFromItsHeaderAndShowsInTheLibraryAtOnce() {
        val source = FakeMyLibrarySource()
        host(source)
        click("browse.open")
        click("library.view.albums")
        await("the catalog's albums") { exists("library.albums.item.0") }
        click("library.albums.item.0")
        await("the album's Add to Library") { exists("album.library") }
        assertFalse(selected("album.library"), "not in the library yet")

        click("album.library")
        assertEquals(listOf(true to AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "alphabeticalByName-1")),
            source.writes.toList(), "the add asked for the album")
        await("the check, before any answer") { selected("album.library") }

        click("album.back")
        click("library.open")
        await("the album in My Library at once") { exists("mylibrary.recent.album.0") }
    }

    @Test fun anAlbumRemovesFromItsHeader() {
        val source = FakeMyLibrarySource(albums = listOf(album("album-kept", "Kind of Blue")))
        host(source)
        click("library.open")
        await("the kept album") { exists("mylibrary.recent.album.0") }
        click("mylibrary.recent.album.0")
        await("the album's Remove from Library") { exists("album.library") && selected("album.library") }

        click("album.library")
        assertEquals(listOf(false to AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "album-kept")),
            source.writes.toList(), "the remove asked for the album")
        await("the album no longer a member") { !selected("album.library") }

        click("album.back")
        await("the library empty of it") { exists("mylibrary.empty") }
    }

    @Test fun aSongAddsAndRemovesFromItsContextMenu() {
        val source = FakeMyLibrarySource()
        host(source)
        click("browse.open")
        click("library.view.albums")
        await("the catalog's albums") { exists("library.albums.item.0") }
        click("library.albums.item.0")
        await("the album's tracks") { exists("album.track.0") }

        click("album.track.0.menu")
        await("Add to Library in the menu") { exists("album.track.0.menu.library") }
        assertEquals("Add to Library", text("album.track.0.menu.library"))
        click("album.track.0.menu.library")
        assertEquals(listOf(true to AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "alphabeticalByName-1-song-1")),
            source.writes.toList())

        click("album.track.0.menu")
        assertEquals("Remove from Library", text("album.track.0.menu.library"), "the pending member reads as added")
        click("album.track.0.menu.library")
        assertEquals(listOf(
            true to AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "alphabeticalByName-1-song-1"),
            false to AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "alphabeticalByName-1-song-1"),
        ), source.writes.toList())
    }

    @Test fun anArtistAddsFromItsHeaderAndListsInTheLibrary() {
        val source = FakeMyLibrarySource()
        host(source)
        click("browse.open")
        click("library.view.artists")
        await("the catalog's artists") { exists("library.artists.item.0") }
        click("library.artists.item.0")
        await("the artist") { exists("artist.surface") }
        await("the artist's Add to Library") { exists("artist.library") }

        click("artist.library")
        assertEquals(listOf(true to AndroidLibraryEntity(AndroidLibraryEntityKind.Artist, "artist-1")), source.writes.toList())
        await("the check") { selected("artist.library") }

        click("library.open")
        click("mylibrary.view.artists")
        await("the artist in My Library") { exists("mylibrary.artists.0") }
        assertTrue(text("mylibrary.artists.0").contains("artist-1"),
            "the fake holds it by id, pending metadata: " + text("mylibrary.artists.0"))
    }

    @Test fun aKeptSongPlaysFromTheSongsSectionAndRemovesFromItsRow() {
        val source = FakeMyLibrarySource(songs = listOf(
            MyLibrarySong("song-kept", "Flamenco Sketches", "album-kept", "Kind of Blue", "Miles Davis", 326_000, null, pending = false)))
        host(source)
        click("library.open")
        click("mylibrary.view.songs")
        await("the song row") { exists("mylibrary.songs.0") }
        assertTrue(text("mylibrary.songs.0").contains("Flamenco Sketches"), text("mylibrary.songs.0"))
        click("mylibrary.songs.0.menu")
        assertEquals("Remove from Library", text("mylibrary.songs.0.menu.library"))
        click("mylibrary.songs.0.menu.library")
        assertEquals(listOf(false to AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "song-kept")), source.writes.toList())
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    private fun album(id: String, title: String) =
        MyLibraryAlbum(id, title, "Fixture Artist", "artist-1", 1959, null, 6, explicitlyAdded = true, memberSongCount = 0, pending = false)

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("\n") { it.text }

    private fun selected(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.endpoints()}" }
            Thread.sleep(10)
        }
    }
}
