package com.legitimateapps.dulcet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.DomainError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun interface AccountConnectionGateway {
    suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult
}

private class CoreAccountConnectionGateway(
    private val connector: AccountConnector = AccountConnector(),
) : AccountConnectionGateway {
    override suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult =
        connector.connect(request)
}

internal sealed interface AccountConnectStatus {
    data object Idle : AccountConnectStatus
    data object Connecting : AccountConnectStatus
    data class Saved(val serverName: String) : AccountConnectStatus
    data class Connected(val serverName: String) : AccountConnectStatus
    data class Failed(val presentation: AccountFailurePresentation) : AccountConnectStatus
    data object PersistenceFailed : AccountConnectStatus
    /** The saved account's record could not be read at launch (its Keystore key lost, say). */
    data object Unreadable : AccountConnectStatus
}


internal data class AccountConnectUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val allowLocalHttp: Boolean = false,
    val status: AccountConnectStatus = AccountConnectStatus.Idle,
) {
    override fun toString(): String =
        "AccountConnectUiState(serverUrl=<redacted>, username=<redacted>, " +
            "password=<redacted>, allowLocalHttp=$allowLocalHttp, status=$status)"
}

internal class AccountConnectViewModel internal constructor(
    application: Application,
    private val gateway: AccountConnectionGateway,
    private val credentialStore: AccountCredentialStore,
    defaults: ChannelDefaults,
    accountData: AccountDataGateway = CoreAccountDataGateway(application),
    playbackRelease: PlaybackRelease = ServicePlaybackRelease(application),
    removalJournal: AccountRemovalJournal = AccountRemovalJournal(application),
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        gateway = CoreAccountConnectionGateway(),
        credentialStore = AndroidAccountCredentialStore(application),
        defaults = ActiveChannelDefaults,
    )

    private val initialServerUrl = defaults.preconfiguredServerUrl.orEmpty()
    private val mutableState = MutableStateFlow(AccountConnectUiState(serverUrl = initialServerUrl))
    val state: StateFlow<AccountConnectUiState> = mutableState.asStateFlow()
    private var connectionJob: Job? = null
    private var connectionGeneration: Long = 0

    /** Signing out and removing the account (spec §14.7). */
    val signOut = AccountSignOut(
        scope = viewModelScope,
        credentials = credentialStore,
        accountData = accountData,
        playback = playbackRelease,
        journal = removalJournal,
        onSignedOut = ::clearAfterSignOut,
    )

    init {
        // Before anything is restored: a credential whose sign-out the previous process began is
        // deleted here, synchronously, so no screen reads it. The rest of that removal, and the sweep
        // of other accounts' data, run in the background (AccountSignOut.resumeInterrupted).
        signOut.resumeInterrupted()
        restoreSavedAccount()
    }

    fun updateServerUrl(value: String) = updateFields { copy(serverUrl = value) }
    fun updateUsername(value: String) = updateFields { copy(username = value) }
    fun updatePassword(value: String) = updateFields { copy(password = value) }
    fun updateAllowLocalHttp(value: Boolean) = updateFields { copy(allowLocalHttp = value) }

    fun submitOrCancel() {
        if (mutableState.value.status == AccountConnectStatus.Connecting) {
            connectionGeneration += 1
            connectionJob?.cancel()
            connectionJob = null
            mutableState.update { it.copy(status = AccountConnectStatus.Idle) }
            return
        }
        val submitted = mutableState.value
        connectionGeneration += 1
        val submittedGeneration = connectionGeneration
        mutableState.update { it.copy(status = AccountConnectStatus.Connecting) }
        connectionJob = viewModelScope.launch {
            val outcome = connectAndSaveAccount(
                AccountConnectionRequest(
                    serverUrl = submitted.serverUrl,
                    username = submitted.username,
                    password = submitted.password,
                    allowLocalHttp = submitted.allowLocalHttp,
                ),
                gateway::connect,
                credentialStore,
                stillWanted = { submittedGeneration == connectionGeneration },
            )
            if (submittedGeneration != connectionGeneration || outcome == AccountConnectOutcome.Superseded) return@launch
            mutableState.update { current ->
                when (outcome) {
                    is AccountConnectOutcome.Connected -> current.copy(
                        serverUrl = outcome.normalizedBaseUrl,
                        status = AccountConnectStatus.Connected(outcome.serverName),
                    )
                    AccountConnectOutcome.PersistenceFailed ->
                        current.copy(status = AccountConnectStatus.PersistenceFailed)
                    is AccountConnectOutcome.Failed -> current.copy(
                        status = AccountConnectStatus.Failed(outcome.error.accountFailurePresentation()),
                    )
                    AccountConnectOutcome.Superseded -> current
                }
            }
            if (submittedGeneration == connectionGeneration) connectionJob = null
        }
    }

    private fun restoreSavedAccount() {
        try {
            val saved = credentialStore.load() ?: return
            mutableState.value = AccountConnectUiState(
                serverUrl = saved.serverUrl,
                username = saved.username,
                password = saved.password,
                allowLocalHttp = saved.allowLocalHttp,
                status = AccountConnectStatus.Saved(saved.serverName),
            )
        } catch (_: CredentialStoreException) {
            mutableState.update { it.copy(status = AccountConnectStatus.Unreadable) }
        }
    }

    /**
     * The signed-out account leaves nothing in the form: the restored password in particular would
     * otherwise stay in memory, and a later Connect would send it again.
     */
    private fun clearAfterSignOut() {
        connectionGeneration += 1
        connectionJob?.cancel()
        connectionJob = null
        mutableState.value = AccountConnectUiState(serverUrl = initialServerUrl)
    }

    private fun updateFields(transform: AccountConnectUiState.() -> AccountConnectUiState) {
        if (mutableState.value.status != AccountConnectStatus.Connecting) {
            mutableState.update(transform)
        }
    }
}
