package com.legitimateapps.dulcet.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

internal actual fun provesNeverConnected(failure: Throwable): Boolean {
    var current: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        when (val candidate = current ?: return false) {
            // Thrown only while connecting: refused, no route, or a name that did not resolve.
            is ConnectException, is NoRouteToHostException, is UnknownHostException, is UnresolvedAddressException -> return true
            else -> current = candidate.cause
        }
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 16
