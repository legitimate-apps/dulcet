package com.legitimateapps.dulcet.library

import android.content.res.Resources
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidOperationFailure
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.core.AndroidQueueSource
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.shared.R

/*
 * The words and the one play rule for playlists (spec §18.6), shared by the phone and the TV. Every
 * input is a value the core published; nothing here decides whether an edit is allowed.
 */

/**
 * What an edit's immediate answer needs said, or null when the edit shows for itself (pending, or
 * nothing to do). A stale view says to look again — the page already draws the latest.
 */
public fun Resources.playlistEditLine(result: AndroidPlaylistEditResult): String? {
    result.failure?.let { failure ->
        return getString(
            when (failure) {
                AndroidOperationFailure.Closed -> R.string.library_unavailable_closed
                AndroidOperationFailure.InternalFailure -> R.string.playlist_edit_not_recorded
            },
        )
    }
    return when (result.record ?: return null) {
        AndroidPlaylistEditRecord.Pending,
        AndroidPlaylistEditRecord.CompactedAway,
        AndroidPlaylistEditRecord.Unchanged -> null
        AndroidPlaylistEditRecord.Invalid -> getString(R.string.playlist_edit_invalid)
        AndroidPlaylistEditRecord.NotEditable -> getString(R.string.playlist_edit_not_editable)
        AndroidPlaylistEditRecord.NotCached -> getString(R.string.playlist_edit_not_cached)
        AndroidPlaylistEditRecord.StaleView -> getString(R.string.playlist_edit_stale)
        AndroidPlaylistEditRecord.Deleted -> getString(R.string.playlist_edit_deleted)
        AndroidPlaylistEditRecord.NotRecorded -> getString(R.string.playlist_edit_not_recorded)
        AndroidPlaylistEditRecord.AlreadySent -> getString(R.string.playlist_edit_already_sent)
    }
}

/** How a queued playlist change ended, in words; null for one that needs none. */
public fun Resources.playlistOutcomeLine(outcome: AndroidPlaylistOutcome?): String? = when (outcome) {
    null, is AndroidPlaylistOutcome.Saved, is AndroidPlaylistOutcome.Created -> null
    is AndroidPlaylistOutcome.NotSaved -> getString(R.string.playlist_outcome_not_saved, errorPhrase(outcome.error))
    is AndroidPlaylistOutcome.ChangedElsewhere -> getString(R.string.playlist_outcome_changed_elsewhere)
    is AndroidPlaylistOutcome.Diverged -> getString(R.string.playlist_outcome_diverged)
    is AndroidPlaylistOutcome.Held -> when (val error = outcome.error) {
        is DomainError.Server.Busy -> getString(R.string.library_change_held_busy)
        else -> getString(R.string.playlist_outcome_held, heldPhrase(error))
    }
    is AndroidPlaylistOutcome.Superseded -> getString(R.string.playlist_outcome_superseded)
    is AndroidPlaylistOutcome.NotRecorded -> getString(R.string.playlist_edit_not_recorded)
    is AndroidPlaylistOutcome.PossiblyCreated -> getString(R.string.playlist_outcome_possibly_created, outcome.name)
    is AndroidPlaylistOutcome.PossibleDuplicate -> playlistQuestionLine(
        PlaylistQuestion(PlaylistQuestion.Kind.WhichIsYours, outcome.localId, outcome.name, outcome.candidates), outcome.name,
    )
}

/**
 * What a create in doubt asks (§18.6), naming it [name] — the sent name, or the name of the playlist
 * shown under its local id — or not at all when neither is known. Only a lone candidate is offered as
 * the person's; with several, the words say they cannot be told apart here.
 */
public fun Resources.playlistQuestionLine(question: PlaylistQuestion, name: String?): String = when (question.kind) {
    PlaylistQuestion.Kind.MaybeCreated -> getString(R.string.playlist_outcome_possibly_created, name ?: question.name.orEmpty())
    PlaylistQuestion.Kind.WhichIsYours -> {
        val count = question.candidates.size
        when {
            question.loneCandidate != null && name != null -> getString(R.string.playlist_question_lone, name)
            question.loneCandidate != null -> getString(R.string.playlist_question_lone_unnamed)
            name != null -> getQuantityString(R.plurals.playlist_question_many, count, count, name)
            else -> getQuantityString(R.plurals.playlist_question_many_unnamed, count, count)
        }
    }
}

/** The line under a playlist's name: its owner when it is someone else's, and read-only then (§18.6). */
public fun Resources.playlistOwnerLine(playlist: AndroidLibraryItem.Playlist): String? = when {
    playlist.local -> getString(R.string.playlist_local)
    !playlist.editable && playlist.owner != null -> getString(R.string.playlist_read_only_owner, playlist.owner)
    !playlist.editable -> getString(R.string.playlist_read_only)
    else -> null
}

/** "Not saved to your server yet" while an edit made here is queued (§18.6). */
public fun Resources.playlistPendingLine(playlist: AndroidLibraryItem.Playlist): String? =
    if (playlist.pendingChanges || playlist.local) getString(R.string.playlist_pending) else null

/**
 * The entry ids of the view drawn, in order, duplicates kept: what a positional edit names as the
 * view the person acted on (§18.6). Null when the view is not the whole playlist — its entries are
 * not all present — so no positional edit is offered on it.
 */
public fun AndroidLibraryPublication.playlistView(): List<String>? {
    if (header !is AndroidLibraryItem.Playlist || itemsState != AndroidLibraryItemsState.Present) return null
    return items.map { it.rawId }
}

/** A playlist queue: its tracks, in the playlist's order, and the index to start at. */
public data class PlaylistQueue(val name: String, val tracks: List<AndroidTrack>, val start: Int)

/**
 * The playlist in [publication] as a queue from the entry at [position] (an index into its items), in
 * the playlist's order, duplicates kept. Entries this device cannot play now, or knows only by id, are
 * left out; a start on one of those begins at the next entry that can play. Null when the publication
 * is not a playlist with its entries present, or nothing in it can play.
 */
public fun AndroidLibraryPublication.playlistQueue(providerInstanceId: String, position: Int): PlaylistQueue? {
    val playlist = header as? AndroidLibraryItem.Playlist ?: return null
    if (itemsState != AndroidLibraryItemsState.Present) return null
    val playable = items.withIndex().filter { (_, item) ->
        item is AndroidLibraryItem.Track && !item.metadataMissing && !item.title.isNullOrBlank() &&
            item.playability != AndroidLibraryPlayability.UnavailableOffline
    }
    val tracks = playable.mapNotNull { (it.value as AndroidLibraryItem.Track).toTrack(providerInstanceId) }
    if (tracks.isEmpty()) return null
    // Duplicates are separate entries: the start is found by position, never by id.
    val start = playable.indexOfFirst { it.index >= position }.takeIf { it >= 0 } ?: 0
    return PlaylistQueue(playlist.name, tracks, start)
}

/**
 * The playlist in [publication] played from the entry at [position] ([playlistQueue]), as a library
 * queue named for the playlist. False, and nothing played, when the service is not bound or nothing
 * can play.
 */
public fun playPlaylist(
    playback: AndroidPlaybackController?,
    providerInstanceId: String,
    publication: AndroidLibraryPublication,
    position: Int,
    shuffle: Boolean = false,
): Boolean {
    val queue = publication.playlistQueue(providerInstanceId, position) ?: return false
    if (playback == null) return false
    playback.playQueue(queue.tracks, queue.start, AndroidQueueSource.Library, queue.name, shuffle = shuffle)
    return true
}
