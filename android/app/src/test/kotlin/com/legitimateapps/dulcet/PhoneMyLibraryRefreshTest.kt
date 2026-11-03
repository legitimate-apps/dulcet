package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
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
import com.legitimateapps.dulcet.library.MyLibrarySyncState
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
import androidx.compose.material3.MaterialTheme
import android.content.Context
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies

/** Production shell regression: refreshing a library retains its actionable cached content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneMyLibraryRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("provider:refresh-cache", server.url, "u", "p", true)
    private val sessions = mutableListOf<MyLibrarySession>()

    @After fun close() {
        sessions.forEach(MyLibrarySession::close)
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

    private fun host(source: FakeMyLibrarySource): MyLibrarySession {
        val library = MyLibrarySession(source).also { sessions += it }
        val dependencies = object : SearchHostDependencies {
            override fun loadAccount(context: Context): SearchAccount = account
            override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter =
                ProductionSearchHostDependencies.createPresenter(account, context, foreground)
        }
        compose.setContent { MaterialTheme { PhoneApp(account, dependencies, PhonePlaybackRequests(), playback = null, myLibrary = library) } }
        compose.waitForIdle()
        click("library.open")
        await("My Library") { exists("mylibrary.surface") }
        return library
    }

    @Test fun aHeldRefreshKeepsTheCachedAlbumInteractive() {
        val source = FakeMyLibrarySource(albums = listOf(album()), syncState = MyLibrarySyncState.Cached)
        val library = host(source)
        await("cached album") { exists("mylibrary.recent.album.0") }
        hold(source, library)
        assertEquals(listOf("album-kept"), library.state.value!!.albums.map { it.rawId })
        compose.onNodeWithTag("mylibrary.recent.album.0").assertHasClickAction()
        assertFalse(exists("mylibrary.loading"), "cached content cannot be replaced by loading")
        click("mylibrary.recent.album.0")
        await("the cached album still opens") { exists("album.surface") }
    }

    @Test fun aHeldRefreshKeepsTheCachedSongInteractive() {
        val source = FakeMyLibrarySource(songs = listOf(song()), syncState = MyLibrarySyncState.Cached)
        val library = host(source)
        await("cached song") { exists("mylibrary.recent.track.0") }
        hold(source, library)
        assertEquals(listOf("song-kept"), library.state.value!!.songs.map { it.rawId })
        compose.onNodeWithTag("mylibrary.recent.track.0").assertHasClickAction()
        assertFalse(exists("mylibrary.loading"))
        click("mylibrary.recent.track.0.menu")
        click("mylibrary.recent.track.0.menu.library")
        assertEquals(listOf(false to AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "song-kept")), source.writes.toList())
    }

    @Test fun aHeldRefreshKeepsTheCachedArtistInteractive() {
        val source = FakeMyLibrarySource(artists = listOf(artist()), syncState = MyLibrarySyncState.Cached)
        val library = host(source)
        click("mylibrary.view.artists")
        await("cached artist") { exists("mylibrary.artists.0") }
        hold(source, library)
        assertEquals(listOf("artist-1"), library.state.value!!.artists.map { it.rawId })
        compose.onNodeWithTag("mylibrary.artists.0").assertHasClickAction()
        click("mylibrary.artists.0")
        await("the cached artist still opens") { exists("artist.surface") }
    }

    @Test fun pendingContentBeforeTheFirstReadRemainsInteractive() {
        val source = FakeMyLibrarySource(albums = listOf(album().copy(pending = true)), syncState = MyLibrarySyncState.Never)
        host(source)
        compose.onNodeWithTag("mylibrary.recent.album.0").assertHasClickAction()
        assertFalse(exists("mylibrary.loading"), "Never does not imply no optimistic content")
        click("mylibrary.recent.album.0")
        await("pending cached album opens") { exists("album.surface") }
    }

    @Test fun anEmptyFirstRefreshLoadsAndACompletedEmptyRefreshIsEmpty() {
        val source = FakeMyLibrarySource(syncState = MyLibrarySyncState.Never)
        val library = host(source)
        assertTrue(exists("mylibrary.loading"))
        assertFalse(exists("mylibrary.empty"))
        hold(source, library)
        assertTrue(exists("mylibrary.loading"), "a first read without content still loads")
        assertFalse(exists("mylibrary.empty"))
        compose.runOnIdle { source.finishRefresh() }
        compose.waitForIdle()
        assertEquals(MyLibrarySyncState.Current, library.state.value!!.syncState)
        assertTrue(exists("mylibrary.empty"), "a true final empty publication retains the empty state")
        assertFalse(exists("mylibrary.loading"))
    }

    @Test fun aFailedRefreshKeepsCachedContentInteractive() {
        host(FakeMyLibrarySource(albums = listOf(album()), syncState = MyLibrarySyncState.Failed))
        compose.onNodeWithTag("mylibrary.recent.album.0").assertHasClickAction()
        assertFalse(exists("mylibrary.loading"))
        click("mylibrary.recent.album.0")
        await("failed refresh retains cached album") { exists("album.surface") }
    }

    private fun hold(source: FakeMyLibrarySource, library: MyLibrarySession) {
        compose.runOnIdle { source.holdRefresh = true; library.refresh() }
        compose.waitForIdle()
        assertEquals(MyLibrarySyncState.Syncing, library.state.value!!.syncState, "actual session publishes held refresh state")
    }

    private fun album() = MyLibraryAlbum("album-kept", "Kind of Blue", "Fixture Artist", "artist-1", 1959, null, 6,
        explicitlyAdded = true, memberSongCount = 0, pending = false)
    private fun song() = MyLibrarySong("song-kept", "Flamenco Sketches", "album-kept", "Kind of Blue", "Fixture Artist", 326_000, null, pending = false)
    private fun artist() = MyLibraryArtist("artist-1", "Fixture Artist", null, 1, explicitlyAdded = true, pending = false)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what" }
            Thread.sleep(10)
        }
    }
}
