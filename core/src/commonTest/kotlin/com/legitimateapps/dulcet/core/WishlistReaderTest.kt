@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class WishlistReaderTest {
    @Test fun generationChangesDuringPagingNeverPublishPartialAsEmpty() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {wish=sampleWish()};val store=env.store();val out=outbox(store,wire)
        val reader=WishlistReader(store,wire,out,{allDiscoverAccess()})
        reader.refresh(WishFilter());assertTrue(reader.settled);assertEquals(1,reader.rows.size)
        wire.snapshotFailure=DiscoverFailure("conflict",null,true)
        reader.refresh(WishFilter())
        assertTrue(reader.settled);assertEquals("conflict",reader.error?.code);assertEquals(1,reader.rows.size)
    }
    @Test fun newerFilterAndRetirementDropOldSnapshot() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {wish=sampleWish();snapshotGate=CompletableDeferred()};val store=env.store();val out=outbox(store,wire)
        var active=true
        val reader=WishlistReader(store,wire,out,{allDiscoverAccess()},{active})
        val held=launch {reader.refresh(WishFilter())};runCurrent()
        active=false;wire.snapshotGate!!.complete(Unit);held.join()
        assertNull(store.snapshot(WishFilter()));assertFalse(reader.settled)
        reader.close()
    }
    @Test fun newerFilterDropsDelayedUnfilteredSnapshot() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {wish=sampleWish();snapshotGate=CompletableDeferred()}
        val store=env.store();val reader=WishlistReader(store,wire,outbox(store,wire),{allDiscoverAccess()})
        val release=wire.snapshotGate!!
        val old=launch {reader.refresh(WishFilter())};runCurrent()
        wire.snapshotGate=null;reader.refresh(WishFilter(state="fulfilled"))
        assertTrue(reader.settled);assertTrue(reader.rows.isEmpty())
        release.complete(Unit);old.join()
        assertNull(store.snapshot(WishFilter()));assertTrue(reader.rows.isEmpty())
    }
    @Test fun acknowledgementOnlyOverlaysSnapshotsOlderThanItsReceipt() = discoverStoreTest { env ->
        val wire=WishlistTestWire();val store=env.store();val out=outbox(store,wire)
        val reader=WishlistReader(store,wire,out,{allDiscoverAccess()})
        reader.refresh(WishFilter())
        val add=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        out.flush()
        assertEquals(add.rowKey,reader.rows.single().key)
        wire.wish=null;reader.refresh(WishFilter())
        assertTrue(reader.rows.isEmpty(),"old add receipt resurrected a wish absent in a newer settled read")
    }
    @Test fun newerSnapshotCanShowAnotherDevicesReaddAfterAcknowledgedRemoval() = discoverStoreTest { env ->
        val wire=WishlistTestWire();val store=env.store();val out=outbox(store,wire)
        val reader=WishlistReader(store,wire,out,{allDiscoverAccess()})
        val a=assertIs<DiscoverResult.Value<WishEnqueued>>(out.enqueueAdd(sampleDiscoverItem(),WishSource("search",null))).value
        out.flush();reader.refresh(WishFilter());out.enqueueRemove(a.rowKey);out.flush()
        wire.wish=sampleWish("other-device-new-generation")
        reader.refresh(WishFilter())
        assertEquals("other-device-new-generation",reader.rows.single().wish?.id)
    }
    @Test fun observedArrivalRefreshesMyLibraryWithoutMembershipMutation() = discoverStoreTest { env ->
        val wire=WishlistTestWire().apply {wish=sampleWish()};val store=env.store();val out=outbox(store,wire)
        val arrivals=mutableListOf<DiscoverLibraryRef>()
        val reader=WishlistReader(store,wire,out,{allDiscoverAccess()},onFulfilled={arrivals+=it})
        reader.refresh(WishFilter());wire.scan();reader.refresh(WishFilter());reader.refresh(WishFilter())
        assertEquals("fulfilled",reader.rows.single().wish?.state)
        assertEquals(listOf(DiscoverLibraryRef("account-A","song","local")),arrivals)
        assertTrue(wire.sent.isEmpty());assertTrue(wire.member)
    }
    @Test fun successfulEmptyIsSettledButUnavailableIsNotEmptySuccess() = discoverStoreTest { env ->
        val wire=WishlistTestWire();val store=env.store();val reader=WishlistReader(store,wire,outbox(store,wire),{allDiscoverAccess()})
        wire.snapshotFailure=DiscoverFailure("offline",null,true);reader.refresh(WishFilter())
        assertFalse(reader.settled);assertEquals("offline",reader.error?.code)
        wire.snapshotFailure=null;reader.refresh(WishFilter())
        assertTrue(reader.settled);assertNull(reader.error);assertTrue(reader.rows.isEmpty())
    }
}
