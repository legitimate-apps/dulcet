package com.legitimateapps.dulcet.core

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLErrorDomain

@OptIn(ExperimentalForeignApi::class)
internal actual fun provesNeverConnected(failure: Throwable): Boolean {
    var current: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return false
        val origin = (candidate as? DarwinHttpRequestException)?.origin
        // NSURLErrorNetworkConnectionLost (-1005) is not here: the connection was made, and what it
        // carried may have arrived.
        if (origin != null && origin.domain == NSURLErrorDomain && origin.code in NEVER_CONNECTED) return true
        current = candidate.cause
    }
    return false
}

/**
 * NSURLErrorCannotFindHost, CannotConnectToHost and DNSLookupFailed: each names a connection that
 * was never made. The no-network codes (-1009, -1018, -1020) are left out: a path that drops while a
 * request is out may be reported with them, and an unproven "never sent" would send a write twice.
 */
private val NEVER_CONNECTED = setOf(-1003L, -1004L, -1006L)
private const val MAX_CAUSE_DEPTH = 16
