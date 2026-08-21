package com.legitimateapps.dulcet.core

internal expect fun secureRandomBytes(count: Int): ByteArray

internal fun md5Hex(value: String): String = md5(value.encodeToByteArray()).toLowerHex()

internal fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(HEX[value ushr 4])
        append(HEX[value and 0x0f])
    }
}

private fun md5(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8L
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until 8) {
        padded[paddedSize - 8 + index] = (bitLength ushr (index * 8)).toByte()
    }

    var a0 = 0x67452301
    var b0 = -0x10325477
    var c0 = -0x67452302
    var d0 = 0x10325476
    val words = IntArray(16)

    for (offset in padded.indices step 64) {
        for (index in words.indices) {
            val base = offset + index * 4
            words[index] = (padded[base].toInt() and 0xff) or
                ((padded[base + 1].toInt() and 0xff) shl 8) or
                ((padded[base + 2].toInt() and 0xff) shl 16) or
                ((padded[base + 3].toInt() and 0xff) shl 24)
        }
        var a = a0
        var b = b0
        var c = c0
        var d = d0
        for (round in 0 until 64) {
            val function: Int
            val wordIndex: Int
            when (round) {
                in 0..15 -> {
                    function = (b and c) or (b.inv() and d)
                    wordIndex = round
                }
                in 16..31 -> {
                    function = (d and b) or (d.inv() and c)
                    wordIndex = (5 * round + 1) % 16
                }
                in 32..47 -> {
                    function = b xor c xor d
                    wordIndex = (3 * round + 5) % 16
                }
                else -> {
                    function = c xor (b or d.inv())
                    wordIndex = (7 * round) % 16
                }
            }
            val nextD = c
            c = b
            val sum = a + function + ROUND_CONSTANTS[round] + words[wordIndex]
            b += sum.rotateLeft(SHIFTS[round])
            a = d
            d = nextD
        }
        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }

    return ByteArray(16).also { digest ->
        intArrayOf(a0, b0, c0, d0).forEachIndexed { wordIndex, word ->
            for (byteIndex in 0 until 4) {
                digest[wordIndex * 4 + byteIndex] = (word ushr (byteIndex * 8)).toByte()
            }
        }
    }
}

private fun Int.rotateLeft(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))

private const val HEX = "0123456789abcdef"

private val SHIFTS = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

private val ROUND_CONSTANTS = intArrayOf(
    -680876936, -389564586, 606105819, -1044525330,
    -176418897, 1200080426, -1473231341, -45705983,
    1770035416, -1958414417, -42063, -1990404162,
    1804603682, -40341101, -1502002290, 1236535329,
    -165796510, -1069501632, 643717713, -373897302,
    -701558691, 38016083, -660478335, -405537848,
    568446438, -1019803690, -187363961, 1163531501,
    -1444681467, -51403784, 1735328473, -1926607734,
    -378558, -2022574463, 1839030562, -35309556,
    -1530992060, 1272893353, -155497632, -1094730640,
    681279174, -358537222, -722521979, 76029189,
    -640364487, -421815835, 530742520, -995338651,
    -198630844, 1126891415, -1416354905, -57434055,
    1700485571, -1894986606, -1051523, -2054922799,
    1873313359, -30611744, -1560198380, 1309151649,
    -145523070, -1120210379, 718787259, -343485551,
)
