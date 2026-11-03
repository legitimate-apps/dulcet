package com.legitimateapps.dulcet.emulator

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** Complete bounded HTTP response bodies from an in-memory relay tap; no wire values leave here. */
internal fun httpResponseBodiesForProof(bytes: ByteArray, limit: Int = 64 * 1024): List<ByteArray> {
    require(limit in 1..1024 * 1024)
    val result = mutableListOf<ByteArray>()
    var at = 0
    fun endOfLine(start: Int): Int = (start until bytes.size - 1)
        .firstOrNull { bytes[it] == 13.toByte() && bytes[it + 1] == 10.toByte() } ?: -1
    while (at < bytes.size) {
        val fields = mutableMapOf<String, String>()
        val statusEnd = endOfLine(at)
        if (statusEnd < 0) break
        val status = bytes.copyOfRange(at, statusEnd).toString(Charsets.ISO_8859_1)
        if (!status.matches(Regex("HTTP/1\\.[01] [0-9]{3}( .*)?"))) break
        val statusCode = status.substring(9, 12).toInt()
        val headStart = at
        at = statusEnd + 2
        var completeHead = false
        while (at < bytes.size && at - headStart <= 16 * 1024) {
            val end = endOfLine(at)
            if (end < 0) break
            if (end == at) { at += 2; completeHead = true; break }
            val line = bytes.copyOfRange(at, end).toString(Charsets.ISO_8859_1)
            val separator = line.indexOf(':')
            if (separator <= 0) return result
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            val previous = fields[key]
            if (key == "content-length" && previous != null && previous != value) return result
            fields[key] = if (previous != null && key != "content-length") "$previous,$value" else value
            at = end + 2
        }
        if (!completeHead) break
        val body = ByteArrayOutputStream()
        var oversized = false
        fun append(count: Int): Boolean {
            if (count < 0 || count > bytes.size - at) return false
            if (body.size().toLong() + count > limit) oversized = true
            if (!oversized) body.write(bytes, at, count)
            at += count
            return true
        }
        val transfer = fields["transfer-encoding"]?.lowercase()
        when {
            statusCode in 100..199 || statusCode == 204 || statusCode == 304 -> Unit
            transfer == "chunked" -> {
                while (true) {
                    val end = endOfLine(at)
                    if (end < 0 || end - at > 128) return result
                    val count = bytes.copyOfRange(at, end).toString(Charsets.ISO_8859_1)
                        .substringBefore(';').trim().toIntOrNull(16) ?: return result
                    at = end + 2
                    if (count == 0) {
                        val trailerStart = at
                        while (true) {
                            val trailerEnd = endOfLine(at)
                            if (trailerEnd < 0 || trailerEnd - trailerStart > 16 * 1024) return result
                            val empty = trailerEnd == at
                            at = trailerEnd + 2
                            if (empty) break
                        }
                        break
                    }
                    if (!append(count) || endOfLine(at) != at) return result
                    at += 2
                }
            }
            transfer != null -> return result
            fields["content-length"] != null -> {
                val count = fields.getValue("content-length").toIntOrNull() ?: return result
                if (!append(count)) return result
            }
            else -> if (!append(bytes.size - at)) return result
        }
        if (oversized) continue
        val encoded = body.toByteArray()
        val decoded = when (fields["content-encoding"]?.lowercase()) {
            null, "identity" -> encoded
            "gzip" -> runCatching {
                GZIPInputStream(ByteArrayInputStream(encoded)).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > limit) return@use null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }.getOrNull()
            else -> null
        }
        if (decoded != null) result += decoded
    }
    return result
}
