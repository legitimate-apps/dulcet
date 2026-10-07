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
 * NSURLErrorCannotFindHost, CannotConnectToHost, DNSLookupFailed, NotConnectedToInternet,
 * InternationalRoamingOff and DataNotAllowed: each is reported before a connection exists.
 */
private val NEVER_CONNECTED = setOf(-1003L, -1004L, -1006L, -1009L, -1018L, -1020L)
private const val MAX_CAUSE_DEPTH = 16
