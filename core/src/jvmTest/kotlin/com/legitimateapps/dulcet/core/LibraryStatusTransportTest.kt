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
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The library reader's HTTP statuses through the real HTTP stack against a real socket (spec §18.6,
 * "Failures"). The editor's tests drive fake transports, which hand the reader a response already
 * built; these prove the production transport carries the status and `Retry-After` that the
 * classification reads, for a plain request and for a repeated-parameter one alike.
 */
class LibraryStatusTransportTest {
    @Test
    fun binaryRequestPreservesBytesWithMislabeledContentType() = answering("200 OK", listOf("Content-Type: application/json"), "GIF89a\u0000\u007f") { transport ->
        val response = transport.requestBinary("getDulcetDiscoverCoverArt", mapOf("id" to "opaque:%/"), 5_242_880)
        kotlin.test.assertContentEquals("GIF89a\u0000\u007f".encodeToByteArray(), response.body)
        assertEquals(200, response.statusCode)
        assertEquals("getDulcetDiscoverCoverArt", response.requestTrace.endpoint)
        kotlin.test.assertFalse(response.redactedUrl.contains("u="))
    }

    private fun classified(response: LibraryEndpointResponse): DomainError =
        assertFailsWith<LibraryRequestFailure> {
            runBlocking { LibraryEndpointTransport { _, _ -> response }.checkedRequest("updatePlaylist") }
        }.error

    private fun answering(status: String, headers: List<String>, body: String, block: suspend (KtorLibraryEndpointTransport) -> Unit) =
        StatusServer(status, headers, body).use { server ->
            val transport = KtorLibraryEndpointTransport(server.request(), null, null, systemHostResolver())
            try {
                runBlocking(Dispatchers.Default) { block(transport) }
            } finally {
                transport.close()
            }
            assertEquals(true, server.requestLines.isNotEmpty(), "the fixture server was never reached")
        }

    @Test
    fun a429sRetryAfterReachesTheClassificationOnBothRequestShapes() = answering("429 Too Many Requests", listOf("Retry-After: 7"), "") { transport ->
        val plain = transport.request("star", mapOf("id" to "album-1"))
        assertEquals(429, plain.statusCode)
        assertEquals("7", plain.retryAfter)
        assertEquals(DomainError.Server.Busy(7.seconds), classified(plain))
        val repeated = transport.requestRepeated("updatePlaylist", listOf("playlistId" to "pl-1", "songIdToAdd" to "a", "songIdToAdd" to "b"), formPost = false)
        assertEquals("7", repeated.retryAfter)
        assertEquals(DomainError.Server.Busy(7.seconds), classified(repeated))
        val form = transport.requestRepeated("updatePlaylist", listOf("playlistId" to "pl-1", "songIdToAdd" to "a"), formPost = true)
        assertEquals("7", form.retryAfter)
        assertEquals(DomainError.Server.Busy(7.seconds), classified(form))
    }

    @Test
    fun a429IsBusyFromTheStatusEvenWithAnEnvelope() = answering(
        "429 Too Many Requests",
        listOf("Retry-After: 5", "Content-Type: application/json"),
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"busy"}}}""",
    ) { transport ->
        assertEquals(DomainError.Server.Busy(5.seconds), classified(transport.request("star", mapOf("id" to "album-1"))))
    }

    @Test
    fun anAbsurdRetryAfterIsReadAsTheCeilingNotAFailureOfTheDevice() = answering("429 Too Many Requests", listOf("Retry-After: 9223372036854775807"), "") { transport ->
        assertEquals(DomainError.Server.Busy(1.days), classified(transport.request("star", mapOf("id" to "album-1"))))
    }

    @Test
    fun aBare401IsRefusedCredentials() = answering("401 Unauthorized", emptyList(), "<html>sign in</html>") { transport ->
        assertEquals(DomainError.Auth.InvalidCredentials, classified(transport.request("star", mapOf("id" to "album-1"))))
    }

    @Test
    fun aBare403And407StayStatuses() {
        answering("403 Forbidden", emptyList(), "<html>blocked</html>") { transport ->
            assertEquals(DomainError.Server.HttpStatus(403), classified(transport.request("star", mapOf("id" to "album-1"))))
        }
        answering("407 Proxy Authentication Required", emptyList(), "<html>proxy</html>") { transport ->
            assertEquals(DomainError.Server.HttpStatus(407), classified(transport.request("star", mapOf("id" to "album-1"))))
        }
    }

    @Test
    fun explicit503And407AndActual599PreserveBodyAndHeadersWithoutEngineReplay() {
        for (code in listOf(503, 407, 599)) {
            StatusServer("$code Fixture", listOf("Retry-After: 0", "X-Total-Count: 17"), "fixture-status-$code").use { server ->
                val transport = KtorLibraryEndpointTransport(server.request(), null, null, systemHostResolver())
                try {
                    runBlocking {
                        val responses = listOf(
                            transport.request("star", mapOf("id" to "album-1")),
                            transport.requestRepeated("updatePlaylist", listOf("songIdToAdd" to "a", "songIdToAdd" to "b"), false),
                            transport.requestRepeated("updatePlaylist", listOf("songIdToAdd" to "a", "songIdToAdd" to "b"), true),
                        )
                        responses.forEach { response ->
                            assertEquals(code, response.statusCode)
                            assertEquals("fixture-status-$code", response.body)
                            assertEquals("0", response.retryAfter)
                        }
                        val binary = transport.requestBinary("getCoverArt", mapOf("id" to "fixture"), 1024)
                        assertEquals(code, binary.statusCode)
                        assertEquals("17", binary.headers.totalCount)
                        assertEquals("0", binary.headers.retryAfter)
                        kotlin.test.assertContentEquals("fixture-status-$code".encodeToByteArray(), binary.body)
                    }
                } finally { transport.close() }
                assertEquals(4, server.requestLines.size, "the engine replayed status $code")
            }
        }
    }

    @Test
    fun http10WithoutKeepAliveDoesNotReuseTheClosedSocketOrReplayRequests() {
        StatusServer("200 OK", emptyList(), "fixture-body", responseProtocol = "HTTP/1.0", connectionHeader = null).use { server ->
            val transport = KtorLibraryEndpointTransport(server.request(), null, null, systemHostResolver())
            try {
                runBlocking {
                    assertEquals("fixture-body", transport.request("ping", emptyMap()).body)
                    assertEquals("fixture-body", transport.request("star", mapOf("id" to "fixture")).body)
                    assertEquals("fixture-body", transport.requestRepeated("updatePlaylist", listOf("songIdToAdd" to "a"), false).body)
                    assertEquals("fixture-body", transport.requestRepeated("updatePlaylist", listOf("songIdToAdd" to "a"), true).body)
                    kotlin.test.assertContentEquals("fixture-body".encodeToByteArray(), transport.requestBinary("getCoverArt", emptyMap(), 1024).body)
                }
            } finally { transport.close() }
            assertEquals(5, server.requestLines.size, "each application request must appear once on a fresh HTTP/1.0 connection")
            assertEquals(5, server.connectionCount.get())
        }
    }

    @Test
    fun http11AndExplicitHttp10KeepAliveStillReuseOneConnection() {
        for ((protocol, connectionHeader) in listOf("HTTP/1.1" to null, "HTTP/1.0" to "keep-alive")) {
            StatusServer("200 OK", emptyList(), "fixture-body", protocol, connectionHeader, persistent = true).use { server ->
                val transport = KtorLibraryEndpointTransport(server.request(), null, null, systemHostResolver())
                try {
                    runBlocking { repeat(3) { assertEquals("fixture-body", transport.request("ping", emptyMap()).body) } }
                } finally { transport.close() }
                assertEquals(3, server.requestLines.size)
                assertEquals(1, server.connectionCount.get(), "persistent $protocol responses should retain pooling")
            }
        }
    }

    /** Fixed status/body fixture, optionally retaining the socket for persistence controls. */
    private class StatusServer(private val status: String, private val headers: List<String>, private val body: String,
        private val responseProtocol: String = "HTTP/1.1", private val connectionHeader: String? = "close",
        private val persistent: Boolean = false) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requestLines = CopyOnWriteArrayList<String>()
        val connectionCount = java.util.concurrent.atomic.AtomicInteger()
        private val clients = CopyOnWriteArrayList<Socket>()

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    return@thread
                }
                clients += client
                connectionCount.incrementAndGet()
                thread(isDaemon = true) { try { serve(client) } catch (_: java.io.IOException) { } }
            }
        }

        private fun serve(client: Socket) {
            client.use {
                do {
                    val input = it.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) return
                        head.append(next.toChar())
                    }
                    val lines = head.split("\r\n")
                    requestLines += lines.first()
                    val length = lines.firstOrNull { line -> line.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    repeat(length) { input.read() }
                    val bytes = body.toByteArray()
                    val response = buildString {
                        append("$responseProtocol $status\r\n")
                        headers.forEach { header -> append(header).append("\r\n") }
                        append("Content-Length: ${bytes.size}\r\n")
                        connectionHeader?.let { append("Connection: $it\r\n") }
                        append("\r\n")
                    }
                    it.getOutputStream().apply {
                        write(response.toByteArray())
                        write(bytes)
                        flush()
                    }
                } while (persistent)
            }
        }

        fun request() = LibraryBrowseRequest(
            providerInstanceId = "provider-instance-status-fixture",
            normalizedBaseUrl = "http://127.0.0.1:${socket.localPort}",
            username = "listener",
            password = "fixture-password",
            allowLocalHttp = true,
        )

        override fun close() {
            socket.close()
            clients.forEach { try { it.close() } catch (_: java.io.IOException) { } }
            acceptor.join(2_000)
        }
    }
}
