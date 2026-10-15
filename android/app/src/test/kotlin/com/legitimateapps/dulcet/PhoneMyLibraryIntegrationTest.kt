package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import com.legitimateapps.dulcet.search.conformance.PlatformNetwork
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
 * The phone's My Library over the PRODUCTION stack (spec §29.9): PhoneApp, the session, the reader
 * and the core's `AndroidMyLibrary`, with only the server a loopback fixture that advertises
 * `dulcetLibrary` and answers its endpoints. Every write asserted here is one the core sent over
 * the wire; detection is the fixture's advertised extension, exactly as a Dulcet Server offers it.
 * Hosting is an ActivityScenario, so a test can end the activity and open it again as a relaunch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneMyLibraryIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("provider:mylibrary-live", server.url, "u", "p", true)
    private val scenarios = mutableListOf<ActivityScenario<ComponentActivity>>()

    @After fun close() {
        scenarios.forEach { runCatching { it.close() } }
        closeReader()
        server.close()
        context.deleteDatabase("dulcet.db")
        context.getSharedPreferences("dulcet.ui", 0).edit().clear().commit()
    }

    private fun closeReader() {
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun host(restoredAccount: Boolean = false): ActivityScenario<ComponentActivity> {
        val dependencies = object : SearchHostDependencies {
            override fun loadAccount(context: android.content.Context): SearchAccount = account
            override fun createPresenter(account: SearchAccount, context: android.content.Context, foreground: Boolean) =
                ProductionSearchHostDependencies.createPresenter(account, context, foreground)
        }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenarios += scenario
        scenario.onActivity { activity ->
            activity.setContent { MaterialTheme {
                PhoneApp(account, dependencies, PhonePlaybackRequests(), playback = null, restoredAccount = restoredAccount)
            } }
        }
        compose.waitForIdle()
        return scenario
    }

    @Test fun theSplitFollowsTheExtensionAndAnAddFromBrowseShowsInLibraryAndOnTheServer() {
        server.dulcetLibrary = true
        host()
        await("the split, once the extension is detected") { exists("browse.open") }
        assertTrue(server.requests("getOpenSubsonicExtensions").isNotEmpty(), "detection asked the server")

        click("browse.open")
        click("library.view.albums")
        await("the catalog's albums") { exists("library.albums.item.0") }
        click("library.albums.item.0")
        await("the album's Add to Library") { exists("album.library") }
        click("album.library")
        await("the add on the wire") { server.requests("addToDulcetLibrary").isNotEmpty() }
        assertEquals("alphabeticalByName-1",
            server.requests("addToDulcetLibrary").first().let { it["albumId"] ?: it["id"] },
            "the album the person added, as the wire carries it")
        await("the check once the server answered") { selected("album.library") }

        click("album.back")
        click("library.open")
        await("the album in My Library") { exists("mylibrary.recent.album.0") }
        assertTrue(server.requests("getDulcetLibrary").isNotEmpty(), "the library was read from the server")
    }

    @Test fun aRemoveOnTheWireTakesTheAlbumOutAndARelaunchKeepsTheLibrary() {
        server.dulcetLibrary = true
        host()
        await("the split") { exists("browse.open") }
        click("browse.open")
        click("library.view.albums")
        await("the catalog's albums") { exists("library.albums.item.0") }
        click("library.albums.item.0")
        await("the album's Add") { exists("album.library") }
        click("album.library")
        await("the add on the wire") { server.requests("addToDulcetLibrary").isNotEmpty() }

        // A relaunch: the activity goes, the reader closes, the account's data stays on the device.
        scenarios.forEach { it.close() }
        scenarios.clear()
        closeReader()
        host()
        await("the split after relaunch") { exists("browse.open") }
        click("library.open")
        await("the album kept across the relaunch") { exists("mylibrary.recent.album.0") }

        click("mylibrary.recent.album.0")
        await("the album's Remove") { exists("album.library") && selected("album.library") }
        click("album.library")
        await("the remove on the wire") { server.requests("removeFromDulcetLibrary").isNotEmpty() }
        await("the album no longer a member") { !selected("album.library") }
        click("album.back")
        click("library.open")
        await("the library empty of it") { exists("mylibrary.empty") }
    }

    @Test fun anAddMadeOfflineSyncsOnReconnect() {
        server.dulcetLibrary = true
        host()
        await("the split") { exists("browse.open") }
        click("browse.open")
        click("library.view.albums")
        await("the catalog's albums") { exists("library.albums.item.0") }
        click("library.albums.item.0")
        await("the album's Add") { exists("album.library") }

        val network = PlatformNetwork(context)
        network.lose()
        compose.waitForIdle()
        click("album.library")
        await("the check with nothing sent yet") { selected("album.library") }
        Thread.sleep(500)
        assertTrue(server.requests("addToDulcetLibrary").isEmpty(), "offline, the add waits in the outbox")
        // Offline, the add is already the library the person sees.
        click("album.back")
        click("library.open")
        await("the album in My Library, offline") { exists("mylibrary.recent.album.0") }

        network.restore()
        await("the add sent on reconnect") { server.requests("addToDulcetLibrary").isNotEmpty() }
    }

    @Test fun aServerWithoutTheExtensionNeverSeesALibraryCallAndNothingSplits() {
        host()
        click("library.open")
        await("the catalog, as today") { exists("library.surface") }
        await("its albums read") { server.requests("getAlbumList2").isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(500)
        assertFalse(exists("browse.open"), "no split without the extension")
        assertFalse(exists("mylibrary.surface"))
        assertTrue(server.requests("getDulcetLibrary").isEmpty(), "the library endpoint is never called")
        assertTrue(server.requests("addToDulcetLibrary").isEmpty())
        assertTrue(server.requests("removeFromDulcetLibrary").isEmpty())
    }

    @Test fun savedAccountAutomaticallyReconnectsAndDiscoversTheSplitWithoutAnotherRefresh() {
        server.dulcetLibrary = true
        host(restoredAccount = true)
        click("library.open")
        await("the split after automatic reconnect") { exists("browse.open") }
        await("the account's library read") { server.requests("getDulcetLibrary").isNotEmpty() }
        assertTrue(server.requests("getScanStatus").isNotEmpty(), "startup uses the ordinary reconnect")
        assertTrue(server.requests("getOpenSubsonicExtensions").isNotEmpty(), "startup discovers the extension")
        assertFalse(exists("library.reconnect"), "a connected account has no reconnect banner")
        assertTrue(server.requests("addToDulcetLibrary").isEmpty(), "discovery does not mutate membership")
        assertTrue(server.requests("removeFromDulcetLibrary").isEmpty())
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun selected(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false } }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!runCatching(condition).getOrDefault(false)) {
            compose.waitForIdle()
            check(System.nanoTime() < deadline) { "never: $what; requests=${server.endpoints()}" }
            Thread.sleep(10)
        }
    }
}
