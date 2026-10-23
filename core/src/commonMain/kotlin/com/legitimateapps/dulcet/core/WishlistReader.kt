package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException

/** A settled owner-only snapshot plus durable local intent; scan clocks never bracket wish state. */
internal class WishlistReader(
    private val store: DiscoverStore,
    private val transport: WishlistTransport,
    private val outbox: WishOutbox,
    private val access: ()->DiscoverFeatureAccess,
    private val active: ()->Boolean = {true},
    private val onFulfilled: (DiscoverLibraryRef)->Unit = {},
    private val changed: ()->Unit = {},
) {
    private var closed=false
    private var generation=0L
    private var filter=WishFilter()
    private var snapshot: WishListSnapshot?=null
    private var issue=0L
    var loading=false;private set
    var error: DiscoverFailure?=null;private set
    val settled: Boolean get()=snapshot!=null
    val rows: List<WishRow> get()=outbox.overlay(snapshot,issue,filter)
    fun close() {closed=true;generation++;loading=false}
    private fun current(ticket: Long)=!closed && active() && generation==ticket
    suspend fun refresh(filter: WishFilter) {
        if(closed || !active()) return
        val ticket=++generation
        this.filter=filter
        try {
            snapshot=store.snapshot(filter);issue=store.snapshotIssue(filter)
            if(!access().canReadWishlist) {error=DiscoverFailure("unsupported",null,false);changed();return}
            loading=true;error=null;changed()
            if(!current(ticket)) return
            val issued=store.nextIssue()
            val result=transport.snapshot(filter)
            if(!current(ticket)) return
            when(result) {
                is DiscoverResult.Value -> {
                    val before=snapshot?.items.orEmpty().associateBy {it.id}
                    store.replaceSnapshot(filter,result.value,issued)
                    snapshot=store.snapshot(filter);issue=store.snapshotIssue(filter)
                    for(wish in snapshot?.items.orEmpty()) {
                        if(wish.state=="fulfilled" && before[wish.id]?.state!="fulfilled") wish.libraryItem?.takeIf {it.providerInstanceId==store.serverId}?.let(onFulfilled)
                    }
                }
                is DiscoverResult.Failed -> error=result.error
            }
        } catch(cancelled: CancellationException) {throw cancelled}
          catch(_: Exception) {if(current(ticket)) error=DiscoverFailure("storage",null,true)}
          finally {if(current(ticket)){loading=false;changed()}}
    }
}
