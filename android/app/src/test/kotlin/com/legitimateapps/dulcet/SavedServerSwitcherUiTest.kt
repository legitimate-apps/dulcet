package com.legitimateapps.dulcet

import android.content.Context
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.search.*
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class SavedServerSwitcherUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val store = AndroidAccountCredentialStore(context, PlaintextCipher())
    @After fun clean() {
        store.savedAccountIds().forEach(store::remove)
        context.getSharedPreferences(AccountRemovalJournal.PREFERENCES_NAME, 0).edit().clear().commit()
    }

    @Test fun accountMenuSwitchesSavedServersAndOffersAddWithoutSettingsNavigation() {
        val a = store.saveAdditional(StoredAccount("", "First Server", "https://first.invalid", "alice", "secret", false))
        val b = store.saveAdditional(StoredAccount("", "Second Server", "https://second.invalid", "bob", "secret", false))
        var added = false
        var releases = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val actions = AccountActions("Second Server", "bob", savedServers = store.savedAccounts(),
                selectServer = { id -> scope.launch { selectSavedServer(id, store, PlaybackRelease { releases++; true }) } },
                addServer = { added = true }, requestSignOut = {})
            CompositionLocalProvider(LocalAccountActions provides actions) { NavigationBar { AccountNavigationItem() } }
        }
        compose.onNodeWithTag("account.open").performClick()
        compose.onNodeWithTag("account.server.${a.id}").performClick()
        compose.waitUntil(5_000) { store.activeAccountId() == a.id }
        assertEquals(setOf(a.id, b.id), store.savedAccountIds())
        compose.onNodeWithTag("account.open").performClick()
        compose.onNodeWithTag("account.server.${b.id}").performClick()
        compose.waitUntil(5_000) { store.activeAccountId() == b.id }
        assertEquals(2, releases)
        compose.onNodeWithTag("account.open").performClick()
        compose.onNodeWithTag("account.add-server").performClick()
        assertTrue(added)
    }

    @Test fun connectFormShowsDownloadCountAndCancelKeepsTheOriginalAccount() {
        val original = store.saveAdditional(StoredAccount("", "Navidrome", "https://music.invalid", "alice", "secret", false,
            ServerIdentityPolicy.identity("Navidrome")))
        val viewModel = AccountConnectViewModel(context, AccountConnectionGateway {
            AccountConnectionResult.Connected(ConnectedAccount("https://music.invalid", false, "1.16.1", true,
                "Dulcet Server", "1", CapabilitySet(emptyMap(), UserPermissions(true, true, false, false, false), true), emptyList(), "alice"))
        }, store, object : ChannelDefaults { override val preconfiguredServerUrl: String? = null },
            accountData = FakeAccountData(mutableListOf()), playbackRelease = PlaybackRelease { error("Cancel must not retire playback") })
        val dependencies = object : SearchHostDependencies {
            override fun loadAccount(context: Context): SearchAccount? = null
            override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter = error("No library before consent")
        }
        compose.setContent { AccountConnectScreen(viewModel, dependencies) }
        compose.onNodeWithTag("account.submit").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            viewModel.replacement.offer.value != null || viewModel.state.value.status != AccountConnectStatus.Connecting
        }
        assertNotNull(viewModel.replacement.offer.value, "The submitted state is ${viewModel.state.value.status}")
        compose.onNodeWithTag("account.replacement").assertIsDisplayed()
        compose.onNodeWithText("The server at this address has changed. Replacing it removes 0 downloads from this device, along with its saved library and queue. Cancel keeps your account unchanged.").assertIsDisplayed()
        compose.onNodeWithTag("account.replacement.cancel").performClick()
        compose.waitUntil(5_000) { viewModel.state.value.status == AccountConnectStatus.Idle }
        assertEquals(original, store.load())
        compose.onNodeWithTag("account.replacement").assertDoesNotExist()
    }
}
