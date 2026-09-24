package com.legitimateapps.dulcet.library

import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType

/**
 * A presentation view of one committed library read: albums with their tracks, and artists with
 * their albums. It derives only from the rows the existing core read returns and holds no state
 * of its own, so a different library source can replace that read without touching the screens.
 */
public data class LibraryIndex(
    val albums: List<LibraryAlbum>,
    val artists: List<LibraryArtist>,
) {
    public fun album(rawId: String): LibraryAlbum? = albums.firstOrNull { it.item.id.rawId == rawId }
    public fun artist(rawId: String): LibraryArtist? = artists.firstOrNull { it.item.id.rawId == rawId }

    /** Albums credited to [artist], matched by opaque id when the credit carries one, else by name. */
    public fun albumsBy(artist: LibraryArtist): List<LibraryAlbum> = albums.filter { album ->
        album.item.credits.any { credit ->
            credit.id?.let { it == artist.item.id } ?: credit.name.equals(artist.item.title, ignoreCase = true)
        }
    }

    public companion object {
        /** The core read lists every album followed by its own tracks; that order is the grouping. */
        public fun from(rows: List<SearchResultItem>): LibraryIndex {
            val albums = mutableListOf<LibraryAlbum>()
            var current: SearchResultItem? = null
            var tracks = mutableListOf<SearchResultItem>()
            fun flush() { current?.let { albums += LibraryAlbum(it, tracks) } }
            for (row in rows) when (row.type) {
                SearchResultType.Album -> { flush(); current = row; tracks = mutableListOf() }
                SearchResultType.Track -> if (current != null) tracks += row
                SearchResultType.Artist -> Unit
            }
            flush()
            return LibraryIndex(albums, rows.filter { it.type == SearchResultType.Artist }.map(::LibraryArtist))
        }
    }
}

public data class LibraryAlbum(val item: SearchResultItem, val tracks: List<SearchResultItem>) {
    val artistLine: String get() = item.credits.joinToString { it.name }
    val durationMilliseconds: Long get() = tracks.sumOf { it.duration?.inWholeMilliseconds ?: 0L }

    public fun playable(): List<AndroidTrack> = tracks.map { it.toTrack(this) }
}

public data class LibraryArtist(val item: SearchResultItem)

public fun SearchResultItem.toTrack(album: LibraryAlbum? = null): AndroidTrack = AndroidTrack(
    providerInstanceId = id.providerInstanceId,
    rawId = id.rawId,
    title = title,
    artist = credits.joinToString { it.name }.takeIf { it.isNotBlank() }
        ?: album?.artistLine?.takeIf { it.isNotBlank() },
    album = albumTitle ?: album?.item?.title,
    durationMilliseconds = duration?.inWholeMilliseconds,
    artworkKey = artworkKey ?: album?.item?.artworkKey,
)
