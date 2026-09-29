package com.legitimateapps.dulcet.search.conformance

import android.app.Application
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.search.*
import com.legitimateapps.dulcet.library.toReaderAccount
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

/**
 * CONF-41's production proof, carried onto the reader: which row is server-only at index 2 is no
 * longer the first of `search3`'s raw order but the first of the core's stable ranking of the
 * server's rows (§18.1, `rankResultsStably`), so the tests read it from the published rows and assert
 * it is one the server returned, not the overlap and not the device's own.
 */
fun SearchUiState.assertMergedOrder(environment: ProductionSearchEnvironment): SearchResultItem {
    assertEquals(environment.overlap.id, rows[0].item.id)
    assertEquals(AndroidLibrarySearchRowSource.Server, rows[0].source, "the server's row replaced the cached one")
    assertEquals(environment.localOnly.id, rows[1].item.id)
    assertEquals(AndroidLibrarySearchRowSource.Device, rows[1].source)
    val third = rows[2]
    assertEquals(AndroidLibrarySearchRowSource.Server, third.source)
    assertTrue(third.item.id in environment.serverIds && third.item.id != environment.overlap.id, "index 2 is server-only")
    return third.item
}

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
    /** Every row `search3` returned for the query, by id: what may appear as a server-only row. */
    lateinit var serverIds: Set<ProviderItemId>
    lateinit var account: SearchAccount
    private val began = kotlin.time.TimeSource.Monotonic.markNow()
    private fun phase(name: String) = println("SEARCH_ENV phase=$name at-ms=${began.elapsedNow().inWholeMilliseconds}")

    override fun before() {
        phase("start")
        val target = disposableBaseUrl()
        val app = RuntimeEnvironment.getApplication()
        assertFalse(app is SearchHostDependencyOwner, "Application must not replace search dependencies")
        closeProcessReader()
        app.deleteDatabase("dulcet.db")
        AndroidAccountCredentialStore(app).save("Disposable", target,
            "dulcet-admin", "dulcet-ci-canary-password", true)
        account = assertNotNull(ProductionSearchHostDependencies.loadAccount(app))
        // Discover opaque reference IDs on the wire, never synthesize or parse server IDs.
        val result = runBlocking { ServerSearch().search(SearchPageRequest(
            account.providerInstanceId, account.normalizedBaseUrl, account.username, account.password,
            true, "Dulcet", 30, 0, 30, 0, 30, 0)) }
        check(result is SearchPageResult.Loaded) { "Disposable search3 must succeed (response redacted)" }
        overlap = result.page.results.first { it.title == "Dulcet Health Probe" }
        serverIds = result.page.results.map { it.id }.toSet()
        check(serverIds.size > 1) { "The disposable server must return more than the overlap row" }
        localOnly = overlap.copy(id = ProviderItemId(account.providerInstanceId,
            "local:opaque/CONF-41:not-an-integer"), title = "Dulcet local only")

        phase("server-search-done")
        // What this device has seen comes only from the production reader's own reads: one search
        // through it writes the server's answer into the seen-cache (§16.15 write-through).
        // No host is in the foreground here, and nothing is to read on a timer.
        val reader = AndroidLibraryReader.forAccount(app, account.toReaderAccount(), foreground = false)
        val seen = mutableListOf<AndroidLibrarySearchPublication>()
        val search = reader.openSearch { seen += it }
        search.updateQuery("Dulcet")
        pumpUntil("the seeding search to be answered by the server") {
            seen.any { it.query == "Dulcet" && it.scope == AndroidLibrarySearchScope.ServerAndDevice }
        }
        phase("seeding-search-answered")
        search.close()
        closeProcessReader()
        phase("seeding-reader-closed")

        // Then make that seen-cache stale in one known way: the overlap row keeps its id but an old
        // title, and one row exists only on this device. IDs and metadata originate in the real read;
        // every write here is to this test's own SQLite file.
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            app.getDatabasePath("dulcet.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        try {
            database.beginTransaction()
            val present = database.rawQuery("SELECT count(*) FROM cache_track WHERE server_id = ? AND raw_id = ?",
                arrayOf(account.providerInstanceId, overlap.id.rawId)).use { it.moveToFirst(); it.getInt(0) }
            check(present == 1) { "The seeding search did not write the overlap track to the seen-cache" }
            database.execSQL("DELETE FROM cache_credit")
            database.execSQL("DELETE FROM cache_artist")
            database.execSQL("DELETE FROM cache_album")
            database.execSQL("DELETE FROM cache_track WHERE raw_id != ?", arrayOf(overlap.id.rawId))
            database.execSQL("""UPDATE cache_track SET title = 'Dulcet', normalized_title = 'dulcet',
                album_title = NULL, normalized_album_title = ''""")
            database.execSQL("""INSERT INTO cache_track(server_id, raw_id, album_raw_id, album_ordinal, title,
                normalized_title, album_title, normalized_album_title, artist_name, artist_raw_id, disc_number,
                track_number, duration_milliseconds, source_container, artwork_key, starred, starred_at,
                user_rating, play_count, played, fetched_at_wall, fetched_epoch, issue_seq, last_access_wall,
                gone, metadata_missing)
                SELECT server_id, ?, album_raw_id, album_ordinal, 'Dulcet local only', 'dulcet local only',
                    NULL, '', NULL, NULL, disc_number, track_number, duration_milliseconds, source_container,
                    artwork_key, starred, starred_at, user_rating, play_count, played, fetched_at_wall,
                    fetched_epoch, issue_seq, last_access_wall, gone, metadata_missing FROM cache_track""",
                arrayOf(localOnly.id.rawId))
            database.setTransactionSuccessful()
            database.endTransaction()
        } finally { database.close() }

        // The seeded state, read back through a fresh production reader offline: the device's rows
        // for the query, and nothing asked of the server.
        val check = AndroidLibraryReader.forAccount(app, account.toReaderAccount(), foreground = false)
        val offline = mutableListOf<AndroidLibrarySearchPublication>()
        check.setOnline(false)
        val probe = check.openSearch { offline += it }
        probe.updateQuery("Dulcet")
        pumpUntil("the seeded device rows") { offline.any { it.query == "Dulcet" } }
        val rows = offline.last { it.query == "Dulcet" }
        assertIs<AndroidLibrarySearchScope.DeviceOffline>(rows.scope)
        assertEquals(listOf(overlap.id.rawId, localOnly.id.rawId), rows.rows.map { it.item.id.rawId })
        probe.close()
        closeProcessReader()
        phase("seed-verified")
    }
    override fun after() {
        phase("after")
        runCatching { closeProcessReader() }
        AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).delete()
        RuntimeEnvironment.getApplication().deleteDatabase("dulcet.db")
    }

    private fun pumpUntil(what: String, condition: () -> Boolean) {
        repeat(6_000) {
            if (condition()) return
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        error("Timed out waiting for $what")
    }
}
