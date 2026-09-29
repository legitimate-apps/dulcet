package com.legitimateapps.dulcet

import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import android.content.Context
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchPresenter
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.RuntimeEnvironment
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign-out dialogs and the account entry, rendered (spec §14.7 step 2). */
@RunWith(RobolectricTestRunner::class)
class SignOutSheetTest {
    @get:Rule val compose = createComposeRule()
    private val pressed = mutableListOf<String>()
    private val callbacks = SignOutCallbacks(
        send = { pressed += "send" },
        signOut = { pressed += "signOut" },
        signOutAnyway = { pressed += "anyway" },
        stay = { pressed += "stay" },
        retry = { pressed += "retry" },
        dismissFailure = { pressed += "dismiss" },
    )

    @Test fun theOfferStatesEachKindOfOwedChangeAndOffersSendStayAndDiscard() {
        var state by mutableStateOf<SignOutState>(
            SignOutState.Offer(changes(plays = 3, edits = 1), afterSend = false, hidesAccount = false))
        compose.setContent { SignOutDialogs(state, callbacks) }
        compose.onNodeWithText("3 plays haven’t reached your server.").assertIsDisplayed()
        compose.onNodeWithText("1 favourite, rating or playlist change hasn’t reached your server.").assertIsDisplayed()
        compose.onNodeWithText("Signing out without sending them discards them.").assertIsDisplayed()

        compose.onNodeWithTag("signout.send").performClick()
        compose.onNodeWithTag("signout.stay").performClick()
        compose.onNodeWithTag("signout.discard").performClick()
        assertEquals(listOf("send", "stay", "signOut"), pressed)

        state = SignOutState.Offer(changes(plays = 1, edits = 0), afterSend = true, hidesAccount = true)
        compose.onNodeWithText("Some of it couldn’t be sent").assertIsDisplayed()
        compose.onNodeWithText("1 play hasn’t reached your server.").assertIsDisplayed()
        compose.onNodeWithTag("signout.pending.edits").assertDoesNotExist()
        compose.onNodeWithText("Try sending again").assertIsDisplayed()
    }

    /** A change only the library reader holds is sent by it too, so Send is offered for it alone. */
    @Test fun changesWithoutPlaysAreStillOfferedASend() {
        compose.setContent {
            SignOutDialogs(SignOutState.Offer(changes(plays = 0, edits = 2), false, false), callbacks)
        }
        compose.onNodeWithText("2 favourite, rating and playlist changes haven’t reached your server.").assertIsDisplayed()
        compose.onNodeWithTag("signout.send").performClick()
        compose.onNodeWithTag("signout.discard").assertIsDisplayed()
        assertEquals(listOf("send"), pressed)
    }

    @Test fun theConfirmationUnknownAndFailureDialogsEachAnswerOnlyTheirOwnChoices() {
        var state by mutableStateOf<SignOutState>(SignOutState.Confirm("music.example.invalid"))
        compose.setContent { SignOutDialogs(state, callbacks) }
        compose.onNodeWithText("Sign out of music.example.invalid?").assertIsDisplayed()
        compose.onNodeWithTag("signout.signout").performClick()

        state = SignOutState.Unknown(hidesAccount = false)
        compose.onNodeWithTag("signout.unknown").assertIsDisplayed()
        compose.onNodeWithTag("signout.anyway").performClick()
        compose.onNodeWithTag("signout.stay").performClick()

        state = SignOutState.Failed
        compose.onNodeWithTag("signout.retry").performClick()

        state = SignOutState.Idle
        compose.onNodeWithTag("signout.failed").assertDoesNotExist()
        assertEquals(listOf("signOut", "anyway", "stay", "retry"), pressed)
    }

    @Test fun aSendInProgressCanBeAbandoned() {
        compose.setContent { SigningOutSurface(SignOutState.Sending, callbacks) }
        compose.onNodeWithText("Sending plays and changes to your server…").assertIsDisplayed()
        compose.onNodeWithTag("signout.stay").performClick()
        assertEquals(listOf("stay"), pressed)
    }

    @Test fun theAccountEntryOpensTheAccountAndItsSignOutStartsSigningOut() {
        val actions = AccountActions("music.example.invalid", "listener") { pressed += "request" }
        compose.setContent {
            CompositionLocalProvider(LocalAccountActions provides actions) { NavigationBar { AccountNavigationItem() } }
        }
        compose.onNodeWithTag("account.open").performClick()
        compose.onNodeWithText("Signed in to music.example.invalid as listener.").assertIsDisplayed()
        compose.onNodeWithTag("account.signout").performClick()
        assertEquals(listOf("request"), pressed)
        compose.onNodeWithTag("account.dialog").assertDoesNotExist()
    }

    @Test fun aSignOutInterruptedByTheProcessFinishesAtLaunchBehindTheProgressScreen() {
        val application = RuntimeEnvironment.getApplication()
        val log = mutableListOf<String>()
        val store = AndroidAccountCredentialStore(application, PlaintextCipher())
        val journal = AccountRemovalJournal(application)
        try {
            val account = store.save("Fixture Server", "https://music.example.invalid", "fixture-user", "fixture-password", false)
            assertTrue(journal.begin(account.id), "The previous process chose to sign out and died")
            val data = FakeAccountData(log).apply { removeGate = CompletableDeferred() }
            val viewModel = AccountConnectViewModel(application, object : AccountConnectionGateway {
                override suspend fun connect(request: AccountConnectionRequest) =
                    AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
            }, store, object : ChannelDefaults { override val preconfiguredServerUrl: String? = null },
                accountData = data, playbackRelease = FakePlayback(log), removalJournal = journal)
            val dependencies = StoreBackedDependencies(store)
            compose.setContent { AccountConnectScreen(viewModel, dependencies) }

            compose.onNodeWithTag("signout.progress").assertIsDisplayed()
            compose.onNodeWithTag("account.submit").assertDoesNotExist()
            assertNull(store.load(), "The credential is deleted before anything is shown")

            data.removeGate?.complete(Unit)
            compose.waitUntil(5_000) { journal.pending().isEmpty() }
            // The connect form scrolls, so its button may sit below a small test window: it exists.
            compose.onNodeWithTag("account.submit").assertExists()
            compose.onNodeWithTag("signout.progress").assertDoesNotExist()
            assertEquals(listOf("data.remove:${account.id}", "data.sweep:null"), log)
            assertEquals(0, dependencies.accountsHandedOut, "The signing-out account must never reach the app")
        } finally {
            application.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit()
            application.getSharedPreferences(AccountRemovalJournal.PREFERENCES_NAME, 0).edit().clear().commit()
        }
    }

    /**
     * A saved account whose record cannot be read (spec §14.7) is still signed out of from the
     * connect form it leaves behind: the removal needs only its id, never its credentials.
     */
    @Test fun anAccountWhoseRecordCannotBeReadIsSignedOutFromTheConnectForm() {
        val application = RuntimeEnvironment.getApplication()
        val log = mutableListOf<String>()
        val cipher = PlaintextCipher()
        val store = AndroidAccountCredentialStore(application, cipher)
        val journal = AccountRemovalJournal(application)
        try {
            val account = store.save("Fixture Server", "https://music.example.invalid", "fixture-user", "fixture-password", false)
            cipher.unreadable = true
            val viewModel = AccountConnectViewModel(application, object : AccountConnectionGateway {
                override suspend fun connect(request: AccountConnectionRequest) =
                    AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
            }, store, object : ChannelDefaults { override val preconfiguredServerUrl: String? = null },
                accountData = FakeAccountData(log), playbackRelease = FakePlayback(log), removalJournal = journal)
            compose.setContent { AccountConnectScreen(viewModel, StoreBackedDependencies(store)) }

            // The form scrolls, so the button may sit below a small test window: click it semantically.
            compose.onNodeWithTag("account.signout-unreadable").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { viewModel.signOut.state.value is SignOutState.Unknown }
            compose.onNodeWithTag("signout.unknown").assertIsDisplayed()
            compose.onNodeWithTag("signout.anyway").performClick()
            compose.waitUntil(5_000) { viewModel.signOut.state.value == SignOutState.Idle && journal.pending().isEmpty() }

            assertEquals(null, store.activeAccountId(), "The unreadable account is signed out")
            assertTrue(account.id !in cipher.keys, "Its key is deleted")
            assertTrue("data.remove:${account.id}" in log, "Its data is removed: $log")
            compose.onNodeWithTag("account.signout-unreadable").assertDoesNotExist()
        } finally {
            application.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit()
            application.getSharedPreferences(AccountRemovalJournal.PREFERENCES_NAME, 0).edit().clear().commit()
        }
    }

    /** The negative control: with no account saved, the form offers nothing to sign out of. */
    @Test fun withNoAccountSavedTheConnectFormOffersNoSignOut() {
        val application = RuntimeEnvironment.getApplication()
        val store = AndroidAccountCredentialStore(application, PlaintextCipher())
        val viewModel = AccountConnectViewModel(application, object : AccountConnectionGateway {
            override suspend fun connect(request: AccountConnectionRequest) =
                AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
        }, store, object : ChannelDefaults { override val preconfiguredServerUrl: String? = null },
            accountData = FakeAccountData(mutableListOf()), playbackRelease = FakePlayback(mutableListOf()),
            removalJournal = AccountRemovalJournal(application))
        compose.setContent { AccountConnectScreen(viewModel, StoreBackedDependencies(store)) }
        compose.onNodeWithTag("account.submit").assertExists()
        compose.onNodeWithTag("account.signout-unreadable").assertDoesNotExist()
    }

    @Test fun withoutASignedInAccountThereIsNoAccountEntry() {
        compose.setContent { NavigationBar { AccountNavigationItem() } }
        compose.onNodeWithTag("account.open").assertDoesNotExist()
    }
}

/** Reads the account the way production does, from the credential store, and counts what it hands out. */
private class StoreBackedDependencies(private val store: AccountCredentialStore) : SearchHostDependencies {
    var accountsHandedOut = 0
    override fun loadAccount(context: Context): SearchAccount? = store.load()?.let {
        accountsHandedOut += 1
        SearchAccount(it.id, it.serverUrl, it.username, it.password, it.allowLocalHttp)
    }
    override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter = error("not reached")
}
