package com.legitimateapps.dulcet.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Used only when the JSON decoder rejected a binary-response prefix. A syntax error is not
 * necessarily truncation: binary at a seek offset can start with '{'. Recognize the JSON grammar
 * up to EOF, without treating decoder exception messages as a stable parsing API.
 */
internal class BinaryJsonPrefix(private val text: String) {
    private var index = 0
    private var recognizedEnvelope = false

    fun inspect(): SubsonicBinaryEnvelopeInspection = try {
        value(0)
        whitespace()
        if (index != text.length) throw Invalid()
        // The caller's decoder rejected a syntactically complete document.
        rejected()
    } catch (_: Incomplete) {
        SubsonicBinaryEnvelopeInspection.Unknown
    } catch (_: Invalid) {
        rejected()
    }

    private fun rejected() = if (recognizedEnvelope) SubsonicBinaryEnvelopeInspection.Malformed
        else SubsonicBinaryEnvelopeInspection.NotEnvelope

    private fun value(depth: Int) {
        // Bound recursion on adversarial nesting, retaining uncertainty rather than accepting audio.
        if (depth > 128) throw Incomplete()
        whitespace()
        when (peek()) {
            '{' -> {
                index++
                whitespace()
                if (peek() == '}') { index++; return }
                while (true) {
                    val keyStart = index
                    string()
                    val keyEnd = index
                    whitespace()
                    expect(':')
                    if (depth == 0 && (Json.parseToJsonElement(text.substring(keyStart, keyEnd)) as? JsonPrimitive)
                            ?.contentOrNull == "subsonic-response") recognizedEnvelope = true
                    value(depth + 1)
                    whitespace()
                    if (peek() == '}') { index++; return }
                    expect(',')
                    whitespace()
                }
            }
            '[' -> {
                index++
                whitespace()
                if (peek() == ']') { index++; return }
                while (true) {
                    value(depth + 1)
                    whitespace()
                    if (peek() == ']') { index++; return }
                    expect(',')
                }
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> throw Invalid()
        }
    }

    private fun string() {
        expect('"')
        while (true) {
            val char = peek()
            index++
            when {
                char == '"' -> return
                char == '\\' -> {
                    when (peek()) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> index++
                        'u' -> {
                            index++
                            repeat(4) {
                                if (peek() !in "0123456789abcdefABCDEF") throw Invalid()
                                index++
                            }
                        }
                        else -> throw Invalid()
                    }
                }
                char < ' ' -> throw Invalid()
            }
        }
    }

    private fun number() {
        if (peek() == '-') index++
        when (peek()) {
            '0' -> index++
            in '1'..'9' -> digits()
            else -> throw Invalid()
        }
        if (text.getOrNull(index) == '.') { index++; requireDigits() }
        if (text.getOrNull(index) in listOf('e', 'E')) {
            index++
            if (text.getOrNull(index) in listOf('+', '-')) index++
            requireDigits()
        }
    }

    private fun requireDigits() {
        if (peek() !in '0'..'9') throw Invalid()
        digits()
    }

    private fun digits() { while (text.getOrNull(index) in '0'..'9') index++ }
    private fun literal(value: String) { value.forEach(::expect) }
    private fun expect(char: Char) { if (peek() != char) throw Invalid(); index++ }
    private fun peek(): Char = text.getOrNull(index) ?: throw Incomplete()
    private fun whitespace() { while (text.getOrNull(index) in listOf(' ', '\t', '\r', '\n')) index++ }
    private class Incomplete : Exception()
    private class Invalid : Exception()
}
