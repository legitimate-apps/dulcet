package com.legitimateapps.dulcet.core

import android.content.Context
import kotlinx.coroutines.runBlocking
import okio.FileSystem

/** Android serialization for the shared durable identity policy; no network or file deletion. */
public object AndroidMediaBindingIdentity {
    /** Called only after authenticating the exact saved request, before saving its confirmed name. */
    public fun adoptAuthenticatedAccount(
        context: Context,
        previous: PlaybackEndpointAccount,
        confirmed: PlaybackEndpointAccount,
    ): Boolean {
        if (!MediaBindingIdentity.isValidAccount(previous) || !MediaBindingIdentity.isValidAccount(confirmed)) return false
        return try {
            runBlocking(AndroidDownloadController.databaseDispatcher) {
                val store = DulcetDriverFactory(context.applicationContext).openDulcetDatabase()
                try {
                    DownloadPolicyEngine(store.database, DownloadFileStore(
                        AndroidAccountData.downloadRootFor(context.applicationContext).path, FileSystem.SYSTEM,
                    )).adoptAuthenticatedAccount(previous, confirmed)
                } finally { store.close() }
            }
        } catch (_: Exception) { false }
    }

    internal fun sameAccount(
        store: DulcetDatabaseStore,
        first: PlaybackEndpointAccount,
        second: PlaybackEndpointAccount,
    ): Boolean {
        if (!MediaBindingIdentity.isValidAccount(first) || !MediaBindingIdentity.isValidAccount(second)) return false
        if (MediaBindingIdentity.sameAccount(first, second)) return true
        return runBlocking(AndroidDownloadController.databaseDispatcher) {
            // This engine only reads the durable identity. No file-store operation is performed.
            DownloadPolicyEngine(store.database, DownloadFileStore(".", FileSystem.SYSTEM))
                .isSameMediaBinding(first, second)
        }
    }
}
