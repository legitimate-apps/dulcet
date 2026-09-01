package com.legitimateapps.dulcet

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.InvalidServerUrlReason
import java.net.URI
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
}

internal data class AccountFailurePresentation(
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val recovery: Int,
    val messageArgument: String? = null,
)

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
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        gateway = CoreAccountConnectionGateway(),
        credentialStore = AndroidAccountCredentialStore(application),
        defaults = ActiveChannelDefaults,
    )

    private val mutableState = MutableStateFlow(
        AccountConnectUiState(serverUrl = defaults.preconfiguredServerUrl.orEmpty()),
    )
    val state: StateFlow<AccountConnectUiState> = mutableState.asStateFlow()
    private var connectionJob: Job? = null
    private var connectionGeneration: Long = 0

    init {
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
            val result = gateway.connect(
                AccountConnectionRequest(
                    serverUrl = submitted.serverUrl,
                    username = submitted.username,
                    password = submitted.password,
                    allowLocalHttp = submitted.allowLocalHttp,
                ),
            )
            if (submittedGeneration != connectionGeneration) return@launch
            mutableState.update { current ->
                when (result) {
                    is AccountConnectionResult.Connected -> {
                        val serverName = result.account.serverType.ifBlank {
                            runCatching { URI(result.account.normalizedBaseUrl).host }
                                .getOrNull()
                                .orEmpty()
                                .ifBlank { result.account.normalizedBaseUrl }
                        }
                        try {
                            credentialStore.save(
                                serverName = serverName,
                                serverUrl = result.account.normalizedBaseUrl,
                                username = submitted.username,
                                password = submitted.password,
                                allowLocalHttp = submitted.allowLocalHttp,
                            )
                            current.copy(
                                serverUrl = result.account.normalizedBaseUrl,
                                status = AccountConnectStatus.Connected(serverName),
                            )
                        } catch (_: CredentialStoreException) {
                            current.copy(status = AccountConnectStatus.PersistenceFailed)
                        }
                    }
                    is AccountConnectionResult.Failed -> current.copy(
                        status = AccountConnectStatus.Failed(result.error.accountFailurePresentation()),
                    )
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
            mutableState.update { it.copy(status = AccountConnectStatus.PersistenceFailed) }
        }
    }

    private fun updateFields(transform: AccountConnectUiState.() -> AccountConnectUiState) {
        if (mutableState.value.status != AccountConnectStatus.Connecting) {
            mutableState.update(transform)
        }
    }
}

internal fun DomainError.accountFailurePresentation(): AccountFailurePresentation = when (this) {
    is DomainError.Input.InvalidServerUrl -> if (
        reason == InvalidServerUrlReason.UnsupportedInternationalizedHost
    ) {
        AccountFailurePresentation(
            R.string.error_internationalized_url_title,
            R.string.error_internationalized_url_message,
            R.string.error_internationalized_url_recovery,
        )
    } else AccountFailurePresentation(
        R.string.error_invalid_url_title,
        R.string.error_invalid_url_message,
        R.string.error_invalid_url_recovery,
    )
    DomainError.Transport.Unreachable -> AccountFailurePresentation(
        R.string.error_unreachable_title,
        R.string.error_unreachable_message,
        R.string.error_unreachable_recovery,
    )
    DomainError.Transport.Timeout -> AccountFailurePresentation(
        R.string.error_timeout_title,
        R.string.error_timeout_message,
        R.string.error_timeout_recovery,
    )
    DomainError.Transport.Cancelled -> AccountFailurePresentation(
        R.string.error_cancelled_title,
        R.string.error_cancelled_message,
        R.string.error_cancelled_recovery,
    )
    is DomainError.Security.TlsUntrusted -> AccountFailurePresentation(
        R.string.error_tls_title,
        R.string.error_tls_message,
        R.string.error_tls_recovery,
    )
    DomainError.Security.LocalExceptionViolated,
    is DomainError.Security.RedirectRejected,
    -> AccountFailurePresentation(
        R.string.error_security_title,
        R.string.error_security_message,
        R.string.error_security_recovery,
    )
    DomainError.Protocol.MalformedEnvelope,
    is DomainError.Protocol.UnexpectedContentType,
    DomainError.Protocol.UnexpectedBinary,
    is DomainError.Protocol.Incompatible,
    DomainError.Protocol.NotASubsonicServer,
    -> AccountFailurePresentation(
        R.string.error_protocol_title,
        R.string.error_protocol_message,
        R.string.error_protocol_recovery,
    )
    is DomainError.Server.Busy,
    is DomainError.Server.Known,
    is DomainError.Server.Unknown,
    DomainError.Playback.NoPlayableSource,
    -> AccountFailurePresentation(
        R.string.error_server_title,
        R.string.error_server_message,
        R.string.error_server_recovery,
    )
    DomainError.Auth.InvalidCredentials,
    DomainError.Auth.TokenAuthUnsupported,
    DomainError.Auth.Forbidden,
    DomainError.Auth.UnsupportedAuthenticationChallenge,
    -> AccountFailurePresentation(
        R.string.error_auth_title,
        R.string.error_auth_message,
        R.string.error_auth_recovery,
    )
    is DomainError.Auth.CrossOriginRedirectRejected -> AccountFailurePresentation(
        R.string.error_cross_origin_title,
        R.string.error_cross_origin_message,
        R.string.error_cross_origin_recovery,
        messageArgument = targetHost.value,
    )
    is DomainError.CapabilityUnsupported -> AccountFailurePresentation(
        R.string.error_capability_title,
        R.string.error_capability_message,
        R.string.error_capability_recovery,
    )
}
