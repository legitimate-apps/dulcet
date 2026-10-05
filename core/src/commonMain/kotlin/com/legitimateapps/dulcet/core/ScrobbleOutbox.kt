package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal fun interface OutboxWallClock {
    fun nowEpochMilliseconds(): Long
}

internal fun interface OutboxMonotonicClock {
    fun now(): Duration
}

internal data class ScrobbleOutboxEntry(
    val serverId: ServerId,
    val rawId: String,
    val sessionStartWallClock: PlaybackWallClockTime,
    val createdAtWallClock: PlaybackWallClockTime,
    val attemptCount: Long,
) {
    init {
        require(rawId.isNotBlank())
        require(attemptCount >= 0)
    }

    fun toSubmittedPlay(): RecordedPlaybackEvent.SubmittedPlay =
        RecordedPlaybackEvent.SubmittedPlay(
            itemId = ProviderItemId(serverId.value, rawId),
            sessionStartWallClock = sessionStartWallClock,
        )
}

internal enum class ScrobbleOutboxTrigger { Foreground, Reachable, RetryTimer }

internal sealed interface ScrobbleOutboxDiagnosticEvent {
    /** A product retention decision removed local user-authored play history. */
    data class ProductRetentionDropped(
        val entry: ScrobbleOutboxEntry,
        val droppedAtWallClock: PlaybackWallClockTime,
        val retentionLimit: Duration,
    ) : ScrobbleOutboxDiagnosticEvent

    /**
     * The server said, again and again, that it cannot take this one play (Subsonic error 70, "not
     * found": the track is gone), so the play was dropped and the plays behind it went on. Like
     * [ProductRetentionDropped] it removes user-authored play history, and so is never silent.
     */
    data class RefusedDropped(
        val entry: ScrobbleOutboxEntry,
        val refusals: Int,
        val serverCode: Int,
    ) : ScrobbleOutboxDiagnosticEvent

    data class DeliveryFailed(
        val entry: ScrobbleOutboxEntry,
        val trigger: ScrobbleOutboxTrigger,
        val nextRetryAfter: Duration,
    ) : ScrobbleOutboxDiagnosticEvent

    data class FutureTimestampClamped(
        val entry: ScrobbleOutboxEntry,
        val submittedAtWallClock: PlaybackWallClockTime,
    ) : ScrobbleOutboxDiagnosticEvent
}

internal fun interface ScrobbleOutboxDiagnosticSink {
    fun record(event: ScrobbleOutboxDiagnosticEvent)
}

internal data class ScrobbleOutboxDeliveryResult(
    val attemptedCount: Int,
    val deliveredCount: Int,
    val retentionDropCount: Int,
    val nextRetryAfter: Duration?,
    val refusedDropCount: Int = 0,
)

/**
 * Durable local hand-off for submitted plays. The primary key prevents two local rows for the same
 * event; it does not make the remote scrobble call idempotent. A response lost after the server
 * increments play count leaves this row pending, so a later retry can count the play twice. Delivery
 * is intentionally at-least-once because losing a play is worse than that possible duplicate.
 */
internal class PersistentScrobbleOutbox(
    private val database: DulcetDatabase,
    private val wallClock: OutboxWallClock,
) : SubmittedPlayOutboxSink {
    override suspend fun persistForAtLeastOnceDelivery(event: RecordedPlaybackEvent.SubmittedPlay) {
        persistSynchronously(event)
    }

    /** The app boundary uses this before returning from an engine-event ingestion call. */
    fun persistSynchronously(event: RecordedPlaybackEvent.SubmittedPlay) {
        database.scrobbleOutboxQueries.enqueue(
            server_id = event.itemId.providerInstanceId,
            raw_id = event.itemId.rawId,
            session_start_wall_clock = event.sessionStartWallClock.epochMilliseconds,
            created_at_wall_clock = wallClock.nowEpochMilliseconds(),
        )
    }

    fun pending(serverId: ServerId): List<ScrobbleOutboxEntry> =
        database.scrobbleOutboxQueries.selectPendingForServer(serverId.value, ::mapEntry)
            .executeAsList()

    fun dropExpired(
        serverId: ServerId,
        nowWallClock: PlaybackWallClockTime,
        diagnosticSink: ScrobbleOutboxDiagnosticSink,
        eligibleEntries: Collection<ScrobbleOutboxEntry>? = null,
    ): Int {
        val cutoff = nowWallClock.epochMilliseconds - OUTBOX_RETENTION.inWholeMilliseconds
        val expiredForServer = database.scrobbleOutboxQueries.selectExpiredBefore(
            server_id = serverId.value,
            created_at_wall_clock = cutoff,
            mapper = ::mapEntry,
        ).executeAsList()
        val expired = if (eligibleEntries == null) {
            expiredForServer
        } else {
            expiredForServer.filter { expiredEntry ->
                eligibleEntries.any { eligible -> eligible.sameEventAs(expiredEntry) }
            }
        }
        if (expired.isEmpty()) return 0
        database.transaction {
            expired.forEach(::delete)
        }
        expired.forEach { entry ->
            diagnosticSink.record(
                ScrobbleOutboxDiagnosticEvent.ProductRetentionDropped(
                    entry = entry,
                    droppedAtWallClock = nowWallClock,
                    retentionLimit = OUTBOX_RETENTION,
                ),
            )
        }
        return expired.size
    }

    fun delete(entry: ScrobbleOutboxEntry) {
        database.scrobbleOutboxQueries.deleteEntry(
            entry.serverId.value,
            entry.rawId,
            entry.sessionStartWallClock.epochMilliseconds,
        )
    }

    fun recordFailedAttempt(entry: ScrobbleOutboxEntry): ScrobbleOutboxEntry {
        database.scrobbleOutboxQueries.recordFailedAttempt(
            entry.serverId.value,
            entry.rawId,
            entry.sessionStartWallClock.epochMilliseconds,
        )
        val attempts = database.scrobbleOutboxQueries.selectAttemptCount(
            entry.serverId.value,
            entry.rawId,
            entry.sessionStartWallClock.epochMilliseconds,
        ).executeAsOne()
        return entry.copy(attemptCount = attempts)
    }

    internal fun count(): Long = database.scrobbleOutboxQueries.countAll().executeAsOne()

    private fun mapEntry(
        serverId: String,
        rawId: String,
        sessionStart: Long,
        createdAt: Long,
        attemptCount: Long,
    ): ScrobbleOutboxEntry = ScrobbleOutboxEntry(
        serverId = ServerId(serverId),
        rawId = rawId,
        sessionStartWallClock = PlaybackWallClockTime(sessionStart),
        createdAtWallClock = PlaybackWallClockTime(createdAt),
        attemptCount = attemptCount,
    )
}

private fun ScrobbleOutboxEntry.sameEventAs(other: ScrobbleOutboxEntry): Boolean =
    serverId == other.serverId &&
        rawId == other.rawId &&
        sessionStartWallClock == other.sessionStartWallClock

/**
 * What a failed send says about the play it carried (spec §15.3): the code of a refusal that is
 * about this play alone, or null for every failure that is not.
 *
 * Only error 70, "the requested data was not found", is one: the server answered, and the track
 * is gone, so no retry of this request can succeed and the plays behind it are unaffected. Every
 * other failure is the transport's, the server's or the account's, not the play's, and the queue
 * stops, keeps its order and backs off: no answer, a timeout, a 5xx, a 429 with its `Retry-After`,
 * a malformed answer, the generic error 0, a missing parameter (10, the request's shape, the
 * same for every play), an upgrade request (20, 30) and every refusal of the account that a
 * reconnect or a new password can fix (40, 41, 50, 60, a TLS or redirect refusal). Dropping on any
 * of those would lose every play behind it for a fault that is not theirs.
 */
private fun ScrobbleSendResult.Failed.refusedPlayCode(): Int? =
    (error as? DomainError.Server.Known)?.code?.takeIf { it == SUBSONIC_DATA_NOT_FOUND }

/** The OpenSubsonic error code for "the requested data was not found". */
private const val SUBSONIC_DATA_NOT_FOUND = 70

/**
 * The refusals of one play that stop its retries, and how long the worker waits between them. A
 * server that is mid-rescan can answer "not found" for a track that is back a minute later, so one
 * refusal is not the end of a play; three, one minute and then five minutes apart (both ASSUMED),
 * are. The count is the worker's, per process: a restart gives the play three more.
 */
internal const val OUTBOX_REFUSALS_BEFORE_DROP: Int = 3
private val FIRST_REFUSAL_WAIT: Duration = 1.minutes

private fun refusalWait(refusals: Int): Duration =
    if (refusals <= 1) FIRST_REFUSAL_WAIT else OUTBOX_RETRY_CEILING

/** Serial durable delivery entry point for platform foreground, reachability, and timer callbacks. */
internal class ScrobbleOutboxDeliveryWorker(
    private val serverId: ServerId,
    private val outbox: PersistentScrobbleOutbox,
    private val sender: ScrobbleEndpointSender,
    private val wallClock: OutboxWallClock,
    private val monotonicClock: OutboxMonotonicClock,
    private val diagnosticSink: ScrobbleOutboxDiagnosticSink,
) {
    private val mutex = Mutex()
    private var nextRetryAt: Duration? = null

    /** A play the server refused as its own, waiting for its next try: [retryAt] is on the monotonic clock. */
    private class Refused(var count: Int, var retryAt: Duration)

    private val refused = mutableMapOf<RefusedPlayKey, Refused>()

    suspend fun onForeground(): ScrobbleOutboxDeliveryResult =
        drain(ScrobbleOutboxTrigger.Foreground)

    suspend fun onReachable(): ScrobbleOutboxDeliveryResult =
        drain(ScrobbleOutboxTrigger.Reachable)

    suspend fun onRetryTimer(): ScrobbleOutboxDeliveryResult =
        drain(ScrobbleOutboxTrigger.RetryTimer)

    private suspend fun drain(trigger: ScrobbleOutboxTrigger): ScrobbleOutboxDeliveryResult =
        mutex.withLock {
            val now = monotonicClock.now()
            val retryAt = nextRetryAt
            if (retryAt != null && now < retryAt) {
                return@withLock ScrobbleOutboxDeliveryResult(
                    attemptedCount = 0,
                    deliveredCount = 0,
                    retentionDropCount = 0,
                    nextRetryAfter = retryAt - now,
                )
            }

            var attempted = 0
            var delivered = 0
            var refusedDrops = 0
            val attemptedEntries = mutableListOf<ScrobbleOutboxEntry>()
            val pending = outbox.pending(serverId)
            refused.keys.retainAll(pending.map { it.refusedKey() }.toSet())
            pending.forEach { entry ->
                val key = entry.refusedKey()
                val waiting = refused[key]
                if (waiting != null && monotonicClock.now() < waiting.retryAt) {
                    // Refused as its own a moment ago: it waits its turn, and the plays behind it do not.
                    return@forEach
                }
                attempted += 1
                val submittedAt = PlaybackWallClockTime(wallClock.nowEpochMilliseconds())
                val event = if (entry.sessionStartWallClock.epochMilliseconds > submittedAt.epochMilliseconds) {
                    diagnosticSink.record(
                        ScrobbleOutboxDiagnosticEvent.FutureTimestampClamped(
                            entry = entry,
                            submittedAtWallClock = submittedAt,
                        ),
                    )
                    RecordedPlaybackEvent.SubmittedPlay(
                        itemId = ProviderItemId(entry.serverId.value, entry.rawId),
                        sessionStartWallClock = submittedAt,
                    )
                } else {
                    entry.toSubmittedPlay()
                }
                val sent = sender.send(ScrobbleEndpointRequest(event))
                when (sent) {
                    is ScrobbleSendResult.Sent -> {
                        attemptedEntries += entry
                        outbox.delete(entry)
                        refused.remove(key)
                        delivered += 1
                        nextRetryAt = null
                    }
                    is ScrobbleSendResult.Failed -> {
                        val failed = outbox.recordFailedAttempt(entry)
                        attemptedEntries += failed
                        val refusedCode = sent.refusedPlayCode()
                        if (refusedCode != null) {
                            val refusals = (refused[key]?.count ?: 0) + 1
                            if (refusals >= OUTBOX_REFUSALS_BEFORE_DROP) {
                                outbox.delete(failed)
                                refused.remove(key)
                                refusedDrops += 1
                                diagnosticSink.record(
                                    ScrobbleOutboxDiagnosticEvent.RefusedDropped(
                                        entry = failed,
                                        refusals = refusals,
                                        serverCode = refusedCode,
                                    ),
                                )
                            } else {
                                val wait = refusalWait(refusals)
                                refused[key] = Refused(refusals, monotonicClock.now() + wait)
                                diagnosticSink.record(
                                    ScrobbleOutboxDiagnosticEvent.DeliveryFailed(
                                        entry = failed,
                                        trigger = trigger,
                                        nextRetryAfter = wait,
                                    ),
                                )
                            }
                            return@forEach
                        }
                        val delay = retryDelay(failed.attemptCount, sent)
                        nextRetryAt = monotonicClock.now() + delay
                        diagnosticSink.record(
                            ScrobbleOutboxDiagnosticEvent.DeliveryFailed(
                                entry = failed,
                                trigger = trigger,
                                nextRetryAfter = delay,
                            ),
                        )
                        val retentionDrops = outbox.dropExpired(
                            serverId = serverId,
                            nowWallClock = PlaybackWallClockTime(wallClock.nowEpochMilliseconds()),
                            diagnosticSink = diagnosticSink,
                            eligibleEntries = attemptedEntries,
                        )
                        return@withLock ScrobbleOutboxDeliveryResult(
                            attemptedCount = attempted,
                            deliveredCount = delivered,
                            retentionDropCount = retentionDrops,
                            nextRetryAfter = delay,
                            refusedDropCount = refusedDrops,
                        )
                    }
                }
            }
            val retentionDrops = outbox.dropExpired(
                serverId = serverId,
                nowWallClock = PlaybackWallClockTime(wallClock.nowEpochMilliseconds()),
                diagnosticSink = diagnosticSink,
                eligibleEntries = attemptedEntries,
            )
            // A play refused as its own and not yet dropped is tried again when its wait ends.
            if (retentionDrops > 0) {
                refused.keys.retainAll(outbox.pending(serverId).map { it.refusedKey() }.toSet())
            }
            val dueIn = refused.values.minOfOrNull { it.retryAt }?.let { (it - monotonicClock.now()).coerceAtLeast(Duration.ZERO) }
            ScrobbleOutboxDeliveryResult(
                attemptedCount = attempted,
                deliveredCount = delivered,
                retentionDropCount = retentionDrops,
                nextRetryAfter = dueIn,
                refusedDropCount = refusedDrops,
            )
        }
}

private data class RefusedPlayKey(val rawId: String, val sessionStartEpochMilliseconds: Long)

private fun ScrobbleOutboxEntry.refusedKey() =
    RefusedPlayKey(rawId, sessionStartWallClock.epochMilliseconds)

private fun retryBackoff(attemptCount: Long): Duration {
    require(attemptCount > 0)
    val exponent = (attemptCount - 1).coerceAtMost(8).toInt()
    return (1.seconds * (1 shl exponent)).coerceAtMost(OUTBOX_RETRY_CEILING)
}

/**
 * The wait after a failed send: the exponential backoff, or a rate-limited server's `Retry-After`
 * when that is longer, and never more than [OUTBOX_RETRY_CEILING] whatever the server says (§18.6:
 * `max(Retry-After, floor)`, capped at five minutes).
 */
private fun retryDelay(attemptCount: Long, failure: ScrobbleSendResult.Failed): Duration {
    val asked = (failure.error as? DomainError.Server.Busy)?.retryAfter ?: Duration.ZERO
    return maxOf(retryBackoff(attemptCount), asked).coerceAtMost(OUTBOX_RETRY_CEILING)
}

/**
 * Keeps one outbox draining while the process lives (spec §15.3): after a drain that leaves a play
 * unsent it waits out the worker's own backoff on the platform's scheduler and drains again, so a
 * failed submission is not left until the next launch, foreground or reachability event. It runs
 * on a single-threaded scope (the main thread on both platforms); the worker's mutex serialises the
 * drains themselves. Every drain, whoever started it, reports through [onDrained]; one that threw
 * (a storage failure) reports through [onFailed] and is retried on the same backoff.
 */
internal class ScrobbleOutboxRetryLoop(
    private val scope: CoroutineScope,
    private val drain: suspend () -> ScrobbleOutboxDeliveryResult,
    private val onDrained: (ScrobbleOutboxDeliveryResult) -> Unit = {},
    private val onFailed: () -> Unit = {},
) {
    private var timer: Job? = null
    private var consecutiveFailures = 0L
    private var closed = false

    private class ArmedTimer { var job: Job? = null; var fired = false }
    private val armed = mutableListOf<ArmedTimer>()

    /**
     * Timers armed and neither fired nor cancelled: a test hook. A second live timer is a stacked
     * one (two retries for one wait), which no request count can show, because the duplicates fire
     * together and find the row sent or the worker gated.
     */
    internal val liveTimers: Int
        get() {
            armed.removeAll { it.fired || it.job?.isActive != true }
            return armed.size
        }

    suspend fun drainNow() {
        if (closed) return
        val wait = try {
            val result = drain()
            consecutiveFailures = 0
            onDrained(result)
            result.nextRetryAfter
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            consecutiveFailures += 1
            onFailed()
            retryBackoff(consecutiveFailures)
        }
        // A drain suspended in a send when cancel() ran finishes here, on a scope that is still
        // alive: without this check its failure would re-arm the loop that was just closed.
        if (closed) return
        timer?.cancel()
        timer = wait?.let { delayFor ->
            armed.removeAll { it.fired || it.job?.isActive != true }
            val entry = ArmedTimer()
            armed += entry
            scope.launch {
                delay(delayFor)
                entry.fired = true
                timer = null
                drainNow()
            }.also { entry.job = it }
        }
    }

    /** Ends the loop for good: no timer is armed again, and a drain still in flight arms none. */
    fun cancel() {
        closed = true
        timer?.cancel()
        timer = null
    }
}

internal val OUTBOX_RETENTION: Duration = 30.days

/** The longest the outbox waits between two attempts, whatever the backoff or `Retry-After` says. */
internal val OUTBOX_RETRY_CEILING: Duration = 5.minutes
