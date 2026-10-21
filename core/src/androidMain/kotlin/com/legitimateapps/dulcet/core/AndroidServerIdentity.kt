package com.legitimateapps.dulcet.core

import android.content.Context
import kotlinx.coroutines.flow.update

/** Records only a server accepted on the account form, after any replacement consent. */
public object AndroidServerIdentity {
    private val mutableVerified = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    public val verified: kotlinx.coroutines.flow.StateFlow<Set<String>> = mutableVerified
    private val validationLock = Any()
    private val generations = mutableMapOf<String, Long>()
    private fun nextGeneration(id: String): Long = ((generations[id] ?: 0) + 1).also { generations[id] = it }
    private val rejected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val seeded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    public fun seed(context: Context, id: String, identity: String) {
        if (id in seeded) return
        val store = DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
        try {
            val identities = ServerIdentityStore(store.database)
            if (identities.read(id).isEmpty()) identities.accept(id, identity)
            seeded += id
        } finally { store.close() }
    }
    public fun requireValidation(id: String) { synchronized(validationLock) {
        nextGeneration(id)
        mutableVerified.update { it - id }
    } }
    public fun isVerified(id: String): Boolean = id in mutableVerified.value && id !in rejected
    internal suspend fun validate(id: String, validation: suspend () -> DomainError?): DomainError? {
        val generation = synchronized(validationLock) {
            mutableVerified.update { it - id }
            nextGeneration(id)
        }
        val error = validation()
        return synchronized(validationLock) {
            if (generations[id] != generation) {
                if (id in rejected) DomainError.Protocol.ServerIdentityChanged else DomainError.Transport.Cancelled
            } else {
                if (error == DomainError.Protocol.ServerIdentityChanged) rejected += id
                if (error == null && id !in rejected) mutableVerified.update { it + id }
                else mutableVerified.update { it - id }
                if (id in rejected) DomainError.Protocol.ServerIdentityChanged else error
            }
        }
    }
    public fun accept(context: Context, providerInstanceId: String, identity: String) {
        val generation = synchronized(validationLock) { nextGeneration(providerInstanceId) }
        val store = DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
        try {
            ServerIdentityStore(store.database).accept(providerInstanceId, identity)
            synchronized(validationLock) {
                if (generations[providerInstanceId] == generation) {
                    rejected -= providerInstanceId
                    mutableVerified.update { it + providerInstanceId }
                }
            }
        }
        finally { store.close() }
    }
}
