package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * My Library for the Apple shells (spec §29.9), over the reader session of one
 * [AppleLibraryReaderClient]: what this account has added on a server that advertises
 * `dulcetLibrary`, apart from everything the server holds (Browse).
 *
 * **Shape** (§7.1–§7.3, CLAUDE.md trap 17). As [AppleLibraryPlaylistClient]: every call returns at
 * once, every delivery arrives on the main thread, and no Kotlin exception crosses. Membership
 * outcomes (`saved`, `notSaved`, `held`, `superseded`, `notRecorded`) arrive on
 * [AppleLibraryReaderClient.subscribeFavouriteOutcomes] with field `inLibrary`, value 1 or 0.
 *
 * **When the server does not offer it**, every publication says `availability = "unavailable"` with
 * empty lists, and the shell shows Browse exactly as it does today.
 */
@OptIn(ExperimentalAtomicApi::class)
public class AppleMyLibraryClient(private val reader: AppleLibraryReaderClient) {
    // Reader-thread state.
    private val subscriptions = mutableListOf<AppleMyLibrarySubscription>()
    private var sequence = 0
    /** The session whose changes this client listens to; a new session (a relaunch of the reader) gets its own. */
    private var listeningTo: LibraryReaderSession? = null

    /**
     * Publishes My Library sorted by [sort] — `recentlyAdded` (default for any other value) or
     * `alphabetical` — now and after every change, until closed.
     */
    public fun subscribe(sort: String, listener: AppleMyLibraryListener): AppleMyLibrarySubscription {
        val subscription = AppleMyLibrarySubscription(reader, this, sort.toSort(), listener)
        reader.onSession { session ->
            subscriptions += subscription
            if (listeningTo !== session) {
                listeningTo = session
                session.myLibrary.addChangeListener { if (listeningTo === session) publishAll(session) }
            }
            publish(session, subscription)
        }
        return subscription
    }

    /**
     * Detects the extension if this connection has not, then brings the device's library up to the
     * server's. Call it when My Library becomes visible; a reconnect refreshes by itself.
     */
    public fun refresh(completion: (AppleMyLibraryRefresh) -> Unit): AppleLibraryReaderOperation =
        reader.operation(completion, failed = { kind -> AppleMyLibraryRefresh("unknown", "failed", kind) }) { session ->
            val outcome = session.myLibrary.refresh()
            val availability = session.myLibrary.availability().appleKind()
            when (outcome) {
                MyLibraryRefreshOutcome.Unavailable -> AppleMyLibraryRefresh(availability, "unavailable", null)
                MyLibraryRefreshOutcome.Unchanged -> AppleMyLibraryRefresh(availability, "unchanged", null)
                is MyLibraryRefreshOutcome.Incremental -> AppleMyLibraryRefresh(availability, "incremental", null)
                is MyLibraryRefreshOutcome.Full -> AppleMyLibraryRefresh(availability, if (outcome.stable) "full" else "unsettled", null)
                is MyLibraryRefreshOutcome.Failed -> AppleMyLibraryRefresh(availability, "failed", outcome.error.readerErrorKind())
            }
        }

    /** Recomputes local playability after a download/cache or reachability change, without I/O. */
    public fun refreshLocal() { reader.onSession { session -> publishAll(session) } }

    /** Missing metadata only, selected by shared retention policy. Sequential reader requests
     * use the normal epoch/cache/concurrency path; an offline reader sends nothing. */
    public fun warmAutomaticDownloadDetails(albumIds: List<String>, trackIds: List<String>, playlistIds: List<String>,
        loadPlaylistIndex: Boolean, completion: (AppleMyLibraryRefresh) -> Unit): AppleLibraryReaderOperation =
        reader.operation(completion, failed = { AppleMyLibraryRefresh("unknown", "failed", it) }) { session ->
            val library = session.reader
            if (!library.online) return@operation AppleMyLibraryRefresh("unknown", "offline", null)
            if (loadPlaylistIndex) library.rereadList(LibraryQuery.Playlists)
            for ((index, id) in albumIds.distinct().withIndex()) {
                if (!library.online) break
                library.readAlbumDetail(id)
                // Start the first resolved album at once, then publish bounded batches while a
                // large collection warms. The executor need not wait for all its metadata.
                if (index % 10 == 0) publishAll(session)
            }
            for (id in trackIds.distinct()) {
                if (!library.online) break
                try {
                    val sent = library.send("getSong", mapOf("id" to id))
                    sent.requireOk("getSong", mapOf("id" to id))
                    parseLookupSong(sent.response.body, id)?.let { track ->
                        library.cache.writeEntities(CacheWriteStamp(sent.issueSeq, library.cache.now(), null),
                            CacheEntitySource.SongLookup, CacheEntities(tracks = listOf(track)))
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Throwable) { }
            }
            for (id in playlistIds.distinct()) {
                if (!library.online) break
                try { library.readPlaylistDetail(id, library.ensureEpoch()) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Throwable) { }
            }
            publishAll(session)
            AppleMyLibraryRefresh(session.myLibrary.availability().appleKind(), "warmed", null)
        }

    /** Adds an `artist`, `album` or `track`. False for any other kind, a blank id or a closed client. */
    public fun add(kind: String, rawId: String): Boolean = reader.changeInLibrary(kind, rawId, member = true)

    /** Removes one; an album also takes out its member songs. False as for [add]. */
    public fun remove(kind: String, rawId: String): Boolean = reader.changeInLibrary(kind, rawId, member = false)

    // ---- Internals ----------------------------------------------------------------------------------

    internal fun unregister(subscription: AppleMyLibrarySubscription) {
        subscriptions -= subscription
    }

    /** Reader thread. */
    internal fun republish(subscription: AppleMyLibrarySubscription) {
        reader.onSession { session -> if (subscription in subscriptions) publish(session, subscription) }
    }

    private fun publishAll(session: LibraryReaderSession) {
        subscriptions.toList().forEach { publish(session, it) }
    }

    private fun publish(session: LibraryReaderSession, subscription: AppleMyLibrarySubscription) {
        val publication = try {
            session.myLibrary.snapshot(subscription.sort).toApple(++sequence, session.reader)
        } catch (_: Throwable) {
            return
        }
        subscription.emit(publication)
    }
}

public interface AppleMyLibraryListener {
    public fun onMyLibraryPublication(publication: AppleMyLibraryPublication)
}

@OptIn(ExperimentalAtomicApi::class)
public class AppleMyLibrarySubscription internal constructor(
    private val reader: AppleLibraryReaderClient,
    private val owner: AppleMyLibraryClient,
    initialSort: MyLibrarySort,
    listener: AppleMyLibraryListener,
) {
    private val listener = AtomicReference<AppleMyLibraryListener?>(listener)

    /** Reader thread only. */
    internal var sort: MyLibrarySort = initialSort
        private set

    /** Re-sorts: `recentlyAdded` or `alphabetical`. A new publication follows. */
    public fun setSort(sort: String) {
        val parsed = sort.toSort()
        reader.onReader {
            this.sort = parsed
            owner.republish(this)
        }
    }

    public fun close() {
        if (listener.exchange(null) == null) return
        reader.onReader { owner.unregister(this) }
    }

    /** Reader thread. */
    internal fun emit(publication: AppleMyLibraryPublication) {
        if (listener.load() == null) return
        reader.onMain { if (!reader.isClosed) listener.load()?.onMyLibraryPublication(publication) }
    }
}

/**
 * One state of My Library.
 *
 * - [availability]: `available`, `unavailable` (the server does not offer it: show Browse only) or
 *   `unknown` (not yet detected on this device).
 * - [syncState]: `never` (no complete read yet), `syncing`, `current` (read this session),
 *   `cached` (from an earlier session; shown from the device, offline included) or `failed` (the
 *   last refresh failed; [errorKind] says why, the lists still show what the device has).
 * - Lists hold only items whose details the device has; [membership] covers every item.
 */
public class AppleMyLibraryPublication internal constructor(
    public val sequence: Int,
    public val availability: String,
    public val syncState: String,
    public val errorKind: String?,
    /** 0 when never read. */
    public val lastReadAtEpochMillis: Long,
    public val albums: List<AppleMyLibraryAlbum>,
    public val artists: List<AppleMyLibraryArtist>,
    public val songs: List<AppleMyLibrarySong>,
    private val snapshot: MyLibrarySnapshot,
) {
    /**
     * `added` (a member, a pending add included), `partial` (an album some of whose songs are
     * members), `notAdded` or `unknown`, for an `artist`, `album` or `track`; `unknown` for any other kind.
     */
    public fun membership(kind: String, rawId: String): String {
        val entity = LibraryEntityKind.fromWireName(kind) ?: return "unknown"
        return snapshot.membership(entity.cacheKind, rawId).appleKind()
    }
}

public class AppleMyLibraryAlbum internal constructor(
    public val rawId: String,
    public val title: String,
    public val artistName: String?,
    public val artistRawId: String?,
    /** 0 when unknown. */
    public val year: Int,
    public val artworkKey: String?,
    /** -1 when unknown. */
    public val songCount: Int,
    /** False when the album is shown only for its member songs. */
    public val explicitlyAdded: Boolean,
    public val memberSongCount: Int,
    /** An add or remove of it, or of one of its member songs, has not reached the server yet. */
    public val pending: Boolean,
)

public class AppleMyLibraryArtist internal constructor(
    public val rawId: String,
    public val name: String,
    public val artworkKey: String?,
    /** -1 when unknown. */
    public val albumCount: Int,
    public val explicitlyAdded: Boolean,
    public val pending: Boolean,
)

public class AppleMyLibrarySong internal constructor(
    public val rawId: String,
    public val title: String,
    public val albumRawId: String?,
    public val albumTitle: String?,
    public val artistName: String?,
    /** -1 when unknown. */
    public val durationMilliseconds: Long,
    public val artworkKey: String?,
    public val pending: Boolean,
    public val sourceContainer: String?,
    public val playability: String?,
)

/**
 * How a refresh ended. [outcome]: `unchanged`, `incremental`, `full`, `unsettled` (the library kept
 * changing during every read; the device keeps what it had and reads again next time),
 * `unavailable` or `failed` ([errorKind]: the reader's vocabulary, or `cancelled`, `closed`,
 * `internalFailure` when the refresh never ran).
 */
public class AppleMyLibraryRefresh internal constructor(
    public val availability: String,
    public val outcome: String,
    public val errorKind: String?,
)

// ---- Closed vocabularies -------------------------------------------------------------------------------

private fun String.toSort(): MyLibrarySort = if (this == "alphabetical") MyLibrarySort.Alphabetical else MyLibrarySort.RecentlyAdded

internal fun MyLibraryAvailability.appleKind(): String = when (this) {
    MyLibraryAvailability.Available -> "available"
    MyLibraryAvailability.Unavailable -> "unavailable"
    MyLibraryAvailability.Unknown -> "unknown"
}

internal fun MyLibraryMembership.appleKind(): String = when (this) {
    MyLibraryMembership.Added -> "added"
    MyLibraryMembership.Partial -> "partial"
    MyLibraryMembership.NotAdded -> "notAdded"
    MyLibraryMembership.Unknown -> "unknown"
}

internal fun MyLibrarySyncState.appleKind(): String = when (this) {
    MyLibrarySyncState.Never -> "never"
    MyLibrarySyncState.Syncing -> "syncing"
    MyLibrarySyncState.Current -> "current"
    MyLibrarySyncState.Cached -> "cached"
    MyLibrarySyncState.Failed -> "failed"
}

internal fun MyLibrarySnapshot.toApple(sequence: Int, reader: LibraryReader? = null): AppleMyLibraryPublication {
    val downloaded = reader?.downloads?.downloadedTrackRawIds(reader.cache.serverId).orEmpty()
    return AppleMyLibraryPublication(
    sequence = sequence,
    availability = availability.appleKind(),
    syncState = syncState.appleKind(),
    errorKind = lastError?.readerErrorKind(),
    lastReadAtEpochMillis = lastReadAtWall ?: 0L,
    albums = if (availability == MyLibraryAvailability.Unavailable) emptyList() else albums.map { album ->
        val credit = album.record.credits.firstOrNull { it.role == CreditRole.AlbumArtist } ?: album.record.credits.firstOrNull()
        AppleMyLibraryAlbum(
            album.record.rawId, album.record.title, credit?.name, credit?.artistRawId, album.record.year ?: 0,
            album.record.artworkKey, album.record.songCount ?: -1, album.explicitlyAdded, album.memberSongCount, album.pending,
        )
    },
    artists = if (availability == MyLibraryAvailability.Unavailable) emptyList() else artists.map {
        AppleMyLibraryArtist(it.record.rawId, it.record.name, it.record.artworkKey, it.record.albumCount ?: -1, it.explicitlyAdded, it.pending)
    },
    songs = if (availability == MyLibraryAvailability.Unavailable) emptyList() else songs.map { song ->
        AppleMyLibrarySong(
            song.record.rawId, song.record.title, song.record.albumRawId, song.record.albumTitle,
            song.record.credits.firstOrNull()?.name, song.record.durationMilliseconds ?: -1L, song.record.artworkKey, song.pending, song.record.sourceContainer?.appleKind(),
            reader?.trackPlayability(song.record.rawId, downloaded)?.appleKind(),
        )
    },
    snapshot = this,
)
}
