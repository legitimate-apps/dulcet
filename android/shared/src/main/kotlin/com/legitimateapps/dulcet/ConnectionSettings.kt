package com.legitimateapps.dulcet

import android.content.Context
import android.content.SharedPreferences
import com.legitimateapps.dulcet.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class ConnectionSettingsState(
    val snapshot: ConnectionSnapshot? = null,
    val canUpgrade: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
)

internal interface ConnectionSettingsBackend {
    fun account(): StoredAccount?
    fun anchor(account: StoredAccount): OwnershipAnchor
    fun restore(anchor: OwnershipAnchor): ConnectionSnapshot
    suspend fun enroll(request: ConnectionEnrollmentRequest): ConnectionEnrollmentResult
    fun remove(anchor: OwnershipAnchor, routeId: String): ConnectionSnapshot
}

/** A displayed snapshot carries its owner into every mutation and asynchronous publication. */
public class ConnectionSettings internal constructor(
    private val scope: CoroutineScope,
    private val backend: ConnectionSettingsBackend,
) : AutoCloseable {
    public constructor(context: Context, scope: CoroutineScope) : this(scope, object : ConnectionSettingsBackend {
        private val credentials = AndroidAccountCredentialStore(context.applicationContext)
        private val connections = AndroidConnections(context.applicationContext)
        override fun account() = credentials.load()
        override fun anchor(account: StoredAccount) = OwnershipAnchor(account.id, account.serverUrl,
            account.username, credentials.acceptedServerIdentity(account))
        override fun restore(anchor: OwnershipAnchor) = connections.restore(anchor)
        override suspend fun enroll(request: ConnectionEnrollmentRequest) = connections.enroll(request)
        override fun remove(anchor: OwnershipAnchor, routeId: String) = connections.remove(anchor, routeId)
    }) {
        preferences = context.applicationContext.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }
    private var preferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> restore() }
    private val mutable = MutableStateFlow(ConnectionSettingsState())
    public val state = mutable.asStateFlow()
    private var generation = 0L
    private var closed = false

    private fun currentAnchor(): OwnershipAnchor? = backend.account()?.let(backend::anchor)

    public fun restore() {
        if (closed) return
        try {
            val account = backend.account()
            val owner = account?.let(backend::anchor)
            val sameOwner = owner != null && owner == mutable.value.snapshot?.anchor
            if (!sameOwner) generation++
            mutable.value = if (owner == null) ConnectionSettingsState()
                else ConnectionSettingsState(backend.restore(owner), requireNotNull(account).serverUrl.startsWith("http://"),
                    busy = sameOwner && mutable.value.busy)
        } catch (_: Exception) { generation++; mutable.value = ConnectionSettingsState(error = true) }
    }

    public fun enroll(owner: OwnershipAnchor, httpsUrl: String? = null) {
        if (closed || mutable.value.busy) return
        val account = backend.account()
        if (account == null || backend.anchor(account) != owner || mutable.value.snapshot?.anchor != owner) { restore(); return }
        val operation = ++generation
        mutable.value = mutable.value.copy(busy = true, error = false)
        scope.launch {
            try {
                val result = backend.enroll(ConnectionEnrollmentRequest(owner,
                    AccountConnectionRequest(httpsUrl ?: account.serverUrl, account.username, account.password, false)))
                if (closed || operation != generation) return@launch
                if (currentAnchor() != owner) { restore(); return@launch }
                mutable.value = when (result) {
                    is ConnectionEnrollmentResult.Enrolled -> ConnectionSettingsState(result.snapshot,
                        account.serverUrl.startsWith("http://"))
                    is ConnectionEnrollmentResult.Failed -> mutable.value.copy(busy = false, error = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && operation == generation) {
                    if (currentAnchor() != owner) restore() else mutable.value = mutable.value.copy(error = true)
                }
            } finally { if (!closed && operation == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    public fun remove(owner: OwnershipAnchor, routeId: String) {
        if (closed) return
        try {
            if (currentAnchor() != owner || mutable.value.snapshot?.anchor != owner) { restore(); return }
            mutable.value = mutable.value.copy(snapshot = backend.remove(owner, routeId), error = false)
        } catch (_: Exception) { restore() }
    }

    override fun close() {
        closed = true; generation++
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = null
    }
}
