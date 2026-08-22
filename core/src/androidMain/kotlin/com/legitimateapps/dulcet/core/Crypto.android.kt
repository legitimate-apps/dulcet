package com.legitimateapps.dulcet.core

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray =
    ByteArray(count).also(secureRandom::nextBytes)
