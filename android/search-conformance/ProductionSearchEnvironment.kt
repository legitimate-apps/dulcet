package com.legitimateapps.dulcet.search.conformance

import android.app.Application
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.search.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.*

/** Host-only OS crypto boundary. Record encoding, persistence and production load remain real. */
@Implements(className = "com.legitimateapps.dulcet.AndroidKeystoreAccountCredentialCipher", isInAndroidSdk = false)
class HostCredentialCipher {
    @Implementation fun encrypt(id: String, plaintext: ByteArray): ByteArray {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keys[id] = key
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }
    @Implementation fun decrypt(id: String, payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.getValue(id), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return cipher.doFinal(payload.copyOfRange(12, payload.size))
    }
    @Implementation fun delete(id: String) { keys.remove(id) }
    companion object { private val keys = mutableMapOf<String, SecretKey>() }
}

class ProductionSearchEnvironment : ExternalResource() {
    lateinit var overlap: SearchResultItem
    lateinit var localOnly: SearchResultItem
    lateinit var serverOnly: SearchResultItem
    lateinit var account: SearchAccount
    override fun before() {
        check(System.getenv("DULCET_CONFORMANCE_DISPOSABLE") == "true") {
            "Production app search requires the disposable conformance runner"
        }
        check(System.getenv("DULCET_CONFORMANCE_BASE_URL") == "http://127.0.0.1:4533") {
            "Production app search requires the fixed loopback disposable endpoint"
        }
        val app = RuntimeEnvironment.getApplication()
        assertFalse(app is SearchHostDependencyOwner, "Application must not replace search dependencies")
        AndroidAccountCredentialStore(app).save("Disposable", "http://127.0.0.1:4533",
            "dulcet-admin", "dulcet-ci-canary-password", true)
        account = assertNotNull(ProductionSearchHostDependencies.loadAccount(app))
        // Discover opaque reference IDs on the wire, never synthesize or parse server IDs.
        val result = runBlocking { ServerSearch().search(SearchPageRequest(
            account.providerInstanceId, account.normalizedBaseUrl, account.username, account.password,
            true, "Dulcet", 30, 0, 30, 0, 30, 0)) }
        check(result is SearchPageResult.Loaded) { "Disposable search3 must succeed (response redacted)" }
        overlap = result.page.results.first { it.title == "Dulcet Health Probe" }
        serverOnly = result.page.results.first { it.id != overlap.id }
        localOnly = overlap.copy(id = ProviderItemId(account.providerInstanceId,
            "local:opaque/CONF-41:not-an-integer"), title = "Dulcet local only")
        app.deleteDatabase("dulcet.db")
        val library = AndroidLibraryDatabase(app)
        val synced = runBlocking { library.synchronize(LibrarySyncRequest(
            account.providerInstanceId, account.normalizedBaseUrl, account.username, account.password, true)) }
        check(synced is LibrarySyncResponse.Completed) { "Disposable library sync must commit (response redacted)" }
        // Seed a minimal stale committed library, not a query-response cache or dependency double.
        // IDs and metadata originate in the real sync; all writes here are to this test's SQLite file.
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            app.getDatabasePath("dulcet.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        try {
            database.beginTransaction()
            database.execSQL("DELETE FROM credit")
            database.execSQL("DELETE FROM artist")
            database.execSQL("DELETE FROM album")
            database.execSQL("DELETE FROM track WHERE raw_id != ?", arrayOf(overlap.id.rawId))
            database.execSQL("UPDATE track SET title = 'Dulcet', normalized_title = 'dulcet', album_title = NULL, normalized_album_title = ''")
            database.execSQL("""INSERT INTO track(server_id, raw_id, album_raw_id, title, artist_name,
                artist_raw_id, album_title, disc_number, track_number, duration_milliseconds,
                source_container, media_source_id, artwork_key, content_key, valid_from_generation,
                valid_to_generation, normalized_title, normalized_album_title)
                SELECT server_id, ?, album_raw_id, 'Dulcet local only', NULL, NULL, NULL,
                    disc_number, track_number, duration_milliseconds, source_container, media_source_id,
                    artwork_key, 'local-only', valid_from_generation, valid_to_generation,
                    'dulcet local only', '' FROM track""", arrayOf(localOnly.id.rawId))
            database.setTransactionSuccessful()
            database.endTransaction()
        } finally { database.close() }
        assertEquals(listOf(overlap.id.rawId, localOnly.id.rawId),
            runBlocking { library.search(account.providerInstanceId, "Dulcet") }.map { it.id.rawId })

    }
    override fun after() {
        AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).delete()
        RuntimeEnvironment.getApplication().deleteDatabase("dulcet.db")
    }
}
