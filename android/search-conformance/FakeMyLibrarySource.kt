package com.legitimateapps.dulcet.search.conformance

import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.library.MyLibraryAlbum
import com.legitimateapps.dulcet.library.MyLibraryArtist
import com.legitimateapps.dulcet.library.MyLibraryPublication
import com.legitimateapps.dulcet.library.MyLibraryRefresh
import com.legitimateapps.dulcet.library.MyLibraryRefreshOutcome
import com.legitimateapps.dulcet.library.MyLibrarySong
import com.legitimateapps.dulcet.library.MyLibrarySort
import com.legitimateapps.dulcet.library.MyLibrarySource
import com.legitimateapps.dulcet.library.MyLibraryAvailability
import com.legitimateapps.dulcet.library.MyLibrarySyncState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [MyLibrarySource] fixture for the shells' host tests, standing where the core's `AndroidMyLibrary`
 * will: it publishes what it is given, applies add/remove optimistically as the core does (a pending
 * add shows at once; removing an album takes its member songs), and records every call so a test
 * asserts what the screen asked for. [available] toggles the split: false is a server without the
 * `dulcetLibrary` extension.
 */
class FakeMyLibrarySource(
    var available: Boolean = true,
    albums: List<MyLibraryAlbum> = emptyList(),
    artists: List<MyLibraryArtist> = emptyList(),
    songs: List<MyLibrarySong> = emptyList(),
    var syncState: MyLibrarySyncState = MyLibrarySyncState.Current,
    var holdRefresh: Boolean = false,
) : MyLibrarySource {
    private val albums = albums.toMutableList()
    private val artists = artists.toMutableList()
    private val songs = songs.toMutableList()

    /** Every add/remove target the screens asked for, in order. */
    val writes = CopyOnWriteArrayList<Pair<Boolean, AndroidLibraryEntity>>()

    /** Every sort a session subscribed with, in order. */
    val sorts = CopyOnWriteArrayList<MyLibrarySort>()

    var refreshes = 0
        private set

    private val listeners = CopyOnWriteArrayList<Pair<MyLibrarySort, (MyLibraryPublication) -> Unit>>()
    private val heldRefreshes = CopyOnWriteArrayList<(MyLibraryRefresh) -> Unit>()

    override fun observe(sort: MyLibrarySort, listener: (MyLibraryPublication) -> Unit): AutoCloseable {
        sorts += sort
        val entry = sort to listener
        listeners += entry
        listener(publication(sort))
        return AutoCloseable { listeners -= entry }
    }

    override fun refresh(completion: (MyLibraryRefresh) -> Unit) {
        refreshes += 1
        if (holdRefresh) {
            heldRefreshes += completion
            syncState = MyLibrarySyncState.Syncing
            republish()
            return
        }
        completion(MyLibraryRefresh(
            if (available) MyLibraryAvailability.Available else MyLibraryAvailability.Unavailable,
            if (available) MyLibraryRefreshOutcome.Unchanged else MyLibraryRefreshOutcome.Unavailable,
            null,
        ))
    }

    /** Holds the same cached rows until the test explicitly completes the refresh. */
    fun finishRefresh() {
        holdRefresh = false
        syncState = MyLibrarySyncState.Current
        republish()
        val waiting = heldRefreshes.toList()
        heldRefreshes.clear()
        waiting.forEach { it(MyLibraryRefresh(MyLibraryAvailability.Available, MyLibraryRefreshOutcome.Unchanged, null)) }
    }

    override fun add(target: AndroidLibraryEntity): Boolean {
        if (!available) return false
        writes += (true to target)
        when (target.kind) {
            AndroidLibraryEntityKind.Album -> if (albums.none { it.rawId == target.rawId }) {
                albums += MyLibraryAlbum(target.rawId, target.rawId, null, null, 0, null, -1,
                    explicitlyAdded = true, memberSongCount = 0, pending = true)
            } else albums.replaceAll { if (it.rawId == target.rawId) it.copy(explicitlyAdded = true, pending = true) else it }
            AndroidLibraryEntityKind.Artist -> if (artists.none { it.rawId == target.rawId }) {
                artists += MyLibraryArtist(target.rawId, target.rawId, null, -1, explicitlyAdded = true, pending = true)
            } else artists.replaceAll { if (it.rawId == target.rawId) it.copy(explicitlyAdded = true, pending = true) else it }
            AndroidLibraryEntityKind.Track -> if (songs.none { it.rawId == target.rawId }) {
                songs += MyLibrarySong(target.rawId, target.rawId, null, null, null, -1, null, pending = true)
            } else songs.replaceAll { if (it.rawId == target.rawId) it.copy(pending = true) else it }
        }
        republish()
        return true
    }

    override fun remove(target: AndroidLibraryEntity): Boolean {
        if (!available) return false
        writes += (false to target)
        when (target.kind) {
            // An album goes, and its member songs with it (the extension's semantics).
            AndroidLibraryEntityKind.Album -> {
                albums.removeAll { it.rawId == target.rawId }
                songs.removeAll { it.albumRawId == target.rawId }
            }
            AndroidLibraryEntityKind.Artist -> artists.removeAll { it.rawId == target.rawId }
            AndroidLibraryEntityKind.Track -> songs.removeAll { it.rawId == target.rawId }
        }
        republish()
        return true
    }

    /** Publishes a changed library from outside (another client, a refresh's answer). */
    fun republish() {
        listeners.forEach { (sort, listener) -> listener(publication(sort)) }
    }

    private fun publication(sort: MyLibrarySort): MyLibraryPublication {
        if (!available) return MyLibraryPublication(
            MyLibraryAvailability.Unavailable, MyLibrarySyncState.Never, null, null,
            emptyList(), emptyList(), emptyList(),
        )
        fun <T> ordered(items: List<T>, name: (T) -> String): List<T> = when (sort) {
            // Recently added first: the fake's lists grow by appending, so newest is last.
            MyLibrarySort.RecentlyAdded -> items.asReversed()
            MyLibrarySort.Alphabetical -> items.sortedBy { name(it).lowercase() }
        }
        return MyLibraryPublication(
            MyLibraryAvailability.Available, syncState, null, 1_700_000_000_000L,
            ordered(albums.toList(), MyLibraryAlbum::title),
            ordered(artists.toList(), MyLibraryArtist::name),
            ordered(songs.toList(), MyLibrarySong::title),
        )
    }
}
