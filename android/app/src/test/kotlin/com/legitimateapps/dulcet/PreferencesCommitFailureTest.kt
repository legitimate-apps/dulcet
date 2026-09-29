package com.legitimateapps.dulcet

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SharedPreferences.commit()` updates the in-memory map even when its disk write fails (a full
 * disk). A failed commit in the sign-out's stores must leave this process seeing what is on disk,
 * or it acts on a state no relaunch would find: an account that looks signed out while still saved,
 * or a `removing` mark that was never persisted.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesCommitFailureTest {
    private val application = RuntimeEnvironment.getApplication()

    @After fun clean() {
        application.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit()
        application.getSharedPreferences("commit-failure-journal", 0).edit().clear().commit()
    }

    @Test fun aRecordWhoseDeletionDidNotReachDiskIsStillSeenAsSaved() {
        val context = FailingCommitContext(application)
        val store = AndroidAccountCredentialStore(context, PlaintextCipher())
        val account = store.save("Fixture Server", "https://music.example.invalid", "listener", "password", false)
        context.preferences.failCommits = true

        val failure = assertFailsWith<CredentialStoreException> { store.delete() }

        assertEquals(CredentialStoreException.Reason.PersistenceFailed, failure.reason)
        assertTrue(context.preferences.failedCommits > 0, "control: the commit failed")
        assertEquals(account.id, store.activeAccountId(), "The account is still on disk, so it must still be seen")
        assertEquals(account, store.load())
    }

    @Test fun aMarkThatDidNotReachDiskIsNotSeenEither() {
        val preferences = FailingCommitPreferences(application.getSharedPreferences("commit-failure-journal", 0))
        val journal = AccountRemovalJournal(preferences)
        assertTrue(journal.begin("account-a"))
        preferences.failCommits = true

        assertFalse(journal.begin("account-b"))
        assertFalse(journal.keyLeft("account-b"))

        assertEquals(2, preferences.failedCommits, "control: both commits failed")
        assertEquals(setOf("account-a"), journal.pending(), "Only the mark on disk may be seen")
        assertEquals(emptySet(), journal.keysLeft())
    }

    private class FailingCommitContext(base: Context) : ContextWrapper(base) {
        val preferences = FailingCommitPreferences(base.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0))
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == AndroidAccountCredentialStore.PREFERENCES_NAME) preferences else super.getSharedPreferences(name, mode)
    }

    /** Behaves as a failed disk write does: the edit reaches the in-memory map, and commit() says false. */
    private class FailingCommitPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failCommits = false
        var failedCommits = 0

        override fun edit(): SharedPreferences.Editor = FailingEditor(delegate.edit())

        private inner class FailingEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { editor.putStringSet(key, values); return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { editor.putFloat(key, value); return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
            override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
            override fun commit(): Boolean {
                if (!failCommits) return editor.commit()
                editor.apply()
                failedCommits += 1
                return false
            }
        }
    }
}
