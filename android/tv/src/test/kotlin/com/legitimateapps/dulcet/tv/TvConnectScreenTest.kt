package com.legitimateapps.dulcet.tv

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import com.legitimateapps.dulcet.accountFailurePresentation
import com.legitimateapps.dulcet.statement
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.core.CapabilityFeature
import com.legitimateapps.dulcet.core.InvalidServerUrlReason
import com.legitimateapps.dulcet.core.ObservedPlaybackContentType
import com.legitimateapps.dulcet.core.ProtocolVersionLevel
import com.legitimateapps.dulcet.core.RedirectRejectionReason
import com.legitimateapps.dulcet.core.RedirectTargetHost
import com.legitimateapps.dulcet.core.TlsTrustFailure
import kotlin.time.Duration.Companion.seconds
import org.robolectric.RuntimeEnvironment
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

    /**
     * CONF-09c on the TV: every account error the connector can return is said on the TV screen as
     * its title, what happened and what to do, and the TLS, internationalized-host and cross-origin
     * redirect remedies keep their decided specifics.
     */
    @Test fun conf09cTheTvSaysEveryAccountErrorWithItsRemedy() {
        val resources = RuntimeEnvironment.getApplication().resources
        var next: DomainError = DomainError.Auth.InvalidCredentials
        compose.setContent { TvConnectScreen({ AccountConnectionResult.Failed(next) }, MemoryStore()) {} }
        fill()
        val said = allAccountErrors().associateWith { error ->
            next = error
            press("tv.connect.submit")
            val expected = error.accountFailurePresentation().statement(resources)
            compose.waitUntil(5_000) { statusText() == expected }
            checkNotNull(statusText())
        }
        said.forEach { (error, text) ->
            val lines = text.split('\n')
            assertEquals(2, lines.size, "$error: a title, then what happened and what to do")
            assertTrue(lines.all { it.isNotBlank() }, "$error is said with nothing to act on: $text")
        }
        fun saidFor(match: (DomainError) -> Boolean) = said.entries.first { match(it.key) }.value
        assertTrue(saidFor { it == DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.UnsupportedInternationalizedHost) }
            .contains("punycode", ignoreCase = true), "The internationalized-host remedy")
        val tls = saidFor { it is DomainError.Security.TlsUntrusted }
        assertTrue(tls.contains("CA") && tls.contains("operating-system", ignoreCase = true), "The TLS remedy: $tls")
        val crossOrigin = saidFor { it is DomainError.Auth.CrossOriginRedirectRejected }
        assertTrue(crossOrigin.contains("login.example.invalid") && crossOrigin.contains("/rest/") &&
            crossOrigin.contains("SSO", ignoreCase = true), "The cross-origin remedy: $crossOrigin")
    }

    private fun statusText(): String? = runCatching {
        compose.onNodeWithTag("tv.connect.status").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
    }.getOrNull()

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

    @Test fun aFieldTakesTextOnlyOnceTheCentreKeySelectsItSoTheRemotePassesOverItWithoutAKeyboard() {
        compose.setContent { TvConnectScreen({ connectedResult() }, MemoryStore()) {} }
        compose.waitForIdle()
        // The remote lands on the first field at launch, and passes down over the next: neither is
        // editable, so neither opens an input session or brings up the keyboard.
        compose.onNodeWithTag("tv.connect.server").assertIsFocused().assert(editable(false))
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        compose.onNodeWithTag("tv.connect.username").assertIsFocused().assert(editable(false))
        // The centre key selects it; leaving it makes it read-only again.
        compose.onNodeWithTag("tv.connect.username").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.onNodeWithTag("tv.connect.username").assertIsFocused().assert(editable(true))
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        compose.onNodeWithTag("tv.connect.password").assertIsFocused().assert(editable(false))
        compose.onNodeWithTag("tv.connect.username").assert(editable(false))
    }

    private fun editable(value: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, value)

    /** A remote's select button on a focused control, which is how a TV user activates it. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).assertIsFocused()
        compose.onNodeWithTag(tag).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
    }

    private fun fill() {
        compose.onNodeWithTag("tv.connect.server").selectWithRemote().performTextInput("http://10.0.2.2:4747")
        compose.onNodeWithTag("tv.connect.username").selectWithRemote().performTextInput("listener")
        compose.onNodeWithTag("tv.connect.password").selectWithRemote().performTextInput("tv-password-canary")
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

private fun allAccountErrors(): List<DomainError> = buildList {
    addAll(InvalidServerUrlReason.entries.map { DomainError.Input.InvalidServerUrl(it) })
    add(DomainError.Transport.Unreachable)
    add(DomainError.Transport.Timeout)
    add(DomainError.Transport.Cancelled)
    addAll(TlsTrustFailure.entries.map { DomainError.Security.TlsUntrusted(it) })
    add(DomainError.Security.LocalExceptionViolated)
    addAll(RedirectRejectionReason.entries.map { DomainError.Security.RedirectRejected(it) })
    add(DomainError.Protocol.MalformedEnvelope)
    add(DomainError.Protocol.UnexpectedContentType(actual = ObservedPlaybackContentType.Other, expected = AudioContainer.Mp3))
    add(DomainError.Protocol.UnexpectedBinary)
    add(DomainError.Protocol.Incompatible(clientVersion = ProtocolVersionLevel(1, 16), serverVersion = ProtocolVersionLevel(2, 0)))
    add(DomainError.Protocol.NotASubsonicServer)
    add(DomainError.Server.Busy(5.seconds))
    add(DomainError.Server.Known(40))
    add(DomainError.Server.Unknown(999))
    add(DomainError.Server.HttpStatus(414))
    add(DomainError.Auth.InvalidCredentials)
    add(DomainError.Auth.TokenAuthUnsupported)
    add(DomainError.Auth.Forbidden)
    add(DomainError.Auth.UnsupportedAuthenticationChallenge)
    add(DomainError.Auth.CrossOriginRedirectRejected(RedirectTargetHost("login.example.invalid")))
    add(DomainError.Playback.NoPlayableSource)
    addAll(CapabilityFeature.entries.map { DomainError.CapabilityUnsupported(it) })
}
