package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray {
    require(count > 0)
    val bytes = ByteArray(count)
    val result = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
    }
    check(result == errSecSuccess) { "The operating system secure random generator failed" }
    return bytes
}
