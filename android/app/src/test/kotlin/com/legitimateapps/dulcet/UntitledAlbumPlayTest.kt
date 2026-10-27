package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.playableTracks
import com.legitimateapps.dulcet.library.playAlbum
import com.legitimateapps.dulcet.library.canBeQueued
import com.legitimateapps.dulcet.library.toTrack
import com.legitimateapps.dulcet.playback.PlaybackBinding
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.search.conformance.assertUntitledPlayback
import com.legitimateapps.dulcet.search.conformance.untitledAlbumAudio
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.seedUntitledAlbum
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.DiscoverAvailability
import com.legitimateapps.dulcet.core.DiscoverGroup
import com.legitimateapps.dulcet.core.DiscoverAlbum
import com.legitimateapps.dulcet.core.DiscoverWish
import com.legitimateapps.dulcet.core.WishRow
import com.legitimateapps.dulcet.core.WishSnapshot
import com.legitimateapps.dulcet.core.WishSource
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class UntitledAlbumPlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("untitled-phone", server.url, "u", "p", true)

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
    }

    @Test fun anAlbumWithOnlyBlankOrMissingTitlesPlaysWithLocalizedUntitledMetadata() = exerciseAlbum(false)
    @Test fun anAlbumWithOnlyBlankOrMissingTitlesShufflesWithLocalizedUntitledMetadata() = exerciseAlbum(true)
    @Test fun anAlbumWithoutDurationsPlays() = exerciseAlbum(false, false)
    @Test fun anAlbumWithoutDurationsShuffles() = exerciseAlbum(true, false)

    @Test fun selectingADurationlessRowInAMixedAlbumPlaysThatSong() = exerciseAlbum(false, rowSelection = true)
    @Test fun selectingADurationlessRowInAnAlbumWithoutDurationsPlaysThatSong() = exerciseAlbum(false, false, rowSelection = true)
    @Test fun aLaterTitleReplacesTheFallbackInThePlayerSessionAndNotification() = exerciseAlbum(false, enrich = true)

    @Test fun discoverSearchPlaysADurationlessLibraryMatch() = exerciseDiscover("search")
    @Test fun discoverAlbumPlaysADurationlessLibraryMatch() = exerciseDiscover("album")
    @Test fun fulfilledWishlistPlaysADurationlessLibraryMatch() = exerciseDiscover("wishlist")

    private fun exerciseDiscover(surface: String) {
        server.albumSongDurationSeconds = null
        seedUntitledAlbum(context, account, server, "untitled-1")
        server.albumPlaybackAudio = untitledAlbumAudio()
        val playback = AndroidPlaybackController(context, PlaybackEndpointAccount(account.providerInstanceId, server.url, "u", "p", true))
        controller = playback
        val binding = PlaybackBinding().apply { bind(playback) }
        val source = FakeDiscoverWishlistSource()
        val reference = DiscoverLibraryRef(account.providerInstanceId, "song", "untitled-1-song-2")
        val held = source.item("song").copy(availability = DiscoverAvailability("held", "known", null, 1, 1, null, listOf(reference)))
        source.state = source.state.copy(providerInstanceId = account.providerInstanceId,
            groups = listOf(DiscoverGroup("song", source.page(listOf(held)), false, null)),
            wishes = listOf(WishRow("arrived", DiscoverWish("arrived", "u", held.target, "song",
                WishSnapshot(held.title, held.artist, null, null), WishSource("search", null), 1000, 2000,
                "fulfilled", null, null, 2000, reference, held.availability, "1"), null, false, null)))
        source.albums[null] = DiscoverAlbum(source.item("album"), emptyList(), null, source.page(listOf(held)))
        val session = DiscoverWishlistSession(source)
        // The same scoped callback used by PhoneApp's Search, Discover details and Wishlist.
        val open: (DiscoverLibraryRef) -> Unit = { ref ->
            if (ref.providerInstanceId == account.providerInstanceId && ref.type == "song") {
                binding.play { it.playSong(ref.providerInstanceId, ref.id, "") }
            }
        }
        try {
            compose.setContent { MaterialTheme {
                when (surface) {
                    "search" -> PhoneDiscoverSearch(session, open, {}, {})
                    "album" -> PhoneDiscoverAlbum(session, "external-album", open, {})
                    else -> PhoneWishlist(session, open, {})
                }
            } }
            if (surface == "album") compose.onNodeWithTag("discover.album.list")
                .performScrollToNode(hasTestTag("discover.item.song.0"))
            if (surface == "wishlist") {
                compose.onNodeWithTag("wishlist.row.menu.0").performClick()
                compose.onNodeWithTag("wishlist.arrival.0").performClick()
            } else compose.onNodeWithTag("discover.openLibrary.song.0").performClick()
            compose.waitUntil(30_000) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                playback.state.value.validatedBytesConsumed > 0
            }
            val entry = playback.state.value.queue.single().track
            assertEquals(reference.id, entry.rawId)
            assertNull(entry.durationMilliseconds, "Discover and Wishlist must admit the server's unknown duration")
            assertTrue(server.requests("getSong").any { it["id"] == reference.id })
        } finally { session.close() }
    }

    private fun exerciseAlbum(shuffle: Boolean, knownDuration: Boolean = true, rowSelection: Boolean = false, enrich: Boolean = false) {
        seedUntitledAlbum(context, account, server, "untitled-1")
        server.albumPlaybackAudio = untitledAlbumAudio()
        if (!knownDuration) server.albumSongDurationSeconds = null
        else if (rowSelection) server.albumSongDurationsSeconds = listOf(120, null, 120)
        val playback = AndroidPlaybackController(context, PlaybackEndpointAccount(account.providerInstanceId, server.url, "u", "p", true))
        controller = playback
        val binding = PlaybackBinding().apply { bind(playback) }
        var playerOpen by mutableStateOf(false)
        var shown: AndroidLibraryPublication? = null
        val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {},
            playAlbum = { album, position, shuffle -> playAlbum(binding, account.providerInstanceId, album, (album.items.getOrNull(position) as? AndroidLibraryItem.Track)?.rawId, shuffle) { playerOpen = true } },
            playArtist = { _, _, _ -> null }, rememberAlbum = { shown = it })
        compose.setContent {
            MaterialTheme {
                val session = remember { LibrarySession(context, account, true) }
                val state by playback.state.collectAsState()
                if (playerOpen) NowPlayingScreen(account, state, playback) {}
                else AlbumScreen(account, session, "untitled-1", null, actions)
                LibraryLifecycle(session)
            }
        }
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            shown?.let { it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present && it.items.size == 3 } == true
        }
        compose.waitForIdle()
        val publication = checkNotNull(shown)
        val tracks = publication.items.filterIsInstance<AndroidLibraryItem.Track>()
        assertEquals(3, tracks.size, "The reader must publish every fixture track")
        assertTrue(tracks.all { it.title.isNullOrBlank() && it.playability == AndroidLibraryPlayability.Streamable })
        compose.onNodeWithTag("album.track.1").assertTextContains(context.getString(com.legitimateapps.dulcet.core.R.string.playback_untitled))
        assertEquals(3, publication.playableTracks(account.providerInstanceId).size)
        compose.onNodeWithTag("album.title").assertExists()
        compose.onNodeWithTag("album.play").assertIsEnabled()
        compose.onNodeWithTag("album.shuffle").assertIsEnabled()
        if (rowSelection) compose.onNodeWithTag("album.track.1").assertIsEnabled().performTouchInput { click() }
        else compose.onNodeWithTag(if (shuffle) "album.shuffle" else "album.play").performClick()
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            playback.state.value.validatedBytesConsumed > 0
        }
        assertUntitledPlayback(context, playback, "untitled-1", shuffle)
        if (rowSelection) assertEquals(tracks[1].rawId, playback.state.value.let { it.queue[checkNotNull(it.currentIndex)].track.rawId }, "The selected unknown-duration song plays")
        compose.onNodeWithTag("player.title").assertTextEquals(context.getString(com.legitimateapps.dulcet.core.R.string.playback_untitled))
        if (enrich) {
            val session = playback.state.value.playbackSessionId
            val attempt = playback.state.value.attemptId
            playback.rememberTracks(playback.state.value.queue.map { it.track.copy(title = "Recovered title") })
            compose.waitForIdle()
            assertEquals(session, playback.state.value.playbackSessionId)
            assertEquals(attempt, playback.state.value.attemptId)
            assertUntitledPlayback(context, playback, "untitled-1", expectedTitle = "Recovered title")
            compose.onNodeWithTag("player.title").assertTextEquals("Recovered title")
        }
        val withoutDuration = tracks.first().copy(durationMilliseconds = null)
        assertTrue(withoutDuration.canBeQueued())
        assertEquals(null, checkNotNull(withoutDuration.toTrack(account.providerInstanceId)).durationMilliseconds)
        assertTrue(tracks.all { it.canBeQueued() })
    }
}
