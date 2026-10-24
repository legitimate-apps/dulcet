package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.playableTracks
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.seedUntitledAlbum
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class UntitledAlbumPlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("untitled-phone", server.url, "u", "p", true)

    @After fun close() {
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

    @Test fun anAlbumWithOnlyBlankOrMissingTitlesDisablesPlayAndShuffle() {
        seedUntitledAlbum(context, account, server, "untitled-1")
        var shown: AndroidLibraryPublication? = null
        val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {},
            playAlbum = { _, _, _ -> error("An untitled album cannot start a queue") },
            playArtist = { _, _, _ -> null }, rememberAlbum = { shown = it })
        compose.setContent {
            MaterialTheme {
                val session = remember { LibrarySession(context, account, true) }
                AlbumScreen(account, session, "untitled-1", null, actions)
                LibraryLifecycle(session)
            }
        }
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            shown?.let { it.itemsState == AndroidLibraryItemsState.Present && it.items.size == 3 } == true
        }
        compose.waitForIdle()
        val publication = checkNotNull(shown)
        val tracks = publication.items.filterIsInstance<AndroidLibraryItem.Track>()
        assertEquals(3, tracks.size, "The reader must publish every fixture track")
        assertTrue(tracks.all { it.title.isNullOrBlank() && it.playability == AndroidLibraryPlayability.Streamable })
        assertTrue(publication.playableTracks(account.providerInstanceId).isEmpty(), "No track can become a queue entry")
        compose.onNodeWithTag("album.title").assertExists()
        compose.onNodeWithTag("album.play").assertIsNotEnabled()
        compose.onNodeWithTag("album.shuffle").assertIsNotEnabled()
        compose.onNodeWithTag("album.menu").assertDoesNotExist()
    }
}
