package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class AccountCanonicalUsernamePersistenceTest {
    @Test fun hostCredentialBoundaryKeepsBothCiphertextsReadableUntilTheAccountKeyIsDeleted() {
        val cipher = com.legitimateapps.dulcet.search.conformance.HostCredentialCipher()
        val id = "same-account-crypto-continuation"
        try {
            val old = "saved record".encodeToByteArray()
            val confirmed = "confirmed spelling record".encodeToByteArray()
            val first = cipher.encrypt(id, old)
            val second = cipher.encrypt(id, confirmed)
            assertContentEquals(old, cipher.decrypt(id, first))
            assertContentEquals(confirmed, cipher.decrypt(id, second))
            assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)), "Each record keeps a fresh IV")
            cipher.delete(id)
            assertFailsWith<NoSuchElementException> { cipher.decrypt(id, first) }
            assertFailsWith<NoSuchElementException> { cipher.decrypt(id, second) }
        } finally { cipher.delete(id) }
    }

    @Test fun serverConfirmedUsernameIsSavedExactly() = saves("Canonical-Alice", "Canonical-Alice")
    @Test fun missingCanonicalUsernamePreservesSubmittedSpelling() = saves(null, "ALIce")
    @Test fun onlyTheUnchangedSavedCredentialsCanAuthorizeUsernameContinuation() = runBlocking {
        for (scenario in listOf("saved", "typed", "password", "endpoint", "superseded")) {
            val previous = StoredAccount("continuity-provider", "Music", "https://music.invalid", "Alice", "saved-secret", false)
            var stored: StoredAccount? = previous
            var adoptions = 0
            val credentials = object : AccountCredentialStore {
                override fun load() = stored
                override fun save(serverName: String, serverUrl: String, username: String, password: String, allowLocalHttp: Boolean) =
                    StoredAccount("replacement-provider", serverName, serverUrl, username, password, allowLocalHttp).also { stored = it }
                override fun saveAuthenticatedContinuation(expected: StoredAccount, confirmed: StoredAccount): StoredAccount? {
                    assertEquals(previous, expected)
                    assertEquals(expected, stored)
                    adoptions++
                    return confirmed.also { stored = it }
                }
                override fun delete() { stored = null }
            }
            val request = AccountConnectionRequest(
                if (scenario == "endpoint") "https://other.invalid" else previous.serverUrl,
                if (scenario == "typed") "alice" else previous.username,
                if (scenario == "password") "typed-secret" else previous.password, false)
            val outcome = connectAndSaveAccount(request, {
                if (scenario == "superseded") stored = previous.copy(username = "different-principal")
                AccountConnectionResult.Connected(ConnectedAccount(request.serverUrl, false, "1.16.1", false, "fixture", "1",
                    CapabilitySet(emptyMap(), UserPermissions(true, true, false, false, false), true), emptyList(), "alice"))
            }, credentials)
            assertEquals(if (scenario == "saved") 1 else 0, adoptions, scenario)
            if (scenario == "superseded") {
                assertIs<AccountConnectOutcome.Superseded>(outcome)
                assertEquals("different-principal", stored?.username)
            } else assertIs<AccountConnectOutcome.Connected>(outcome)
            if (scenario == "saved") {
                assertEquals(previous.id, stored?.id)
                assertEquals("alice", stored?.username)
            }
        }
    }

    private fun saves(canonical: String?, expected: String) = runBlocking {
        var saved: StoredAccount? = null
        val store = object : AccountCredentialStore {
            override fun load() = saved
            override fun save(serverName: String, serverUrl: String, username: String, password: String, allowLocalHttp: Boolean) =
                StoredAccount("username-binding-test", serverName, serverUrl, username, password, allowLocalHttp).also { saved = it }
            override fun delete() { saved = null }
        }
        val request = AccountConnectionRequest("https://music.invalid", "ALIce", "fixture", false)
        val account = ConnectedAccount(request.serverUrl, false, "1.16.1", false, "fixture", "1",
            CapabilitySet(emptyMap(), UserPermissions(true, true, false, false, false), true), emptyList(), canonical)
        assertIs<AccountConnectOutcome.Connected>(connectAndSaveAccount(request, { AccountConnectionResult.Connected(account) }, store))
        assertEquals(expected, assertNotNull(saved).username)
        assertEquals("fixture", saved?.password)
    }
}
