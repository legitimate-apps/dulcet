package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.AccountCredentialStore
import com.legitimateapps.dulcet.AccountDataGateway
import com.legitimateapps.dulcet.AccountRemovalJournal
import com.legitimateapps.dulcet.AccountSignOut
import com.legitimateapps.dulcet.CredentialStoreException
import com.legitimateapps.dulcet.PendingChanges
import com.legitimateapps.dulcet.PlaybackRelease
import com.legitimateapps.dulcet.SignOutState
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.CapabilitySet
import com.legitimateapps.dulcet.core.ConnectedAccount
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.UserPermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sign out from the connect screen (spec §14.7): a saved account whose record cannot be read never
 * reaches the app, so this is the only place it can be signed out. Composed as the activity does —
 * the account host around the connect screen, which gives way to the app once an account connects —
 * on a 1080p television (960 x 540 dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvConnectScreenSignOutTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val application = RuntimeEnvironment.getApplication()
    private val store = UnreadableStore()
    private val data = RecordingAccountData()
    private val journal = AccountRemovalJournal(application.getSharedPreferences("tv-connect-signout-test", 0))
    private var signedOut = 0
    private val signOut = AccountSignOut(scope, store, data, PlaybackRelease { true }, journal) { signedOut += 1 }

    @After fun clean() {
        scope.cancel()
        application.getSharedPreferences("tv-connect-signout-test", 0).edit().clear().commit()
    }

    /** Should-fix 1: the unreadable account is offered Sign out here, and signing out removes it by its id. */
    @Test fun anUnreadableSavedAccountIsSignedOutFromTheConnectScreen() {
        store.saveUnreadable(BROKEN)
        host { connected() }

        press("tv.account.signout")
        compose.onNodeWithTag("signout.unknown").assertExists()
        awaitFocused("signout.stay")
        press("signout.anyway")
        compose.waitUntil(5_000) { signedOut == 1 }

        assertNull(store.activeAccountId(), "The unreadable account's record is gone")
        assertEquals(listOf("remove:$BROKEN"), data.log, "Its data is removed by its own id")
        compose.onNodeWithTag("tv.account.signout").assertDoesNotExist()
        compose.onNodeWithTag("tv.connect.server").assertExists()
    }

    /**
     * Should-fix 2: with a status line under Connect, the form is taller than a 1080p screen. The
     * remote's DOWN still reaches Sign out, and Sign out is then on the screen, not below its edge.
     */
    @Test fun signOutIsReachedWithTheRemoteAndSeenAt1080pUnderAStatusLine() {
        store.saveUnreadable(BROKEN)
        host { AccountConnectionResult.Failed(DomainError.Auth.InvalidCredentials) }
        compose.onNodeWithTag("tv.connect.server").performTextInput("https://music.example.invalid")
        press("tv.connect.submit")
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("tv.connect.status").assertTextContains("not accepted", substring = true) }.isSuccess
        }

        compose.onNodeWithTag("tv.connect.server").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val walk = listOf("tv.connect.username", "tv.connect.password", "tv.connect.allow-local-http",
            "tv.connect.submit", "tv.account.signout")
        for (next in walk) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
            compose.onNodeWithTag(next).assertIsFocused()
        }
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val entry = compose.onNodeWithTag("tv.account.signout").fetchSemanticsNode().boundsInRoot
        println("TV CONNECT SIGNOUT BOUNDS screen=$screen entry=$entry")
        assertTrue(entry.top >= screen.top && entry.bottom <= screen.bottom,
            "Focused Sign out at $entry must be on the $screen screen")
    }

    /**
     * Should-fix 3, one order: Sign out is asked for while a connect is in flight, and the connect
     * finishes first, saving a new account. Signing out afterwards removes the unreadable account it
     * was asked for — never the one just connected, which stays saved and is what the app then shows.
     */
    @Test fun aConnectFinishingDuringSignOutKeepsTheNewAccountAndRemovesOnlyTheOldOne() {
        store.saveUnreadable(BROKEN)
        val gate = CompletableDeferred<Unit>()
        var started = false
        host { started = true; gate.await(); connected() }
        fillAndSubmit()
        compose.waitUntil(5_000) { started }

        press("tv.account.signout")
        compose.onNodeWithTag("signout.unknown").assertExists()
        gate.complete(Unit)
        compose.waitUntil(5_000) { store.activeAccountId() != BROKEN }
        val connected = store.activeAccountId()
        compose.waitUntil(5_000) { exists("app.connected") }

        press("signout.anyway")
        compose.waitUntil(5_000) { signedOut == 1 }
        assertEquals(connected, store.activeAccountId(), "The account connected meanwhile stays saved")
        assertEquals(connected, store.load()?.id)
        assertEquals(listOf("remove:$BROKEN"), data.log, "Only the account asked about loses its data")
        assertTrue(exists("app.connected"), "The app goes on showing the connected account")
    }

    /**
     * Should-fix 3, the other order: the sign-out runs while a connect is in flight. The signing-out
     * screen replaces the form, which abandons the attempt, so a server answering afterwards saves
     * nothing: the removed account is not resurrected and no account appears that the form no longer
     * shows being connected.
     */
    @Test fun aConnectAnsweredAfterTheSignOutSavesNothing() {
        store.saveUnreadable(BROKEN)
        val gate = CompletableDeferred<Unit>()
        var started = false
        var calls = 0
        // Ignores cancellation while it waits, as a connector blocked in I/O would.
        host { calls++; started = true; withContext(NonCancellable) { gate.await() }; connected() }
        fillAndSubmit()
        compose.waitUntil(5_000) { started }

        data.removeGate = CompletableDeferred()
        press("tv.account.signout")
        press("signout.anyway")
        compose.waitUntil(5_000) { signOut.state.value == SignOutState.Removing }
        compose.onNodeWithTag("signout.progress").assertExists()
        gate.complete(Unit)
        compose.waitForIdle()
        data.removeGate!!.complete(Unit)
        compose.waitUntil(5_000) { signedOut == 1 }

        assertEquals(1, calls)
        assertNull(store.activeAccountId(), "Nothing is saved: neither the removed account nor the abandoned connect")
        assertEquals(listOf("remove:$BROKEN"), data.log)
        compose.onNodeWithTag("tv.connect.submit").assertTextContains("Connect")
    }

    /** The activity's shape: the host around the connect screen, which gives way once connected. */
    private fun host(connect: suspend (AccountConnectionRequest) -> AccountConnectionResult) {
        compose.setContent {
            MaterialTheme {
                var account by remember { mutableStateOf(store.activeAccountId()) }
                var readable by remember { mutableStateOf(false) }
                TvAccountHost(signOut, account) {
                    if (!readable) {
                        TvConnectScreen(connect, store) { account = store.activeAccountId(); readable = true }
                    } else {
                        Text("connected", modifier = Modifier.testTag("app.connected"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fillAndSubmit() {
        compose.onNodeWithTag("tv.connect.server").performTextInput("https://music.example.invalid")
        compose.onNodeWithTag("tv.connect.username").performTextInput("listener")
        compose.onNodeWithTag("tv.connect.password").performTextInput("tv-password-canary")
        press("tv.connect.submit")
    }

    /** As a remote does: focus on the control, then the centre key. */
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun awaitFocused(tag: String) {
        val ok = runCatching {
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
                }.getOrDefault(false)
            }
        }.isSuccess
        assertTrue(ok, "$tag must hold focus")
    }

    private fun connected() = AccountConnectionResult.Connected(ConnectedAccount(
        normalizedBaseUrl = "https://music.example.invalid", allowsLocalHttp = false, protocolVersion = "1.16.1",
        openSubsonic = true, serverType = "Music", serverVersion = "fixture",
        capabilities = CapabilitySet(emptyMap(), UserPermissions(false, false, false, false, false), false),
        requests = emptyList()))

    /**
     * Holds one account at a time, as the production store does, whose record may be unreadable: its
     * id is still known without it. Saving replaces the saved account with a new id.
     */
    private class UnreadableStore : AccountCredentialStore {
        private var saved: StoredAccount? = null
        private var unreadableId: String? = null
        private var next = 0

        fun saveUnreadable(id: String) { saved = null; unreadableId = id }

        override fun load(): StoredAccount? {
            if (unreadableId != null) throw CredentialStoreException(CredentialStoreException.Reason.CorruptRecord)
            return saved
        }

        override fun activeAccountId(): String? = unreadableId ?: saved?.id

        override fun save(serverName: String, serverUrl: String, username: String, password: String, allowLocalHttp: Boolean): StoredAccount {
            unreadableId = null
            return StoredAccount("connected-${++next}", serverName, serverUrl, username, password, allowLocalHttp).also { saved = it }
        }

        /** Deletes whatever is saved now, as the production store does: it is not told an id. */
        override fun delete() { saved = null; unreadableId = null }
    }

    private class RecordingAccountData : AccountDataGateway {
        val log = mutableListOf<String>()
        var removeGate: CompletableDeferred<Unit>? = null
        override suspend fun pendingChanges(account: StoredAccount) = PendingChanges(emptySet(), 0)
        override suspend fun send(account: StoredAccount, changes: Boolean) = PendingChanges(emptySet(), 0)
        override suspend fun removeAccountData(serverId: String) { log += "remove:$serverId"; removeGate?.await() }
        override suspend fun sweep(activeAccountId: () -> String?): Set<String> = emptySet()
    }

    private companion object {
        const val BROKEN = "unreadable-account"
    }
}

