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
 * **The strategy, and why it has the smallest risk of removing the wrong song.** No positional
 * edit is ever sent as a position:
 *
 * 1. Every entry edit is recorded against the **view** it was made on: the shell passes the entry
 *    ids it showed, and a view that is not the one the core now publishes is refused
 *    ([PlaylistEditRecord.StaleView]) — the tap was aimed at a list that has since moved.
 * 2. Entry edits of one playlist compact into ONE outbox row: the server's entries when the first
 *    edit was made (`base`) and the entries the person now wants (`target`).
 * 3. Sending re-reads the playlist first. A change that only adds songs at the end is sent as an
 *    append (`songIdToAdd`), which is not positional, whatever the server now holds. Any other
 *    change is written only when the server still holds exactly `base` — as the WHOLE desired list
 *    (`createPlaylist` with `playlistId`, which OBSERVED keeps the id, name, comment and
 *    visibility). A server holding anything else refuses the edit locally
 *    ([PlaylistEditOutcome.ChangedElsewhere]): the person is told and sees the server's list.
 *    Emptying a playlist is the one positional write, because a replace with no songs is OBSERVED
 *    to be ignored; it removes EVERY index of the verified list.
 * 4. After every write the playlist is read back and compared with what was meant
 *    ([PlaylistEditOutcome.Diverged] when they differ).
 *
 * **What remains, stated honestly.** Subsonic has no conditional write, so a change another
 * client makes in the moment between step 3's read and its write is overwritten by the whole-list
 * write — that client's edit is lost, and only a read-back that differs can reveal it. That is the
 * residual exposure, and it is chosen over the alternative's: a positional removal racing the same
 * moment deletes a song the person SAW and meant to keep. A full list never removes a song the
 * person's view held.
 *
 * **Offline.** Every edit queues, including positional ones, because the base it was made on
 * travels with it: replayed on reconnect, it is written only if the server still holds that base,
 * and refused with words otherwise. An append made on a playlist whose entries were never read
 * has no base and needs none — appends are not positional — so it is sent as an append.
 *
 * **At least once.** A write whose answer was lost is recorded as attempted — with the whole list
 * it sent — and retried; before the retry, the re-read shows whether it landed: the server holds
 * `target`, or the list sent (this device's own write, even after a later edit changed `target`), or
 * — for an append — ends with the songs appended (ASSUMED to be this device's). A create whose answer
 * was lost is looked for before it is sent again (ASSUMED: a playlist this account owns, of the same
 * name and song count, that the device has never seen, is that create); an ambiguous search sends
 * again, so a lost answer may cost a duplicate playlist but never a lost one.
 *
 * **Permissions (§10.4).** An edit needs the server's `readonly: false`, or — for a server that
 * does not say — ownership. OBSERVED on the reference server: another user's public playlist is
 * `readonly: true` and every edit of it answers code 50; another user's private playlist is code 70
 * to a non-admin; an ADMIN sees every user's playlists, `readonly: true` for those of others, and
 * the server would let the admin edit them anyway. Dulcet follows `readonly` and does not offer
 * that override.
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
         * The whole list a write whose answer was lost sent. A server holding it holds this
         * device's own write, not another client's — even after a later edit changed [target].
         */
        val sent: List<String>? = null,
    ) : PendingPlaylistRow {
        override val kind: PlaylistRowKind get() = PlaylistRowKind.Entries

        init {
            require((base == null) == (target == null))
            require(base != null || suffix.isNotEmpty())
            require(base == null || suffix.isEmpty())
        }

        /** Only additions at the end: sent as an append, which no concurrent edit can misplace. */
        val appendOnly: Boolean
            get() = base == null || (target!!.size > base.size && target.subList(0, base.size) == base)
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
     * The write was answered `ok`, but the read-back differs from what was meant: another client
     * wrote in the moment between, or the server dropped a song id it does not know.
     */
    data class Diverged(override val playlistId: String, val kind: PlaylistRowKind, val serverEntries: List<String>?) : PlaylistEditOutcome

    /** A header field another client changed after this device last saw it: the server wins (§18.3). */
    data class Superseded(override val playlistId: String, val fields: Set<PlaylistDetailField>) : PlaylistEditOutcome

    data class NotRecorded(override val playlistId: String) : PlaylistEditOutcome
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
        fun bool(name: String) = (json[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        fun text(name: String) = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return try {
            when (kind) {
                PlaylistRowKind.Create -> PendingPlaylistRow.Create(
                    targetId, text("name") ?: return null, text("comment"), bool("public"),
                    json["songs"].strings() ?: return null, bool("attempted") ?: false, bool("cancelled") ?: false,
                    localSequence, wallClock, failures,
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
 * [formPost]: the server advertises the OpenSubsonic `formPost` extension, so whole-list writes
 * travel as a form body and never meet a proxy's URL-length limit. Without it they are query
 * strings — about 30 bytes per song — which a proxy with a small URL limit may refuse; that refusal
 * is told like any other.
 */
internal class PlaylistEditor(
    private val database: DulcetDatabase,
    private val readerProvider: () -> LibraryReader,
    private val formPost: Boolean = false,
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
                        // A positional edit always carries the base it was made on: the pending
                        // base, else the server's entries as cached (which an append-only row's
                        // suffix was made on top of).
                        val base = existing?.base ?: cachedEntries!!
                        val attempted = existing?.attempted ?: false
                        if (next == base && !attempted) {
                            outbox.remove(target.id, PlaylistRowKind.Entries)
                            PlaylistEditRecord.CompactedAway
                        } else {
                            // An in-doubt send's list travels on: the server holding it is this device's own write.
                            outbox.put(PendingPlaylistRow.Entries(target.id, base, next, emptyList(), attempted, 0, 0, sent = existing?.sent))
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
     * [MAX_FAILURES]; a server that cannot be reached stops the flush with every change kept.
     */
    suspend fun flush(): PlaylistFlushReport {
        reader.checkConfined()
        return lock.withLock {
            flushing = true
            try {
                flushLocked()
            } finally {
                flushing = false
            }
        }
    }

    private suspend fun flushLocked(): PlaylistFlushReport {
        run {
            val tally = Tally()
            val deferred = mutableSetOf<String>()
            while (reader.online) {
                val row = outbox.all().firstOrNull { "${it.playlistId}|${it.kind.field}" !in deferred } ?: break
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
                            deferred += "${row.playlistId}|${row.kind.field}"
                        }
                    }
                    PlaylistFailureClass.Transport -> {
                        tally.stoppedBy = failure
                        break
                    }
                }
            }
            return PlaylistFlushReport(
                tally.sent, tally.saved, tally.refused, tally.changedElsewhere, tally.diverged, deferred.size,
                tally.stoppedBy, outbox.all().size,
            )
        }
    }

    private class Tally {
        var sent = 0
        var saved = 0
        var refused = 0
        var changedElsewhere = 0
        var diverged = 0
        var stoppedBy: DomainError? = null
    }

    /** Delivers one row; a failure it cannot conclude itself is thrown for [flush] to classify. */
    private suspend fun deliver(row: PendingPlaylistRow, tally: Tally) {
        when (row) {
            is PendingPlaylistRow.Create -> deliverCreate(row, tally)
            is PendingPlaylistRow.Delete -> deliverDelete(row, tally)
            is PendingPlaylistRow.Details -> reader.listLock(playlistDetailListKey(row.playlistId)).withLock { deliverDetails(row, tally) }
            is PendingPlaylistRow.Entries -> reader.listLock(playlistDetailListKey(row.playlistId)).withLock { deliverEntries(row, tally) }
        }
    }

    private suspend fun deliverEntries(row: PendingPlaylistRow.Entries, tally: Tally) {
        // Step 3 of the class comment: the server's entries NOW, read through to the cache.
        val server = reader.readPlaylistDetail(row.playlistId, reader.ensureEpoch()).map { it.rawId }
        val expected: List<String>
        val target = row.target
        // This device's own in-doubt write, landed: the list it verified against is now that one.
        val base = if (row.sent != null && server == row.sent) row.sent else row.base
        when {
            target != null && server == target -> {
                // Already there: an earlier send whose answer was lost, or the same edit elsewhere.
                finish(row, PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries), tally)
                return
            }
            row.appendOnly -> {
                // Additions at the end only: an append, which no concurrent edit can misplace, so
                // it is sent whatever the server now holds.
                val suffix = if (row.base != null) target!!.subList(row.base.size, target.size) else row.suffix
                // An append in doubt appended a PREFIX of today's suffix (later appends extend it).
                // ASSUMED, and stated: a server list ending with that prefix ends with this
                // device's own songs, so only the rest is sent.
                val landed = if (row.attempted) (suffix.size downTo 1).firstOrNull { server.endsWith(suffix.subList(0, it)) } ?: 0 else 0
                if (landed == suffix.size) {
                    finish(row, PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries), tally)
                    return
                }
                val rest = suffix.subList(landed, suffix.size)
                val attempted = markAttempted(row) ?: return
                tally.sent += 1
                appendSongs(attempted, rest)
                expected = server + rest
            }
            server == base -> {
                val attempted = markAttempted(row) ?: return
                tally.sent += 1
                if (target!!.isNotEmpty()) {
                    replaceSongs(attempted, target)
                } else {
                    // A replace with no songs is ignored (OBSERVED), so emptying removes every
                    // index of the list just verified — the one positional write.
                    writeChecked(attempted, "updatePlaylist", listOf("playlistId" to row.playlistId) + base.indices.map { "songIndexToRemove" to it.toString() })
                }
                expected = target
            }
            else -> {
                // The stale-index hazard, prevented: the list moved since the edit was made.
                outbox.removeIfUnchanged(outbox.current(row) ?: row)
                tally.changedElsewhere += 1
                changed(setOf(row.playlistId))
                emit(PlaylistEditOutcome.ChangedElsewhere(row.playlistId, server))
                return
            }
        }
        val readBack = reader.readPlaylistDetail(row.playlistId, reader.ensureEpoch()).map { it.rawId }
        finish(
            row,
            if (readBack == expected) PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Entries)
            else PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Entries, readBack),
            tally,
        )
    }

    private suspend fun appendSongs(row: PendingPlaylistRow, songs: List<String>) =
        writeChecked(row, "updatePlaylist", listOf("playlistId" to row.playlistId) + songs.map { "songIdToAdd" to it })

    /** The whole list, in order: `createPlaylist` with `playlistId` keeps the playlist (OBSERVED). */
    private suspend fun replaceSongs(row: PendingPlaylistRow, songs: List<String>) =
        writeChecked(row, "createPlaylist", listOf("playlistId" to row.playlistId) + songs.map { "songId" to it })

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
        val attempted = row.copy(
            fields = row.fields.mapValues { (field, change) -> if (field in toSend) change.copy(attempted = change.attempted + change.value) else change },
        )
        val current = outbox.current(row) ?: return
        beforeMark[current.key] = current
        outbox.rewrite(attempted)
        tally.sent += 1
        writeChecked(attempted, "updatePlaylist", listOf("playlistId" to row.playlistId) + toSend.map { (field, change) -> field.wireName to change.value })
        reader.readPlaylistDetail(row.playlistId, reader.ensureEpoch())
        val readBack = serverDetails(row.playlistId)
        finish(
            attempted,
            if (toSend.all { (field, change) -> readBack[field] == change.value }) PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Details)
            else PlaylistEditOutcome.Diverged(row.playlistId, PlaylistRowKind.Details, null),
            tally,
        )
    }

    private suspend fun deliverDelete(row: PendingPlaylistRow.Delete, tally: Tally) {
        val attempted = markAttempted(row) ?: return
        tally.sent += 1
        val gone = try {
            writeChecked(attempted, "deletePlaylist", listOf("id" to row.playlistId))
            true
        } catch (thrown: LibraryRequestFailure) {
            // Code 70 on a delete: already gone — possibly this device's own earlier send.
            if (thrown.error.isNotFound()) true else throw thrown
        }
        if (gone) cache.markPlaylistNotFound(cache.issue(), row.playlistId)
        finish(attempted, PlaylistEditOutcome.Saved(row.playlistId, PlaylistRowKind.Delete), tally)
        reader.rereadList(LibraryQuery.Playlists)
    }

    private suspend fun deliverCreate(row: PendingPlaylistRow.Create, tally: Tally) {
        var serverId: String? = if (row.attempted) findLostCreate(row) else null
        if (row.cancelled) {
            // Deleted here after a send whose answer was lost: delete what that send made, if anything.
            serverId?.let { id ->
                tally.sent += 1
                writeChecked(row, "deletePlaylist", listOf("id" to id))
            }
            outbox.removeIfUnchanged(row)
            reader.rereadList(LibraryQuery.Playlists)
            return
        }
        if (serverId == null) {
            val attempted = markAttempted(row) as PendingPlaylistRow.Create? ?: return
            tally.sent += 1
            val sent = writeChecked(attempted, "createPlaylist", listOf("name" to row.name) + row.songs.map { "songId" to it })
            // OpenSubsonic answers with the playlist; a server that answers with an empty `ok` is
            // looked up by the same search a lost answer uses.
            serverId = createdPlaylistId(sent.response.body) ?: findLostCreate(row)
                ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
        }
        val id = serverId
        created[row.playlistId] = id
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
                    database.transaction {
                        outbox.put(PendingPlaylistRow.Details(id, extra.associate { (f, v) -> f to PendingFieldChange(v, null) }, 0, 0))
                    }
                }
            }
            reader.readPlaylistDetail(id, reader.ensureEpoch())
        }
        outbox.removeIfUnchanged(outbox.current(row) ?: row)
        tally.saved += 1
        reader.rereadList(LibraryQuery.Playlists)
        changed(setOf(row.playlistId, id))
        emit(PlaylistEditOutcome.Created(row.playlistId, id))
        val entries = cachedEntries(id)
        if (entries != null && entries != row.songs) emit(PlaylistEditOutcome.Diverged(id, PlaylistRowKind.Create, entries))
    }

    /**
     * After a create whose answer was lost: the one playlist this account owns, of that name and
     * song count, that the device has never seen (ASSUMED to be that create). Null when there is
     * none or more than one — which sends the create again (at least once).
     */
    private suspend fun findLostCreate(row: PendingPlaylistRow.Create): String? {
        val playlists = parseReaderPlaylists(reader.sendChecked("getPlaylists").response.body)
        val candidates = playlists.filter {
            it.name == row.name && it.owner == username && it.songCount == row.songs.size && cache.playlist(it.rawId) == null
        }
        return candidates.singleOrNull()?.rawId
    }

    private fun createdPlaylistId(body: String): String? {
        val playlist = parseLibraryEnvelope(body)?.payload?.get("playlist") as? JsonObject ?: return null
        return (playlist["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
    }

    /** Each row as it was before its current send was marked, restored if that send provably never arrived. */
    private val beforeMark = mutableMapOf<String, PendingPlaylistRow>()

    private val PendingPlaylistRow.key: String get() = "$playlistId|${kind.field}"

    /** Durably marks [row] attempted BEFORE its write is issued; null if a newer change replaced it. */
    private fun markAttempted(row: PendingPlaylistRow): PendingPlaylistRow? = database.transactionWithResult {
        val current = outbox.current(row) ?: return@transactionWithResult null
        beforeMark[current.key] = current
        val attempted = when (current) {
            is PendingPlaylistRow.Create -> current.copy(attempted = true)
            // A whole-list write records the list it sends; an append needs no record (see deliverEntries).
            is PendingPlaylistRow.Entries -> current.copy(attempted = true, sent = if (current.appendOnly) current.sent else current.target)
            is PendingPlaylistRow.Delete -> current.copy(attempted = true)
            is PendingPlaylistRow.Details -> current
        }
        outbox.rewrite(attempted)
        attempted
    }

    /**
     * One write. A failure that proves the request never changed the server restores the row as it
     * was before this send was marked, so a later decision is shielded only by sends that may have
     * landed — an earlier one in doubt stays recorded (§18.3, revision 99 item 19).
     */
    private suspend fun writeChecked(row: PendingPlaylistRow, endpoint: String, parameters: List<Pair<String, String>>): SentResponse = try {
        reader.sendRepeatedChecked(endpoint, parameters, formPost)
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
            else -> tally.saved += 1
        }
        changed(setOf(row.playlistId))
        emit(outcome)
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
    }
}

private fun List<String>.endsWith(suffix: List<String>): Boolean =
    size >= suffix.size && subList(size - suffix.size, size) == suffix

private enum class PlaylistFailureClass { Refused, ThisChange, Transport }

private fun DomainError.failureClass(): PlaylistFailureClass = when (this) {
    // Code 0 is the server's generic error — the reference server uses it for a busy limit.
    is DomainError.Server.Known -> if (code == 0) PlaylistFailureClass.ThisChange else PlaylistFailureClass.Refused
    is DomainError.Server.Unknown -> if (code == 0) PlaylistFailureClass.ThisChange else PlaylistFailureClass.Refused
    is DomainError.Server.Busy -> PlaylistFailureClass.ThisChange
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
    is DomainError.Server -> true
    else -> false
}
