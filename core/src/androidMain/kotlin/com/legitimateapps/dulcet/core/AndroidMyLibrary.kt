package com.legitimateapps.dulcet.core

import java.util.concurrent.atomic.AtomicReference

/**
 * My Library for the Android shells (spec §29.9), over one [AndroidLibraryReader]'s session: what
 * this account has added on a server that advertises `dulcetLibrary`, apart from everything the
 * server holds (Browse). Every call returns at once; listeners and completions run on the main
 * thread. Membership outcomes arrive on [AndroidLibraryReader.addChangeOutcomeListener] with
 * [AndroidLibraryChangeField.InLibrary], value 1 added, 0 removed.
 *
 * On a server that does not offer it every publication says [AndroidMyLibraryAvailability.Unavailable]
 * with empty lists, and the shell shows Browse exactly as it does today.
 */
public class AndroidMyLibrary(private val reader: AndroidLibraryReader) {
    // Reader-thread state.
    private val registrations = mutableListOf<AndroidMyLibraryRegistration>()
    private var listeningTo: LibraryReaderSession? = null

    /** Publishes My Library sorted by [sort], now and after every change, until closed. */
    public fun observe(sort: AndroidMyLibrarySort, listener: (AndroidMyLibraryPublication) -> Unit): AndroidMyLibraryRegistration {
        val registration = AndroidMyLibraryRegistration(reader, this, sort, listener)
        reader.onReader {
            val session = reader.composed()?.session ?: return@onReader
            registrations += registration
            if (listeningTo !== session) {
                listeningTo = session
                session.myLibrary.addChangeListener { if (listeningTo === session) publishAll(session) }
            }
            publish(session, registration)
        }
        return registration
    }

    /** Detects the extension if this connection has not, then brings the device's library up to the server's. */
    public fun refresh(completion: (AndroidMyLibraryRefresh) -> Unit = {}) {
        reader.operation(completion, { failure -> AndroidMyLibraryRefresh(AndroidMyLibraryAvailability.Unknown, AndroidMyLibraryRefreshOutcome.Failed, null, failure) }) { session ->
            val outcome = session.myLibrary.refresh()
            val availability = session.myLibrary.availability().toAndroid()
            when (outcome) {
                MyLibraryRefreshOutcome.Unavailable -> AndroidMyLibraryRefresh(availability, AndroidMyLibraryRefreshOutcome.Unavailable, null, null)
                MyLibraryRefreshOutcome.Unchanged -> AndroidMyLibraryRefresh(availability, AndroidMyLibraryRefreshOutcome.Unchanged, null, null)
                is MyLibraryRefreshOutcome.Incremental -> AndroidMyLibraryRefresh(availability, AndroidMyLibraryRefreshOutcome.Incremental, null, null)
                is MyLibraryRefreshOutcome.Full -> AndroidMyLibraryRefresh(
                    availability, if (outcome.stable) AndroidMyLibraryRefreshOutcome.Full else AndroidMyLibraryRefreshOutcome.Unsettled, null, null,
                )
                is MyLibraryRefreshOutcome.Failed -> AndroidMyLibraryRefresh(availability, AndroidMyLibraryRefreshOutcome.Failed, outcome.error, null)
            }
        }
    }

    /** Adds [target]; false for a blank id or a closed reader. */
    public fun add(target: AndroidLibraryEntity): Boolean = reader.changeInLibrary(target, member = true)

    /** Removes [target]; an album also takes out its member songs. */
    public fun remove(target: AndroidLibraryEntity): Boolean = reader.changeInLibrary(target, member = false)

    // ---- Internals ----------------------------------------------------------------------------------

    internal fun unregister(registration: AndroidMyLibraryRegistration) {
        registrations -= registration
    }

    /** Reader thread. */
    internal fun republish(registration: AndroidMyLibraryRegistration) {
        val session = reader.composed()?.session ?: return
        if (registration in registrations) publish(session, registration)
    }

    private fun publishAll(session: LibraryReaderSession) {
        registrations.toList().forEach { publish(session, it) }
    }

    private fun publish(session: LibraryReaderSession, registration: AndroidMyLibraryRegistration) {
        val publication = try {
            session.myLibrary.snapshot(registration.sort.toCore()).toAndroid()
        } catch (_: Throwable) {
            return
        }
        registration.emit(publication)
    }
}

public class AndroidMyLibraryRegistration internal constructor(
    private val reader: AndroidLibraryReader,
    private val owner: AndroidMyLibrary,
    initialSort: AndroidMyLibrarySort,
    listener: (AndroidMyLibraryPublication) -> Unit,
) {
    private val listener = AtomicReference<((AndroidMyLibraryPublication) -> Unit)?>(listener)

    /** Reader thread only. */
    internal var sort: AndroidMyLibrarySort = initialSort
        private set

    /** Re-sorts; a new publication follows. */
    public fun setSort(sort: AndroidMyLibrarySort) {
        reader.onReader {
            this.sort = sort
            owner.republish(this)
        }
    }

    public fun close() {
        if (listener.getAndSet(null) == null) return
        reader.onReader { owner.unregister(this) }
    }

    /** Reader thread. */
    internal fun emit(publication: AndroidMyLibraryPublication) {
        if (listener.get() == null) return
        reader.onMain { listener.get()?.invoke(publication) }
    }
}

public enum class AndroidMyLibrarySort { RecentlyAdded, Alphabetical }

/** Whether the account's server offers My Library: [Unknown] until detected on this device. */
public enum class AndroidMyLibraryAvailability { Available, Unavailable, Unknown }

/** Drives Add and Remove: [Partial] is an album some of whose songs are members. */
public enum class AndroidMyLibraryMembership { Added, Partial, NotAdded, Unknown }

public enum class AndroidMyLibrarySyncState { Never, Syncing, Current, Cached, Failed }

/** [Unsettled]: the library kept changing during every read; the device keeps what it had. */
public enum class AndroidMyLibraryRefreshOutcome { Unchanged, Incremental, Full, Unsettled, Unavailable, Failed }

public data class AndroidMyLibraryRefresh(
    val availability: AndroidMyLibraryAvailability,
    val outcome: AndroidMyLibraryRefreshOutcome,
    val error: DomainError?,
    /** Set when the refresh never ran. */
    val failure: AndroidOperationFailure?,
)

public data class AndroidMyLibraryAlbum(
    val rawId: String,
    val title: String,
    val artistName: String?,
    val artistRawId: String?,
    val year: Int?,
    val artworkKey: String?,
    val songCount: Int?,
    /** False when shown only for its member songs. */
    val explicitlyAdded: Boolean,
    val memberSongCount: Int,
    val pending: Boolean,
)

public data class AndroidMyLibraryArtist(
    val rawId: String,
    val name: String,
    val artworkKey: String?,
    val albumCount: Int?,
    val explicitlyAdded: Boolean,
    val pending: Boolean,
)

public data class AndroidMyLibrarySong(
    val rawId: String,
    val title: String,
    val albumRawId: String?,
    val albumTitle: String?,
    val artistName: String?,
    val durationMilliseconds: Long?,
    val artworkKey: String?,
    val pending: Boolean,
)

/** One state of My Library. Lists hold items whose details the device has; [membership] covers every item. */
public class AndroidMyLibraryPublication internal constructor(
    public val availability: AndroidMyLibraryAvailability,
    public val syncState: AndroidMyLibrarySyncState,
    public val error: DomainError?,
    public val lastReadAtEpochMillis: Long?,
    public val albums: List<AndroidMyLibraryAlbum>,
    public val artists: List<AndroidMyLibraryArtist>,
    public val songs: List<AndroidMyLibrarySong>,
    private val snapshot: MyLibrarySnapshot,
) {
    public fun membership(target: AndroidLibraryEntity): AndroidMyLibraryMembership =
        snapshot.membership(target.kind.toCore().cacheKind, target.rawId).toAndroid()
}

private fun AndroidMyLibrarySort.toCore(): MyLibrarySort = when (this) {
    AndroidMyLibrarySort.RecentlyAdded -> MyLibrarySort.RecentlyAdded
    AndroidMyLibrarySort.Alphabetical -> MyLibrarySort.Alphabetical
}

internal fun MyLibraryAvailability.toAndroid(): AndroidMyLibraryAvailability = when (this) {
    MyLibraryAvailability.Available -> AndroidMyLibraryAvailability.Available
    MyLibraryAvailability.Unavailable -> AndroidMyLibraryAvailability.Unavailable
    MyLibraryAvailability.Unknown -> AndroidMyLibraryAvailability.Unknown
}

internal fun MyLibraryMembership.toAndroid(): AndroidMyLibraryMembership = when (this) {
    MyLibraryMembership.Added -> AndroidMyLibraryMembership.Added
    MyLibraryMembership.Partial -> AndroidMyLibraryMembership.Partial
    MyLibraryMembership.NotAdded -> AndroidMyLibraryMembership.NotAdded
    MyLibraryMembership.Unknown -> AndroidMyLibraryMembership.Unknown
}

private fun MyLibrarySyncState.toAndroid(): AndroidMyLibrarySyncState = when (this) {
    MyLibrarySyncState.Never -> AndroidMyLibrarySyncState.Never
    MyLibrarySyncState.Syncing -> AndroidMyLibrarySyncState.Syncing
    MyLibrarySyncState.Current -> AndroidMyLibrarySyncState.Current
    MyLibrarySyncState.Cached -> AndroidMyLibrarySyncState.Cached
    MyLibrarySyncState.Failed -> AndroidMyLibrarySyncState.Failed
}

internal fun MyLibrarySnapshot.toAndroid(): AndroidMyLibraryPublication {
    val offered = availability != MyLibraryAvailability.Unavailable
    return AndroidMyLibraryPublication(
        availability = availability.toAndroid(),
        syncState = syncState.toAndroid(),
        error = lastError,
        lastReadAtEpochMillis = lastReadAtWall,
        albums = if (!offered) emptyList() else albums.map { album ->
            val credit = album.record.credits.firstOrNull { it.role == CreditRole.AlbumArtist } ?: album.record.credits.firstOrNull()
            AndroidMyLibraryAlbum(
                album.record.rawId, album.record.title, credit?.name, credit?.artistRawId, album.record.year,
                album.record.artworkKey, album.record.songCount, album.explicitlyAdded, album.memberSongCount, album.pending,
            )
        },
        artists = if (!offered) emptyList() else artists.map {
            AndroidMyLibraryArtist(it.record.rawId, it.record.name, it.record.artworkKey, it.record.albumCount, it.explicitlyAdded, it.pending)
        },
        songs = if (!offered) emptyList() else songs.map { song ->
            AndroidMyLibrarySong(
                song.record.rawId, song.record.title, song.record.albumRawId, song.record.albumTitle,
                song.record.credits.firstOrNull()?.name, song.record.durationMilliseconds, song.record.artworkKey, song.pending,
            )
        },
        snapshot = this,
    )
}
