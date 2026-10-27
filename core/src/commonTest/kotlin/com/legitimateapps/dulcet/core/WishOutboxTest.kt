@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

internal fun allDiscoverAccess()=DiscoverCapabilityResolver.resolve(mapOf("dulcetDiscover" to setOf(1),"dulcetWishlist" to setOf(1)),true)
internal class WishlistTestWire : WishlistTransport {
    val sent=mutableListOf<WishMutation>()
    val receipts=mutableMapOf<String,WishReceipt>()
    val removed=mutableSetOf<String>()
    var wish:DiscoverWish?=null
    var member=false
    var loseNextAdd=false
    var failure:DiscoverFailure?=null
    var onApply:(suspend (WishMutation)->Unit)?=null
    var snapshotFailure:DiscoverFailure?=null
    var snapshotGate:CompletableDeferred<Unit>?=null
    override suspend fun snapshot(filter:WishFilter):DiscoverResult<WishListSnapshot> {
        val captured=WishListSnapshot(listOfNotNull(wish).filter {filter.state==null || filter.state==it.state},"clock")
        snapshotGate?.let {withContext(NonCancellable){it.await()}}
        return snapshotFailure?.let {DiscoverResult.Failed(it)} ?: DiscoverResult.Value(captured)
    }
    override suspend fun wish(id:String):DiscoverResult<DiscoverWish> = wish?.let {DiscoverResult.Value(it)} ?: DiscoverResult.Failed(DiscoverFailure("unsupported",null,false))
    override suspend fun apply(mutation:WishMutation):DiscoverResult<WishReceipt> {
        sent+=mutation;onApply?.invoke(mutation)
        failure?.let {return DiscoverResult.Failed(it)}
        val result=receipts.getOrPut(mutation.operationId) {
            when(mutation) {
                is WishMutation.Add -> if(mutation.operationId in removed) WishReceipt(false,null,true) else {
                    if(wish==null) wish=sampleWish("wish:${mutation.operationId}",mutation.target)
                    WishReceipt(true,wish,false)
                }
                is WishMutation.Remove -> {
                    mutation.addOperationId?.let {removed+=it}
                    if(mutation.wishId==null || wish?.id==mutation.wishId) wish=null
                    WishReceipt(true,null,true)
                }
                is WishMutation.Reject -> {
                    if(mutation.wishId==wish?.id) wish=wish?.copy(state="wanted",fulfilledAtMillis=null,libraryItem=null)
                    WishReceipt(true,wish,false)
                }
            }
        }
        if(mutation is WishMutation.Add && loseNextAdd) {loseNextAdd=false;return DiscoverResult.Failed(DiscoverFailure("offline",null,true))}
        return DiscoverResult.Value(result)
    }
    fun scan() {member=true;wish=wish?.copy(state="fulfilled",fulfilledAtMillis=2000,libraryItem=DiscoverLibraryRef("account-A","song","local"))}
}
internal fun outbox(store:DiscoverStore,wire:WishlistTestWire,active:()->Boolean={true},canSend:()->Boolean={true},access:()->DiscoverFeatureAccess={allDiscoverAccess()}):WishOutbox {
    var next=store.intents().size
    return WishOutbox(store,wire,access,active,canSend,uuid={"operation-${++next}"})
}
class WishOutboxTest {
    @Test fun malformedMutationReceiptRetainsImmutableIntent() = readerTest { env ->
        val wire=DiscoverWireFixture().apply {json={_,_ ->discoverEnvelope(""""dulcetWishMutation":{}""")}}
        val reader=env.reader(transport=wire)
        val store=DiscoverStore(com.legitimateapps.dulcet.database.DulcetDatabase(env.driver),ReaderEnv.BINDING.serverId)
        val out=WishOutbox(store,RestWishlistTransport(reader),{allDiscoverAccess()})
        out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null));out.flush()
        assertEquals(1L,store.pendingCount())
        assertEquals("invalid-response",out.lastError?.code)
    }
    @Test fun storageFailureDoesNotEscapeFlushOrBecomeZero() = runTest {
        val driver=createTestDriver();val store=DiscoverStore(DulcetDatabaseStore.open(driver).database,"A")
        val out=outbox(store,WishlistTestWire());driver.close()
        out.flush()
        assertEquals("storage",out.lastError?.code);assertNull(store.pendingCount())
    }
    @Test fun pendingCancellationDoesNotHideAnotherTarget() = discoverStoreTest { env ->
        val store=env.store();val a=sampleAdd();val c=sampleAdd("C").copy(target=DiscoverTarget(DiscoverKind.Song,"22222222-2222-4222-8222-222222222222"))
        store.enqueue(a);store.enqueue(c);store.enqueue(WishMutation.Remove("R",2000,null,"C"))
        assertEquals(discoverTargetKey(c.target),store.pending().last().targetKey)
        val rows=outbox(store,WishlistTestWire()).overlay(WishListSnapshot(emptyList(),"clock"))
        assertEquals(listOf("add:${a.operationId}"),rows.map {it.key})
    }

    @Test fun lostAddThenRemoveBeforeReceiptSurvivesRestart() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {loseNextAdd=true};val store=env.store();val first=outbox(store,wire)
        val add=assertIs<DiscoverResult.Value<WishEnqueued>>(first.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        first.flush();assertNotNull(wire.wish);assertEquals(1L,store.pendingCount())
        assertIs<DiscoverResult.Value<WishEnqueued>>(first.enqueueRemove(add.rowKey))
        val remove=assertIs<WishMutation.Remove>(store.pending().last().mutation)
        assertEquals(add.operationId,remove.addOperationId)
        env.now+=86400001;wire.receipts.clear()
        outbox(env.store(),wire).flush()
        assertNull(wire.wish);assertEquals(0L,store.pendingCount())
        assertEquals(remove,wire.sent.last())
        assertTrue(outbox(env.store(),wire).overlay(WishListSnapshot(emptyList(),"clock")).isEmpty())
    }
    @Test fun fulfilmentRacingRemovalStaysRemoved() = discoverStoreTest { env ->
        val wire=WishlistTestWire();val store=env.store();val out=outbox(store,wire)
        val add=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        out.flush();wire.scan();assertTrue(wire.member)
        out.enqueueRemove(add.rowKey)
        assertTrue(out.overlay(WishListSnapshot(listOf(wire.wish!!),"arrived")).isEmpty())
        out.flush();assertNull(wire.wish);assertTrue(wire.member)
    }
    @Test fun losingRemovalReceiptRestoresServerWishInsteadOfHidingIt() = discoverStoreTest { env ->
        val store=env.store();val wire=WishlistTestWire();val out=outbox(store,wire)
        val a=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        out.flush();val wish=wire.wish!!;val issue=store.nextIssue()
        out.enqueueRemove(a.rowKey)
        val remove=store.pending().single().mutation
        store.acknowledge(remove.operationId,WishReceipt(false,wish,false))
        assertEquals(wish.id,out.overlay(WishListSnapshot(listOf(wish),"old"),issue).single().wish?.id)
    }
    @Test fun oldRemovalCannotDeleteIntentionalReadd() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {loseNextAdd=true};val store=env.store();val out=outbox(store,wire)
        val a=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        out.flush();out.enqueueRemove(a.rowKey)
        val b=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("album",null))).value
        out.flush()
        assertEquals("wish:${b.operationId}",wire.wish?.id)
        val rows=out.overlay(WishListSnapshot(listOf(wire.wish!!),"new"))
        assertEquals(listOf(b.rowKey),rows.map {it.key})
        assertTrue(wire.sent.filterIsInstance<WishMutation.Remove>().all {it.addOperationId==a.operationId})
    }
    @Test fun accountSwitchStopsAAndBDoesNotDrainA() = discoverStoreTest { env ->
        val wire=WishlistTestWire();var active=true
        val held=CompletableDeferred<Unit>();wire.onApply={held.await()}
        val a=outbox(env.store(),wire,{active})
        a.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))
        val flush=launch {a.flush()};runCurrent();assertEquals(1,wire.sent.size)
        active=false;held.complete(Unit);flush.join()
        assertEquals(1L,env.store().pendingCount())
        val otherWire=WishlistTestWire();outbox(env.store("B"),otherWire).flush()
        assertTrue(otherWire.sent.isEmpty())
        a.flush();assertEquals(1,wire.sent.size)
    }
    @Test fun rejectionReopensWishWithoutRemovingMyLibrary() = discoverStoreTest { env ->
        val wire=WishlistTestWire();val store=env.store();val out=outbox(store,wire)
        out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null));out.flush();wire.scan()
        val wish=wire.wish!!
        val readIssue=store.nextIssue()
        out.enqueueReject(sampleDiscoverItem(),wish.libraryItem!!,wish.id)
        assertTrue(out.overlay(WishListSnapshot(listOf(wish),"arrived"),readIssue).single().pending)
        out.flush()
        assertEquals("wanted",out.overlay(WishListSnapshot(listOf(wish),"arrived"),readIssue).single().wish?.state)
        assertEquals("wanted",wire.wish?.state);assertTrue(wire.member)
        val before=wire.sent.size
        assertEquals("invalid-response",assertIs<DiscoverResult.Failed>(out.enqueueReject(sampleDiscoverItem(),wish.libraryItem!!.copy(providerInstanceId="B"),wish.id)).error.code)
        assertEquals(before,wire.sent.size)
    }
    @Test fun targetedRejectionWithoutWishIdOverlaysMatchingWish() = discoverStoreTest { env ->
        val store=env.store();val wire=WishlistTestWire().apply {wish=sampleWish();scan()};val out=outbox(store,wire)
        val old=wire.wish!!;val issue=store.nextIssue()
        out.enqueueReject(sampleDiscoverItem(),old.libraryItem!!,null)
        assertTrue(out.overlay(WishListSnapshot(listOf(old),"before"),issue).single().pending)
    }
    @Test fun rejectionFenceStopsBeforeSendAndAfterAwait() = discoverStoreTest { env ->
        var canSend=false;val wire=WishlistTestWire();val store=env.store();val out=outbox(store,wire,canSend={canSend})
        out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null));out.flush()
        assertTrue(wire.sent.isEmpty());assertEquals(1L,store.pendingCount())
        canSend=true;val held=CompletableDeferred<Unit>();wire.onApply={withContext(NonCancellable){held.await()}}
        val flush=launch {out.flush()};runCurrent();assertEquals(1,wire.sent.size)
        canSend=false;held.complete(Unit);flush.join()
        assertEquals(1L,store.pendingCount());assertNull(store.receipt(store.pending().single().mutation.operationId))
        out.flush();assertEquals(1,wire.sent.size)
    }
    @Test fun permanentRefusalRollsBackOnlyItsOverlay() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {failure=DiscoverFailure("forbidden",null,false)}
        val store=env.store();val out=outbox(store,wire)
        out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null));out.flush()
        val failed=out.overlay(WishListSnapshot(listOf(sampleWish("existing",target=null)),"clock"))
        assertTrue(failed.any {it.wish?.id=="existing"})
        assertTrue(failed.any {it.error?.code=="forbidden" && !it.pending})
        assertEquals(0L,store.pendingCount())
    }
    @Test fun retriesReusePayloadAndRollbackClockStillOrdersRemovalAfterAdd() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {failure=DiscoverFailure("offline",null,true)}
        val store=env.store();val out=outbox(store,wire)
        val a=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        out.flush();out.flush();assertEquals(wire.sent[0],wire.sent[1])
        env.now=-1000;out.enqueueRemove(a.rowKey)
        val add=assertIs<WishMutation.Add>(store.pending().first().mutation)
        val remove=assertIs<WishMutation.Remove>(store.pending().last().mutation)
        assertTrue(remove.actionTimeMillis>add.actionTimeMillis)
    }
    @Test fun discoverWithdrawalKeepsRemovalAndUnknownDetectionPreservesPending() = discoverStoreTest { env ->
        val wire=WishlistTestWire();var access=allDiscoverAccess();val store=env.store();val out=outbox(store,wire,access={access})
        val a=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        access=DiscoverCapabilityResolver.resolve(null,true,access);out.flush()
        assertTrue(wire.sent.isEmpty());assertEquals(1L,store.pendingCount())
        access=allDiscoverAccess();out.flush()
        access=DiscoverCapabilityResolver.resolve(mapOf("dulcetWishlist" to setOf(1)),true)
        assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueRemove(a.rowKey));out.flush();assertNull(wire.wish)
    }
}
