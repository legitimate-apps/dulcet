package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.legitimateapps.dulcet.*
import com.legitimateapps.dulcet.core.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvSavedServerSwitcherTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val cipher = object : AccountCredentialCipher {
        override fun encrypt(id: String, plaintext: ByteArray) = plaintext.reversedArray()
        override fun decrypt(id: String, payload: ByteArray) = payload.reversedArray()
        override fun delete(id: String) {}
    }
    private val store = AndroidAccountCredentialStore(context, cipher)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun clean() { scope.cancel(); store.savedAccountIds().forEach(store::remove) }

    @Test fun accountScreenSwitchesBothWaysWithRemoteSelectionAndOffersAddServer() {
        val a = store.saveAdditional(StoredAccount("", "First Server", "https://first.invalid", "alice", "secret", false))
        val b = store.saveAdditional(StoredAccount("", "Second Server", "https://second.invalid", "bob", "secret", false))
        var releases = 0
        var added = false
        val data = object : AccountDataGateway {
            override suspend fun pendingChanges(account: StoredAccount) = PendingChanges(emptySet(), 0)
            override suspend fun send(account: StoredAccount, changes: Boolean) = pendingChanges(account)
            override suspend fun removeAccountData(serverId: String) {}
            override suspend fun sweep(activeAccountId: () -> String?): Set<String> = emptySet()
        }
        val signOut = AccountSignOut(scope, store, data, PlaybackRelease { true }, AccountRemovalJournal(context)) {}
        compose.setContent {
            val selection = rememberCoroutineScope()
            TvAccountHost(signOut, b.id, store.savedAccounts(),
                selectServer = { id -> selection.launch { selectSavedServer(id, store, PlaybackRelease { releases++; true }) } },
                addServer = { added = true }) { TvSignOutEntry() }
        }
        compose.onNodeWithTag("tv.account.server.${a.id}").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { store.activeAccountId() == a.id }
        compose.onNodeWithTag("tv.account.server.${b.id}").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { store.activeAccountId() == b.id }
        assertEquals(setOf(a.id, b.id), store.savedAccountIds())
        assertEquals(2, releases)
        compose.onNodeWithTag("tv.account.add-server").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(added)
    }
    @Test fun remoteCanCancelTheCountedServerReplacementWithoutChangingTheAccount() {
        val original = store.saveAdditional(StoredAccount("", "Navidrome", "https://music.invalid", "alice", "secret", false,
            ServerIdentityPolicy.identity("Navidrome")))
        var connected = false
        compose.setContent {
            TvConnectScreen({ AccountConnectionResult.Connected(ConnectedAccount("https://music.invalid", false, "1.16.1", true,
                "Dulcet Server", "1", CapabilitySet(emptyMap(), UserPermissions(true, true, false, false, false), true), emptyList(), "alice")) },
                store) { connected = true }
        }
        compose.onNodeWithTag("tv.connect.server").selectWithRemote().performTextInput(original.serverUrl)
        compose.onNodeWithTag("tv.connect.username").selectWithRemote().performTextInput(original.username)
        compose.onNodeWithTag("tv.connect.password").selectWithRemote().performTextInput(original.password)
        compose.onNodeWithTag("tv.connect.submit").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { compose.onNodeWithTag("account.replacement").assertExists() }.isSuccess
        }
        compose.onNodeWithText("The server at this address has changed. Replacing it removes 0 downloads from this device, along with its saved library and queue. Cancel keeps your account unchanged.").assertIsDisplayed()
        compose.onNodeWithTag("account.replacement.cancel").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(original, store.load())
        assertFalse(connected)
    }

}
