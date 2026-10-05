package com.legitimateapps.dulcet

import android.os.Looper
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.conformance.PlatformNetwork
import com.legitimateapps.dulcet.search.SearchAccount
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A reported loss of the default network is a hint, not a verdict (spec §16.14): the PRODUCTION
 * session tells the reader only once the loss has stood for [LibrarySession.UNREACHABLE_GRACE_MILLIS],
 * and a network back inside the grace leaves the reader — every screen and Play — online. The CI album
 * page that went "Unavailable offline" for 4.5 s while its server answered (conformance run
 * 37229956675) is the case. The platform's callbacks are driven through Robolectric's connectivity
 * shadow and the grace runs on the main looper's virtual clock; only the server is a loopback socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryReachabilityGraceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = OkServer()
    private val account = SearchAccount("provider:grace", server.url, "grace-user", "grace-canary-password", true)
    private lateinit var session: LibrarySession
    private lateinit var network: PlatformNetwork

    @Before fun open() {
        ShadowLog.clear()
        session = LibrarySession(context, account, foreground = true)
        network = PlatformNetwork(context)
        session.start()
        // The start's reconnect is answered before the network moves, so its outcome cannot be
        // mistaken for anything the network did.
        settle("the start's reconnect answered") { session.observation.value.reconnectAnswers >= 1 }
        assertTrue(session.observation.value.reachabilityReports.none { !it }, "control: the reader was never told unreachable")
    }

    @After fun close() {
        session.close()
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

    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "never: $what; ${session.observation.value.connections}" }
            Thread.sleep(5)
        }
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun reportsSince(mark: Int) = session.observation.value.reachabilityReports.drop(mark)

    private fun logLines() = ShadowLog.getLogsForTag(LibrarySession.REACHABILITY_LOG_TAG).map { it.msg }

    @Test fun aLossShorterThanTheGraceIsNeverToldToTheReader() {
        val mark = session.observation.value.reachabilityReports.size
        val connectionsMark = session.observation.value.connections.size

        network.drop()
        advance(LibrarySession.UNREACHABLE_GRACE_MILLIS - 100)
        assertEquals(emptyList(), reportsSince(mark).filter { !it }, "4.9 s into the blip the reader is not told")
        assertTrue(session.observation.value.connections.drop(connectionsMark).none { it is LibraryConnectionState.Offline },
            "nor does the library say offline: ${session.observation.value.connections}")

        network.restore()
        advance(60_000)
        assertEquals(emptyList(), reportsSince(mark).filter { !it }, "a withdrawn loss reaches the reader as nothing at all")
        assertTrue(session.observation.value.connections.drop(connectionsMark).none { it is LibraryConnectionState.Offline },
            "the library never said offline: ${session.observation.value.connections}")
        val lines = logLines()
        assertTrue(lines.any { it == "default network lost; held for 5000 ms before the reader is told" }, "$lines")
        assertTrue(lines.any { it.startsWith("network available again after 49") && it.endsWith("the reader stayed online") },
            "the log measures the blip by the monotonic clock: $lines")
    }

    @Test fun aLossThatOutlastsTheGraceTakesTheLibraryOfflineAndANetworkBringsItBack() {
        val mark = session.observation.value.reachabilityReports.size

        network.drop()
        advance(LibrarySession.UNREACHABLE_GRACE_MILLIS - 1)
        assertEquals(emptyList(), reportsSince(mark), "nothing is told during the grace")
        advance(1)
        assertEquals(listOf(false), reportsSince(mark), "a real loss is told when the grace ends")
        assertIs<LibraryConnectionState.Offline>(session.observation.value.connections.last())
        assertTrue(logLines().any { it == "no network for 5000 ms; the reader is told and goes offline" }, "${logLines()}")

        network.restore()
        assertEquals(listOf(false, true), reportsSince(mark), "a network back is told at once")
        settle("the reconnect the network starts") {
            session.observation.value.connections.last() !is LibraryConnectionState.Offline &&
                session.observation.value.connections.last() !is LibraryConnectionState.Connecting
        }
        for (line in logLines()) {
            assertTrue("grace-user" !in line && "grace-canary-password" !in line && "127.0.0.1" !in line && "http" !in line,
                "a reachability line names no server or credential: $line")
        }
    }

    /** Answers every request with an `ok` envelope. */
    private class OkServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        private val connections = Executors.newCachedThreadPool()

        init {
            connections.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    connections.submit { answer(client) }
                }
            }
        }

        private fun answer(client: java.net.Socket) = client.use {
            client.soTimeout = 5_000
            val input = client.getInputStream().bufferedReader()
            input.readLine() ?: return@use
            generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val envelope = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${envelope.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                write(envelope)
                flush()
            }
        }

        override fun close() {
            socket.close()
            connections.shutdownNow()
        }
    }
}
