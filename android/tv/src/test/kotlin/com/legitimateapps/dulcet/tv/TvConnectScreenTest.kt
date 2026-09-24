package com.legitimateapps.dulcet.tv

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import com.legitimateapps.dulcet.AccountCredentialStore
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.CapabilitySet
import com.legitimateapps.dulcet.core.ConnectedAccount
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.UserPermissions
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvConnectScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun acceptedAccountIsSavedAsSubmittedAndOpensTheApp() {
        val store = MemoryStore()
        val requests = mutableListOf<AccountConnectionRequest>()
        var connected = 0
        compose.setContent {
            TvConnectScreen({ requests += it; connectedResult() }, store) { connected++ }
        }
        fill()
        press("tv.connect.allow-local-http")
        press("tv.connect.submit")
        compose.waitUntil(5_000) { connected == 1 }
        assertEquals(listOf(AccountConnectionRequest("http://10.0.2.2:4747", "listener", "tv-password-canary", true)), requests)
        val saved = store.saved!!
        assertEquals("https://music.example.invalid", saved.serverUrl, "The connector's normalized address is saved")
        assertEquals("listener", saved.username)
        assertEquals("tv-password-canary", saved.password)
        assertTrue(saved.allowLocalHttp)
    }

    @Test fun aRejectedAccountIsNotSavedAndSaysWhy() {
        val store = MemoryStore()
        var connected = 0
        compose.setContent {
            TvConnectScreen({ AccountConnectionResult.Failed(DomainError.Auth.InvalidCredentials) }, store) { connected++ }
        }
        fill()
        press("tv.connect.submit")
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("tv.connect.status").assertTextContains("not accepted", substring = true) }.isSuccess
        }
        assertNull(store.saved)
        assertEquals(0, connected)
    }

    @Test fun aCancelledAttemptLeavesNothingSavedEvenIfTheServerAccepts() {
        val store = MemoryStore()
        val gate = CompletableDeferred<Unit>()
        var started = false
        var calls = 0
        var connected = 0
        compose.setContent {
            // Ignores cancellation, as a connector blocked in I/O would: only the check before
            // saving can keep this attempt's credentials out of storage.
            TvConnectScreen({ calls++; started = true; kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }; connectedResult() },
                store) { connected++ }
        }
        fill()
        press("tv.connect.submit")
        compose.waitUntil(5_000) { started }
        compose.onNodeWithTag("tv.connect.submit").assertTextContains("Cancel")
        press("tv.connect.submit")
        gate.complete(Unit)
        compose.waitForIdle()
        println("TV CANCEL calls=$calls")
        assertEquals(1, calls, "Cancel must not start a second attempt")
        assertNull(store.saved, "A cancelled attempt must never save credentials")
        assertEquals(0, connected)
        compose.onNodeWithTag("tv.connect.submit").assertTextContains("Connect")
    }

    @Test fun theRemoteMovesDownThroughEveryControlWithoutBeingTrappedInAField() {
        compose.setContent { TvConnectScreen({ connectedResult() }, MemoryStore()) {} }
        compose.waitForIdle()
        compose.onNodeWithTag("tv.connect.server").assertIsFocused()
        for (next in listOf("tv.connect.username", "tv.connect.password", "tv.connect.allow-local-http", "tv.connect.submit")) {
            compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
            compose.onNodeWithTag(next).assertIsFocused()
        }
    }

    /** A remote's select button on a focused control, which is how a TV user activates it. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).assertIsFocused()
        compose.onNodeWithTag(tag).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
    }

    private fun fill() {
        compose.onNodeWithTag("tv.connect.server").performTextInput("http://10.0.2.2:4747")
        compose.onNodeWithTag("tv.connect.username").performTextInput("listener")
        compose.onNodeWithTag("tv.connect.password").performTextInput("tv-password-canary")
    }

    private class MemoryStore : AccountCredentialStore {
        var saved: StoredAccount? = null
        override fun load(): StoredAccount? = saved
        override fun save(serverName: String, serverUrl: String, username: String, password: String, allowLocalHttp: Boolean) =
            StoredAccount("tv-account", serverName, serverUrl, username, password, allowLocalHttp).also { saved = it }
        override fun delete() { saved = null }
    }

    private fun connectedResult() = AccountConnectionResult.Connected(ConnectedAccount(
        normalizedBaseUrl = "https://music.example.invalid", allowsLocalHttp = true, protocolVersion = "1.16.1",
        openSubsonic = true, serverType = "Music", serverVersion = "fixture",
        capabilities = CapabilitySet(emptyMap(), UserPermissions(false, false, false, false, false), false),
        requests = emptyList()))
}
