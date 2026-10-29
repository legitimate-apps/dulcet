package com.legitimateapps.dulcet.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

/** Additive route storage under the saved account; no account/media rebind is exposed here. */
public class AndroidConnections(context: Context) {
    private val context = context.applicationContext

    /** Called only while saving a newly allocated UUID account, before consumers start. */
    public fun initializeFreshOwnership(anchor: OwnershipAnchor): Boolean = runBlocking(AndroidDownloadController.databaseDispatcher) {
        val store = DulcetDriverFactory(context).openDulcetDatabase()
        try { initializeFreshConnectionOwnership(store.database, anchor) } finally { store.close() }
    }

    /** Call synchronously while restoring saved credentials, before starting consumers. */
    public fun restore(anchor: OwnershipAnchor): ConnectionSnapshot = withStore { it.restore(anchor) }

    public suspend fun enroll(request: ConnectionEnrollmentRequest): ConnectionEnrollmentResult = withContext(Dispatchers.IO) {
        val store = DulcetDriverFactory(context).openDulcetDatabase()
        try { ConnectionEnrollmentService(store.database, nowEpochMilliseconds = System::currentTimeMillis).enroll(request) }
        finally { store.close() }
    }

    public fun remove(anchor: OwnershipAnchor, routeId: String): ConnectionSnapshot = withStore { it.remove(anchor, routeId) }

    private fun <T> withStore(block: (ConnectionEnrollmentService) -> T): T {
        val store = DulcetDriverFactory(context).openDulcetDatabase()
        return try { block(ConnectionEnrollmentService(store.database)) } finally { store.close() }
    }
}
