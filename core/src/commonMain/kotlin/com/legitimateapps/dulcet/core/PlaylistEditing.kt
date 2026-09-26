package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Playlist editing in a reader (spec §18.6): create, rename, comment, make public or private, add,
 * remove, reorder and delete — shown at once, queued in the mutation outbox, sent at least once,
 * and never allowed to remove a song the person did not mean to remove.
 *
 * **The hazard this file exists for.** Subsonic removes playlist entries by POSITION
 * (`updatePlaylist?songIndexToRemove=`), and OBSERVED on the reference server (CONF-88): the indices
 * are applied to the list as it is when the request arrives, an index past the end is silently
 * ignored, and the answer is an empty `ok` either way. So an index computed from a view another
 * client has since changed removes whichever song now sits there, and nothing reports it.
 *
 * **The strategy.** No position is ever computed from a list the server has not just shown:
 *
 * 1. Every entry edit is recorded against the **view** it was made on: the shell passes the entry
 *    ids it showed, and a view that is not the one the core now publishes is refused
 *    ([PlaylistEditRecord.StaleView]) — the tap was aimed at a list that has since moved.
 * 2. Entry edits of one playlist compact into ONE outbox row: the server's entries when the first
 *    edit was made (`base`) and the entries the person now wants (`target`).
 * 3. Sending re-reads the playlist first, and holds ONE slot of the per-server bound from that read
 *    to the write, so nothing else this device sends lands between them. Then:
 *    - a change that only adds songs at the end is an append (`songIdToAdd`), which is not
 *      positional, sent whatever the server now holds;
 *    - any other change is written only when the server still holds exactly `base`; otherwise
 *      nothing is written ([PlaylistEditOutcome.ChangedElsewhere]) and the person sees the server's
 *      list;
 *    - a change that only REMOVES entries sends their positions in the list just re-read
 *      (`updatePlaylist?songIndexToRemove=`, highest first), which leaves every other entry — and
 *      anything another client adds meanwhile — where it is;
 *    - a move, an insert, or a mix of removals and additions has no positional expression in the
 *      protocol, so it is the WHOLE desired list (`createPlaylist` with `playlistId`, which OBSERVED
 *      keeps the id, name, comment and visibility).
 * 4. After every write the playlist is read back. A removal is kept only if exactly the chosen
 *    entries went missing and nothing else changed; a whole list only if the entries are the ones
 *    sent and the name, comment and visibility are those the re-read saw. Anything else is told
 *    ([PlaylistEditOutcome.Diverged]) with the server's entries and the entries meant.
 *
 * **What remains, stated honestly.** Subsonic has no conditional write, so one round trip remains
 * between the re-read and the write in which another client's change can land:
 * - under a **removal**, the positions sent name whatever sits there when the request arrives, so
 *   another client's change can make one name a different song, which is removed instead. The
 *   read-back detects this exactly when the list that results differs from the list meant, and the
 *   person is told. It CANNOT when the other client changed nothing but the chosen positions — for
 *   example, replaced the chosen entry with another song: that song is removed, the list that
 *   results is the one meant, and it is reported saved. A change that only appends is kept, and
 *   told.
 * - under a **whole list**, any entry change another client makes in that round trip is
 *   OVERWRITTEN, and an overwrite cannot be detected: the read-back equals what was sent by
 *   construction. A header change in the same round trip is detected and told, because it may mean
 *   another client was editing the list too; an entry change alone is lost silently. This is why
 *   removals, the commonest positional edit, do not use it.
 *
 * **Offline.** Every edit queues, including positional ones, because the base it was made on
 * travels with it: replayed on reconnect, it is written only if the server still holds that base,
 * and refused with words otherwise. An append made on a playlist whose entries were never read
 * has no base and needs none — appends are not positional — so it is sent as an append.
 *
 * **At least once.** A write is marked attempted before it is sent, with the list the server holds
 * if it lands (`sent`), and — for an append — the songs it appends. Before a retry the re-read shows
 * whether it landed: the server holds `target`; or `sent`, or `sent` without song ids this device
 * added that the server does not know (it drops those silently, OBSERVED) — this device's own write,
 * even after a later edit changed `target`; or, for an append whose list changed elsewhere as well,
 * a list ending with the songs appended (ASSUMED to be this device's). A server still holding the
 * list the write was sent onto holds a write that never arrived, which is sent again. The songs an
 * append in doubt carried travel with its row through later edits. One reshaped here (one of its
 * songs removed or moved) that landed is positional against the list it was sent onto, never
 * appended again; one that never arrived leaves the row's own edit, sent as an append when it only
 * adds — never as the whole list. A removal sent in batches records each verified batch as
 * progress. A create in doubt is never sent again on a guess (below).
 *
 * **A create in doubt is identified without any clock.** Just before every send of a create, the
 * server's playlists are listed and the ids of ALL of them — whatever their names — recorded with
 * the send. Every id this device later ties to another of its creates joins that record for every
 * create still in doubt: one another create is found to have made (answered, adopted or chosen),
 * and one offered to the person for another create, as what it may have made or may be. After a
 * lost answer — or an empty `ok` — a create's CANDIDATES are the playlists of the name that send
 * carried whose ids are in no such record, and not mapped to another create made here, whoever the
 * server says owns them. No `created` stamp, timezone or device clock is compared, so a playlist
 * that existed before the send is never a candidate — however close in time, and even renamed into
 * the name later — and nor is one this device knows another of its creates made, or has offered
 * for another. What another create's lost send made CAN be a candidate while that create is still
 * in doubt itself: nothing yet says whose it is. So a lone candidate is adopted only while no other
 * create of this device that sent the same name is in doubt, and named otherwise; no create adopts
 * a playlist another create of that name may have made.
 * - No candidate: nothing the send could have made is listed, and it is sent again. This does not
 *   prove the send did not land: a server that commits a timed-out create only after the next
 *   flush has listed its playlists ends with two, and nobody is told.
 * - Exactly one, while no other create of this device that sent the same name is in doubt, owned
 *   by this account or with no owner stated (compared ignoring case), holding the songs sent in the
 *   order sent, or some of them — every copy of a song left out, never all the songs: it is the
 *   create's playlist, adopted, and any difference from the songs sent is told
 *   ([PlaylistEditOutcome.Diverged]). A server dropping ids it does not know cannot be told apart
 *   from a playlist another client made with fewer of the songs.
 * - Several; or one while another create of this device that sent the same name is in doubt, one
 *   holding other songs (or the same in another order), or one whose stated owner is not this
 *   account: nothing is sent.
 *   The person is told the candidates ([PlaylistEditOutcome.PossibleDuplicate]) and the create waits
 *   for their choice ([chooseCreated]: one of them, or none — which sends it) or for them to
 *   withdraw it. An owner that does not match names a candidate and never rules it out: a server
 *   may state this account in a form other than the name it signs in with. A candidate another
 *   create then settles leaves the choice: a choice of it is void, and the create is asked again
 *   with those that remain — never adopting one the person passed over — or, with none left, looks
 *   again.
 * - Deleted here while in doubt — before the flush, or while it looked: NOTHING is deleted on
 *   inference. The person is told the candidates ([PlaylistEditOutcome.PossiblyCreated]) so a shell
 *   can offer to delete one; the delete the person confirms is an ordinary delete by id. Only an id
 *   the server's own answer named is deleted without them. A playlist found by inference is written
 *   nothing once the delete is seen — checked just before the create's comment or visibility is
 *   written — but a write already out when the delete arrives is not recalled, and may land.
 * What was sent is recorded with the send, so a rename or song change made here meanwhile neither
 * hides the create nor is lost: it follows the adopted playlist as its own change. What remains: a
 * playlist another client makes for this account under the same name, with the songs sent or some of
 * them, between a lost send and the next flush is indistinguishable from the create's own — alone, it
 * is adopted, merging the two; a playlist the send made, then renamed or deleted elsewhere before the
 * next flush, leaves no candidate, so a create still wanted is sent again and one deleted here names
 * nothing; a create committed late, as above; and with two creates of one name in doubt, what either
 * made may be offered for the other, and a create whose own playlist was offered for another is sent
 * again — a duplicate for the person to delete, never a playlist lost.
 *
 * **Without `formPost`** every request stays within [QUERY_BUDGET_BYTES] of parameters: appends —
 * including a create's songs beyond the first request's — go in batches, in order; removals go in
 * batches, highest positions first, each verified by the read before it; a whole list that does not
 * fit is refused with nothing written (`CapabilityUnsupported(PlaylistWholeListWrite)`).
 *
 * **Permissions (§10.4).** An edit needs the server's `readonly: false`, or — for a server that
 * does not say — ownership. OBSERVED on the reference server: another user's public playlist is
 * `readonly: true` and every edit of it answers code 50; another user's private playlist is code 70
 * to a non-admin; an ADMIN sees every user's playlists, `readonly: true` for those of others, and
 * the server would let the admin edit them anyway. Dulcet follows `readonly` and does not offer
 * that override. REQUIREMENT on the shells (not yet met by any shell): show such a playlist's owner
 * and present it read-only, with no edit affordance.
 *
 * **Failures.** A refusal is dropped and told; a failure of this change alone is retried up to
 * [MAX_FAILURES], and the flush moves on; no answer, or a gateway that cannot reach the server, stops
 * the flush with every change kept, counting toward nothing. A request refused ACCESS — credentials
 * refused (envelope code 40, or a bare 401), a proxy's 403 or 407 — is followed by one authenticated
 * `ping`: refused too, the ACCOUNT is refused, and the flush stops with every change kept and the
 * person told ([PlaylistEditOutcome.Held]); answered, the refusal was that request's own — a rule in
 * front of one endpoint — and the change fails on its own, so one refused change never holds every
 * later one. A rate limit (429) stops it the same way, told once per run of 429s — a run begins with
 * a 429 for a change still queued, and ends when a flush sends something and meets no 429, or
 * finishes with nothing pending, or the queue empties here, never on one delivery; a 429 for a
 * change withdrawn or undone while its request was out, or for a create deleted here meanwhile, stops
 * the flush, sets the wait and keeps a run going — it is a 429 the flush met — but begins none and is
 * not told. Such a 429 for a deleted create proves it made nothing: its row goes, unless an earlier
 * send of it is still in doubt. Neither this flush nor the favourites one begins a change again
 * until `max(Retry-After, a floor doubling through the run from two seconds)` has passed, never more
 * than five minutes, whatever triggers them meanwhile — each checks the wait before it begins each
 * change, so one already running stops before its next; a change already under way is not recalled
 * and may finish its requests (a create's comment write, a later batch of an append sent without a
 * form body) — and a later 429 of either never shortens that wait. Whatever holds a change, the
 * person can withdraw it ([withdraw]). Only a send that went out and was not answered with a 429,
 * another 4xx, or another answer proving it did not apply (an error envelope, unreachable) makes that
 * too late to undo ([PlaylistEditRecord.AlreadySent]); a change whose sends were all answered so is
 * [PlaylistEditRecord.CompactedAway], edited here while a send was out or not — save a list change
 * so edited, which keeps the list it was sent onto and stays [PlaylistEditRecord.AlreadySent]. A
 * failure of the device's own database stops the flush too,
 * reported as local.
 *
 * **Storage.** Rows live in `mutation_outbox` (protected, §11.4) under `field = playlist.<kind>`
 * and the playlist id; the favourites outbox ignores them, and the §16.10 username rebinding
 * discards them with every other queued change. A row holds names, a comment, opaque song ids and
 * flags — never a credential or a URL.
 *
 * **Threading.** Confined to the reader's thread and checked, like [LibraryFavourites].
 */
internal enum class PlaylistRowKind(val wireName: String) {
    Create("create"),
    Details("details"),
    Entries("entries"),
    Delete("delete"),
    ;

    val field: String get() = "$PLAYLIST_FIELD_PREFIX$wireName"

    companion object {
        fun fromField(field: String): PlaylistRowKind? =
            if (field.startsWith(PLAYLIST_FIELD_PREFIX)) entries.firstOrNull { it.field == field } else null
    }
}

internal const val PLAYLIST_FIELD_PREFIX = "playlist."

/** The prefix of an id minted on this device for a playlist not yet created. Never sent. */
internal const val LOCAL_PLAYLIST_PREFIX = "dulcet-local-playlist:"

/** One of the header fields `updatePlaylist` sets. Values travel as strings (`public`: "true"/"false"). */
internal enum class PlaylistDetailField(val wireName: String) {
    Name("name"),
    Comment("comment"),
    Public("public"),
}

/** A pending change to one header field, with §18.3's conflict inputs. */
internal data class PendingFieldChange(
    val value: String,
    /** What the server last reported when the change was made; null when unknown. */
    val base: String?,
    /** Values whose send may have reached the server (their answers were lost). */
    val attempted: Set<String> = emptySet(),
)

/** One pending playlist change, as a row of the outbox. */
internal sealed interface PendingPlaylistRow {
    val playlistId: String
    val localSequence: Long
    val wallClock: Long
    val failures: Int
    val kind: PlaylistRowKind

    /** Created on this device. Later edits of a playlist not yet created fold into this row. */
    data class Create(
        override val playlistId: String,
        val name: String,
        val comment: String?,
        val isPublic: Boolean?,
        val songs: List<String>,
        /** A send whose answer was lost: the playlist may exist on the server already. */
        val attempted: Boolean,
        /** Deleted on this device after an attempted send: find it and delete it, never create it. */
        val cancelled: Boolean,
        override val localSequence: Long,
        override val wallClock: Long,
        override val failures: Int = 0,
        /**
         * The name and songs the latest send carried. A lost create is looked for as THAT playlist,
         * even after the person renamed it or changed its songs here; those edits then follow it.
         */
        val sentName: String? = null,
        val sentSongs: List<String>? = null,
        /**
         * The ids of every playlist the server listed just before the latest send, whatever its name;
         * of every playlist another create of this device was since found to have made; and of every
         * playlist since offered to the person for another create (§18.6). None of them is taken as
         * what that send made, whatever any clock says. Null only on a row no send has marked.
         */
        val seenBeforeSend: List<String>? = null,
        /**
         * The playlists the person was told may be what a send in doubt made
         * ([PlaylistEditOutcome.PossibleDuplicate]): the create is not sent again until they choose
         * ([PlaylistEditor.chooseCreated]) or withdraw it. A candidate another create settles leaves
         * this list, and the person is asked again with those that remain.
         */
        val candidates: List<String>? = null,
        /**
         * The candidate the person chose as this create's playlist, adopted by the next flush — void
         * if another create settles it first.
         */
        val chosen: String? = null,
    ) : PendingPlaylistRow {
        override val kind: PlaylistRowKind get() = PlaylistRowKind.Create

        /** Waiting for the person to choose between [candidates]: no flush sends or adopts it. */
        val awaitsChoice: Boolean get() = candidates != null && chosen == null && !cancelled
    }

    data class Details(
        override val playlistId: String,
        val fields: Map<PlaylistDetailField, PendingFieldChange>,
        override val localSequence: Long,
        override val wallClock: Long,
        override val failures: Int = 0,
    ) : PendingPlaylistRow {
        override val kind: PlaylistRowKind get() = PlaylistRowKind.Details
    }

    /**
     * The entries the person wants. With a [base] the change is verified against it and written as
     * [target]; without one (the entries were never read here) it is an append of [suffix] only.
     */
    data class Entries(
        override val playlistId: String,
        val base: List<String>?,
        val target: List<String>?,
        val suffix: List<String>,
        val attempted: Boolean,
        override val localSequence: Long,
        override val wallClock: Long,
        override val failures: Int = 0,
        /**
         * The list the server holds if the write in doubt landed — for a whole list or a removal,
         * the list it meant; for an append, the list it was sent onto plus [sentAdded]. A server
         * holding it holds this device's own write, not another client's, even after a later edit
         * changed [target].
         */
        val sent: List<String>? = null,
        /** For an append in doubt: the songs that request appended, a prefix of those still to add. */
        val sentAdded: List<String>? = null,
        /** For an append: how many of its songs are known to be on the server (earlier batches). */
        val appended: Int = 0,
    ) : PendingPlaylistRow {
        override val kind: PlaylistRowKind get() = PlaylistRowKind.Entries

        init {
            require((base == null) == (target == null))
            require(base != null || suffix.isNotEmpty())
            require(base == null || suffix.isEmpty())
            require(appended >= 0)
        }

        /** Only additions at the end: sent as an append, which no concurrent edit can misplace. */
        val appendOnly: Boolean
            get() = base == null || (target!!.size > base.size && target.subList(0, base.size) == base)

        /** For an append: every song it adds, in order — those already on the server first. */
        val added: List<String>
            get() = if (base == null) suffix else target!!.subList(base.size, target.size)
    }

    data class Delete(
        override val playlistId: String,
        val attempted: Boolean,
        override val localSequence: Long,
        override val wallClock: Long,
        override val failures: Int = 0,
    ) : PendingPlaylistRow {
        override val kind: PlaylistRowKind get() = PlaylistRowKind.Delete
    }
}

/** What an edit did to the outbox. */
internal enum class PlaylistEditRecord {
    /** A row now holds the change; it is in the next publication. */
    Pending,

    /** The change undid the pending ones and nothing may have reached the server: no row. */
    CompactedAway,

    /** Nothing to do: the playlist already shows this. */
    Unchanged,

    /** A blank name, no songs, an index outside the list, a move onto itself. Nothing recorded. */
    Invalid,

    /** This account may not edit the playlist (`readonly`, or another user's, §10.4). */
    NotEditable,

    /** The playlist's entries were never read on this device, so a position means nothing. */
    NotCached,

    /** The view the edit was made on is not the one now shown; the shell re-renders. */
    StaleView,

    /** Deleted on this device or gone from the server. */
    Deleted,

    /** The device could not record the change (its database failed). */
    NotRecorded,

    /**
     * Withdrawn too late to undo: a send of the change has gone out and was not answered with a 429,
     * another 4xx or another answer proving it did not apply — it is in flight now, or its answer was
     * lost — so the server may hold it already. It is not sent again, and the playlist shows what the
     * server answers, or its next read; a create's possible playlist is named
     * ([PlaylistEditOutcome.PossiblyCreated]). A change whose sends were all answered with a 429 or
     * a 4xx is [CompactedAway], even when edited here while one was out — except a list change so
     * edited: the list it was sent onto travels with the edit, and it stays [AlreadySent].
     */
    AlreadySent,
}

internal data class PlaylistCreateRecord(val record: PlaylistEditRecord, val localId: String?)

/** How a change ended, for the shell to tell the person. */
internal sealed interface PlaylistEditOutcome {
    val playlistId: String

    /** The server holds the change, read back. */
    data class Saved(override val playlistId: String, val kind: PlaylistRowKind) : PlaylistEditOutcome

    /** A playlist made on this device now exists on the server under [playlistId]. */
    data class Created(val localId: String, override val playlistId: String) : PlaylistEditOutcome

    /** Refused (code 50, gone), or failed [PlaylistEditor.MAX_FAILURES] times: the overlay goes. */
    data class NotSaved(override val playlistId: String, val kind: PlaylistRowKind, val error: DomainError) : PlaylistEditOutcome

    /**
     * The playlist's entries changed elsewhere since the edit was made, so it was NOT written: the
     * person sees [serverEntries] and is told. This is the stale-index hazard, prevented.
     */
    data class ChangedElsewhere(override val playlistId: String, val serverEntries: List<String>) : PlaylistEditOutcome

    /**
     * The write was answered `ok`, but the read-back is not what was meant: another client wrote in
     * the round trip between the re-read and the write (for a removal, possibly removing another
     * song than the one chosen), or the server dropped a song id it does not know. For a whole list,
     * also when the entries are as sent but the name, comment or visibility changed in that round
     * trip — a sign another client was editing, whose entry changes, if any, the replace overwrote
     * unseen (§18.6); for a create, the songs it sent. [serverEntries] is the server's list, which
     * the person now sees, and [intendedEntries] the list this device meant, so a shell can say what
     * to put back; both null for a header change.
     */
    data class Diverged(
        override val playlistId: String,
        val kind: PlaylistRowKind,
        val serverEntries: List<String>?,
        val intendedEntries: List<String>?,
    ) : PlaylistEditOutcome

    /**
     * The flush stopped at this change and kept it, and every change after it, unsent: the ACCOUNT
     * was refused access — this change's request was, and an authenticated `ping` sent to check was
     * refused too, so [error] is the ping's: an `Auth` error (credentials refused: envelope code 40,
     * or a bare 401, which reads as `Auth.InvalidCredentials` whether the server or a proxy sent it)
     * or an `HttpStatus` 403 or 407 — or the server asked to wait (`Server.Busy`, told once per run
     * of 429s). Nothing counts toward [PlaylistEditor.MAX_FAILURES]; a later flush sends it — after
     * the person signs in again, or once the wait has passed. The shell tells the person why, and
     * the person can withdraw the change ([PlaylistEditor.withdraw]).
     */
    data class Held(override val playlistId: String, val kind: PlaylistRowKind, val error: DomainError) : PlaylistEditOutcome

    /** A header field another client changed after this device last saw it: the server wins (§18.3). */
    data class Superseded(override val playlistId: String, val fields: Set<PlaylistDetailField>) : PlaylistEditOutcome

    data class NotRecorded(override val playlistId: String) : PlaylistEditOutcome

    /**
     * A create whose answer was lost was then deleted here — before the flush looked for it, or
     * while it did. Nothing is deleted on inference (§18.6): [candidates] are the server's playlists
     * that may be what that create made — named [name], not listed before the send, and neither
     * known to be nor offered as another create's of this device, whoever the server says owns them
     * — never empty, and the change is gone from the outbox. None of them is ever a candidate for
     * another create here. A shell offers "A playlist named [name] may have been created. Delete it
     * on the server?"; the delete the person confirms is an ordinary [PlaylistEditor.delete] of the
     * candidate's id.
     */
    data class PossiblyCreated(val localId: String, val name: String, val candidates: List<String>) : PlaylistEditOutcome {
        override val playlistId: String get() = localId
    }

    /**
     * A create whose answer was lost (or answered with an empty `ok`) may already be one of
     * [candidates] — playlists named [name] that were not listed before its send — and none is
     * certain: there are several; or the one there may be what another create of this device made,
     * which is also in doubt; or it does not hold what was sent; or its stated owner is not this
     * account (§18.6). None of them is ever a candidate for another create here. It is NOT sent
     * again on a guess: it waits, shown as pending, until the person chooses one
     * ([PlaylistEditor.chooseCreated] with its id), says none is theirs ([PlaylistEditor.chooseCreated]
     * with null, which sends it), or withdraws it ([PlaylistEditor.withdraw]). Told again, with fewer
     * candidates, when another create settles one of them — voiding a choice of that one.
     */
    data class PossibleDuplicate(val localId: String, val name: String, val candidates: List<String>) : PlaylistEditOutcome {
        override val playlistId: String get() = localId
    }
}

/** One pending playlist change as a shell lists it (§18.6), so the person can withdraw it. */
internal data class PendingPlaylistChange(
    /** The playlist's id: a local id for a create not yet on the server. */
    val playlistId: String,
    val kind: PlaylistRowKind,
    /** Sent, and its answer lost: it may be on the server already. */
    val inDoubt: Boolean,
    /** This change's own failures so far; at [PlaylistEditor.MAX_FAILURES] it is dropped and told. */
    val failures: Int,
    /** A create waiting for the person's choice ([PlaylistEditOutcome.PossibleDuplicate]): its candidates. */
    val candidates: List<String>?,
)

internal data class PlaylistFlushReport(
    val sent: Int,
    val saved: Int,
    val refused: Int,
    val changedElsewhere: Int,
    val diverged: Int,
    val deferred: Int,
    val stoppedBy: DomainError?,
    val stillPending: Int,
    /**
     * The device's own database failed during the flush. Nothing was lost: every change is kept as
     * it was, and the next flush retries. [stillPending] is -1 when it could not be counted.
     */
    val interruptedLocally: Boolean = false,
)

/** §18.3's conflict rule for one header field, ordered by issue sequence, never by clock. */
internal fun decideFieldDelivery(change: PendingFieldChange, localSequence: Long, serverValue: String?, serverReadIssueSeq: Long?): DeliveryDecision {
    if (serverValue == null || serverReadIssueSeq == null || serverReadIssueSeq <= localSequence) return DeliveryDecision.Send
    if (serverValue == change.value) return DeliveryDecision.AlreadyApplied
    if (serverValue in change.attempted) return DeliveryDecision.Send
    if (change.base != null && serverValue != change.base) return DeliveryDecision.ServerWins(0)
    return DeliveryDecision.Send
}

/**
 * The durable rows of one bound account's playlist changes, in `mutation_outbox`. Pure storage:
 * every decision is [PlaylistEditor]'s.
 */
internal class PlaylistOutbox(
    private val database: DulcetDatabase,
    private val cache: BoundSeenCache,
) {
    private val queries get() = database.protectedReservedDataQueries

    fun all(): List<PendingPlaylistRow> =
        queries.selectPendingMutations(cache.serverId).executeAsList().mapNotNull { row ->
            decode(row.target_id, row.field_, row.value_, row.local_sequence, row.wall_clock)
        }

    fun rowsFor(playlistId: String): List<PendingPlaylistRow> = all().filter { it.playlistId == playlistId }

    fun find(playlistId: String, kind: PlaylistRowKind): PendingPlaylistRow? =
        queries.selectPendingMutation(cache.serverId, playlistId, kind.field).executeAsOneOrNull()?.let { row ->
            decode(row.target_id, row.field_, row.value_, row.local_sequence, row.wall_clock)
        }

    /** Writes [row] as a NEW change: a fresh sequence, replacing any row of the same key. */
    fun put(row: PendingPlaylistRow): PendingPlaylistRow {
        val sequence = cache.issue()
        val stamped = row.withSequence(sequence, cache.now())
        val existing = queries.selectPendingMutation(cache.serverId, row.playlistId, row.kind.field).executeAsOneOrNull()
        if (existing == null) {
            queries.insertPendingMutation(cache.serverId, row.playlistId, row.kind.field, encode(stamped), stamped.localSequence, stamped.wallClock)
        } else {
            queries.replacePendingMutation(
                value = encode(stamped),
                local_sequence = stamped.localSequence,
                wall_clock = stamped.wallClock,
                server_id = cache.serverId,
                target_id = row.playlistId,
                field = row.kind.field,
            )
        }
        return stamped
    }

    /** Rewrites [row]'s value in place, only while the row still holds that change. */
    fun rewrite(row: PendingPlaylistRow) {
        queries.updatePendingMutationValue(
            value = encode(row),
            server_id = cache.serverId,
            target_id = row.playlistId,
            field = row.kind.field,
            local_sequence = row.localSequence,
        )
    }

    fun remove(playlistId: String, kind: PlaylistRowKind) {
        queries.deletePendingMutation(cache.serverId, playlistId, kind.field)
    }

    /** Removes [row] only if no newer change to the same key replaced it meanwhile. */
    fun removeIfUnchanged(row: PendingPlaylistRow) {
        queries.deletePendingMutationIfUnchanged(cache.serverId, row.playlistId, row.kind.field, row.localSequence)
    }

    fun current(row: PendingPlaylistRow): PendingPlaylistRow? =
        find(row.playlistId, row.kind)?.takeIf { it.localSequence == row.localSequence }

    private fun PendingPlaylistRow.withSequence(sequence: Long, wall: Long): PendingPlaylistRow = when (this) {
        is PendingPlaylistRow.Create -> copy(localSequence = sequence, wallClock = wall)
        is PendingPlaylistRow.Details -> copy(localSequence = sequence, wallClock = wall)
        is PendingPlaylistRow.Entries -> copy(localSequence = sequence, wallClock = wall)
        is PendingPlaylistRow.Delete -> copy(localSequence = sequence, wallClock = wall)
    }

    private fun encode(row: PendingPlaylistRow): String = JsonObject(
        buildMap {
            put("failures", JsonPrimitive(row.failures))
            when (row) {
                is PendingPlaylistRow.Create -> {
                    put("name", JsonPrimitive(row.name))
                    row.comment?.let { put("comment", JsonPrimitive(it)) }
                    row.isPublic?.let { put("public", JsonPrimitive(it)) }
                    put("songs", row.songs.json())
                    put("attempted", JsonPrimitive(row.attempted))
                    put("cancelled", JsonPrimitive(row.cancelled))
                    row.sentName?.let { put("sentName", JsonPrimitive(it)) }
                    row.sentSongs?.let { put("sentSongs", it.json()) }
                    row.seenBeforeSend?.let { put("seenBeforeSend", it.json()) }
                    row.candidates?.let { put("candidates", it.json()) }
                    row.chosen?.let { put("chosen", JsonPrimitive(it)) }
                }
                is PendingPlaylistRow.Details -> put(
                    "fields",
                    JsonObject(
                        row.fields.entries.associate { (field, change) ->
                            field.wireName to JsonObject(
                                buildMap {
                                    put("value", JsonPrimitive(change.value))
                                    put("base", change.base?.let(::JsonPrimitive) ?: JsonNull)
                                    put("attempted", change.attempted.sorted().json())
                                },
                            )
                        },
                    ),
                )
                is PendingPlaylistRow.Entries -> {
                    row.base?.let { put("base", it.json()) }
                    row.target?.let { put("target", it.json()) }
                    row.sent?.let { put("sent", it.json()) }
                    row.sentAdded?.let { put("sentAdded", it.json()) }
                    if (row.appended > 0) put("appended", JsonPrimitive(row.appended))
                    put("suffix", row.suffix.json())
                    put("attempted", JsonPrimitive(row.attempted))
                }
                is PendingPlaylistRow.Delete -> put("attempted", JsonPrimitive(row.attempted))
            }
        },
    ).toString()

    private fun decode(targetId: String, field: String, value: String, localSequence: Long, wallClock: Long): PendingPlaylistRow? {
        val kind = PlaylistRowKind.fromField(field) ?: return null
        if (targetId.isBlank()) return null
        val json = try {
            LIBRARY_JSON.parseToJsonElement(value).jsonObject
        } catch (_: IllegalArgumentException) {
            return null
        }
        val failures = (json["failures"] as? JsonPrimitive)?.intOrNull ?: 0
        fun number(name: String) = (json[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        fun bool(name: String) = (json[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        fun text(name: String) = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return try {
            when (kind) {
                PlaylistRowKind.Create -> PendingPlaylistRow.Create(
                    targetId, text("name") ?: return null, text("comment"), bool("public"),
                    json["songs"].strings() ?: return null, bool("attempted") ?: false, bool("cancelled") ?: false,
                    localSequence, wallClock, failures, text("sentName"), json["sentSongs"].strings(),
                    json["seenBeforeSend"].strings(), json["candidates"].strings(), text("chosen"),
                )
                PlaylistRowKind.Details -> {
                    val fields = (json["fields"] as? JsonObject ?: return null).entries.mapNotNull { (name, element) ->
                        val detail = PlaylistDetailField.entries.firstOrNull { it.wireName == name } ?: return@mapNotNull null
                        val change = element as? JsonObject ?: return@mapNotNull null
                        val set = (change["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                        val base = (change["base"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        detail to PendingFieldChange(set, base, change["attempted"].strings().orEmpty().toSet())
                    }.toMap()
                    if (fields.isEmpty()) return null
                    PendingPlaylistRow.Details(targetId, fields, localSequence, wallClock, failures)
                }
                PlaylistRowKind.Entries -> PendingPlaylistRow.Entries(
                    targetId, json["base"].strings(), json["target"].strings(), json["suffix"].strings().orEmpty(),
                    bool("attempted") ?: false, localSequence, wallClock, failures, json["sent"].strings(),
                    json["sentAdded"].strings(), number("appended")?.toInt() ?: 0,
                )
                PlaylistRowKind.Delete -> PendingPlaylistRow.Delete(targetId, bool("attempted") ?: false, localSequence, wallClock, failures)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private fun List<String>.json(): JsonArray = JsonArray(map(::JsonPrimitive))

private fun JsonElement?.strings(): List<String>? =
    (this as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }

/**
 * The shells' playlist editing, for one reader, and the reader's [LibraryPlaylistOverlay]. Every
 * entry point is confined to the reader's thread and checks it; none throws on that thread.
 *
 * [formPost]: the server advertises the OpenSubsonic `formPost` extension, so every write travels
 * as a form body, one request whatever its size. Without it writes are query strings — about 30
 * bytes per song — kept within [QUERY_BUDGET_BYTES]: appends and removals in batches, and a whole
 * list that does not fit refused with a reason (§18.6). Required, never defaulted: a wrong guess
 * either way is a failure a person meets on a large playlist.
 */
internal class PlaylistEditor(
    private val database: DulcetDatabase,
    private val readerProvider: () -> LibraryReader,
    private val formPost: Boolean,
) : LibraryPlaylistOverlay {
    private val reader: LibraryReader get() = readerProvider()
    private val cache: BoundSeenCache get() = reader.cache
    private val outbox by lazy { PlaylistOutbox(database, cache) }
    private val lock = Mutex()
    private val outcomeListeners = mutableListOf<(PlaylistEditOutcome) -> Unit>()

    /** Local ids of playlists created in this session, mapped to their server ids. */
    private val created = mutableMapOf<String, String>()

    /** This outbox's run of 429s: [PlaylistEditOutcome.Held] is told once per run. */
    private val busyRun = BusyRun()

    private val username: String? by lazy {
        database.seenCacheQueries.selectBinding(cache.serverId).executeAsOneOrNull()?.username
    }

    fun addOutcomeListener(listener: (PlaylistEditOutcome) -> Unit) {
        reader.checkConfined()
        outcomeListeners += listener
    }

    /** For the sign-out offer of §14.7: playlist changes that have not reached the server. */
    fun pendingCount(): Long {
        reader.checkConfined()
        return guarded(0L) { outbox.all().size.toLong() }
    }

    /**
     * Every playlist change not yet on the server, oldest first — the order they are sent in — so a
     * shell can list them, re-offer a choice the person has not made, and offer to withdraw any one.
     */
    fun pendingChanges(): List<PendingPlaylistChange> {
        reader.checkConfined()
        return guarded(emptyList()) {
            outbox.all().map { row ->
                PendingPlaylistChange(
                    playlistId = row.playlistId,
                    kind = row.kind,
                    inDoubt = row.sent,
                    failures = row.failures,
                    candidates = (row as? PendingPlaylistRow.Create)?.takeIf { it.awaitsChoice }?.candidates,
                )
            }
        }
    }

    /**
     * Takes back one pending change: it is not sent again, and the playlist shows the server's last
     * state again. However a change is held or failing, the person can always withdraw it (§18.6
     * "Failures").
     * - [PlaylistEditRecord.CompactedAway]: no send of it had gone out, or each one was answered with a
     *   429, another 4xx or another answer proving it did not apply — even when it was edited here
     *   while one was out, save a list change so edited — so it never reaches the server.
     * - [PlaylistEditRecord.AlreadySent]: too late to undo — a send of it is in flight, or its answer
     *   was lost, so the server may hold it already; the playlist shows what the server answers, or
     *   its next read.
     * A create is withdrawn as [delete] of its local id: one never sent is simply gone
     * ([PlaylistEditRecord.CompactedAway]); one sent is looked for once more, and what it may have made
     * is named, never deleted ([PlaylistEditOutcome.PossiblyCreated], [PlaylistEditRecord.AlreadySent]).
     * [PlaylistEditRecord.Unchanged] when no such change is pending.
     */
    fun withdraw(playlistId: String, kind: PlaylistRowKind): PlaylistEditRecord {
        reader.checkConfined()
        return recording(playlistId, { PlaylistEditRecord.NotRecorded }) {
            val row = outbox.find(playlistId, kind)
            when {
                row == null -> PlaylistEditRecord.Unchanged
                row is PendingPlaylistRow.Create -> when {
                    row.cancelled -> PlaylistEditRecord.Unchanged
                    else -> delete(playlistId).let { if (it == PlaylistEditRecord.Pending && row.attempted) PlaylistEditRecord.AlreadySent else it }
                }
                else -> {
                    outbox.removeIfUnchanged(row)
                    changed(setOf(playlistId))
                    if (row.sent) PlaylistEditRecord.AlreadySent else PlaylistEditRecord.CompactedAway
                }
            }
        }
    }

    /** A send of this change has gone out and its answer has not come back: the server may hold it. */
    private val PendingPlaylistRow.sent: Boolean
        get() = when (this) {
            is PendingPlaylistRow.Create -> attempted
            is PendingPlaylistRow.Details -> fields.values.any { it.attempted.isNotEmpty() }
            is PendingPlaylistRow.Entries -> attempted
            is PendingPlaylistRow.Delete -> attempted
        }

    /**
     * The person's answer to [PlaylistEditOutcome.PossibleDuplicate] for the create [localId]:
     * [playlistId], one of the candidates they were shown, IS that playlist — adopted by the next
     * flush, and every edit made here since follows it — or, with null, none of them is: the create is
     * sent, and those playlists are left as they are. A choice another create settles first is void,
     * and the person is asked again ([PlaylistEditOutcome.PossibleDuplicate]) with the candidates that
     * remain; none they passed over is adopted instead. [PlaylistEditRecord.Invalid] when the create is
     * not waiting for a choice or [playlistId] is not one of its candidates.
     */
    fun chooseCreated(localId: String, playlistId: String?): PlaylistEditRecord {
        reader.checkConfined()
        return recording(localId, { PlaylistEditRecord.NotRecorded }) {
            database.transactionWithResult {
                val row = outbox.find(localId, PlaylistRowKind.Create) as PendingPlaylistRow.Create?
                val candidates = row?.takeIf { it.awaitsChoice }?.candidates
                when {
                    row == null || candidates == null -> PlaylistEditRecord.Invalid
                    playlistId != null && playlistId !in candidates -> PlaylistEditRecord.Invalid
                    playlistId != null -> PlaylistEditRecord.Pending.also { outbox.put(row.copy(chosen = playlistId)) }
                    // None is theirs: sent afresh, its pre-send list taken anew — which now holds them.
                    else -> PlaylistEditRecord.Pending.also {
                        outbox.put(row.copy(attempted = false, sentName = null, sentSongs = null, seenBeforeSend = null, candidates = null))
                    }
                }
            }.also { if (it == PlaylistEditRecord.Pending) changed(setOf(localId)) }
        }
    }

    // ---- Recording -------------------------------------------------------------------------------

    /**
     * Creates a playlist named [name], optionally with [songs] (track ids, in order, duplicates
     * kept). Shown at once under the returned local id; sent when online, else on reconnect, and
     * told as [PlaylistEditOutcome.Created] with the server's id.
     */
    fun create(name: String, songs: List<String> = emptyList(), comment: String? = null, isPublic: Boolean? = null): PlaylistCreateRecord {
        reader.checkConfined()
        val trimmed = name.trim()
        if (trimmed.isEmpty() || songs.any(String::isBlank)) return PlaylistCreateRecord(PlaylistEditRecord.Invalid, null)
        return recording(null, { PlaylistCreateRecord(PlaylistEditRecord.NotRecorded, null) }) {
            val localId = database.transactionWithResult {
                // A durable sequence, so a local id is never reused for the life of the store.
                val id = LOCAL_PLAYLIST_PREFIX + cache.issue()
                outbox.put(PendingPlaylistRow.Create(id, trimmed, comment?.takeIf(String::isNotEmpty), isPublic, songs, false, false, 0, 0))
                id
            }
            changed(setOf(localId))
            PlaylistCreateRecord(PlaylistEditRecord.Pending, localId)
        }
    }

    fun rename(playlistId: String, name: String): PlaylistEditRecord {
        val trimmed = name.trim()
        // The reference server ignores an empty name (OBSERVED), so one is never queued.
        if (trimmed.isEmpty()) return PlaylistEditRecord.Invalid
        return updateDetails(playlistId, mapOf(PlaylistDetailField.Name to trimmed))
    }

    /** An empty comment clears it (OBSERVED: `comment=` removes the comment). */
    fun setComment(playlistId: String, comment: String): PlaylistEditRecord =
        updateDetails(playlistId, mapOf(PlaylistDetailField.Comment to comment))

    fun setPublic(playlistId: String, isPublic: Boolean): PlaylistEditRecord =
        updateDetails(playlistId, mapOf(PlaylistDetailField.Public to isPublic.toString()))

    /** Adds [songs] at the end — from a queue, an album or a multi-selection. Never positional. */
    fun append(playlistId: String, songs: List<String>): PlaylistEditRecord {
        reader.checkConfined()
        if (songs.isEmpty() || songs.any(String::isBlank)) return PlaylistEditRecord.Invalid
        return recording(playlistId, { PlaylistEditRecord.NotRecorded }) {
            database.transactionWithResult {
                when (val target = resolveTarget(playlistId)) {
                    is Target.Local -> {
                        outbox.put(target.row.copy(songs = target.row.songs + songs))
                        PlaylistEditRecord.Pending
                    }
                    is Target.Server -> {
                        val existing = outbox.find(target.id, PlaylistRowKind.Entries) as PendingPlaylistRow.Entries?
                        val row = when {
                            existing == null && target.cachedEntries != null ->
                                PendingPlaylistRow.Entries(target.id, target.cachedEntries, target.cachedEntries + songs, emptyList(), false, 0, 0)
                            existing == null -> PendingPlaylistRow.Entries(target.id, null, null, songs, false, 0, 0)
                            existing.base == null -> existing.copy(suffix = existing.suffix + songs)
                            else -> existing.copy(target = existing.target!! + songs)
                        }
                        outbox.put(row)
                        PlaylistEditRecord.Pending
                    }
                    is Target.Refused -> target.record
                }
            }.also { if (it == PlaylistEditRecord.Pending) changed(setOf(playlistId)) }
        }
    }

    /** Appends an album's tracks in its order. Reads the album first when its tracks are not cached. */
    suspend fun appendAlbum(playlistId: String, albumRawId: String): PlaylistEditRecord {
        reader.checkConfined()
        val cached = cache.album(albumRawId)
        if (cached == null || !cached.detailComplete) {
            if (!reader.online) return PlaylistEditRecord.NotCached
            if (reader.readAlbumDetail(albumRawId) != DetailReadResult.Read) return PlaylistEditRecord.NotCached
        }
        val tracks = cache.albumTracks(albumRawId).map { it.rawId }
        if (tracks.isEmpty()) return PlaylistEditRecord.NotCached
        return append(playlistId, tracks)
    }

    /**
     * Removes the entries at [indices] of [expectedEntries] — the entry ids of the publication the
     * person acted on. Positions are resolved HERE, against that view, never on the server.
     */
    fun remove(playlistId: String, indices: Set<Int>, expectedEntries: List<String>): PlaylistEditRecord =
        positional(playlistId, expectedEntries) { view ->
            if (indices.isEmpty() || indices.any { it !in view.indices }) null
            else view.filterIndexed { index, _ -> index !in indices }
        }

    /** Moves the entry at [from] to [to] (both positions in [expectedEntries]). */
    fun move(playlistId: String, from: Int, to: Int, expectedEntries: List<String>): PlaylistEditRecord =
        positional(playlistId, expectedEntries) { view ->
            if (from !in view.indices || to !in view.indices || from == to) null
            else view.toMutableList().also { it.add(to, it.removeAt(from)) }
        }

    /** Inserts [songs] before position [at] of [expectedEntries] (`at == size` appends). */
    fun insert(playlistId: String, songs: List<String>, at: Int, expectedEntries: List<String>): PlaylistEditRecord =
        positional(playlistId, expectedEntries) { view ->
            if (songs.isEmpty() || songs.any(String::isBlank) || at !in 0..view.size) null
            else view.subList(0, at) + songs + view.subList(at, view.size)
        }

    fun delete(playlistId: String): PlaylistEditRecord {
        reader.checkConfined()
        return recording(playlistId, { PlaylistEditRecord.NotRecorded }) {
            database.transactionWithResult {
                when (val target = resolveTarget(playlistId)) {
                    is Target.Refused -> target.record
                    is Target.Local -> {
                        if (target.row.attempted) {
                            outbox.put(target.row.copy(cancelled = true))
                            PlaylistEditRecord.Pending
                        } else {
                            outbox.remove(target.row.playlistId, PlaylistRowKind.Create)
                            PlaylistEditRecord.CompactedAway
                        }
                    }
                    is Target.Server -> {
                        // A delete supersedes every other pending change of the playlist.
                        outbox.remove(target.id, PlaylistRowKind.Details)
                        outbox.remove(target.id, PlaylistRowKind.Entries)
                        outbox.put(PendingPlaylistRow.Delete(target.id, false, 0, 0))
                        PlaylistEditRecord.Pending
                    }
                }
            }.also { if (it == PlaylistEditRecord.Pending || it == PlaylistEditRecord.CompactedAway) changed(setOf(playlistId)) }
        }
    }

    private fun updateDetails(playlistId: String, requested: Map<PlaylistDetailField, String>): PlaylistEditRecord {
        reader.checkConfined()
        return recording(playlistId, { PlaylistEditRecord.NotRecorded }) {
            database.transactionWithResult {
                when (val target = resolveTarget(playlistId)) {
                    is Target.Refused -> target.record
                    is Target.Local -> {
                        var row = target.row
                        requested.forEach { (field, value) ->
                            row = when (field) {
                                PlaylistDetailField.Name -> row.copy(name = value)
                                PlaylistDetailField.Comment -> row.copy(comment = value.takeIf(String::isNotEmpty))
                                PlaylistDetailField.Public -> row.copy(isPublic = value.toBooleanStrict())
                            }
                        }
                        if (row == target.row) PlaylistEditRecord.Unchanged else PlaylistEditRecord.Pending.also { outbox.put(row) }
                    }
                    is Target.Server -> {
                        val existing = outbox.find(target.id, PlaylistRowKind.Details) as PendingPlaylistRow.Details?
                        val server = serverDetails(target.id)
                        val fields = existing?.fields.orEmpty().toMutableMap()
                        requested.forEach { (field, value) ->
                            val pending = fields[field]
                            val serverValue = server[field]
                            when {
                                pending == null && value == serverValue -> Unit
                                pending != null && value == serverValue && pending.attempted.all { it == value } -> fields.remove(field)
                                else -> fields[field] = PendingFieldChange(value, pending?.base ?: serverValue, pending?.attempted.orEmpty())
                            }
                        }
                        when {
                            fields == existing?.fields.orEmpty() -> if (existing == null) PlaylistEditRecord.Unchanged else PlaylistEditRecord.Pending
                            fields.isEmpty() -> PlaylistEditRecord.CompactedAway.also { outbox.remove(target.id, PlaylistRowKind.Details) }
                            else -> PlaylistEditRecord.Pending.also {
                                outbox.put(PendingPlaylistRow.Details(target.id, fields, 0, 0))
                            }
                        }
                    }
                }
            }.also { if (it == PlaylistEditRecord.Pending || it == PlaylistEditRecord.CompactedAway) changed(setOf(playlistId)) }
        }
    }

    private fun positional(
        playlistId: String,
        expectedEntries: List<String>,
        edit: (List<String>) -> List<String>?,
    ): PlaylistEditRecord {
        reader.checkConfined()
        return recording(playlistId, { PlaylistEditRecord.NotRecorded }) {
            database.transactionWithResult {
                when (val target = resolveTarget(playlistId)) {
                    is Target.Refused -> target.record
                    is Target.Local -> {
                        if (target.row.songs != expectedEntries) return@transactionWithResult PlaylistEditRecord.StaleView
                        val songs = edit(target.row.songs) ?: return@transactionWithResult PlaylistEditRecord.Invalid
                        outbox.put(target.row.copy(songs = songs))
                        PlaylistEditRecord.Pending
                    }
                    is Target.Server -> {
                        val existing = outbox.find(target.id, PlaylistRowKind.Entries) as PendingPlaylistRow.Entries?
                        val cachedEntries = target.cachedEntries
                        val view = viewOf(existing, cachedEntries) ?: return@transactionWithResult PlaylistEditRecord.NotCached
                        if (view != expectedEntries) return@transactionWithResult PlaylistEditRecord.StaleView
                        val next = edit(view) ?: return@transactionWithResult PlaylistEditRecord.Invalid
                        // A positional edit carries the base it will be verified against: what the
                        // server holds if nobody else changed it. Over a pending append that is the
                        // list the append was made on plus its songs already known to be there.
                        val base: List<String>
                        val sent: List<String>?
                        if (existing != null && existing.appendOnly) {
                            base = (existing.base ?: cachedEntries!!) + existing.added.take(existing.appended)
                            // An append in doubt is this device's own write only if it was sent onto
                            // that very list; one sent onto a list another client had changed is not,
                            // and this edit must meet that change, not write over it.
                            sent = existing.sent
                        } else {
                            base = existing?.base ?: cachedEntries!!
                            sent = existing?.sent
                        }
                        // The songs an append in doubt carried travel with its list: an edit that
                        // still only adds at the end must not send them a second time, and one that
                        // reshapes them is verified against the list they were sent onto.
                        val sentAdded = existing?.sentAdded
                        val attempted = existing?.attempted ?: false
                        if (next == base && !attempted) {
                            outbox.remove(target.id, PlaylistRowKind.Entries)
                            PlaylistEditRecord.CompactedAway
                        } else {
                            // An in-doubt send's list travels on: the server holding it is this
                            // device's own write — and an undo over it must still be sent.
                            outbox.put(PendingPlaylistRow.Entries(target.id, base, next, emptyList(), attempted, 0, 0, sent = sent, sentAdded = sentAdded))
                            PlaylistEditRecord.Pending
                        }
                    }
                }
            }.also { if (it == PlaylistEditRecord.Pending || it == PlaylistEditRecord.CompactedAway) changed(setOf(playlistId)) }
        }
    }

    private sealed interface Target {
        data class Local(val row: PendingPlaylistRow.Create) : Target
        data class Server(val id: String, val cachedEntries: List<String>?) : Target
        data class Refused(val record: PlaylistEditRecord) : Target
    }

    /** Which playlist an edit applies to, and whether this account may edit it (§10.4). */
    private fun resolveTarget(playlistId: String): Target {
        val id = resolve(playlistId)
        if (id.startsWith(LOCAL_PLAYLIST_PREFIX)) {
            val row = outbox.find(id, PlaylistRowKind.Create) as PendingPlaylistRow.Create?
            return if (row == null || row.cancelled) Target.Refused(PlaylistEditRecord.Deleted) else Target.Local(row)
        }
        if (outbox.find(id, PlaylistRowKind.Delete) != null) return Target.Refused(PlaylistEditRecord.Deleted)
        val cached = cache.playlist(id) ?: return Target.Refused(PlaylistEditRecord.NotCached)
        if (cached.row.gone) return Target.Refused(PlaylistEditRecord.Deleted)
        if (!editable(cached.record)) return Target.Refused(PlaylistEditRecord.NotEditable)
        return Target.Server(id, cachedEntries(id))
    }

    private fun editable(record: CachePlaylistRecord): Boolean = when (record.readonly) {
        false -> true
        true -> false
        null -> isThisAccount(record.owner)
    }

    /**
     * Whether a stated owner is this account. Compared ignoring case: OBSERVED, the reference server
     * accepts a login in any case and reports the owner as the account was created ("Admin").
     */
    private fun isThisAccount(owner: String?): Boolean = owner != null && username?.equals(owner, ignoreCase = true) == true

    /**
     * The server's entries as last read here and as the playlist screen shows them — the same rows,
     * so a view the shell passes back compares equal; null when never read on this device.
     */
    private fun cachedEntries(playlistId: String): List<String>? {
        val listKey = playlistDetailListKey(playlistId)
        cache.listState(listKey) ?: return null
        return cache.listRows(listKey).map { it.rawId }
    }

    private fun viewOf(row: PendingPlaylistRow.Entries?, cachedEntries: List<String>?): List<String>? = when {
        row == null -> cachedEntries
        row.target != null -> row.target
        else -> cachedEntries?.plus(row.suffix)
    }

    private fun serverDetails(playlistId: String): Map<PlaylistDetailField, String> {
        val record = cache.playlist(playlistId)?.record ?: return emptyMap()
        return buildMap {
            put(PlaylistDetailField.Name, record.name)
            put(PlaylistDetailField.Comment, record.comment.orEmpty())
            put(PlaylistDetailField.Public, (record.isPublic == true).toString())
        }
    }

    private inline fun <T> recording(playlistId: String?, notRecorded: () -> T, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        playlistId?.let { emit(PlaylistEditOutcome.NotRecorded(it)) }
        notRecorded()
    }

    /**
     * Synchronously, before any send: the next publication of every playlist screen carries it.
     * A change recorded online then starts a flush; one made BY a flush does not start another.
     */
    private fun changed(rawIds: Set<String>) {
        guarded(Unit) { reader.republishPlaylists(rawIds + rawIds.map(::resolve)) }
        // Nothing left to send ends a run of 429s, however the queue emptied — a change withdrawn or
        // undone here, as much as a flush that sent the last one (§18.6 "Failures").
        if (guarded(false) { outbox.all().isEmpty() }) busyRun.end()
        if (reader.online && !flushing) launchFlush(reader.scope)
    }

    private var flushing = false

    private fun launchFlush(scope: CoroutineScope) {
        scope.launch {
            try {
                flush()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A database failure mid-flush leaves the rows as they were; the next flush retries.
            }
        }
    }

    // ---- The overlay (§16.20's rule, for playlists) ------------------------------------------------

    override fun resolve(rawId: String): String = created[rawId] ?: rawId

    override fun isLocal(rawId: String): Boolean = resolve(rawId).startsWith(LOCAL_PLAYLIST_PREFIX)

    override fun overlayList(items: List<LibraryItem>): List<LibraryItem> {
        val rows = guarded(emptyList()) { outbox.all() }
        val byId = rows.groupBy { it.playlistId }
        val shown = items.mapNotNull { item ->
            if (item !is LibraryItem.Playlist) return@mapNotNull item
            val pending = byId[item.rawId].orEmpty()
            if (pending.any { it is PendingPlaylistRow.Delete }) return@mapNotNull null
            overlayHeader(item, pending, entriesAfter(item.rawId, pending))
        }
        val shownIds = shown.mapTo(mutableSetOf()) { it.rawId }
        val creates = rows.filterIsInstance<PendingPlaylistRow.Create>()
            .filter { !it.cancelled && it.playlistId !in shownIds && resolve(it.playlistId) !in shownIds }
            .map { localHeader(it) }
        return shown + creates
    }

    override fun overlayDetail(rawId: String, header: LibraryItem.Playlist?, entries: List<LibraryItem>?): PlaylistOverlayView {
        val rows = guarded(emptyList()) { outbox.rowsFor(rawId) }
        if (rows.any { it is PendingPlaylistRow.Delete || (it is PendingPlaylistRow.Create && it.cancelled) }) {
            return PlaylistOverlayView(null, null, deletedLocally = true)
        }
        val create = rows.filterIsInstance<PendingPlaylistRow.Create>().firstOrNull()
        // A local id with no create row: created in an earlier session, or its create was undone.
        if (create == null && rawId.startsWith(LOCAL_PLAYLIST_PREFIX)) return PlaylistOverlayView(null, null, deletedLocally = true)
        if (create != null) return PlaylistOverlayView(localHeader(create), create.songs.map(::trackItem), deletedLocally = false)
        val pendingEntries = entriesAfter(rawId, rows)
        val shownEntries = pendingEntries?.map(::trackItem) ?: entries
        return PlaylistOverlayView(header?.let { overlayHeader(it, rows, pendingEntries) }, shownEntries, deletedLocally = false)
    }

    /** The entries a pending entries row leaves, or null when none is pending (or unknown). */
    private fun entriesAfter(playlistId: String, rows: List<PendingPlaylistRow>): List<String>? {
        val row = rows.filterIsInstance<PendingPlaylistRow.Entries>().firstOrNull() ?: return null
        return viewOf(row, guarded(null) { cachedEntries(playlistId) })
    }

    private fun overlayHeader(item: LibraryItem.Playlist, rows: List<PendingPlaylistRow>, entries: List<String>?): LibraryItem.Playlist {
        val details = rows.filterIsInstance<PendingPlaylistRow.Details>().firstOrNull()?.fields.orEmpty()
        val appendOnlyUnknown = rows.filterIsInstance<PendingPlaylistRow.Entries>().firstOrNull()?.takeIf { it.base == null }
        val record = guarded(null) { cache.playlist(item.rawId)?.record }
        return item.copy(
            name = details[PlaylistDetailField.Name]?.value ?: item.name,
            comment = details[PlaylistDetailField.Comment]?.value?.let { it.ifEmpty { null } } ?: item.comment,
            isPublic = details[PlaylistDetailField.Public]?.value?.toBooleanStrict() ?: item.isPublic,
            songCount = entries?.size ?: appendOnlyUnknown?.let { (item.songCount ?: 0) + it.suffix.size } ?: item.songCount,
            durationMilliseconds = if (entries != null) duration(entries) else item.durationMilliseconds,
            editable = record?.let(::editable) ?: item.editable,
            pendingChanges = rows.isNotEmpty(),
        )
    }

    private fun localHeader(row: PendingPlaylistRow.Create) = LibraryItem.Playlist(
        rawId = row.playlistId,
        name = row.name,
        songCount = row.songs.size,
        durationMilliseconds = duration(row.songs),
        owner = username,
        artworkKey = null,
        comment = row.comment,
        isPublic = row.isPublic,
        editable = true,
        pendingChanges = true,
        local = true,
    )

    /** The sum of the entries' durations, or null when any is unknown. */
    private fun duration(entries: List<String>): Long? {
        var total = 0L
        entries.forEach { id -> total += guarded(null) { cache.track(id)?.record?.durationMilliseconds } ?: return null }
        return total
    }

    private fun trackItem(rawId: String): LibraryItem {
        val member = CacheListMember(CacheItemKind.Track, rawId)
        val pending = guarded(emptyMap()) { reader.overlay.pending(cache.serverId, setOf(member)) }[member]
        val downloaded = guarded(emptySet()) { reader.downloads.downloadedTrackRawIds(cache.serverId) }
        val playability = reader.trackPlayability(rawId, downloaded)
        return guarded(null) { cache.track(rawId) }?.toItem(pending, playability) ?: LibraryItem.Track(
            rawId = rawId, title = null, albumRawId = null, albumTitle = null, artistName = null, artistRawId = null,
            discNumber = null, trackNumber = null, durationMilliseconds = null, sourceContainer = null, artworkKey = null,
            starred = pending?.starred, userRating = pending?.userRating, playCount = null, playability = playability,
            metadataMissing = true,
        )
    }

    // ---- Delivery --------------------------------------------------------------------------------

    /**
     * Sends every pending playlist change, oldest first, passing over a create that waits for the
     * person's choice. A refused change is dropped and told; one the server answered for without
     * applying — or refused access alone, while a ping is answered — stays pending and the flush moves
     * on, until [MAX_FAILURES]; a server that cannot be reached stops the flush with every change kept,
     * and one that refuses the account or asks to wait stops it and is told (the class comment's
     * "Failures"). A failure of the device's own database stops it too — reported
     * ([PlaylistFlushReport.interruptedLocally]), never thrown, with every change kept as it was.
     */
    suspend fun flush(): PlaylistFlushReport {
        reader.checkConfined()
        return lock.withLock {
            flushing = true
            val tally = Tally()
            try {
                flushLocked(tally)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                PlaylistFlushReport(
                    tally.sent, tally.saved, tally.refused, tally.changedElsewhere, tally.diverged, tally.deferred.size,
                    tally.stoppedBy, guarded(-1) { outbox.all().size }, interruptedLocally = true,
                )
            } finally {
                flushing = false
            }
        }
    }

    private suspend fun flushLocked(tally: Tally): PlaylistFlushReport {
        while (reader.online) {
            // The server asked for quiet (a 429): no change begins until the wait has passed. Checked
            // before each row, not once, so a wait the favourites flush's 429 sets meanwhile stops
            // this flush too; a change already under way is not recalled and may finish its requests.
            tally.stoppedBy = reader.busyError()
            if (tally.stoppedBy != null) break
            // A create waiting for the person's choice is passed over, never sent on a guess.
            val row = outbox.all().firstOrNull { it.key !in tally.deferred && !(it is PendingPlaylistRow.Create && it.awaitsChoice) } ?: break
            // Only a failure of a request is the server's to classify; anything else — the device's
            // own database — propagates to [flush], which reports it as local.
            val failure = try {
                deliver(row, tally)
                null
            } catch (thrown: LibraryRequestFailure) {
                thrown.error
            }
            if (failure == null) continue
            // A send of a create the person deleted while it was out, refused: nothing to tell them.
            // The refusal proves that send made nothing, and either the tombstone went with it (no
            // earlier send in doubt) or it survives and the next flush looks for what an earlier send
            // made (§18.6). Never the tombstone's OWN failure: its lookup failing leaves a playlist
            // that send may have made unnamed, so that is told (eighth review round).
            val deletedHere = row is PendingPlaylistRow.Create && (
                tombstonesProvenUnsent.remove(row.key) ||
                    (!row.cancelled && (outbox.find(row.playlistId, row.kind) as? PendingPlaylistRow.Create)?.cancelled == true)
                )
            // A refusal of access holds every change only when the ACCOUNT is refused: one ping asks,
            // once per flush. Answered, it was this request's own refusal, and fails like one.
            var error: DomainError = failure
            val failureClass = when {
                !failure.refusesAccess -> failure.failureClass()
                tally.accountAnswers -> PlaylistFailureClass.ThisChange
                else -> when (val pinged = reader.pingAfterRefusal()) {
                    null -> PlaylistFailureClass.ThisChange.also { tally.accountAnswers = true }
                    else -> {
                        error = pinged
                        if (pinged.failureClass() == PlaylistFailureClass.Transport) PlaylistFailureClass.Transport else PlaylistFailureClass.Held
                    }
                }
            }
            when (failureClass) {
                PlaylistFailureClass.Refused -> {
                    outbox.removeIfUnchanged(outbox.current(row) ?: row)
                    if (error.isNotFound() && row !is PendingPlaylistRow.Create) cache.markPlaylistNotFound(cache.issue(), row.playlistId)
                    changed(setOf(row.playlistId))
                    if (!deletedHere) {
                        tally.refused += 1
                        emit(PlaylistEditOutcome.NotSaved(row.playlistId, row.kind, error))
                    }
                }
                PlaylistFailureClass.ThisChange -> {
                    val current = outbox.current(row)
                    val failures = (current?.failures ?: row.failures) + 1
                    if (current != null && failures >= MAX_FAILURES) {
                        // Always told. [deletedHere] cannot hold here: a row still current is either not
                        // deleted (a delete writes its tombstone as a new change) or the tombstone
                        // itself, whose own failure is told.
                        outbox.removeIfUnchanged(current)
                        changed(setOf(row.playlistId))
                        tally.refused += 1
                        emit(PlaylistEditOutcome.NotSaved(row.playlistId, row.kind, error))
                    } else {
                        current?.let { outbox.rewrite(it.withFailures(failures)) }
                        tally.deferred += row.key
                    }
                }
                PlaylistFailureClass.Held -> {
                    tally.stoppedBy = error
                    // A 429 is told once per run of them, not once per retry. Every 429 this flush
                    // meets keeps its run going. One for a change withdrawn or undone while its
                    // request was out, or for a create deleted here meanwhile (a tombstone is not a
                    // queued change), still sets the wait, but nothing of it is queued: it neither
                    // begins nor lengthens a run, and is not told.
                    val tell = if (error is DomainError.Server.Busy) {
                        val queued = outbox.find(row.playlistId, row.kind)
                            ?.let { it !is PendingPlaylistRow.Create || !it.cancelled } == true
                        tally.met429 = true
                        reader.noteBusy(busyRun, error.retryAfter, count = queued)
                    } else {
                        true
                    }
                    if (tell) emit(PlaylistEditOutcome.Held(row.playlistId, row.kind, error))
                    break
                }
                PlaylistFailureClass.Transport -> {
                    tally.stoppedBy = error
                    break
                }
            }
        }
        val pending = outbox.all().size
        busyRun.flushed(tally.met429, tally.sent, pending)
        return PlaylistFlushReport(
            tally.sent, tally.saved, tally.refused, tally.changedElsewhere, tally.diverged, tally.deferred.size,
            tally.stoppedBy, pending,
        )
    }

    private class Tally {
        var sent = 0
        var saved = 0
        var refused = 0
        var changedElsewhere = 0
        var diverged = 0
        val deferred = mutableSetOf<String>()
        var stoppedBy: DomainError? = null

        /** An authenticated ping was answered this flush: a later refusal of access is its request's own. */
        var accountAnswers = false

        /** This flush met a 429 of its own: the run of them goes on. */
        var met429 = false
    }

    /** Delivers one row; a failure it cannot conclude itself is thrown for [flush] to classify. */
    private suspend fun deliver(row: PendingPlaylistRow, tally: Tally) {
        when (row) {
            is PendingPlaylistRow.Create -> deliverCreate(row, tally)
            is PendingPlaylistRow.Delete -> deliverDelete(row, tally)
            is PendingPlaylistRow.Details -> reader.listLock(playlistDetailListKey(row.playlistId)).withLock { deliverDetails(row, tally) }
            is PendingPlaylistRow.Entries -> {
                val end = reader.listLock(playlistDetailListKey(row.playlistId)).withLock {
                    // §16.14: a flush reads no epoch before its writes. Its reads are written through
                    // with the session's reading — none on a relaunch — and step 3 revalidates them.
                    val epoch = reader.sessionEpoch
                    // One slot from the re-read to the write (step 3 of the class comment).
                    reader.withOneSlot { slot -> sendEntries(row, EntriesSend(slot, epoch, tally)) }
                }
                if (end is EntriesEnd.Finish) finish(end.row, end.outcome, tally)
            }
        }
    }

    private class EntriesSend(val slot: LibraryReader.HeldSlot, val epoch: CatalogEpoch?, val tally: Tally)

    private sealed interface EntriesEnd {
        data class Finish(val row: PendingPlaylistRow, val outcome: PlaylistEditOutcome) : EntriesEnd

        /** A newer change replaced the row while it was being sent: the flush delivers that one next. */
        data object Replaced : EntriesEnd
    }

    private suspend fun EntriesSend.read(playlistId: String): PlaylistDetailRead = reader.readPlaylistDetail(playlistId, epoch, slot)

    /** Steps 3 and 4 of the class comment for one entries row, all on one held slot. */
    private suspend fun sendEntries(row: PendingPlaylistRow.Entries, send: EntriesSend): EntriesEnd {
        val read = send.read(row.playlistId)
        val server = read.ids
        val target = row.target
        if (target != null && server == target) {
            // Already there: an earlier send whose answer was lost, or the same edit elsewhere.
            return EntriesEnd.Finish(row, PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries))
        }
        val sent = row.sent
        val inDoubt = row.sentAdded
        // An append in doubt that never arrived — the server still holds the list it was sent onto —
        // leaves nothing to resolve: what remains is the row's own edit, and one that only adds is
        // an append, however it was reshaped here, never a whole list (§18.6).
        val neverArrived = inDoubt != null && sent != null && server == sent.subList(0, sent.size - inDoubt.size)
        // An append in doubt whose songs no longer head the songs still to add was reshaped here —
        // one of its songs removed or moved. If it landed, those songs are on the server, so what
        // remains is positional against that list, not an append.
        val inDoubtStillHeads = inDoubt == null || neverArrived || row.added.drop(row.appended).take(inDoubt.size) == inDoubt
        if (row.base == null || (row.appendOnly && inDoubtStillHeads)) return sendAppend(row, server, send)
        val rowBase = row.base
        var base = rowBase
        // The list an append in doubt was sent onto must be the one this edit was made on: one sent
        // onto a list another client had changed cannot vouch for this edit's base.
        val ownList = inDoubt == null || sent == rowBase + inDoubt
        if (row.attempted && sent != null && ownList) {
            if (server == sent) {
                // This device's own write in doubt landed: the list is verified as that one.
                base = sent
            } else if (server != rowBase && droppedOnly(server, sent, introduced = sent.toSet() - rowBase.toSet())) {
                // (A server still holding the list the write was sent onto holds a write that
                // never arrived, which is sent again — never one whose every new id was dropped.)
                // It landed, less song ids this device added that the server does not know.
                if (target == sent) return EntriesEnd.Finish(row, PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, server, target))
                base = server
            }
        }
        if (server != base) {
            // The stale-index hazard, prevented: the list moved since the edit was made.
            return EntriesEnd.Finish(row, PlaylistEditOutcome.ChangedElsewhere(row.playlistId, server))
        }
        val removed = removedPositions(base, target!!)
        return if (removed != null) sendRemoval(row, base, removed, send) else sendWholeList(row, read, target, send)
    }

    /**
     * A change that only removes: the positions of the removed entries in the list just re-read,
     * highest first — so no removal shifts another, and a server applying them one by one removes
     * the same entries as one applying them to the list as found (OBSERVED on the reference
     * server). Without `formPost` they go in batches, each verified by the read before it.
     */
    private suspend fun sendRemoval(row: PendingPlaylistRow.Entries, base: List<String>, removed: List<Int>, send: EntriesSend): EntriesEnd {
        var current = row
        var expected = base
        val batches = batches(listOf("playlistId" to row.playlistId), removed.sortedDescending()) { "songIndexToRemove" to it.toString() }
        for ((index, batch) in batches.withIndex()) {
            val positions = batch.toSet()
            val after = expected.filterIndexed { position, _ -> position !in positions }
            current = mark(current) { it.copy(attempted = true, sent = after) } ?: return EntriesEnd.Replaced
            send.tally.sent += 1
            writeChecked(current, "updatePlaylist", listOf("playlistId" to row.playlistId) + batch.map { "songIndexToRemove" to it.toString() }, send.slot)
            val readBack = send.read(row.playlistId).ids
            // Kept only if exactly the chosen entries went missing and nothing else changed.
            if (readBack != after) return EntriesEnd.Finish(current, PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, readBack, row.target))
            expected = readBack
            if (index < batches.lastIndex) {
                // A verified batch is progress: the rest resumes from the list just read back, so a
                // later batch that fails leaves a row the next flush continues, never a list that
                // looks changed elsewhere.
                current = rewriteCurrent(current) { it.copy(base = readBack, attempted = false, sent = null, sentAdded = null) } ?: return EntriesEnd.Replaced
            }
        }
        return EntriesEnd.Finish(current, PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries))
    }

    /**
     * A move, an insert, or a mix: the whole list, in order — `createPlaylist` with `playlistId`
     * keeps the playlist (OBSERVED). An entry change another client makes between the re-read and
     * this write is overwritten and cannot be seen; a header change in that round trip can, and is
     * told, since it means another client was editing too (§18.6).
     */
    private suspend fun sendWholeList(row: PendingPlaylistRow.Entries, before: PlaylistDetailRead, target: List<String>, send: EntriesSend): EntriesEnd {
        val parameters = listOf("playlistId" to row.playlistId) + target.map { "songId" to it }
        if (!formPost && encodedLength(parameters) > QUERY_BUDGET_BYTES) {
            // The protocol has no other way to write this, and as a query string it would not fit
            // what a proxy can be assumed to accept: refused with a reason, nothing written.
            val refusal = DomainError.CapabilityUnsupported(CapabilityFeature.PlaylistWholeListWrite)
            return EntriesEnd.Finish(row, PlaylistEditOutcome.NotSaved(row.playlistId, PlaylistRowKind.Entries, refusal))
        }
        val attempted = mark(row) { it.copy(attempted = true, sent = target) } ?: return EntriesEnd.Replaced
        send.tally.sent += 1
        writeChecked(attempted, "createPlaylist", parameters, send.slot)
        val after = send.read(row.playlistId)
        val outcome = when {
            after.ids != target -> PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, after.ids, target)
            after.playlist.header() != before.playlist.header() -> PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, after.ids, target)
            else -> PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries)
        }
        return EntriesEnd.Finish(attempted, outcome)
    }

    private fun CachePlaylistRecord.header() = Triple(name, comment, isPublic == true)

    /**
     * Additions at the end: appends, which no concurrent edit can misplace, sent whatever the server
     * now holds — in order, and without `formPost` in batches, each recorded once it is on the
     * server so a later failure never sends it again.
     */
    private suspend fun sendAppend(row: PendingPlaylistRow.Entries, server: List<String>, send: EntriesSend): EntriesEnd {
        var appended = row.appended
        var dropped = false
        val inDoubt = row.sentAdded
        val sent = row.sent
        // The caller sends an append here only while the songs in doubt still head those to add, or
        // when they never arrived.
        if (row.attempted && inDoubt != null && sent != null) {
            val onto = sent.subList(0, sent.size - inDoubt.size)
            val landed = when {
                server == sent -> true
                // Still the list it was sent onto: it never arrived (sending it again is harmless
                // even if it did and the server dropped every id).
                server == onto -> false
                droppedOnly(server, sent, introduced = inDoubt.toSet() - onto.toSet()) -> true.also { dropped = true }
                // ASSUMED (§18.6): the list also changed elsewhere, and a list that ends with the
                // songs that request appended ends with this device's own songs.
                server.endsWith(inDoubt) -> true
                else -> false
            }
            if (landed) appended += inDoubt.size
        }
        val pending = row.added.drop(appended)
        if (pending.isEmpty()) {
            val outcome = if (dropped) PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, server, sent) else PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries)
            return EntriesEnd.Finish(row, outcome)
        }
        var current = row
        var expected = server
        val chunks = batches(listOf("playlistId" to row.playlistId), pending) { "songIdToAdd" to it }
        for ((index, songs) in chunks.withIndex()) {
            val onto = expected
            val done = appended
            current = mark(current) { it.copy(attempted = true, sent = onto + songs, sentAdded = songs, appended = done) } ?: return EntriesEnd.Replaced
            send.tally.sent += 1
            writeChecked(current, "updatePlaylist", listOf("playlistId" to row.playlistId) + songs.map { "songIdToAdd" to it }, send.slot)
            expected = onto + songs
            appended += songs.size
            if (index < chunks.lastIndex) {
                val progress = appended
                current = rewriteCurrent(current) { it.copy(attempted = false, sent = null, sentAdded = null, appended = progress) } ?: return EntriesEnd.Replaced
            }
        }
        val readBack = send.read(row.playlistId).ids
        val outcome = if (readBack == expected && !dropped) PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries)
            else PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, readBack, expected)
        return EntriesEnd.Finish(current, outcome)
    }

    private suspend fun deliverDetails(row: PendingPlaylistRow.Details, tally: Tally) {
        val cached = cache.playlist(row.playlistId)
        val server = serverDetails(row.playlistId)
        val toSend = mutableMapOf<PlaylistDetailField, PendingFieldChange>()
        val superseded = mutableSetOf<PlaylistDetailField>()
        row.fields.forEach { (field, change) ->
            when (decideFieldDelivery(change, row.localSequence, server[field], cached?.row?.issueSeq)) {
                DeliveryDecision.Send -> toSend[field] = change
                DeliveryDecision.AlreadyApplied -> Unit
                is DeliveryDecision.ServerWins -> superseded += field
            }
        }
        if (superseded.isNotEmpty()) emit(PlaylistEditOutcome.Superseded(row.playlistId, superseded))
        if (toSend.isEmpty()) {
            outbox.removeIfUnchanged(outbox.current(row) ?: row)
            changed(setOf(row.playlistId))
            if (superseded.size < row.fields.size) {
                tally.saved += 1
                emit(PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Details))
            }
            return
        }
        val attempted = mark(row) { current ->
            current.copy(fields = current.fields.mapValues { (field, change) -> if (field in toSend) change.copy(attempted = change.attempted + change.value) else change })
        } ?: return
        tally.sent += 1
        writeChecked(attempted, "updatePlaylist", listOf("playlistId" to row.playlistId) + toSend.map { (field, change) -> field.wireName to change.value })
        reader.readPlaylistDetail(row.playlistId, reader.sessionEpoch)
        val readBack = serverDetails(row.playlistId)
        finish(
            attempted,
            if (toSend.all { (field, change) -> readBack[field] == change.value }) PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Details)
            else PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Details, null, null),
            tally,
        )
    }

    private suspend fun deliverDelete(row: PendingPlaylistRow.Delete, tally: Tally) {
        val attempted = mark(row) { it.copy(attempted = true) } ?: return
        tally.sent += 1
        try {
            writeChecked(attempted, "deletePlaylist", listOf("id" to row.playlistId))
        } catch (thrown: LibraryRequestFailure) {
            // Code 70 on a delete: already gone — possibly this device's own earlier send.
            if (!thrown.error.isNotFound()) throw thrown
        }
        cache.markPlaylistNotFound(cache.issue(), row.playlistId)
        finish(attempted, PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Delete), tally)
        reader.rereadList(LibraryQuery.Playlists)
    }

    private suspend fun deliverCreate(row: PendingPlaylistRow.Create, tally: Tally) {
        // What an earlier send carried — looked for as sent, whatever was edited here since.
        val earlierName = row.sentName ?: row.name
        val earlierSongs = row.sentSongs ?: createSongs(row)
        var listing: List<ListedPlaylist>? = null
        var adopted: String? = null
        if (row.attempted) {
            val listed = listPlaylists()
            listing = listed
            val candidates = lostCreateCandidates(row, earlierName, listed)
            if (row.cancelled) {
                // Deleted here after a send whose answer was lost. Nothing is deleted on inference, however
                // strong: every playlist that may be what that send made is named to the person, who can
                // delete one by its id — an ordinary delete, confirmed by them (§18.6) — and none of them
                // is ever a candidate for another create here, which could keep the one they delete.
                if (candidates.isNotEmpty()) offer(PlaylistEditOutcome.PossiblyCreated(row.playlistId, earlierName, candidates.map { it.id }))
                outbox.removeIfUnchanged(outbox.current(row) ?: row)
                changed(setOf(row.playlistId))
                reader.rereadList(LibraryQuery.Playlists)
                return
            }
            when (val found = resolveLostCreate(row, earlierName, earlierSongs, candidates)) {
                // Nothing of that name is listed that the send could have made: it is sent again. A
                // server that commits it only after this listing ends with two (§18.6).
                LostCreate.NotLanded -> Unit
                LostCreate.Choose -> return
                is LostCreate.Adopt -> adopted = found.id
            }
        }
        val id: String
        // Whether [id] is the server's own answer, or found without one: adopted, chosen by the
        // person, or left by an empty `ok`. Nothing found without an answer is ever deleted (§18.6).
        val inferred: Boolean
        val sentName: String
        val sentSongs: List<String>
        if (adopted != null) {
            id = adopted
            inferred = true
            sentName = earlierName
            sentSongs = earlierSongs
        } else {
            // Every playlist the server lists now is recorded with the send, whatever its name: none of
            // them can be what it makes (§18.6), even renamed into the name later. The list a create
            // in doubt was just checked against serves.
            val before = listing ?: listPlaylists()
            val songs = createSongs(row)
            val attempted = mark(row) { current ->
                current.copy(
                    attempted = true,
                    sentName = current.name,
                    sentSongs = songs,
                    seenBeforeSend = before.map { it.id },
                    candidates = null,
                    chosen = null,
                )
            } ?: return
            tally.sent += 1
            val answer = writeChecked(attempted, "createPlaylist", listOf("name" to attempted.name) + songs.map { "songId" to it })
            // OpenSubsonic answers with the playlist. A server that answers with an empty `ok` made one
            // it does not name: it is looked for at once, exactly as a lost answer's is.
            val answered = createdPlaylistId(answer.response.body)
            inferred = answered == null
            id = answered ?: when (
                val found = resolveLostCreate(attempted, attempted.name, songs, lostCreateCandidates(attempted, attempted.name, listPlaylists()))
            ) {
                is LostCreate.Adopt -> found.id
                LostCreate.Choose -> return
                // An `ok` with nothing new to show for it: this change's failure, and in doubt until
                // the next flush looks again.
                LostCreate.NotLanded -> throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
            }
            sentName = attempted.name
            sentSongs = songs
        }
        val deletedMeanwhile = reader.listLock(playlistDetailListKey(id)).withLock {
            // Checked as late as it can be, just before the first write to [id]: deleted here while the
            // flush looked for it — or waited for this lock — a playlist found by inference is named,
            // and nothing more is written to it. A write already out when the delete arrives is not
            // recalled and may land (§18.6).
            if (inferred && deletedHere(row.playlistId)) return@withLock true
            val extra = buildList {
                row.comment?.let { add(PlaylistDetailField.Comment to it) }
                row.isPublic?.let { add(PlaylistDetailField.Public to it.toString()) }
            }
            if (extra.isNotEmpty()) {
                // `createPlaylist` takes no comment or visibility. A failure here keeps them as a
                // pending header change of the new playlist, sent like any other.
                try {
                    tally.sent += 1
                    reader.sendChecked("updatePlaylist", mapOf("playlistId" to id) + extra.map { (f, v) -> f.wireName to v })
                } catch (thrown: LibraryRequestFailure) {
                    database.transaction { putDetails(id, extra.associate { (f, v) -> f to PendingFieldChange(v, null) }) }
                }
            }
            reader.readPlaylistDetail(id, reader.sessionEpoch)
            false
        }
        if (deletedMeanwhile) return nameInsteadOfDeleting(row.playlistId, sentName, id)
        // Edits made while the create was in flight folded into its row; from here they are the
        // new playlist's own changes, never a second create.
        if (!settleCreate(row.copy(name = sentName), id, sentSongs, inferred)) return nameInsteadOfDeleting(row.playlistId, sentName, id)
        created[row.playlistId] = id
        tally.saved += 1
        reader.rereadList(LibraryQuery.Playlists)
        changed(setOf(row.playlistId, id))
        emit(PlaylistEditOutcome.Created(row.playlistId, id))
        val entries = cachedEntries(id)
        if (entries != null && entries != sentSongs) emit(PlaylistEditOutcome.Diverged(id, PlaylistRowKind.Create, entries, sentSongs))
    }

    /** The songs a create's own request carries: all, or — without `formPost` — those that fit. */
    private fun createSongs(row: PendingPlaylistRow.Create): List<String> =
        batches(listOf("name" to row.name), row.songs) { "songId" to it }.first()

    /**
     * The create [sent] is on the server as [id]. Its row goes; what the person changed since it was
     * sent — a later name, comment, visibility or songs — and the songs its request could not carry
     * become pending changes of [id], delivered like any other. [id] joins the pre-send record of
     * every other create still in doubt: a playlist this device knows one of its creates made is
     * never a candidate for another, after a relaunch too (§18.6). A create waiting on a choice that
     * named [id] loses it from that choice: a choice the person made of [id] is void, and a create
     * left waiting is asked again with the candidates that remain — never adopting one the person
     * passed over — while one left with none looks again. Deleted here meanwhile, the playlist is
     * deleted only when [id] was the server's own answer; one [inferred] is not, and false is
     * returned — nothing settled, the row gone, for the caller to name the playlist to the person.
     */
    private fun settleCreate(sent: PendingPlaylistRow.Create, id: String, createSongs: List<String>, inferred: Boolean): Boolean {
        val askedAgain = mutableListOf<PlaylistEditOutcome.PossibleDuplicate>()
        val settled = database.transactionWithResult {
            val latest = outbox.find(sent.playlistId, PlaylistRowKind.Create) as PendingPlaylistRow.Create? ?: sent
            outbox.remove(sent.playlistId, PlaylistRowKind.Create)
            if (latest.cancelled && inferred) return@transactionWithResult false
            outbox.all().filterIsInstance<PendingPlaylistRow.Create>().forEach { other ->
                val seen = other.seenBeforeSend
                val named = other.candidates
                val offered = named != null && id in named
                if ((seen == null || id in seen) && !offered) return@forEach
                val remaining = named?.filter { it != id }
                val next = other.copy(
                    seenBeforeSend = seen?.let { if (id in it) it else it + id },
                    candidates = if (offered) remaining?.takeIf { it.isNotEmpty() } else named,
                    // A choice of [id] is void; so is any choice once nothing is left to choose from.
                    chosen = other.chosen?.takeIf { it != id && (!offered || !remaining.isNullOrEmpty()) },
                )
                outbox.rewrite(next)
                if (offered && next.awaitsChoice) askedAgain += PlaylistEditOutcome.PossibleDuplicate(next.playlistId, next.sentName ?: next.name, next.candidates!!)
            }
            if (latest.cancelled) {
                outbox.put(PendingPlaylistRow.Delete(id, false, 0, 0))
                return@transactionWithResult true
            }
            val fields = buildMap {
                if (latest.name != sent.name) put(PlaylistDetailField.Name, PendingFieldChange(latest.name, sent.name))
                if (latest.comment != sent.comment) put(PlaylistDetailField.Comment, PendingFieldChange(latest.comment.orEmpty(), sent.comment.orEmpty()))
                latest.isPublic?.takeIf { it != sent.isPublic }?.let { put(PlaylistDetailField.Public, PendingFieldChange(it.toString(), (sent.isPublic == true).toString())) }
            }
            if (fields.isNotEmpty()) putDetails(id, fields)
            // Verified against the list just read back — the songs sent, less any the server dropped.
            if (latest.songs != createSongs) outbox.put(PendingPlaylistRow.Entries(id, cachedEntries(id) ?: createSongs, latest.songs, emptyList(), false, 0, 0))
            true
        }
        askedAgain.forEach { outcome ->
            changed(setOf(outcome.localId))
            offer(outcome)
        }
        return settled
    }

    /** Whether the create [localId] was deleted on this device after its send. */
    private fun deletedHere(localId: String): Boolean =
        (outbox.find(localId, PlaylistRowKind.Create) as PendingPlaylistRow.Create?)?.cancelled == true

    /**
     * The create [localId] was deleted here while the flush found [id] without the server's answer —
     * the lone candidate, the person's choice, or what an empty `ok` left. NOTHING is deleted on
     * inference (§18.6): the playlist is named to the person, who can delete it by its id, and is
     * never a candidate for another create here; the create is gone.
     */
    private suspend fun nameInsteadOfDeleting(localId: String, name: String, id: String) {
        database.transaction { outbox.remove(localId, PlaylistRowKind.Create) }
        changed(setOf(localId))
        offer(PlaylistEditOutcome.PossiblyCreated(localId, name, listOf(id)))
        reader.rereadList(LibraryQuery.Playlists)
    }

    /**
     * Tells the person of playlists that may be what the create [localId] made
     * ([PlaylistEditOutcome.PossiblyCreated]) or may be that create
     * ([PlaylistEditOutcome.PossibleDuplicate]), after [ids] — every one the offer names — join the
     * pre-send record of every other create of this device (§18.6): an id offered for one create is
     * never a candidate for another, whatever its name, so no create adopts a playlist the person
     * may delete, or may yet choose for the create it was offered for.
     */
    private fun offer(outcome: PlaylistEditOutcome.PossiblyCreated) = offer(outcome.localId, outcome.candidates, outcome)

    private fun offer(outcome: PlaylistEditOutcome.PossibleDuplicate) = offer(outcome.localId, outcome.candidates, outcome)

    private fun offer(localId: String, ids: List<String>, outcome: PlaylistEditOutcome) {
        database.transaction {
            outbox.all().filterIsInstance<PendingPlaylistRow.Create>().forEach { other ->
                val seen = other.seenBeforeSend
                // A create never sent records every playlist listed when it is sent: these among them.
                if (other.playlistId == localId || seen == null) return@forEach
                val missing = ids.filter { it !in seen }
                if (missing.isNotEmpty()) outbox.rewrite(other.copy(seenBeforeSend = seen + missing))
            }
        }
        emit(outcome)
    }

    /**
     * Whether a create of this device other than [localId], whose send carried [sentName], is in
     * doubt — sent, its answer lost, and not yet settled, whether it waits for the person or was
     * deleted here: the playlist its send made may be the one the create [localId] finds. A row that
     * never recorded the name it sent is counted as the same name.
     */
    private fun anotherCreateInDoubt(localId: String, sentName: String): Boolean =
        outbox.all().any {
            it is PendingPlaylistRow.Create && it.playlistId != localId && it.attempted &&
                (it.sentName == null || it.sentName == sentName)
        }

    /** Adds [fields] to [id]'s pending header change, keeping any other field already pending. */
    private fun putDetails(id: String, fields: Map<PlaylistDetailField, PendingFieldChange>) {
        val existing = outbox.find(id, PlaylistRowKind.Details) as PendingPlaylistRow.Details?
        outbox.put(PendingPlaylistRow.Details(id, existing?.fields.orEmpty() + fields, 0, 0))
    }

    /** The server's playlists, as a lost create's candidates are looked for among them. */
    private suspend fun listPlaylists(): List<ListedPlaylist> = parseListedPlaylists(reader.sendChecked("getPlaylists").response.body)

    /**
     * After a create whose answer was lost: every playlist that may be what it made (§18.6) — named
     * [sentName], NOT in its pre-send record ([PendingPlaylistRow.Create.seenBeforeSend]: every
     * playlist listed just before the send, every one another create here has made since, and every
     * one since offered to the person for another create), and not mapped to another create of this
     * session. The stated owner rules nothing out: a server may state this account in another form.
     * No clock enters it: neither the server's `created` stamp, nor its timezone, nor the device's
     * clock. A playlist another client makes under that name after the send is a candidate too, and
     * so is what another create's lost send made while that create is in doubt — which is why a
     * candidate is never deleted without the person, and adopted only when it is the only one, no
     * other create here is in doubt, and it is this account's and holds the songs sent.
     */
    private fun lostCreateCandidates(row: PendingPlaylistRow.Create, sentName: String, listing: List<ListedPlaylist>): List<ListedPlaylist> {
        val before = row.seenBeforeSend.orEmpty().toSet()
        val ours = created.values.toSet()
        return listing.filter { listed -> listed.name == sentName && listed.id !in before && listed.id !in ours }
    }

    private sealed interface LostCreate {
        /**
         * Nothing listed is what the send could have made: sent again. Not a proof — a server that
         * commits the send only after this listing ends with two (§18.6).
         */
        data object NotLanded : LostCreate

        /** The person was told the candidates and chooses; nothing is sent meanwhile. */
        data object Choose : LostCreate

        data class Adopt(val id: String) : LostCreate
    }

    /**
     * A create in doubt, decided from its [candidates] and never on a guess (§18.6): the candidate
     * the person chose; else the ONLY candidate when no other create of this device that sent the
     * same name is in doubt, it
     * is this account's (or states no owner) and it holds the songs sent, in order, or some of them —
     * never none ([holdsWhatWasSent]) — adopted; else, with no candidate at all, it is sent again;
     * else the person is told the candidates ([PlaylistEditOutcome.PossibleDuplicate]), none of which
     * is then a candidate for another create, and it waits for their choice. A row whose pre-send
     * list was never recorded adopts nothing on its own.
     */
    private suspend fun resolveLostCreate(
        row: PendingPlaylistRow.Create,
        sentName: String,
        sentSongs: List<String>,
        candidates: List<ListedPlaylist>,
    ): LostCreate {
        row.chosen?.let { chosen -> if (candidates.any { it.id == chosen }) return LostCreate.Adopt(chosen) }
        if (candidates.isEmpty()) return LostCreate.NotLanded
        val only = candidates.singleOrNull()
        // Named, never adopted: while another create of this device that sent the same name is in
        // doubt, since its send may have made this one; and one whose stated owner is not this
        // account — the person says whether it is theirs.
        if (
            only != null && !anotherCreateInDoubt(row.playlistId, sentName) && (only.owner == null || isThisAccount(only.owner)) &&
            row.seenBeforeSend != null && holdsWhatWasSent(only.id, sentSongs)
        ) {
            return LostCreate.Adopt(only.id)
        }
        val ids = candidates.map { it.id }
        rewriteCurrent(row) { it.copy(candidates = ids, chosen = null) }
        changed(setOf(row.playlistId))
        offer(PlaylistEditOutcome.PossibleDuplicate(row.playlistId, sentName, ids))
        return LostCreate.Choose
    }

    /**
     * Whether playlist [id] holds [sentSongs]: exactly, or in the order sent with some songs left out
     * — every copy of each — but never all of them. That admits the ids a server drops because it
     * does not know them, and equally a playlist made with only some of the songs: the difference is
     * told as [PlaylistEditOutcome.Diverged] once adopted.
     */
    private suspend fun holdsWhatWasSent(id: String, sentSongs: List<String>): Boolean {
        val entries = entriesOf(id) ?: return false
        // An empty playlist proves nothing about a create that carried songs.
        return entries == sentSongs || (entries.isNotEmpty() && droppedOnly(entries, sentSongs, introduced = sentSongs.toSet()))
    }

    /** A playlist's entries, read raw (never cached: it may not be the one looked for); null when gone. */
    private suspend fun entriesOf(id: String): List<String>? = try {
        parseReaderPlaylist(reader.sendChecked("getPlaylist", mapOf("id" to id)).response.body, id).second.map { it.rawId }
    } catch (thrown: LibraryRequestFailure) {
        if (thrown.error.isNotFound()) null else throw thrown
    }

    private fun createdPlaylistId(body: String): String? {
        val playlist = parseLibraryEnvelope(body)?.payload?.get("playlist") as? JsonObject ?: return null
        return (playlist["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
    }

    /** Each row as it was before its current send was marked, restored if that send provably never arrived. */
    private val beforeMark = mutableMapOf<String, PendingPlaylistRow>()

    /** Keys of creates deleted here whose tombstone a failed send removed; read once by [flushLocked]. */
    private val tombstonesProvenUnsent = mutableSetOf<String>()

    private val PendingPlaylistRow.key: String get() = "$playlistId|${kind.field}"

    /**
     * Durably rewrites [row] as [transform] makes it BEFORE its write is issued, remembering it as it
     * was; null if a newer change replaced it meanwhile.
     */
    private inline fun <reified R : PendingPlaylistRow> mark(row: R, crossinline transform: (R) -> R): R? = database.transactionWithResult {
        val current = outbox.current(row) as? R ?: return@transactionWithResult null
        beforeMark[current.key] = current
        transform(current).also(outbox::rewrite)
    }

    /** Rewrites [row] in place, only while it still holds that change; null if a newer one replaced it. */
    private inline fun <reified R : PendingPlaylistRow> rewriteCurrent(row: R, crossinline transform: (R) -> R): R? = database.transactionWithResult {
        val current = outbox.current(row) as? R ?: return@transactionWithResult null
        transform(current).also(outbox::rewrite)
    }

    /**
     * One write — on [slot] when the caller holds one. A failure that proves the request never
     * changed the server restores the row as it was before this send was marked, so a later decision
     * is shielded only by sends that may have landed — an earlier one in doubt stays recorded
     * (§18.3, revision 104 item 19).
     */
    private suspend fun writeChecked(
        row: PendingPlaylistRow,
        endpoint: String,
        parameters: List<Pair<String, String>>,
        slot: LibraryReader.HeldSlot? = null,
    ): SentResponse = try {
        slot?.sendRepeatedChecked(endpoint, parameters, formPost) ?: reader.sendRepeatedChecked(endpoint, parameters, formPost)
    } catch (thrown: LibraryRequestFailure) {
        if (thrown.error.provesNotApplied() && unmarkUnapplied(row)) changed(setOf(row.playlistId))
        throw thrown
    } finally {
        beforeMark.remove(row.key)
    }

    /**
     * A send of [marked] provably never arrived: what it marked is taken back from whatever change
     * now holds the key, as favourites do (§18.6 "Failures", sixth review round). Unchanged since,
     * the row is restored as it was. Edited here while the send was out, a header change loses the
     * values this send added to each field's attempted set — never one an earlier send left in doubt
     * — and a create what this send recorded, when no earlier send of it is in doubt; a create
     * deleted here meanwhile is then gone, since the send made nothing. When an earlier send IS in
     * doubt, the create or its tombstone takes back that send's name, songs and pre-send listing, as
     * an unchanged row does, so a late commit of it is still recognised (§18.6, seventh and eighth
     * review rounds). A list change edited while its send was out keeps its mark: the list it was
     * sent onto travels with the edit. Whether the row changed.
     */
    private fun unmarkUnapplied(marked: PendingPlaylistRow): Boolean = database.transactionWithResult {
        val prior = beforeMark[marked.key] ?: return@transactionWithResult false
        val current = outbox.find(marked.playlistId, marked.kind) ?: return@transactionWithResult false
        when {
            current.localSequence == marked.localSequence -> {
                if (prior.localSequence != marked.localSequence) return@transactionWithResult false
                outbox.rewrite(prior)
            }
            current is PendingPlaylistRow.Details && prior is PendingPlaylistRow.Details && marked is PendingPlaylistRow.Details -> {
                val fields = current.fields.mapValues { (field, change) ->
                    val added = marked.fields[field]?.attempted.orEmpty() - prior.fields[field]?.attempted.orEmpty()
                    change.copy(attempted = change.attempted - added)
                }
                if (fields == current.fields) return@transactionWithResult false
                outbox.rewrite(current.copy(fields = fields))
            }
            current is PendingPlaylistRow.Create && prior is PendingPlaylistRow.Create && !prior.attempted -> {
                if (current.cancelled) {
                    outbox.removeIfUnchanged(current)
                    tombstonesProvenUnsent += marked.key
                } else {
                    outbox.rewrite(
                        current.copy(
                            attempted = false, sentName = prior.sentName, sentSongs = prior.sentSongs,
                            seenBeforeSend = prior.seenBeforeSend, candidates = prior.candidates, chosen = prior.chosen,
                        ),
                    )
                }
            }
            current is PendingPlaylistRow.Create && prior is PendingPlaylistRow.Create -> {
                // An earlier send is in doubt, and it is the only one that may have made anything: the
                // create — or its tombstone — is looked for again as THAT send, by the name and songs it
                // carried, and against what was listed before it — the unchanged row's restore above,
                // exactly. Kept as the re-send, a first send committed late would be sought under a
                // later name or songs and the create sent again: a silent duplicate (§18.6). The
                // re-send's listing is NOT kept: the lookup matches by name, so a first-send playlist
                // another client had renamed was listed there unrecognised, and excluding it would
                // send the create again once it is renamed back. Whatever that listing held is at
                // worst named to the person as a candidate: a question, never a duplicate.
                val restored = current.copy(
                    sentName = prior.sentName, sentSongs = prior.sentSongs,
                    seenBeforeSend = prior.seenBeforeSend,
                )
                if (restored == current) return@transactionWithResult false
                outbox.rewrite(restored)
            }
            else -> return@transactionWithResult false
        }
        true
    }

    private fun finish(row: PendingPlaylistRow, outcome: PlaylistEditOutcome, tally: Tally) {
        outbox.removeIfUnchanged(outbox.current(row) ?: row)
        when (outcome) {
            is PlaylistEditOutcome.Diverged -> tally.diverged += 1
            is PlaylistEditOutcome.ChangedElsewhere -> tally.changedElsewhere += 1
            is PlaylistEditOutcome.NotSaved -> tally.refused += 1
            else -> tally.saved += 1
        }
        changed(setOf(row.playlistId))
        emit(outcome)
    }

    /**
     * [items] as requests of [fixed] plus as many items as fit [QUERY_BUDGET_BYTES] of query-string
     * parameters, in order; one request when the server takes a form body (§18.6).
     */
    private fun <T> batches(fixed: List<Pair<String, String>>, items: List<T>, parameter: (T) -> Pair<String, String>): List<List<T>> {
        if (formPost || items.isEmpty()) return listOf(items)
        val batches = mutableListOf<List<T>>()
        var batch = mutableListOf<T>()
        var size = encodedLength(fixed)
        for (item in items) {
            val length = encodedLength(listOf(parameter(item)))
            if (batch.isNotEmpty() && size + length > QUERY_BUDGET_BYTES) {
                batches += batch
                batch = mutableListOf()
                size = encodedLength(fixed)
            }
            batch += item
            size += length
        }
        batches += batch
        return batches
    }

    private fun PendingPlaylistRow.withFailures(failures: Int): PendingPlaylistRow = when (this) {
        is PendingPlaylistRow.Create -> copy(failures = failures)
        is PendingPlaylistRow.Details -> copy(failures = failures)
        is PendingPlaylistRow.Entries -> copy(failures = failures)
        is PendingPlaylistRow.Delete -> copy(failures = failures)
    }

    private fun emit(outcome: PlaylistEditOutcome) = outcomeListeners.toList().forEach { listener -> guarded(Unit) { listener(outcome) } }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        fallback
    }

    companion object {
        /** Per-change failures in a row before a change is dropped and the person told (ASSUMED, as §18.3). */
        const val MAX_FAILURES = 3

        /**
         * Parameter bytes one query-string request may carry without `formPost` (§18.6). ASSUMED: a
         * proxy in front of the server accepts a request line of 8 KiB — a common default — and this
         * leaves about 1 KiB of it for the address, the path and the credentials.
         */
        const val QUERY_BUDGET_BYTES = 7_000
    }
}

private fun List<String>.endsWith(suffix: List<String>): Boolean =
    size >= suffix.size && subList(size - suffix.size, size) == suffix

private fun List<String>.startsWith(prefix: List<String>): Boolean =
    size >= prefix.size && subList(0, prefix.size) == prefix

/**
 * Whether [server] is [sent] with EVERY occurrence of some of the ids in [introduced] missing — what
 * a server that silently drops a song id it does not know (OBSERVED, §18.6) makes of [sent]. Only
 * ids this device added can be unknown to the server; equal lists count.
 */
internal fun droppedOnly(server: List<String>, sent: List<String>, introduced: Set<String>): Boolean {
    val missing = sent.toSet() - server.toSet()
    return missing.all { it in introduced } && sent.filter { it !in missing } == server
}

/**
 * The positions of [base] a change to [target] removes, when [target] is [base] with entries
 * removed and nothing else; null otherwise. Duplicates are matched earliest first: which of two
 * equal entries goes does not change the list that results.
 */
internal fun removedPositions(base: List<String>, target: List<String>): List<Int>? {
    val removed = mutableListOf<Int>()
    var next = 0
    base.forEachIndexed { index, id -> if (next < target.size && target[next] == id) next += 1 else removed += index }
    return if (next == target.size) removed else null
}

/** The bytes [parameters] take in a query string, percent-encoded as UTF-8, with their separators. */
internal fun encodedLength(parameters: List<Pair<String, String>>): Int =
    parameters.sumOf { (name, value) -> percentEncodedLength(name) + percentEncodedLength(value) + 2 }

private fun percentEncodedLength(text: String): Int = text.encodeToByteArray().sumOf { byte ->
    val c = byte.toInt() and 0xff
    val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
        c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
    if (unreserved) 1 else 3
}

/** One row of `getPlaylists` with the fields a lost create's candidates are judged by (§18.6). */
internal data class ListedPlaylist(val id: String, val name: String, val owner: String?)

internal fun parseListedPlaylists(body: String): List<ListedPlaylist> =
    parseReaderPlaylists(body).map { ListedPlaylist(it.rawId, it.name, it.owner) }

private enum class PlaylistFailureClass { Refused, ThisChange, Held, Transport }

private fun DomainError.failureClass(): PlaylistFailureClass = when (this) {
    // Code 0 is the server's generic error — the reference server uses it for a busy limit.
    is DomainError.Server.Known -> if (code == 0) PlaylistFailureClass.ThisChange else PlaylistFailureClass.Refused
    is DomainError.Server.Unknown -> if (code == 0) PlaylistFailureClass.ThisChange else PlaylistFailureClass.Refused
    // A rate limit holds every change until its Retry-After, and counts toward nothing.
    is DomainError.Server.Busy -> PlaylistFailureClass.Held
    // An HTTP status with no envelope: a gateway that cannot reach the server stops the flush like
    // no answer at all; a request too large never fits, so it is refused and told; anything else is
    // this change's failure. A refusal of access (401, 403, 407) never reaches here from a change:
    // the flush sends a ping first and decides there (see [flushLocked]).
    is DomainError.Server.HttpStatus -> when {
        gatewayCannotReachServer -> PlaylistFailureClass.Transport
        tooLarge -> PlaylistFailureClass.Refused
        else -> PlaylistFailureClass.ThisChange
    }
    // Code 50 in an envelope: this user may not edit this playlist.
    DomainError.Auth.Forbidden -> PlaylistFailureClass.Refused
    // Credentials refused — reached only as the ping's own failure, since the flush pings first:
    // every change is held for the person to sign in again.
    is DomainError.Auth -> PlaylistFailureClass.Held
    is DomainError.Protocol -> PlaylistFailureClass.ThisChange
    else -> PlaylistFailureClass.Transport
}

/** Whether a failure proves the request never changed the server. */
private fun DomainError.provesNotApplied(): Boolean = when (this) {
    DomainError.Transport.Unreachable -> true
    is DomainError.Security -> true
    is DomainError.Auth -> true
    is DomainError.Server.HttpStatus -> provesNotApplied
    is DomainError.Server -> true
    else -> false
}
