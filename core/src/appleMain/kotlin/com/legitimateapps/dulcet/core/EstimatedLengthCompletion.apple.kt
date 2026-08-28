package com.legitimateapps.dulcet.core

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLErrorDomain

@OptIn(ExperimentalForeignApi::class)
internal actual fun isPlatformEstimatedLengthCompletion(failure: Throwable): Boolean {
    var current: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return false
        val origin = (candidate as? DarwinHttpRequestException)?.origin
        if (origin?.domain == NSURLErrorDomain && origin?.code == NETWORK_CONNECTION_LOST) {
            return true
        }
        current = candidate.cause
    }
    return false
}

// NSURLSession reports a body shorter than its declared Content-Length through this code after it
// has delivered all received bytes to the data-task delegate. OBSERVED with Ktor 3.5.2 on macOS.
private const val NETWORK_CONNECTION_LOST = -1005L
private const val MAX_CAUSE_DEPTH = 16
