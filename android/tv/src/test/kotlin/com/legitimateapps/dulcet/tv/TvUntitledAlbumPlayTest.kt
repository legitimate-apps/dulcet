package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.seedUntitledAlbum
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

    @Test fun anAlbumWithOnlyBlankOrMissingTitlesOffersNoPlayOrShuffle() {
        seedUntitledAlbum(context, account, server, "newest-1")
        // Bind playback so a missing queue action cannot pass just because the service is absent.
        controller = AndroidPlaybackController(context,
            PlaybackEndpointAccount(account.providerInstanceId, server.url, "u", "p", true))
        compose.setContent { MaterialTheme { TvLibraryEntry(account, playback = controller) { _, _ -> } } }
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
                it.itemsState == AndroidLibraryItemsState.Present && it.itemCount == 3
            } == true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("album.title").assertExists()
        compose.onNodeWithTag("album.play").assertDoesNotExist()
        compose.onNodeWithTag("album.shuffle").assertDoesNotExist()
        compose.onNodeWithTag("album.queue").assertDoesNotExist()
        assertTrue(server.requests("getSong").isEmpty(), "No queue was started")
    }

    private fun focused(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(30_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
}
