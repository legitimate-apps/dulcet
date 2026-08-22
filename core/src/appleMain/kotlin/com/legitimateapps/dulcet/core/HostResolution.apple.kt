package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_UNSPEC
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun platformResolveHost(host: String): List<String> = memScoped {
    val hints = alloc<addrinfo> {
        ai_flags = 0
        ai_family = AF_UNSPEC
        ai_socktype = SOCK_STREAM
        ai_protocol = 0
        ai_addrlen = 0u
        ai_canonname = null
        ai_addr = null
        ai_next = null
    }
    val result = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped emptyList()
    val head = result.value ?: return@memScoped emptyList()

    try {
        buildList {
            var current: CPointer<addrinfo>? = head
            while (current != null) {
                val item = current.pointed
                val address = item.ai_addr
                if (address != null) {
                    val buffer = allocArray<ByteVar>(NI_MAXHOST)
                    if (
                        getnameinfo(
                            address,
                            item.ai_addrlen,
                            buffer,
                            NI_MAXHOST.toUInt(),
                            null,
                            0u,
                            NI_NUMERICHOST,
                        ) == 0
                    ) {
                        add(buffer.toKString())
                    }
                }
                current = item.ai_next
            }
        }.distinct()
    } finally {
        freeaddrinfo(head)
    }
}
