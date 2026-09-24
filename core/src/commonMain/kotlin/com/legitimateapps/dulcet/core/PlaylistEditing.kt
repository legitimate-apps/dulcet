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
 *    ([PlaylistEditOutcome.Diverged]) with the server's entries.
 *
 * **What remains, stated honestly.** Subsonic has no conditional write, so one round trip remains
 * between the re-read and the write in which another client's change can land:
 * - under a **removal**, a change that moves entries (another removal or an insert before the
 *   chosen position) makes the position name a different song, which is removed instead. The
 *   read-back always sees it and the person is told, with the server's list. A change that only
 *   appends is kept, and told.
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
 * append in doubt carried travel with its row through later edits. A create whose answer was
 * lost is adopted only on proof (below), so a lost answer may cost a duplicate playlist but never a
 * lost one.
 *
 * **A lost create is identified by proof, never by resemblance.** A playlist is taken to be this
 * device's lost create only when it is the ONE playlist that is owned by this account, has the
 * name that send carried, has a server `created` time at or after the create was first sent, and
 * holds the songs sent — exactly, before it is deleted (a create deleted here while its send was in
 * doubt), or less song ids the server did not know, before it is adopted. What was sent is recorded
 * with the send, so a rename or song change made here meanwhile neither hides the create nor is
 * lost: it follows the adopted playlist as its own change. Without proof nothing is deleted:
 * the person is told a playlist of that name may have been created
 * ([PlaylistEditOutcome.PossiblyCreated]). ASSUMED: the server's and the device's clocks agree; a
 * server clock behind the device's makes a lost create unrecognisable (sent again, never deleted
 * wrongly), and one ahead of it admits a playlist created up to that skew before the send.
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
 * that override. The shells show such a playlist's owner and present it read-only.
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
         * The device's wall clock when the create was FIRST sent, kept across retries: a playlist
         * the server says it created earlier cannot be this one (the identity proof, §18.6).
         */
        val attemptedAt: Long? = null,
        /**
         * The name and songs the latest send carried. A lost create is looked for as THAT playlist,
         * even after the person renamed it or changed its songs here; those edits then follow it.
         */
        val sentName: String? = null,
        val sentSongs: List<String>? = null,
    ) : PendingPlaylistRow {
        override val kind: PlaylistRowKind get() = PlaylistRowKind.Create
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
     * unseen (§18.6). [serverEntries] is the server's list, which the person now sees; null for a
     * header change.
     */
    data class Diverged(override val playlistId: String, val kind: PlaylistRowKind, val serverEntries: List<String>?) : PlaylistEditOutcome

    /** A header field another client changed after this device last saw it: the server wins (§18.3). */
    data class Superseded(override val playlistId: String, val fields: Set<PlaylistDetailField>) : PlaylistEditOutcome

    data class NotRecorded(override val playlistId: String) : PlaylistEditOutcome

    /**
     * A create whose answer was lost was then deleted here, and the server holds a playlist of that
     * name owned by this account that may be it but is not PROVEN to be (§18.6). Nothing was
     * deleted, and the change is gone from the outbox: "A playlist named [name] may have been
     * created; check it and delete it if you don't want it."
     */
    data class PossiblyCreated(val localId: String, val name: String) : PlaylistEditOutcome {
        override val playlistId: String get() = localId
    }
}

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
                    row.attemptedAt?.let { put("attemptedAt", JsonPrimitive(it)) }
                    row.sentName?.let { put("sentName", JsonPrimitive(it)) }
                    row.sentSongs?.let { put("sentSongs", it.json()) }
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
                    localSequence, wallClock, failures, number("attemptedAt"), text("sentName"), json["sentSongs"].strings(),
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
                            sent = existing.sent?.takeIf { existing.sentAdded != null && it == base + existing.sentAdded }
                        } else {
                            base = existing?.base ?: cachedEntries!!
                            sent = existing?.sent
                        }
                        // The songs an append in doubt carried travel with its list: an edit that
                        // still only adds at the end must not send them a second time.
                        val sentAdded = existing?.sentAdded?.takeIf { sent != null }
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
        null -> record.owner != null && record.owner == username
    }

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
     * Sends every pending playlist change, oldest first. A refused change is dropped and told; one
     * the server answered for without applying stays pending and the flush moves on, until
     * [MAX_FAILURES]; a server that cannot be reached stops the flush with every change kept. A
     * failure of the device's own database stops it too — reported
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
            val row = outbox.all().firstOrNull { it.key !in tally.deferred } ?: break
            val failure = try {
                deliver(row, tally)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                thrown.asReaderError()
            }
            if (failure == null) continue
            when (failure.failureClass()) {
                PlaylistFailureClass.Refused -> {
                    outbox.removeIfUnchanged(outbox.current(row) ?: row)
                    if (failure.isNotFound() && row !is PendingPlaylistRow.Create) cache.markPlaylistNotFound(cache.issue(), row.playlistId)
                    tally.refused += 1
                    changed(setOf(row.playlistId))
                    emit(PlaylistEditOutcome.NotSaved(row.playlistId, row.kind, failure))
                }
                PlaylistFailureClass.ThisChange -> {
                    val current = outbox.current(row)
                    val failures = (current?.failures ?: row.failures) + 1
                    if (current != null && failures >= MAX_FAILURES) {
                        outbox.removeIfUnchanged(current)
                        tally.refused += 1
                        changed(setOf(row.playlistId))
                        emit(PlaylistEditOutcome.NotSaved(row.playlistId, row.kind, failure))
                    } else {
                        current?.let { outbox.rewrite(it.withFailures(failures)) }
                        tally.deferred += row.key
                    }
                }
                PlaylistFailureClass.Transport -> {
                    tally.stoppedBy = failure
                    break
                }
            }
        }
        return PlaylistFlushReport(
            tally.sent, tally.saved, tally.refused, tally.changedElsewhere, tally.diverged, tally.deferred.size,
            tally.stoppedBy, outbox.all().size,
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
        // An append in doubt that landed, then edited here among its own songs: against the list
        // the server now holds, what remains is positional, not an append.
        val landedThenEdited = row.attempted && sent != null && server == sent && target != null && !target.startsWith(sent)
        if (row.appendOnly && !landedThenEdited) return sendAppend(row, server, send)
        val rowBase = row.base!!
        var base = rowBase
        if (row.attempted && sent != null) {
            if (server == sent) {
                // This device's own write in doubt landed: the list is verified as that one.
                base = sent
            } else if (server != rowBase && droppedOnly(server, sent, introduced = sent.toSet() - rowBase.toSet())) {
                // (A server still holding the list the write was sent onto holds a write that
                // never arrived, which is sent again — never one whose every new id was dropped.)
                // It landed, less song ids this device added that the server does not know.
                if (target == sent) return EntriesEnd.Finish(row, PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, server))
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
        for (batch in batches(listOf("playlistId" to row.playlistId), removed.sortedDescending()) { "songIndexToRemove" to it.toString() }) {
            val positions = batch.toSet()
            val after = expected.filterIndexed { index, _ -> index !in positions }
            current = mark(current) { it.copy(attempted = true, sent = after) } ?: return EntriesEnd.Replaced
            send.tally.sent += 1
            writeChecked(current, "updatePlaylist", listOf("playlistId" to row.playlistId) + batch.map { "songIndexToRemove" to it.toString() }, send.slot)
            val readBack = send.read(row.playlistId).ids
            // Kept only if exactly the chosen entries went missing and nothing else changed.
            if (readBack != after) return EntriesEnd.Finish(current, PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, readBack))
            expected = readBack
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
            after.ids != target -> PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, after.ids)
            after.playlist.header() != before.playlist.header() -> PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, after.ids)
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
        if (row.attempted && inDoubt != null && sent != null && row.added.drop(appended).take(inDoubt.size) == inDoubt) {
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
            val outcome = if (dropped) PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, server) else PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries)
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
            else PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, readBack)
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
            else PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Details, null),
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
        if (row.cancelled) {
            // Deleted here after a send whose answer was lost. What that send made is deleted only
            // on PROOF that it made it; short of proof nothing is deleted and the person is told.
            if (row.attempted) {
                when (val found = findLostCreate(row, earlierName, earlierSongs, forDelete = true)) {
                    is LostCreate.Proven -> {
                        tally.sent += 1
                        try {
                            writeChecked(row, "deletePlaylist", listOf("id" to found.id))
                        } catch (thrown: LibraryRequestFailure) {
                            if (!thrown.error.isNotFound()) throw thrown
                        }
                    }
                    LostCreate.Unproven -> emit(PlaylistEditOutcome.PossiblyCreated(row.playlistId, earlierName))
                    LostCreate.None -> Unit
                }
            }
            outbox.removeIfUnchanged(outbox.current(row) ?: row)
            changed(setOf(row.playlistId))
            reader.rereadList(LibraryQuery.Playlists)
            return
        }
        val adopted = if (row.attempted) (findLostCreate(row, earlierName, earlierSongs, forDelete = false) as? LostCreate.Proven)?.id else null
        val id: String
        val sentName: String
        val sentSongs: List<String>
        if (adopted != null) {
            id = adopted
            sentName = earlierName
            sentSongs = earlierSongs
        } else {
            val songs = createSongs(row)
            val attempted = mark(row) { it.copy(attempted = true, attemptedAt = it.attemptedAt ?: cache.now(), sentName = it.name, sentSongs = songs) } ?: return
            tally.sent += 1
            val answer = writeChecked(attempted, "createPlaylist", listOf("name" to attempted.name) + songs.map { "songId" to it })
            // OpenSubsonic answers with the playlist; a server that answers with an empty `ok` is
            // looked up by the same proof a lost answer needs.
            id = createdPlaylistId(answer.response.body)
                ?: (findLostCreate(attempted, attempted.name, songs, forDelete = false) as? LostCreate.Proven)?.id
                ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
            sentName = attempted.name
            sentSongs = songs
        }
        reader.listLock(playlistDetailListKey(id)).withLock {
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    database.transaction { putDetails(id, extra.associate { (f, v) -> f to PendingFieldChange(v, null) }) }
                }
            }
            reader.readPlaylistDetail(id, reader.sessionEpoch)
        }
        // Edits made while the create was in flight folded into its row; from here they are the
        // new playlist's own changes, never a second create.
        created[row.playlistId] = id
        settleCreate(row.copy(name = sentName), id, sentSongs)
        tally.saved += 1
        reader.rereadList(LibraryQuery.Playlists)
        changed(setOf(row.playlistId, id))
        emit(PlaylistEditOutcome.Created(row.playlistId, id))
        val entries = cachedEntries(id)
        if (entries != null && entries != sentSongs) emit(PlaylistEditOutcome.Diverged(id, PlaylistRowKind.Create, entries))
    }

    /** The songs a create's own request carries: all, or — without `formPost` — those that fit. */
    private fun createSongs(row: PendingPlaylistRow.Create): List<String> =
        batches(listOf("name" to row.name), row.songs) { "songId" to it }.first()

    /**
     * The create [sent] is on the server as [id]. Its row goes; what the person changed since it was
     * sent — a later name, comment, visibility or songs, or a delete — and the songs its request
     * could not carry become pending changes of [id], delivered like any other.
     */
    private fun settleCreate(sent: PendingPlaylistRow.Create, id: String, createSongs: List<String>) = database.transaction {
        val latest = outbox.find(sent.playlistId, PlaylistRowKind.Create) as PendingPlaylistRow.Create? ?: sent
        outbox.remove(sent.playlistId, PlaylistRowKind.Create)
        if (latest.cancelled) {
            outbox.put(PendingPlaylistRow.Delete(id, false, 0, 0))
            return@transaction
        }
        val fields = buildMap {
            if (latest.name != sent.name) put(PlaylistDetailField.Name, PendingFieldChange(latest.name, sent.name))
            if (latest.comment != sent.comment) put(PlaylistDetailField.Comment, PendingFieldChange(latest.comment.orEmpty(), sent.comment.orEmpty()))
            latest.isPublic?.takeIf { it != sent.isPublic }?.let { put(PlaylistDetailField.Public, PendingFieldChange(it.toString(), (sent.isPublic == true).toString())) }
        }
        if (fields.isNotEmpty()) putDetails(id, fields)
        // Verified against the list just read back — the songs sent, less any the server dropped.
        if (latest.songs != createSongs) outbox.put(PendingPlaylistRow.Entries(id, cachedEntries(id) ?: createSongs, latest.songs, emptyList(), false, 0, 0))
    }

    /** Adds [fields] to [id]'s pending header change, keeping any other field already pending. */
    private fun putDetails(id: String, fields: Map<PlaylistDetailField, PendingFieldChange>) {
        val existing = outbox.find(id, PlaylistRowKind.Details) as PendingPlaylistRow.Details?
        outbox.put(PendingPlaylistRow.Details(id, existing?.fields.orEmpty() + fields, 0, 0))
    }

    private sealed interface LostCreate {
        data class Proven(val id: String) : LostCreate

        /** A playlist of that name that may be this account's exists, but nothing proves it is this one. */
        data object Unproven : LostCreate

        data object None : LostCreate
    }

    /**
     * After a create whose answer was lost: the playlist it made, on PROOF (§18.6) — the ONE playlist
     * owned by this account, named [sentName], with a server `created` time at or after the create
     * was first sent, holding [sentSongs]: exactly, [forDelete]; otherwise less song ids the server
     * did not know. Nothing about what the device has seen enters into it.
     */
    private suspend fun findLostCreate(row: PendingPlaylistRow.Create, sentName: String, sentSongs: List<String>, forDelete: Boolean): LostCreate {
        val named = parseListedPlaylists(reader.sendChecked("getPlaylists").response.body)
            .filter { it.name == sentName && (it.owner == null || it.owner == username) }
        if (named.isEmpty()) return LostCreate.None
        val since = row.attemptedAt ?: return LostCreate.Unproven
        val proven = named
            .filter { it.owner != null && it.owner == username && it.createdAt != null && it.createdAt >= since }
            .filter { candidate ->
                val entries = entriesOf(candidate.id) ?: return@filter false
                // Adopting allows ids the server dropped, but not all of them: an empty playlist
                // proves nothing about a create that carried songs.
                if (forDelete) entries == sentSongs else entries == sentSongs || (entries.isNotEmpty() && droppedOnly(entries, sentSongs, introduced = sentSongs.toSet()))
            }
        return proven.singleOrNull()?.let { LostCreate.Proven(it.id) } ?: LostCreate.Unproven
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
     * (§18.3, revision 99 item 19).
     */
    private suspend fun writeChecked(
        row: PendingPlaylistRow,
        endpoint: String,
        parameters: List<Pair<String, String>>,
        slot: LibraryReader.HeldSlot? = null,
    ): SentResponse = try {
        slot?.sendRepeatedChecked(endpoint, parameters, formPost) ?: reader.sendRepeatedChecked(endpoint, parameters, formPost)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (thrown: Throwable) {
        val error = thrown.asReaderError()
        if (error.provesNotApplied()) {
            val prior = beforeMark[row.key]?.takeIf { it.localSequence == row.localSequence }
            if (prior != null && outbox.current(row) != null) outbox.rewrite(prior)
        }
        throw LibraryRequestFailure(error)
    } finally {
        beforeMark.remove(row.key)
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

/** One row of `getPlaylists` with the fields a lost create's proof needs. */
internal data class ListedPlaylist(val id: String, val name: String, val owner: String?, val createdAt: Long?)

internal fun parseListedPlaylists(body: String): List<ListedPlaylist> {
    val records = parseReaderPlaylists(body)
    val created = ((parseLibraryEnvelope(body)?.payload?.get("playlists") as? JsonObject)?.get("playlist") as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val playlist = element as? JsonObject ?: return@mapNotNull null
            val id = (playlist["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            id to (playlist["created"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::epochMillisOrNull)
        }
        .toMap()
    return records.map { ListedPlaylist(it.rawId, it.name, it.owner, created[it.rawId]) }
}

/** An ISO-8601 instant as epoch milliseconds; null when it is not one. */
private fun epochMillisOrNull(text: String): Long? = try {
    kotlin.time.Instant.parse(text).toEpochMilliseconds()
} catch (_: IllegalArgumentException) {
    null
}

private enum class PlaylistFailureClass { Refused, ThisChange, Transport }

private fun DomainError.failureClass(): PlaylistFailureClass = when (this) {
    // Code 0 is the server's generic error — the reference server uses it for a busy limit.
    is DomainError.Server.Known -> if (code == 0) PlaylistFailureClass.ThisChange else PlaylistFailureClass.Refused
    is DomainError.Server.Unknown -> if (code == 0) PlaylistFailureClass.ThisChange else PlaylistFailureClass.Refused
    is DomainError.Server.Busy -> PlaylistFailureClass.ThisChange
    // An HTTP status with no envelope: a gateway that cannot reach the server stops the flush like
    // no answer at all; a request too large never fits, so it is refused and told; anything else
    // is this change's failure.
    is DomainError.Server.HttpStatus -> when {
        gatewayCannotReachServer -> PlaylistFailureClass.Transport
        tooLarge -> PlaylistFailureClass.Refused
        else -> PlaylistFailureClass.ThisChange
    }
    // Code 50: this user may not edit this playlist.
    DomainError.Auth.Forbidden -> PlaylistFailureClass.Refused
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
