package com.legitimateapps.dulcet.core

import android.content.Context
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

/** Records only a server accepted on the account form, after any replacement consent. */
public object AndroidServerIdentity {
    private val mutableVerified = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    public val verified: kotlinx.coroutines.flow.StateFlow<Set<String>> = mutableVerified
    private val validationLock = Any()
    private val generations = mutableMapOf<String, Long>()
    // Routine probes do not retire accepted work. A rejection or explicit consent does, including
    // reject→accept while an older HTTP reply is held on another dispatcher.
    private val authorizationEpochs = mutableMapOf<String, Long>()
    internal fun authorizationEpoch(id: String): Long? = synchronized(validationLock) {
        if (id in rejected) null else authorizationEpochs[id] ?: 0L
    }
    private fun retireAuthorization(id: String) {
        authorizationEpochs[id] = (authorizationEpochs[id] ?: 0L) + 1
    }
    private val validating = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Waits only for probes already in flight; it never chooses Connect or authenticates by itself. */
    public suspend fun awaitValidation(id: String) { validating.first { (it[id] ?: 0) == 0 } }
    /** A background reader may have learned stronger lineage than a legacy credential contains. */
    public fun acceptedIdentity(context: Context, id: String, fallback: String): String {
        val store = DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
        return try { ServerIdentityStore(store.database).read(id).ifEmpty { fallback } } finally { store.close() }
    }
    private fun nextGeneration(id: String): Long = ((generations[id] ?: 0) + 1).also { generations[id] = it }
    private val rejected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val seeded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    public fun seed(context: Context, id: String, identity: String) {
        if (id in seeded) return
        val store = DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
        try {
            val identities = ServerIdentityStore(store.database)
            store.database.transaction {
                if (identities.read(id).isEmpty()) identities.accept(id, identity)
            }
            seeded += id
        } finally { store.close() }
    }
    public fun requireValidation(id: String) { synchronized(validationLock) {
        nextGeneration(id)
        mutableVerified.update { it - id }
    } }
    internal fun isRejected(id: String): Boolean = id in rejected
    public fun isVerified(id: String): Boolean = id in mutableVerified.value && !isRejected(id)
    internal suspend fun validate(id: String, validation: suspend () -> DomainError?): DomainError? {
        val generation = synchronized(validationLock) {
            // An independent background probe does not revoke established contact while in flight.
            // New connections explicitly requireValidation; each ID-using worker still gates its own batch.
            validating.update { it + (id to ((it[id] ?: 0) + 1)) }
            nextGeneration(id)
        }
        try {
            val error = validation()
            var notifyRejected = false
            val outcome = synchronized(validationLock) {
                if (generations[id] != generation) {
                    if (id in rejected) DomainError.Protocol.ServerIdentityChanged else DomainError.Transport.Cancelled
                } else {
                    if (error == DomainError.Protocol.ServerIdentityChanged && rejected.add(id)) {
                        retireAuthorization(id)
                        notifyRejected = true
                    }
                    if (error == null && id !in rejected) mutableVerified.update { it + id }
                    else mutableVerified.update { it - id }
                    if (id in rejected) DomainError.Protocol.ServerIdentityChanged else error
                }
            }
            // Do not acquire the composition registry's lock while holding the gate's lock.
            if (notifyRejected) AndroidLibraryReader.currentFor(id)?.rejectServerIdentity()
            return outcome
        } catch (failure: Throwable) {
            synchronized(validationLock) {
                if (generations[id] == generation) mutableVerified.update { it - id }
            }
            throw failure
        } finally {
            synchronized(validationLock) {
                validating.update { counts ->
                    val remaining = (counts[id] ?: 1) - 1
                    if (remaining == 0) counts - id else counts + (id to remaining)
                }
            }
        }
    }
    public fun accept(context: Context, providerInstanceId: String, identity: String) {
        val generation = synchronized(validationLock) {
            retireAuthorization(providerInstanceId)
            nextGeneration(providerInstanceId)
        }
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
