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

// NSURLSession reports a body shorter than its declared Content-Length through this code, after
// handing the data-task delegate a prefix of it. OBSERVED with Ktor 3.5.2 on macOS. For a request
// that asked for an estimate, the session delegate in AccountHttpClient.apple.kt already turns this
// completion into a normal end, so it reaches the estimated read only if that predicate misses one.
private const val NETWORK_CONNECTION_LOST = -1005L
private const val MAX_CAUSE_DEPTH = 16
