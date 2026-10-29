package com.legitimateapps.dulcet

import android.database.sqlite.SQLiteDatabase
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.*

/** Real secure-record composition and real Android SQLite; no preseeded media binding or network. */
@RunWith(RobolectricTestRunner::class)
class FirstConnectionEnrollmentTest {
    private val context = RuntimeEnvironment.getApplication()
    private val cipher = HostCredentialCipher()
    private val credentials = AndroidAccountCredentialStore(context, object : AccountCredentialCipher {
        override fun encrypt(id: String, plaintext: ByteArray) = cipher.encrypt(id, plaintext)
        override fun decrypt(id: String, payload: ByteArray) = cipher.decrypt(id, payload)
        override fun delete(id: String) = cipher.delete(id)
    })

    @After fun cleanup() {
        credentials.savedAccountIds().forEach { credentials.remove(it) }
        context.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit()
    }

    @Test fun firstSignInInitializesAnchoredOwnershipBeforeAutomaticDirectoryEnrollment() = runBlocking {
        assertNull(credentials.load())
        val request = AccountConnectionRequest("https://localhost:9/music", "listener", "synthetic", false)
        val identity = ServerIdentityPolicy.identity("dulcet", "first-sign-in-fixture")
        var calls = 0
        var observedEpoch: String? = null
        val store = object : AccountCredentialStore by credentials {
            override suspend fun enrollConnections(account: StoredAccount, request: AccountConnectionRequest) {
                calls++
                assertEquals(account, credentials.load())
                assertEquals(identity, credentials.acceptedServerIdentity(account))
                assertEquals("https://localhost:9/music", account.serverUrl)
                SQLiteDatabase.openDatabase(context.getDatabasePath("dulcet.db").path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    database.rawQuery("SELECT value FROM library_depth_entry WHERE server_id=? AND category='download.mediaBinding' AND raw_id='binding'",
                        arrayOf(account.id)).use { rows ->
                        assertTrue(rows.moveToFirst(), "Fresh save must establish a real media epoch before enrollment")
                        observedEpoch = rows.getString(0)
                        assertFalse(rows.moveToNext())
                    }
                }
                assertTrue(requireNotNull(observedEpoch).contains(account.serverUrl))
                assertTrue(requireNotNull(observedEpoch).contains(account.username))
            }
        }
        val result = connectAndSaveAccount(request, { AccountConnectionResult.Connected(ConnectedAccount(
            request.serverUrl, false, "1.16.1", true, "dulcet", "fixture",
            CapabilitySet(mapOf("dulcetRoutes" to setOf(1)), UserPermissions(true, true, false, false, false), false), emptyList(),
            request.username, identity)) }, store)
        assertIs<AccountConnectOutcome.Connected>(result)
        assertEquals(1, calls, "The actual sign-in composition reaches automatic enrollment without a media controller")
        assertNotNull(observedEpoch)
    }
}
