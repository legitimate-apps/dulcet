package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.library.SavedAccountConnection
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
 * account it accepted is saved, using a server-confirmed canonical username when supplied. A save that fails leaves
 * nothing half-connected. Presentation, cancellation and field editing belong to the caller.
 */
public suspend fun connectAndSaveAccount(
    request: AccountConnectionRequest,
    connect: suspend (AccountConnectionRequest) -> AccountConnectionResult,
    store: AccountCredentialStore,
    stillWanted: () -> Boolean = { true },
): AccountConnectOutcome {
    // Preserve the exact credential snapshot that was actually authenticated. A server-confirmed
    // spelling is not evidence of continuity for a different typed username or password.
    val previous = try { store.load() } catch (_: CredentialStoreException) { null }
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
                val username = result.account.canonicalUsername ?: request.username
                val identity = com.legitimateapps.dulcet.core.MediaBindingIdentity
                val previousOrigin = previous?.let { identity.canonicalServerUrl(it.serverUrl) }
                val continuesSaved = previous != null && previous.username == request.username &&
                    previous.password == request.password && previousOrigin != null &&
                    previousOrigin == identity.canonicalServerUrl(request.serverUrl) &&
                    previousOrigin == identity.canonicalServerUrl(result.account.normalizedBaseUrl)
                if (continuesSaved && store.load() != previous) return AccountConnectOutcome.Superseded
                val continued = if (continuesSaved) {
                    val authenticated = requireNotNull(previous)
                    store.saveAuthenticatedContinuation(authenticated, StoredAccount(
                        authenticated.id, serverName, result.account.normalizedBaseUrl,
                        username, request.password, request.allowLocalHttp,
                    ))
                } else null
                val saved = continued ?: store.save(
                    serverName = serverName,
                    serverUrl = result.account.normalizedBaseUrl,
                    username = username,
                    password = request.password,
                    allowLocalHttp = request.allowLocalHttp,
                )
                // The person chose Connect: the library this opens reads the server (spec §13.1).
                SavedAccountConnection.connectedOnTheForm(saved.id)
                AccountConnectOutcome.Connected(serverName, result.account.normalizedBaseUrl)
            } catch (_: CredentialStoreException) {
                AccountConnectOutcome.PersistenceFailed
            }
        }
    }
}
