package com.legitimateapps.dulcet.library

import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidQueueSource

/*
 * Playing from the library (spec §14.1), one implementation for the phone and the TV: an album from a
 * row, an artist's albums in the artist's order, and a home row's tracks. Each queue holds only what
 * the core says can play now (§16.14), so a track this device cannot play offline is never queued.
 */

/**
 * The album in [publication] queued from the track with [trackRawId], or from its first playable
 * track when that one cannot play or is null. False, and nothing played, before the playback service
 * is bound, when [publication] is not an album, or when nothing in it can play.
 */
public fun playAlbum(
    playback: AndroidPlaybackController?,
    providerInstanceId: String,
    publication: AndroidLibraryPublication,
    trackRawId: String?,
    shuffle: Boolean = false,
): Boolean {
    val album = publication.header as? AndroidLibraryItem.Album ?: return false
    val tracks = publication.playableTracks(providerInstanceId)
    if (playback == null || tracks.isEmpty()) return false
    val start = tracks.indexOfFirst { it.rawId == trackRawId }.takeIf { it >= 0 } ?: 0
    playback.playQueue(tracks, start, AndroidQueueSource.Album, album.title, album.rawId, shuffle)
    return true
}

/**
 * Every album of the artist in [publication], each opened for its playable tracks, queued in the
 * artist's album order. [done] hears how it ended; the returned handle abandons the albums still
 * opening, so leaving the screen starts nothing afterwards. Null, and nothing started, before the
 * playback service is bound or when the artist lists no album.
 */
public fun playArtist(
    playback: AndroidPlaybackController?,
    library: LibrarySession,
    providerInstanceId: String,
    publication: AndroidLibraryPublication,
    shuffle: Boolean,
    done: (ArtistPlayResult) -> Unit,
): AutoCloseable? {
    val artist = publication.header as? AndroidLibraryItem.Artist ?: return null
    val albums = publication.items.filterIsInstance<AndroidLibraryItem.Album>()
    if (playback == null || albums.isEmpty()) return null
    return library.collectAlbums(albums.map { it.rawId }) { details ->
        val tracks = details.flatMap { it.playableTracks(providerInstanceId) }
        if (tracks.isNotEmpty()) {
            playback.playQueue(tracks, 0, AndroidQueueSource.Artist, artist.name, artist.rawId, shuffle)
        }
        done(
            when {
                tracks.isNotEmpty() -> ArtistPlayResult.Played
                details.any { it.heldBackOffline() } -> ArtistPlayResult.NeedsConnection
                else -> ArtistPlayResult.NothingPlayable
            },
        )
    }
}

/** How playing an artist ended, for the line an artist screen shows when nothing played. */
public enum class ArtistPlayResult { Played, NeedsConnection, NothingPlayable }

/**
 * The playable tracks among a list's [items] — a home row holding tracks — queued from the one with
 * [trackRawId], as a library queue named [sourceName]. False, and nothing played, when the service is
 * not bound or that track cannot play now.
 */
public fun playTracks(
    playback: AndroidPlaybackController?,
    providerInstanceId: String,
    items: List<AndroidLibraryItem>,
    trackRawId: String,
    sourceName: String,
): Boolean {
    val tracks = items.filterIsInstance<AndroidLibraryItem.Track>()
        .filter { it.playability != AndroidLibraryPlayability.UnavailableOffline }
        .mapNotNull { it.toTrack(providerInstanceId) }
    val start = tracks.indexOfFirst { it.rawId == trackRawId }
    if (playback == null || start < 0) return false
    playback.playQueue(tracks, start, AndroidQueueSource.Library, sourceName)
    return true
}

/** An album whose tracks this device cannot play only because it is offline (§16.14). */
internal fun AndroidLibraryPublication.heldBackOffline(): Boolean =
    itemsUnavailableReason == AndroidLibraryUnavailableReason.NotCachedOffline || freshness.isOffline() ||
        items.any { (it as? AndroidLibraryItem.Track)?.playability == AndroidLibraryPlayability.UnavailableOffline }
