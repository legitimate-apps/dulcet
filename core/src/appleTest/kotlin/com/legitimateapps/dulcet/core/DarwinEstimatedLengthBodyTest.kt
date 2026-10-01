package com.legitimateapps.dulcet.core

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.IntVar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SHUT_RDWR
import platform.posix.SHUT_WR
import platform.posix.shutdown
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Navidrome's `estimateContentLength=true` Content-Length is a guess. When the transcoder produces
 * more than the guess, the server refuses the write that would overshoot it and closes the
 * connection, so the body ends short of its declaration (OBSERVED locally with curl: declared 24576,
 * 23385 delivered, server log "wrote more than the declared Content-Length"). NSURLSession reports
 * that as NSURLErrorNetworkConnectionLost after handing over a prefix of the body, and the Ktor
 * Darwin engine answered the error by cancelling the body channel, discarding every byte the reader
 * had not consumed yet. Each test reads only after the server has closed and the task has had time
 * to end, which makes that race's losing side deterministic: without the session delegate in
 * AccountHttpClient.apple.kt the estimated read fails with the -1005 and no bytes at all.
 */
class DarwinEstimatedLengthBodyTest {
    @Test
    fun anEstimatedBodyShorterThanItsDeclarationKeepsWhatTheSessionDeliveredAfterTheTaskEnded() = runBlocking {
        val outcome = readAfterTheServerCloses(query = ESTIMATED_QUERY)

        assertTrue(outcome.requestHead.startsWith("GET /rest/stream.view?"), "the fixture never served the request")
        val body = outcome.body.getOrThrow()
        // NSURLSession itself hands over only a prefix when the rest arrives with the end of the
        // stream -- the whole body, or its first 16 KiB read (both OBSERVED here). The prefix is
        // the representation; what must not happen is losing what it delivered.
        assertTrue(body.isNotEmpty(), "every delivered byte was discarded")
        assertContentEquals(BODY.copyOf(body.size), body, "the body is not a prefix of what was sent")
    }

    @Test
    fun anExactBodyShorterThanItsDeclarationStillFails() = runBlocking {
        val outcome = readAfterTheServerCloses(query = EXACT_QUERY)

        // The control reached the condition: the server served it the same short body.
        assertTrue(outcome.requestHead.startsWith("GET /rest/stream.view?"), "the fixture never served the request")
        assertFalse(outcome.requestHead.contains("estimateContentLength"), "the control asked for an estimate")
        // A failure, not merely a shorter body: the session hands over a prefix either way.
        assertTrue(outcome.body.isFailure, "a truncated body with an exact Content-Length was accepted as complete")
        assertTrue(
            isPlatformEstimatedLengthCompletion(outcome.body.exceptionOrNull()!!),
            "the control failed for a reason other than the connection-lost completion",
        )
    }

    @Test
    fun aRangedEstimatedBodyShorterThanItsDeclarationStillFails() = runBlocking {
        val outcome = readAfterTheServerCloses(query = ESTIMATED_QUERY, range = "bytes=0-")

        assertTrue(outcome.requestHead.contains("\r\nRange: bytes=0-", ignoreCase = true), "the Range header never arrived")
        // A failure, not merely a shorter body: the session hands over a prefix either way.
        assertTrue(outcome.body.isFailure, "a ranged request's truncated body was accepted as complete")
        assertTrue(
            isPlatformEstimatedLengthCompletion(outcome.body.exceptionOrNull()!!),
            "the control failed for a reason other than the connection-lost completion",
        )
    }

    @Test
    fun anEstimatedResponseThatEndsBeforeAnyBodyByteStillFails() = runBlocking {
        val outcome = readAfterTheServerCloses(query = ESTIMATED_QUERY, body = ByteArray(0))

        assertTrue(outcome.requestHead.startsWith("GET /rest/stream.view?"), "the fixture never served the request")
        // Nothing arrived, so there is no representation to end: an empty success would hide it.
        assertTrue(outcome.body.isFailure, "an estimated response with no body was accepted as complete")
        assertTrue(
            isPlatformEstimatedLengthCompletion(outcome.body.exceptionOrNull()!!),
            "the control failed for a reason other than the connection-lost completion",
        )
    }

    private class Outcome(val requestHead: String, val body: Result<ByteArray>)

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private suspend fun readAfterTheServerCloses(
        query: String,
        range: String? = null,
        body: ByteArray = BODY,
    ): Outcome {
        val server = OneShotLoopbackServer(RESPONSE_HEAD + body)
        val serverThread = newSingleThreadContext("short-body-fixture")
        val client = createAccountHttpClient(AccountClientTransport.Default()) {}
        try {
            val served = CoroutineScope(serverThread).async { server.serveOnce() }
            // A failure anywhere in the exchange is an outcome; running out of time is not one.
            val body = withTimeout(TIMEOUT_MILLIS) {
                runCatching {
                    client.prepareGet("http://127.0.0.1:${server.port}/rest/stream.view?$query") {
                        range?.let { header(HttpHeaders.Range, it) }
                    }.execute { response ->
                        served.await()
                        // Let NSURLSession report the short body before the first read.
                        delay(SETTLE_MILLIS)
                        response.bodyAsChannel().readRemaining().readByteArray()
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
            return Outcome(served.await(), body)
        } finally {
            client.close()
            server.closeListener()
            serverThread.close()
        }
    }

    private companion object {
        const val ESTIMATED_QUERY = "id=song&format=mp3&maxBitRate=96&estimateContentLength=true"
        const val EXACT_QUERY = "id=song&format=mp3&maxBitRate=96"
        const val DECLARED_LENGTH = 24_576
        const val SETTLE_MILLIS = 500L
        const val TIMEOUT_MILLIS = 10_000L
        val BODY = ByteArray(23_385) { (it % 251).toByte() }
        val RESPONSE_HEAD = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/mpeg\r\n" +
                "Content-Length: $DECLARED_LENGTH\r\n" +
                "Connection: close\r\n\r\n"
            ).encodeToByteArray()
    }
}

/** Accepts one connection, reads its request head, writes [response] and closes: nothing more. */
@OptIn(ExperimentalForeignApi::class)
private class OneShotLoopbackServer(private val response: ByteArray) {
    private val listener: Int = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket failed" } }
    val port: Int

    init {
        port = memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            address.sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed" }
            check(listen(listener, 1) == 0) { "listen failed" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname failed" }
            val networkOrder = address.sin_port.toInt()
            ((networkOrder and 0xff) shl 8) or ((networkOrder shr 8) and 0xff)
        }
    }

    fun serveOnce(): String {
        val connection = accept(listener, null, null)
        check(connection >= 0) { "accept failed" }
        try {
            memScoped {
                val on = alloc<IntVar>().apply { value = 1 }
                setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, on.ptr, sizeOf<IntVar>().convert())
            }
            val head = StringBuilder()
            val buffer = ByteArray(4096)
            while (!head.contains("\r\n\r\n")) {
                val count = buffer.usePinned { recv(connection, it.addressOf(0), buffer.size.convert(), 0) }
                if (count <= 0) break
                head.append(buffer.decodeToString(0, count.toInt()))
            }
            var sent = 0
            response.usePinned { pinned ->
                while (sent < response.size) {
                    val count = send(connection, pinned.addressOf(sent), (response.size - sent).convert(), 0)
                    check(count > 0) { "send failed" }
                    sent += count.toInt()
                }
            }
            // End the stream the way the reference server does -- a FIN after the last byte, then
            // wait for the peer -- rather than an abortive close that could discard bytes in flight.
            shutdown(connection, SHUT_WR)
            buffer.usePinned { while (recv(connection, it.addressOf(0), buffer.size.convert(), 0) > 0) Unit }
            return head.toString()
        } finally {
            close(connection)
        }
    }

    fun closeListener() {
        shutdown(listener, SHUT_RDWR)
        close(listener)
    }

    private companion object {
        // 127.0.0.1 with its bytes in network order, read as a little-endian UInt.
        const val LOOPBACK_NETWORK_ORDER = 0x0100007Fu
    }
}
