package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class SavedServerAccountsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val hostCipher = HostCredentialCipher()
    private val cipher = object : AccountCredentialCipher {
        override fun encrypt(id: String, plaintext: ByteArray) = hostCipher.encrypt(id, plaintext)
        override fun decrypt(id: String, payload: ByteArray) = hostCipher.decrypt(id, payload)
        override fun delete(id: String) = hostCipher.delete(id)
    }
    private val store = AndroidAccountCredentialStore(context, cipher)
    @Before fun before() { context.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit() }
    @After fun after() {
        store.savedAccountIds().forEach { store.remove(it) }
        context.getSharedPreferences(AccountRemovalJournal.PREFERENCES_NAME, 0).edit().clear().commit()
    }

    @Test fun twoEncryptedServersSurviveRelaunchAndSelectionRetiresPlaybackBeforeActivation() = runBlocking {
        val a = store.saveAdditional(record("https://a.invalid", "alice"))
        val b = store.saveAdditional(record("https://b.invalid", "bob"))
        val relaunched = AndroidAccountCredentialStore(context, cipher)
        assertEquals(setOf(a.id, b.id), relaunched.savedAccountIds())
        assertEquals(b, relaunched.load())
        var releases = 0
        assertTrue(selectSavedServer(a.id, relaunched, PlaybackRelease {
            assertEquals(b.id, relaunched.activeAccountId()); releases++; true
        }))
        assertEquals(a, relaunched.load())
        assertEquals(1, releases)
        assertFalse(selectSavedServer(b.id, relaunched, PlaybackRelease { false }))
        assertEquals(a, relaunched.load(), "Failure to retire playback cannot switch accounts")
        assertTrue(selectSavedServer(b.id, relaunched, PlaybackRelease { true }))
        assertEquals(b, relaunched.load())
        relaunched.delete()
        assertEquals(setOf(a.id), relaunched.savedAccountIds(), "Removing B never removes A")
        assertEquals(a, relaunched.load(), "Signing out of B restores another saved server")
        assertEquals(a, relaunched.activate(a.id))
    }

    @Test fun typedCaseAndPasswordRefreshContinueWhenServerConfirmsTheSavedPrincipal() = runBlocking {
        val a = store.saveAdditional(record("https://music.invalid", "alice"))
        val request = AccountConnectionRequest(a.serverUrl, "Alice", "new-password", false)
        assertIs<AccountConnectOutcome.Connected>(connectAndSaveAccount(request,
            { connected("alice", "Navidrome", "99.0") }, store))
        assertEquals(a.id, store.load()?.id)
        assertEquals("alice", store.load()?.username)
        assertEquals("new-password", store.load()?.password)
        assertEquals(setOf(a.id), store.savedAccountIds())
    }

    @Test fun navidromeToDulcetSwapRequiresConsentAndRotatesTheNamespace() = swap("Navidrome", "Dulcet Server")
    @Test fun dulcetToNavidromeSwapRequiresConsentAndRotatesTheNamespace() = swap("Dulcet Server", "Navidrome")

    private fun swap(oldType: String, newType: String) = runBlocking {
        val original = store.saveAdditional(record("https://music.invalid", "alice").copy(
            serverName = oldType, serverIdentity = ServerIdentityPolicy.identity(oldType)))
        val log = mutableListOf<String>()
        val data = object : AccountDataGateway {
            override suspend fun pendingChanges(account: StoredAccount): PendingChanges? = null
            override suspend fun send(account: StoredAccount, changes: Boolean): PendingChanges? = null
            override suspend fun removeAccountData(serverId: String) { log += "remove:$serverId"; assertNull(store.load()) }
            override suspend fun sweep(activeAccountId: () -> String?): Set<String> = emptySet()
        }
        val replacement = AndroidAccountReplacement(context, store, PlaybackRelease { log += "retire"; true }, data,
            countDownloads = { assertEquals(original.id, it); 7 })
        val request = AccountConnectionRequest(original.serverUrl, "alice", original.password, false)
        suspend fun attempt() = connectAndSaveAccount(request, { connected("alice", newType, "1") }, store,
            replacement = replacement)
        val cancelled = async { attempt() }
        yield()
        assertEquals(7, replacement.offer.value?.downloadCount)
        assertEquals(original, store.load())
        replacement.answer(false)
        assertIs<AccountConnectOutcome.Superseded>(cancelled.await())
        assertEquals(original, store.load())
        assertEquals(emptyList(), log)
        val accepted = async { attempt() }; yield()
        replacement.answer(true)
        assertIs<AccountConnectOutcome.Connected>(accepted.await())
        assertEquals(listOf("retire", "remove:${original.id}"), log)
        assertNotEquals(original.id, store.load()?.id)
        assertEquals(1, store.savedAccountIds().size)
    }

    private fun record(url: String, username: String) = StoredAccount("new", "Navidrome", url, username, "secret", false,
        ServerIdentityPolicy.identity("Navidrome"))
    private fun connected(username: String, type: String, version: String) = AccountConnectionResult.Connected(
        ConnectedAccount("https://music.invalid", false, "1.16.1", true, type, version,
            CapabilitySet(emptyMap(), UserPermissions(true, true, false, false, false), true), emptyList(), username))
}
