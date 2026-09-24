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

    /** Answers every request with one fixed status, headers and body, then closes the connection. */
    private class StatusServer(private val status: String, private val headers: List<String>, private val body: String) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requestLines = CopyOnWriteArrayList<String>()

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    return@thread
                }
                thread(isDaemon = true) { serve(client) }
            }
        }

        private fun serve(client: Socket) {
            client.use {
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
                    append("HTTP/1.1 $status\r\n")
                    headers.forEach { header -> append(header).append("\r\n") }
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                it.getOutputStream().apply {
                    write(response.toByteArray())
                    write(bytes)
                    flush()
                }
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
            acceptor.join(2_000)
        }
    }
}
