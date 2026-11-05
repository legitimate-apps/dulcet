package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.RECONNECT_NOTICE_GRACE_MILLIS
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import com.legitimateapps.dulcet.library.connectionFailureIsUnreachable
import com.legitimateapps.dulcet.library.connectionLine
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

/** A reconnect that resolves at once is nobody's business; one that does not, and a real outage, are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneReconnectNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val account = SearchAccount("phone-reconnect-notice", server.url, "u", "p", true)
    private lateinit var session: LibrarySession

    @After fun close() {
        server.releaseScanStatus()
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
                session = remember { LibrarySession(context, account, true, restoredAccount = true) }
                SavedAccountNotice(session, account)
                LibraryLifecycle(session)
            }
        }
    }

    private fun shown() = compose.onAllNodesWithTag("library.saved").fetchSemanticsNodes().isNotEmpty()

    private fun await(what: String, condition: () -> Boolean) {
        try { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
        catch (e: Throwable) { fail("$what; connection=${session.connection.value}; shown=${shown()}") }
    }

    /** Moves past [millis] with the state unchanged, and says whether the notice ever stood on the way. */
    private fun seenWithin(millis: Long): Boolean {
        var seen = false
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            seen = seen || shown()
        }
        return seen
    }

    private fun inFlight() = session.connection.value == LibraryConnectionState.Saved || session.connection.value == LibraryConnectionState.Connecting

    @Test fun aReconnectThatResolvesWithinTheGraceNeverShowsTheNotice() {
        server.holdScanStatus = true
        compose.mainClock.autoAdvance = false
        host()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue(inFlight(), "the condition under test: the reconnect is running; ${session.connection.value}")
        assertFalse(shown() || seenWithin(RECONNECT_WINDOW_INSIDE_THE_GRACE), "a reconnect inside the grace is invisible")
        assertTrue(inFlight(), "still running, so nothing but the grace kept it quiet; ${session.connection.value}")
        server.releaseScanStatus()
        compose.mainClock.autoAdvance = true
        await("the answer") { session.connection.value is LibraryConnectionState.Online }
        assertFalse(shown(), "and nothing is left behind")
    }

    @Test fun aReconnectThatOutlastsTheGraceSaysSo() {
        server.holdScanStatus = true
        host()
        await("the notice over a reconnect still running") { shown() }
        assertTrue(inFlight(), "${session.connection.value}")
        server.releaseScanStatus()
        await("the answer to clear it") { !shown() }
    }

    /** The trip back to the foreground, every time the app comes forward. */
    @Test fun anOnlineAccountComingForwardShowsNothing() {
        host()
        await("online") { session.connection.value is LibraryConnectionState.Online }
        compose.waitForIdle() // The page has composed the answer: an app in use, not one still opening.
        server.holdScanStatus = true
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { session.stop(); session.start() }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(session.connection.value == LibraryConnectionState.Connecting, "the condition under test; ${session.connection.value}")
        assertFalse(shown() || seenWithin(RECONNECT_NOTICE_GRACE_MILLIS * 2), "an online reader stays online however long its check takes (§16.14)")
    }

    /** With the server really gone the strip stands, and a retry does not take it away and bring it back. */
    @Test fun anOutageKeepsItsNoticeThroughARetry() {
        server.close()
        host()
        await("the outage") { session.connection.value is LibraryConnectionState.Offline }
        await("its notice") { shown() }
        compose.runOnUiThread { session.connectSavedAccount() }
        compose.waitForIdle()
        var left = false
        await("the retry to fail again") {
            left = left || !shown()
            session.connection.value is LibraryConnectionState.Offline
        }
        assertFalse(left, "the notice never left while the outage stood")
        assertTrue(shown())
    }

    /** A server that was not there is stated once and calmly; a failure with a reason of its own still names it. */
    @Test fun anUnreachableServerIsSaidOnceAndAFailureKeepsItsReason() {
        val resources = context.resources
        val away = com.legitimateapps.dulcet.library.LibraryConnectionState.Failed(com.legitimateapps.dulcet.core.DomainError.Transport.Cancelled, readerOffline = true)
        assertEquals("Showing what is on this device. Couldn't reach your server.", resources.connectionLine(away))
        assertTrue(resources.connectionFailureIsUnreachable(away))
        val refused = com.legitimateapps.dulcet.library.LibraryConnectionState.Failed(com.legitimateapps.dulcet.core.DomainError.Auth.InvalidCredentials, readerOffline = true)
        assertTrue(resources.connectionLine(refused)!!.startsWith("Couldn't connect to your server — "))
        assertFalse(resources.connectionFailureIsUnreachable(refused))
    }

    private companion object {
        const val RECONNECT_WINDOW_INSIDE_THE_GRACE = 2_500L
    }
}
