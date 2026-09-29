package com.legitimateapps.dulcet.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Playlist editing for the Android shells (spec §18.6), over the reader session of one
 * [AndroidLibraryReader]: create, rename, add, remove, move, delete, and the person's answers to a
 * create in doubt. Every edit is in the next publication of every open window that shows the
 * playlist, before any request; it is queued in the outbox, sent at least once, and read back.
 *
 * **Shape.** Every method returns at once; the edit runs on the reader's thread after every call
 * made before it, and [completion] runs exactly once, on the main thread (see
 * [AndroidLibraryReader.operation]). Nothing throws out of it. Every closed value is mapped by an
 * exhaustive `when` with no `else`, so a record or outcome the core adds later fails this file's
 * compilation instead of reaching a shell as a guess. No field can carry a URL or server error text:
 * an error is a [DomainError].
 *
 * **Positions** (§18.6 "The hazard"). A removal or a move names positions in `expectedEntries` — the
 * entry ids of the publication the person acted on, in order, duplicates kept — never a server
 * position. A view that is no longer the one the core publishes is refused
 * ([AndroidPlaylistEditRecord.StaleView]) and nothing is recorded; the shell draws its latest
 * publication and lets the person act again.
 *
 * How each queued change ended arrives on [addOutcomeListener], possibly long after the edit's
 * completion (a flush on reconnect). A playlist's own [AndroidLibraryItem.Playlist.pendingChanges]
 * is what a screen shows while a change is queued.
 */
public class AndroidLibraryPlaylists internal constructor(private val reader: AndroidLibraryReader) {
    /**
     * Creates a playlist named [name] with [songRawIds] (track ids, in order, duplicates kept; may be
     * empty). It is shown at once under the local id in the result; the server's id arrives as
     * [AndroidPlaylistOutcome.Created].
     */
    public fun create(name: String, songRawIds: List<String>, completion: (AndroidPlaylistEditResult) -> Unit = {}) {
        reader.operation(completion, ::failedEdit) { session ->
            val created = session.playlists.create(name, songRawIds)
            AndroidPlaylistEditResult(created.record.toAndroid(), created.localId, null)
        }
    }

    /** Renames a playlist. A blank name is [AndroidPlaylistEditRecord.Invalid] and nothing is recorded. */
    public fun rename(playlistId: String, name: String, completion: (AndroidPlaylistEditResult) -> Unit = {}) {
        record(completion) { it.rename(playlistId, name) }
    }

    /** Appends [songRawIds] at the end: never positional, so it needs no view. */
    public fun append(playlistId: String, songRawIds: List<String>, completion: (AndroidPlaylistEditResult) -> Unit = {}) {
        record(completion) { it.append(playlistId, songRawIds) }
    }

    /**
     * Appends an album's tracks in album order. When the album's tracks were never read on this
     * device it reads them first, which needs the reader online; otherwise
     * [AndroidPlaylistEditRecord.NotCached].
     */
    public fun appendAlbum(playlistId: String, albumRawId: String, completion: (AndroidPlaylistEditResult) -> Unit = {}) {
        reader.operation(completion, ::failedEdit) { session ->
            AndroidPlaylistEditResult(session.playlists.appendAlbum(playlistId, albumRawId).toAndroid(), null, null)
        }
    }

    /** Removes the entries at [indices] of [expectedEntries], the view the person acted on. */
    public fun remove(
        playlistId: String,
        indices: Set<Int>,
        expectedEntries: List<String>,
        completion: (AndroidPlaylistEditResult) -> Unit = {},
    ) {
        record(completion) { it.remove(playlistId, indices, expectedEntries) }
    }

    /** Moves the entry at [from] to [to], both positions in [expectedEntries]. */
    public fun move(
        playlistId: String,
        from: Int,
        to: Int,
        expectedEntries: List<String>,
        completion: (AndroidPlaylistEditResult) -> Unit = {},
    ) {
        record(completion) { it.move(playlistId, from, to, expectedEntries) }
    }

    /** Deletes a playlist. A local one never sent is simply gone ([AndroidPlaylistEditRecord.CompactedAway]). */
    public fun delete(playlistId: String, completion: (AndroidPlaylistEditResult) -> Unit = {}) {
        record(completion) { it.delete(playlistId) }
    }

    /**
     * Takes back one pending change of kind [change], so it is not sent again:
     * [AndroidPlaylistEditRecord.CompactedAway] when it never reached the server,
     * [AndroidPlaylistEditRecord.AlreadySent] when it may have, [AndroidPlaylistEditRecord.Unchanged]
     * when none is pending.
     */
    public fun withdraw(playlistId: String, change: AndroidPlaylistChange, completion: (AndroidPlaylistEditResult) -> Unit = {}) {
        record(completion) { it.withdraw(playlistId, change.toCore()) }
    }

    /**
     * The person's answer to [AndroidPlaylistOutcome.PossibleDuplicate] for the create [localId]:
     * [playlistId] is the candidate that IS theirs, or null when none is (the create is then sent).
     */
    public fun chooseCreated(localId: String, playlistId: String?, completion: (AndroidPlaylistEditResult) -> Unit = {}) {
        record(completion) { it.chooseCreated(localId, playlistId) }
    }

    /** Every playlist change not yet on the server, oldest first, so the person can withdraw one. */
    public fun pendingChanges(completion: (AndroidPlaylistPendingChanges) -> Unit) {
        reader.operation(completion, { failure -> AndroidPlaylistPendingChanges(emptyList(), failure) }) { session ->
            AndroidPlaylistPendingChanges(session.playlists.pendingChanges().map { it.toAndroid() }, null)
        }
    }

    /** How each queued change ended, or why it waits: on the main thread, until the registration is closed. */
    public fun addOutcomeListener(listener: (AndroidPlaylistOutcome) -> Unit): AndroidPlaylistOutcomeRegistration {
        val registration = AndroidPlaylistOutcomeRegistration(reader, listener)
        reader.onReader { reader.register(registration) }
        return registration
    }

    private fun record(completion: (AndroidPlaylistEditResult) -> Unit, change: (PlaylistEditor) -> PlaylistEditRecord) {
        reader.operation(completion, ::failedEdit) { session ->
            AndroidPlaylistEditResult(change(session.playlists).toAndroid(), null, null)
        }
    }
}

private fun failedEdit(failure: AndroidOperationFailure) = AndroidPlaylistEditResult(null, null, failure)

// ---- Values that cross ------------------------------------------------------------------------------------

/** Why a call never ran: the reader was closed, or failed on this device. Never a server state. */
public enum class AndroidOperationFailure { Closed, InternalFailure }

/** What an edit did to the outbox (§18.6). */
public enum class AndroidPlaylistEditRecord {
    /** Shown now, queued to send. */
    Pending,

    /** It undid what was queued; nothing is sent. */
    CompactedAway,

    /** Nothing to do: the playlist already shows this. */
    Unchanged,

    /** A blank name, no songs, a position outside the view, a move onto itself. Nothing recorded. */
    Invalid,

    /** Another user's, or a read-only, playlist. */
    NotEditable,

    /** The playlist's (or album's) entries were never read on this device. */
    NotCached,

    /** The view acted on is not the one now shown: draw the latest and let the person act again. */
    StaleView,

    /** Deleted on this device, or gone from the server. */
    Deleted,

    /** The device could not record it. */
    NotRecorded,

    /** A withdrawal too late to undo: a send may already be on the server. */
    AlreadySent,
}

/** The four kinds of queued playlist change, each withdrawable on its own. */
public enum class AndroidPlaylistChange { Create, Details, Entries, Delete }

/** A header field another client changed ([AndroidPlaylistOutcome.Superseded]). */
public enum class AndroidPlaylistField { Name, Comment, Public }

/**
 * What an edit did. Exactly one of [record] and [failure] is set; [localId] is the id a created
 * playlist is shown under until the server's arrives.
 */
public data class AndroidPlaylistEditResult(
    val record: AndroidPlaylistEditRecord?,
    val localId: String?,
    val failure: AndroidOperationFailure?,
)

/** How one queued playlist change ended, or why it waits (§18.6). [playlistId] is the playlist it concerns. */
public sealed interface AndroidPlaylistOutcome {
    public val playlistId: String

    /** The server holds the change, read back. */
    public data class Saved(override val playlistId: String, val change: AndroidPlaylistChange) : AndroidPlaylistOutcome

    /** A playlist made on this device now exists on the server as [playlistId]; its local id was [localId]. */
    public data class Created(val localId: String, override val playlistId: String) : AndroidPlaylistOutcome

    /** Refused, or failed too often: the server's state shows again. */
    public data class NotSaved(override val playlistId: String, val change: AndroidPlaylistChange, val error: DomainError) :
        AndroidPlaylistOutcome

    /** NOT written: the entries changed on the server since the edit was made. [serverEntries] now show. */
    public data class ChangedElsewhere(override val playlistId: String, val serverEntries: List<String>) : AndroidPlaylistOutcome

    /** Written, but the read-back is not what was meant; the server's list now shows. */
    public data class Diverged(
        override val playlistId: String,
        val change: AndroidPlaylistChange,
        val serverEntries: List<String>?,
        val intendedEntries: List<String>?,
    ) : AndroidPlaylistOutcome

    /** Kept unsent, with every change after it: the account was refused, or the server asked to wait. */
    public data class Held(override val playlistId: String, val change: AndroidPlaylistChange, val error: DomainError) :
        AndroidPlaylistOutcome

    /** [fields] were changed elsewhere after this device last saw them; the server's values stand. */
    public data class Superseded(override val playlistId: String, val fields: Set<AndroidPlaylistField>) : AndroidPlaylistOutcome

    /** The device could not record a change. */
    public data class NotRecorded(override val playlistId: String) : AndroidPlaylistOutcome

    /**
     * A create whose answer was lost was deleted here: [candidates] may be what it made. Offer to
     * delete one; nothing is deleted on inference.
     */
    public data class PossiblyCreated(val localId: String, val name: String, val candidates: List<String>) : AndroidPlaylistOutcome {
        override val playlistId: String get() = localId
    }

    /** A create in doubt may already be one of [candidates]: ask, then [AndroidLibraryPlaylists.chooseCreated]. */
    public data class PossibleDuplicate(val localId: String, val name: String, val candidates: List<String>) : AndroidPlaylistOutcome {
        override val playlistId: String get() = localId
    }
}

/** One queued playlist change, so a shell can list it and offer to withdraw it. */
public data class AndroidPlaylistPendingChange(
    val playlistId: String,
    val change: AndroidPlaylistChange,
    /** Sent, and its answer lost: it may be on the server already. */
    val inDoubt: Boolean,
    val failures: Int,
    /** A create waiting for the person's choice: its candidates; null otherwise. */
    val candidates: List<String>?,
)

/** The pending changes, or [failure] with none. */
public data class AndroidPlaylistPendingChanges(
    val changes: List<AndroidPlaylistPendingChange>,
    val failure: AndroidOperationFailure?,
)

/** The stream of playlist outcomes. [close] is idempotent; nothing is delivered after it returns on the main thread. */
public class AndroidPlaylistOutcomeRegistration internal constructor(
    private val owner: AndroidLibraryReader,
    listener: (AndroidPlaylistOutcome) -> Unit,
) {
    private val listener = AtomicReference<((AndroidPlaylistOutcome) -> Unit)?>(listener)

    public fun close() {
        if (listener.getAndSet(null) == null) return
        owner.onReader { owner.unregister(this) }
    }

    /** Reader thread. */
    internal fun emit(outcome: AndroidPlaylistOutcome) {
        if (listener.get() == null) return
        owner.onMain { listener.get()?.invoke(outcome) }
    }
}

// ---- Closed vocabularies -----------------------------------------------------------------------------------

internal fun PlaylistEditRecord.toAndroid(): AndroidPlaylistEditRecord = when (this) {
    PlaylistEditRecord.Pending -> AndroidPlaylistEditRecord.Pending
    PlaylistEditRecord.CompactedAway -> AndroidPlaylistEditRecord.CompactedAway
    PlaylistEditRecord.Unchanged -> AndroidPlaylistEditRecord.Unchanged
    PlaylistEditRecord.Invalid -> AndroidPlaylistEditRecord.Invalid
    PlaylistEditRecord.NotEditable -> AndroidPlaylistEditRecord.NotEditable
    PlaylistEditRecord.NotCached -> AndroidPlaylistEditRecord.NotCached
    PlaylistEditRecord.StaleView -> AndroidPlaylistEditRecord.StaleView
    PlaylistEditRecord.Deleted -> AndroidPlaylistEditRecord.Deleted
    PlaylistEditRecord.NotRecorded -> AndroidPlaylistEditRecord.NotRecorded
    PlaylistEditRecord.AlreadySent -> AndroidPlaylistEditRecord.AlreadySent
}

internal fun PlaylistRowKind.toAndroid(): AndroidPlaylistChange = when (this) {
    PlaylistRowKind.Create -> AndroidPlaylistChange.Create
    PlaylistRowKind.Details -> AndroidPlaylistChange.Details
    PlaylistRowKind.Entries -> AndroidPlaylistChange.Entries
    PlaylistRowKind.Delete -> AndroidPlaylistChange.Delete
}

internal fun AndroidPlaylistChange.toCore(): PlaylistRowKind = when (this) {
    AndroidPlaylistChange.Create -> PlaylistRowKind.Create
    AndroidPlaylistChange.Details -> PlaylistRowKind.Details
    AndroidPlaylistChange.Entries -> PlaylistRowKind.Entries
    AndroidPlaylistChange.Delete -> PlaylistRowKind.Delete
}

internal fun PlaylistDetailField.toAndroid(): AndroidPlaylistField = when (this) {
    PlaylistDetailField.Name -> AndroidPlaylistField.Name
    PlaylistDetailField.Comment -> AndroidPlaylistField.Comment
    PlaylistDetailField.Public -> AndroidPlaylistField.Public
}

internal fun PendingPlaylistChange.toAndroid() = AndroidPlaylistPendingChange(
    playlistId = playlistId,
    change = kind.toAndroid(),
    inDoubt = inDoubt,
    failures = failures,
    candidates = candidates,
)

internal fun PlaylistEditOutcome.toAndroid(): AndroidPlaylistOutcome = when (this) {
    is PlaylistEditOutcome.Saved -> AndroidPlaylistOutcome.Saved(playlistId, kind.toAndroid())
    is PlaylistEditOutcome.Created -> AndroidPlaylistOutcome.Created(localId, playlistId)
    is PlaylistEditOutcome.NotSaved -> AndroidPlaylistOutcome.NotSaved(playlistId, kind.toAndroid(), error)
    is PlaylistEditOutcome.ChangedElsewhere -> AndroidPlaylistOutcome.ChangedElsewhere(playlistId, serverEntries)
    is PlaylistEditOutcome.Diverged -> AndroidPlaylistOutcome.Diverged(playlistId, kind.toAndroid(), serverEntries, intendedEntries)
    is PlaylistEditOutcome.Held -> AndroidPlaylistOutcome.Held(playlistId, kind.toAndroid(), error)
    is PlaylistEditOutcome.Superseded -> AndroidPlaylistOutcome.Superseded(playlistId, fields.map { it.toAndroid() }.toSet())
    is PlaylistEditOutcome.NotRecorded -> AndroidPlaylistOutcome.NotRecorded(playlistId)
    is PlaylistEditOutcome.PossiblyCreated -> AndroidPlaylistOutcome.PossiblyCreated(localId, name, candidates)
    is PlaylistEditOutcome.PossibleDuplicate -> AndroidPlaylistOutcome.PossibleDuplicate(localId, name, candidates)
}
