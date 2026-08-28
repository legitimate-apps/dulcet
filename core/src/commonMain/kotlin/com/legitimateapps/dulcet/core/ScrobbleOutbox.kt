package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
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
            val attemptedEntries = mutableListOf<ScrobbleOutboxEntry>()
            outbox.pending(serverId).forEach { entry ->
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
                when (sender.send(ScrobbleEndpointRequest(event))) {
                    is ScrobbleSendResult.Sent -> {
                        attemptedEntries += entry
                        outbox.delete(entry)
                        delivered += 1
                        nextRetryAt = null
                    }
                    is ScrobbleSendResult.Failed -> {
                        val failed = outbox.recordFailedAttempt(entry)
                        attemptedEntries += failed
                        val delay = retryBackoff(failed.attemptCount)
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
            ScrobbleOutboxDeliveryResult(
                attemptedCount = attempted,
                deliveredCount = delivered,
                retentionDropCount = retentionDrops,
                nextRetryAfter = null,
            )
        }
}

private fun retryBackoff(attemptCount: Long): Duration {
    require(attemptCount > 0)
    val exponent = (attemptCount - 1).coerceAtMost(8).toInt()
    return (1.seconds * (1 shl exponent)).coerceAtMost(5.minutes)
}

internal val OUTBOX_RETENTION: Duration = 30.days
