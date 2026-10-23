package com.legitimateapps.dulcet

import android.content.Context
import com.legitimateapps.dulcet.core.AndroidAccountData
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A requested, authenticated replacement; no state changes before the person confirms. */
public data class AccountReplacementOffer(val serverName: String, val downloadCount: Int)

public interface AccountReplacement {
    public suspend fun retirePlayback(): Boolean = true
    public suspend fun confirmAndRemove(account: StoredAccount, stillWanted: () -> Boolean): Boolean
}

/** Shared by both Android shells. The offer counts stored downloads without opening media. */
public class AndroidAccountReplacement(
    context: Context,
    private val credentials: AccountCredentialStore,
    private val playback: PlaybackRelease = ServicePlaybackRelease(context),
    private val data: AccountDataGateway = CoreAccountDataGateway(context),
    private val countDownloads: suspend (String) -> Int = { AndroidAccountData.downloadedTrackCount(context, it) },
) : AccountReplacement {
    private val mutableOffer = MutableStateFlow<AccountReplacementOffer?>(null)
    public val offer = mutableOffer.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null
    private val journal = AccountRemovalJournal(context)

    public fun answer(confirmed: Boolean) { answer?.complete(confirmed) }
    override suspend fun retirePlayback(): Boolean = playback.release()

    override suspend fun confirmAndRemove(account: StoredAccount, stillWanted: () -> Boolean): Boolean {
        val count = try { countDownloads(account.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed, failure) }
        val pending = CompletableDeferred<Boolean>()
        answer = pending
        mutableOffer.value = AccountReplacementOffer(account.serverName, count)
        val accepted = try { pending.await() } finally { mutableOffer.value = null; answer = null }
        if (!accepted || !stillWanted()) return false
        if (!playback.release()) throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        if (!stillWanted() || account !in credentials.savedAccounts()) return false
        if (!journal.begin(account.id)) throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        if (!journal.keyLeft(account.id)) {
            journal.end(account.id)
            throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        }
        try {
            credentials.remove(account.id)
            journal.keyDeleted(account.id)
        } catch (failure: CredentialStoreException) {
            if (failure.reason != CredentialStoreException.Reason.SecureStorageUnavailable) {
                journal.end(account.id)
                journal.keyDeleted(account.id)
                throw failure
            }
            // The encrypted record is gone; keep its failed key deletion for the launch retry.
        }
        // A failed removal retains its journal. The new namespace can never read the old rows.
        try { data.removeAccountData(account.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed, failure) }
        journal.end(account.id)
        return true
    }
}

/** Selection keeps every server's persisted state. Only its installed OS playback is retired. */
public suspend fun selectSavedServer(id: String, store: AccountCredentialStore, playback: PlaybackRelease): Boolean {
    if (id == store.activeAccountId()) return true
    if (id !in store.savedAccountIds()) return false
    if (!playback.release()) return false
    com.legitimateapps.dulcet.core.AndroidServerIdentity.requireValidation(id)
    return store.activate(id) != null
}
