package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Process-local authority, shared across native facades before any queued UI notification. */
@OptIn(ExperimentalAtomicApi::class)
internal class AppleLibraryReaderIdentityAuthorization private constructor(
    private val state: AtomicReference<State>,
    private val generation: Long,
) {
    internal data class State(val generation: Long, val rejected: Boolean)
    constructor() : this(AtomicReference(State(0L, false)), 0L)
    fun currentToken(): Long? = state.load().let { if (!it.rejected && it.generation == generation) generation else null }
    fun reject(): Boolean {
        while (true) {
            val old = state.load()
            if (old.generation != generation || old.rejected) return false
            if (state.compareAndSet(old, old.copy(rejected = true))) return true
        }
    }
    companion object {
        private val accounts = AtomicReference<Map<Pair<String, String>, AtomicReference<State>>>(emptyMap())
        private fun state(databaseName: String, provider: String): AtomicReference<State> {
            val key = databaseName to provider
            while (true) {
                val old = accounts.load()
                old[key]?.let { return it }
                val created = AtomicReference(State(0L, false))
                if (accounts.compareAndSet(old, old + (key to created))) return created
            }
        }
        fun capture(databaseName: String, provider: String): AppleLibraryReaderIdentityAuthorization {
            val state = state(databaseName, provider)
            return AppleLibraryReaderIdentityAuthorization(state, state.load().generation)
        }
        /** Only called after durable, explicitly validated acceptance; ordinary probes do not renew. */
        fun accept(databaseName: String, provider: String, replacement: Boolean = false) {
            val state = state(databaseName, provider)
            while (true) {
                val old = state.load()
                if (!old.rejected && !replacement) return
                if (state.compareAndSet(old, State(old.generation + 1L, false))) return
            }
        }
    }
}

/** Captured account lifetime for native callbacks that await outside the shared reader. */
public class AppleServerIdentityAccess(databaseName: String, providerInstanceId: String) {
    private val authorization = AppleLibraryReaderIdentityAuthorization.capture(databaseName, providerInstanceId)
    public val authorized: Boolean get() = authorization.currentToken() != null
}
