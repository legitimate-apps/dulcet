package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The lyrics response limit (§18.4) through the real HTTP stack against a real socket: the limit
 * must bound what is DOWNLOADED, which a fake transport cannot show. The server streams a body far
 * larger than the limit and counts the bytes it managed to write before the client went away.
 */
class LyricsResponseLimitTest {
    @Test
    fun anUndeclaredBodyBeyondTheLimitStopsTheDownload() {
        StreamingServer(status = 200, bodyBytes = HUGE, declareLength = false).use { server ->
            val failure = refusal(server)
            assertEquals(DomainError.Protocol.TooLarge, failure.error)
            assertTrue(server.requests.get() >= 1, "the fixture server was never reached")
            val written = server.settledBytesWritten()
            println("LYRICS_LIMIT_EVIDENCE written=$written of=$HUGE limit=$MAX_LYRICS_RESPONSE_BYTES")
            assertTrue(written < HUGE / 4, "the client read on: $written of $HUGE bytes went out")
        }
    }

    @Test
    fun aDeclaredLengthBeyondTheLimitIsRefusedBeforeTheBodyIsRead() {
        StreamingServer(status = 200, bodyBytes = HUGE, declareLength = true).use { server ->
            assertEquals(DomainError.Protocol.TooLarge, refusal(server).error)
            val written = server.settledBytesWritten()
            println("LYRICS_LIMIT_EVIDENCE written=$written of=$HUGE limit=$MAX_LYRICS_RESPONSE_BYTES")
            assertTrue(written < HUGE / 4, "the client read on: $written of $HUGE bytes went out")
        }
    }

    /**
     * The declared length alone decides: this server announces a body beyond the limit and then
     * sends none of it. A client that reads before judging would wait out the transport's 30-second
     * budget and fail as a timeout; one that checks the declared length refuses at once.
     */
    @Test
    fun aDeclaredLengthBeyondTheLimitIsRefusedWithoutWaitingForTheBody() {
        StreamingServer(status = 200, bodyBytes = HUGE, declareLength = true, stallBody = true).use { server ->
            val started = System.nanoTime()
            val failure = refusal(server)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(DomainError.Protocol.TooLarge, failure.error)
            assertTrue(elapsedMillis < 10_000, "waited ${elapsedMillis}ms for a body it should never have read")
            assertEquals(0L, server.settledBytesWritten(), "the stalled server sent body bytes")
        }
    }

    @Test
    fun aFailureStatusBeyondTheLimitIsNotATooLargeAnswer() {
        StreamingServer(status = 502, bodyBytes = HUGE, declareLength = false).use { server ->
            // Classified by its status, as a body within the limit is, and counted against the
            // endpoint like any failure (§10.4).
            assertEquals(DomainError.Server.HttpStatus(502), refusal(server).error)
        }
    }

    /**
     * The status is read before the size: a 429 whose body is beyond the limit is still the server
     * asking for quiet, with its `Retry-After`, and a 401 still refused credentials — whether the
     * length is declared (refused before the body) or not (refused at the limit).
     */
    @Test
    fun aStatusWithMeaningIsKeptWhenItsBodyIsBeyondTheLimit() {
        for (declared in listOf(true, false)) {
            StreamingServer(status = 429, bodyBytes = HUGE, declareLength = declared, retryAfter = "3600").use { server ->
                assertEquals(DomainError.Server.Busy(3_600.seconds), refusal(server).error, "429, declared length: $declared")
            }
            StreamingServer(status = 401, bodyBytes = HUGE, declareLength = declared).use { server ->
                assertEquals(DomainError.Auth.InvalidCredentials, refusal(server).error, "401, declared length: $declared")
            }
        }
    }

    /**
     * The positive control: the same server, route and client read a body under the limit to the
     * end, so the refusals above are the limit's and not the harness's.
     */
    @Test
    fun aBodyWithinTheLimitIsReadToTheEnd() {
        for (declared in listOf(false, true)) {
            StreamingServer(status = 200, bodyBytes = MAX_LYRICS_RESPONSE_BYTES.toLong(), declareLength = declared).use { server ->
                val response = runBlocking(Dispatchers.Default) {
                    server.transport().use { it.request("getLyricsBySongId", mapOf("id" to "t"), MAX_LYRICS_RESPONSE_BYTES) }
                }
                assertEquals(200, response.statusCode)
                assertEquals(MAX_LYRICS_RESPONSE_BYTES, response.body.length, "declared length: $declared")
                assertEquals(MAX_LYRICS_RESPONSE_BYTES.toLong(), server.settledBytesWritten())
            }
        }
    }

    private fun refusal(server: StreamingServer): AuthenticatedEndpointFailure = runBlocking(Dispatchers.Default) {
        server.transport().use { transport ->
            assertIs<AuthenticatedEndpointFailure>(
                runCatching { transport.request("getLyricsBySongId", mapOf("id" to "t"), MAX_LYRICS_RESPONSE_BYTES) }
                    .exceptionOrNull(),
            )
        }
    }

    private inline fun <R> KtorLibraryEndpointTransport.use(block: (KtorLibraryEndpointTransport) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }

    /** Answers every request with [bodyBytes] of `a`, in 64 KiB writes, counting what it wrote. */
    private class StreamingServer(
        private val status: Int,
        private val bodyBytes: Long,
        private val declareLength: Boolean,
        /** Send the headers and then no body at all, holding the connection open. */
        private val stallBody: Boolean = false,
        /** A raw `Retry-After` header to send, if any. */
        private val retryAfter: String? = null,
    ) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val clients = CopyOnWriteArrayList<Socket>()
        private val written = AtomicLong()
        val requests = AtomicLong()

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    return@thread
                }
                clients += client
                thread(isDaemon = true) { serve(client) }
            }
        }

        private fun serve(client: Socket) {
            runCatching {
                val input = client.getInputStream().bufferedReader()
                input.readLine() ?: return
                while (true) {
                    val header = input.readLine() ?: return
                    if (header.isEmpty()) break
                }
                requests.incrementAndGet()
                val output = client.getOutputStream()
                output.write(
                    buildString {
                        append("HTTP/1.1 $status Fixture\r\n")
                        append("Content-Type: application/json\r\n")
                        if (declareLength) append("Content-Length: $bodyBytes\r\n")
                        if (retryAfter != null) append("Retry-After: $retryAfter\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(),
                )
                output.flush()
                if (stallBody) {
                    // Hold the connection until the client goes away (read returns -1 or throws).
                    runCatching { client.getInputStream().read() }
                    return@runCatching
                }
                val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                var remaining = bodyBytes
                while (remaining > 0) {
                    val count = minOf(remaining, chunk.size.toLong()).toInt()
                    output.write(chunk, 0, count)
                    written.addAndGet(count.toLong())
                    remaining -= count
                }
                output.flush()
                client.close()
            }
        }

        /**
         * The bytes written once writing has stopped — the body is done, or the client went away
         * and the socket buffers filled — sampled until two readings a quarter-second apart agree.
         */
        fun settledBytesWritten(): Long {
            var previous = -1L
            repeat(40) {
                val now = written.get()
                if (now == previous) return now
                previous = now
                Thread.sleep(250)
            }
            return written.get()
        }

        fun transport() = KtorLibraryEndpointTransport(
            LibraryBrowseRequest(
                providerInstanceId = "provider-instance-limit-fixture",
                normalizedBaseUrl = "http://127.0.0.1:${socket.localPort}",
                username = "listener",
                password = "fixture-password",
                allowLocalHttp = true,
            ),
            saltSource = null,
            logSink = null,
            hostResolver = systemHostResolver(),
            operationName = "library.lyrics",
        )

        override fun close() {
            socket.close()
            clients.forEach { runCatching { it.close() } }
            acceptor.join(2_000)
        }
    }

    private companion object {
        /** 64 MiB: far beyond the limit plus any socket buffering on the loopback route. */
        const val HUGE = 64L * 1024 * 1024
    }
}
