package com.legitimateapps.dulcet

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signing out on Android, spec §14.7, against the production credential store and removal journal.
 * Only the core's account data and the playback service are fakes; each records what it was asked
 * to do into one ordered log, so the tests assert order as well as occurrence. The core's half —
 * the reader's close, the rows, artwork and downloads — is tested against the production database
 * in AndroidAccountDataTest, and end to end with canaries in AccountSignOutCanaryTest.
 */
@RunWith(RobolectricTestRunner::class)
class AccountSignOutTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val log = mutableListOf<String>()
    private val cipher = PlaintextCipher()
    private val store = RecordingStore(AndroidAccountCredentialStore(application, cipher), log)
    private val journal = AccountRemovalJournal(application)
    private val data = FakeAccountData(log)
    private val playback = FakePlayback(log)
    private var signedOut = 0

    @After fun clean() {
        application.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit()
        application.getSharedPreferences(AccountRemovalJournal.PREFERENCES_NAME, 0).edit().clear().commit()
    }

    @Test fun withNothingOwedItConfirmsThenStopsPlaybackBeforeDeletingTheCredentialAndTheData() = runTest {
        val account = saved()
        val flow = flow()
        flow.request()
        assertEquals(SignOutState.Checking, flow.state.value)
        advanceUntilIdle()
        assertEquals(SignOutState.Confirm("Fixture Server"), flow.state.value)
        assertEquals(listOf("data.count:${account.id}"), log, "Nothing may happen before the person confirms")

        log.clear()
        flow.signOut()
        assertTrue(flow.state.value.hidesAccount, "The account leaves the screen as soon as the removal starts")
        advanceUntilIdle()

        assertEquals(listOf("playback.release", "data.count:${account.id}", "credential.delete", "data.remove:${account.id}"), log)
        assertNull(store.load())
        assertTrue(journal.pending().isEmpty(), "The removing entry is cleared once everything is gone")
        assertEquals(SignOutState.Idle, flow.state.value)
        assertEquals(1, signedOut)
    }

    @Test fun theRemovingEntryIsWrittenBeforeTheCredentialIsDeleted() = runTest {
        val account = saved()
        store.onDelete = { log += "journal:${journal.pending()}" }
        val flow = flow()
        flow.request(); advanceUntilIdle()
        flow.signOut(); advanceUntilIdle()
        assertTrue("journal:[${account.id}]" in log, "A process dying after the credential goes must find the entry: $log")
    }

    @Test fun stayingSignedInFromTheConfirmationUndoesEverything() = runTest {
        val account = saved()
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        log.clear()
        flow.stay()
        advanceUntilIdle()
        assertEquals(SignOutState.Idle, flow.state.value)
        assertTrue(log.isEmpty(), "Stay signed in must leave playback, the credential and the data alone: $log")
        assertEquals(account.id, store.load()?.id)
        assertEquals(0, signedOut)
    }

    @Test fun owedChangesAreOfferedAndNothingIsRemovedUntilOneIsChosen() = runTest {
        val account = saved()
        data.pending = changes(plays = 2, edits = 1)
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        assertEquals(SignOutState.Offer(changes(2, 1), afterSend = false, hidesAccount = false), flow.state.value)
        assertEquals(account.id, store.load()?.id)

        log.clear()
        flow.signOut() // Sign out and discard
        advanceUntilIdle()
        assertEquals(listOf("playback.release", "data.count:${account.id}", "credential.delete", "data.remove:${account.id}"), log)
        assertNull(store.load())
    }

    @Test fun sendingDeliversPlaysAndChangesThenSignsOut() = runTest {
        val account = saved()
        data.pending = changes(plays = 3, edits = 2)
        data.afterSend = changes(0, 0)
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        log.clear()
        flow.send()
        assertEquals(SignOutState.Sending, flow.state.value)
        advanceUntilIdle()
        assertEquals(listOf("playback.release", "data.send:${account.id}:changes", "credential.delete", "data.remove:${account.id}"), log)
        assertNull(store.load())
        assertEquals(1, signedOut)
    }

    @Test fun onlyChangesPendingAreSentThroughTheReaderToo() = runTest {
        val account = saved()
        data.pending = changes(plays = 0, edits = 2)
        val flow = flow()
        flow.request(); advanceUntilIdle()
        log.clear()
        flow.send(); advanceUntilIdle()
        assertEquals("data.send:${account.id}:changes", log[1])
    }

    @Test fun onlyPlaysPendingDoNotAskTheReaderToFlush() = runTest {
        val account = saved()
        data.pending = changes(plays = 1, edits = 0)
        val flow = flow()
        flow.request(); advanceUntilIdle()
        log.clear()
        flow.send(); advanceUntilIdle()
        assertEquals("data.send:${account.id}:plays", log[1])
    }

    @Test fun whatASendLeavesIsOfferedAgainAndTheAccountIsKeptUntilChosen() = runTest {
        val account = saved()
        data.pending = changes(plays = 3, edits = 0)
        data.afterSend = changes(1, 0)
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        log.clear()
        flow.send()
        advanceUntilIdle()
        assertEquals(SignOutState.Offer(changes(1, 0), afterSend = true, hidesAccount = true), flow.state.value)
        assertEquals(listOf("playback.release", "data.send:${account.id}:plays"), log)
        assertEquals(account.id, store.load()?.id, "A failed send must not remove the account")

        data.pending = changes(1, 0)
        flow.signOut()
        advanceUntilIdle()
        assertNull(store.load())
    }

    @Test fun aCountThatGrewSinceTheOfferIsOfferedAgainNotDiscardedUnseen() = runTest {
        saved()
        data.pending = changes(plays = 1, edits = 0)
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        // A play in progress crosses the scrobble threshold while the dialog is open.
        data.pending = changes(plays = 2, edits = 0)
        flow.signOut()
        advanceUntilIdle()
        assertEquals(SignOutState.Offer(changes(2, 0), afterSend = false, hidesAccount = true), flow.state.value)
        assertFalse("credential.delete" in log, "The larger count was never offered: $log")
        assertNotNull(store.load())

        flow.signOut()
        advanceUntilIdle()
        assertNull(store.load())
    }

    /**
     * The service sends the offered play and records a different one while the dialog is open: the
     * count is unchanged, and only identities tell them apart. Comparing totals (the mutant) discards
     * the new play without offering it.
     */
    @Test fun aPlayRecordedWhileTheOfferIsOpenIsOfferedAgainEvenAtTheSameCount() = runTest {
        saved()
        data.pending = PendingChanges(setOf("song-1"), 0)
        val flow = flow()
        flow.request(); advanceUntilIdle()
        data.pending = PendingChanges(setOf("song-2"), 0)
        flow.signOut(); advanceUntilIdle()
        assertEquals(SignOutState.Offer(PendingChanges(setOf("song-2"), 0), afterSend = false, hidesAccount = true), flow.state.value)
        assertNotNull(store.load(), "A play that was never offered was discarded")
    }

    @Test fun fewerChangesThanOfferedAreNotOfferedAgain() = runTest {
        saved()
        data.pending = PendingChanges(setOf("song-1", "song-2"), 3)
        val flow = flow()
        flow.request(); advanceUntilIdle()
        // The reader's own reconnect sent some meanwhile.
        data.pending = PendingChanges(setOf("song-2"), 1)
        flow.signOut(); advanceUntilIdle()
        assertNull(store.load())
    }

    @Test fun anUnreadableCountIsStatedAsUnknownAndNeedsItsOwnChoice() = runTest {
        val account = saved()
        data.pending = null
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        assertEquals(SignOutState.Unknown(hidesAccount = false), flow.state.value)
        log.clear()
        flow.signOut() // the ordinary sign-out is not an answer to "unknown"
        advanceUntilIdle()
        assertTrue(log.isEmpty(), "Unknown must not be treated as nothing owed: $log")

        flow.signOutAnyway()
        advanceUntilIdle()
        assertEquals(listOf("playback.release", "credential.delete", "data.remove:${account.id}"), log)
    }

    /** An account whose saved record cannot be read can still be signed out; nothing can be sent for it. */
    @Test fun anAccountWhoseRecordCannotBeReadCanStillBeSignedOut() = runTest {
        val account = saved()
        cipher.unreadable = true
        assertNull(runCatching { store.load() }.getOrNull(), "The control needs an unreadable record")
        val flow = flow()
        assertEquals(account.id, flow.unreadableAccountId())
        flow.request()
        advanceUntilIdle()
        assertEquals(SignOutState.Unknown(hidesAccount = false), flow.state.value)
        assertTrue(log.isEmpty(), "Nothing can be counted or sent without the record: $log")

        flow.signOutAnyway()
        advanceUntilIdle()
        assertEquals(listOf("playback.release", "credential.delete", "data.remove:${account.id}"), log)
        assertNull(store.activeAccountId())
        assertEquals(1, signedOut)
    }

    @Test fun stayingSignedInWhileSendingCancelsTheSendAndKeepsTheAccount() = runTest {
        val account = saved()
        data.pending = changes(plays = 1, edits = 0)
        data.sendGate = CompletableDeferred()
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        log.clear()
        flow.send()
        advanceUntilIdle()
        assertEquals(SignOutState.Sending, flow.state.value)
        assertTrue(data.sending, "The control requires the send to be in flight")

        flow.stay()
        advanceUntilIdle()
        assertEquals(SignOutState.Idle, flow.state.value)
        assertTrue(data.sendCancelled, "Stay signed in must cancel the send in flight")
        assertEquals(listOf("playback.release", "data.send:${account.id}:plays"), log)
        assertEquals(account.id, store.load()?.id)
        assertEquals(0, signedOut)
    }

    @Test fun playbackThatCannotBeStoppedFailsTheSignOutBeforeAnythingIsDeleted() = runTest {
        val account = saved()
        playback.succeeds = false
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        log.clear()
        flow.signOut()
        advanceUntilIdle()
        assertEquals(SignOutState.Failed, flow.state.value)
        assertEquals(listOf("playback.release"), log)
        assertEquals(account.id, store.load()?.id)
        assertTrue(journal.pending().isEmpty())
    }

    /**
     * A record the disk did not let go of is not deleted, whatever the in-memory map says; so nothing
     * of the account is deleted either, and nothing is left to resume.
     */
    @Test fun aCredentialThatCannotBeDeletedFromDiskFailsTheSignOutAndKeepsTheData() = runTest {
        val account = saved()
        store.deleteFailure = CredentialStoreException.Reason.PersistenceFailed
        val flow = flow()
        flow.request()
        advanceUntilIdle()
        log.clear()
        flow.signOut()
        advanceUntilIdle()
        assertEquals(SignOutState.Failed, flow.state.value)
        assertEquals(listOf("playback.release", "data.count:${account.id}", "credential.delete"), log)
        assertEquals(account.id, store.load()?.id)
        assertTrue(journal.pending().isEmpty(), "Nothing is left to resume: the account was kept")
        assertEquals(0, signedOut)
    }

    /** Only the key failed, after the record left the disk: the sign-out goes on, and the key is retried at launch. */
    @Test fun aKeyThatCannotBeDeletedIsDeletedAtALaterLaunch() = runTest {
        val account = saved()
        cipher.deleteFails = true
        val flow = flow()
        flow.request(); advanceUntilIdle()
        flow.signOut(); advanceUntilIdle()
        assertNull(store.activeAccountId(), "The record is gone")
        assertTrue(account.id in cipher.keys, "The control needs the key to have survived")
        assertEquals(setOf(account.id), journal.keysLeft())
        assertEquals(1, signedOut)

        // Still failing at the next launch: kept for the one after.
        flow().resumeInterrupted(); advanceUntilIdle()
        assertEquals(setOf(account.id), journal.keysLeft())

        cipher.deleteFails = false
        flow().resumeInterrupted(); advanceUntilIdle()
        assertFalse(account.id in cipher.keys, "The key is deleted at a later launch")
        assertTrue(journal.keysLeft().isEmpty())
    }

    /**
     * A process that dies after the record left the disk and before the key did must still leave
     * the key for a later launch: so the key is recorded before the record is deleted. Observed at
     * the moment the key's deletion begins, which is the last point such a death can precede.
     */
    @Test fun theKeyIsRecordedAsLeftBeforeTheRecordIsDeletedAndClearedOnceBothAreGone() = runTest {
        val account = saved()
        var recordedWhenTheKeyGoes: Set<String>? = null
        var recordGoneByThen: Boolean? = null
        cipher.onDelete = {
            recordedWhenTheKeyGoes = AccountRemovalJournal(application).keysLeft()
            recordGoneByThen = store.activeAccountId() == null
        }
        val flow = flow()
        flow.request(); advanceUntilIdle()
        flow.signOut(); advanceUntilIdle()
        assertEquals(true, recordGoneByThen, "control: the key is deleted after the record")
        assertEquals(setOf(account.id), recordedWhenTheKeyGoes, "A death before the key's deletion would orphan the key")
        assertFalse(account.id in cipher.keys)
        assertTrue(journal.keysLeft().isEmpty(), "Once both are gone nothing is left to retry")
        assertEquals(1, signedOut)
    }

    @Test fun aRemovalThatDidNotFinishIsFinishedAtTheNextLaunch() = runTest {
        val account = saved()
        data.removeFails = true
        val first = flow()
        first.request()
        advanceUntilIdle()
        first.signOut()
        advanceUntilIdle()
        assertNull(store.load(), "The credential goes before the data")
        assertEquals(setOf(account.id), journal.pending(), "The removing entry outlives the failed data step")
        assertEquals(1, signedOut)

        log.clear()
        data.removeFails = false
        val relaunched = flow()
        relaunched.resumeInterrupted()
        assertEquals(SignOutState.Idle, relaunched.state.value, "Nothing signed in is being signed out")
        advanceUntilIdle()
        assertEquals(listOf("data.sweep:null"), log)
        assertTrue(journal.pending().isEmpty())
        assertEquals(1, signedOut, "Finishing an earlier account's removal is not a sign-out")
    }

    @Test fun aSweepThatFailsKeepsTheEntriesForTheNextLaunch() = runTest {
        assertTrue(journal.begin("account-a"))
        data.sweepFails = true
        flow().resumeInterrupted(); advanceUntilIdle()
        assertEquals(setOf("account-a"), journal.pending())
    }

    /**
     * A's data step fails; the person connects B and signs B out in the same process. B's entry must
     * not replace A's, or A's rows are never removed. (The review's probe, now asserting the fix.)
     */
    @Test fun aSecondSignOutKeepsAnUnfinishedRemovalOfAnother() = runTest {
        val a = store.save("A", "https://a.example.invalid", "user-a", "pw-a", false)
        data.removeFails = true
        flow().apply { request(); advanceUntilIdle(); signOut(); advanceUntilIdle() }
        assertEquals(setOf(a.id), journal.pending(), "precondition: A's removal is unfinished")

        data.removeFails = false
        val b = store.save("B", "https://b.example.invalid", "user-b", "pw-b", false)
        flow().apply { request(); advanceUntilIdle(); signOut(); advanceUntilIdle() }
        assertEquals(setOf(a.id), journal.pending(), "A's entry must survive B's sign-out")
        assertNull(store.load())

        log.clear()
        flow().resumeInterrupted(); advanceUntilIdle()
        assertEquals(listOf("data.sweep:null"), log, "The next launch removes what A left: $log")
        assertTrue(journal.pending().isEmpty())
        assertFalse(b.id in journal.pending())
    }

    /**
     * B is signed in while A's removal is unfinished: B survives, the screen is not covered by a
     * "signing out" for A, and nothing reports a sign-out.
     */
    @Test fun finishingAnotherAccountsRemovalAtLaunchLeavesTheSignedInAccountAlone() = runTest {
        val b = store.save("B", "https://b.example.invalid", "user-b", "pw-b", false)
        assertTrue(journal.begin("account-a-uuid"))
        val flow = flow()
        flow.resumeInterrupted()
        assertEquals(SignOutState.Idle, flow.state.value)
        advanceUntilIdle()
        assertEquals(b.id, store.load()?.id, "The signed-in account B must survive")
        assertEquals(listOf("data.sweep:${b.id}"), log)
        assertEquals(SignOutState.Idle, flow.state.value)
        assertEquals(0, signedOut, "A's cleanup must not report B as signed out")
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun aProcessThatDiedBeforeDeletingTheCredentialDeletesItAtTheNextLaunch() = runTest {
        val account = saved()
        assertTrue(journal.begin(account.id))
        val relaunched = flow()
        relaunched.resumeInterrupted()
        assertNull(store.load(), "Deleted before the launch shows anything")
        assertEquals(SignOutState.Removing, relaunched.state.value)
        advanceUntilIdle()
        assertEquals(listOf("credential.delete", "data.remove:${account.id}", "data.sweep:null"), log)
        assertTrue(journal.pending().isEmpty())
        assertEquals(1, signedOut)
    }

    @Test fun everyLaunchSweepsAccountsOtherThanTheSavedOne() = runTest {
        val account = saved()
        flow().resumeInterrupted(); advanceUntilIdle()
        assertEquals(listOf("data.sweep:${account.id}"), log)
        assertEquals(account.id, store.load()?.id)
    }

    private fun TestScope.flow() = AccountSignOut(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        credentials = store,
        accountData = data,
        playback = playback,
        journal = journal,
        onSignedOut = { signedOut += 1 },
    )

    private fun saved(): StoredAccount =
        store.save("Fixture Server", "https://music.example.invalid", "fixture-user", "fixture-password", false)
}

internal fun changes(plays: Int, edits: Long): PendingChanges = PendingChanges((1..plays).map { "song-$it" }.toSet(), edits)

/** Reversible stand-in for Android Keystore, which Robolectric lacks; it keeps plaintext so a scan can see it. */
internal class PlaintextCipher : AccountCredentialCipher {
    val keys = mutableSetOf<String>()
    var unreadable = false
    var deleteFails = false
    var onDelete: (String) -> Unit = {}
    override fun encrypt(id: String, plaintext: ByteArray): ByteArray { keys += id; return plaintext }
    override fun decrypt(id: String, payload: ByteArray): ByteArray =
        if (unreadable) throw CredentialStoreException(CredentialStoreException.Reason.SecureStorageUnavailable) else payload
    override fun delete(id: String) {
        onDelete(id)
        if (deleteFails) throw IllegalStateException("keystore unavailable")
        keys -= id
    }
}

internal class RecordingStore(private val store: AccountCredentialStore, private val log: MutableList<String>) :
    AccountCredentialStore by store {
    var deleteFailure: CredentialStoreException.Reason? = null
    var onDelete: () -> Unit = {}
    override fun delete() {
        log += "credential.delete"
        onDelete()
        deleteFailure?.let { throw CredentialStoreException(it) }
        store.delete()
    }
}

internal class FakeAccountData(private val log: MutableList<String>) : AccountDataGateway {
    var pending: PendingChanges? = changes(0, 0)
    var afterSend: PendingChanges? = changes(0, 0)
    var removeFails = false
    var sweepFails = false
    var sendGate: CompletableDeferred<Unit>? = null
    var sending = false
    var sendCancelled = false
    var removeGate: CompletableDeferred<Unit>? = null

    override suspend fun pendingChanges(account: StoredAccount): PendingChanges? {
        log += "data.count:${account.id}"
        return pending
    }

    override suspend fun send(account: StoredAccount, changes: Boolean): PendingChanges? {
        log += "data.send:${account.id}:${if (changes) "changes" else "plays"}"
        sending = true
        try {
            sendGate?.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            sendCancelled = true
            throw cancelled
        }
        return afterSend
    }

    override suspend fun removeAccountData(serverId: String) {
        log += "data.remove:$serverId"
        removeGate?.await()
        if (removeFails) throw java.io.IOException("fixture removal failure")
    }

    override suspend fun sweep(activeAccountId: () -> String?): Set<String> {
        log += "data.sweep:${activeAccountId()}"
        if (sweepFails) throw java.io.IOException("fixture sweep failure")
        return emptySet()
    }
}

internal class FakePlayback(private val log: MutableList<String>) : PlaybackRelease {
    var succeeds = true
    override suspend fun release(): Boolean {
        log += "playback.release"
        return succeeds
    }
}
