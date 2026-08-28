package com.legitimateapps.dulcet.core

/** Thread-safe handoff from a platform authentication delegate to the suspended Kotlin request. */
internal expect class UnsupportedAuthenticationChallengeTracker() {
    fun markUnsupported()
    fun consumeUnsupported(): Boolean
}
