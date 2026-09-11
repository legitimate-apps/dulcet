package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The deadline, driven through the real HTTP stack against a real socket.
 *
 * The unit tests in `LibraryBrowseTest` drive a fake transport on virtual time, so they cannot
 * tell a bounded walk from an unbounded one on a live connection. These bind a loopback socket
 * that accepts and then answers nothing, which is the shape of the stall this deadline exists to
 * report.
 */
class LibraryBrowseDeadlineTest {
    @Test
    fun aServerThatAcceptsAndNeverAnswersFailsWithATypedTimeoutInsideTheBudget() {
        SilentServer().use { server ->
            val budget = 3.seconds
            val started = TimeSource.Monotonic.markNow()

            val result = runBlocking(Dispatchers.Default) {
                LibraryBrowser(firstPaintBudget = budget).browse(server.request())
            }
            val elapsed = started.elapsedNow()

            assertEquals(
                DomainError.Transport.Timeout,
                assertIs<LibraryBrowseResult.Failed>(result).error,
            )
            // The socket really was reached, so this is a stalled exchange and not a refusal.
            assertTrue(server.acceptedConnections >= 1, "the fixture server was never reached")
            // It really waited, and it stopped on THIS deadline rather than on the transport's
            // own 30-second per-request budget.
            assertTrue(elapsed >= budget, "returned before the budget elapsed: $elapsed")
            assertTrue(
                elapsed < HALF_THE_TRANSPORT_BUDGET,
                "waited past the walk deadline, so something else ended it: $elapsed",
            )
        }
    }

    @Test
    fun aServerThatAcceptsAndNeverAnswersAlsoBoundsOneAlbumsTrackList() {
        SilentServer().use { server ->
            val budget = 3.seconds
            val started = TimeSource.Monotonic.markNow()

            val result = runBlocking(Dispatchers.Default) {
                LibraryBrowser(firstPaintBudget = budget)
                    .albumTracks(server.request(), "album:hung")
            }
            val elapsed = started.elapsedNow()

            assertEquals(
                DomainError.Transport.Timeout,
                assertIs<LibraryAlbumTracksResult.Failed>(result).error,
            )
            assertTrue(server.acceptedConnections >= 1, "the fixture server was never reached")
            assertTrue(elapsed >= budget, "returned before the budget elapsed: $elapsed")
            assertTrue(elapsed < HALF_THE_TRANSPORT_BUDGET, "waited past the deadline: $elapsed")
        }
    }

    /**
     * The negative control for the two tests above. The same budget, the same loopback route and
     * the same client reach a server that does answer, and produce a library instead of a
     * timeout — so `Transport.Timeout` above is caused by the silence and not by the harness.
     */
    @Test
    fun theSameBudgetAndRouteSucceedAgainstAServerThatAnswers() {
        AnsweringServer().use { server ->
            val result = runBlocking(Dispatchers.Default) {
                LibraryBrowser(firstPaintBudget = 3.seconds).browse(server.request())
            }

            val loaded = assertIs<LibraryBrowseResult.Loaded>(result).snapshot
            assertEquals(listOf("album:answered"), loaded.albums.map { it.id.rawId })
            assertEquals(4, loaded.albums.single().trackCount)
            assertTrue(server.observedEndpoints.none { it.startsWith("/rest/getAlbum.view") })
        }
    }

    private abstract class LoopbackServer : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val clients = CopyOnWriteArrayList<Socket>()

        @Volatile
        var acceptedConnections: Int = 0
            private set

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    return@thread
                }
                clients += client
                acceptedConnections += 1
                thread(isDaemon = true) { serve(client) }
            }
        }

        protected abstract fun serve(client: Socket)

        fun request() = LibraryBrowseRequest(
            providerInstanceId = "provider-instance-deadline-fixture",
            normalizedBaseUrl = "http://127.0.0.1:${socket.localPort}",
            username = "listener",
            password = "fixture-password",
            allowLocalHttp = true,
        )

        override fun close() {
            socket.close()
            clients.forEach { runCatching { it.close() } }
            acceptor.join(2_000)
        }
    }

    /** Completes the TCP handshake, reads the request, and then answers nothing at all. */
    private class SilentServer : LoopbackServer() {
        override fun serve(client: Socket) {
            runCatching {
                val input = client.getInputStream()
                val buffer = ByteArray(4096)
                while (input.read(buffer) > 0) {
                    // Deliberately no response: this is the stall under test.
                }
            }
        }
    }

    private class AnsweringServer : LoopbackServer() {
        val observedEndpoints = CopyOnWriteArrayList<String>()

        override fun serve(client: Socket) {
            runCatching {
                val input = client.getInputStream().bufferedReader()
                val output = client.getOutputStream()
                while (true) {
                    val requestLine = input.readLine() ?: return
                    if (requestLine.isBlank()) continue
                    while (true) {
                        val header = input.readLine() ?: return
                        if (header.isEmpty()) break
                    }
                    val target = requestLine.split(' ').getOrElse(1) { "" }
                    observedEndpoints += target
                    val body = bodyFor(target).toByteArray()
                    output.write(
                        buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ${body.size}\r\n")
                            append("Connection: keep-alive\r\n\r\n")
                        }.toByteArray(),
                    )
                    output.write(body)
                    output.flush()
                }
            }
        }

        private fun bodyFor(target: String): String = when {
            target.startsWith("/rest/getMusicFolders.view") ->
                envelope("\"musicFolders\":{\"musicFolder\":[{\"id\":\"1\",\"name\":\"Primary\"}]}")
            target.startsWith("/rest/getArtists.view") -> envelope(
                "\"artists\":{\"index\":[{\"name\":\"A\",\"artist\":[{" +
                    "\"id\":\"artist:answered\",\"name\":\"Answering Artist\"}]}]}",
            )
            target.startsWith("/rest/getAlbumList2.view") -> {
                val offset = Regex("offset=([0-9]+)").find(target)?.groupValues?.get(1)
                if (offset == "0") {
                    envelope(
                        "\"albumList2\":{\"album\":[{\"id\":\"album:answered\"," +
                            "\"name\":\"Answered\",\"artist\":\"Answering Artist\"," +
                            "\"coverArt\":\"artwork:answered\",\"duration\":121," +
                            "\"songCount\":4}]}",
                    )
                } else {
                    envelope("\"albumList2\":{\"album\":[]}")
                }
            }
            else -> envelope("\"error\":{\"code\":70,\"message\":\"unexpected\"}")
        }

        private fun envelope(payload: String) =
            "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",$payload}}"
    }

    private companion object {
        /**
         * `AuthenticatedEndpointClient.REQUEST_TIMEOUT_MILLIS` is 30 s. A run that ends before
         * half of that cannot have been ended by the per-request budget.
         */
        val HALF_THE_TRANSPORT_BUDGET: Duration = 15.seconds
    }
}
