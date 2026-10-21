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
    replacement: AccountReplacement? = null,
): AccountConnectOutcome {
    // Preserve the selection snapshot across authentication. The confirmed canonical principal,
    // origin and server identity decide continuity; typed case and password refresh do not.
    val previousActive = try { store.load() } catch (_: CredentialStoreException) { null }
    val savedAccounts = try { store.savedAccounts() } catch (_: CredentialStoreException) { emptyList() }
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
                val policy = com.legitimateapps.dulcet.core.SavedServerPolicy
                val previous = savedAccounts.firstOrNull {
                    identity.canonicalServerUrl(it.serverUrl) == identity.canonicalServerUrl(result.account.normalizedBaseUrl) &&
                        (it.username == username || (it == previousActive && it.username == request.username && it.password == request.password))
                }
                if (store.load() != previousActive) return AccountConnectOutcome.Superseded
                val confirmsSaved = previous != null && policy.canContinue(previous.serverUrl, previous.username,
                    previous.serverIdentity, result.account.normalizedBaseUrl, username, result.account.serverIdentity)
                // Exact saved credentials can also learn a canonical spelling from getUser. An
                // unrelated typed spelling only continues when the server confirms the saved name.
                val learnsCanonical = previous != null && previous.username == request.username &&
                    previous.password == request.password && !policy.isServerReplacement(previous.serverIdentity, result.account.serverIdentity)
                val continuesSaved = confirmsSaved || learnsCanonical
                val confirmed = StoredAccount(previous?.id.orEmpty(), serverName, result.account.normalizedBaseUrl,
                    username, request.password, request.allowLocalHttp, result.account.serverIdentity)
                if (previous != null && !continuesSaved) {
                    if (replacement == null || !replacement.confirmAndRemove(previous, stillWanted))
                        return AccountConnectOutcome.Superseded
                }
                if (!stillWanted()) return AccountConnectOutcome.Superseded
                val changesSelection = previous?.id != previousActive?.id || !continuesSaved
                if (previousActive != null && changesSelection && (previous == null || continuesSaved) && replacement?.retirePlayback() == false)
                    return AccountConnectOutcome.PersistenceFailed
                val continued = if (continuesSaved) {
                    store.saveAuthenticatedContinuation(requireNotNull(previous), confirmed)
                } else null
                val saved = if (continued != null) {
                    if (continued.id != store.activeAccountId() && store.activate(continued.id) == null)
                        return AccountConnectOutcome.PersistenceFailed
                    continued
                } else store.saveAdditional(confirmed)
                // The person chose Connect: the library this opens reads the server (spec §13.1).
                SavedAccountConnection.connectedOnTheForm(saved.id)
                AccountConnectOutcome.Connected(serverName, result.account.normalizedBaseUrl)
            } catch (_: CredentialStoreException) {
                AccountConnectOutcome.PersistenceFailed
            }
        }
    }
}
