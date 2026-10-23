package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal data class StoredWishIntent(val mutation: WishMutation, val rowKey: String, val targetKey: String,
    val status: String, val error: DiscoverFailure?)

/** Confined to the account's database owner. Protected intent/receipts never participate in LRU. */
internal class DiscoverStore(
    private val database: DulcetDatabase,
    val serverId: String,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val jsonLimit: Long = 16L * 1024 * 1024,
    private val artworkLimit: Long = 32L * 1024 * 1024,
) {
    private val cache get() = database.discoverQueries
    private val wishes get() = database.wishlistQueries
    init { require(serverId.isNotBlank() && jsonLimit >= 0 && artworkLimit >= 0) }

    fun nextIssue(): Long = database.transactionWithResult {
        wishes.ensureClock(serverId)
        val old = wishes.selectClock(serverId).executeAsOne()
        check(old.last_sequence < Long.MAX_VALUE)
        (old.last_sequence + 1).also { wishes.updateClock(old.last_action_time, it, serverId) }
    }
    fun nextActionTime(): Long = database.transactionWithResult {
        wishes.ensureClock(serverId)
        val old = wishes.selectClock(serverId).executeAsOne()
        check(old.last_action_time < Long.MAX_VALUE)
        maxOf(wall(),old.last_action_time + 1).also { wishes.updateClock(it,old.last_sequence,serverId) }
    }
    fun read(key: String): ByteArray? = database.transactionWithResult {
        val row = cache.selectRead(serverId,key).executeAsOneOrNull() ?: return@transactionWithResult null
        cache.touchRead(nextIssue(),serverId,key)
        row.payload
    }
    fun putRead(key: String, bytes: ByteArray, artwork: Boolean, issue: Long) = database.transaction {
        val old = cache.selectRead(serverId,key).executeAsOneOrNull()
        if (old != null && old.issue_seq >= issue) return@transaction
        val kind = if (artwork) 1L else 0L
        cache.upsertRead(serverId,key,bytes,kind,issue,nextIssue())
        val ceiling = if (artwork) artworkLimit else jsonLimit
        while (cache.readBytes(serverId,kind).executeAsOne() > ceiling) {
            cache.deleteRead(serverId,cache.oldestRead(serverId,kind).executeAsOne())
        }
    }
    fun snapshot(filter: WishFilter): WishListSnapshot? = wishes.selectSnapshot(serverId,Json.encodeToString(filter))
        .executeAsOneOrNull()?.let { Json.decodeFromString<WishListSnapshot>(it.payload) }
    fun replaceSnapshot(filter: WishFilter, snapshot: WishListSnapshot, issue: Long) = database.transaction {
        val key=Json.encodeToString(filter)
        val old=wishes.selectSnapshot(serverId,key).executeAsOneOrNull()
        if (old == null || old.issue_seq < issue) wishes.upsertSnapshot(serverId,key,Json.encodeToString(snapshot),issue)
    }
    fun intents(): List<StoredWishIntent> = wishes.selectIntents(serverId).executeAsList().map {
        StoredWishIntent(Json.decodeFromString(it.payload),it.row_key,it.target_key,it.status,
            it.error?.let { code -> DiscoverFailure(code,null,false) })
    }
    fun pending(): List<StoredWishIntent> = intents().filter { it.status == "pending" }
    fun pendingCount(): Long? = try { wishes.countPending(serverId).executeAsOne() } catch (_: Exception) { null }

    suspend fun enqueue(mutation: WishMutation, rowKey: String = defaultKey(mutation), targetKey: String = mutationTarget(mutation)): DiscoverResult<WishEnqueued> {
        currentCoroutineContext().ensureActive()
        return try {
            database.transactionWithResult {
                val payload=Json.encodeToString(mutation)
                val old=wishes.selectIntent(serverId,mutation.operationId).executeAsOneOrNull()
                if (old != null) {
                    if (old.payload != payload) return@transactionWithResult DiscoverResult.Failed(DiscoverFailure("conflict",null,false))
                    return@transactionWithResult DiscoverResult.Value(WishEnqueued(mutation.operationId,old.row_key))
                }
                val ordinal=nextIssue()
                val actionTime=when(mutation) { is WishMutation.Add -> mutation.actionTimeMillis; is WishMutation.Remove -> mutation.actionTimeMillis; is WishMutation.Reject -> 0L }
                val clock=wishes.selectClock(serverId).executeAsOne()
                wishes.updateClock(maxOf(clock.last_action_time,actionTime),clock.last_sequence,serverId)
                wishes.insertIntent(serverId,mutation.operationId,rowKey,targetKey,payload,ordinal)
                DiscoverResult.Value(WishEnqueued(mutation.operationId,rowKey))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { DiscoverResult.Failed(DiscoverFailure("storage",null,true)) }
    }
    fun acknowledge(operationId: String, receipt: WishReceipt) = database.transaction {
        // Retain immutable history so a late generation cannot hide an intentional re-add.
        wishes.upsertReceipt(serverId,operationId,receipt.wish?.id,Json.encodeToString(receipt))
        wishes.updateIntent("acknowledged",null,serverId,operationId)
    }
    fun refuse(operationId: String, failure: DiscoverFailure) = wishes.updateIntent("failed",failure.code,serverId,operationId)
    fun receipt(operationId: String): WishReceipt? = wishes.selectReceipt(serverId,operationId).executeAsOneOrNull()?.let { Json.decodeFromString(it.payload) }
    fun rowKey(wishId: String): String = wishes.selectReceiptsForWish(serverId,wishId).executeAsList()
        .mapNotNull { receipt -> wishes.selectIntent(serverId,receipt.operation_id).executeAsOneOrNull() }
        .lastOrNull()?.row_key ?: "wish:$wishId"
    private fun defaultKey(mutation: WishMutation): String = when(mutation) {
        is WishMutation.Add -> "add:${mutation.operationId}"
        is WishMutation.Remove -> mutation.addOperationId?.let { "add:$it" } ?: rowKey(mutation.wishId ?: "")
        is WishMutation.Reject -> mutation.wishId?.let(::rowKey) ?: "reject:${mutation.operationId}"
    }
    private fun mutationTarget(mutation: WishMutation): String = when(mutation) {
        is WishMutation.Add -> discoverTargetKey(mutation.target)
        is WishMutation.Reject -> discoverTargetKey(mutation.target)
        is WishMutation.Remove -> intents().firstOrNull { it.mutation.operationId == mutation.addOperationId || receipt(it.mutation.operationId)?.wish?.id == mutation.wishId }?.targetKey ?: "wish:${mutation.wishId}"
    }
}
internal fun discoverTargetKey(target: DiscoverTarget): String = Json.encodeToString(target)
