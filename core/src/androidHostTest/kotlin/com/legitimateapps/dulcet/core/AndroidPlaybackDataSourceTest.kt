package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPlaybackDataSourceTest {
    @Test fun fragmentedSocketReadsAreAccumulatedBeforeSignatureClassification() {
        val bytes = wav() + ByteArray(32)
        var reads = 0
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(buffer, offset, minOf(length, 1))
            }
        }
        val source = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ -> response(bytes).let {
            AndroidPlaybackResponse(it.status, it.headers, fragmented, {})
        } }).createDataSource()
        assertEquals(bytes.size.toLong(), source.open(spec()))
        assertTrue(reads > 12, "The fixture must split the WAV signature across reads")
        val delivered = ByteArray(bytes.size)
        assertEquals(bytes.size, source.read(delivered, 0, delivered.size))
        assertContentEquals(bytes, delivered)
        source.close()
    }

    @Test fun validatesTheActualResponseAndDeliversThoseSameBytesWithoutAPreflight() {
        val bytes = wav() + ByteArray(40_000) { (it % 255).toByte() }
        var requests = 0
        var closes = 0
        var consumed = 0L
        val factory = AndroidPlaybackDataSourceFactory(playbackPlan(), { position, _ ->
            requests++
            assertEquals(0L, position)
            response(bytes) { closes++ }
        }, { consumed += it })
        val source = factory.createDataSource()
        assertEquals(bytes.size.toLong(), source.open(spec()))
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(1000)
        while (true) {
            val read = source.read(chunk, 0, chunk.size)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(chunk, 0, read)
        }
        source.close()
        assertContentEquals(bytes, output.toByteArray())
        assertEquals(1, requests)
        assertEquals(1, closes)
        assertEquals(bytes.size.toLong(), consumed)
        println("ANDROID DATASOURCE OBSERVED requests=1 validated-delivery-bytes=$consumed identical-response=true preflight=false decoder-executed=false")
    }

    @Test fun envelopesMalformedAudioAndCredentialBearingErrorsFailBeforeDelivery() {
        val bodies = listOf(
            "\uFEFF  {\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"TOKEN_CANARY\"}}}".toByteArray(),
            "  <subsonic-response status=\"failed\"><error code=\"40\" message=\"PASSWORD_CANARY\"/></subsonic-response>".toByteArray(),
            "RIFFxxxxNOTWAVE".toByteArray(),
            "<html>USER_CANARY</html>".toByteArray(),
        )
        for (bytes in bodies) {
            var consumed = 0L
            val source = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ -> response(bytes) }, { consumed += it }).createDataSource()
            val error = assertFailsWith<AndroidPlaybackIOException> { source.open(spec()) }
            assertEquals(0, consumed)
            for (canary in listOf("TOKEN_CANARY", "PASSWORD_CANARY", "USER_CANARY"))
                assertFalse(error.stackTraceToString().contains(canary))
        }
        val source = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ ->
            throw IllegalArgumentException("https://invalid/?u=USER_CANARY&t=TOKEN_CANARY&s=SALT_CANARY")
        }).createDataSource()
        val error = assertFailsWith<AndroidPlaybackIOException> { source.open(spec()) }
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains("CANARY"))
    }

    @Test fun exactLengthTruncationAndWrongRangeCannotBecomeSuccessfulEof() {
        val bytes = wav() + ByteArray(9000)
        val source = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ ->
            response(bytes, declaredLength = bytes.size.toLong() + 100)
        }).createDataSource()
        source.open(spec())
        assertFailsWith<AndroidPlaybackIOException> {
            val buffer = ByteArray(8192)
            while (source.read(buffer, 0, buffer.size) >= 0) Unit
        }
        val ranged = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ ->
            response(bytes, status = 206, range = "bytes 1-${bytes.size}/${bytes.size + 1}")
        }).createDataSource()
        assertFailsWith<AndroidPlaybackIOException> { ranged.open(spec()) }
    }

    @Test fun aSeekRequiresInitialSignatureAndEveryResponseStillRejectsEnvelopes() {
        val bytes = wav() + ByteArray(9000)
        var injectEnvelope = false
        val factory = AndroidPlaybackDataSourceFactory(playbackPlan(), { position, _ ->
            if (position == 0L) response(bytes)
            else if (injectEnvelope) response("{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40}}}".toByteArray())
            else response(bytes.copyOfRange(position.toInt(), bytes.size), status = 206,
                range = "bytes $position-${bytes.lastIndex}/${bytes.size}")
        })
        assertFailsWith<AndroidPlaybackIOException> { factory.createDataSource().open(spec(100)) }
        factory.createDataSource().also { it.open(spec()); it.close() }
        factory.createDataSource().also { assertEquals((bytes.size - 100).toLong(), it.open(spec(100))); it.close() }
        injectEnvelope = true
        assertFailsWith<AndroidPlaybackIOException> { factory.createDataSource().open(spec(100)) }
    }

    @Test fun oversizedEnvelopeOnAValid206SeekNeverReachesThePlayer() {
        for (padding in listOf("", "\uFEFF" + " ".repeat(9000))) {
            val envelope = (padding + "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"" +
                "TOKEN_CANARY".repeat(1000) + "\"}}}").toByteArray()
            var consumed = 0L
            val factory = AndroidPlaybackDataSourceFactory(playbackPlan(), { position, _ ->
                if (position == 0L) response(wav() + ByteArray(30000))
                else response(envelope, status = 206,
                    range = "bytes $position-${position + envelope.size - 1}/${position + envelope.size}")
            }, { consumed += it })
            factory.createDataSource().also { it.open(spec()); it.close() }
            val source = factory.createDataSource()
            val failure = assertFailsWith<AndroidPlaybackIOException> { source.open(spec(100)) }
            assertEquals(0L, consumed)
            assertNull(failure.cause)
            assertFalse(failure.stackTraceToString().contains("TOKEN_CANARY"))
        }
    }

    @Test fun xmlPrologBeforeOversizedRangedEnvelopeRemainsUnclassifiedUntilTheRoot() {
        val error = """<subsonic-response status="failed"><error code="40" message="TOKEN_CANARY"/></subsonic-response>"""
        for (prolog in listOf(
            "<?xml version=\"1.0\"?>" + " ".repeat(9000),
            "<!--" + "comment padding ".repeat(900) + "-->",
            "<?xml version=\"1.0\"?><!--" + "x".repeat(9000) + "--> ",
            "<!DOCTYPE subsonic-response>",
            "<!DOCTYPE subsonic-response [<?note ] > " + "x".repeat(9000) + " ?>]>",
            "<!DOCTYPE subsonic-response [<!-- ] > " + "x".repeat(9000) + " --><!ENTITY note \"value > end\">]>",
        )) {
            val envelope = (prolog + error).toByteArray()
            var consumed = 0L
            val factory = AndroidPlaybackDataSourceFactory(playbackPlan(), { position, _ ->
                if (position == 0L) response(wav())
                else response(envelope, status = 206,
                    range = "bytes $position-${position + envelope.size - 1}/${position + envelope.size}",
                    contentType = "application/octet-stream")
            }, { consumed += it })
            factory.createDataSource().also { it.open(spec()); it.close() }
            val failure = assertFailsWith<AndroidPlaybackIOException> { factory.createDataSource().open(spec(100)) }
            assertEquals(DomainError.Auth.InvalidCredentials, failure.error)
            assertEquals(0L, consumed, "No error-envelope bytes may reach Media3")
        }
    }

    @Test fun impossibleJsonAtAWitnessedSeekOffsetDeliversIdenticalBinaryWithoutReadingToEof() {
        for (prefix in listOf(
            byteArrayOf(123, 0, 1, 2),
            "{not-json".toByteArray(),
            "{\"key\":!".toByteArray(),
            "{\"key\":\"bad\\q".toByteArray(),
        )) {
            val bytes = prefix + ByteArray(40000) { (it % 251).toByte() }
            var readFromResource = 0
            val factory = AndroidPlaybackDataSourceFactory(playbackPlan(), { position, _ ->
                if (position == 0L) response(wav())
                else {
                    val ranged = response(bytes, status = 206,
                        range = "bytes $position-${position + bytes.size - 1}/${position + bytes.size}",
                        contentType = "application/octet-stream")
                    AndroidPlaybackResponse(ranged.status, ranged.headers, object : ByteArrayInputStream(bytes) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                            super.read(buffer, offset, length).also { if (it > 0) readFromResource += it }
                    }, {})
                }
            })
            factory.createDataSource().also { it.open(spec()); it.close() }
            val source = factory.createDataSource()
            assertEquals(bytes.size.toLong(), source.open(spec(100)))
            assertEquals(8192, readFromResource, "Impossible JSON must not buffer the full range")
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val n = source.read(chunk, 0, chunk.size)
                if (n == C.RESULT_END_OF_INPUT) break
                output.write(chunk, 0, n)
            }
            assertContentEquals(bytes, output.toByteArray())
            source.close()
        }
    }

    @Test fun internallyConsistentShort206CannotAdvertiseAnUnservedSuffix() {
        val bytes = wav() + ByteArray(9988)
        val source = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ ->
            response(bytes, status = 206, range = "bytes 0-9999/100000")
        }).createDataSource()
        assertFailsWith<AndroidPlaybackIOException> { source.open(spec()) }
    }

    @Test fun completeBounded206DeliversItsExactRequestedBytes() {
        val bytes = wav() + ByteArray(9988)
        val source = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ ->
            response(bytes, status = 206, range = "bytes 0-9999/100000")
        }).createDataSource()
        assertEquals(10000L, source.open(spec().buildUpon().setLength(10000).build()))
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = source.read(chunk, 0, chunk.size)
            if (n == C.RESULT_END_OF_INPUT) break
            output.write(chunk, 0, n)
        }
        assertContentEquals(bytes, output.toByteArray())
        source.close()
    }

    @Test fun unknownRangePrefixAtTheMemoryLimitFailsClosed() {
        var consumed = 0L
        var prefixBytesRead = 0L
        val unknown = object : java.io.InputStream() {
            override fun read(): Int { prefixBytesRead++; return 32 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(32, offset, offset + length); prefixBytesRead += length; return length
            }
        }
        val factory = AndroidPlaybackDataSourceFactory(playbackPlan(), { position, _ ->
            if (position == 0L) response(wav())
            else AndroidPlaybackResponse(206,
                AuthenticatedEndpointResponseHeaders("audio/wav", PlaybackContentLength.Exact(20000000),
                    null, "bytes", "bytes 100-20000099/20000100"), unknown, {})
        }, { consumed += it })
        factory.createDataSource().also { it.open(spec()); it.close() }
        assertFailsWith<AndroidPlaybackIOException> { factory.createDataSource().open(spec(100)) }
        assertEquals(16L * 1024 * 1024, prefixBytesRead)
        assertEquals(0L, consumed)
    }

    @Test fun estimatedLengthEndsAtObservedEofRatherThanTheServerEstimate() {
        val bytes = wav() + ByteArray(9000)
        val source = AndroidPlaybackDataSourceFactory(playbackPlan(), { _, _ -> response(bytes).let {
            AndroidPlaybackResponse(it.status, it.headers.copy(contentLength = PlaybackContentLength.Estimated(999999)), it.input, it.close)
        } }).createDataSource()
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec()))
        val buffer = ByteArray(8192)
        var observed = 0
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; observed += n }
        assertEquals(bytes.size, observed)
        source.close()
    }

    private fun spec(position: Long = 0) = DataSpec.Builder().setUri(Uri.parse("dulcet://resource")).setPosition(position).build()
    private fun wav() = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()
    private fun response(bytes: ByteArray, declaredLength: Long = bytes.size.toLong(), status: Int = 200,
        range: String? = null, contentType: String = "audio/wav", close: () -> Unit = {}) = AndroidPlaybackResponse(status,
        AuthenticatedEndpointResponseHeaders(contentType, PlaybackContentLength.Exact(declaredLength), null, "bytes", range),
        ByteArrayInputStream(bytes), close)
}
