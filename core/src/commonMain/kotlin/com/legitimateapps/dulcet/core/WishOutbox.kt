@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Durable wish generations. Admission is supplied by the owning reader, never by reachability here. */
internal class WishOutbox(
    private val store: DiscoverStore,
    private val transport: WishlistTransport,
    private val access: ()->DiscoverFeatureAccess,
    private val active: ()->Boolean = {true},
    private val canSend: ()->Boolean = {true},
    private val uuid: ()->String = {Uuid.random().toString()},
    private val backoff: DiscoverBackoff? = null,
    private val changed: ()->Unit = {},
    private val captureAuthorization: ()->(()->Boolean) = { active },
) {
    var lastError: DiscoverFailure?=null;private set
    private val lock=Mutex()
    private var closed=false
    private fun admitted()=!closed && active()
    suspend fun enqueueAdd(item: DiscoverItem,source: WishSource): DiscoverResult<WishEnqueued> = enqueue {
        if(!access().canAddWish) return@enqueue failure("unsupported")
        val target=item.target ?: return@enqueue failure("invalid-response")
        if(!item.wishable) return@enqueue failure("invalid-response")
        store.enqueue(WishMutation.Add(uuid(),store.nextActionTime(),target,WishSnapshot(item.title,item.artist,item.year,item.artworkId),source))
    }
    suspend fun enqueueRemove(rowKey: String): DiscoverResult<WishEnqueued> = enqueue {
        if(!access().canRemoveWish) return@enqueue failure("unsupported")
        val add=store.intents().lastOrNull {it.rowKey==rowKey && it.mutation is WishMutation.Add}
        val wish=store.knownWish(rowKey)
        if(add==null && wish==null) return@enqueue failure("invalid-response")
        val targetKey=add?.targetKey ?: wish?.target?.let(::discoverTargetKey) ?: "wish:${wish!!.id}"
        val operation=WishMutation.Remove(uuid(),store.nextActionTime(),wish?.id,if(wish==null) add?.mutation?.operationId else null)
        store.enqueue(operation,rowKey,targetKey)
    }
    suspend fun enqueueReject(item: DiscoverItem,libraryItem: DiscoverLibraryRef,wishId: String?): DiscoverResult<WishEnqueued> = enqueue {
        if(!access().canRejectMatch) return@enqueue failure("unsupported")
        if(libraryItem.providerInstanceId!=store.serverId || item.target==null) return@enqueue failure("invalid-response")
        store.enqueue(WishMutation.Reject(uuid(),item.target,libraryItem,wishId))
    }
    private suspend fun enqueue(block: suspend ()->DiscoverResult<WishEnqueued>): DiscoverResult<WishEnqueued> {
        if(!admitted()) return failure("cancelled")
        return try { block().also { if(it is DiscoverResult.Value && admitted()) changed() } }
        catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {DiscoverResult.Failed(DiscoverFailure("storage",null,true))}
    }
    suspend fun flush() {
        try { flushChecked() }
        catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {lastError=DiscoverFailure("storage",null,true);if(admitted()) changed()}
    }
    private suspend fun flushChecked() = lock.withLock {
        // Renewing the account must never authorize a reply from a retired flush.
        val authorized=captureAuthorization()
        while(admitted() && authorized() && canSend() && backoff?.allowed()!=false) {
            val gates=access()
            if(gates.detection!="known") return@withLock
            val pending=store.pending()
            // An unresolved cancellation is delivered ahead of the add it cancels.
            val intent=pending.firstOrNull {it.mutation is WishMutation.Remove && it.mutation.addOperationId!=null}
                ?: pending.firstOrNull() ?: return@withLock
            val allowed=when(intent.mutation) {
                is WishMutation.Add -> gates.canAddWish
                is WishMutation.Remove -> gates.canRemoveWish
                is WishMutation.Reject -> gates.canRejectMatch
            }
            if(!allowed) {store.refuse(intent.mutation.operationId,DiscoverFailure("unsupported",null,false));changed();continue}
            if(!admitted() || !authorized() || !canSend()) return@withLock
            lastError=null
            val result=transport.apply(intent.mutation)
            if(!admitted() || !authorized() || !canSend()) return@withLock
            when(result) {
                is DiscoverResult.Value -> {
                    val receipt=result.value
                    if(intent.mutation is WishMutation.Add && receipt.wish==null && !receipt.removed) {lastError=DiscoverFailure("invalid-response",null,true);return@withLock}
                    store.acknowledge(intent.mutation.operationId,receipt)
                    if(intent.mutation is WishMutation.Remove && receipt.removed) {
                        intent.mutation.addOperationId?.let {store.acknowledge(it,WishReceipt(false,null,true))}
                    }
                    changed()
                }
                is DiscoverResult.Failed -> {
                    lastError=result.error
                    backoff?.note(result.error)
                    // Authentication or an ambiguous response is not proof a write was refused.
                    if(result.error.retryable || result.error.code in setOf("unauthorized","offline","rate-limited")) return@withLock
                    store.refuse(intent.mutation.operationId,result.error);changed()
                }
            }
        }
    }
    fun close() {closed=true}
    fun overlay(snapshot: WishListSnapshot?, readIssue: Long = 0, filter: WishFilter = WishFilter()): List<WishRow> {
        val intents=store.intents()
        val effective=intents.filter {it.status!="failed" && it.mutation !is WishMutation.Reject && (it.status=="pending" || store.receiptIssue(it.mutation.operationId)>readIssue)}
        val latest=effective.groupBy {it.targetKey}.mapValues {it.value.last()}
        val rows=snapshot?.items.orEmpty().map {WishRow(store.rowKey(it.id),it,null,false,null)}.toMutableList()
        rows.removeAll {row ->
            val wish=row.wish!!
            val target=wish.target?.let(::discoverTargetKey) ?: "wish:${wish.id}"
            val last=latest[target]
            when(val mutation=last?.mutation) {
                is WishMutation.Remove -> last.status=="pending" || store.receipt(mutation.operationId)?.removed==true
                is WishMutation.Add -> store.receipt(mutation.operationId)?.let {it.removed || (it.wish!=null && it.wish.id!=wish.id)} ?: false
                else -> effective.any {it.mutation is WishMutation.Remove && it.mutation.wishId==wish.id}
            }
        }
        for(intent in intents) {
            val mutation=intent.mutation
            if(mutation is WishMutation.Add) {
                if(intent.status=="failed") {
                    rows+=WishRow(intent.rowKey,null,mutation,false,intent.error)
                } else if(latest[intent.targetKey]==intent) {
                    val receipt=store.receipt(mutation.operationId)
                    if(receipt?.removed==true) continue
                    if(intent.status=="pending" || store.receiptIssue(mutation.operationId)>readIssue) {
                        rows.removeAll {it.wish?.target==mutation.target || it.key==intent.rowKey}
                        rows+=WishRow(intent.rowKey,if(intent.status=="pending") null else receipt?.wish,if(intent.status=="pending") mutation else null,intent.status=="pending",null)
                    }
                }
            } else if(mutation is WishMutation.Reject && intent.status!="failed" && (intent.status=="pending" || store.receiptIssue(mutation.operationId)>readIssue)) {
                if(latest[intent.targetKey]?.let {intents.indexOf(it)>intents.indexOf(intent)}==true) continue
                val index=rows.indexOfFirst {if(mutation.wishId!=null) it.wish?.id==mutation.wishId else it.wish?.target==mutation.target}
                if(index>=0) {
                    val receipt=store.receipt(mutation.operationId)
                    rows[index]=rows[index].copy(wish=if(intent.status=="pending") rows[index].wish else receipt?.wish ?: rows[index].wish,pending=intent.status=="pending")
                }
            } else if(intent.status=="failed") {
                val index=rows.indexOfFirst {it.key==intent.rowKey}
                if(index>=0) rows[index]=rows[index].copy(error=intent.error)
            }
        }
        return rows.filter {row ->
            val kind=row.wish?.kind ?: row.pendingAdd?.target?.kind?.wire
            val state=row.wish?.state ?: "wanted"
            (filter.kind==null || filter.kind==kind) && (filter.state==null || filter.state==state)
        }.sortedWith(compareByDescending<WishRow> {it.pendingAdd?.actionTimeMillis ?: it.wish?.addedAtMillis ?: 0}.thenBy {it.key})
    }
    private fun failure(code: String)=DiscoverResult.Failed(DiscoverFailure(code,null,false))
}
