package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.test.*

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidEncodedPlaybackDataSourceTest(private val encoding: String, private val bom: Boolean) {
    @Test fun encodedXmlErrorsCannotUseAnEarlierAudioWitness() {
        // Declaration, declaration-free root, and encoded leading whitespace cover BOMless sniffing too.
        val declarationEncoding = when {
            encoding.startsWith("UTF-16") -> "UTF-16"
            encoding.startsWith("UTF-32") -> "UTF-32"
            encoding.startsWith("UCS-4") -> "ISO-10646-UCS-4"
            else -> encoding
        }
        for (opening in listOf("<?xml version=\"1.0\" encoding=\"$declarationEncoding\"?>", "", " ")) {
            val body = encode(opening + " ".repeat(9000) +
                "<subsonic-response><error code=\"40\"/></subsonic-response>")
            val factory = factory(body)
            witness(factory)
            val source = factory.createDataSource()
            val failure = assertFailsWith<AndroidPlaybackIOException> { source.open(spec(100)) }
            assertEquals(DomainError.Auth.InvalidCredentials, failure.error, "$encoding BOM=$bom")
            source.close()
        }
    }

    @Test fun encodedJsonErrorsCannotUseAnEarlierAudioWitness() {
        val body = encode("{\"subsonic-response\":{\"error\":{\"message\":\"" + "x".repeat(9000) + "\",\"code\":40}}}")
        val factory = factory(body)
        witness(factory)
        val failure = assertFailsWith<AndroidPlaybackIOException> { factory.createDataSource().open(spec(100)) }
        assertEquals(DomainError.Auth.InvalidCredentials, failure.error)
    }

    @Test fun splitEncodingMarkersAndCodePointsRemainUnknown() {
        val bytes = encode("<?note Ω😀?>")
        for (length in 1..bytes.size) {
            assertEquals(SubsonicBinaryEnvelopeInspection.Unknown,
                bytes.copyOf(length).inspectSubsonicBinaryEnvelope(), "$encoding BOM=$bom prefix=$length")
        }
    }

    @Test fun encodingMarkersDoNotRejectImpossibleDocumentBinaryRanges() {
        val body = encode("{\u0000\u0001") + ByteArray(40000) { (it % 251).toByte() }
        var reads = 0
        var consumed = 0L
        val factory = factory(body, { reads += it }, { consumed += it })
        witness(factory)
        reads = 0
        val source = factory.createDataSource()
        assertEquals(body.size.toLong(), source.open(spec(100)))
        assertEquals(8192, reads, "An impossible encoded document must not read to EOF")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, n)
        }
        assertContentEquals(body, output.toByteArray())
        assertEquals(body.size.toLong(), consumed)
        source.close()
    }

    private fun factory(body: ByteArray, read: (Int) -> Unit = {}, observed: (Long) -> Unit = {}): AndroidPlaybackDataSourceFactory =
        AndroidPlaybackDataSourceFactory(playbackPlan(), { position, _ ->
            val bytes = if (position == 0L) "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray() else body
            AndroidPlaybackResponse(if (position == 0L) 200 else 206,
                AuthenticatedEndpointResponseHeaders("application/octet-stream", PlaybackContentLength.Exact(bytes.size.toLong()),
                    null, "bytes", if (position == 0L) null else "bytes $position-${position + bytes.size - 1}/${position + bytes.size}"),
                object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        super.read(buffer, offset, length).also { if (it > 0) read(it) }
                }, {})
        }, observed)

    private fun witness(factory: AndroidPlaybackDataSourceFactory) {
        factory.createDataSource().also { it.open(spec(0)); it.close() }
    }
    private fun spec(position: Long) = DataSpec.Builder().setUri(Uri.parse("dulcet://resource")).setPosition(position).build()
    private fun encode(text: String): ByteArray {
        val sourceEncoding = if (encoding.startsWith("UCS-4")) "UTF-32BE" else encoding
        val bytes = text.toByteArray(Charset.forName(sourceEncoding))
        val marker = when (encoding) {
            "UTF-8" -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
            "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte())
            "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte())
            else -> "\uFEFF".toByteArray(Charset.forName(sourceEncoding))
        }
        val encoded = (if (bom) marker else byteArrayOf()) + bytes
        if (encoding == "UCS-4-2143") {
            for (i in encoded.indices step 4) {
                val a = encoded[i]; encoded[i] = encoded[i + 1]; encoded[i + 1] = a
                val b = encoded[i + 2]; encoded[i + 2] = encoded[i + 3]; encoded[i + 3] = b
            }
        } else if (encoding == "UCS-4-3412") {
            for (i in encoded.indices step 4) {
                val a = encoded[i]; val b = encoded[i + 1]
                encoded[i] = encoded[i + 2]; encoded[i + 1] = encoded[i + 3]
                encoded[i + 2] = a; encoded[i + 3] = b
            }
        }
        // Assert the fixture's actual byte marker, independently of the charset encoder and
        // permutation helpers; BOMless cases must not silently acquire a BOM from the host codec.
        val expectedMarker = when (encoding) {
            "UTF-8" -> listOf(0xef, 0xbb, 0xbf)
            "UTF-16BE" -> listOf(0xfe, 0xff)
            "UTF-16LE" -> listOf(0xff, 0xfe)
            "UTF-32BE" -> listOf(0, 0, 0xfe, 0xff)
            "UTF-32LE" -> listOf(0xff, 0xfe, 0, 0)
            "UCS-4-2143" -> listOf(0, 0, 0xff, 0xfe)
            else -> listOf(0xfe, 0xff, 0, 0)
        }
        assertEquals(bom, encoded.take(expectedMarker.size).map { it.toInt() and 0xff } == expectedMarker)
        return encoded
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-bom={1}")
        fun encodings(): List<Array<Any>> = listOf("UTF-8", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE", "UCS-4-2143", "UCS-4-3412")
            .flatMap { encoding -> listOf(false, true).map { arrayOf<Any>(encoding, it) } }
    }
}
