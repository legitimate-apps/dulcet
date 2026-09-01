package com.legitimateapps.dulcet

import android.app.Application
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
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
