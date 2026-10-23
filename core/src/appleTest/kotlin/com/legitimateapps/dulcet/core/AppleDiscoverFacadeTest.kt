package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode

/** Real parent/session and native main dispatcher; owner polls deliberately never pump main. */
@OptIn(ExperimentalAtomicApi::class)
class AppleDiscoverFacadeTest {
    @Test fun callbackMainAndConstructionIsLazy()=facadeTest { h ->
        h.onReader { }
        val before=h.onReader {h.server.log.size}
        val discover=AppleDiscoverClient(h.reader)
        h.onReader { }
        assertEquals(before,h.onReader {h.server.log.size})
        val publications=mutableListOf<AppleDiscoverPublication>()
        discover.observe(object:AppleDiscoverListener {
            override fun onPublication(publication:AppleDiscoverPublication){
                assertTrue(NSThread.isMainThread);publications+=publication
            }
        })
        h.onReader { }
        assertTrue(publications.isEmpty(),"publication must wait for native main delivery")
        pumpUntil("initial publication"){publications.isNotEmpty()}
        assertTrue(publications.single().access.canAddWish)
        assertEquals(before,h.onReader {h.server.log.size},"offline construction/observation sends no requests")
        discover.close()
    }

    @Test fun closeDropsQueuedPublications()=facadeTest { h ->
        val discover=AppleDiscoverClient(h.reader)
        var count=0
        discover.observe(object:AppleDiscoverListener {override fun onPublication(publication:AppleDiscoverPublication){count++}})
        h.onReader { }
        discover.close()
        pumpFor(100.milliseconds)
        assertEquals(0,count,"close must revoke the already built publication")
    }

    @Test fun cancelBeforeCoreAttachmentPreventsDurableIntent()=facadeTest { h ->
        val discover=AppleDiscoverClient(h.reader)
        val release=h.holdReader()
        val results=mutableListOf<AppleWishEnqueueResult>()
        val operation=discover.add(sampleDiscoverItem().appleDiscover(),AppleWishSource("search",null)){results+=it}
        operation.cancel()
        release()
        pumpUntil("cancelled enqueue"){results.size==1}
        assertEquals("cancelled",results.single().error?.code)
        assertNull(results.single().value)
        h.onReader { }
        assertEquals(0L,h.pending())
        discover.close()
    }

    @Test fun cancelAfterEnqueueDoesNotDeleteDurableIntent()=facadeTest { h ->
        val discover=AppleDiscoverClient(h.reader)
        val results=mutableListOf<AppleWishEnqueueResult>()
        val operation=discover.add(sampleDiscoverItem().appleDiscover(),AppleWishSource("search",null)){
            assertTrue(NSThread.isMainThread);results+=it
        }
        h.waitFor("positive durable intent"){h.pending()==1L}
        assertTrue(results.isEmpty(),"success is held before main delivery")
        operation.cancel()
        pumpUntil("cancelled enqueue observation"){results.size==1}
        assertEquals("cancelled",results.single().error?.code)
        assertEquals(1L,h.pending())
        discover.close();h.onReader { }
        assertEquals(1L,h.pending(),"closing the child cannot roll back its committed intent")
        pumpFor(100.milliseconds)
        assertEquals(1,results.size)
    }

    @Test fun rejectedAndReacceptedEpochCannotReviveQueuedSuccess()=facadeTest { h ->
        val discover=AppleDiscoverClient(h.reader)
        val results=mutableListOf<AppleWishEnqueueResult>()
        discover.add(sampleDiscoverItem().appleDiscover(),AppleWishSource("search",null)){results+=it}
        h.waitFor("intent whose completion is queued"){h.pending()==1L}
        h.reader.rejectServerIdentity()
        AppleLibraryReaderIdentityAuthorization.accept(h.identityName,h.provider)
        pumpUntil("identity failure"){results.size==1}
        assertNull(results.single().value)
        assertEquals("identity-changed",results.single().error?.code)
        assertEquals(1L,h.pending())
        discover.close()
    }

    @Test fun parentCloseCompletesEveryOutstandingOperationExactlyOnceCancelled()=facadeTest { h ->
        val discover=AppleDiscoverClient(h.reader)
        val release=h.holdReader()
        val results=mutableListOf<AppleWishEnqueueResult>()
        discover.add(sampleDiscoverItem().appleDiscover(),AppleWishSource("search",null)){results+=it}
        var stopped=false
        h.reader.close {assertTrue(NSThread.isMainThread);stopped=true}
        release()
        pumpUntil("parent close and cancelled operation"){stopped && results.size==1}
        assertEquals("cancelled",results.single().error?.code)
        pumpFor(100.milliseconds)
        assertEquals(1,results.size)
    }

    @Test fun identityRejectionPublishesTypedDisabledSnapshotOnMain()=facadeTest { h ->
        val discover=AppleDiscoverClient(h.reader)
        val publications=mutableListOf<AppleDiscoverPublication>()
        discover.observe(object:AppleDiscoverListener {
            override fun onPublication(publication:AppleDiscoverPublication){
                assertTrue(NSThread.isMainThread);publications+=publication
            }
        })
        pumpUntil("positive writable publication"){publications.any {it.access.canAddWish}}
        val positive=publications.last().generation
        val before=publications.size
        // This queues the real reader rejection; do not fabricate the failure DTO.
        h.reader.rejectServerIdentity()
        pumpUntil("typed identity rejection publication"){
            publications.drop(before).any {it.access.error?.code=="identity-changed"}
        }
        val rejected=publications.drop(before).last {it.access.error?.code=="identity-changed"}
        assertTrue(rejected.generation>positive)
        assertFalse(rejected.access.canSearch);assertFalse(rejected.access.canReadWishlist)
        assertFalse(rejected.access.canAddWish);assertFalse(rejected.access.canRemoveWish);assertFalse(rejected.access.canRejectMatch)
        assertEquals("offline",rejected.connection)
        assertTrue(publications.drop(before).none {it.access.canAddWish},"queued success must still be revoked")
        discover.close()
    }

    @Test fun failedCompositionPublishesNamespacedUnresolvedStorageOnMain()=facadeTest(failComposition=true) { h ->
        val discover=AppleDiscoverClient(h.reader)
        val publications=mutableListOf<AppleDiscoverPublication>()
        val listener=object:AppleDiscoverListener {
            override fun onPublication(publication:AppleDiscoverPublication){
                assertTrue(NSThread.isMainThread);publications+=publication
            }
        }
        discover.observe(listener);discover.observe(listener)
        pumpUntil("two typed failed snapshots"){publications.size==2}
        for (p in publications){
            assertEquals(h.provider,p.providerInstanceId)
            assertEquals("unresolved",p.access.detection);assertEquals("storage",p.access.error?.code)
            assertFalse(p.access.discoverAvailable);assertFalse(p.access.wishlistAvailable)
            assertFalse(p.access.canSearch);assertFalse(p.access.canReadWishlist)
            assertFalse(p.access.canAddWish);assertFalse(p.access.canRemoveWish);assertFalse(p.access.canRejectMatch)
            assertEquals("offline",p.connection);assertEquals(0,p.groupCount);assertEquals(0,p.wishCount)
            assertFalse(p.wishlistSettled);assertNull(p.pendingChangeCount)
        }
        assertTrue(publications[1].generation>publications[0].generation)
        discover.close()
    }

    @Test fun storageFailureOperationCompletesOnceOnMainWithoutExportingThrowable()=facadeTest(failComposition=true) { h ->
        val discover=AppleDiscoverClient(h.reader)
        val results=mutableListOf<AppleDiscoverArtistResult>()
        discover.artist("artist"){assertTrue(NSThread.isMainThread);results+=it}
        pumpUntil("typed storage completion"){results.size==1}
        assertNull(results.single().value)
        assertEquals("storage",results.single().error?.code)
        pumpFor(100.milliseconds);assertEquals(1,results.size)
        discover.close()
    }

    @Test fun malformedTransportCompletesOnceOnMainWithoutExportingThrowable()=facadeTest(malformed=true) {h ->
        val discover=AppleDiscoverClient(h.reader)
        h.reader.setOnline(true)
        h.waitFor("real reader online"){h.onReader {h.session.load()?.reader?.online==true}}
        val results=mutableListOf<AppleDiscoverArtistResult>()
        discover.artist("fixture-artist"){assertTrue(NSThread.isMainThread);results+=it}
        pumpUntil("typed parser failure"){results.size==1}
        assertEquals(1,h.onReader {h.malformedRequests});assertNull(results.single().value)
        assertEquals("invalid-response",results.single().error?.code)
        pumpFor(100.milliseconds);assertEquals(1,results.size);discover.close()
    }

    @Test fun coldWishlistCorrectionNeedsNoDiscoverReadAndKeepsWishlistOnlyGate()=facadeTest { h ->
        h.onReader {
            val session=requireNotNull(h.session.load())
            val store=DiscoverStore(h.database.database,session.reader.cache.serverId)
            store.putRead("capabilities", """{"dulcetWishlist":[1]}""".encodeToByteArray(),false,store.nextIssue())
        }
        val discover=AppleDiscoverClient(h.reader)
        val results=mutableListOf<AppleWishEnqueueResult>()
        discover.rejectTarget(AppleDiscoverTarget("song","cold-recording"),
            AppleDiscoverLibraryRef(h.provider,"song","local-song"),"cold-wish") {
            assertTrue(NSThread.isMainThread);results+=it
        }
        pumpUntil("cold correction"){results.size==1}
        assertNotNull(results.single().value);assertNull(results.single().error)
        assertEquals(1L,h.pending())
        h.onReader {
            val session=requireNotNull(h.session.load())
            val mutation=DiscoverStore(h.database.database,session.reader.cache.serverId).intents().single().mutation
            assertEquals(DiscoverTarget(DiscoverKind.Song,"cold-recording"),(mutation as WishMutation.Reject).target)
            assertEquals("cold-wish",mutation.wishId)
            assertTrue(h.server.log.isEmpty(),"cold correction must send no Discover read")
        }
        discover.close()
    }

    @Test fun coldCorrectionRejectsForeignProviderAndInvalidKindWithoutThrowing()=facadeTest { h ->
        val discover=AppleDiscoverClient(h.reader)
        for ((kind,provider) in listOf("song" to "other-account","future" to h.provider)) {
            val results=mutableListOf<AppleWishEnqueueResult>()
            discover.rejectTarget(AppleDiscoverTarget(kind,"cold-recording"),
                AppleDiscoverLibraryRef(provider,"song","local-song"),null){results+=it}
            pumpUntil("refused correction"){results.size==1}
            assertEquals("invalid-response",results.single().error?.code)
        }
        assertEquals(0L,h.pending());discover.close()
    }

    @Test fun scopedSongLookupHandlesOneCharacterTitleAndRefusesForeignOrWrongIds()=facadeTest { h ->
        val results=mutableListOf<AppleLibrarySongLookup>()
        h.reader.lookupLibrarySong(AppleDiscoverLibraryRef("other-account","song","exact-id")){results+=it}
        pumpUntil("foreign reference refusal"){results.size==1}
        assertEquals("invalid-reference",results.last().errorKind);assertEquals(0,h.onReader {h.lookupRequests})
        h.reader.setOnline(true)
        h.waitFor("reader online"){h.onReader {h.session.load()?.reader?.online==true}}
        h.reader.lookupLibrarySong(AppleDiscoverLibraryRef(h.provider,"song","exact-id")){
            assertTrue(NSThread.isMainThread);results+=it
        }
        pumpUntil("exact one-character song"){results.size==2}
        val item=assertNotNull(results.last().item)
        assertEquals(h.provider,item.providerInstanceId);assertEquals("exact-id",item.rawId);assertEquals("A",item.title)
        assertEquals("track",item.kind);assertNull(results.last().errorKind);assertEquals(1,h.onReader {h.lookupRequests})
        h.reader.setOnline(false);h.onReader { }
        h.reader.lookupLibrarySong(AppleDiscoverLibraryRef(h.provider,"song","exact-id")){results+=it}
        pumpUntil("offline cached exact song"){results.size==3}
        assertEquals("exact-id",results.last().item?.rawId);assertEquals(1,h.onReader {h.lookupRequests})
        h.reader.setOnline(true)
        h.waitFor("reader online again"){h.onReader {h.session.load()?.reader?.online==true}}
        h.onReader {h.lookupWrongID=true}
        h.reader.lookupLibrarySong(AppleDiscoverLibraryRef(h.provider,"song","different-id")){results+=it}
        pumpUntil("wrong response identity refused"){results.size==4}
        assertNull(results.last().item);assertEquals("not-found",results.last().errorKind)
        assertEquals(2,h.onReader {h.lookupRequests})
    }

    @Test fun scopedSongLookupCancellationAndAccountEpochRevokeQueuedDelivery()=facadeTest { h ->
        val results=mutableListOf<AppleLibrarySongLookup>()
        val release=h.holdReader()
        val operation=h.reader.lookupLibrarySong(AppleDiscoverLibraryRef(h.provider,"song","exact-id")){results+=it}
        operation.cancel();release()
        pumpUntil("cancelled lookup"){results.size==1}
        assertEquals("cancelled",results.single().errorKind);assertEquals(0,h.onReader {h.lookupRequests})
        h.reader.setOnline(true)
        h.waitFor("reader online"){h.onReader {h.session.load()?.reader?.online==true}}
        h.reader.lookupLibrarySong(AppleDiscoverLibraryRef(h.provider,"song","exact-id")){results+=it}
        h.waitFor("lookup delivery queued"){h.onReader {h.lookupRequests==1}}
        h.onReader { } // Completion queued on main, which is deliberately not pumped yet.
        AppleLibraryReaderIdentityAuthorization.capture(h.identityName,h.provider).reject()
        pumpUntil("epoch-revoked lookup"){results.size==2}
        assertNull(results.last().item);assertEquals("identity-changed",results.last().errorKind)
    }

    private fun facadeTest(failComposition:Boolean=false,malformed:Boolean=false,block:(Harness)->Unit){
        check(NSThread.isMainThread)
        val h=Harness(failComposition,malformed)
        try {block(h)} finally {h.close()}
    }
    private class Harness(private val failComposition:Boolean=false,private val malformed:Boolean=false) {
        private val driver=createTestDriver()
        val database=DulcetDatabaseStore.open(driver)
        private val store=SeenCacheStore(database,ManualWallClock(now=3_000_000))
        private val dispatcher:CloseableCoroutineDispatcher=newLibraryReaderDispatcher()
        val server=SessionTestServer()
        var malformedRequests=0
        var lookupRequests=0
        var lookupWrongID=false
        private val wire=object:LibraryEndpointTransport by server {
            override suspend fun request(endpoint:String,parameters:Map<String,String>):LibraryEndpointResponse {
                if(endpoint=="getSong") {
                    lookupRequests++
                    val id=if(lookupWrongID) "wrong-id" else parameters["id"]
                    return LibraryEndpointResponse(200,discoverEnvelope("\"song\":{\"id\":\"$id\",\"title\":\"A\",\"duration\":1}"),"https://fixture.invalid?<redacted>")
                }
                if(malformed && endpoint=="getOpenSubsonicExtensions")return LibraryEndpointResponse(200,
                    discoverEnvelope("\"openSubsonicExtensions\":[{\"name\":\"dulcetDiscover\",\"versions\":[1]}]"),"https://fixture.invalid?<redacted>")
                if(malformed && endpoint=="getDulcetDiscoverArtist"){
                    malformedRequests++
                    return LibraryEndpointResponse(200,"malformed-json","https://fixture.invalid?<redacted>")
                }
                return server.request(endpoint,parameters)
            }
        }
        val session=AtomicReference<LibraryReaderSession?>(null)
        val provider="server:discover-apple"
        val identityName="discover-apple-${Random.nextLong()}"
        private val authorization=AppleLibraryReaderIdentityAuthorization.capture(identityName,provider)
        val reader=AppleLibraryReaderClient({scope,foreground ->
            if(failComposition)error("test-only storage exception must never cross the facade")
            val s=LibraryReaderSession(database.database,store.bind(CacheBinding(provider,"https://music.invalid","owner")),wire,scope,
                formPost=false,foreground=foreground,identityAuthorization=authorization::currentToken)
            s.reader.setOnline(false)
            DiscoverStore(database.database,s.reader.cache.serverId).let {discover ->
                discover.putRead("capabilities","""{"dulcetDiscover":[1],"dulcetWishlist":[1]}""".encodeToByteArray(),false,discover.nextIssue())
            }
            session.store(s)
            AppleLibraryReaderComposition(s)
        },false,dispatcher,Dispatchers.Main,authorization,discoverProviderInstanceId=provider)
        fun <T> onReader(block:()->T):T=runBlocking(dispatcher){block()}
        fun pending():Long=onReader {
            session.load()?.let {DiscoverStore(database.database,it.reader.cache.serverId).pendingCount()}?:0L
        }
        fun holdReader():()->Unit {
            val state=AtomicInt(0)
            CoroutineScope(dispatcher).launch {
                state.store(1)
                val start=TimeSource.Monotonic.markNow()
                while(state.load()==1 && start.elapsedNow()<20.seconds)platform.posix.usleep(1_000u)
            }
            waitFor("reader hold"){state.load()==1}
            return {state.store(2)}
        }
        fun waitFor(what:String,condition:()->Boolean){
            val start=TimeSource.Monotonic.markNow()
            while(!condition()){
                check(start.elapsedNow()<20.seconds){"timed out waiting for $what"}
                platform.posix.usleep(2_000u)
            }
        }
        fun close(){reader.close();runBlocking {withTimeout(20.seconds){reader.awaitTermination()}};driver.close()}
    }
    companion object {
        private fun pumpUntil(what:String,condition:()->Boolean){
            val start=TimeSource.Monotonic.markNow()
            while(!condition()){
                check(start.elapsedNow()<20.seconds){"timed out waiting for $what"}
                NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode,NSDate.dateWithTimeIntervalSinceNow(.005))
            }
        }
        private fun pumpFor(duration:Duration){
            val start=TimeSource.Monotonic.markNow()
            while(start.elapsedNow()<duration)NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode,NSDate.dateWithTimeIntervalSinceNow(.005))
        }
    }
}
