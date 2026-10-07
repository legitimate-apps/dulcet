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

/**
 * CONF-10b through the PRODUCTION library session (spec §13.1): an app opened on a saved account
 * contacts nothing — not at start, not for a screen that opens, not on a return to the foreground —
 * until the person chooses Reconnect, which then reconnects in place. A session connected in this
 * process (Connect on the form, or Reconnect) reads as §16.14 says. The server is a loopback socket
 * that counts every connection made to it.
 */
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

    @Test fun aLaunchIntoASavedAccountSendsNothingUntilReconnectThenConnectsInPlace() {
        val account = account("provider:launch")
        val session = open(account, untilReconnectChosen = true)
        session.start()
        val albums = session.openAlbums()
        session.stop()
        session.start()
        settleFor(1_500)
        assertEquals(0, server.connections.get(), "Nothing may reach the server before Reconnect")
        assertEquals(LibraryConnectionState.Saved, session.connection.value)
        assertEquals(listOf(false), session.observation.value.reachabilityReports, "The reader is told only that it may not read")
        assertEquals(0, session.observation.value.reconnects)
        assertTrue(albums.state.value != null, "The screen paints from what this device has seen")

        session.connectSavedAccount()
        settle("the reconnect Reconnect starts, answered") { session.observation.value.reconnectAnswers >= 1 }
        assertTrue(server.connections.get() > 0, "Reconnect reaches the server")
        assertEquals(1, session.observation.value.reconnects)
        assertTrue(session.connection.value != LibraryConnectionState.Saved, "The session no longer says saved")
    }

    @Test fun tryAgainOnASavedAccountIsTheReconnectThePersonAskedFor() {
        val session = open(account("provider:retry"), untilReconnectChosen = true)
        session.start()
        settleFor(500)
        assertEquals(0, server.connections.get(), "control: nothing before the person asks")
        session.retry()
        settle("the reconnect Try again starts, answered") { session.observation.value.reconnectAnswers >= 1 }
        assertTrue(server.connections.get() > 0)
    }

    @Test fun anAccountConnectedOnTheFormReadsAndSoDoesEveryLaterHostInTheSameProcess() {
        val account = account("provider:form")
        SavedAccountConnection.connectedOnTheForm(account.providerInstanceId)
        val first = open(account, untilReconnectChosen = true)
        first.start()
        settle("the connected session's reconnect, answered") { first.observation.value.reconnectAnswers >= 1 }
        first.close()

        // A new screen host in the same process: a return to the foreground of a connected session.
        val before = server.connections.get()
        val second = open(account, untilReconnectChosen = true)
        second.start()
        settle("the later host's reconnect, answered") { second.observation.value.reconnectAnswers >= 1 }
        assertTrue(server.connections.get() > before, "A session connected in this process reconnects on foreground")
    }

    @Test fun aNewProcessReaderForTheSameAccountWaitsForReconnectAgain() {
        val account = account("provider:process")
        SavedAccountConnection.connectedOnTheForm(account.providerInstanceId)
        val first = open(account, untilReconnectChosen = true)
        first.start()
        settle("the connected session's reconnect, answered") { first.observation.value.reconnectAnswers >= 1 }
        first.close()
        closeReader()

        val before = server.connections.get()
        val relaunched = open(account, untilReconnectChosen = true)
        relaunched.start()
        relaunched.openAlbums()
        settleFor(1_500)
        assertEquals(before, server.connections.get(), "A reader the person has not connected sends nothing")
        assertIs<LibraryConnectionState.Saved>(relaunched.connection.value)
    }

    @Test fun anAccountStaysHeldWhileAnySessionOfItWaitsAndEachReleasesOnlyItsOwnHold() {
        val account = account("provider:two")
        val library = open(account, untilReconnectChosen = true)
        val player = open(account, untilReconnectChosen = true)
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
        val library = open(account, untilReconnectChosen = true)
        library.start()
        val search = open(account, untilReconnectChosen = true)
        settleFor(500)
        assertEquals(0, server.connections.get(), "control: nothing before the person asks")

        search.connectSavedAccount()
        settle("the started library's own reconnect, answered") { library.observation.value.reconnectAnswers >= 1 }
        assertTrue(library.connection.value !is LibraryConnectionState.Saved, "The library no longer says saved")
        assertFalse(account.providerInstanceId in SavedAccountConnection.waitingForReconnect.value)
    }

    @Test fun aReconnectTheServerRefusesStatesTheRefusalNotSaved() {
        server.answer = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
        val session = open(account("provider:refused"), untilReconnectChosen = true)
        session.start()
        session.connectSavedAccount()
        settle("the refused reconnect, answered") { session.observation.value.reconnectAnswers >= 1 }
        val state = assertIs<LibraryConnectionState.Failed>(session.connection.value)
        assertEquals(DomainError.Auth.InvalidCredentials, state.error)
        val line = assertNotNull(context.resources.connectionLine(state), "The failure is stated above the screen")
        assertTrue(context.getString(R.string.library_error_credentials) in line, line)
        assertNull(context.resources.savedAccountLine(state, account("provider:refused")))
    }

    @Test fun aReconnectToAServerThatIsGoneSaysItCannotBeReached() {
        val gone = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { "http://127.0.0.1:${it.localPort}" }
        val account = SearchAccount("provider:gone", gone, "launch-user", "launch-canary-password", true)
        val session = open(account, untilReconnectChosen = true)
        session.start()
        session.connectSavedAccount()
        settle("the unreachable reconnect, answered") { session.observation.value.reconnectAnswers >= 1 }
        val state = assertIs<LibraryConnectionState.Offline>(session.connection.value)
        assertEquals(DomainError.Transport.Unreachable, state.error)
        assertEquals(context.getString(R.string.library_error_unreachable), context.resources.errorPhrase(state.error!!))
    }

    @Test fun aWaitingReaderInTheForegroundReadsNoEpochOnItsCadence() {
        val library = LoopbackLibraryServer()
        AndroidLibraryReader.testEpochIntervalMillis = 100
        try {
            val waiting = open(SearchAccount("provider:cadence", library.url, "u", "p", true), untilReconnectChosen = true)
            waiting.start()
            waiting.openAlbums()  // the cadence runs with a library screen open
            AndroidLibraryReader.currentFor("provider:cadence")!!.setForeground(true)
            settleFor(1_500)
            assertEquals(emptyList(), library.endpoints(), "Fifteen cadence periods in the foreground, and no epoch read")
            waiting.close()
            closeReader()

            // Control: the same cadence on a connected session reads the epoch again and again.
            val connected = SearchAccount("provider:cadence-connected", library.url, "u", "p", true)
            SavedAccountConnection.connectedOnTheForm(connected.providerInstanceId)
            val session = open(connected, untilReconnectChosen = true)
            session.start()
            session.openAlbums()
            settle("the connected session online") { session.connection.value is LibraryConnectionState.Online }
            val after = library.requests("getScanStatus").size
            settle("the cadence's own epoch reads") { library.requests("getScanStatus").size >= after + 3 }
        } finally {
            AndroidLibraryReader.testEpochIntervalMillis = null
            sessions.forEach(LibrarySession::close)
            closeReader()
            library.close()
        }
    }

    private fun account(id: String) = SearchAccount(id, server.url, "launch-user", "launch-canary-password", true)

    private fun open(account: SearchAccount, untilReconnectChosen: Boolean) =
        LibrarySession(context, account, foreground = true, untilReconnectChosen = untilReconnectChosen).also { sessions += it }

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
            val envelope = answer.toByteArray()
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${envelope.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                write(envelope)
                flush()
            }
        }

        override fun close() {
            socket.close()
            pool.shutdownNow()
        }
    }
}
