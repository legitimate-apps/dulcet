package com.legitimateapps.dulcet.core

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * The device-held half of signing out on Android (spec §14.7): the account's unsent plays, sending
 * them, and deleting everything the device holds for it. The count and the flush of favourite,
 * rating and playlist changes belong to the account's library reader
 * ([AndroidLibraryReader.pendingChangeCount], and its reconnect's flush), which is their one
 * definition; this class counts only the scrobble outbox, which the reader does not own.
 *
 * **What can still write after a removal.** [removeAccountData] waits for the process's library
 * reader to terminate before it deletes anything, because that reader writes seen-cache rows on its
 * own thread. Its termination is bounded, not exact (see [AndroidLibraryReader.close]): a read that
 * ignores cancellation past those bounds can still write a row afterwards, and so can any writer
 * this class does not know of. Such a row is orphaned under an account id that is never reused, and
 * [sweepAccountsOtherThan], run at every launch, deletes it. An artwork load that finishes after the
 * removal does not recreate the account's directory ([isRemoved]); one already past that check when
 * the removal runs can, and the sweep deletes that too.
 *
 * Every entry point opens its own connection to the database and closes it before returning.
 */
public class AndroidAccountData internal constructor(
    private val serverId: String,
    private val openStore: () -> DulcetDatabaseStore,
    private val artworkRoot: File,
    private val downloadRoot: File,
    private val senderFor: (PlaybackEndpointAccount) -> ScrobbleEndpointSender,
    private val wall: OutboxWallClock,
    private val monotonic: OutboxMonotonicClock,
    /** Returns once the process's library reader has terminated (§14.7 step 6 waits for it). */
    private val closeLibraryReader: suspend () -> Unit,
) {
    public constructor(context: Context, serverId: String) : this(
        serverId = serverId,
        openStore = { DulcetDriverFactory(context.applicationContext).openDulcetDatabase() },
        artworkRoot = artworkRootFor(context.applicationContext, serverId),
        downloadRoot = downloadRootFor(context.applicationContext),
        senderFor = { account -> ScrobbleEndpointSender(account) },
        wall = OutboxWallClock(System::currentTimeMillis),
        monotonic = OutboxMonotonicClock { SystemClock.elapsedRealtime().milliseconds },
        closeLibraryReader = ::closeProcessLibraryReader,
    )

    init {
        require(serverId.isNotBlank())
    }

    /**
     * The account's unsent plays, each by its identity (the song and when its session started), or
     * null when they cannot be read. Identities rather than a count, so a sign-out can tell a play
     * it has not offered from one it has, even when one was sent and another recorded meanwhile.
     * An unreadable outbox is never reported as empty: that would let a sign-out discard it unasked.
     */
    public suspend fun pendingPlays(): Set<String>? = withContext(Dispatchers.IO) {
        try {
            withStore(openStore) { store ->
                PersistentScrobbleOutbox(store.database, wall).pending(ServerId(serverId))
                    .mapTo(mutableSetOf()) { "${it.rawId}\u0000${it.sessionStartWallClock.epochMilliseconds}" }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends the account's unsent plays once, oldest first, and returns those still unsent afterwards
     * (null when that cannot be read). Delivery stops at the first play the server does not accept;
     * that play and every later one stay unsent.
     */
    public suspend fun submitPlays(account: PlaybackEndpointAccount): Set<String>? {
        require(account.providerInstanceId == serverId) { "the account must be the one being signed out" }
        return withContext(Dispatchers.IO) {
            val sender = senderFor(account)
            try {
                withStore(openStore) { store ->
                    ScrobbleOutboxDeliveryWorker(
                        ServerId(serverId),
                        PersistentScrobbleOutbox(store.database, wall),
                        sender,
                        wall,
                        monotonic,
                        ScrobbleOutboxDiagnosticSink {},
                    ).onForeground()
                }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Whatever did not go stays unsent; the read below says which.
            } finally {
                sender.close()
            }
            pendingPlays()
        }
    }

    /**
     * Deletes everything the device holds for the account, in the order of spec §14.7: once the
     * process's library reader has terminated (step 6 waits for it), the account's cached artwork
     * and downloaded media (step 5), then every database row it owns (step 6: the seen-cache and its
     * binding, the outboxes, the queue, resume positions and the library). Idempotent, so an
     * interrupted sign-out can run it again at launch. Throws when anything the account owns may
     * survive; nothing is deleted before the reader has terminated.
     */
    public suspend fun removeAccountData() {
        closeLibraryReader()
        removeStored(serverId, openStore, artworkRoot, downloadRoot)
    }

    public companion object {
        /** The directory [AndroidArtworkRepository] caches this account's artwork in. */
        internal fun artworkRootFor(context: Context, serverId: String): File =
            File(artworkParentFor(context), AndroidArtworkRepository.digest(serverId))

        internal fun artworkParentFor(context: Context): File = File(context.cacheDir, "artwork")

        /**
         * Where downloaded media lives on Android. Android downloads nothing yet (spec §14.5), so no
         * row names a file here; a removal is still given the directory an Android download executor
         * must use, so that its rows' files are deleted with them once one exists.
         */
        internal fun downloadRootFor(context: Context): File = File(context.noBackupFilesDir, "downloads")

        /** Removed in this process; ids are random and never reused, so this never needs undoing. */
        private val removed: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Whether [serverId]'s data was removed in this process: nothing may be written for it again. */
        internal fun isRemoved(serverId: String): Boolean = serverId in removed

        /**
         * Deletes the rows, cached artwork and downloaded media of every account except the one saved
         * now — accounts a sign-out began and did not finish, and anything written for a signed-out
         * account after its removal (see the class documentation). Run at launch, before any account
         * is signed out in this process. It closes no library reader: the only reader it could meet
         * belongs to the saved account, which it keeps. Returns the ids whose rows it deleted.
         * Throws, having deleted what it could, when anything may survive.
         *
         * [activeAccountId] is read only AFTER the rows and directories are listed. An account saved
         * after that read cannot own a row or an artwork directory at listing time — both are written
         * only for an account already saved — so nothing an account saved meanwhile owns is ever
         * listed, whichever moment it was saved.
         */
        public suspend fun sweepAccountsOtherThan(context: Context, activeAccountId: () -> String?): Set<String> {
            val application = context.applicationContext
            return sweep(
                openStore = { DulcetDriverFactory(application).openDulcetDatabase() },
                artworkParent = artworkParentFor(application),
                downloadRoot = downloadRootFor(application),
                activeAccountId = activeAccountId,
            )
        }

        internal suspend fun sweep(
            openStore: () -> DulcetDatabaseStore,
            artworkParent: File,
            downloadRoot: File,
            activeAccountId: () -> String?,
        ): Set<String> = withContext(Dispatchers.IO) {
            val withRows = withStore(openStore) { store ->
                store.database.serverDataQueries.selectServerIdsWithRows().executeAsList().toSet()
            }
            val directories = artworkParent.listFiles { entry -> entry.isDirectory }.orEmpty().toList()
            val active = activeAccountId()
            val keptDirectory = active?.let(AndroidArtworkRepository::digest)
            var failure: IOException? = null
            val swept = mutableSetOf<String>()
            for (id in withRows) {
                if (id == active) continue
                removed += id
                withStore(openStore) { store -> DownloadPolicyEngine(store.database, downloadFiles(downloadRoot)).removeAccountData(id) }
                swept += id
            }
            for (directory in directories) {
                if (directory.name == keptDirectory) continue
                if (!directory.deleteRecursively()) failure = IOException("cached artwork could not be deleted")
            }
            failure?.let { throw it }
            swept
        }

        private suspend fun removeStored(serverId: String, openStore: () -> DulcetDatabaseStore, artworkRoot: File, downloadRoot: File) =
            withContext(Dispatchers.IO) {
                // Before the directory goes, so an artwork load finishing after this cannot recreate it.
                removed += serverId
                if (artworkRoot.exists() && !artworkRoot.deleteRecursively()) {
                    throw IOException("cached artwork for the account could not be deleted")
                }
                // The account's downloaded files, then every row, in one transaction checked to leave none.
                withStore(openStore) { store ->
                    DownloadPolicyEngine(store.database, downloadFiles(downloadRoot)).removeAccountData(serverId)
                }
            }

        private fun downloadFiles(root: File) = DownloadFileStore(root.path, FileSystem.SYSTEM)

        private inline fun <T> withStore(openStore: () -> DulcetDatabaseStore, block: (DulcetDatabaseStore) -> T): T {
            val store = openStore()
            try {
                return block(store)
            } finally {
                store.close()
            }
        }

        /** Closes the process's library reader and returns once it has terminated. */
        internal suspend fun closeProcessLibraryReader(): Unit = suspendCancellableCoroutine { continuation ->
            AndroidLibraryReader.closeCurrent { if (continuation.isActive) continuation.resume(Unit) }
        }
    }
}
