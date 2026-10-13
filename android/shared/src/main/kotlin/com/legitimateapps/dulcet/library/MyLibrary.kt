package com.legitimateapps.dulcet.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * My Library, the per-account library a Dulcet Server advertises as the `dulcetLibrary` OpenSubsonic
 * extension (docs/dulcet-server/dulcet-library-extension.md), beside Browse — the whole catalog, which
 * every server offers. The shells draw what this publishes and ask it to add and remove; the core owns
 * detection, the read model, the outbox and the wire. The shapes here are the core facade's
 * (`AndroidMyLibrary`), so the session below is the only place that adapts it. Until the facade ships,
 * the session is backed by [UnavailableMyLibrarySource]: no server advertises the extension to it, so
 * nothing splits and no library endpoint is ever called — the behaviour on every server today.
 */

public enum class MyLibrarySort { RecentlyAdded, Alphabetical }

/** Whether the connected server advertises `dulcetLibrary` for this account. */
public enum class MyLibraryAvailability { Available, Unavailable, Unknown }

public enum class MyLibrarySyncState { Never, Syncing, Current, Cached, Failed }

/** What the account's library holds of one item; drives Add/Remove. */
public enum class MyLibraryMembership { Added, Partial, NotAdded, Unknown }

public data class MyLibraryAlbum(
    val rawId: String,
    val title: String,
    val artistName: String?,
    val artistRawId: String?,
    /** 0 when unknown. */
    val year: Int,
    val artworkKey: String?,
    /** -1 when unknown. */
    val songCount: Int,
    val explicitlyAdded: Boolean,
    val memberSongCount: Int,
    /** A local add or remove is not yet answered. */
    val pending: Boolean,
)

public data class MyLibraryArtist(
    val rawId: String,
    val name: String,
    val artworkKey: String?,
    /** -1 when unknown. */
    val albumCount: Int,
    val explicitlyAdded: Boolean,
    val pending: Boolean,
)

public data class MyLibrarySong(
    val rawId: String,
    val title: String,
    val albumRawId: String?,
    val albumTitle: String?,
    val artistName: String?,
    /** -1 when unknown. */
    val durationMilliseconds: Long,
    val artworkKey: String?,
    val pending: Boolean,
)

public data class MyLibraryPublication(
    val availability: MyLibraryAvailability,
    val syncState: MyLibrarySyncState,
    /** The last failed refresh's error kind; null when none. */
    val errorKind: String?,
    /** Null until the library was read once. */
    val lastReadAtEpochMillis: Long?,
    val albums: List<MyLibraryAlbum>,
    val artists: List<MyLibraryArtist>,
    val songs: List<MyLibrarySong>,
) {
    /** What the account's library holds of [target]. */
    public fun membership(target: AndroidLibraryEntity): MyLibraryMembership = when (target.kind) {
        AndroidLibraryEntityKind.Album -> albums.firstOrNull { it.rawId == target.rawId }?.let { album ->
            when {
                album.explicitlyAdded -> MyLibraryMembership.Added
                album.memberSongCount > 0 -> MyLibraryMembership.Partial
                else -> MyLibraryMembership.NotAdded
            }
        } ?: if (availability == MyLibraryAvailability.Available && hasRead()) MyLibraryMembership.NotAdded else MyLibraryMembership.Unknown
        AndroidLibraryEntityKind.Artist -> artists.firstOrNull { it.rawId == target.rawId }?.let { artist ->
            if (artist.explicitlyAdded) MyLibraryMembership.Added else MyLibraryMembership.Partial
        } ?: if (availability == MyLibraryAvailability.Available && hasRead()) MyLibraryMembership.NotAdded else MyLibraryMembership.Unknown
        AndroidLibraryEntityKind.Track -> songs.firstOrNull { it.rawId == target.rawId }?.let {
            MyLibraryMembership.Added
        } ?: if (availability == MyLibraryAvailability.Available && hasRead()) MyLibraryMembership.NotAdded else MyLibraryMembership.Unknown
        else -> MyLibraryMembership.Unknown
    }

    private fun hasRead(): Boolean = syncState == MyLibrarySyncState.Current || syncState == MyLibrarySyncState.Cached
}

public enum class MyLibraryRefreshOutcome { Unchanged, Incremental, Full, Unavailable, Failed }

public data class MyLibraryRefresh(
    val availability: MyLibraryAvailability,
    val outcome: MyLibraryRefreshOutcome,
    val errorKind: String?,
)

/**
 * The shell's handle on the account's My Library: the core facade's own shape (`AndroidMyLibrary`),
 * so the production session is a thin adapter and tests a fake. [observe] republishes on every change
 * and once at subscribe; [add]/[remove] are optimistic, durable and idempotent in the core, and answer
 * false for a target that cannot be held (a bad kind, or a closed source).
 */
public interface MyLibrarySource {
    public fun observe(sort: MyLibrarySort, listener: (MyLibraryPublication) -> Unit): AutoCloseable
    public fun refresh(completion: (MyLibraryRefresh) -> Unit = {})
    public fun add(target: AndroidLibraryEntity): Boolean
    public fun remove(target: AndroidLibraryEntity): Boolean
}

/**
 * The backing while no core facade exists, and the answer for a server without the extension: nothing
 * is available, nothing can be added, and no endpoint is ever called (the shells never split).
 */
public class UnavailableMyLibrarySource : MyLibrarySource {
    override fun observe(sort: MyLibrarySort, listener: (MyLibraryPublication) -> Unit): AutoCloseable {
        listener(PUBLICATION)
        return AutoCloseable {}
    }

    override fun refresh(completion: (MyLibraryRefresh) -> Unit) {
        completion(MyLibraryRefresh(MyLibraryAvailability.Unavailable, MyLibraryRefreshOutcome.Unavailable, null))
    }

    override fun add(target: AndroidLibraryEntity): Boolean = false
    override fun remove(target: AndroidLibraryEntity): Boolean = false

    private companion object {
        val PUBLICATION = MyLibraryPublication(
            MyLibraryAvailability.Unavailable, MyLibrarySyncState.Never, null, null,
            emptyList(), emptyList(), emptyList(),
        )
    }
}

/**
 * One screen host's My Library: the single [MyLibrarySource.observe] subscription for every screen of
 * it, as Compose state, plus the add/remove/refresh the screens ask for. Sort applies to the lists the
 * sections draw; a change re-subscribes, as the core publishes each sort as its own read.
 */
public class MyLibrarySession(
    private val source: MyLibrarySource,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<MyLibraryPublication?>(null)

    /** The latest publication, or null before the first one arrives. */
    public val state: StateFlow<MyLibraryPublication?> = mutableState.asStateFlow()

    private val mutableSort = MutableStateFlow(MyLibrarySort.RecentlyAdded)

    /** The order the sections list in: newest addition first, or A to Z. */
    public val sort: StateFlow<MyLibrarySort> = mutableSort.asStateFlow()

    private var subscription: AutoCloseable? = source.observe(MyLibrarySort.RecentlyAdded) { mutableState.value = it }
    private var closed = false

    public fun setSort(sort: MyLibrarySort) {
        if (closed || sort == mutableSort.value) return
        mutableSort.value = sort
        val previous = subscription
        // The new subscription is taken before the old one closes, so the lists never blank.
        subscription = source.observe(sort) { mutableState.value = it }
        previous?.close()
    }

    public fun refresh(completion: (MyLibraryRefresh) -> Unit = {}) {
        if (!closed) source.refresh(completion)
    }

    /** Adds [target] to the account's library; it shows in the next publication, before any request. */
    public fun add(target: AndroidLibraryEntity): Boolean = !closed && source.add(target)

    /** Removes [target]; for an album, its member songs go with it (the extension's semantics). */
    public fun remove(target: AndroidLibraryEntity): Boolean = !closed && source.remove(target)

    override fun close() {
        if (closed) return
        closed = true
        subscription?.close()
        subscription = null
    }
}

/**
 * The membership of [target] as the last publication knows it, or null when the controls hide: no
 * source, no publication yet, or a server without the extension (the shell then draws today's UI).
 */
@Composable
public fun membershipOf(myLibrary: MyLibrarySession?, target: AndroidLibraryEntity): MyLibraryMembership? {
    if (myLibrary == null) return null
    val publication by myLibrary.state.collectAsState()
    val current = publication ?: return null
    if (current.availability != MyLibraryAvailability.Available) return null
    return current.membership(target)
}

/**
 * What a membership control does with its state: Added removes; Partial, NotAdded and Unknown add
 * (a partial album is made a full member; the write is idempotent, so an unknown state is safe).
 */
public fun MyLibrarySession.toggleMembership(target: AndroidLibraryEntity, membership: MyLibraryMembership?) {
    if (membership == MyLibraryMembership.Added) remove(target) else add(target)
}

/** A My Library song as the track row the library screens already draw and play. */
public fun MyLibrarySong.toTrack(): AndroidLibraryItem.Track = AndroidLibraryItem.Track(
    rawId = rawId,
    title = title,
    albumRawId = albumRawId,
    albumTitle = albumTitle,
    artistName = artistName,
    artistRawId = null,
    discNumber = null,
    trackNumber = null,
    durationMilliseconds = durationMilliseconds.takeIf { it > 0 },
    sourceContainer = null,
    artworkKey = artworkKey,
    favourite = null,
    rating = null,
    playCount = null,
    playability = AndroidLibraryPlayability.Streamable,
    metadataMissing = false,
)

/** A My Library album as the card row the library grids already draw. */
public fun MyLibraryAlbum.toLibraryItem(): AndroidLibraryItem.Album = AndroidLibraryItem.Album(
    rawId = rawId, title = title, artistName = artistName, artistRawId = artistRawId,
    year = year.takeIf { it > 0 }, genre = null, durationMilliseconds = null,
    songCount = songCount.takeIf { it >= 0 }, artworkKey = artworkKey,
    favourite = null, rating = null, playCount = null, detailComplete = false,
)

/** A My Library artist as the card row the library grids already draw. */
public fun MyLibraryArtist.toLibraryItem(): AndroidLibraryItem.Artist = AndroidLibraryItem.Artist(
    rawId = rawId, name = name, albumCount = albumCount.takeIf { it >= 0 }, artworkKey = artworkKey,
    favourite = null, rating = null,
)
