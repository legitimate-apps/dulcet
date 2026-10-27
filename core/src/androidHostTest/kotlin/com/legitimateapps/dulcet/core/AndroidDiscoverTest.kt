package com.legitimateapps.dulcet.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AndroidDiscoverTest {
    @Test fun shelfPlaylistAndHomeCommandsDeliverOnMainAndCancelOnce() = withHarness {h ->
        val client=AndroidDiscoverClient(h.reader)
        val mainThread=Thread.currentThread()
        val shelf=mutableListOf<DiscoverResult<DiscoverShelfPage>>()
        val list=mutableListOf<DiscoverResult<DiscoverPlaylistList>>()
        val playlist=mutableListOf<DiscoverResult<DiscoverPlaylist>>()
        client.shelf("trendingSongs",null){assertSame(mainThread,Thread.currentThread());shelf+=it}
        client.playlists("",null){assertSame(mainThread,Thread.currentThread());list+=it}
        client.playlist("opaque",null){assertSame(mainThread,Thread.currentThread());playlist+=it}
        h.main.drainUntil {shelf.size==1 && list.size==1 && playlist.size==1}
        for(answer in listOf(shelf.single(),list.single(),playlist.single())) assertEquals("offline",assertIs<DiscoverResult.Failed>(answer).error.code)
        val pubs=mutableListOf<DiscoverPublication>()
        client.observe {pubs+=it};client.refreshHome()
        h.main.drainUntil {pubs.any {it.home?.shelves?.size==6 && it.home.shelves.none {shelf -> shelf.loading}}}
        assertTrue(h.server.log.isEmpty(),"offline home sends no request")
        val release=h.holdReader()
        val cancelled=mutableListOf<DiscoverResult<DiscoverPlaylist>>()
        val op=client.playlist("opaque",null){cancelled+=it};op.cancel();release.countDown()
        h.main.drainUntil {cancelled.size==1};h.barrier();h.main.drain()
        assertEquals("cancelled",assertIs<DiscoverResult.Failed>(cancelled.single()).error.code)
        client.close()
    }

    @Test fun callbackMainAndConstructionIsLazy() = withHarness { h ->
        val discover=AndroidDiscoverClient(h.reader)
        assertNull(h.session.get(),"constructing Discover must not compose a session")
        assertTrue(h.server.log.isEmpty())
        val publications=mutableListOf<DiscoverPublication>()
        val mainThread=Thread.currentThread()
        discover.observe { assertSame(mainThread,Thread.currentThread());publications+=it }
        h.main.awaitQueued()
        assertTrue(publications.isEmpty(),"owner callback must wait for the UI dispatcher")
        h.main.drain()
        assertEquals(1,publications.size)
        assertTrue(publications.single().access.canAddWish)
        assertTrue(h.server.log.isEmpty(),"the first offline publication is a device read")
        discover.close()
    }

    @Test fun closeDropsQueuedPublicationsAndParentCloseCompletesOutstandingOperationsOnce() = withHarness { h ->
        val discover=AndroidDiscoverClient(h.reader)
        var publications=0
        discover.observe { publications++ }
        h.main.awaitQueued()
        discover.close()
        h.main.drain()
        assertEquals(0,publications)
        val second=AndroidDiscoverClient(h.reader)
        val answers=mutableListOf<DiscoverResult<WishEnqueued>>()
        val release=h.holdReader()
        second.add(sampleDiscoverItem(),WishSource("search",null)){answers+=it}
        h.reader.close()
        release.countDown()
        runBlocking { withTimeout(30_000){ h.reader.awaitTermination() } }
        h.main.drainUntil { answers.size==1 }
        assertEquals("cancelled",assertIs<DiscoverResult.Failed>(answers.single()).error.code)
        h.main.drain()
        assertEquals(1,answers.size)
    }

    @Test fun cancelBeforeCoreOperationAttachesDoesNotEnqueue() = withHarness { h ->
        val discover=AndroidDiscoverClient(h.reader)
        discover.observe { }
        h.main.awaitQueued();h.main.drain()
        val release=h.holdReader()
        val answers=mutableListOf<DiscoverResult<WishEnqueued>>()
        val operation=discover.add(sampleDiscoverItem(),WishSource("search",null)){answers+=it}
        operation.cancel()
        release.countDown()
        h.barrier()
        h.main.drainUntil { answers.size==1 }
        assertEquals("cancelled",assertIs<DiscoverResult.Failed>(answers.single()).error.code)
        assertEquals(0L,h.pending())
        discover.close()
    }

    @Test fun cancelAfterEnqueueDoesNotDeleteDurableIntent() = withHarness { h ->
        val discover=AndroidDiscoverClient(h.reader)
        val answers=mutableListOf<DiscoverResult<WishEnqueued>>()
        val operation=discover.add(sampleDiscoverItem(),WishSource("search",null)){answers+=it}
        h.waitFor("durable offline intent") { h.pending()==1L }
        assertTrue(answers.isEmpty(),"success must still be queued on main")
        operation.cancel()
        h.main.drainUntil { answers.size==1 }
        assertEquals("cancelled",assertIs<DiscoverResult.Failed>(answers.single()).error.code)
        assertEquals(1L,h.pending(),"cancel after commit revokes observation, never the intent")
        discover.close()
        h.barrier()
        assertEquals(1L,h.pending(),"closing Discover also preserves the intent")
    }

    @Test fun accountFenceRejectAcceptCannotDeliverQueuedSuccess() = withHarness { h ->
        val discover=AndroidDiscoverClient(h.reader)
        val answers=mutableListOf<DiscoverResult<WishEnqueued>>()
        discover.add(sampleDiscoverItem(),WishSource("search",null)){answers+=it}
        h.waitFor("durable intent before identity replacement") { h.pending()==1L }
        h.main.awaitQueued()
        // Existing external authorization epoch: reject/accept cannot resurrect epoch 1 delivery.
        h.authorization.set(null)
        h.authorization.set(2L)
        h.main.drainUntil { answers.size==1 }
        assertEquals("identity-changed",assertIs<DiscoverResult.Failed>(answers.single()).error.code)
        assertEquals(1L,h.pending())
        discover.close()
    }

    @Test fun identityRejectionPublishesTypedDisabledSnapshotOnMain() = withHarness { h ->
        val discover=AndroidDiscoverClient(h.reader)
        val publications=mutableListOf<DiscoverPublication>()
        val uiThread=Thread.currentThread()
        discover.observe {assertSame(uiThread,Thread.currentThread());publications+=it}
        h.main.drainUntil {publications.any {it.access.canAddWish}}
        val positive=publications.last().generation
        val before=publications.size
        // Production notifications follow a rejected identity decision. The reader intentionally
        // ignores unbacked or stale notifications so an accepted replacement stays authorized.
        runBlocking {
            assertEquals(DomainError.Protocol.ServerIdentityChanged,
                AndroidServerIdentity.validate(h.account.providerInstanceId) { DomainError.Protocol.ServerIdentityChanged })
        }
        assertNotNull(AndroidServerIdentity.rejectionEpoch(h.account.providerInstanceId))
        h.reader.rejectServerIdentity()
        h.main.drainUntil {publications.drop(before).any {it.access.error?.code=="identity-changed"}}
        val rejected=publications.drop(before).last {it.access.error?.code=="identity-changed"}
        assertTrue(rejected.generation>positive)
        assertFalse(rejected.access.canSearch);assertFalse(rejected.access.canReadWishlist)
        assertFalse(rejected.access.canAddWish);assertFalse(rejected.access.canRemoveWish);assertFalse(rejected.access.canRejectMatch)
        assertEquals("offline",rejected.connection)
        assertTrue(publications.drop(before).none {it.access.canAddWish})
        discover.close()
    }

    @Test fun failedCompositionPublishesNamespacedUnresolvedStorageOnMain() = withHarness(failComposition=true) { h ->
        val discover=AndroidDiscoverClient(h.reader)
        val publications=mutableListOf<DiscoverPublication>()
        val uiThread=Thread.currentThread()
        repeat(2){discover.observe {assertSame(uiThread,Thread.currentThread());publications+=it}}
        h.main.drainUntil {publications.size==2}
        for(p in publications){
            assertEquals(SessionEnv.BINDING.serverId,p.providerInstanceId)
            assertEquals("unresolved",p.access.detection);assertEquals("storage",p.access.error?.code)
            assertFalse(p.access.discoverAvailable);assertFalse(p.access.wishlistAvailable)
            assertFalse(p.access.canSearch);assertFalse(p.access.canReadWishlist)
            assertFalse(p.access.canAddWish);assertFalse(p.access.canRemoveWish);assertFalse(p.access.canRejectMatch)
            assertEquals("offline",p.connection);assertTrue(p.groups.isEmpty());assertTrue(p.wishes.isEmpty())
            assertFalse(p.wishlistSettled);assertNull(p.pendingChangeCount)
        }
        assertTrue(publications[1].generation>publications[0].generation)
        discover.close()
    }

    @Test fun storageFailureOperationCompletesOnceOnMainWithoutExportingThrowable() = withHarness(failComposition=true) { h ->
        val discover=AndroidDiscoverClient(h.reader)
        val results=mutableListOf<DiscoverResult<DiscoverArtist>>()
        val uiThread=Thread.currentThread()
        discover.artist("artist"){assertSame(uiThread,Thread.currentThread());results+=it}
        h.main.drainUntil {results.size==1}
        assertEquals("storage",assertIs<DiscoverResult.Failed>(results.single()).error.code)
        h.main.drain();assertEquals(1,results.size)
        discover.close()
    }

    @Test fun malformedTransportCompletesOnceOnMainWithoutExportingThrowable()=withHarness(malformed=true) {h ->
        val discover=AndroidDiscoverClient(h.reader);val ui=Thread.currentThread()
        h.reader.setOnline(true)
        h.waitFor("real reader online"){h.onReader {h.session.get()?.reader?.online==true}}
        val results=mutableListOf<DiscoverResult<DiscoverArtist>>()
        discover.artist("fixture-artist"){assertSame(ui,Thread.currentThread());results+=it}
        h.main.drainUntil {results.size==1}
        assertEquals(1,h.onReader {h.malformedRequests})
        assertEquals("invalid-response",assertIs<DiscoverResult.Failed>(results.single()).error.code)
        h.main.drain();assertEquals(1,results.size);discover.close()
    }

    private inline fun withHarness(failComposition:Boolean=false,malformed:Boolean=false,block:(Harness)->Unit) {
        val h=Harness(failComposition,malformed)
        try { block(h) } finally { h.close() }
    }

    private class Main:CoroutineDispatcher() {
        private val queue=LinkedBlockingQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){queue.put(block)}
        fun awaitQueued(){waitFor("queued main delivery"){queue.isNotEmpty()}}
        fun drain(){while(true)(queue.poll()?:return).run()}
        fun drainUntil(done:()->Boolean){waitFor("main completion"){drain();done()}}
    }

    private class Harness(private val failComposition:Boolean=false,private val malformed:Boolean=false) {
        private val driver=createTestDriver()
        private val database=DulcetDatabaseStore.open(driver)
        private val store=SeenCacheStore(database,ManualWallClock(now=2_000_000))
        val server=SessionTestServer()
        var malformedRequests=0
        private val wire=object:LibraryEndpointTransport by server {
            override suspend fun request(endpoint:String,parameters:Map<String,String>):LibraryEndpointResponse {
                if(malformed && endpoint=="getOpenSubsonicExtensions")return LibraryEndpointResponse(200,
                    discoverEnvelope("\"openSubsonicExtensions\":[{\"name\":\"dulcetDiscover\",\"versions\":[1]}]"),"https://fixture.invalid?<redacted>")
                if(malformed && endpoint=="getDulcetDiscoverArtist"){
                    malformedRequests++
                    return LibraryEndpointResponse(200,"malformed-json","https://fixture.invalid?<redacted>")
                }
                return server.request(endpoint,parameters)
            }
        }
        val session=AtomicReference<LibraryReaderSession?>()
        val authorization=AtomicReference<Long?>(1L)
        val main=Main()
        val account=AndroidLibraryReaderAccount(SessionEnv.BINDING.serverId,SessionEnv.BINDING.normalizedBaseUrl,SessionEnv.BINDING.username,"fixture",true)
        val reader=AndroidLibraryReader(account,{scope,foreground ->
            if(failComposition)error("test-only storage exception must never cross the facade")
            val s=LibraryReaderSession(database.database,store.bind(SessionEnv.BINDING),wire,scope,
                formPost=false,foreground=foreground,identityAuthorization={authorization.get()})
            s.reader.setOnline(false)
            DiscoverStore(database.database,s.reader.cache.serverId).let { discover ->
                discover.putRead("capabilities","""{"dulcetDiscover":[1],"dulcetWishlist":[1]}""".encodeToByteArray(),false,discover.nextIssue())
            }
            session.set(s)
            AndroidLibraryReaderComposition(s)
        },newLibraryReaderDispatcher(),main,initiallyForeground=false)
        fun barrier(){onReader { }}
        fun <T> onReader(block:()->T):T {
            val result=AtomicReference<Result<T>>()
            val complete=CountDownLatch(1)
            reader.onReader { result.set(runCatching(block));complete.countDown() }
            check(complete.await(30,TimeUnit.SECONDS)){"reader barrier timed out"}
            return result.get().getOrThrow()
        }
        fun pending():Long?=onReader {
            val s=checkNotNull(session.get())
            DiscoverStore(database.database,s.reader.cache.serverId).pendingCount()
        }
        fun holdReader():CountDownLatch {
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            reader.onReader {entered.countDown();check(release.await(30,TimeUnit.SECONDS))}
            check(entered.await(30,TimeUnit.SECONDS))
            return release
        }
        fun waitFor(what:String,done:()->Boolean)=Companion.waitFor(what,done)
        fun close(){
            reader.close()
            runBlocking {
                withTimeout(30_000){reader.awaitTermination()}
                AndroidServerIdentity.validate(account.providerInstanceId) { null }
            }
            main.drain();driver.close()
        }
    }

    companion object {
        private fun waitFor(what:String,done:()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!done()){check(System.nanoTime()<deadline){"timed out waiting for $what"};Thread.sleep(5)}
        }
    }
}
