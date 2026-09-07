package com.legitimateapps.dulcet.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android composition root; SQLDelight and its driver stay behind the core facade. */
public class AndroidLibraryDatabase(context: Context) {
    private val context = context.applicationContext

    public suspend fun search(providerInstanceId: String, query: String): List<SearchResultItem> =
        withContext(Dispatchers.IO) {
            val store = DulcetDriverFactory(context).openDulcetDatabase()
            try { LocalLibrarySearch(store).search(providerInstanceId, query) }
            finally { store.close() }
        }

    public suspend fun readCommitted(providerInstanceId: String): AndroidCommittedLibrary = withContext(Dispatchers.IO) {
        val store = DulcetDriverFactory(context).openDulcetDatabase()
        try {
            val snapshot = LibrarySyncRepository(store).readCommittedLibrary(providerInstanceId)
            AndroidCommittedLibrary(snapshot.generation,
                snapshot.library.artists.map { item ->
                    SearchResultItem(item.id, SearchResultType.Artist, item.name, emptyList(),
                        null, null, null, null, null, null, item.mediaSourceId, null)
                } + snapshot.library.albums.flatMap { album ->
                    listOf(SearchResultItem(album.id, SearchResultType.Album, album.title, album.credits,
                        null, album.year, album.duration, null, null, null, album.mediaSourceId, album.artworkKey)) +
                        album.tracks.map { track ->
                            SearchResultItem(track.id, SearchResultType.Track, track.title, track.credits,
                                track.albumTitle, null, track.duration, track.discNumber, track.trackNumber,
                                track.sourceContainer, track.mediaSourceId, track.artworkKey)
                        }
                })
        } finally { store.close() }
    }

    public suspend fun synchronize(request: LibrarySyncRequest): LibrarySyncResponse = withContext(Dispatchers.IO) {
        val store = DulcetDriverFactory(context).openDulcetDatabase()
        try {
            val repository = LibrarySyncRepository(store)
            LibrarySyncManager(LibrarySyncEngine(repository), repository).synchronize(request)
        } finally { store.close() }
    }
}

/** One atomic committed snapshot, usable without credentials or network access. */
public data class AndroidCommittedLibrary(val generation: Long, val rows: List<SearchResultItem>)
