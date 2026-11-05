package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.hasConnectionNotice
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Listen Now when the server cannot be reached: no bare headings, and a failed refresh says so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television")
class TvListenNowOfflineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply {
        dulcetHome = true
        homePlaylists = listOf("kept" to "Kept playlist")
    }
    private val account = SearchAccount("tv-home-offline", server.url, "u", "p", true)
    private lateinit var session: LibrarySession

    @After fun close() {
        if (::session.isInitialized) session.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    private fun host() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                    TvListenNow(account, session, null, TvNavigator({}, {}))
                }
                LibraryLifecycle(session)
            }
        }
    }

    private fun tags(): List<String> = compose.onAllNodes(SemanticsMatcher("tagged") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        .filter { it.startsWith("listenNow") }.distinct()

    private fun await(what: String, condition: () -> Boolean) {
        try { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
        catch (e: Throwable) { fail("$what; tags=${tags()}; connection=${session.connection.value}") }
    }

    @Test fun openedOfflineWithNothingSavedItDrawsOneStatementAndNoBareHeadings() {
        server.close()
        host()
        await("the session to find the server gone") { session.connection.value.hasConnectionNotice() }
        compose.waitForIdle()
        println("TV LISTEN NOW OFFLINE tags=${tags()} connection=${session.connection.value}")
        await("the one statement") { tags().contains("listenNow.nothingSaved") }
        for (shelf in com.legitimateapps.dulcet.core.ListenNowShelf.entries.map { it.wireName }) {
            assertFalse(tags().contains("listenNow.$shelf"), "$shelf is a bare heading over nothing; tags=${tags()}")
        }
    }

    @Test fun aSessionThatLosesTheServerMidUseSaysSoOnARefresh() {
        host()
        await("a loaded shelf") { tags().any { it == "listenNow.recentPlaylists.item.0" } }
        server.close()
        compose.runOnIdle { session.refresh() }
        // Settled: the connection has decided what it is, and every shelf has stopped reloading.
        await("the refresh to settle") {
            val settled = session.connection.value.let { it is LibraryConnectionState.Online || it is LibraryConnectionState.Offline || it is LibraryConnectionState.Failed }
            settled && !tags().any { it.endsWith(".loading") } && (session.connection.value !is LibraryConnectionState.Online || tags().any { it.endsWith(".freshness") })
        }
        compose.waitForIdle()
        println("TV LISTEN NOW MID-USE tags=${tags()} connection=${session.connection.value}")
        // The roots draw the account notice when the connection itself is not online; otherwise the page must say it.
        assertTrue(session.connection.value.hasConnectionNotice() || tags().any { it.endsWith(".freshness") || it.endsWith(".unavailable") },
            "A refresh that could not reach the server must be visible; tags=${tags()}; connection=${session.connection.value}")
        assertTrue(tags().contains("listenNow.recent.item.0"), "the saved shelf stays while it says so; tags=${tags()}")
    }
}
