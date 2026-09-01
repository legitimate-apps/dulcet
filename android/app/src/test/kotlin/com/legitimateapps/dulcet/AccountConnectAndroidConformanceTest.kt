package com.legitimateapps.dulcet

import android.app.Application
import android.content.Context
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.core.CapabilityFeature
import com.legitimateapps.dulcet.core.CapabilitySet
import com.legitimateapps.dulcet.core.ConnectedAccount
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.InvalidServerUrlReason
import com.legitimateapps.dulcet.core.ObservedPlaybackContentType
import com.legitimateapps.dulcet.core.ProtocolVersionLevel
import com.legitimateapps.dulcet.core.RedirectRejectionReason
import com.legitimateapps.dulcet.core.RedirectTargetHost
import com.legitimateapps.dulcet.core.TlsTrustFailure
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun conf09cEveryDomainErrorHasAnActionablePresentation() {
        val application = RuntimeEnvironment.getApplication()
        val errors = allAccountPresentationErrors()
        val presentations = errors.map(DomainError::accountFailurePresentation)

        assertEquals(errors.size, presentations.size)
        presentations.forEachIndexed { index, presentation ->
            val title = application.getString(presentation.title)
            val message = presentation.messageArgument?.let {
                application.getString(presentation.message, it)
            } ?: application.getString(presentation.message)
            val recovery = application.getString(presentation.recovery)

            assertTrue(title.isNotBlank(), "CONF-09c error #$index has no title")
            assertTrue(message.isNotBlank(), "CONF-09c error #$index has no explanatory message")
            assertTrue(recovery.isNotBlank(), "CONF-09c error #$index has no recovery action")
        }

        val internationalized = DomainError.Input.InvalidServerUrl(
            InvalidServerUrlReason.UnsupportedInternationalizedHost,
        ).accountFailurePresentation()
        assertTrue(
            application.getString(internationalized.recovery).contains("punycode", ignoreCase = true),
            "CONF-09c lost the decided internationalized-host remedy",
        )

        val tls = DomainError.Security.TlsUntrusted(
            TlsTrustFailure.CertificateChain,
        ).accountFailurePresentation()
        val tlsRecovery = application.getString(tls.recovery)
        assertTrue(tlsRecovery.contains("CA", ignoreCase = true))
        assertTrue(tlsRecovery.contains("operating-system", ignoreCase = true))

        val targetHost = "login.example.invalid"
        val crossOrigin = DomainError.Auth.CrossOriginRedirectRejected(
            RedirectTargetHost(targetHost),
        ).accountFailurePresentation()
        val crossOriginMessage = application.getString(
            crossOrigin.message,
            requireNotNull(crossOrigin.messageArgument),
        )
        val crossOriginRecovery = application.getString(crossOrigin.recovery)
        assertTrue(crossOriginMessage.contains(targetHost))
        assertTrue('/' !in crossOriginMessage && '?' !in crossOriginMessage)
        assertTrue(crossOriginRecovery.contains("/rest/"))
        assertTrue(crossOriginRecovery.contains("SSO", ignoreCase = true))
    }

    @Test
    fun conf10aUnavailableSecureStorageFailsClosedWithoutFallback() {
        val application = RuntimeEnvironment.getApplication()
        val cleared = application.getSharedPreferences(
            AndroidAccountCredentialStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        assertTrue(cleared, "CONF-10a could not establish an empty record-store precondition")
        val cipher = UnavailableCredentialCipher()
        val store = AndroidAccountCredentialStore(application, cipher)

        val failure = assertFailsWith<CredentialStoreException> {
            store.save(
                serverName = "Music",
                serverUrl = "https://music.example.invalid",
                username = "listener",
                password = "fixture-password",
                allowLocalHttp = false,
            )
        }

        assertEquals(
            CredentialStoreException.Reason.SecureStorageUnavailable,
            failure.reason,
            "CONF-10a secure-storage failure lost its typed reason",
        )
        assertEquals(1, cipher.encryptAttempts)
        assertFalse(
            store.hasActiveAccountPointer(),
            "CONF-10a selected an account whose secure credential write failed",
        )
        assertEquals(
            0,
            store.storedEntryCount(),
            "CONF-10a persisted credential material after the secure cipher failed",
        )
    }

    @Test
    fun conf10bRelaunchPrefillsCredentialsAndWaitsForExplicitReconnect() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val persisted = storedAccount()
            val gateway = CancellableGateway()

            // A new ViewModel is the Android shell's process/relaunch restoration boundary.
            val relaunched = viewModel(
                gateway = gateway,
                credentialStore = MemoryCredentialStore(persisted),
            )

            val restored = relaunched.state.value
            val saved = assertIs<AccountConnectStatus.Saved>(restored.status)
            assertEquals(persisted.serverName, saved.serverName)
            assertEquals(persisted.serverUrl, restored.serverUrl)
            assertEquals(persisted.username, restored.username)
            assertEquals(persisted.password, restored.password)
            assertEquals(persisted.allowLocalHttp, restored.allowLocalHttp)
            assertTrue(
                gateway.requests.isEmpty(),
                "CONF-10b performed a network request while restoring persisted credentials",
            )

            relaunched.submitOrCancel()
            assertIs<AccountConnectStatus.Connecting>(relaunched.state.value.status)
            runCurrent()

            assertEquals(
                listOf(
                    AccountConnectionRequest(
                        serverUrl = persisted.serverUrl,
                        username = persisted.username,
                        password = persisted.password,
                        allowLocalHttp = persisted.allowLocalHttp,
                    ),
                ),
                gateway.requests,
                "CONF-10b did not wait for explicit reconnect or changed the restored request",
            )
            relaunched.submitOrCancel()
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        gateway: AccountConnectionGateway,
        credentialStore: AccountCredentialStore = MemoryCredentialStore(),
    ): AccountConnectViewModel = AccountConnectViewModel(
        application = RuntimeEnvironment.getApplication(),
        gateway = gateway,
        credentialStore = credentialStore,
        defaults = object : ChannelDefaults {
            override val preconfiguredServerUrl: String? = null
        },
    )
}

private class UnavailableCredentialCipher : AccountCredentialCipher {
    var encryptAttempts: Int = 0
        private set

    override fun encrypt(id: String, plaintext: ByteArray): ByteArray {
        encryptAttempts += 1
        throw IllegalStateException("AndroidKeyStore unavailable to this caller")
    }

    override fun decrypt(id: String, payload: ByteArray): ByteArray =
        error("CONF-10a must not attempt a read after its failed write")

    override fun delete(id: String) = Unit
}

private fun allAccountPresentationErrors(): List<DomainError> = buildList {
    addAll(InvalidServerUrlReason.entries.map { DomainError.Input.InvalidServerUrl(it) })
    add(DomainError.Transport.Unreachable)
    add(DomainError.Transport.Timeout)
    add(DomainError.Transport.Cancelled)
    addAll(TlsTrustFailure.entries.map { DomainError.Security.TlsUntrusted(it) })
    add(DomainError.Security.LocalExceptionViolated)
    addAll(
        RedirectRejectionReason.entries.map {
            DomainError.Security.RedirectRejected(it)
        },
    )
    add(DomainError.Protocol.MalformedEnvelope)
    add(
        DomainError.Protocol.UnexpectedContentType(
            actual = ObservedPlaybackContentType.Other,
            expected = AudioContainer.Mp3,
        ),
    )
    add(DomainError.Protocol.UnexpectedBinary)
    add(
        DomainError.Protocol.Incompatible(
            clientVersion = ProtocolVersionLevel(1, 16),
            serverVersion = ProtocolVersionLevel(2, 0),
        ),
    )
    add(DomainError.Protocol.NotASubsonicServer)
    add(DomainError.Server.Busy(5.seconds))
    add(DomainError.Server.Known(40))
    add(DomainError.Server.Unknown(999))
    add(DomainError.Auth.InvalidCredentials)
    add(DomainError.Auth.TokenAuthUnsupported)
    add(DomainError.Auth.Forbidden)
    add(DomainError.Auth.UnsupportedAuthenticationChallenge)
    add(
        DomainError.Auth.CrossOriginRedirectRejected(
            RedirectTargetHost("login.example.invalid"),
        ),
    )
    add(DomainError.Playback.NoPlayableSource)
    addAll(CapabilityFeature.entries.map { DomainError.CapabilityUnsupported(it) })
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
