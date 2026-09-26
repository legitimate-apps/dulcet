package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.AccountCredentialStore
import com.legitimateapps.dulcet.AccountDataGateway
import com.legitimateapps.dulcet.AccountRemovalJournal
import com.legitimateapps.dulcet.AccountSignOut
import com.legitimateapps.dulcet.PendingChanges
import com.legitimateapps.dulcet.PlaybackRelease
import com.legitimateapps.dulcet.SignOutState
import com.legitimateapps.dulcet.StoredAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The TV's sign-out entry and dialogs (spec §14.7), driven through the shared [AccountSignOut]. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvAccountSignOutTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val application = RuntimeEnvironment.getApplication()
    private val store = MemoryStore()
    private val data = QuietAccountData()
    private val journal = AccountRemovalJournal(application.getSharedPreferences("tv-signout-test", 0))
    private var signedOut = 0
    private val signOut = AccountSignOut(scope, store, data, PlaybackRelease { true }, journal) { signedOut += 1 }

    @After fun clean() {
        scope.cancel()
        application.getSharedPreferences("tv-signout-test", 0).edit().clear().commit()
    }

    @Test fun withoutASavedAccountThereIsNoSignOutEntry() {
        compose.setContent { TvAccountHost(signOut, null) { Text("app") } }
        compose.onNodeWithTag("tv.account.signout").assertDoesNotExist()
    }

    @Test fun connectingShowsTheEntryWithoutARestart() {
        var account by mutableStateOf<Any?>(null)
        compose.setContent { TvAccountHost(signOut, account) { Text("app") } }
        compose.onNodeWithTag("tv.account.signout").assertDoesNotExist()
        account = store.save("Fixture Server", "https://music.example.invalid", "listener", "tv-password", false).id
        compose.onNodeWithTag("tv.account.signout").assertIsDisplayed()
    }

    @Test fun theEntryAsksFirstStayKeepsTheAccountAndSigningOutRemovesItBehindTheProgressScreen() {
        val account = store.save("Fixture Server", "https://music.example.invalid", "listener", "tv-password", false)
        compose.setContent { TvAccountHost(signOut, account.id) { Text("app") } }

        press("tv.account.signout")
        compose.onNodeWithTag("signout.confirm").assertIsDisplayed()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("signout.stay").assertIsFocused() }.isSuccess }
        press("signout.stay")
        compose.onNodeWithTag("signout.confirm").assertDoesNotExist()
        assertEquals(account.id, store.load()?.id, "Stay signed in keeps the account")

        data.removeGate = CompletableDeferred()
        press("tv.account.signout")
        press("signout.signout")
        compose.waitUntil(5_000) { signOut.state.value == SignOutState.Removing }
        compose.onNodeWithTag("signout.progress").assertIsDisplayed()
        compose.onNodeWithTag("tv.account.signout").assertDoesNotExist()
        assertNull(store.load(), "The credential is deleted before the data")

        data.removeGate!!.complete(Unit)
        compose.waitUntil(5_000) { signedOut == 1 }
        compose.onNodeWithTag("signout.progress").assertDoesNotExist()
        compose.onNodeWithTag("tv.account.signout").assertDoesNotExist()
        assertEquals(listOf("remove:${account.id}"), data.log)
    }

    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private class MemoryStore : AccountCredentialStore {
        var saved: StoredAccount? = null
        override fun load(): StoredAccount? = saved
        override fun save(serverName: String, serverUrl: String, username: String, password: String, allowLocalHttp: Boolean) =
            StoredAccount("tv-account", serverName, serverUrl, username, password, allowLocalHttp).also { saved = it }
        override fun delete() { saved = null }
    }

    private class QuietAccountData : AccountDataGateway {
        val log = mutableListOf<String>()
        var removeGate: CompletableDeferred<Unit>? = null
        override suspend fun pendingChanges(account: StoredAccount) = PendingChanges(emptySet(), 0)
        override suspend fun send(account: StoredAccount, changes: Boolean) = PendingChanges(emptySet(), 0)
        override suspend fun removeAccountData(serverId: String) { log += "remove:$serverId"; removeGate?.await() }
        override suspend fun sweep(activeAccountId: () -> String?): Set<String> = emptySet()
    }
}
