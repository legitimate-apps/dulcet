package com.legitimateapps.dulcet

import android.os.Looper
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.errorPhrase
import com.legitimateapps.dulcet.library.savedAccountLine
import com.legitimateapps.dulcet.shared.R
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.SavedAccountConnection
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.PlatformNetwork
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Historical test identifiers remain stable for parity evidence; assertions use automatic reconnect. */
/** Saved launches use the production session's ordinary reconnect, without a user action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedAccountLaunchTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = CountingServer()
    private val sessions = mutableListOf<LibrarySession>()

    @After fun close() {
        sessions.forEach(LibrarySession::close)
        closeReader()
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aLaunchIntoASavedAccountConnectsWithoutATapAndReconnectStillWorks() {
        LoopbackLibraryServer().use { library ->
            val session = open(SearchAccount("provider:launch", library.url, "u", "p", true), restoredAccount = true)
            val albums = session.openAlbums()
            settle("device content before starting") { albums.state.value != null }
            assertEquals(emptyList(), library.endpoints(), "Cached content paints before any network work")
            session.start()
            settle("automatic reconnect online") { session.connection.value is LibraryConnectionState.Online }
            assertTrue(library.requests("getScanStatus").isNotEmpty())
            val before = library.requests("getScanStatus").size
            val answers = session.observation.value.reconnectAnswers
            session.connectSavedAccount()
            settle("manual fallback reconnect completed") {
                library.requests("getScanStatus").size > before && session.observation.value.reconnectAnswers > answers
            }
            val foregroundBefore = library.requests("getScanStatus").size
            session.stop()
            session.start()
            settle("foreground reconnect") { library.requests("getScanStatus").size > foregroundBefore }
        }
    }

    @Test fun tryAgainJoinsTheAutomaticReconnectInFlight() {
        server.hold = true
        val session = open(account("provider:retry"), restoredAccount = true)
        session.start()
        settle("automatic request held") { server.connections.get() > 0 }
        val before = server.connections.get()
        session.retry()
        settleFor(300)
        assertEquals(before, server.connections.get(), "An in-flight reconnect is joined")
        server.release.countDown()
        settle("joined reconnect answered") { session.observation.value.reconnectAnswers >= 1 }
    }

    @Test fun aSavedAccountReconnectsWhenTheNetworkReturns() {
        LoopbackLibraryServer().use { library ->
            val session = open(SearchAccount("provider:network", library.url, "u", "p", true), restoredAccount = true)
            session.start()
            settle("initial automatic reconnect") { session.connection.value is LibraryConnectionState.Online }
            val network = PlatformNetwork(context)
            network.lose()
            try {
                assertIs<LibraryConnectionState.Offline>(session.connection.value)
                val before = library.requests("getScanStatus").size
                network.restore()
                settle("network-return reconnect") {
                    library.requests("getScanStatus").size > before && session.connection.value is LibraryConnectionState.Online
                }
            } finally {
                network.restoreIfLost()
            }
        }
    }

    @Test fun anAccountConnectedOnTheFormReadsAndSoDoesEveryLaterHostInTheSameProcess() {
        val account = account("provider:form")
        SavedAccountConnection.connectedOnTheForm(account.providerInstanceId)
        val first = open(account, restoredAccount = true)
        first.start()
        settle("the connected session's reconnect, answered") { first.observation.value.reconnectAnswers >= 1 }
        first.close()

        // A new screen host in the same process: a return to the foreground of a connected session.
        val before = server.connections.get()
        val second = open(account, restoredAccount = true)
        second.start()
        settle("the later host's reconnect, answered") { second.observation.value.reconnectAnswers >= 1 }
        assertTrue(server.connections.get() > before, "A session connected in this process reconnects on foreground")
    }

    @Test fun aNewProcessReaderForTheSameAccountReconnectsAutomatically() {
        val account = account("provider:process")
        SavedAccountConnection.connectedOnTheForm(account.providerInstanceId)
        val first = open(account, restoredAccount = true)
        first.start()
        settle("the connected session's reconnect, answered") { first.observation.value.reconnectAnswers >= 1 }
        first.close()
        closeReader()

        val before = server.connections.get()
        val relaunched = open(account, restoredAccount = true)
        relaunched.start()
        relaunched.openAlbums()
        settle("new process reconnect answered") { relaunched.observation.value.reconnectAnswers >= 1 }
        assertTrue(server.connections.get() > before, "A new reader reconnects without a tap")
        assertTrue(relaunched.connection.value !is LibraryConnectionState.Saved)
    }

    @Test fun anAccountStaysHeldWhileAnySessionOfItWaitsAndEachReleasesOnlyItsOwnHold() {
        val account = account("provider:two")
        val library = open(account, restoredAccount = true)
        val player = open(account, restoredAccount = true)
        assertTrue(account.providerInstanceId in SavedAccountConnection.waitingForReconnect.value)
        player.close()
        player.close()
        assertTrue(account.providerInstanceId in SavedAccountConnection.waitingForReconnect.value,
            "The library still waits, so cover art still reads only what this device kept")
        library.close()
        assertFalse(account.providerInstanceId in SavedAccountConnection.waitingForReconnect.value)
    }

    @Test fun reconnectChosenInOneScreenHostLeavesSavedInEveryOtherInPlace() {
        val account = account("provider:elsewhere")
        val library = open(account, restoredAccount = true)
        library.start()
        val search = open(account, restoredAccount = true)
        settleFor(500)
        assertTrue(server.connections.get() > 0, "The started library already requested reconnect")

        search.connectSavedAccount()
        settle("the started library's own reconnect, answered") { library.observation.value.reconnectAnswers >= 1 }
        assertTrue(library.connection.value !is LibraryConnectionState.Saved, "The library no longer says saved")
        assertFalse(account.providerInstanceId in SavedAccountConnection.waitingForReconnect.value)
    }

    @Test fun aReconnectTheServerRefusesStatesTheRefusalNotSaved() {
        server.answer = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
        val session = open(account("provider:refused"), restoredAccount = true)
        session.start()
        settle("the refused reconnect, answered") { session.observation.value.reconnectAnswers >= 1 }
        val state = assertIs<LibraryConnectionState.Failed>(session.connection.value)
        assertEquals(DomainError.Auth.InvalidCredentials, state.error)
        val line = assertNotNull(context.resources.connectionLine(state), "The failure is stated above the screen")
        assertTrue(context.getString(R.string.library_error_credentials) in line, line)
        val attempts = server.connections.get()
        settleFor(4_500)
        assertEquals(attempts, server.connections.get(), "Authentication failures do not retry on a timer")
    }

    @Test fun aReconnectToAServerThatIsGoneSaysItCannotBeReached() {
        val gone = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { "http://127.0.0.1:${it.localPort}" }
        val account = SearchAccount("provider:gone", gone, "launch-user", "launch-canary-password", true)
        val session = open(account, restoredAccount = true)
        session.start()
        settle("the unreachable reconnect, answered") { session.observation.value.reconnectAnswers >= 1 }
        val albums = session.openAlbums()
        settle("usable device library while unreachable") { albums.state.value != null }
        val state = assertIs<LibraryConnectionState.Offline>(session.connection.value)
        assertEquals(DomainError.Transport.Unreachable, state.error)
        assertEquals(context.getString(R.string.library_error_unreachable), context.resources.errorPhrase(state.error!!))
    }

    @Test fun aWaitingReaderInTheForegroundReadsNoEpochOnItsCadence() {
        val library = LoopbackLibraryServer()
        AndroidLibraryReader.testEpochIntervalMillis = 100
        try {
            val session = open(SearchAccount("provider:cadence", library.url, "u", "p", true), restoredAccount = true)
            session.start()
            session.openAlbums()
            settle("automatic connection") { session.connection.value is LibraryConnectionState.Online }
            val before = library.requests("getScanStatus").size
            settle("foreground cadence") { library.requests("getScanStatus").size >= before + 3 }
        } finally {
            AndroidLibraryReader.testEpochIntervalMillis = null
            sessions.forEach(LibrarySession::close)
            closeReader()
            library.close()
        }
    }

    @Test fun aTransientReconnectThatLaterFailsAuthenticationShowsTheFailureAndStopsRetrying() {
        server.status = 503
        server.answer = "<html>Unavailable</html>"
        val session = open(account("provider:retry-auth"), restoredAccount = true)
        session.openAlbums()
        session.start()
        settle("initial transient failure") { session.observation.value.reconnectAnswers >= 1 }
        server.status = 200
        server.answer = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Refused"}}}"""
        settle("the reader's retry surfaces authentication") {
            (session.connection.value as? LibraryConnectionState.Failed)?.error == DomainError.Auth.InvalidCredentials
        }
        val attempts = server.connections.get()
        settleFor(4_500)
        assertEquals(attempts, server.connections.get(), "No authentication retry timer")
    }

    @Test fun stoppingOneHostDoesNotStopTheVisibleHostsAutomaticRetries() {
        server.status = 503
        server.answer = "<html>Unavailable</html>"
        val account = account("provider:two-started")
        val library = open(account, restoredAccount = true)
        library.openAlbums()
        library.start()
        settle("the first host's transient failure") { library.observation.value.reconnectAnswers >= 1 }
        val player = open(account, restoredAccount = true)
        player.start()
        settle("the player's transient failure") { player.observation.value.reconnectAnswers >= 1 }
        library.stop()
        val before = server.connections.get()
        settleFor(300)
        assertEquals(before, server.connections.get(), "No tight retry loop")
        settle("retry while the player remains foreground") { server.connections.get() > before }
    }

    private fun account(id: String) = SearchAccount(id, server.url, "launch-user", "launch-canary-password", true)

    private fun open(account: SearchAccount, restoredAccount: Boolean) =
        LibrarySession(context, account, foreground = true, restoredAccount = restoredAccount).also { sessions += it }

    private fun closeReader() {
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "never: $what; ${sessions.map { it.observation.value.connections }}" }
            Thread.sleep(5)
        }
    }

    /** Lets the reader's thread and the main looper run, so anything it would send has been sent. */
    private fun settleFor(millis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Answers every request with an `ok` envelope, and counts the connections made to it. */
    private class CountingServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val connections = AtomicInteger()
        @Volatile var status = 200
        @Volatile var hold = false
        val release = java.util.concurrent.CountDownLatch(1)
        @Volatile var answer = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
        private val pool = Executors.newCachedThreadPool()

        init {
            pool.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    connections.incrementAndGet()
                    pool.submit { answer(client) }
                }
            }
        }

        private fun answer(client: java.net.Socket) = client.use {
            client.soTimeout = 5_000
            val input = client.getInputStream().bufferedReader()
            input.readLine() ?: return@use
            generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            if (hold) release.await(30, TimeUnit.SECONDS)
            val envelope = answer.toByteArray()
            client.getOutputStream().apply {
                write(("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${envelope.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                write(envelope)
                flush()
            }
        }

        override fun close() {
            release.countDown()
            socket.close()
            pool.shutdownNow()
        }
    }
}
