package com.legitimateapps.dulcet.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Ordered first-stage inspection shared by every binary-or-Subsonic-envelope endpoint. */
internal sealed interface SubsonicBinaryEnvelopeInspection {
    data object NotEnvelope : SubsonicBinaryEnvelopeInspection
    data object Malformed : SubsonicBinaryEnvelopeInspection
    data class Error(val code: Int) : SubsonicBinaryEnvelopeInspection
}

internal fun ByteArray.inspectSubsonicBinaryEnvelope(): SubsonicBinaryEnvelopeInspection {
    val start = binaryPayloadContentStartIndex()
    if (start >= size) return SubsonicBinaryEnvelopeInspection.NotEnvelope
    return when (this[start].toInt().toChar()) {
        '{' -> inspectJsonSubsonicBinaryEnvelope(start)
        '<' -> inspectXmlSubsonicBinaryEnvelope(start)
        else -> SubsonicBinaryEnvelopeInspection.NotEnvelope
    }
}

private fun ByteArray.inspectJsonSubsonicBinaryEnvelope(
    start: Int,
): SubsonicBinaryEnvelopeInspection = try {
    val root = BINARY_ENVELOPE_JSON.parseToJsonElement(
        copyOfRange(start, size).decodeToString(),
    ) as? JsonObject ?: return SubsonicBinaryEnvelopeInspection.NotEnvelope
    val payload = root["subsonic-response"] as? JsonObject
        ?: return SubsonicBinaryEnvelopeInspection.NotEnvelope
    val error = payload["error"] as? JsonObject
        ?: return SubsonicBinaryEnvelopeInspection.Malformed
    val code = (error["code"] as? JsonPrimitive)?.intOrNull
        ?: return SubsonicBinaryEnvelopeInspection.Malformed
    SubsonicBinaryEnvelopeInspection.Error(code)
} catch (_: IllegalArgumentException) {
    SubsonicBinaryEnvelopeInspection.NotEnvelope
}

private fun ByteArray.inspectXmlSubsonicBinaryEnvelope(
    start: Int,
): SubsonicBinaryEnvelopeInspection {
    val xml = copyOfRange(start, size).decodeToString()
    if (!XML_SUBSONIC_RESPONSE_ROOT.containsMatchIn(xml)) {
        return SubsonicBinaryEnvelopeInspection.NotEnvelope
    }
    val code = XML_SUBSONIC_ERROR_CODE.find(xml)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: return SubsonicBinaryEnvelopeInspection.Malformed
    return SubsonicBinaryEnvelopeInspection.Error(code)
}

internal fun ByteArray.binaryPayloadContentStartIndex(): Int {
    var index = 0
    while (index < size && this[index].isEnvelopeWhitespace()) index += 1
    if (matchesBytes(index, 0xEF, 0xBB, 0xBF)) index += 3
    while (index < size && this[index].isEnvelopeWhitespace()) index += 1
    return index
}

private fun Byte.isEnvelopeWhitespace(): Boolean = when (toInt() and 0xFF) {
    0x09, 0x0A, 0x0D, 0x20 -> true
    else -> false
}

internal fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
    value.indices.all { index ->
        offset + index < size && this[offset + index].toInt() and 0xFF == value[index].code
    }

internal fun ByteArray.matchesBytes(offset: Int, vararg expected: Int): Boolean =
    expected.indices.all { index ->
        offset + index < size && this[offset + index].toInt() and 0xFF == expected[index]
    }

private val BINARY_ENVELOPE_JSON = Json { ignoreUnknownKeys = true }
private val XML_SUBSONIC_RESPONSE_ROOT = Regex(
    """<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?subsonic-response\b""",
)
private val XML_SUBSONIC_ERROR_CODE = Regex(
    """<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?error\b[^>]*\bcode\s*=\s*["'](-?\d+)["']""",
)
