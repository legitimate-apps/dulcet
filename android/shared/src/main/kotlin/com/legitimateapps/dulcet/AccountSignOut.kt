package com.legitimateapps.dulcet

import android.content.Context
import android.content.SharedPreferences
import com.legitimateapps.dulcet.core.AndroidAccountData
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidLibraryReaderAccount
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.playback.releasePlaybackForSignOut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What an account has not yet sent its server (spec §14.7 step 2). */
public data class PendingChanges(
    /** Unsent plays, each by its identity, so a play recorded after the offer is told apart. */
    val plays: Set<String>,
    /** Unsent favourite, rating and playlist changes, as the account's library reader counts them. */
    val edits: Long,
) {
    init {
        require(edits >= 0)
    }

    val total: Long get() = plays.size + edits

    /**
     * Whether this holds something [offered] did not: a play not among its plays, or more changes
     * than it counted. Plays are compared by identity, because the playback service can send one
     * and record another while the offer is open, leaving the count unchanged. Changes are compared
     * by count: they are made only on the app's own screens, which the offer covers, so none can be
     * added while it is open — only sent by the reader's own reconnect.
     */
    public fun exceeds(offered: PendingChanges): Boolean = !offered.plays.containsAll(plays) || edits > offered.edits
}

/** What the device holds for accounts, reached through the core (spec §14.7 steps 2, 5 and 6). */
public interface AccountDataGateway {
    /** The account's unsent plays and changes; null when either cannot be read, never a guessed zero. */
    public suspend fun pendingChanges(account: StoredAccount): PendingChanges?

    /** Sends the unsent plays, and the changes when [changes], once; then what is still unsent. */
    public suspend fun send(account: StoredAccount, changes: Boolean): PendingChanges?

    /**
     * Deletes everything the device holds for [serverId], once the process's library reader has
     * terminated. Throws when anything may survive.
     */
    public suspend fun removeAccountData(serverId: String)

    /** Deletes what every account but the one saved now owns; returns the ids whose rows it deleted. */
    public suspend fun sweep(activeAccountId: () -> String?): Set<String>
}

/**
 * The production gateway. The changes' count and their flush are the account's library reader's —
 * its session count, which is unknown when a queued change cannot be decoded, and its reconnect,
 * which flushes the outboxes first — so there is one definition of both. Plays are the scrobble
 * outbox's, which the reader does not own.
 */
public class CoreAccountDataGateway(context: Context) : AccountDataGateway {
    private val context = context.applicationContext

    override suspend fun pendingChanges(account: StoredAccount): PendingChanges? {
        val plays = AndroidAccountData(context, account.id).pendingPlays() ?: return null
        val edits = suspendCancellableCoroutine<Long?> { continuation ->
            reader(account).pendingChangeCount { count -> if (continuation.isActive) continuation.resume(count) }
        } ?: return null
        return PendingChanges(plays, edits)
    }

    override suspend fun send(account: StoredAccount, changes: Boolean): PendingChanges? {
        AndroidAccountData(context, account.id).submitPlays(
            PlaybackEndpointAccount(account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp),
        )
        // A reconnect flushes both outboxes before anything else; what it could not send stays
        // queued, and the count below says how much.
        if (changes) suspendCancellableCoroutine<Unit> { continuation ->
            reader(account).reconnect { if (continuation.isActive) continuation.resume(Unit) }
        }
        return pendingChanges(account)
    }

    override suspend fun removeAccountData(serverId: String) {
        AndroidAccountData(context, serverId).removeAccountData()
    }

    override suspend fun sweep(activeAccountId: () -> String?): Set<String> =
        AndroidAccountData.sweepAccountsOtherThan(context, activeAccountId)

    /**
     * The process's reader for the account: the one its screens already use, since the account is
     * the same value. A sign-out is asked for in the foreground, so a reader created here starts so.
     */
    private fun reader(account: StoredAccount): AndroidLibraryReader = AndroidLibraryReader.forAccount(
        context,
        AndroidLibraryReaderAccount(account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp),
        foreground = true,
    )
}

/** Stops playback and closes the controller that holds the signing-out account's credentials. */
public fun interface PlaybackRelease {
    /** False when playback could not be reached, so the account must not be removed yet. */
    public suspend fun release(): Boolean
}

/** Releases the playback service's controller through its local binding (spec §14.7). */
public class ServicePlaybackRelease(context: Context) : PlaybackRelease {
    private val context = context.applicationContext
    override suspend fun release(): Boolean = releasePlaybackForSignOut(context)
}

/**
 * The spec's `removing` marker (§14.7), one entry per account: written once the person has chosen
 * to sign out and before the credential is deleted, and cleared only once the account's data is
 * gone. A second sign-out never overwrites the first's entry, so every removal the app did not live
 * to finish is resumed at a later launch. It also keeps the ids whose secure-storage key could not
 * be deleted after their record was, for a later launch to retry.
 */
public class AccountRemovalJournal(private val preferences: SharedPreferences) {
    public constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    public fun pending(): Set<String> = read(REMOVING)
    public fun begin(serverId: String): Boolean = write(REMOVING, pending() + serverId)
    public fun end(serverId: String) {
        if (serverId in pending()) write(REMOVING, pending() - serverId)
    }

    public fun keysLeft(): Set<String> = read(KEYS)
    public fun keyLeft(serverId: String): Boolean = write(KEYS, keysLeft() + serverId)
    public fun keyDeleted(serverId: String) {
        if (serverId in keysLeft()) write(KEYS, keysLeft() - serverId)
    }

    // A copy: the set SharedPreferences returns must not be modified.
    @Synchronized private fun read(key: String): Set<String> = preferences.getStringSet(key, null).orEmpty().toSet()

    /**
     * False when the value did not reach disk. `commit()` updates the in-memory map even then, so
     * the previous value is put back: this process must not act on a mark, or a key, that a
     * relaunch would not find.
     */
    @Synchronized private fun write(key: String, value: Set<String>): Boolean {
        val previous = read(key)
        if (preferences.edit().putStringSet(key, value).commit()) return true
        preferences.edit().putStringSet(key, previous).apply()
        return false
    }

    public companion object {
        public const val PREFERENCES_NAME: String = "dulcet.account.removal"
        private const val REMOVING = "removing"
        private const val KEYS = "keysLeft"
    }
}

/** Where a sign-out stands. The account stays on screen unless [hidesAccount]. */
public sealed interface SignOutState {
    public val hidesAccount: Boolean get() = false

    public data object Idle : SignOutState
    /** Reading what the account still owes its server. */
    public data object Checking : SignOutState
    /** Nothing is owed: an ordinary confirmation. */
    public data class Confirm(val serverName: String) : SignOutState
    /**
     * Plays or changes have not reached the server (§14.7 step 2). [afterSend] says a send was
     * attempted and these are what it left; [hidesAccount] that playback has already been released.
     */
    public data class Offer(
        val pending: PendingChanges,
        val afterSend: Boolean,
        override val hidesAccount: Boolean,
    ) : SignOutState
    /**
     * What is owed could not be read — or the account's saved record itself cannot be, which leaves
     * nothing to send with — so it is stated as unknown rather than as zero.
     */
    public data class Unknown(override val hidesAccount: Boolean) : SignOutState
    public data object Sending : SignOutState { override val hidesAccount: Boolean get() = true }
    public data object Removing : SignOutState { override val hidesAccount: Boolean get() = true }
    /** Nothing was removed; the account is still saved. */
    public data object Failed : SignOutState
}

/**
 * Signing out on Android (spec §14.7, and its Android paragraph for where this order differs).
 * Nothing destructive happens until the person has answered the offer of step 2, and Stay signed in
 * is offered until then. From the choice onward it is refused: the choice releases playback and may
 * send what is owed, neither of which Stay could undo. It is offered again only where the offer is
 * made again, and choosing it there keeps the account, since nothing is marked or deleted before
 * the recount has passed.
 *
 * On a choice to sign out: playback is released first, so no new play is recorded and no other
 * delivery races the send; what is owed is read again, and anything not offered — a play by its
 * identity, or a larger count of changes — is offered again rather than discarded unseen. Then the
 * account's `removing` entry is written, its credential deleted (step 3), and — once the process's
 * library reader has terminated — its cached artwork and downloaded media (step 5) and every row it
 * owns (step 6) deleted. Android downloads nothing, so step 4 has no tasks, and the credential store
 * is the account record, so step 7 is step 3. A removal whose data step fails — including a reader
 * that has not terminated within its bound — keeps its entry, lands on the connect form like a
 * finished one, and is finished at a later launch by [resumeInterrupted].
 *
 * An account whose saved record cannot be read can still be signed out: its id is read without the
 * record, what it owes is stated as unknown, and nothing is sent.
 *
 * Left in place, as not account data: the phone's last-open tab (`dulcet.ui`), which names a tab and
 * nothing of the account.
 */
public class AccountSignOut(
    private val scope: CoroutineScope,
    private val credentials: AccountCredentialStore,
    private val accountData: AccountDataGateway,
    private val playback: PlaybackRelease,
    private val journal: AccountRemovalJournal,
    private val onSignedOut: () -> Unit,
) {
    private val mutableState = MutableStateFlow<SignOutState>(SignOutState.Idle)
    public val state: StateFlow<SignOutState> = mutableState.asStateFlow()

    /** The account being signed out; [account] is null when its record cannot be read. */
    private class Target(val id: String, val account: StoredAccount?)

    private var job: Job? = null
    private var target: Target? = null
    private var offered: PendingChanges? = null

    /** The saved account's id, readable or not; null when none is saved. */
    public fun savedAccountId(): String? = try { credentials.activeAccountId() } catch (_: CredentialStoreException) { null }

    /** The saved account's id when its record cannot be read, so the screen can offer to sign it out. */
    public fun unreadableAccountId(): String? {
        val id = try { credentials.activeAccountId() } catch (_: CredentialStoreException) { return null } ?: return null
        return try {
            credentials.load()
            null
        } catch (_: CredentialStoreException) {
            id
        }
    }

    public fun request() {
        val current = mutableState.value
        if (current != SignOutState.Idle && current != SignOutState.Failed) return
        val id = try {
            credentials.activeAccountId()
        } catch (_: CredentialStoreException) {
            mutableState.value = SignOutState.Failed
            return
        }
        if (id == null) {
            reset(SignOutState.Idle)
            onSignedOut()
            return
        }
        val stored = try { credentials.load()?.takeIf { it.id == id } } catch (_: CredentialStoreException) { null }
        val chosen = Target(id, stored)
        target = chosen
        mutableState.value = SignOutState.Checking
        job = scope.launch {
            val pending = stored?.let { account -> read { accountData.pendingChanges(account) } }
            offered = pending
            mutableState.value = when {
                stored == null || pending == null -> SignOutState.Unknown(hidesAccount = false)
                pending.total == 0L -> SignOutState.Confirm(stored.serverName)
                else -> SignOutState.Offer(pending, afterSend = false, hidesAccount = false)
            }
        }
    }

    /** Stay signed in: abandons the sign-out, including a send in progress. Refused once removing. */
    public fun stay() {
        if (mutableState.value == SignOutState.Removing) return
        job?.cancel()
        reset(SignOutState.Idle)
    }

    /** Send what is owed — plays, and changes when there are any — then sign out if nothing is left. */
    public fun send() {
        val chosen = target ?: return
        val stored = chosen.account ?: return
        if (mutableState.value !is SignOutState.Offer) return
        val changes = (offered?.edits ?: 0L) > 0L
        mutableState.value = SignOutState.Sending
        job = scope.launch {
            if (!playback.release()) return@launch reset(SignOutState.Failed)
            val remaining = read { accountData.send(stored, changes) }
            offered = remaining
            when {
                remaining == null -> mutableState.value = SignOutState.Unknown(hidesAccount = true)
                remaining.total == 0L -> remove(chosen)
                else -> mutableState.value = SignOutState.Offer(remaining, afterSend = true, hidesAccount = true)
            }
        }
    }

    /** Sign out from the confirmation, or discard what the offer stated. */
    public fun signOut() {
        val chosen = target ?: return
        val stored = chosen.account ?: return
        val current = mutableState.value
        if (current !is SignOutState.Confirm && current !is SignOutState.Offer) return
        mutableState.value = SignOutState.Removing
        job = scope.launch {
            if (!playback.release()) return@launch reset(SignOutState.Failed)
            val now = read { accountData.pendingChanges(stored) }
            val seen = offered ?: PendingChanges(emptySet(), 0)
            when {
                now == null -> mutableState.value = SignOutState.Unknown(hidesAccount = true)
                now.exceeds(seen) -> {
                    offered = now
                    mutableState.value = SignOutState.Offer(now, afterSend = false, hidesAccount = true)
                }
                else -> remove(chosen)
            }
        }
    }

    /** Sign out although what is owed could not be read; the person was told it may discard some. */
    public fun signOutAnyway() {
        val chosen = target ?: return
        if (mutableState.value !is SignOutState.Unknown) return
        mutableState.value = SignOutState.Removing
        job = scope.launch {
            if (!playback.release()) return@launch reset(SignOutState.Failed)
            remove(chosen)
        }
    }

    public fun dismissFailure() {
        if (mutableState.value == SignOutState.Failed) mutableState.value = SignOutState.Idle
    }

    /**
     * At launch, before any account is shown: finishes what earlier processes began (§14.7).
     *
     * - A key whose deletion failed after its record was deleted is deleted again.
     * - If the saved account is itself marked `removing`, the previous process died after the person
     *   chose to sign out and before its credential was deleted. The credential is deleted here,
     *   synchronously, so no screen can read the account; its data is then removed behind the
     *   signing-out screen, which reports the sign-out when it is done.
     * - Then, in the background, every account but the saved one loses its rows, artwork and
     *   downloads ([AccountDataGateway.sweep]): the removals of earlier processes that did not
     *   finish, and anything written for a signed-out account after its removal. Their `removing`
     *   entries are cleared once that succeeds. This touches neither the screen nor the saved
     *   account, so an account signed in meanwhile is never shown as signing out.
     */
    public fun resumeInterrupted() {
        val active = try { credentials.activeAccountId() } catch (_: CredentialStoreException) { return }
        for (id in journal.keysLeft()) {
            if (id == active) continue
            try {
                credentials.deleteKey(id)
                journal.keyDeleted(id)
            } catch (_: Exception) {
                // Kept for the next launch; the key alone decrypts nothing.
            }
        }
        val earlier = journal.pending() - setOfNotNull(active)
        val foreground = active?.takeIf { it in journal.pending() && deleteCredential(it) }
        if (foreground != null) mutableState.value = SignOutState.Removing
        scope.launch {
            if (foreground != null) removeData(foreground)
            try {
                accountData.sweep { credentials.activeAccountId() }
                earlier.forEach(journal::end)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The entries stay; the next launch sweeps again.
            }
            if (foreground != null) {
                reset(SignOutState.Idle)
                onSignedOut()
            }
        }
    }

    private suspend fun remove(chosen: Target) {
        mutableState.value = SignOutState.Removing
        if (!journal.begin(chosen.id)) return reset(SignOutState.Failed)
        if (!deleteCredential(chosen.id)) {
            journal.end(chosen.id)
            return reset(SignOutState.Failed)
        }
        removeData(chosen.id)
        reset(SignOutState.Idle)
        onSignedOut()
    }

    /**
     * True once [serverId]'s record is gone from disk. [AccountCredentialStore.delete] judges that
     * by the preferences' `commit()`, never by their in-memory map, and fails with
     * `PersistenceFailed` when the record may still be on disk. A failure to delete only the key,
     * after the record, still counts as deleted, and the key is retried at a later launch.
     *
     * The key is recorded as left BEFORE the record is deleted and cleared once both are gone, so a
     * process that dies between the record's deletion and the key's still leaves the key for a
     * later launch. While the record is saved the launch retry skips its id.
     *
     * Keyed by [serverId]: an account saved since the sign-out was asked for — a connect that
     * finished meanwhile — is a different id and is left saved. [AccountCredentialStore.delete]
     * takes no id, so the check and the delete must see the same saved account: both run on the
     * main thread, as every save does ([connectAndSaveAccount]'s callers), with no suspension
     * between them.
     */
    private fun deleteCredential(serverId: String): Boolean {
        val active = try { credentials.activeAccountId() } catch (_: CredentialStoreException) { return false }
        if (active != serverId) return true
        if (!journal.keyLeft(serverId)) return false
        try {
            credentials.delete()
        } catch (failure: CredentialStoreException) {
            if (failure.reason == CredentialStoreException.Reason.SecureStorageUnavailable) return true
            journal.keyDeleted(serverId)
            return false
        }
        journal.keyDeleted(serverId)
        return true
    }

    private suspend fun removeData(serverId: String) {
        try {
            accountData.removeAccountData(serverId)
            journal.end(serverId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // The credential is already gone; the entry stays, so a later launch finishes this.
        }
    }

    /** A read that fails is unknown, never zero. */
    private suspend fun read(block: suspend () -> PendingChanges?): PendingChanges? = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun reset(next: SignOutState) {
        job = null
        target = null
        offered = null
        mutableState.value = next
    }
}
