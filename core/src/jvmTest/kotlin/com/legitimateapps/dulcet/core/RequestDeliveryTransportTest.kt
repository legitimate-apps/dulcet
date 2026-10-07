package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import java.io.Closeable
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whether a request that got no answer may have reached the server, through the real HTTP stack
 * against real sockets (spec §18.3, §18.6). Both cases below are unreachable to the person; only
 * a connection never made proves the request never arrived, so only then may an outbox take a write
 * back and send it again as if it had never gone. Android's HTTP client is the same CIO engine and
 * its classification the same code (`RequestDelivery.android.kt`).
 */
class RequestDeliveryTransportTest {
    @Test
    fun aConnectionClosedWithoutAnAnswerMayHaveCarriedTheRequest() = SilentServer().use { server ->
        val failures = transport(server.port) { transport ->
            listOf(
                runCatching { transport.request("star", mapOf("id" to "song-1")) }.exceptionOrNull(),
                runCatching { transport.requestRepeated("createPlaylist", listOf("name" to "Road", "songId" to "song-1"), formPost = true) }.exceptionOrNull(),
            )
        }
        // The control: the server read both requests whole before it closed.
        assertEquals(listOf("GET /rest/star", "POST /rest/createPlaylist"), server.requestLines.map { it.substringBefore('?').substringBefore(".view").substringBefore(" HTTP") })
        failures.forEach { failure ->
            assertNotNull(failure, "a request with no answer succeeded")
            assertEquals(DomainError.Transport.Unreachable, failure.asReaderError(), "$failure")
            assertFalse(provesNeverConnected(failure), "a connection made and then closed was read as never made: $failure")
        }
    }

    @Test
    fun aRefusedConnectionProvesTheRequestNeverLeft() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } // nothing listens there now
        val failure = transport(port) { transport -> runCatching { transport.request("star", mapOf("id" to "song-1")) }.exceptionOrNull() }
        assertNotNull(failure, "a request to a closed port succeeded")
        assertEquals(DomainError.Transport.Unreachable, failure.asReaderError(), "$failure")
        assertTrue(provesNeverConnected(failure), "a refused connection was not read as never made: $failure")
    }

    /** Through the editor and the reader: what the platform proves decides whether a create is in doubt. */
    @Test
    fun aCreateRefusedAConnectionIsNotInDoubtAndOneDroppedIs() {
        playlistTest { env ->
            val session = env.session()
            env.server.failWithThrowable["createPlaylist"] = ConnectException("Connection refused")
            session.playlists.create("Road", listOf("song-1"))
            advanceUntilIdle()
            assertFalse(session.playlists.pendingChanges().single().inDoubt, "a create that never connected was left in doubt")
        }
        playlistTest { env ->
            val session = env.session()
            env.server.failWithThrowable["createPlaylist"] = java.io.IOException("Connection reset")
            session.playlists.create("Road", listOf("song-1"))
            advanceUntilIdle()
            assertTrue(session.playlists.pendingChanges().single().inDoubt, "a create whose connection dropped was taken back as unsent")
        }
    }

    private fun <T> transport(port: Int, block: suspend (KtorLibraryEndpointTransport) -> T): T {
        val transport = KtorLibraryEndpointTransport(
            LibraryBrowseRequest(
                providerInstanceId = "provider-instance-delivery-fixture",
                normalizedBaseUrl = "http://127.0.0.1:$port",
                username = "listener",
                password = "fixture-password",
                allowLocalHttp = true,
            ),
            null, null, systemHostResolver(),
        )
        return try {
            runBlocking(Dispatchers.Default) { block(transport) }
        } finally {
            transport.close()
        }
    }

    /** Reads each request whole, then closes the connection without a byte of answer. */
    private class SilentServer : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requestLines = CopyOnWriteArrayList<String>()

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    return@thread
                }
                client.use {
                    val input = it.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) break
                        head.append(next.toChar())
                    }
                    val lines = head.split("\r\n")
                    val length = lines.firstOrNull { line -> line.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    repeat(length) { input.read() }
                    requestLines += lines.first()
                }
            }
        }

        override fun close() {
            socket.close()
            acceptor.join(2_000)
        }
    }
}
