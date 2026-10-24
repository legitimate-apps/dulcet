package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.IntVar
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SHUT_RDWR
import platform.posix.SHUT_WR
import platform.posix.shutdown
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * Accepts one connection, reads its request head, writes [response] and closes: nothing more. With
 * [holdEnd], the close waits for it after the last byte; what it returns is reported as
 * [Served.endFollowedDelivery].
 */
@OptIn(ExperimentalForeignApi::class)
internal class OneShotLoopbackServer(
    private val response: ByteArray,
    private val holdEnd: (() -> Boolean)? = null,
) {
    private val listener: Int = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket failed" } }
    val port: Int

    init {
        port = memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            address.sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind failed" }
            check(listen(listener, 1) == 0) { "listen failed" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname failed" }
            val networkOrder = address.sin_port.toInt()
            ((networkOrder and 0xff) shl 8) or ((networkOrder shr 8) and 0xff)
        }
    }

    data class Served(val requestHead: String, val endFollowedDelivery: Boolean)

    fun serveOnce(): Served {
        val connection = accept(listener, null, null)
        check(connection >= 0) { "accept failed" }
        try {
            memScoped {
                val on = alloc<IntVar>().apply { value = 1 }
                setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, on.ptr, sizeOf<IntVar>().convert())
            }
            val head = StringBuilder()
            val buffer = ByteArray(4096)
            while (!head.contains("\r\n\r\n")) {
                val count = buffer.usePinned { recv(connection, it.addressOf(0), buffer.size.convert(), 0) }
                if (count <= 0) break
                head.append(buffer.decodeToString(0, count.toInt()))
            }
            var sent = 0
            response.usePinned { pinned ->
                while (sent < response.size) {
                    val count = send(connection, pinned.addressOf(sent), (response.size - sent).convert(), 0)
                    check(count > 0) { "send failed" }
                    sent += count.toInt()
                }
            }
            // A FIN arriving close behind bytes the session has not handed over yet discards them,
            // so a test about delivered bytes holds it until the client has been handed one.
            val endFollowedDelivery = holdEnd?.invoke() ?: false
            // End the stream the way the reference server does -- a FIN after the last byte, then
            // wait for the peer -- rather than an abortive close that could discard bytes in flight.
            shutdown(connection, SHUT_WR)
            buffer.usePinned { while (recv(connection, it.addressOf(0), buffer.size.convert(), 0) > 0) Unit }
            return Served(head.toString(), endFollowedDelivery)
        } finally {
            close(connection)
        }
    }

    fun closeListener() {
        shutdown(listener, SHUT_RDWR)
        close(listener)
    }

    private companion object {
        // 127.0.0.1 with its bytes in network order, read as a little-endian UInt.
        const val LOOPBACK_NETWORK_ORDER = 0x0100007Fu
    }
}
