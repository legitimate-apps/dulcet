package com.legitimateapps.dulcet.core

import java.util.concurrent.atomic.AtomicBoolean

internal actual class UnsupportedAuthenticationChallengeTracker actual constructor() {
    private val unsupported = AtomicBoolean(false)

    actual fun markUnsupported() {
        unsupported.set(true)
    }

    actual fun consumeUnsupported(): Boolean = unsupported.getAndSet(false)
}
