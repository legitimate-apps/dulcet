package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Favourites and ratings in a reader (spec §16.20, §18.3): the mutation outbox, the overlay that
 * shows a pending change in every publication, and the delivery that sends it.
 *
 * **A favourite is a star.** Subsonic's `star`/`unstar` is the only favourite the protocol has;
 * [LibraryFavourites] names it for the shells so that no shell has to know the wire word.
 *
 * The rules, and where each is enforced:
 *
 * 1. **The change is in the next publication, before any request** — [LibraryFavourites] writes the
 *    outbox row and republishes every open window and search mentioning the target synchronously,
 *    and only then may a send begin.
 * 2. **The overlay is applied at publish time and never written into the cache** — [MutationOutbox]
 *    is the reader's [LibraryMutationOverlay]. A live read that lands while a change is pending
 *    updates the cache row, and the overlay still wins, so a star never flickers off (CONF-84).
 * 3. **The key is (server, kind, id, field).** Opaque ids of different kinds may be equal — a
 *    server that numbers artists and albums independently gives both an id `4` — so the kind is part
 *    of the key and of every overlay lookup. It is carried in the `field` column as
 *    `<kind>.<field>` because `mutation_outbox`'s schema is protected (§11.4) and must not change.
 * 4. **Compaction** — one row per key; a later change replaces the earlier one. A change back to the
 *    value the server last reported leaves no row when nothing else may be on the server: star then
 *    unstar of an unstarred album sends nothing. A change some other value of which may already have
 *    reached the server is kept and sent.
 * 5. **Adopt-on-echo** — `star`, `unstar` and `setRating` answer with an empty `ok`, so the echo is
 *    the acknowledgement itself: the value sent is now the server's. It is written into the cache
 *    row and the outbox row is removed, so the published value does not change when the overlay goes.
 * 6. **The conflict rule of §18.3**, decided per change when it is about to be sent
 *    ([decideDelivery]). Order is the seen-cache issue sequence, never a clock.
 * 7. **At-least-once.** A send whose answer is lost (a timeout) leaves the row pending, and it is
 *    sent again at the next flush, so the server may receive the same change twice. These operations
 *    SET a value rather than change one, so a repeat ends in the same state; nothing here claims the
 *    network call is idempotent in any other sense.
 * 8. **No credential is ever queued.** A row holds the target's opaque id, the kind-qualified field
 *    and a JSON object of integers; the request is built at send time by the reader's transport.
 * 9. **A change can always be taken back.** [LibraryFavourites.pendingChanges] lists what is
 *    unsent and [LibraryFavourites.withdraw] removes one, however it is held or failing (§18.6).
 * 10. **No exception leaves an entry point.** The shells reach these through the Objective-C
 *    boundary, where a Kotlin exception terminates the process (CORPUS §4 line 8): a change the
 *    device could not record is [MutationRecord.NotRecorded], told as [MutationOutcome.NotRecorded].
 */
internal enum class LibraryEntityKind(val wireName: String, val cacheKind: CacheItemKind) {
    Artist("artist", CacheItemKind.Artist),
    Album("album", CacheItemKind.Album),
    Track("track", CacheItemKind.Track);

    companion object {
        fun fromWireName(value: String): LibraryEntityKind? = entries.firstOrNull { it.wireName == value }
        fun of(kind: CacheItemKind): LibraryEntityKind? = entries.firstOrNull { it.cacheKind == kind }
    }
}

/** Something a person can favourite or rate: an artist, an album or a track, by kind AND opaque id. */
internal data class LibraryEntityRef(val kind: LibraryEntityKind, val rawId: String) {
    init {
        require(rawId.isNotBlank())
    }

    val member: CacheListMember get() = CacheListMember(kind.cacheKind, rawId)
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
     * Values of this field whose send may have reached the server: each was sent and its answer
     * never arrived. A value whose send provably never arrived (unreachable, an error envelope) is
     * removed again. The server showing one of these may be this device's own earlier send.
     */
    val attemptedValues: Set<Int>,
    /** Consecutive per-change failures (the server answered, but not with success or a refusal). */
    val failures: Int,
    /** The seen-cache issue sequence taken when the change was made — the ordering key. */
    val localSequence: Long,
    /** Wall-clock of the change, for display and retention only; never used to order anything. */
    val wallClock: Long,
) {
    val key: String get() = mutationKey(target.kind, this.field)
}

internal fun mutationKey(kind: LibraryEntityKind, field: MutationField): String = "${kind.wireName}.${field.wireName}"

/** What a local change did to the outbox. */
internal enum class MutationRecord {
    /** A row now holds the change. */
    Pending,

    /** The change returned the field to the server's value and nothing else may be on it: no row. */
    CompactedAway,

    /** Nothing to do: the field already shows this value and nothing is pending. */
    Unchanged,

    /** The value is not one the field can take (a rating outside 0–5). Nothing was recorded. */
    Invalid,

    /** The device could not record the change (its database failed). Nothing was recorded. */
    NotRecorded,

    /**
     * Withdrawn too late to undo: a send of the change has gone out and was not answered with a 429,
     * another 4xx or another answer proving it did not apply — it is in flight now, or its answer was
     * lost — so the server may hold it already. It is not sent again, and the item shows what the
     * server answers, or its next read. A change whose sends were all answered with a 429 or a 4xx is
     * [CompactedAway].
     */
    AlreadySent,
}

/** How a change ended, for the shell to tell the person. */
internal sealed interface MutationOutcome {
    val target: LibraryEntityRef
    val field: MutationField

    /** The server acknowledged the change; its value is now the published value. */
    data class Saved(override val target: LibraryEntityRef, override val field: MutationField, val value: Int) : MutationOutcome

    /**
     * The server refused the change (the item no longer exists; this user may not change it), or it
     * failed [LibraryFavourites.MAX_FAILURES] times in a row. The overlay is removed and the server's
     * value shows; the person is told the change did not save (§16.20).
     */
    data class NotSaved(override val target: LibraryEntityRef, override val field: MutationField, val error: DomainError) : MutationOutcome

    /**
     * A live read issued after the change showed the server holding a value this device neither
     * last saw nor sent, so the server's value wins (§18.3). The person is told it was changed
     * elsewhere.
     */
    data class Superseded(override val target: LibraryEntityRef, override val field: MutationField, val serverValue: Int) : MutationOutcome

    /**
     * The flush stopped at this change and kept it, and every change after it, unsent: the ACCOUNT
     * was refused access — this change's request was, and an authenticated `ping` sent to check was
     * refused too, so [error] is the ping's: an `Auth` error (credentials refused: envelope code 40,
     * or a bare 401, which reads as `Auth.InvalidCredentials` whether the server or a proxy sent it)
     * or an `HttpStatus` 403 or 407 — or the server asked to wait (`Server.Busy`, told once per
     * run of 429s). Nothing counts toward [LibraryFavourites.MAX_FAILURES]; a later flush sends it —
     * after the person signs in again, or once the wait has passed. The shell tells the person why,
     * and the person can withdraw the change ([LibraryFavourites.withdraw]).
     */
    data class Held(override val target: LibraryEntityRef, override val field: MutationField, val error: DomainError) : MutationOutcome

    /** The device could not record the change at all. Nothing is shown as changed. */
    data class NotRecorded(override val target: LibraryEntityRef, override val field: MutationField) : MutationOutcome
}

/** The result of one flush. [stoppedBy] is set when the server could not be reached. */
internal data class MutationFlushReport(
    val sent: Int,
    val saved: Int,
    val adoptedWithoutSending: Int,
    val refused: Int,
    val superseded: Int,
    /** Changes that failed on their own this flush and stay pending for the next one. */
    val deferred: Int,
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
 * - When a read issued after the change shows the change's own value, the server already holds it:
 *   nothing is sent.
 * - When it shows a value this device may itself have sent ([PendingMutation.attemptedValues]),
 *   that may be this device's own earlier send, not another client's: the change is sent.
 * - When it shows the value the change was made over, the server did not change: the change is sent.
 * - Otherwise the server changed after this device last saw it, and **the server wins**. ASSUMED,
 *   and stated: a value first seen after the change was set after it.
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
    if (serverValue in change.attemptedValues) return DeliveryDecision.Send
    if (change.base != null && serverValue != change.base) return DeliveryDecision.ServerWins(serverValue)
    return DeliveryDecision.Send
}

/**
 * The durable outbox of one bound account, and the reader's overlay over it. Survives relaunch: it
 * is the `mutation_outbox` table, which every migration preserves (spec §11.4). Changes queued as a
 * different user are discarded by [SeenCacheStore.bind] in the transaction that rebinds, and counted
 * in [BoundSeenCache.discardedPendingChanges].
 */
internal class MutationOutbox(
    private val database: DulcetDatabase,
    private val cache: BoundSeenCache,
) : LibraryMutationOverlay {
    private val queries get() = database.protectedReservedDataQueries

    val serverId: String get() = cache.serverId

    /** Every pending change, oldest first — the order they are sent in. */
    fun all(): List<PendingMutation> =
        queries.selectPendingMutations(cache.serverId).executeAsList().mapNotNull { row ->
            decode(row.target_id, row.field_, row.value_, row.local_sequence, row.wall_clock)
        }

    fun pendingCount(): Long = all().size.toLong()

    fun pendingFor(target: LibraryEntityRef, field: MutationField): PendingMutation? =
        queries.selectPendingMutation(cache.serverId, target.rawId, mutationKey(target.kind, field)).executeAsOneOrNull()?.let { row ->
            decode(row.target_id, row.field_, row.value_, row.local_sequence, row.wall_clock)
        }

    /**
     * The overlay (§16.20): for each target, the latest pending value of each field, looked up by
     * kind AND id. Reads the whole outbox — it holds a person's unsent taps, not a library — rather
     * than binding one parameter per id, which some SQLite builds cap at 999.
     */
    override fun pending(serverId: String, targets: Set<CacheListMember>): Map<CacheListMember, PendingUserState> {
        if (serverId != cache.serverId || targets.isEmpty()) return emptyMap()
        val result = mutableMapOf<CacheListMember, PendingUserState>()
        all().forEach { change ->
            val member = change.target.member
            if (member !in targets) return@forEach
            val state = result[member] ?: PendingUserState()
            result[member] = when (change.field) {
                MutationField.Starred -> state.copy(starred = change.value == 1)
                MutationField.Rating -> state.copy(userRating = change.value)
            }
        }
        return result
    }

    /**
     * Records a local change, compacting per key. [serverValue] is the value the server last
     * reported for the field (the cache row's), or null when unknown.
     */
    fun record(target: LibraryEntityRef, field: MutationField, value: Int, serverValue: Int?): MutationRecord {
        if (!field.isValid(value)) return MutationRecord.Invalid
        return database.transactionWithResult {
            val key = mutationKey(target.kind, field)
            val existing = pendingFor(target, field)
            if (existing == null && value == serverValue) return@transactionWithResult MutationRecord.Unchanged
            if (existing != null && existing.value == value) return@transactionWithResult MutationRecord.Pending
            if (existing != null && value == serverValue && existing.attemptedValues.all { it == value }) {
                queries.deletePendingMutation(cache.serverId, target.rawId, key)
                return@transactionWithResult MutationRecord.CompactedAway
            }
            val change = PendingMutation(
                target = target,
                field = field,
                value = value,
                base = serverValue,
                attemptedValues = existing?.attemptedValues.orEmpty(),
                failures = 0,
                localSequence = cache.issue(),
                wallClock = cache.now(),
            )
            if (existing == null) {
                queries.insertPendingMutation(cache.serverId, target.rawId, key, encode(change), change.localSequence, change.wallClock)
            } else {
                queries.replacePendingMutation(
                    value = encode(change),
                    local_sequence = change.localSequence,
                    wall_clock = change.wallClock,
                    server_id = cache.serverId,
                    target_id = target.rawId,
                    field = key,
                )
            }
            MutationRecord.Pending
        }
    }

    /**
     * Durably adds [change]'s value to its attempted set BEFORE its send is issued, in one
     * transaction with re-reading the row, so the change sent is the change stored.
     */
    fun markAttempted(change: PendingMutation): PendingMutation? = database.transactionWithResult {
        val current = pendingFor(change.target, change.field)
            ?.takeIf { it.localSequence == change.localSequence } ?: return@transactionWithResult null
        val attempted = current.copy(attemptedValues = current.attemptedValues + current.value)
        rewrite(attempted)
        attempted
    }

    /**
     * The send of [value] provably never reached the server (unreachable, or the server answered
     * with an error envelope): it can no longer be the reason the server shows [value]. Applied to
     * whatever change now holds the key, since the attempted set is inherited across compaction.
     */
    fun unmarkAttempted(target: LibraryEntityRef, field: MutationField, value: Int) = database.transaction {
        val current = pendingFor(target, field) ?: return@transaction
        if (value in current.attemptedValues) rewrite(current.copy(attemptedValues = current.attemptedValues - value))
    }

    /** Counts one per-change failure against [change] if it still holds its key; returns the new count. */
    fun countFailure(change: PendingMutation): Int? = database.transactionWithResult {
        val current = pendingFor(change.target, change.field)
            ?.takeIf { it.localSequence == change.localSequence } ?: return@transactionWithResult null
        val counted = current.copy(failures = current.failures + 1)
        rewrite(counted)
        counted.failures
    }

    /** Removes [change] only if no newer change to the same key replaced it meanwhile. */
    fun removeIfUnchanged(change: PendingMutation) {
        queries.deletePendingMutationIfUnchanged(cache.serverId, change.target.rawId, change.key, change.localSequence)
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
            LibraryEntityKind.Track -> cache.track(target.rawId)?.let { it.userState to it.row.issueSeq }
        } ?: return null
        val value = when (field) {
            MutationField.Starred -> state.starred?.let { if (it) 1 else 0 }
            MutationField.Rating -> state.userRating
        }
        return value to issueSeq
    }

    private fun rewrite(change: PendingMutation) {
        queries.updatePendingMutationValue(
            value = encode(change),
            server_id = cache.serverId,
            target_id = change.target.rawId,
            field = change.key,
            local_sequence = change.localSequence,
        )
    }

    private fun decode(targetId: String, key: String, value: String, localSequence: Long, wallClock: Long): PendingMutation? {
        val kind = LibraryEntityKind.fromWireName(key.substringBefore('.', "")) ?: return null
        val field = MutationField.fromWireName(key.substringAfter('.', "")) ?: return null
        if (targetId.isBlank()) return null
        val json = try {
            LIBRARY_JSON.parseToJsonElement(value).jsonObject
        } catch (_: IllegalArgumentException) {
            return null
        }
        fun int(name: String) = (json[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val set = int("value") ?: return null
        if (!field.isValid(set)) return null
        val attempted = (json["attempted"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet().orEmpty()
        return PendingMutation(
            LibraryEntityRef(kind, targetId), field, set, int("base"), attempted, int("failures") ?: 0, localSequence, wallClock,
        )
    }

    private fun encode(change: PendingMutation): String = JsonObject(
        buildMap {
            put("value", JsonPrimitive(change.value))
            change.base?.let { put("base", JsonPrimitive(it)) }
            put("attempted", JsonArray(change.attemptedValues.sorted().map(::JsonPrimitive)))
            put("failures", JsonPrimitive(change.failures))
        },
    ).toString()
}

private fun MutationField.isValid(value: Int): Boolean = when (this) {
    MutationField.Starred -> value == 0 || value == 1
    MutationField.Rating -> value in 0..5
}

/** How a failed send is handled. */
private enum class FailureClass {
    /** The server refused this change; retrying cannot help. */
    Refused,

    /** The server answered for this change without applying it; retry it later, move on now. */
    ThisChange,

    /**
     * The account was refused ACCESS — a ping sent after the refusal was refused too — or the server
     * asked to wait: nothing else can be sent now, every change is kept, and the person is told
     * ([MutationOutcome.Held]).
     */
    Held,

    /** The server could not be reached; nothing else can be sent now. */
    Transport,
}

private fun DomainError.failureClass(): FailureClass = when (this) {
    // Code 0 is the server's generic error — the reference server uses it for a busy limit.
    is DomainError.Server.Known -> if (code == 0) FailureClass.ThisChange else FailureClass.Refused
    is DomainError.Server.Unknown -> if (code == 0) FailureClass.ThisChange else FailureClass.Refused
    // A rate limit holds every change until its Retry-After, and counts toward nothing.
    is DomainError.Server.Busy -> FailureClass.Held
    // An HTTP status with no envelope: a gateway that cannot reach the server stops the flush like
    // no answer at all; a request too large never fits; anything else is this change's failure. A
    // refusal of access (401, 403, 407) never reaches here from a change: the flush sends a ping
    // first and decides there.
    is DomainError.Server.HttpStatus -> when {
        gatewayCannotReachServer -> FailureClass.Transport
        tooLarge -> FailureClass.Refused
        else -> FailureClass.ThisChange
    }
    // Code 50 in an envelope: this user may not make this change.
    DomainError.Auth.Forbidden -> FailureClass.Refused
    // Credentials refused — reached only as the ping's own failure, since the flush pings first:
    // every change is held for the person to sign in again.
    is DomainError.Auth -> FailureClass.Held
    is DomainError.Protocol -> FailureClass.ThisChange
    else -> FailureClass.Transport
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

/**
 * The shells' favourites and ratings, for one reader.
 *
 * **Threading, enforced.** Every entry point is confined to the reader's own thread
 * ([newLibraryReaderDispatcher]) and checks it, exactly as the reader's do: a call from any other
 * thread — the main thread included — throws `IllegalStateException`, which is a facade bug, never
 * a runtime condition. So a change is recorded, compacted and read for sending on one thread, and
 * [MutationOutbox.record] cannot interleave with a flush between reading a change and marking it
 * attempted (which is also one transaction). Listeners are called on the reader's thread; the
 * platform facade hops each outcome to the main thread (§16.18, trap 17).
 *
 * **Nothing else throws.** On the reader's thread every entry point returns a value: a database
 * failure is [MutationRecord.NotRecorded], told as [MutationOutcome.NotRecorded].
 */
internal class LibraryFavourites(
    private val reader: LibraryReader,
    private val outbox: MutationOutbox,
) {
    private val lock = Mutex()
    private val outcomeListeners = mutableListOf<(MutationOutcome) -> Unit>()
    private val changeListeners = mutableListOf<(Set<String>) -> Unit>()

    /** This outbox's run of 429s: [MutationOutcome.Held] is told once per run. */
    private val busyRun = BusyRun()

    /** Told how each change ended; every outcome except [MutationOutcome.Saved] needs words. */
    fun addOutcomeListener(listener: (MutationOutcome) -> Unit) {
        reader.checkConfined()
        outcomeListeners += listener
    }

    /** Surfaces outside the reader's windows (search) republish through this. */
    fun addChangeListener(listener: (Set<String>) -> Unit) {
        reader.checkConfined()
        changeListeners += listener
    }

    /** The favourite state a publication shows: the pending change, else the server's last value. */
    fun isFavourite(target: LibraryEntityRef): Boolean? = confined { guarded(null) {
        (outbox.pendingFor(target, MutationField.Starred)?.value ?: outbox.serverState(target, MutationField.Starred)?.first)
            ?.let { it == 1 }
    } }

    fun rating(target: LibraryEntityRef): Int? = confined { guarded(null) {
        outbox.pendingFor(target, MutationField.Rating)?.value ?: outbox.serverState(target, MutationField.Rating)?.first
    } }

    /** Makes [target] a favourite or not. Shown at once; sent when online, else on reconnect. */
    fun setFavourite(target: LibraryEntityRef, favourite: Boolean): MutationRecord =
        change(target, MutationField.Starred, if (favourite) 1 else 0)

    /** Flips the favourite state as shown; an unknown state becomes a favourite. Returns the new state. */
    fun toggleFavourite(target: LibraryEntityRef): Boolean {
        reader.checkConfined()
        val favourite = isFavourite(target) != true
        return if (setFavourite(target, favourite) == MutationRecord.NotRecorded) !favourite else favourite
    }

    /** Sets a 1–5 rating, or removes it with 0. Any other value is [MutationRecord.Invalid]. */
    fun setRating(target: LibraryEntityRef, rating: Int): MutationRecord = change(target, MutationField.Rating, rating)

    /** For the sign-out offer of §14.7: changes that have not reached the server. */
    fun pendingCount(): Long = confined { guarded(0L) { outbox.pendingCount() } }

    /**
     * Every change not yet on the server, oldest first — the order they are sent in — so a shell can
     * list them and offer to withdraw one. A change with its value in
     * [PendingMutation.attemptedValues] was sent and its answer lost: it may be on the server already.
     */
    fun pendingChanges(): List<PendingMutation> = confined { guarded(emptyList()) { outbox.all() } }

    /**
     * Takes back the pending change of [field] on [target]: it is not sent again, and the item shows
     * the server's last value again. However a change is held or failing, the person can always
     * withdraw it (§18.6 "Failures").
     * - [MutationRecord.CompactedAway]: no send of it had gone out, or each one was answered with a
     *   429, another 4xx or another answer proving it did not apply, so it never reaches the server.
     * - [MutationRecord.AlreadySent]: too late to undo — a send of it is in flight, or its answer was
     *   lost ([PendingMutation.attemptedValues]), so the server may hold it already; the item shows
     *   what the server answers, or its next read.
     * - [MutationRecord.Unchanged] when none was pending, [MutationRecord.NotRecorded] when the
     *   device's database failed and nothing changed.
     */
    fun withdraw(target: LibraryEntityRef, field: MutationField): MutationRecord {
        reader.checkConfined()
        val withdrawn = try {
            outbox.pendingFor(target, field)?.also(outbox::removeIfUnchanged)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return MutationRecord.NotRecorded
        }
        if (withdrawn == null) return MutationRecord.Unchanged
        endRunIfIdle()
        changed(setOf(target.rawId))
        return if (withdrawn.attemptedValues.isEmpty()) MutationRecord.CompactedAway else MutationRecord.AlreadySent
    }

    private fun change(target: LibraryEntityRef, field: MutationField, value: Int): MutationRecord {
        reader.checkConfined()
        if (!field.isValid(value)) return MutationRecord.Invalid
        val record = try {
            outbox.record(target, field, value, outbox.serverState(target, field)?.first)
        } catch (failure: Throwable) {
            emit(MutationOutcome.NotRecorded(target, field))
            return MutationRecord.NotRecorded
        }
        if (record == MutationRecord.Pending || record == MutationRecord.CompactedAway) {
            if (record == MutationRecord.CompactedAway) endRunIfIdle()
            // Synchronously, before any send is launched: the tap's publication carries the change.
            changed(setOf(target.rawId))
            if (reader.online) launchFlush(reader.scope)
        }
        return record
    }

    /**
     * Nothing left to send ends a run of 429s, however the queue emptied — a change withdrawn or
     * undone here, as much as a flush that sent the last one (§18.6 "Failures").
     */
    private fun endRunIfIdle() {
        if (guarded(-1L) { outbox.pendingCount() } == 0L) busyRun.end()
    }

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

    private fun changed(rawIds: Set<String>) {
        guarded(Unit) { reader.republishPendingChanges(rawIds) }
        changeListeners.toList().forEach { listener -> guarded(Unit) { listener(rawIds) } }
    }

    /**
     * Sends every pending change, oldest first, one at a time.
     *
     * - A change the server refuses is dropped and told ([MutationOutcome.NotSaved]).
     * - A change the server answers for without applying (the generic error code 0, a malformed
     *   answer) stays pending, and the flush **moves on** to the next change; after [MAX_FAILURES]
     *   such answers in a row it is dropped and told, so one change cannot hold the queue for ever.
     * - When a request is refused ACCESS — credentials refused (HTTP 401 or envelope code 40), a
     *   proxy answering 403 or 407 — one authenticated `ping` asks whether the ACCOUNT is. If the
     *   ping is refused too, the flush stops, keeps every change, counts toward nothing, and tells
     *   the person ([MutationOutcome.Held]). If the ping is answered, the refusal was that request's
     *   own — a rule in front of one endpoint — and the change fails on its own, as above, so it
     *   cannot hold every later change for ever. One ping per flush at most.
     * - When the server asks to wait (HTTP 429), the flush stops the same way, told once per run of
     *   429s ([BusyRun]: it ends when a flush sends something and meets no 429, or finishes with
     *   nothing pending, or the queue empties here — not on one delivery). A 429 for a change
     *   withdrawn or undone while its request was out stops the flush and sets the wait, but begins
     *   no run and is not told. Neither this flush nor the playlist one begins sending a change until
     *   `max(Retry-After, a floor that doubles through the run from two seconds)` has passed, capped
     *   at five minutes — each checks the wait before each change, so one already running stops at
     *   its next change; a request already out is not recalled — and a later 429 never shortens that
     *   wait (§18.6 "Failures"); a change made meanwhile does not send early. Then one flush of every
     *   outbox runs.
     * - When the server cannot be reached at all, the flush stops and keeps every change, in order,
     *   for the next flush — the next change made online, or the reconnect of §16.14.
     * - A failure of the device's own database is thrown, never reported as the server's; every
     *   change is kept.
     * - Offline it does nothing.
     */
    suspend fun flush(): MutationFlushReport = confined { lock.withLock {
        var sent = 0
        var saved = 0
        var adopted = 0
        var refused = 0
        var superseded = 0
        val deferred = mutableSetOf<String>()
        // An authenticated ping was answered this flush: a later refusal of access is its request's own.
        var accountAnswers = false
        // This flush met a 429 of its own: the run of them goes on.
        var met429 = false
        var stoppedBy: DomainError? = null
        while (reader.online) {
            // The server asked for quiet (a 429): nothing is sent until the wait has passed. Checked
            // before each change, not once, so a wait the playlist flush's 429 sets meanwhile stops
            // this flush too.
            stoppedBy = reader.busyError()
            if (stoppedBy != null) break
            val change = outbox.all().firstOrNull { "${it.target.rawId}|${it.key}" !in deferred } ?: break
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
                    val attempted = outbox.markAttempted(change) ?: continue
                    sent += 1
                    // Only a failure of the request is the server's; the device's own database failing
                    // propagates, with every change kept.
                    val failure = try {
                        reader.sendChecked(change.endpoint(), change.parameters())
                        null
                    } catch (thrown: LibraryRequestFailure) {
                        thrown.error
                    }
                    if (failure == null) {
                        outbox.acknowledge(attempted, reader.cache.issue())
                        saved += 1
                        changed(setOf(change.target.rawId))
                        emit(MutationOutcome.Saved(change.target, change.field, change.value))
                        continue
                    }
                    if (failure.provesNotApplied()) outbox.unmarkAttempted(change.target, change.field, change.value)
                    // A refusal of access holds every change only when the account is refused: one
                    // ping asks, once per flush. Answered, it was this request's own refusal.
                    var error: DomainError = failure
                    val failureClass = when {
                        !failure.refusesAccess -> failure.failureClass()
                        accountAnswers -> FailureClass.ThisChange
                        else -> when (val pinged = reader.pingAfterRefusal()) {
                            null -> FailureClass.ThisChange.also { accountAnswers = true }
                            else -> {
                                error = pinged
                                if (pinged.failureClass() == FailureClass.Transport) FailureClass.Transport else FailureClass.Held
                            }
                        }
                    }
                    when (failureClass) {
                        FailureClass.Refused -> {
                            outbox.removeIfUnchanged(attempted)
                            refused += 1
                            changed(setOf(change.target.rawId))
                            emit(MutationOutcome.NotSaved(change.target, change.field, error))
                        }
                        FailureClass.ThisChange -> {
                            val failures = outbox.countFailure(attempted)
                            if (failures != null && failures >= MAX_FAILURES) {
                                outbox.removeIfUnchanged(attempted)
                                refused += 1
                                changed(setOf(change.target.rawId))
                                emit(MutationOutcome.NotSaved(change.target, change.field, error))
                            } else {
                                deferred += "${change.target.rawId}|${change.key}"
                            }
                        }
                        FailureClass.Held -> {
                            stoppedBy = error
                            // A 429 is told once per run of them, not once per retry. One for a change
                            // withdrawn or undone while its request was out still sets the wait, but
                            // nothing of it is queued: it begins no run and is not told.
                            val tell = if (error is DomainError.Server.Busy) {
                                val queued = outbox.pendingFor(change.target, change.field) != null
                                if (queued) met429 = true
                                reader.noteBusy(busyRun, error.retryAfter, count = queued)
                            } else {
                                true
                            }
                            if (tell) emit(MutationOutcome.Held(change.target, change.field, error))
                            break
                        }
                        FailureClass.Transport -> {
                            stoppedBy = error
                            break
                        }
                    }
                }
            }
        }
        val pending = outbox.pendingCount().toInt()
        busyRun.flushed(met429, sent, pending)
        MutationFlushReport(sent, saved, adopted, refused, superseded, deferred.size, stoppedBy, pending)
    } }

    private inline fun <T> confined(block: () -> T): T {
        reader.checkConfined()
        return block()
    }

    private fun emit(outcome: MutationOutcome) = outcomeListeners.toList().forEach { listener -> guarded(Unit) { listener(outcome) } }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        fallback
    }

    companion object {
        /** Per-change failures in a row before a change is dropped and the person told (ASSUMED). */
        const val MAX_FAILURES = 3
    }
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
 * One account's reader with its favourites and playlist editing wired in: the outbox is the
 * reader's overlay for both, and their flushes run first on reconnect — favourites, then playlist
 * changes, then [otherOutboxes] (the scrobble outbox) — before the epoch read (§16.14 step 1). This
 * is the assembly the shells' facades construct (R2a, R3). [formPost]: the account's server
 * advertises the OpenSubsonic `formPost` extension (§18.6).
 */
internal class LibraryReaderSession(
    database: DulcetDatabase,
    cache: BoundSeenCache,
    transport: LibraryEndpointTransport,
    scope: CoroutineScope,
    config: LibraryReaderConfig = LibraryReaderConfig(),
    downloads: DownloadedTrackSource = DownloadedTrackSource.None,
    otherOutboxes: ReconnectOutboxes = ReconnectOutboxes.None,
    /** Required: whether the account's server advertises the OpenSubsonic `formPost` extension (§18.6). */
    formPost: Boolean,
) {
    val outbox = MutationOutbox(database, cache)
    private lateinit var favouritesRef: LibraryFavourites
    private lateinit var playlistsRef: PlaylistEditor

    /**
     * Pending changes the bind that produced this session discarded because the account's USERNAME
     * changed (§16.10, §14.7). The shell tells the person once. A server-address change for the
     * same username keeps them.
     */
    val discardedPendingChanges: Long = cache.discardedPendingChanges

    val reader = LibraryReader(
        cache = cache,
        transport = transport,
        scope = scope,
        config = config,
        overlay = outbox,
        downloads = downloads,
        // Each flush is independent: one whose database fails must not skip the next, nor the
        // epoch read that follows (§16.14). What one throws is recorded, never raised.
        outboxes = ReconnectOutboxes {
            flushRecordingFailure { favouritesRef.flush() }
            flushRecordingFailure { playlistsRef.flush() }
            flushRecordingFailure { otherOutboxes.flush() }
        },
        playlistOverlay = object : LibraryPlaylistOverlay {
            override fun resolve(rawId: String) = playlistsRef.resolve(rawId)
            override fun isLocal(rawId: String) = playlistsRef.isLocal(rawId)
            override fun overlayList(items: List<LibraryItem>) = playlistsRef.overlayList(items)
            override fun overlayDetail(rawId: String, header: LibraryItem.Playlist?, entries: List<LibraryItem>?) =
                playlistsRef.overlayDetail(rawId, header, entries)
        },
    )

    val favourites = LibraryFavourites(reader, outbox).also { favouritesRef = it }

    /** Playlist editing (§18.6): create, rename, add, remove, reorder, delete — shown at once. */
    val playlists = PlaylistEditor(database, { reader }, formPost).also { playlistsRef = it }

    private val searches = mutableListOf<LibrarySearchSession>()

    private suspend fun flushRecordingFailure(flush: suspend () -> Unit) {
        try {
            flush()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            reader.uncaughtFailures += failure
        }
    }

    /** A search over this reader whose rows carry the same overlaid favourite state (§16.15). */
    fun openSearch(
        config: LibrarySearchConfig = LibrarySearchConfig(),
        listener: (LibrarySearchPublication) -> Unit,
    ): LibrarySearchSession {
        reader.checkConfined()
        val session = LibrarySearchSession(reader, config, listener)
        favourites.addChangeListener(session::republishPendingChanges)
        searches += session
        return session
    }

    /**
     * Reachability, as the platform reports it: the reader republishes its windows, and every open
     * search re-runs so its scope says `deviceOffline` (or merges the server again) without waiting
     * for a keystroke.
     */
    fun setOnline(reachable: Boolean) {
        reader.checkConfined()
        val changed = reader.online != reachable
        reader.setOnline(reachable)
        searches.removeAll { it.isClosed }
        if (changed) searches.toList().forEach(LibrarySearchSession::refresh)
    }
}
