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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import platform.Foundation.NSLock
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 *
 * The fixture holds the end of the stream (its FIN) until the client's session delegate has
 * forwarded a body byte. NSURLSession discards body bytes it has read but not yet handed over when
 * the end arrives close behind them, and on a loaded host that can be every byte of this short body:
 * the completion then arrives with nothing received and nothing forwarded, and no delegate can keep
 * what it was never given. OBSERVED on macOS under 16 busy loops: sending the body and the FIN back
 * to back ended 65 of 200 and 49 of 100 reads with zero bytes, and holding the FIN 200 ms behind the
 * body ended 0 of 100 that way, against 36 of 100 for the same body sent with the FIN. That -- not the
 * delegate's byte count -- is what failed this class on CI's macOS host (spec §28, 2026-10-01). Each
 * test that holds asserts the hold was released by a delivery rather than its timeout, so the
 * condition the test is about is shown to have been reached.
 */
class DarwinEstimatedLengthBodyTest {
    @Test
    fun anEstimatedBodyShorterThanItsDeclarationKeepsWhatTheSessionDeliveredAfterTheTaskEnded() = runBlocking {
        val outcome = readAfterTheServerCloses(query = ESTIMATED_QUERY)

        assertTrue(outcome.requestHead.startsWith("GET /rest/stream.view?"), "the fixture never served the request")
        assertTrue(outcome.endFollowedDelivery, "the stream ended before the client had been handed a byte")
        val body = outcome.body.getOrThrow()
        // NSURLSession itself hands over only a prefix when the rest arrives with the end of the
        // stream -- the whole body, or its first 16 KiB read (both OBSERVED here). The prefix is
        // the representation; what must not happen is losing what it delivered.
        assertTrue(body.isNotEmpty(), "every delivered byte was discarded")
        assertContentEquals(BODY.copyOf(body.size), body, "the body is not a prefix of what was sent")
        // The delegate itself ended this body, and the reader kept every byte it forwarded.
        assertEquals(listOf(body.size.toLong()), outcome.estimatedBodyEnds, "the reader did not get every forwarded byte")
    }

    @Test
    fun anExactBodyShorterThanItsDeclarationStillFails() = runBlocking {
        val outcome = readAfterTheServerCloses(query = EXACT_QUERY)

        // The control reached the condition: the server served it the same short body.
        assertTrue(outcome.requestHead.startsWith("GET /rest/stream.view?"), "the fixture never served the request")
        assertFalse(outcome.requestHead.contains("estimateContentLength"), "the control asked for an estimate")
        assertTrue(outcome.endFollowedDelivery, "the stream ended before the client had been handed a byte")
        // A failure, not merely a shorter body: the session hands over a prefix either way.
        assertTrue(outcome.body.isFailure, "a truncated body with an exact Content-Length was accepted as complete")
        assertTrue(
            isPlatformEstimatedLengthCompletion(outcome.body.exceptionOrNull()!!),
            "the control failed for a reason other than the connection-lost completion",
        )
        assertEquals(emptyList(), outcome.estimatedBodyEnds, "the delegate ended a body it must not end")
    }

    @Test
    fun aRangedEstimatedBodyShorterThanItsDeclarationStillFails() = runBlocking {
        val outcome = readAfterTheServerCloses(query = ESTIMATED_QUERY, range = "bytes=0-")

        assertTrue(outcome.requestHead.contains("\r\nRange: bytes=0-", ignoreCase = true), "the Range header never arrived")
        assertTrue(outcome.endFollowedDelivery, "the stream ended before the client had been handed a byte")
        // A failure, not merely a shorter body: the session hands over a prefix either way.
        assertTrue(outcome.body.isFailure, "a ranged request's truncated body was accepted as complete")
        assertTrue(
            isPlatformEstimatedLengthCompletion(outcome.body.exceptionOrNull()!!),
            "the control failed for a reason other than the connection-lost completion",
        )
        assertEquals(emptyList(), outcome.estimatedBodyEnds, "the delegate ended a body it must not end")
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
        assertEquals(emptyList(), outcome.estimatedBodyEnds, "the delegate ended a body it must not end")
    }

    /**
     * NSURLSession does not report a short body as connection-lost for every status. OBSERVED on
     * macOS with this fixture: 200 and 206 end in -1005; 201, 202, 203, 299, 300, 302, 399, 400,
     * 401, 404, 410, 416, 429, 500, 503 and 599 complete without an error after handing over every
     * byte. So the delegate's 2xx clause cannot be reached over the wire here, and its control is
     * [theDecisionEndsOnlyA2xxEstimatedUnrangedBodyWithForwardedBytes]. This pins the platform fact
     * that makes that so for one representative status, 500 (the others above were observed once,
     * not pinned): if a short 500 body ever does end in -1005, this fails, and the wire control
     * becomes possible and should replace it.
     */
    @Test
    fun aShortNon2xxEstimatedBodyIsNotReportedAsConnectionLostAndIsNeverEndedByTheDelegate() = runBlocking {
        val outcome = readAfterTheServerCloses(query = ESTIMATED_QUERY, head = ERROR_RESPONSE_HEAD)

        assertTrue(outcome.requestHead.startsWith("GET /rest/stream.view?"), "the fixture never served the request")
        assertTrue(outcome.requestHead.contains("estimateContentLength=true"), "the control did not ask for an estimate")
        assertEquals(emptyList(), outcome.estimatedBodyEnds, "the delegate ended a non-2xx body")
        assertContentEquals(BODY, outcome.body.getOrThrow(), "the session now treats a short non-2xx body differently")
    }

    /** The delegate's decision, one clause at a time: each case differs from the end in one input. */
    @Test
    fun theDecisionEndsOnlyA2xxEstimatedUnrangedBodyWithForwardedBytes() {
        fun decide(status: Int = 200, ranged: Boolean = false, estimate: Boolean = true, forwarded: Long = 1) =
            isEstimatedBodyEnd(status, hasRangeHeader = ranged, asksForEstimate = estimate, bodyBytesForwarded = forwarded)

        assertTrue(decide(), "a 2xx estimated unranged body with a forwarded byte was not ended")
        assertTrue(decide(status = 299), "the top of 2xx was not ended")
        assertFalse(decide(status = 500), "a non-2xx body was ended")
        assertFalse(decide(status = 300), "a 3xx body was ended")
        assertFalse(decide(status = 199), "a 1xx status was ended")
        assertFalse(decide(ranged = true), "a ranged body was ended")
        assertFalse(decide(estimate = false), "an exact body was ended")
        // The session may have received bytes it never handed over; only forwarded bytes count.
        assertFalse(decide(forwarded = 0), "a body with nothing forwarded was ended")
    }

    /**
     * The production route since §28 2026-10-04: a capped legacy load through [PlaybackWireClient]
     * and the [AuthenticatedEndpointClient] behind it, reading as soon as the response arrives, as
     * playback does. The plan asks for no estimate, so the same short body -- what an undershooting
     * cold transcode sends -- is a lost connection, never the song, and the delegate ends nothing.
     */
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun aCappedLegacyLoadAsksForNoEstimateAndFailsOnABodyCutOffShortOfItsLength() = runBlocking {
        val delivery = DeliveryLatch()
        val server = OneShotLoopbackServer(RESPONSE_HEAD + MP3_BODY, holdEnd = delivery::await)
        val serverThread = newSingleThreadContext("short-body-fixture")
        val ends = EstimatedBodyEnds()
        val account = PlaybackEndpointAccount(
            providerInstanceId = PROVIDER_ID,
            normalizedBaseUrl = "http://127.0.0.1:${server.port}",
            username = "wire-user-canary",
            password = "wire-password-canary",
            allowLocalHttp = true,
        )
        val wire = PlaybackWireClient(
            account = account,
            transport = KtorPlaybackEndpointTransport(
                account = account,
                saltSource = null,
                logSink = null,
                hostResolver = systemHostResolver(),
                clientTransport = AccountClientTransport.Default(
                    estimatedBodyEndObserver = ends::record,
                    bodyForwardedObserver = delivery::record,
                ),
            ),
        )
        try {
            val served = CoroutineScope(serverThread).async { server.serveOnce() }
            val result = withTimeout(TIMEOUT_MILLIS) {
                val plan = assertIs<PlaybackResolutionResult.Resolved>(wire.resolve(cappedLegacyRequest())).plan
                wire.load(plan)
            }
            val (head, endFollowedDelivery) = served.await()

            assertTrue(head.startsWith("GET /rest/stream.view?"), "the fixture never served the request")
            assertTrue(head.contains("maxBitRate=96"), "setup: the request is not the capped transcode")
            assertTrue(endFollowedDelivery, "the stream ended before the client had been handed a byte")
            assertIs<PlaybackLoadResult.Failed>(result, "a body cut off short of its length loaded as the song")
            assertFalse(head.contains("estimateContentLength"), "the load asked for an estimate")
            assertEquals(emptyList(), ends.snapshot(), "the delegate ended a body the load never asked to estimate")
        } finally {
            wire.close()
            server.closeListener()
            serverThread.close()
        }
    }

    private fun cappedLegacyRequest() = PlaybackResolveRequest(
        playbackSessionId = PlaybackSessionId("session:estimated-body"),
        attemptId = AttemptId("attempt:estimated-body"),
        itemId = ProviderItemId(PROVIDER_ID, "song"),
        sourceContainer = AudioContainer.Flac,
        supportsTranscodingExtension = false,
        deviceProfile = PlaybackDeviceProfile(
            name = "Dulcet Test",
            platform = "Darwin",
            maxAudioBitrate = 96_000,
            maxTranscodingAudioBitrate = 96_000,
            directPlayProfiles = listOf(DirectPlayAudioProfile(listOf(AudioContainer.Flac), listOf("flac"), maxAudioChannels = 2)),
            transcodingProfiles = listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)),
        ),
        // An explicit hint, so the resolve asks for the transcode without reading the source first.
        legacyPreference = LegacyPlaybackPreference(format = AudioContainer.Mp3, maxBitRateKbps = 96),
    )

    private class Outcome(
        val requestHead: String,
        val endFollowedDelivery: Boolean,
        val body: Result<ByteArray>,
        val estimatedBodyEnds: List<Long>,
    )

    /** Opened by the first body byte the client's session delegate forwards; awaited by the server. */
    private class DeliveryLatch {
        private val delivered = CompletableDeferred<Unit>()

        fun record(@Suppress("UNUSED_PARAMETER") forwardedSoFar: Long) {
            delivered.complete(Unit)
        }

        /** True when a byte was forwarded within [HOLD_MILLIS]; false when the hold timed out. */
        fun await(): Boolean = runBlocking { withTimeoutOrNull(HOLD_MILLIS) { delivered.await() } != null }
    }

    /** What the session delegate reported through the transport's observer, from its own queue. */
    private class EstimatedBodyEnds {
        private val lock = NSLock()
        private val ends = mutableListOf<Long>()

        fun record(forwardedBytes: Long) {
            lock.lock()
            try {
                ends += forwardedBytes
            } finally {
                lock.unlock()
            }
        }

        fun snapshot(): List<Long> {
            lock.lock()
            try {
                return ends.toList()
            } finally {
                lock.unlock()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private suspend fun readAfterTheServerCloses(
        query: String,
        range: String? = null,
        body: ByteArray = BODY,
        head: ByteArray = RESPONSE_HEAD,
    ): Outcome {
        // With no body there is nothing to deliver, so nothing to hold the end behind.
        val delivery = DeliveryLatch()
        val server = OneShotLoopbackServer(head + body, holdEnd = delivery::await.takeIf { body.isNotEmpty() })
        val serverThread = newSingleThreadContext("short-body-fixture")
        val ends = EstimatedBodyEnds()
        val client = createAccountHttpClient(
            AccountClientTransport.Default(estimatedBodyEndObserver = ends::record, bodyForwardedObserver = delivery::record),
        ) {}
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
            val (requestHead, endFollowedDelivery) = served.await()
            return Outcome(requestHead, endFollowedDelivery, body, ends.snapshot())
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
        const val HOLD_MILLIS = 5_000L
        const val PROVIDER_ID = "provider:estimated-body"
        val BODY = ByteArray(23_385) { (it % 251).toByte() }

        // The same length, opening with an MPEG-1 Layer III frame header so the playback validator
        // accepts it as the MP3 the plan asked for.
        val MP3_BODY = BODY.copyOf().also { body ->
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x50, 0xC4.toByte()).copyInto(body)
        }
        val RESPONSE_HEAD = responseHead("200 OK")
        val ERROR_RESPONSE_HEAD = responseHead("500 Internal Server Error")

        fun responseHead(status: String) = (
            "HTTP/1.1 $status\r\n" +
                "Content-Type: audio/mpeg\r\n" +
                "Content-Length: $DECLARED_LENGTH\r\n" +
                "Connection: close\r\n\r\n"
            ).encodeToByteArray()
    }
}

/**
 * Accepts one connection, reads its request head, writes [response] and closes: nothing more. With
 * [holdEnd], the close waits for it after the last byte; what it returns is reported as
 * [Served.endFollowedDelivery].
 */
@OptIn(ExperimentalForeignApi::class)
private class OneShotLoopbackServer(
    private val response: ByteArray,
    private val holdEnd: (() -> Boolean)? = null,
) {
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

    data class Served(val requestHead: String, val endFollowedDelivery: Boolean)

    fun serveOnce(): Served {
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
            // A FIN arriving close behind bytes the session has not handed over yet discards them,
            // so a test about delivered bytes holds it until the client has been handed one.
            val endFollowedDelivery = holdEnd?.invoke() ?: false
            // End the stream the way the reference server does -- a FIN after the last byte, then
            // wait for the peer -- rather than an abortive close that could discard bytes in flight.
            shutdown(connection, SHUT_WR)
            buffer.usePinned { while (recv(connection, it.addressOf(0), buffer.size.convert(), 0) > 0) Unit }
            return Served(head.toString(), endFollowedDelivery)
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
