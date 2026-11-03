package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.emulator.httpResponseBodiesForProof
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captured server evidence must survive HTTP framing and negotiated compression. */
class HttpResponseBodiesTest {
    private val refused = """{"subsonic-response":{"status":"failed","error":{"code":40}}}""".toByteArray()
    private val accepted = """{"subsonic-response":{"status":"ok"}}""".toByteArray()

    @Test fun gzipAndChunkedResponsesRemainDistinctOnOnePersistentConnection() {
        val compressed = gzip(refused)
        val wire = fixed(accepted) + ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n" +
            "Vary: Origin\r\nVary: Accept-Encoding\r\n\r\n").toByteArray() +
            compressed.size.toString(16).toByteArray() + "\r\n".toByteArray() + compressed + "\r\n0\r\n\r\n".toByteArray()
        val decoded = httpResponseBodiesForProof(wire)
        assertEquals(2, decoded.size)
        assertArrayEquals(accepted, decoded[0])
        assertArrayEquals(refused, decoded[1])
    }

    @Test fun identityAndGzipContentLengthsPreserveActualServerRefusalBytes() {
        for (compressed in listOf(false, true)) {
            val wire = if (compressed) fixed(gzip(refused), "Content-Encoding: gzip\r\n") else fixed(refused)
            assertArrayEquals(refused, httpResponseBodiesForProof(wire).single())
        }
    }

    @Test fun incompleteOrConflictingFramesCannotSupplyServerEvidence() {
        assertTrue(httpResponseBodiesForProof(fixed(refused).dropLast(1).toByteArray()).isEmpty())
        val invalid = "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n{}".toByteArray()
        assertTrue(httpResponseBodiesForProof(invalid).isEmpty())
        val partialChunk = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n40\r\n{}".toByteArray()
        assertTrue(httpResponseBodiesForProof(partialChunk).isEmpty())
        assertTrue(httpResponseBodiesForProof(fixed(refused, "Content-Encoding: gzip\r\n")).isEmpty())
    }

    @Test fun decompressionIsBoundedAndResponseHeadersNeverBecomeTheBody() {
        assertTrue(httpResponseBodiesForProof(fixed(gzip(ByteArray(1024)), "Content-Encoding: gzip\r\n"), 128).isEmpty())
        val wire = fixed(accepted, "X-Refusal-Canary: ${refused.toString(Charsets.UTF_8)}\r\n")
        assertArrayEquals(accepted, httpResponseBodiesForProof(wire).single())
    }

    private fun fixed(body: ByteArray, extra: String = "") =
        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n$extra\r\n".toByteArray() + body

    private fun gzip(body: ByteArray): ByteArray = ByteArrayOutputStream().let { output ->
        GZIPOutputStream(output).use { it.write(body) }
        output.toByteArray()
    }
}
