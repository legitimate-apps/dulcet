package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Playlist editing for the Apple shells (spec §18.6), over the reader session of one
 * [AppleLibraryReaderClient]: create, rename, add, remove, move, delete, and the person's answers
 * to a create in doubt — every edit shown at once in the next publication of every open window
 * that shows the playlist, queued in the outbox, sent at least once, read back.
 *
 * **Shape** (§7.1–§7.3, CLAUDE.md trap 17). Every method returns an [AppleLibraryReaderOperation]
 * at once and completes exactly once, on the main thread; the work runs on the reader's own thread,
 * after every call made before it, through the reader client's operation machinery. No Kotlin
 * exception crosses: a failure the core throws completes with `internalFailure`. Every word that
 * crosses is from a closed vocabulary, mapped by an exhaustive `when` with no `else`, so a record or
 * outcome the core adds later fails this file's compilation rather than crossing as a guess. No
 * field can carry a URL or server error text: errors cross as the reader's closed error kind.
 *
 * **Positions** (§18.6 "The hazard"). A removal or a move names positions in [expectedEntries] —
 * the entry ids of the publication the person acted on, in order, duplicates kept — never a server
 * position. A view that is no longer the one the core publishes is refused (`staleView`) and nothing
 * is recorded; the shell re-renders from its latest publication.
 *
 * How each queued change ended arrives on [subscribeOutcomes], possibly long after its edit's
 * completion (a flush on reconnect). The playlist's own `pendingChanges` flag in the window
 * publications is what a screen shows while a change is queued.
 */
@OptIn(ExperimentalAtomicApi::class)
public class AppleLibraryPlaylistClient(private val reader: AppleLibraryReaderClient) {
    // Reader-thread state.
    private val subscriptions = mutableListOf<AppleLibraryPlaylistOutcomeSubscription>()

    init {
        reader.onSession { session -> session.playlists.addOutcomeListener(::fanOut) }
    }

    /**
     * Creates a playlist named [name] with [songRawIds] (track ids, in order, duplicates kept; may be
     * empty). It is shown at once under a local id, returned in the result; the server's id arrives
     * as a `created` outcome.
     */
    public fun create(
        name: String,
        songRawIds: List<String>,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = edit(completion) { session ->
        val created = session.playlists.create(name, songRawIds)
        AppleLibraryPlaylistEditResult(created.record.appleKind(), created.localId, null)
    }

    /** Renames a playlist. A blank name is `invalid` and nothing is recorded. */
    public fun rename(
        playlistId: String,
        name: String,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = record(completion) { it.rename(playlistId, name) }

    /** Appends [songRawIds] at the end: never positional, so it needs no view. */
    public fun append(
        playlistId: String,
        songRawIds: List<String>,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = record(completion) { it.append(playlistId, songRawIds) }

    /**
     * Appends an album's tracks in album order. When the album's tracks were never read on this
     * device it reads them first, which needs the reader online; otherwise `notCached`.
     */
    public fun appendAlbum(
        playlistId: String,
        albumRawId: String,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = edit(completion) { session ->
        AppleLibraryPlaylistEditResult(session.playlists.appendAlbum(playlistId, albumRawId).appleKind(), null, null)
    }

    /** Removes the entries at [indices] of [expectedEntries], the view the person acted on. */
    public fun remove(
        playlistId: String,
        indices: List<Int>,
        expectedEntries: List<String>,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = record(completion) { it.remove(playlistId, indices.toSet(), expectedEntries) }

    /** Moves the entry at [from] to [to], both positions in [expectedEntries]. */
    public fun move(
        playlistId: String,
        from: Int,
        to: Int,
        expectedEntries: List<String>,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = record(completion) { it.move(playlistId, from, to, expectedEntries) }

    /** Deletes a playlist. A local one never sent is simply gone (`compactedAway`). */
    public fun delete(
        playlistId: String,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = record(completion) { it.delete(playlistId) }

    /**
     * Takes back one pending change of kind [change] — `create`, `details`, `entries` or `delete` —
     * so it is not sent again: `compactedAway` when it never reached the server, `alreadySent` when
     * it may have, `unchanged` when none is pending, `invalid` for an unknown kind.
     */
    public fun withdraw(
        playlistId: String,
        change: String,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = record(completion) { editor ->
        val kind = PlaylistRowKind.entries.firstOrNull { it.wireName == change } ?: return@record PlaylistEditRecord.Invalid
        editor.withdraw(playlistId, kind)
    }

    /**
     * The person's answer to a `possibleDuplicate` outcome for the create [localId]: [playlistId] is
     * the candidate that IS theirs, or null when none is (the create is then sent).
     */
    public fun chooseCreated(
        localId: String,
        playlistId: String?,
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
    ): AppleLibraryReaderOperation = record(completion) { it.chooseCreated(localId, playlistId) }

    /** Every playlist change not yet on the server, oldest first, so the person can withdraw one. */
    public fun pendingChanges(completion: (AppleLibraryPlaylistPendingChanges) -> Unit): AppleLibraryReaderOperation =
        reader.operation(completion, failed = { kind -> AppleLibraryPlaylistPendingChanges(emptyList(), kind) }) { session ->
            AppleLibraryPlaylistPendingChanges(session.playlists.pendingChanges().map { it.toApple() }, null)
        }

    /** How each queued change ended, or why it waits. Delivered on the main thread until closed. */
    public fun subscribeOutcomes(listener: AppleLibraryPlaylistOutcomeListener): AppleLibraryPlaylistOutcomeSubscription {
        val subscription = AppleLibraryPlaylistOutcomeSubscription(reader, this, listener)
        reader.onReader { subscriptions += subscription }
        return subscription
    }

    // ---- Internals ----------------------------------------------------------------------------------

    internal fun unregister(subscription: AppleLibraryPlaylistOutcomeSubscription) {
        subscriptions -= subscription
    }

    /** Reader thread: the editor's listener. */
    private fun fanOut(outcome: PlaylistEditOutcome) {
        val converted = try {
            outcome.toApple()
        } catch (_: Throwable) {
            return
        }
        subscriptions.toList().forEach { it.emit(converted) }
    }

    private fun record(
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
        change: (PlaylistEditor) -> PlaylistEditRecord,
    ): AppleLibraryReaderOperation = edit(completion) { session ->
        AppleLibraryPlaylistEditResult(change(session.playlists).appleKind(), null, null)
    }

    private fun edit(
        completion: (AppleLibraryPlaylistEditResult) -> Unit,
        work: suspend (LibraryReaderSession) -> AppleLibraryPlaylistEditResult,
    ): AppleLibraryReaderOperation =
        reader.operation(completion, failed = { kind -> AppleLibraryPlaylistEditResult(null, null, kind) }, work = work)
}

// ---- Values that cross --------------------------------------------------------------------------------

/**
 * What an edit did (§18.6). Exactly one of [record] and [errorKind] is set.
 *
 * - [record]: `pending` (shown now, queued), `compactedAway` (it undid what was queued; nothing is
 *   sent), `unchanged`, `invalid` (a blank name, no songs, a position outside the view, a move onto
 *   itself), `notEditable` (another user's or a read-only playlist), `notCached` (the playlist's or
 *   album's entries were never read on this device), `staleView` (the view acted on is not the one
 *   now shown — re-render and let the person act again), `deleted`, `notRecorded` (the device could
 *   not record it) or `alreadySent` (a withdrawal too late to undo).
 * - [localId]: for a `pending` create, the local id the new playlist is shown under.
 * - [errorKind]: `cancelled`, `closed` or `internalFailure` when the edit never ran.
 */
public class AppleLibraryPlaylistEditResult internal constructor(
    public val record: String?,
    public val localId: String?,
    public val errorKind: String?,
)

/**
 * How one queued playlist change ended, or why it waits (§18.6).
 *
 * - [kind]: `saved`, `created` (a local playlist now exists on the server as [playlistId]; its local
 *   id is [localId]), `notSaved` (refused or failed too often; the server's state shows again),
 *   `changedElsewhere` (not written: the entries changed on the server since the edit was made),
 *   `diverged` (written, but the read-back is not what was meant), `held` (kept unsent with every
 *   change after it; [errorKind] says why), `superseded` ([fields] were changed elsewhere; the
 *   server wins), `notRecorded`, `possiblyCreated` (a create in doubt was deleted here; [candidates]
 *   may be what it made — offer to delete one, never delete on inference) or `possibleDuplicate`
 *   (a create in doubt may already be one of [candidates]; ask, then `chooseCreated`).
 * - [change]: `create`, `details`, `entries` or `delete` where the outcome names one.
 * - [serverEntries] and [intendedEntries]: for `changedElsewhere` and `diverged`, the server's
 *   entries and those meant, as track ids; null otherwise.
 * - [fields]: for `superseded`, `name`, `comment` and/or `public`.
 */
public class AppleLibraryPlaylistOutcome internal constructor(
    public val kind: String,
    public val playlistId: String,
    public val change: String?,
    public val localId: String?,
    public val name: String?,
    public val candidates: List<String>,
    public val serverEntries: List<String>?,
    public val intendedEntries: List<String>?,
    public val fields: List<String>,
    public val errorKind: String?,
)

/** One queued playlist change, so a shell can list it and offer to withdraw it. */
public class AppleLibraryPlaylistPendingChange internal constructor(
    public val playlistId: String,
    /** `create`, `details`, `entries` or `delete`. */
    public val change: String,
    /** Sent, and its answer lost: it may be on the server already. */
    public val inDoubt: Boolean,
    public val failures: Int,
    /** A create waiting for the person's choice: its candidates; null otherwise. */
    public val candidates: List<String>?,
    /** A create waiting for the person's choice: the name the candidates share; null otherwise. */
    public val name: String?,
)

/** The pending changes, or [errorKind] (`cancelled`, `closed`, `internalFailure`) with none. */
public class AppleLibraryPlaylistPendingChanges internal constructor(
    public val changes: List<AppleLibraryPlaylistPendingChange>,
    public val errorKind: String?,
)

/** Receives playlist outcomes, on the main thread, until the subscription is closed. */
public interface AppleLibraryPlaylistOutcomeListener {
    public fun onPlaylistOutcome(outcome: AppleLibraryPlaylistOutcome)
}

/** The stream of playlist outcomes. [close] is idempotent; nothing is delivered after it returns on the main thread. */
@OptIn(ExperimentalAtomicApi::class)
public class AppleLibraryPlaylistOutcomeSubscription internal constructor(
    private val reader: AppleLibraryReaderClient,
    private val owner: AppleLibraryPlaylistClient,
    listener: AppleLibraryPlaylistOutcomeListener,
) {
    private val listener = AtomicReference<AppleLibraryPlaylistOutcomeListener?>(listener)

    public fun close() {
        if (listener.exchange(null) == null) return
        reader.onReader { owner.unregister(this) }
    }

    /** Reader thread. */
    internal fun emit(outcome: AppleLibraryPlaylistOutcome) {
        if (listener.load() == null) return
        reader.onMain { if (!reader.isClosed) listener.load()?.onPlaylistOutcome(outcome) }
    }
}

// ---- Closed vocabularies -------------------------------------------------------------------------------

internal fun PlaylistEditRecord.appleKind(): String = when (this) {
    PlaylistEditRecord.Pending -> "pending"
    PlaylistEditRecord.CompactedAway -> "compactedAway"
    PlaylistEditRecord.Unchanged -> "unchanged"
    PlaylistEditRecord.Invalid -> "invalid"
    PlaylistEditRecord.NotEditable -> "notEditable"
    PlaylistEditRecord.NotCached -> "notCached"
    PlaylistEditRecord.StaleView -> "staleView"
    PlaylistEditRecord.Deleted -> "deleted"
    PlaylistEditRecord.NotRecorded -> "notRecorded"
    PlaylistEditRecord.AlreadySent -> "alreadySent"
}

internal fun PlaylistRowKind.appleKind(): String = when (this) {
    PlaylistRowKind.Create -> "create"
    PlaylistRowKind.Details -> "details"
    PlaylistRowKind.Entries -> "entries"
    PlaylistRowKind.Delete -> "delete"
}

internal fun PlaylistDetailField.appleKind(): String = when (this) {
    PlaylistDetailField.Name -> "name"
    PlaylistDetailField.Comment -> "comment"
    PlaylistDetailField.Public -> "public"
}

internal fun PendingPlaylistChange.toApple() = AppleLibraryPlaylistPendingChange(
    playlistId = playlistId,
    change = kind.appleKind(),
    inDoubt = inDoubt,
    failures = failures,
    candidates = candidates,
    name = name,
)

internal fun PlaylistEditOutcome.toApple(): AppleLibraryPlaylistOutcome {
    fun outcome(
        kind: String,
        change: String? = null,
        localId: String? = null,
        name: String? = null,
        candidates: List<String> = emptyList(),
        serverEntries: List<String>? = null,
        intendedEntries: List<String>? = null,
        fields: List<String> = emptyList(),
        errorKind: String? = null,
    ) = AppleLibraryPlaylistOutcome(kind, playlistId, change, localId, name, candidates, serverEntries, intendedEntries, fields, errorKind)
    return when (this) {
        is PlaylistEditOutcome.Saved -> outcome("saved", change = kind.appleKind())
        is PlaylistEditOutcome.Created -> outcome("created", change = "create", localId = localId)
        is PlaylistEditOutcome.NotSaved -> outcome("notSaved", change = kind.appleKind(), errorKind = error.readerErrorKind())
        is PlaylistEditOutcome.ChangedElsewhere -> outcome("changedElsewhere", change = "entries", serverEntries = serverEntries)
        is PlaylistEditOutcome.Diverged ->
            outcome("diverged", change = kind.appleKind(), serverEntries = serverEntries, intendedEntries = intendedEntries)
        is PlaylistEditOutcome.Held -> outcome("held", change = kind.appleKind(), errorKind = error.readerErrorKind())
        is PlaylistEditOutcome.Superseded ->
            outcome("superseded", change = "details", fields = fields.map { it.appleKind() }.sorted())
        is PlaylistEditOutcome.NotRecorded -> outcome("notRecorded")
        is PlaylistEditOutcome.PossiblyCreated ->
            outcome("possiblyCreated", change = "create", localId = localId, name = name, candidates = candidates)
        is PlaylistEditOutcome.PossibleDuplicate ->
            outcome("possibleDuplicate", change = "create", localId = localId, name = name, candidates = candidates)
    }
}
