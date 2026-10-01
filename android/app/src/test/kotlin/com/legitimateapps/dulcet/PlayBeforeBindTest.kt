package com.legitimateapps.dulcet

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidQueueInsertion
import com.legitimateapps.dulcet.core.AndroidQueueSource
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.playback.PlaybackBinding
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import com.legitimateapps.dulcet.ui.DroppedAdditionsNotice
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A Play pressed before the playback service binds is held and made once it binds, on the phone
 * (spec §14.1); and Play Next or Add to Queue dropped with a queue that never started is said.
 * The phone runs as it ships -- PhoneApp, the production library session and reader, a real
 * controller -- with only the server a loopback fixture and the binding handed in, so the test
 * decides when the service "binds". Every request the controller makes is one the server records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PlayBeforeBindTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val provider = "provider:bind"
    private val account = SearchAccount(provider, server.url, "u", "p", true)
    private val controllers = mutableListOf<AndroidPlaybackController>()

    private fun controller() = AndroidPlaybackController(context,
        PlaybackEndpointAccount(provider, server.url, "u", "p", true)).also { controllers += it }

    @After fun close() {
        controllers.forEach { it.close() }
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

    // ---- The binding ---------------------------------------------------------------------------------

    /** Held until a controller binds, the latest only, applied once; with one bound, made at once. */
    @Test fun theBindingHoldsTheLatestPlayUntilAControllerBindsAndThenPlaysAtOnce() {
        val binding = PlaybackBinding()
        val made = mutableListOf<Pair<String, AndroidPlaybackController>>()
        binding.play { made += "first" to it }
        binding.play { made += "second" to it }
        assertTrue(binding.holding)
        binding.bind(null)
        assertTrue(made.isEmpty() && binding.holding, "binding nothing applies nothing")
        val bound = controller()
        binding.bind(bound)
        assertEquals(listOf("second" to bound), made, "only the latest Play, on the controller that bound")
        assertFalse(binding.holding)
        binding.bind(bound)
        assertEquals(1, made.size, "a held Play is made once")
        binding.play { made += "third" to it }
        assertEquals("third" to bound, made.last(), "with a controller bound, the Play is made at once")
        binding.bind(null)
        binding.play { made += "fourth" to it }
        assertEquals(2, made.size, "after the service unbinds, a Play waits again")
        assertTrue(binding.holding)
    }

    // ---- The phone -----------------------------------------------------------------------------------

    /**
     * A genre's Play pressed before the service binds plays the genre once it binds. Before the fix the
     * press found no controller and was lost: no getSong was ever asked for.
     */
    @Test fun aPlayPressedBeforeTheServiceBindsIsMadeOnceItBinds() {
        // Made before the screens start, as the service makes it before it can bind, and handed in
        // only later: opening the database while the library session writes to it would block the
        // main thread on SQLite's busy wait, which is not what this test is about.
        val controller = controller()
        var bound by mutableStateOf<AndroidPlaybackController?>(null)
        host { bound }
        click("library.open")
        click("library.view.genres")
        await("the genres listed") { exists("library.genres.item.0") }
        click("library.genres.item.0")
        await("Jazz's songs and Play") { exists("genre.track.1") && exists("genre.play") }
        click("genre.play")
        settle()
        assertTrue(server.requests("getSong").isEmpty(), "setup: nothing can play before the service binds")

        compose.runOnIdle { bound = controller }
        await("the held Play made once the service binds") { server.requests("getSong").isNotEmpty() }
        assertEquals(setOf("jazz-song-1"), server.requests("getSong").map { it["id"] }.toSet(),
            "the genre's first song, as Play asked")
    }

    /** With the service bound, the same Play is made at once, as it always was. */
    @Test fun aPlayPressedOnceBoundIsMadeAtOnce() {
        val controller = controller()
        host { controller }
        click("library.open")
        click("library.view.genres")
        await("the genres listed") { exists("library.genres.item.0") }
        click("library.genres.item.0")
        await("Jazz's songs and Play") { exists("genre.track.1") && exists("genre.play") }
        click("genre.play")
        await("the Play made") { server.requests("getSong").isNotEmpty() }
        assertEquals(setOf("jazz-song-1"), server.requests("getSong").map { it["id"] }.toSet())
    }

    // ---- Dropped additions -------------------------------------------------------------------------

    /**
     * An addition made while a queue loads, dropped because that queue failed, is said once -- the
     * count of tracks it carried -- and the controller is told, so nothing says it again.
     */
    @Test fun additionsDroppedWithAQueueThatFailedAreSaidOnce() {
        val controller = controller()
        compose.setContent {
            MaterialTheme {
                val state by controller.state.collectAsState()
                DroppedAdditionsNotice(controller, state.droppedAdditions)
            }
        }
        compose.waitForIdle()
        val toasts = ShadowToast.shownToastCount()
        // The fixture answers getSong with no song, so this queue fails once its first entry is read.
        controller.playQueue(listOf(AndroidTrack(provider, "a1", "A1")), 0, AndroidQueueSource.Library, "Library")
        assertTrue(controller.addToQueue(listOf(AndroidTrack(provider, "x1", "X1"), AndroidTrack(provider, "x2", "X2")),
            AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"), "held while the queue loads")
        await("the queue to fail and the drop to be said") { ShadowToast.shownToastCount() > toasts }
        assertNotNull(controller.state.value.error, "setup: the queue failed")
        assertEquals(context.resources.getQuantityString(com.legitimateapps.dulcet.shared.R.plurals.queue_additions_dropped, 2, 2),
            ShadowToast.getTextOfLatestToast())
        await("the controller told it was said") { controller.state.value.droppedAdditions == null }
        settle()
        assertEquals(toasts + 1, ShadowToast.shownToastCount(), "said once")
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private fun host(playback: () -> AndroidPlaybackController?) {
        val dependencies = object : SearchHostDependencies {
            override fun loadAccount(context: Context): SearchAccount = account
            override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter =
                ProductionSearchHostDependencies.createPresenter(account, context, foreground)
        }
        compose.setContent { MaterialTheme { PhoneApp(account, dependencies, PhonePlaybackRequests(), playback()) } }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        await(tag) { exists(tag) }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        compose.waitForIdle()
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000) {
                shadowOf(Looper.getMainLooper()).idle()
                runCatching(condition).getOrDefault(false)
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; requests=${server.endpoints()}", timeout)
        }
    }
}
