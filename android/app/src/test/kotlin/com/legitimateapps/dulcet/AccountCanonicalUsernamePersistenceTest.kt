package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class AccountCanonicalUsernamePersistenceTest {
    @Test fun serverConfirmedUsernameIsSavedExactly() = saves("Canonical-Alice", "Canonical-Alice")
    @Test fun missingCanonicalUsernamePreservesSubmittedSpelling() = saves(null, "ALIce")
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
