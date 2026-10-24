package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
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
    @Test fun anAlbumWithoutDurationsKeepsPlayAndShuffleDisabled() = exerciseAlbum(false, false)

    private fun exerciseAlbum(shuffle: Boolean, knownDuration: Boolean = true) {
        seedUntitledAlbum(context, account, server, "untitled-1")
        server.albumPlaybackAudio = untitledAlbumAudio()
        if (!knownDuration) server.albumSongDurationSeconds = null
        val playback = AndroidPlaybackController(context, PlaybackEndpointAccount(account.providerInstanceId, server.url, "u", "p", true))
        controller = playback
        val binding = PlaybackBinding().apply { bind(playback) }
        var playerOpen by mutableStateOf(false)
        var shown: AndroidLibraryPublication? = null
        val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {},
            playAlbum = { album, _, shuffle -> playAlbum(binding, account.providerInstanceId, album, null, shuffle) { playerOpen = true } },
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
        if (!knownDuration) {
            assertTrue(publication.playableTracks(account.providerInstanceId).isEmpty())
            compose.onNodeWithTag("album.play").assertIsNotEnabled()
            compose.onNodeWithTag("album.shuffle").assertIsNotEnabled()
            assertTrue(playback.state.value.queue.isEmpty())
            return
        }
        assertEquals(3, publication.playableTracks(account.providerInstanceId).size)
        compose.onNodeWithTag("album.title").assertExists()
        compose.onNodeWithTag("album.play").assertIsEnabled()
        compose.onNodeWithTag("album.shuffle").assertIsEnabled()
        compose.onNodeWithTag(if (shuffle) "album.shuffle" else "album.play").performClick()
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            playback.state.value.validatedBytesConsumed > 0
        }
        assertUntitledPlayback(context, playback, "untitled-1", shuffle)
        compose.onNodeWithTag("player.title").assertTextEquals(context.getString(com.legitimateapps.dulcet.core.R.string.playback_untitled))
        val withoutDuration = tracks.first().copy(durationMilliseconds = null)
        assertFalse(withoutDuration.canBeQueued())
        assertNull(withoutDuration.toTrack(account.providerInstanceId))
        assertTrue(tracks.all { it.canBeQueued() })
    }
}
