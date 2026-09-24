package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.DomainError
import java.net.URI

/** What connecting an account ended in. Carries no credential and no URL beyond the saved server's. */
public sealed interface AccountConnectOutcome {
    public data class Connected(val serverName: String, val normalizedBaseUrl: String) : AccountConnectOutcome
    public data class Failed(val error: DomainError) : AccountConnectOutcome
    public data object PersistenceFailed : AccountConnectOutcome
    /** The caller withdrew the attempt (cancelled, or replaced it) before it finished. Nothing was saved. */
    public data object Superseded : AccountConnectOutcome
}

/**
 * The one connect sequence every Android shell uses: the core connector decides, and only an
 * account it accepted is saved, with the credentials exactly as submitted. A save that fails leaves
 * nothing half-connected. Presentation, cancellation and field editing belong to the caller.
 */
public suspend fun connectAndSaveAccount(
    request: AccountConnectionRequest,
    connect: suspend (AccountConnectionRequest) -> AccountConnectionResult,
    store: AccountCredentialStore,
    stillWanted: () -> Boolean = { true },
): AccountConnectOutcome {
    val result = connect(request)
    // Checked after the connector returns and before anything is saved: an attempt the user
    // cancelled or replaced must never leave credentials behind, even when the server accepted them.
    if (!stillWanted()) return AccountConnectOutcome.Superseded
    return when (result) {
        is AccountConnectionResult.Failed -> AccountConnectOutcome.Failed(result.error)
        is AccountConnectionResult.Connected -> {
            val serverName = result.account.serverType.ifBlank {
                runCatching { URI(result.account.normalizedBaseUrl).host }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { result.account.normalizedBaseUrl }
            }
            try {
                store.save(
                    serverName = serverName,
                    serverUrl = result.account.normalizedBaseUrl,
                    username = request.username,
                    password = request.password,
                    allowLocalHttp = request.allowLocalHttp,
                )
                AccountConnectOutcome.Connected(serverName, result.account.normalizedBaseUrl)
            } catch (_: CredentialStoreException) {
                AccountConnectOutcome.PersistenceFailed
            }
        }
    }
}
