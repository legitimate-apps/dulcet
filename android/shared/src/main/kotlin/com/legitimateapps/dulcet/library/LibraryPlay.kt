package com.legitimateapps.dulcet.library

import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidQueueInsertion
import com.legitimateapps.dulcet.core.AndroidQueueSource
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.CreditRole
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.playback.PlaybackBinding

/*
 * Playing from the library (spec §14.1), one implementation for the phone and the TV: an album from a
 * row, an artist's albums in the artist's order, and a home row's tracks. Each queue holds only what
 * the core says can play now (§16.14), so a track this device cannot play offline is never queued.
 */

/**
 * The album in [publication] queued from the track with [trackRawId], or from its first playable
 * track when that one cannot play or is null. False, and nothing played, when [publication] is not
 * an album or when nothing in it can play. Pressed before the playback service is bound, the play is
 * held by [playback] and made once it binds; [started] runs when the queue is handed to the controller.
 */
public fun playAlbum(
    playback: PlaybackBinding,
    providerInstanceId: String,
    publication: AndroidLibraryPublication,
    trackRawId: String?,
    shuffle: Boolean = false,
    started: () -> Unit = {},
): Boolean {
    val album = publication.header as? AndroidLibraryItem.Album ?: return false
    val tracks = publication.playableTracks(providerInstanceId)
    if (tracks.isEmpty()) return false
    val start = tracks.indexOfFirst { it.rawId == trackRawId }.takeIf { it >= 0 } ?: 0
    playback.play { controller ->
        controller.playQueue(tracks, start, AndroidQueueSource.Album, album.title, album.rawId, shuffle)
        started()
    }
    return true
}

/**
 * Every album of the artist in [publication], each opened for its playable tracks, queued in the
 * artist's album order. [done] hears how it ended -- [ArtistPlayResult.Played] once the queue is
 * handed to the controller, which, before the playback service is bound, waits for it to bind. The
 * returned handle abandons the albums still opening and a play still waiting, so leaving the screen
 * starts nothing afterwards. Null, and nothing started, when the artist lists no album.
 */
public fun playArtist(
    playback: PlaybackBinding,
    library: LibrarySession,
    providerInstanceId: String,
    publication: AndroidLibraryPublication,
    shuffle: Boolean,
    done: (ArtistPlayResult) -> Unit,
): AutoCloseable? {
    val artist = publication.header as? AndroidLibraryItem.Artist ?: return null
    val albums = publication.items.filterIsInstance<AndroidLibraryItem.Album>()
    if (albums.isEmpty()) return null
    var abandoned = false
    val collecting = library.collectAlbums(albums.map { it.rawId }) { details ->
        val tracks = details.flatMap { it.playableTracks(providerInstanceId) }
        if (tracks.isEmpty()) {
            done(if (details.any { it.heldBackOffline() }) ArtistPlayResult.NeedsConnection else ArtistPlayResult.NothingPlayable)
        } else playback.play { controller ->
            if (abandoned) return@play
            controller.playQueue(tracks, 0, AndroidQueueSource.Artist, artist.name, artist.rawId, shuffle)
            done(ArtistPlayResult.Played)
        }
    }
    return AutoCloseable { abandoned = true; collecting.close() }
}

/** How playing an artist ended, for the line an artist screen shows when nothing played. */
public enum class ArtistPlayResult { Played, NeedsConnection, NothingPlayable }

/**
 * The playable tracks among a list's [items] — a home row holding tracks, the favourites, a genre's
 * songs — queued from the one with [trackRawId], or from the first when it is null (Play and Shuffle),
 * as a library queue named [sourceName]. False, and nothing played, when that track cannot play now,
 * or nothing can. Held until the service binds, as for [playAlbum]; [started] runs when it is played.
 */
public fun playTracks(
    playback: PlaybackBinding,
    providerInstanceId: String,
    items: List<AndroidLibraryItem>,
    trackRawId: String?,
    sourceName: String,
    shuffle: Boolean = false,
    started: () -> Unit = {},
): Boolean {
    val tracks = items.filterIsInstance<AndroidLibraryItem.Track>()
        .filter { it.playability != AndroidLibraryPlayability.UnavailableOffline }
        .mapNotNull { it.toTrack(providerInstanceId) }
    val start = if (trackRawId == null) 0.takeIf { tracks.isNotEmpty() } ?: -1 else tracks.indexOfFirst { it.rawId == trackRawId }
    if (start < 0) return false
    playback.play { controller ->
        controller.playQueue(tracks, start, AndroidQueueSource.Library, sourceName, null, shuffle)
        started()
    }
    return true
}

/*
 * Play Next and Add to Queue from the library (spec §14.1), one implementation for the phone and the
 * TV. What is added is what a play would queue: only tracks the core says can play now (§16.14).
 */

/**
 * The album in [publication]'s playable tracks, added to the queue as the album's. With no queue to
 * add to they play instead. False when the addition was refused -- the surface says so -- or when
 * there is nothing to add or no playback service bound.
 */
public fun queueAlbum(
    playback: AndroidPlaybackController?,
    providerInstanceId: String,
    publication: AndroidLibraryPublication,
    insertion: AndroidQueueInsertion,
): Boolean {
    val album = publication.header as? AndroidLibraryItem.Album ?: return false
    val tracks = publication.playableTracks(providerInstanceId)
    if (playback == null || tracks.isEmpty()) return false
    return playback.addToQueue(tracks, insertion, AndroidQueueSource.Album, album.title, album.rawId)
}

/**
 * One track added to the queue, attributed as [queueSource] says. [album] is the album the track is
 * shown on, if any, whose artwork and credits it borrows. False as for [queueAlbum].
 */
public fun queueTrack(
    playback: AndroidPlaybackController?,
    providerInstanceId: String,
    track: AndroidLibraryItem.Track,
    insertion: AndroidQueueInsertion,
    libraryName: String,
    album: AndroidLibraryItem.Album? = null,
): Boolean {
    if (playback == null || !track.canBeQueued()) return false
    val queued = track.toTrack(providerInstanceId, album) ?: return false
    val from = track.queueSource(album, libraryName)
    return playback.addToQueue(listOf(queued), insertion, from.source, from.name, from.rawId)
}

/** Where a queued track says it came from (spec §14.1 `sourceContext`). */
public data class QueueSourceOf(val source: AndroidQueueSource, val name: String, val rawId: String?)

/**
 * A track added on its own comes from its album when it names one, or is shown on [album], and
 * otherwise from the library, called [libraryName] -- as the Apple shells attribute it.
 */
public fun AndroidLibraryItem.Track.queueSource(album: AndroidLibraryItem.Album?, libraryName: String): QueueSourceOf {
    val albumRawId = albumRawId?.takeIf { it.isNotBlank() } ?: album?.rawId
    return if (albumRawId == null) QueueSourceOf(AndroidQueueSource.Library, libraryName, null)
    else QueueSourceOf(AndroidQueueSource.Album, albumTitle?.takeIf { it.isNotBlank() } ?: album?.title ?: libraryName, albumRawId)
}

/** Whether a row may offer Play Next and Add to Queue: the track can play now and has a title to show. */
public fun AndroidLibraryItem.Track.canBeQueued(): Boolean =
    playability != AndroidLibraryPlayability.UnavailableOffline && !title.isNullOrBlank()

/**
 * One search track added to the queue, attributed to the search (spec §14.1 `sourceContext`), exactly
 * as the Apple shells attribute a search result. False as for [queueTrack]; nothing is added, and the
 * surface says so, when the result is not a track, cannot play now, or no playback service is bound.
 */
public fun queueSearchResult(
    playback: AndroidPlaybackController?,
    providerInstanceId: String,
    result: SearchResultItem,
    insertion: AndroidQueueInsertion,
    searchName: String,
): Boolean {
    if (playback == null || result.type != SearchResultType.Track) return false
    val title = result.title.takeIf { it.isNotBlank() } ?: return false
    val artist = result.credits.singleOrNull { it.role == CreditRole.Artist }?.name?.takeIf { it.isNotBlank() }
    val track = AndroidTrack(
        providerInstanceId = providerInstanceId,
        rawId = result.id.rawId,
        title = title,
        artist = artist,
        album = result.albumTitle?.takeIf { it.isNotBlank() },
        durationMilliseconds = result.duration?.inWholeMilliseconds,
        artworkKey = result.artworkKey,
    )
    return playback.addToQueue(listOf(track), insertion, AndroidQueueSource.Search, searchName)
}

/** An album whose tracks this device cannot play only because it is offline (§16.14). */
internal fun AndroidLibraryPublication.heldBackOffline(): Boolean =
    itemsUnavailableReason == AndroidLibraryUnavailableReason.NotCachedOffline || freshness.isOffline() ||
        items.any { (it as? AndroidLibraryItem.Track)?.playability == AndroidLibraryPlayability.UnavailableOffline }
