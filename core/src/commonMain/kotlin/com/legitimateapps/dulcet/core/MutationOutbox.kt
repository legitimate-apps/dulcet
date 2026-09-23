package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Favourites and ratings in a reader (spec §16.20, §18.3): the mutation outbox, the overlay that
 * shows a pending change in every publication, and the delivery that sends it.
 *
 * **A favourite is a star.** Subsonic's `star`/`unstar` is the only favourite the protocol has, and
 * every OpenSubsonic client and server treats it that way; [LibraryFavourites] names it for the
 * shells so that no shell has to know the wire word.
 *
 * The rules, and where each is enforced:
 *
 * 1. **The change is in the next publication, before any request** — [LibraryFavourites] writes the
 *    outbox row and republishes every open window mentioning the target synchronously, and only
 *    then may a send begin.
 * 2. **The overlay is applied at publish time and never written into the cache** — [MutationOutbox]
 *    is the reader's [LibraryMutationOverlay]. A live read that lands while a change is pending
 *    updates the cache row, and the overlay still wins, so a star never flickers off (CONF-84).
 * 3. **Compaction** — one row per `(target, field)`; a later change replaces the earlier one. A
 *    change that returns the field to the value the server last reported, and that was never sent,
 *    leaves nothing to send: star then unstar of an unstarred album is no row at all. A change that
 *    may already have reached the server is never compacted away — see [PendingMutation.attempted].
 * 4. **Adopt-on-echo** — `star`, `unstar` and `setRating` answer with an empty `ok`, so the echo is
 *    the acknowledgement itself: the value sent is now the server's. It is written into the cache
 *    row and the outbox row is removed, so the published value does not change when the overlay
 *    goes away.
 * 5. **The conflict rule of §18.3**, decided per change when it is about to be sent
 *    ([decideDelivery]). Order is the seen-cache issue sequence, never a clock.
 * 6. **At-least-once.** A send whose answer is lost (a timeout, a dropped connection) leaves the row
 *    pending, and it is sent again at the next flush. The server may therefore receive the same
 *    change twice. These operations SET a value rather than change one, so a repeat ends in the same
 *    state; nothing here claims the network call is idempotent in any other sense.
 * 7. **No credential is ever queued.** A row holds the target's opaque id, the field, and a JSON
 *    object of the target's kind and integers; the request is built at send time by the reader's
 *    authenticated transport.
 */
internal enum class LibraryEntityKind(val wireName: String) {
    Artist("artist"),
    Album("album"),
    Track("track");

    companion object {
        fun fromWireName(value: String): LibraryEntityKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** Something a person can favourite or rate: an artist, an album or a track, by opaque id. */
internal data class LibraryEntityRef(val kind: LibraryEntityKind, val rawId: String) {
    init {
        require(rawId.isNotBlank())
    }
}

/** The set-to-value fields of §18.3. A future set-to-value mutation adds a field here (§16.20). */
internal enum class MutationField(val wireName: String) {
    Starred("starred"),
    Rating("rating");

    companion object {
        fun fromWireName(value: String): MutationField? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * One pending change. [value] is `1`/`0` for [MutationField.Starred] and `0..5` for
 * [MutationField.Rating], where `0` removes a rating, as `setRating` defines it.
 */
internal data class PendingMutation(
    val target: LibraryEntityRef,
    val field: MutationField,
    val value: Int,
    /** The value the server last reported when this change was made; null when unknown. */
    val base: Int?,
    /**
     * A send of this field was issued and its answer never arrived, so the server may already hold
     * some value this device sent. Such a row is never compacted away and never loses to the
     * server's value (§18.3): what the server shows may be this device's own earlier send.
     */
    val attempted: Boolean,
    /** The seen-cache issue sequence taken when the change was made — the ordering key. */
    val localSequence: Long,
    /** Wall-clock of the change, for display and retention only; never used to order anything. */
    val wallClock: Long,
)

/** What a local change did to the outbox. */
internal enum class MutationRecord {
    /** A row now holds the change. */
    Pending,

    /** The change returned the field to the server's value before anything was sent: no row. */
    CompactedAway,

    /** Nothing to do: the field already shows this value and nothing is pending. */
    Unchanged,
}

/** How a delivered, refused or superseded change ended, for the shell to tell the person. */
internal sealed interface MutationOutcome {
    val target: LibraryEntityRef
    val field: MutationField

    /** The server acknowledged the change; its value is now the published value. */
    data class Saved(override val target: LibraryEntityRef, override val field: MutationField, val value: Int) : MutationOutcome

    /**
     * The server refused the change (for example: the item no longer exists). The overlay is
     * removed and the server's value shows; the person is told the change did not save (§16.20).
     */
    data class NotSaved(override val target: LibraryEntityRef, override val field: MutationField, val error: DomainError) : MutationOutcome

    /**
     * A live read issued after the change showed the server holding a third value — neither the
     * value this device last knew nor the one it set — so the server's value wins (§18.3). The
     * person is told the change did not save because it was changed elsewhere.
     */
    data class Superseded(override val target: LibraryEntityRef, override val field: MutationField, val serverValue: Int) : MutationOutcome
}

/** The result of one flush. [stoppedBy] is set when a transient failure left changes pending. */
internal data class MutationFlushReport(
    val sent: Int,
    val saved: Int,
    val adoptedWithoutSending: Int,
    val refused: Int,
    val superseded: Int,
    val stoppedBy: DomainError?,
    val stillPending: Int,
)

/** The decision the §18.3 conflict rule makes for one change about to be sent. */
internal sealed interface DeliveryDecision {
    data object Send : DeliveryDecision

    /** The server was read after the change and already holds its value: nothing to send. */
    data object AlreadyApplied : DeliveryDecision

    data class ServerWins(val serverValue: Int) : DeliveryDecision
}

/**
 * §18.3's conflict rule, with revision 99's ordering key — the last live read of the item:
 *
 * - When no live read of the item was issued after the change, the change is newer than everything
 *   this device knows of the server: it is sent.
 * - When a read issued after the change shows the change's own value, the server already holds it
 *   (an earlier send that lost its answer, or the same change made elsewhere): nothing is sent.
 * - When a read issued after the change shows the value the change was made over, the server did
 *   not change in the meantime, so the change is still the newest writer: it is sent.
 * - When a read issued after the change shows a third value, the server changed after this device
 *   last saw it, and **the server wins**. This can only happen to a rating: a star has two values.
 *   ASSUMED, and stated: a value first seen after the change was set after it; a read cannot tell
 *   an older change it had not yet seen from a newer one.
 * - A change that may already have been delivered ([PendingMutation.attempted]) never loses: the
 *   value a later read shows may be this device's own earlier send, so the person's latest value is
 *   sent.
 */
internal fun decideDelivery(
    change: PendingMutation,
    serverValue: Int?,
    serverReadIssueSeq: Long?,
): DeliveryDecision {
    if (serverValue == null || serverReadIssueSeq == null || serverReadIssueSeq <= change.localSequence) {
        return DeliveryDecision.Send
    }
    if (serverValue == change.value) return DeliveryDecision.AlreadyApplied
    if (!change.attempted && change.base != null && serverValue != change.base) {
        return DeliveryDecision.ServerWins(serverValue)
    }
    return DeliveryDecision.Send
}

/**
 * The durable outbox of one bound account, and the reader's overlay over it. Survives relaunch: it
 * is the `mutation_outbox` table, which every migration preserves (spec §11.4).
 */
internal class MutationOutbox(
    private val database: DulcetDatabase,
    private val cache: BoundSeenCache,
) : LibraryMutationOverlay {
    private val queries get() = database.protectedReservedDataQueries

    init {
        // A namespace the store just purged because its binding changed was filled as another
        // server or user. Changes queued for it were authored as that account; sending them as the
        // new one would write another person's favourites.
        if (cache.purgedOnBind) {
            database.transaction {
                all().forEach { queries.deletePendingMutation(cache.serverId, it.target.rawId, it.field.wireName) }
            }
        }
    }

    val serverId: String get() = cache.serverId

    /** Every pending change, oldest first — the order they are sent in. */
    fun all(): List<PendingMutation> =
        queries.selectPendingMutations(cache.serverId).executeAsList().mapNotNull { row ->
            decode(row.target_id, row.field_, row.value_, row.local_sequence, row.wall_clock)
        }

    fun pendingCount(): Long = queries.countPendingMutations(cache.serverId).executeAsOne()

    fun pendingFor(target: LibraryEntityRef, field: MutationField): PendingMutation? =
        queries.selectPendingMutation(cache.serverId, target.rawId, field.wireName).executeAsOneOrNull()?.let { row ->
            decode(row.target_id, row.field_, row.value_, row.local_sequence, row.wall_clock)
        }?.takeIf { it.target.kind == target.kind }

    /**
     * The overlay (§16.20): for each entity, the latest pending value of each field. Reads the
     * whole outbox — it holds a person's unsent taps, not a library — rather than binding one
     * parameter per id, which some SQLite builds cap at 999.
     */
    override fun pending(serverId: String, rawIds: Set<String>): Map<String, PendingUserState> {
        if (serverId != cache.serverId || rawIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, PendingUserState>()
        all().forEach { change ->
            val rawId = change.target.rawId
            if (rawId !in rawIds) return@forEach
            val state = result[rawId] ?: PendingUserState()
            result[rawId] = when (change.field) {
                MutationField.Starred -> state.copy(starred = change.value == 1)
                MutationField.Rating -> state.copy(userRating = change.value)
            }
        }
        return result
    }

    /**
     * Records a local change, compacting per `(target, field)`. [serverValue] is the value the
     * server last reported for the field (the cache row's), or null when unknown.
     */
    fun record(target: LibraryEntityRef, field: MutationField, value: Int, serverValue: Int?): MutationRecord {
        field.requireValid(value)
        return database.transactionWithResult {
            val existing = pendingFor(target, field)
            if (existing == null && value == serverValue) return@transactionWithResult MutationRecord.Unchanged
            if (existing != null && existing.value == value) return@transactionWithResult MutationRecord.Pending
            if (existing != null && !existing.attempted && value == serverValue) {
                queries.deletePendingMutation(cache.serverId, target.rawId, field.wireName)
                return@transactionWithResult MutationRecord.CompactedAway
            }
            val change = PendingMutation(
                target = target,
                field = field,
                value = value,
                base = serverValue,
                attempted = existing?.attempted ?: false,
                localSequence = cache.issue(),
                wallClock = cache.now(),
            )
            if (existing == null) {
                queries.insertPendingMutation(
                    cache.serverId, target.rawId, field.wireName, encode(change), change.localSequence, change.wallClock,
                )
            } else {
                queries.replacePendingMutation(
                    value = encode(change),
                    local_sequence = change.localSequence,
                    wall_clock = change.wallClock,
                    server_id = cache.serverId,
                    target_id = target.rawId,
                    field = field.wireName,
                )
            }
            MutationRecord.Pending
        }
    }

    /** Durably marks [change] as possibly delivered, BEFORE its send is issued. */
    fun markAttempted(change: PendingMutation): PendingMutation {
        val attempted = change.copy(attempted = true)
        queries.updatePendingMutationValue(
            value = encode(attempted),
            server_id = cache.serverId,
            target_id = change.target.rawId,
            field = change.field.wireName,
            local_sequence = change.localSequence,
        )
        return attempted
    }

    /** Removes [change] only if no newer change to the same field replaced it meanwhile. */
    fun removeIfUnchanged(change: PendingMutation) {
        queries.deletePendingMutationIfUnchanged(cache.serverId, change.target.rawId, change.field.wireName, change.localSequence)
    }

    /**
     * Adopt-on-echo: writes the acknowledged value into the cache row under [acknowledgedSeq] and
     * removes the row if it still holds [change], in one transaction.
     */
    fun acknowledge(change: PendingMutation, acknowledgedSeq: Long) = database.transaction {
        val serverId = cache.serverId
        val rawId = change.target.rawId
        val value = change.value.toLong()
        when (change.field) {
            MutationField.Starred -> when (change.target.kind) {
                LibraryEntityKind.Artist -> queries.adoptArtistStarred(value, acknowledgedSeq, serverId, rawId)
                LibraryEntityKind.Album -> queries.adoptAlbumStarred(value, acknowledgedSeq, serverId, rawId)
                LibraryEntityKind.Track -> queries.adoptTrackStarred(value, acknowledgedSeq, serverId, rawId)
            }
            MutationField.Rating -> when (change.target.kind) {
                LibraryEntityKind.Artist -> queries.adoptArtistRating(value, acknowledgedSeq, serverId, rawId)
                LibraryEntityKind.Album -> queries.adoptAlbumRating(value, acknowledgedSeq, serverId, rawId)
                LibraryEntityKind.Track -> queries.adoptTrackRating(value, acknowledgedSeq, serverId, rawId)
            }
        }
        removeIfUnchanged(change)
    }

    /** What the cache says the server holds for [field] of [target], and the issue of that read. */
    fun serverState(target: LibraryEntityRef, field: MutationField): Pair<Int?, Long>? {
        val (state, issueSeq) = when (target.kind) {
            LibraryEntityKind.Artist -> cache.artist(target.rawId)?.let { it.record.userState to it.row.issueSeq }
            LibraryEntityKind.Album -> cache.album(target.rawId)?.let { it.record.userState to it.row.issueSeq }
            LibraryEntityKind.Track -> cache.track(target.rawId)?.let { (it.record?.userState ?: CacheUserState()) to it.row.issueSeq }
        } ?: return null
        val value = when (field) {
            MutationField.Starred -> state.starred?.let { if (it) 1 else 0 }
            MutationField.Rating -> state.userRating
        }
        return value to issueSeq
    }

    private fun decode(targetId: String, field: String, value: String, localSequence: Long, wallClock: Long): PendingMutation? {
        val parsedField = MutationField.fromWireName(field) ?: return null
        val json = try {
            LIBRARY_JSON.parseToJsonElement(value).jsonObject
        } catch (_: IllegalArgumentException) {
            return null
        }
        val kind = (json["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(LibraryEntityKind::fromWireName) ?: return null
        val set = (json["value"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
        val base = (json["base"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val attempted = (json["attempted"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!parsedField.isValid(set) || targetId.isBlank()) return null
        return PendingMutation(LibraryEntityRef(kind, targetId), parsedField, set, base, attempted, localSequence, wallClock)
    }

    private fun encode(change: PendingMutation): String = JsonObject(
        buildMap {
            put("kind", JsonPrimitive(change.target.kind.wireName))
            put("value", JsonPrimitive(change.value))
            change.base?.let { put("base", JsonPrimitive(it)) }
            put("attempted", JsonPrimitive(change.attempted))
        },
    ).toString()
}

private fun MutationField.isValid(value: Int): Boolean = when (this) {
    MutationField.Starred -> value == 0 || value == 1
    MutationField.Rating -> value in 0..5
}

private fun MutationField.requireValid(value: Int) = require(isValid(value)) { "invalid value for $wireName" }

/** Whether a failed send is the server's refusal of this change, rather than a reason to retry. */
private fun DomainError.refusesTheChange(): Boolean = when (this) {
    // Code 0 is the server's generic error — the reference server uses it for a busy limit — so it
    // is retried, not taken as a refusal.
    is DomainError.Server.Known -> code != 0
    is DomainError.Server.Unknown -> code != 0
    // Code 50: this user may not make this change. Retrying cannot help.
    DomainError.Auth.Forbidden -> true
    else -> false
}

/**
 * The shells' favourites and ratings, for one reader. Every call is on the reader's thread (see
 * [LibraryReader]'s threading contract); sends run on the reader's scope.
 */
internal class LibraryFavourites(
    private val reader: LibraryReader,
    private val outbox: MutationOutbox,
) {
    private val lock = Mutex()
    private val outcomeListeners = mutableListOf<(MutationOutcome) -> Unit>()
    private val changeListeners = mutableListOf<(Set<String>) -> Unit>()

    /** Told how each change ended; a [MutationOutcome.NotSaved] or [MutationOutcome.Superseded] needs words. */
    fun addOutcomeListener(listener: (MutationOutcome) -> Unit) {
        outcomeListeners += listener
    }

    /** Surfaces outside the reader's windows (search) republish through this. */
    fun addChangeListener(listener: (Set<String>) -> Unit) {
        changeListeners += listener
    }

    /** The favourite state a publication shows: the pending change, else the server's last value. */
    fun isFavourite(target: LibraryEntityRef): Boolean? =
        (outbox.pendingFor(target, MutationField.Starred)?.value ?: outbox.serverState(target, MutationField.Starred)?.first)
            ?.let { it == 1 }

    fun rating(target: LibraryEntityRef): Int? =
        outbox.pendingFor(target, MutationField.Rating)?.value ?: outbox.serverState(target, MutationField.Rating)?.first

    /** Makes [target] a favourite or not. Shown at once; sent when online, else on reconnect. */
    fun setFavourite(target: LibraryEntityRef, favourite: Boolean): MutationRecord =
        change(target, MutationField.Starred, if (favourite) 1 else 0)

    /** Flips the favourite state as shown; an unknown state becomes a favourite. Returns the new state. */
    fun toggleFavourite(target: LibraryEntityRef): Boolean {
        val favourite = isFavourite(target) != true
        setFavourite(target, favourite)
        return favourite
    }

    /** Sets a 1–5 rating, or removes it with 0. */
    fun setRating(target: LibraryEntityRef, rating: Int): MutationRecord {
        require(rating in 0..5)
        return change(target, MutationField.Rating, rating)
    }

    /** For the logout offer of §14.7: changes that have not reached the server. */
    fun pendingCount(): Long = outbox.pendingCount()

    private fun change(target: LibraryEntityRef, field: MutationField, value: Int): MutationRecord {
        val record = outbox.record(target, field, value, outbox.serverState(target, field)?.first)
        if (record != MutationRecord.Unchanged) {
            // Synchronously, before any send is launched: the tap's publication carries the change.
            changed(setOf(target.rawId))
            if (reader.online) reader.scope.launch { flush() }
        }
        return record
    }

    private fun changed(rawIds: Set<String>) {
        reader.republishPendingChanges(rawIds)
        changeListeners.toList().forEach { it(rawIds) }
    }

    /**
     * Sends every pending change in order, one at a time. Stops at the first transient failure so
     * order is kept, leaving that change and every later one pending for the next flush — the next
     * change made online, or the reconnect of §16.14. Does nothing offline.
     */
    suspend fun flush(): MutationFlushReport = lock.withLock {
        var sent = 0
        var saved = 0
        var adopted = 0
        var refused = 0
        var superseded = 0
        var stoppedBy: DomainError? = null
        while (reader.online) {
            val change = outbox.all().firstOrNull() ?: break
            val server = outbox.serverState(change.target, change.field)
            when (val decision = decideDelivery(change, server?.first, server?.second)) {
                DeliveryDecision.AlreadyApplied -> {
                    outbox.removeIfUnchanged(change)
                    adopted += 1
                    changed(setOf(change.target.rawId))
                }
                is DeliveryDecision.ServerWins -> {
                    outbox.removeIfUnchanged(change)
                    superseded += 1
                    changed(setOf(change.target.rawId))
                    emit(MutationOutcome.Superseded(change.target, change.field, decision.serverValue))
                }
                DeliveryDecision.Send -> {
                    val attempted = outbox.markAttempted(change)
                    sent += 1
                    val failure = try {
                        reader.checked(change.endpoint(), change.parameters())
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        error.asReaderError()
                    }
                    when {
                        failure == null -> {
                            outbox.acknowledge(attempted, reader.cache.issue())
                            saved += 1
                            changed(setOf(change.target.rawId))
                            emit(MutationOutcome.Saved(change.target, change.field, change.value))
                        }
                        failure.refusesTheChange() -> {
                            outbox.removeIfUnchanged(attempted)
                            refused += 1
                            changed(setOf(change.target.rawId))
                            emit(MutationOutcome.NotSaved(change.target, change.field, failure))
                        }
                        else -> {
                            stoppedBy = failure
                            break
                        }
                    }
                }
            }
        }
        MutationFlushReport(sent, saved, adopted, refused, superseded, stoppedBy, outbox.pendingCount().toInt())
    }

    private fun emit(outcome: MutationOutcome) = outcomeListeners.toList().forEach { it(outcome) }
}

private fun PendingMutation.endpoint(): String = when (field) {
    MutationField.Starred -> if (value == 1) "star" else "unstar"
    MutationField.Rating -> "setRating"
}

private fun PendingMutation.parameters(): Map<String, String> = when (field) {
    MutationField.Starred -> mapOf(
        when (target.kind) {
            LibraryEntityKind.Track -> "id"
            LibraryEntityKind.Album -> "albumId"
            LibraryEntityKind.Artist -> "artistId"
        } to target.rawId,
    )
    MutationField.Rating -> mapOf("id" to target.rawId, "rating" to value.toString())
}

/**
 * One account's reader with its favourites wired in: the outbox is the reader's overlay, and its
 * flush runs first on reconnect, before [otherOutboxes] (the scrobble outbox) and before the epoch
 * read (§16.14 step 1). This is the assembly the shells' facades construct (R2a, R3).
 */
internal class LibraryReaderSession(
    database: DulcetDatabase,
    cache: BoundSeenCache,
    transport: LibraryEndpointTransport,
    scope: kotlinx.coroutines.CoroutineScope,
    config: LibraryReaderConfig = LibraryReaderConfig(),
    downloads: DownloadedTrackSource = DownloadedTrackSource.None,
    otherOutboxes: ReconnectOutboxes = ReconnectOutboxes.None,
) {
    val outbox = MutationOutbox(database, cache)
    private lateinit var favouritesRef: LibraryFavourites

    val reader = LibraryReader(
        cache = cache,
        transport = transport,
        scope = scope,
        config = config,
        overlay = outbox,
        downloads = downloads,
        outboxes = ReconnectOutboxes {
            favouritesRef.flush()
            otherOutboxes.flush()
        },
    )

    val favourites = LibraryFavourites(reader, outbox).also { favouritesRef = it }

    /** A search over this reader whose rows carry the same overlaid favourite state (§16.15). */
    fun openSearch(
        config: LibrarySearchConfig = LibrarySearchConfig(),
        listener: (LibrarySearchPublication) -> Unit,
    ): LibrarySearchSession {
        val session = LibrarySearchSession(reader, config, listener)
        favourites.addChangeListener(session::republishPendingChanges)
        searches += session
        return session
    }

    private val searches = mutableListOf<LibrarySearchSession>()

    /**
     * Reachability, as the platform reports it: the reader republishes its windows, and every open
     * search re-runs so its scope says `deviceOffline` (or merges the server again) without waiting
     * for a keystroke.
     */
    fun setOnline(reachable: Boolean) {
        val changed = reader.online != reachable
        reader.setOnline(reachable)
        searches.removeAll { it.isClosed }
        if (changed) searches.toList().forEach(LibrarySearchSession::refresh)
    }
}
