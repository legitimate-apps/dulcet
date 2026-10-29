package com.legitimateapps.dulcet

import android.content.Context
import com.legitimateapps.dulcet.core.*
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

/** Shared account action state; route addresses never enter the saved credential store. */
public class ConnectionSettings(context: Context, private val scope: CoroutineScope) {
    private val credentials = AndroidAccountCredentialStore(context.applicationContext)
    private val connections = AndroidConnections(context.applicationContext)
    private val mutable = MutableStateFlow(ConnectionSettingsState())
    public val state = mutable.asStateFlow()

    private fun anchor(account: StoredAccount) = OwnershipAnchor(account.id, account.serverUrl,
        account.username, credentials.acceptedServerIdentity(account))

    public fun restore() {
        try {
            val account = credentials.load() ?: return
            mutable.value = ConnectionSettingsState(connections.restore(anchor(account)),
                account.serverUrl.startsWith("http://"))
        } catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
    }

    public fun enroll(httpsUrl: String? = null) {
        if (mutable.value.busy) return
        scope.launch {
            mutable.value = mutable.value.copy(busy = true, error = false)
            try {
                val account = credentials.load() ?: return@launch
                val result = connections.enroll(ConnectionEnrollmentRequest(anchor(account),
                    AccountConnectionRequest(httpsUrl ?: account.serverUrl, account.username, account.password, false)))
                mutable.value = when (result) {
                    is ConnectionEnrollmentResult.Enrolled -> ConnectionSettingsState(result.snapshot,
                        account.serverUrl.startsWith("http://"))
                    is ConnectionEnrollmentResult.Failed -> mutable.value.copy(busy = false, error = true)
                }
            } catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, error = true) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    public fun remove(routeId: String) {
        try {
            val account = credentials.load() ?: return
            mutable.value = mutable.value.copy(snapshot = connections.remove(anchor(account), routeId), error = false)
        } catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
    }
}
