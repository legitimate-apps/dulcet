package com.legitimateapps.dulcet

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AndroidAccountData
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.DomainError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nothing of a signed-out account survives on the device: a canary is planted in every store the
 * account owns — its credential, its database rows, its cached artwork and its downloaded media —
 * each is first found by the scan (the positive control), and none is found after signing out
 * through the production view model, sign-out, gateway, core removal and library reader. Only
 * playback is a fake; PlaybackReleaseTest covers the service.
 */
@RunWith(RobolectricTestRunner::class)
class AccountSignOutCanaryTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val cipher = PlaintextCipher()

    @After fun clean() {
        AndroidLibraryReader.closeCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        application.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit()
        application.getSharedPreferences(AccountRemovalJournal.PREFERENCES_NAME, 0).edit().clear().commit()
    }

    @Test fun noCanaryOfASignedOutAccountSurvivesInAnyStoreItOwned() {
        val store = AndroidAccountCredentialStore(application, cipher)
        // The account's username is the database canary too: the reader binds the seen-cache to it.
        val account = store.save("Fixture Server", "http://127.0.0.1:9", USER_CANARY, PASSWORD_CANARY, true)
        plantRows(account.id)
        val artwork = File(application.cacheDir, "artwork/${sha256(account.id)}/cover.image")
            .apply { parentFile!!.mkdirs(); writeText("image-bytes-$ARTWORK_CANARY") }
        val download = File(application.noBackupFilesDir, "downloads/$DOWNLOAD_FILE")
            .apply { parentFile!!.mkdirs(); writeText("audio-bytes-$DOWNLOAD_CANARY") }

        val viewModel = AccountConnectViewModel(application, object : AccountConnectionGateway {
            override suspend fun connect(request: AccountConnectionRequest) =
                AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
        }, store, object : ChannelDefaults { override val preconfiguredServerUrl: String? = null },
            playbackRelease = FakePlayback(mutableListOf()))
        settle { viewModel.signOut.state.value == SignOutState.Idle }

        // Positive controls: every store holds its canary, and the scan finds each where it was put.
        val before = found()
        assertTrue(before.getValue(PASSWORD_CANARY).any { it.endsWith(".xml") }, "credential: $before")
        assertTrue(before.getValue(USER_CANARY).any { it.startsWith("dulcet.db") }, "binding row: $before")
        assertTrue(before.getValue(ROW_CANARY).any { it.startsWith("dulcet.db") }, "resume row: $before")
        assertEquals(setOf(artwork.name), before.getValue(ARTWORK_CANARY), "artwork: $before")
        assertEquals(setOf(download.name), before.getValue(DOWNLOAD_CANARY), "download: $before")
        assertEquals(PASSWORD_CANARY, viewModel.state.value.password, "The saved account is restored into the form")
        assertTrue(account.id in cipher.keys)

        viewModel.signOut.request()
        settle { viewModel.signOut.state.value is SignOutState.Confirm }
        viewModel.signOut.signOut()
        settle { viewModel.signOut.state.value == SignOutState.Idle }

        assertEquals(emptyMap(), found().filterValues { it.isNotEmpty() }, "A canary of the signed-out account survived")
        assertFalse(account.id in cipher.keys, "The account's encryption key must be deleted")
        assertTrue(AccountRemovalJournal(application).pending().isEmpty(), "The removal finished")
        val form = viewModel.state.value
        for (value in listOf(form.serverUrl, form.username, form.password))
            assertFalse(value.contains(USER_CANARY) || value.contains(PASSWORD_CANARY), "A credential value survived in the form")
        assertIs<AccountConnectStatus.Idle>(form.status)
        assertNull(store.load())
    }

    /** Rows the account owns, written into the production database file with the production schema. */
    private fun plantRows(serverId: String) {
        // Opened through the core first, so the schema is the production one.
        runBlocking { AndroidAccountData(application, serverId).pendingPlays() }
        val database = SQLiteDatabase.openDatabase(application.getDatabasePath("dulcet.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.execSQL("INSERT OR REPLACE INTO cache_binding(server_id, normalized_base_url, username, created_at_wall) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(serverId, "http://127.0.0.1:9", USER_CANARY, 1_788_000_000_000L))
            database.execSQL("INSERT INTO resume_position(server_id, raw_id, position_milliseconds) VALUES (?, ?, ?)",
                arrayOf<Any>(serverId, ROW_CANARY, 12_000L))
            database.execSQL("INSERT INTO download(server_id, raw_id, transcode_profile, download_id, state, file_relative_path, expected_byte_length, file_size_bytes) VALUES (?, ?, ?, ?, 'complete', ?, ?, ?)",
                arrayOf<Any>(serverId, "song-0", "original", "download-canary", DOWNLOAD_FILE, 32L, 32L))
            database.execSQL("INSERT INTO download_policy_state(server_id, raw_id, transcode_profile, expected_container, source_duration_milliseconds, source_size_bytes, enqueue_sequence, auth_generation, updated_at_wall_clock) VALUES (?, ?, ?, 'mp3', 1000, 32, 0, 0, ?)",
                arrayOf<Any>(serverId, "song-0", "original", 1_788_000_000_000L))
        } finally {
            database.close()
        }
    }

    /** Runs the main looper, which the sign-out's coroutines and the reader's completions use, until [done]. */
    private fun settle(done: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "never settled" }
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * For each canary, the names of the files under the app's storage that hold it, read raw and
     * with each Base64-looking run decoded: the credential store Base64-encodes its record.
     */
    private fun found(): Map<String, Set<String>> {
        val roots = listOf(application.dataDir, application.cacheDir, application.noBackupFilesDir, application.filesDir,
            application.getDatabasePath("dulcet.db").parentFile!!)
        val files = roots.flatMap { root -> root.walk().filter { it.isFile }.toList() }.distinctBy { it.canonicalPath }
        return CANARIES.associateWith { canary ->
            files.filter { file ->
                val text = file.readBytes().toString(Charsets.ISO_8859_1)
                val decoded = Regex("[A-Za-z0-9+/]{16,}={0,2}").findAll(text).mapNotNull { run ->
                    runCatching { java.util.Base64.getDecoder().decode(run.value).toString(Charsets.ISO_8859_1) }.getOrNull()
                }
                (sequenceOf(text) + decoded).any { it.contains(canary) }
            }.map(File::getName).toSet()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val USER_CANARY = "user-canary-7f3a91c2"
        const val PASSWORD_CANARY = "password-canary-4be8d05e"
        const val ROW_CANARY = "row-canary-19ad73e0"
        const val ARTWORK_CANARY = "artwork-canary-5c02be71"
        const val DOWNLOAD_CANARY = "download-canary-e84f1d36"
        const val DOWNLOAD_FILE = "canary-song.bin"
        val CANARIES = listOf(USER_CANARY, PASSWORD_CANARY, ROW_CANARY, ARTWORK_CANARY, DOWNLOAD_CANARY)
    }
}
