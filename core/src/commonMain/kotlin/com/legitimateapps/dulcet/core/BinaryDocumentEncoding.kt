package com.legitimateapps.dulcet.core

/**
 * Decode only an inspection view; the original response bytes always go to the media consumer.
 * Unicode BOMs and BOMless ASCII opening code units follow XML 1.0 appendix F:
 * https://www.w3.org/TR/xml/#sec-guessing . This is detection, not declaration-driven transcoding.
 * UTF-8, UTF-16 BE/LE, UTF-32 BE/LE and UCS-4 orders 2143/3412 are decoded. An EBCDIC XML
 * signature is recognized but remains unknown; no EBCDIC/UTF-7 or arbitrary legacy decoder exists.
 */
internal fun ByteArray.decodeBinaryDocumentPrefix(): String? {
    var offset = 0
    var encoding = documentEncodingAt(offset)
    if (encoding == null) {
        // Retain the existing tolerance of ASCII whitespace before a UTF-8 BOM, and extend it
        // to other BOMs. Detect encoded whitespace first so a UTF-16LE space is not split in half.
        while (offset < size && this[offset].toInt() and 0xff in listOf(9, 10, 13, 32)) offset++
        encoding = documentEncodingAt(offset)
    }
    if (offset == size) return ""
    if (size - offset < 4 && (
            DOCUMENT_ENCODINGS.any { candidate ->
                size - offset < candidate.bom.size && (offset until size).all {
                    this[it].toInt() and 0xff == candidate.bom[it - offset]
                }
            } || this[offset].toInt() and 0xff in DOCUMENT_OPENING_BYTES || this[offset] == 0.toByte()
        )) return null
    // XML's EBCDIC opening signature requires a code-page decoder we deliberately do not have.
    if (matchesBytes(offset, 0x4c, 0x6f, 0xa7, 0x94)) return null
    if (encoding == null) return decodeUtf8Prefix(offset)
    if (matchesBytes(offset, *encoding.bom)) offset += encoding.bom.size
    if (encoding.order.size == 1) return decodeUtf8Prefix(offset)

    val text = StringBuilder((size - offset) / encoding.order.size)
    val width = encoding.order.size
    while (offset + width <= size) {
        var code = 0L
        for (byte in encoding.order) code = (code shl 8) or (this[offset + byte].toLong() and 0xff)
        when {
            width == 2 -> text.append(code.toInt().toChar())
            code > 0x10ffff || code in 0xd800..0xdfff -> text.append('\u0000') // impossible Unicode scalar
            code <= 0xffff -> text.append(code.toInt().toChar())
            else -> {
                val supplementary = code.toInt() - 0x10000
                text.append((0xd800 + (supplementary shr 10)).toChar())
                text.append((0xdc00 + (supplementary and 0x3ff)).toChar())
            }
        }
        offset += width
    }
    // An incomplete code unit or surrogate pair is not evidence against a text document.
    if (text.lastOrNull()?.isHighSurrogate() == true) text.setLength(text.length - 1)
    return text.toString()
}

private class DocumentEncoding(val bom: IntArray, val order: IntArray)

// Longest BOMs first: FF FE is also the beginning of UTF-32LE, FE FF of UCS-4 order 3412.
private val DOCUMENT_ENCODINGS = listOf(
    DocumentEncoding(intArrayOf(0, 0, 0xfe, 0xff), intArrayOf(0, 1, 2, 3)),
    DocumentEncoding(intArrayOf(0xff, 0xfe, 0, 0), intArrayOf(3, 2, 1, 0)),
    DocumentEncoding(intArrayOf(0, 0, 0xff, 0xfe), intArrayOf(1, 0, 3, 2)),
    DocumentEncoding(intArrayOf(0xfe, 0xff, 0, 0), intArrayOf(2, 3, 0, 1)),
    DocumentEncoding(intArrayOf(0xfe, 0xff), intArrayOf(0, 1)),
    DocumentEncoding(intArrayOf(0xff, 0xfe), intArrayOf(1, 0)),
    DocumentEncoding(intArrayOf(0xef, 0xbb, 0xbf), intArrayOf(0)),
)
private val DOCUMENT_OPENING_BYTES = setOf(9, 10, 13, 32, '<'.code, '{'.code)

private fun ByteArray.documentEncodingAt(offset: Int): DocumentEncoding? {
    DOCUMENT_ENCODINGS.firstOrNull { matchesBytes(offset, *it.bom) }?.let { return it }
    // These byte patterns identify ASCII markup/whitespace encoded in multibyte code units,
    // including declarations without BOMs and documents beginning directly with their root.
    return DOCUMENT_ENCODINGS.firstOrNull { candidate ->
        val width = candidate.order.size
        width > 1 && offset + width <= size &&
            candidate.order.dropLast(1).all { this[offset + it] == 0.toByte() } &&
            this[offset + candidate.order.last()].toInt() and 0xff in DOCUMENT_OPENING_BYTES
    }
}

private fun ByteArray.decodeUtf8Prefix(start: Int): String {
    var end = size
    var lead = size - 1
    while (lead >= start && this[lead].toInt() and 0xc0 == 0x80) lead--
    if (lead >= start) {
        val byte = this[lead].toInt() and 0xff
        val width = when (byte) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 1 }
        if (size - lead < width) end = lead
    }
    return copyOfRange(start, end).decodeToString()
}
