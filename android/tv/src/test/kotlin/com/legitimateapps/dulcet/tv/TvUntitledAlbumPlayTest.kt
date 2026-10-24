package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.seedUntitledAlbum
import com.legitimateapps.dulcet.search.conformance.assertUntitledPlayback
import com.legitimateapps.dulcet.search.conformance.untitledAlbumAudio
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
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
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvUntitledAlbumPlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("untitled-tv", server.url, "u", "p", true)
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

    @Test fun anAlbumWithOnlyBlankOrMissingTitlesPlaysWithLocalizedUntitledMetadata() = exerciseAlbum(false)
    @Test fun anAlbumWithOnlyBlankOrMissingTitlesShufflesWithLocalizedUntitledMetadata() = exerciseAlbum(true)
    @Test fun anAlbumWithoutDurationsKeepsPlayAndShuffleHidden() = exerciseAlbum(false, false)

    private fun exerciseAlbum(shuffle: Boolean, knownDuration: Boolean = true) {
        seedUntitledAlbum(context, account, server, "newest-1")
        server.albumPlaybackAudio = untitledAlbumAudio()
        if (!knownDuration) server.albumSongDurationSeconds = null
        var playerOpen by mutableStateOf(false)
        // Bind playback so a missing queue action cannot pass just because the service is absent.
        controller = AndroidPlaybackController(context,
            PlaybackEndpointAccount(account.providerInstanceId, server.url, "u", "p", true))
        compose.setContent { MaterialTheme {
            val state by controller!!.state.collectAsState()
            if (playerOpen) TvNowPlayingScreen(account, state, null)
            else TvLibraryEntry(account, playback = controller) { _, _ -> }
        } }
        await { focused("listenNow.open") }
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        assertTrue(focused("library.open"))
        key(Key.DirectionCenter)
        await { focused("library.home.0.item.0") }
        key(Key.DirectionCenter)
        await {
            val surfaces = compose.onAllNodesWithTag("album.surface").fetchSemanticsNodes()
            surfaces.singleOrNull()?.config?.get(LibraryObservation)?.surfaces?.values?.flatten()?.any {
                it.freshness == AndroidLibraryFreshness.Live && it.itemsState == AndroidLibraryItemsState.Present && it.itemCount == 3
            } == true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("album.title").assertExists()
        if (!knownDuration) {
            compose.onNodeWithTag("album.play").assertDoesNotExist()
            compose.onNodeWithTag("album.shuffle").assertDoesNotExist()
            assertTrue(controller!!.state.value.queue.isEmpty())
            return
        }
        compose.onNodeWithTag("album.play").assertIsEnabled()
        compose.onNodeWithTag("album.shuffle").assertIsEnabled()
        val action = if (shuffle) "album.shuffle" else "album.play"
        compose.onNodeWithTag(action).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(action).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        await { controller!!.state.value.validatedBytesConsumed > 0 }
        assertUntitledPlayback(context, controller!!, "newest-1", shuffle)
        assertTrue(shadowOf(context).nextStartedActivity != null, "Playback opens the TV player")
        compose.runOnIdle { playerOpen = true }
        compose.onNodeWithTag("tv.player.title").assertTextEquals(context.getString(com.legitimateapps.dulcet.core.R.string.playback_untitled))
    }

    private fun focused(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); condition() }
        } catch (failure: Throwable) {
            throw AssertionError("Timed out; endpoints=${server.endpoints()}; state=${controller?.state?.value}", failure)
        }
    }
}
