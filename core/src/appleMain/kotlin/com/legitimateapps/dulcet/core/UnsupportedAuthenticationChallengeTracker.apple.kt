package com.legitimateapps.dulcet.core

import platform.Foundation.NSLock

internal actual class UnsupportedAuthenticationChallengeTracker actual constructor() {
    private val lock = NSLock()
    private var unsupported = false

    actual fun markUnsupported() {
        lock.lock()
        try {
            unsupported = true
        } finally {
            lock.unlock()
        }
    }

    actual fun consumeUnsupported(): Boolean {
        lock.lock()
        return try {
            unsupported.also { unsupported = false }
        } finally {
            lock.unlock()
        }
    }
}
