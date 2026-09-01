package com.legitimateapps.dulcet

import android.app.Application
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.CapabilitySet
import com.legitimateapps.dulcet.core.ConnectedAccount
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.UserPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AccountConnectAndroidConformanceTest {
    @Test
    fun conf09aProgressIsPublishedAndTheActiveOperationIsCancellable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gateway = CancellableGateway()
            val viewModel = viewModel(gateway = gateway)
            viewModel.updateServerUrl("https://music.example.invalid")
            viewModel.updateUsername("listener")
            viewModel.updatePassword("fixture-password")

            viewModel.submitOrCancel()

            assertIs<AccountConnectStatus.Connecting>(viewModel.state.value.status)
            runCurrent()
            assertEquals(
                listOf(
                    AccountConnectionRequest(
                        serverUrl = "https://music.example.invalid",
                        username = "listener",
                        password = "fixture-password",
                        allowLocalHttp = false,
                    ),
                ),
                gateway.requests,
            )

            viewModel.submitOrCancel()
            assertIs<AccountConnectStatus.Idle>(viewModel.state.value.status)
            runCurrent()

            assertTrue(
                gateway.cancelled,
                "CONF-09a reached the progress state but did not cancel the active gateway call",
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun conf09bEveryDeclaredDistinctRenderStateIsReachable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val observed = mutableSetOf<String>()

            observed += viewModel(ImmediateGateway(connectedResult()))
                .state.value.status.renderStateName()

            observed += viewModel(
                gateway = ImmediateGateway(connectedResult()),
                credentialStore = MemoryCredentialStore(persisted = storedAccount()),
            ).state.value.status.renderStateName()

            val connectingGateway = CancellableGateway()
            val connecting = viewModel(connectingGateway)
            connecting.submitOrCancel()
            observed += connecting.state.value.status.renderStateName()
            connecting.submitOrCancel()
            runCurrent()

            val connected = viewModel(ImmediateGateway(connectedResult()))
            connected.submitOrCancel()
            runCurrent()
            observed += connected.state.value.status.renderStateName()

            val failed = viewModel(
                ImmediateGateway(AccountConnectionResult.Failed(DomainError.Transport.Unreachable)),
            )
            failed.submitOrCancel()
            runCurrent()
            observed += failed.state.value.status.renderStateName()

            val persistenceFailed = viewModel(
                gateway = ImmediateGateway(connectedResult()),
                credentialStore = FailingSaveCredentialStore,
            )
            persistenceFailed.submitOrCancel()
            runCurrent()
            observed += persistenceFailed.state.value.status.renderStateName()

            assertEquals(
                setOf("idle", "connecting", "saved", "connected", "failed", "persistence-failed"),
                observed,
                "CONF-09b did not reach every declared Android account-connect render state",
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        gateway: AccountConnectionGateway,
        credentialStore: AccountCredentialStore = MemoryCredentialStore(),
    ): AccountConnectViewModel = AccountConnectViewModel(
        application = RuntimeEnvironment.getApplication<Application>(),
        gateway = gateway,
        credentialStore = credentialStore,
        defaults = object : ChannelDefaults {
            override val preconfiguredServerUrl: String? = null
        },
    )
}

private fun AccountConnectStatus.renderStateName(): String = when (this) {
    AccountConnectStatus.Idle -> "idle"
    AccountConnectStatus.Connecting -> "connecting"
    is AccountConnectStatus.Saved -> "saved"
    is AccountConnectStatus.Connected -> "connected"
    is AccountConnectStatus.Failed -> "failed"
    AccountConnectStatus.PersistenceFailed -> "persistence-failed"
}

private fun storedAccount(): StoredAccount = StoredAccount(
    id = "stored-account",
    serverName = "Music",
    serverUrl = "https://music.example.invalid",
    username = "listener",
    password = "fixture-password",
    allowLocalHttp = false,
)

private fun connectedResult(): AccountConnectionResult = AccountConnectionResult.Connected(
    ConnectedAccount(
        normalizedBaseUrl = "https://music.example.invalid",
        allowsLocalHttp = false,
        protocolVersion = "1.16.1",
        openSubsonic = true,
        serverType = "Music",
        serverVersion = "fixture",
        capabilities = CapabilitySet(
            extensions = emptyMap(),
            permissions = UserPermissions(
                download = false,
                playlist = false,
                share = false,
                jukebox = false,
                admin = false,
            ),
            legacySubsonic = false,
        ),
        requests = emptyList(),
    ),
)

private class ImmediateGateway(
    private val result: AccountConnectionResult,
) : AccountConnectionGateway {
    val requests = mutableListOf<AccountConnectionRequest>()

    override suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult {
        requests += request
        return result
    }
}

private class CancellableGateway : AccountConnectionGateway {
    val requests = mutableListOf<AccountConnectionRequest>()
    var cancelled: Boolean = false
        private set

    override suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult {
        requests += request
        try {
            awaitCancellation()
        } finally {
            cancelled = true
        }
    }
}

private class MemoryCredentialStore(
    var persisted: StoredAccount? = null,
) : AccountCredentialStore {
    override fun load(): StoredAccount? = persisted

    override fun save(
        serverName: String,
        serverUrl: String,
        username: String,
        password: String,
        allowLocalHttp: Boolean,
    ): StoredAccount = StoredAccount(
        id = "android-account-control",
        serverName = serverName,
        serverUrl = serverUrl,
        username = username,
        password = password,
        allowLocalHttp = allowLocalHttp,
    ).also { persisted = it }

    override fun delete() {
        persisted = null
    }
}

private object FailingSaveCredentialStore : AccountCredentialStore {
    override fun load(): StoredAccount? = null

    override fun save(
        serverName: String,
        serverUrl: String,
        username: String,
        password: String,
        allowLocalHttp: Boolean,
    ): StoredAccount = throw CredentialStoreException(
        CredentialStoreException.Reason.SecureStorageUnavailable,
    )

    override fun delete() = Unit
}
