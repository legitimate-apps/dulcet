package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidLibraryCachedReason
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.ListenNowShelf
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Listen Now when the server goes away: the page keeps what it has, and something on it says so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneListenNowOfflineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply {
        dulcetHome = true
        homePlaylists = listOf("kept-1" to "Kept one", "kept-2" to "Kept two", "kept-3" to "Kept three")
    }
    private val account = SearchAccount("phone-home-offline", server.url, "u", "p", true)
    private lateinit var session: LibrarySession
    private var shelf: AndroidLibraryPublication? = null
    private val launch = androidx.compose.runtime.mutableIntStateOf(0)

    @After fun close() {
        if (::session.isInitialized) session.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    // The account notice sits with the page, as PhoneApp draws it above the tabs.
    private fun host(restoredAccount: Boolean = false) {
        compose.setContent {
            MaterialTheme { androidx.compose.runtime.key(launch.intValue) {
                session = remember { LibrarySession(context, account, true, restoredAccount || launch.intValue > 0) }
                shelf = rememberSurface(session, "probe") { openListenNow(ListenNowShelf.RecentPlaylists) }.state.collectAsState().value
                Column {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                        PhoneListenNow(account, session, PhoneActions(openAlbum = {}, openArtist = {}, back = {},
                            playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null }), AndroidPlaybackState(), null)
                    }
                    SavedAccountNotice(session, account)
                }
                LibraryLifecycle(session)
            } }
        }
    }

    /** A new process in everything but the process: the reader closed, the same database, a new session. */
    private fun relaunch() {
        compose.runOnIdle { session.close() }
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        await("the reader to close") { closed }
        compose.runOnIdle { launch.intValue++ }
    }

    private fun tags(): List<String> = compose.onAllNodes(SemanticsMatcher("tagged") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()

    private fun statements() = tags().filter { it in setOf("library.saved", "library.connection", "listenNow.freshness") }

    private fun await(what: String, condition: () -> Boolean) {
        try { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
        catch (e: Throwable) { fail("$what; tags=${tags()}; connection=${session.connection.value}; shelf=${shelf?.freshness}") }
    }

    @Test fun aSessionThatLosesTheServerMidUseSaysSoOnceAndKeepsTheShelf() {
        host()
        await("a loaded shelf on a connected session") {
            tags().contains("listenNow.recentPlaylists.item.0") && session.connection.value is LibraryConnectionState.Online
        }
        server.close()
        compose.runOnIdle { session.refresh() }
        // The condition under test: the shelf's own read has failed and left its items in place.
        await("the shelf's refresh to fail") {
            (shelf?.freshness as? AndroidLibraryFreshness.Cached)?.reason is AndroidLibraryCachedReason.Failed
        }
        // The connection stays online here, so the notice under the tabs has nothing to say: the page must.
        await("the line") { tags().contains("listenNow.freshness") }
        println("PHONE LISTEN NOW MID-USE tags=${tags()} connection=${session.connection.value}")
        assertEquals(1, statements().size, "exactly one line says the refresh failed; statements=${statements()}; tags=${tags()}; connection=${session.connection.value}")
        assertTrue(tags().contains("listenNow.recentPlaylists.item.0"), "the shelf stays while it says so; tags=${tags()}")
    }

    @Test fun theLineClearsWhenTryAgainReachesTheServer() {
        host()
        await("a loaded shelf on a connected session") {
            tags().contains("listenNow.recentPlaylists.item.0") && session.connection.value is LibraryConnectionState.Online
        }
        assertTrue(statements().isEmpty(), "control: nothing has failed yet; statements=${statements()}")
        server.failHome = true
        compose.runOnIdle { session.refresh() }
        await("the line") { tags().contains("listenNow.freshness") }
        server.failHome = false
        compose.onNodeWithTag("listenNow.retry").performClick()
        await("the line to clear") { !tags().contains("listenNow.freshness") }
        assertTrue(tags().contains("listenNow.recentPlaylists.item.0"), "the shelf stays; tags=${tags()}")
    }

    /** With nothing on the page, "what you last saw" would describe a blank page: say the failure, and offer the way out. */
    @Test fun aFailedRefreshOverNoShelvesSaysSoAndClaimsNothing() {
        server.homeAlbumCount = 0
        server.homePlaylists = emptyList()
        host()
        await("empty shelves on a connected session") {
            session.connection.value is LibraryConnectionState.Online && tags().none { it.endsWith(".loading") }
        }
        assertTrue(tags().none { it.endsWith(".item.0") }, "the condition under test: no shelf has items; tags=${tags()}")
        assertFalse(tags().contains("listenNow.freshness"), "control: nothing has failed yet; tags=${tags()}")
        server.failHome = true
        compose.runOnIdle { session.refresh() }
        await("the page's statement") { tags().contains("listenNow.freshness") }
        val said = compose.onNodeWithTag("listenNow.freshness", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        assertFalse(said.contains("last saw", ignoreCase = true), "a blank page has nothing it last showed: $said")
        assertTrue(tags().contains("listenNow.unreachable"), "the centred state, not the inline line; tags=${tags()}")
        server.failHome = false
        compose.onNodeWithTag("listenNow.retry").performClick()
        await("the statement to clear") { !tags().contains("listenNow.freshness") }
    }

    /** Over real shelves the inline line is the true one, and the centred state stays away. */
    @Test fun aFailedRefreshOverShelvesKeepsTheInlineLine() {
        host()
        await("a loaded shelf") { tags().contains("listenNow.recentPlaylists.item.0") && session.connection.value is LibraryConnectionState.Online }
        server.failHome = true
        compose.runOnIdle { session.refresh() }
        await("the line") { tags().contains("listenNow.freshness") }
        val said = compose.onNodeWithTag("listenNow.freshness", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(said.contains("last saw", ignoreCase = true), "the line says what the shelves are: $said")
        assertFalse(tags().contains("listenNow.unreachable"), "tags=${tags()}")
    }

    @Test fun aConnectionThatFailedIsSaidOnThePageOnce() {
        server.scanFailureCode = 40
        host(restoredAccount = true)
        await("the failed connection") { (session.connection.value as? LibraryConnectionState.Failed)?.readerOffline == true }
        await("its line") { tags().contains("library.connection") }
        assertEquals(listOf("library.connection"), statements())
    }

    /** What a person whose server has dropped since they last used the app sees: every shelf this device read. */
    @Test fun aLaunchWithTheServerGoneKeepsEveryShelfTheDeviceRead() {
        server.homeAlbumCount = 6
        host()
        await("shelves read on a connected session") {
            session.connection.value is LibraryConnectionState.Online && tags().none { it.endsWith(".loading") } && tags().any { it.endsWith(".item.0") }
        }
        fun shelves() = ListenNowShelf.entries.map { it.wireName }.filter { "listenNow.$it.item.0" in tags() }
        val read = shelves()
        assertTrue(read.size >= 2, "the condition under test: several shelves were read; shelves=$read tags=${tags()}")
        server.close()
        relaunch()
        await("the session to find the server gone and its shelves to settle") {
            session.connection.value is LibraryConnectionState.Offline && tags().none { it.endsWith(".loading") }
        }
        println("PHONE LISTEN NOW RELAUNCH read=$read kept=${shelves()} tags=${tags()}")
        assertTrue(shelves().containsAll(read), "every shelf read last time is still on the page; read=$read tags=${tags()}")
        assertEquals(listOf("library.saved"), statements())
    }

    // Phone has no "nothing saved" statement of its own: the account notice is the only one, over no shelves.
    @Test fun openedOfflineWithNothingSavedTheAccountNoticeIsTheOnlyStatement() {
        server.close()
        host()
        await("the session to find the server gone and its shelves to settle") {
            session.connection.value is LibraryConnectionState.Offline && tags().none { it.endsWith(".loading") }
        }
        println("PHONE LISTEN NOW OFFLINE tags=${tags()} connection=${session.connection.value}")
        assertEquals(listOf("library.saved"), statements())
        for (shelf in ListenNowShelf.entries.map { it.wireName }) assertFalse(tags().contains("listenNow.$shelf"), "$shelf is a bare heading; tags=${tags()}")
    }
}
