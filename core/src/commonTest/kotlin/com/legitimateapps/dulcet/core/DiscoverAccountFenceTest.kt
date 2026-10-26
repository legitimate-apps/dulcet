@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class DiscoverSessionWire:LibraryEndpointTransport {
    val baseline=SessionTestServer(FakeReaderServer(albumCount=3))
    val requests=mutableListOf<String>()
    val wishParameters=mutableListOf<Map<String,String>>()
    var retryAfter:String?=null
    val retryHeaders=ArrayDeque<String?>()
    val wishTimes=mutableListOf<Long>()
    var now:()->Long={0L}
    var retryEndpoint="addToDulcetWishlist"
    var advertised=true
    var hold:String?=null
    var entered=CompletableDeferred<Unit>()
    var release=CompletableDeferred<Unit>()
    var extensionFailure=false
    override suspend fun request(endpoint:String,parameters:Map<String,String>):LibraryEndpointResponse {
        requests+=endpoint
        if(endpoint=="addToDulcetWishlist") {
            wishParameters+=parameters
            wishTimes+=now()
        }
        if(endpoint==retryEndpoint && (retryHeaders.isNotEmpty() || retryAfter!=null)) {
            val wait=if(retryHeaders.isNotEmpty())retryHeaders.removeFirst() else retryAfter.also {retryAfter=null}
            return LibraryEndpointResponse(429,"busy","https://fixture.invalid?<redacted>",retryAfter=wait)
        }
        if(endpoint==hold){entered.complete(Unit);withContext(NonCancellable){release.await()}}
        val child=when(endpoint) {
            "getOpenSubsonicExtensions" -> if(extensionFailure) return LibraryEndpointResponse(500,"failure","https://fixture.invalid?<redacted>") else "\"openSubsonicExtensions\":"+if(advertised) """[{"name":"dulcetDiscover","versions":[1]},{"name":"dulcetWishlist","versions":[1]}]""" else "[]"
            "searchDulcetDiscover" -> "\"dulcetDiscover\":"+discoverPageJson()
            "getDulcetWishlist" -> """"dulcetWishlist":{"wish":[]}"""
            "addToDulcetWishlist" -> """"dulcetWishMutation":{"applied":true,"wish":${wishJson()},"removed":false}"""
            "removeFromDulcetWishlist" -> """"dulcetWishMutation":{"applied":true,"removed":true}"""
            else -> return baseline.request(endpoint,parameters)
        }
        return LibraryEndpointResponse(200,discoverEnvelope(child),"https://fixture.invalid?<redacted>")
    }
}

class DiscoverAccountFenceTest {
    private suspend fun TestScope.open(env:ReaderEnv,wire:DiscoverSessionWire,authorization:()->Long?={0L},validate:suspend ()->DomainError?={null}):LibraryReaderSession = LibraryReaderSession(
        com.legitimateapps.dulcet.database.DulcetDatabase(env.driver),env.store.bind(ReaderEnv.BINDING),wire,
        backgroundScope,config=LibraryReaderConfig(monotonic=testScheduler.timeSource),formPost=false,foreground=false,identityAuthorization=authorization,validateServerIdentity=validate
    ).also {it.reader.connect()}
    @Test fun identityMismatchBeforeReconnectSendsNoFeatureWrite()=readerTest {env ->
        val wire=DiscoverSessionWire();var rejected=false
        val parent=open(env,wire,validate={if(rejected) DomainError.Protocol.ServerIdentityChanged else null})
        val client=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>();client.observe {pubs+=it}
        client.refreshWishlist(WishFilter());advanceUntilIdle();runCurrent()
        assertTrue(wire.requests.contains("getDulcetWishlist"))
        parent.setOnline(false);runCurrent()
        var queued:DiscoverResult<WishEnqueued>?=null
        client.add(sampleDiscoverItem(),WishSource("search",null)){queued=it};runCurrent()
        assertIs<DiscoverResult.Value<WishEnqueued>>(queued);assertEquals(1L,parent.pendingChangeCount())
        rejected=true;val before=wire.requests.size
        assertIs<ReaderConnectionOutcome.Failed>(parent.reader.reconnect())
        assertTrue(wire.requests.drop(before).none {it=="addToDulcetWishlist"})
        assertEquals(1L,parent.pendingChangeCount());assertEquals("identity-changed",pubs.last().access.error?.code)
        client.close()
    }
    /** #393: the accepted saved lineage must reopen Discover on the SAME reader and client. */
    @Test fun acceptedSameReaderReconnectFlushesWishAfterIdentityRejection()=readerTest {env ->
        val wire=DiscoverSessionWire();var rejected=false;var epoch:Long?=0
        val parent=open(env,wire,{epoch},validate={if(rejected) DomainError.Protocol.ServerIdentityChanged else null})
        val reader=parent.reader
        val client=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>();client.observe {pubs+=it}
        client.refreshWishlist(WishFilter());runCurrent()
        client.setQuery("restored");advanceTimeBy(350);runCurrent()
        assertEquals(3,pubs.last().groups.size)
        parent.setOnline(false);runCurrent()
        client.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        assertEquals(1L,parent.pendingChangeCount())
        rejected=true;epoch=null
        assertIs<ReaderConnectionOutcome.Failed>(reader.reconnect());runCurrent()
        assertEquals("identity-changed",pubs.last().access.error?.code)
        assertTrue(wire.wishParameters.isEmpty())
        // Validation accepts the saved account again, not a bind to a different principal.
        rejected=false;epoch=1
        assertIs<ReaderConnectionOutcome.Read>(reader.reconnect());runCurrent()
        assertSame(reader,parent.reader)
        assertEquals(1,wire.wishParameters.size,"accepted reconnect must deliver the durable wish")
        assertEquals(0L,parent.pendingChangeCount())
        assertTrue(pubs.last().access.canAddWish,"the existing client must recover too")
        assertTrue(pubs.last().wishlistSettled);assertNull(pubs.last().wishlistError)
        advanceTimeBy(350);runCurrent()
        assertEquals("restored",pubs.last().query);assertEquals(3,pubs.last().groups.size)
        assertTrue(pubs.last().groups.all {it.page!=null && it.error==null})
        client.close()
    }
    /** #393: no foreground event, refresh, reconnect or second command wakes this idle outbox. */
    @Test fun idleOnlineWishRetriesExactlyAtBackoffDeadline()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire);val client=parent.openDiscoverClient()
        wire.retryAfter="120"
        client.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        assertEquals(1,wire.wishParameters.size);assertEquals(1L,parent.pendingChangeCount())
        client.close() // The reader owns delivery after the originating surface disappears.
        // A wall-clock jump cannot move the in-memory monotonic deadline.
        env.clock.now+=86_400_000
        advanceTimeBy(119_999);runCurrent()
        assertEquals(1,wire.wishParameters.size,"nothing may write before Retry-After")
        advanceTimeBy(1);runCurrent()
        assertEquals(2,wire.wishParameters.size,"the reader must wake the idle outbox at its deadline")
        assertEquals(wire.wishParameters.first(),wire.wishParameters.last(),"retry keeps the durable operation bytes")
        assertEquals(0L,parent.pendingChangeCount())
        advanceTimeBy(120_000);runCurrent();assertEquals(2,wire.wishParameters.size)
    }
    /** #393 review: a finite 429 script exposes a hot loop without hanging virtual time. */
    @Test fun repeatedZeroRetryAfterUsesGrowingBoundedFloorAndResetsAfterAcceptance()=readerTest {env ->
        assertRetrySpacing(env,"0",listOf(2_000,4_000,8_000,16_000,32_000,64_000,128_000,256_000,300_000,300_000))
    }
    @Test fun repeatedPastRetryAfterUsesPositiveGrowingFloor()=readerTest {env ->
        assertRetrySpacing(env,"Thu, 01 Jan 1970 00:00:00 GMT",listOf(2_000,4_000,8_000))
    }
    @Test fun missingRetryAfterKeepsSixtySecondFallbackUntilGrowingFloorIsLonger()=readerTest {env ->
        assertRetrySpacing(env,null,listOf(60_000,60_000,60_000,60_000,60_000,64_000,128_000))
    }
    @Test fun serverRetryDeadlineLongerThanClientCapIsNeverShortened()=readerTest {env ->
        assertRetrySpacing(env,"600",listOf(600_000))
    }
    private suspend fun TestScope.assertRetrySpacing(env:ReaderEnv,header:String?,waits:List<Long>) {
        val wire=DiscoverSessionWire().apply {
            now={testScheduler.currentTime}
            repeat(waits.size){retryHeaders.addLast(header)}
        }
        val parent=open(env,wire);val client=parent.openDiscoverClient()
        client.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        assertEquals(1,wire.wishTimes.size,"429 must schedule a spaced retry, never a delay(0) loop")
        val expectedTimes=mutableListOf(0L)
        for((index,wait) in waits.withIndex()) {
            advanceTimeBy(wait-1);runCurrent()
            assertEquals(index+1,wire.wishTimes.size,"attempt ${index+2} fired before its effective deadline")
            advanceTimeBy(1);runCurrent()
            expectedTimes+=expectedTimes.last()+wait
            assertEquals(expectedTimes,wire.wishTimes,"each actual 429 advances the floor once")
        }
        assertEquals(0L,parent.pendingChangeCount())
        val acceptedCount=wire.wishTimes.size
        advanceTimeBy(600_000);runCurrent();assertEquals(acceptedCount,wire.wishTimes.size)
        // An accepted write resets the streak even after the floor reached its cap.
        wire.retryHeaders.addLast("0")
        client.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        assertEquals(acceptedCount+1,wire.wishTimes.size)
        advanceTimeBy(1_999);runCurrent();assertEquals(acceptedCount+1,wire.wishTimes.size)
        advanceTimeBy(1);runCurrent();assertEquals(acceptedCount+2,wire.wishTimes.size)
        assertEquals(2_000L,wire.wishTimes.last()-wire.wishTimes[acceptedCount])
        assertEquals(0L,parent.pendingChangeCount())
        advanceTimeBy(600_000);runCurrent();assertEquals(acceptedCount+2,wire.wishTimes.size)
        client.close()
    }
    @Test fun repeatedRateLimitReplacesWakeupAndKeepsOneRetryPerDeadline()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire)
        wire.retryAfter="120"
        parent.openDiscoverClient().add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        wire.retryAfter="60"
        advanceTimeBy(120_000);runCurrent()
        assertEquals(2,wire.wishParameters.size);assertEquals(1L,parent.pendingChangeCount())
        advanceTimeBy(59_999);runCurrent();assertEquals(2,wire.wishParameters.size)
        advanceTimeBy(1);runCurrent()
        assertEquals(3,wire.wishParameters.size);assertEquals(0L,parent.pendingChangeCount())
        advanceTimeBy(120_000);runCurrent();assertEquals(3,wire.wishParameters.size)
    }
    @Test fun wishQueuedDuringReadBackoffStillGetsItsDeadlineWakeup()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire);val client=parent.openDiscoverClient()
        wire.retryEndpoint="getDulcetWishlist";wire.retryAfter="120"
        client.refreshWishlist(WishFilter());runCurrent()
        assertTrue(wire.requests.contains("getDulcetWishlist"));assertEquals(0L,parent.pendingChangeCount())
        client.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        assertEquals(1L,parent.pendingChangeCount());assertTrue(wire.wishParameters.isEmpty())
        advanceTimeBy(119_999);runCurrent();assertTrue(wire.wishParameters.isEmpty())
        advanceTimeBy(1);runCurrent()
        assertEquals(1,wire.wishParameters.size);assertEquals(0L,parent.pendingChangeCount())
        client.close()
    }
    @Test fun acceptedRecoveryKeepsUnexpiredMonotonicBackoff()=readerTest {env ->
        val wire=DiscoverSessionWire();var epoch:Long?=0
        val parent=open(env,wire,{epoch})
        wire.retryAfter="120"
        parent.openDiscoverClient().add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        epoch=null;parent.reader.rejectServerIdentity()
        advanceTimeBy(60_000);runCurrent();epoch=1
        assertIs<ReaderConnectionOutcome.Read>(parent.reader.reconnect());runCurrent()
        assertEquals(1,wire.wishParameters.size,"same-account recovery cannot bypass Retry-After")
        advanceTimeBy(59_999);runCurrent();assertEquals(1,wire.wishParameters.size)
        advanceTimeBy(1);runCurrent()
        assertEquals(2,wire.wishParameters.size);assertEquals(0L,parent.pendingChangeCount())
    }
    @Test fun closeOrIdentityRetirementCancelsWishWakeup()=runTest {
        for(close in listOf(false,true)) withReaderEnv {isolated ->
            val wire=DiscoverSessionWire();var epoch:Long?=0
            val parent=open(isolated,wire,{epoch})
            wire.retryAfter="120"
            parent.openDiscoverClient().add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
            assertEquals(1,wire.wishParameters.size)
            val retry=assertNotNull(parent.reader.wishRetryJob)
            assertTrue(retry.isActive,"the positive control must have a live delayed retry")
            if(close)parent.closeDiscoverClients() else {
                epoch=null;parent.reader.rejectServerIdentity()
                epoch=1 // An external accept alone must not authorize an old timer.
            }
            assertTrue(retry.isCancelled,"${if(close) "close" else "identity rejection"} must cancel the job immediately")
            advanceTimeBy(120_000);runCurrent()
            assertEquals(1,wire.wishParameters.size)
            assertEquals(1L,parent.pendingChangeCount())
            assertTrue(parent.reader.uncaughtFailures.isEmpty())
        }
    }
    @Test fun oldWishReplyCannotAcknowledgeAfterSameReaderRecovery()=readerTest {env ->
        val wire=DiscoverSessionWire();var epoch:Long?=0
        val parent=open(env,wire,{epoch});val client=parent.openDiscoverClient()
        wire.hold="addToDulcetWishlist"
        client.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent();wire.entered.await()
        epoch=null;parent.reader.rejectServerIdentity();epoch=1
        val reconnect=async {parent.reader.reconnect()};runCurrent()
        wire.release.complete(Unit);runCurrent()
        assertIs<ReaderConnectionOutcome.Read>(reconnect.await())
        assertEquals(2,wire.wishParameters.size,"a retired reply cannot acknowledge the intent for the recovered lifetime")
        assertEquals(wire.wishParameters.first(),wire.wishParameters.last())
        assertEquals(0L,parent.pendingChangeCount());client.close()
    }
    @Test fun heldWishAcknowledgementAcrossExternalRejectAcceptKeepsIntent()=readerTest {env ->
        val wire=DiscoverSessionWire();var epoch:Long?=0
        val parent=open(env,wire,{epoch});val client=parent.openDiscoverClient()
        client.refreshWishlist(WishFilter());runCurrent();wire.hold="addToDulcetWishlist"
        client.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent();wire.entered.await()
        epoch=null;parent.reader.rejectServerIdentity();epoch=1;wire.release.complete(Unit);runCurrent()
        assertEquals(1L,parent.pendingChangeCount())
        assertEquals(1,wire.requests.count {it=="addToDulcetWishlist"})
        client.close()
    }
    @Test fun retiredSessionCannotReopenOnLateOnlineCallback()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire);val client=parent.openDiscoverClient()
        val values=mutableListOf<DiscoverPublication>();client.observe {values+=it}
        client.refreshWishlist(WishFilter());runCurrent();val before=wire.requests.size;val publications=values.size
        client.close();parent.setOnline(true);client.setQuery("old");advanceUntilIdle()
        assertEquals(before,wire.requests.size);assertEquals(publications,values.size)
        var result:DiscoverResult<DiscoverArtist>?=null
        client.artist(DISCOVER_MBID){result=it};runCurrent()
        assertEquals("cancelled",assertIs<DiscoverResult.Failed>(result).error.code)
    }
    @Test fun sharedDetectionAndOutboxAreNotDuplicatedByTwoClients()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire)
        val first=parent.openDiscoverClient();val second=parent.openDiscoverClient()
        assertFalse(wire.requests.contains("getOpenSubsonicExtensions"),"construction must be lazy")
        first.refreshWishlist(WishFilter());second.refreshWishlist(WishFilter());runCurrent()
        assertEquals(1,wire.requests.count {it=="getOpenSubsonicExtensions"})
        parent.setOnline(false);first.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        assertEquals(1L,parent.pendingChangeCount());first.close()
        parent.reader.reconnect();runCurrent()
        assertEquals(1,wire.requests.count {it=="addToDulcetWishlist"});assertEquals(0L,parent.pendingChangeCount())
        second.close()
    }
    @Test fun absentExtensionsNeverSendFeatureRequestsAndFailedDetectionIsUnresolved()=readerTest {env ->
        val wire=DiscoverSessionWire().apply {advertised=false};val parent=open(env,wire);val c=parent.openDiscoverClient()
        val pubs=mutableListOf<DiscoverPublication>();c.observe {pubs+=it};c.setQuery("positive");c.refreshWishlist(WishFilter());advanceUntilIdle();runCurrent()
        assertTrue(wire.requests.contains("getOpenSubsonicExtensions"));assertFalse(pubs.last().access.discoverAvailable)
        assertTrue(wire.requests.none {it.contains("Dulcet")});c.close()
    }
    @Test fun knownUnsupportedWishlistAddsNoDiscoveryRequestToReconnect()=readerTest {env ->
        val wire=DiscoverSessionWire().apply {advertised=false}
        val parent=open(env,wire);val client=parent.openDiscoverClient()
        val values=mutableListOf<DiscoverPublication>();client.observe {values+=it}
        // The Apple session requests the initial capability answer through this refresh.
        client.refreshWishlist(WishFilter());runCurrent()
        assertEquals(1,wire.requests.count {it=="getOpenSubsonicExtensions"})
        assertEquals("known",values.last().access.detection)
        assertFalse(values.last().access.wishlistAvailable)
        parent.setOnline(false);runCurrent()
        val before=wire.requests.size
        assertIs<ReaderConnectionOutcome.Read>(parent.reader.reconnect());runCurrent()
        assertFalse(wire.requests.drop(before).contains("getOpenSubsonicExtensions"),wire.requests.drop(before).toString())
        assertTrue(wire.requests.none {it.contains("Dulcet")})
        // An explicit refresh can discover support added since that definitive answer.
        wire.advertised=true;client.refreshWishlist(WishFilter());runCurrent()
        assertTrue(values.last().access.canReadWishlist)
        assertEquals(2,wire.requests.count {it=="getOpenSubsonicExtensions"})
        assertTrue(wire.requests.contains("getDulcetWishlist"))
        client.close()
    }
    @Test fun heldSearchAcrossRejectionCannotCacheReplacementRows()=readerTest {env ->
        val wire=DiscoverSessionWire();var epoch:Long?=0
        val parent=open(env,wire,{epoch});val c=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>();c.observe {pubs+=it}
        c.refreshWishlist(WishFilter());runCurrent();wire.hold="searchDulcetDiscover"
        c.setQuery("held");advanceTimeBy(350);runCurrent();wire.entered.await()
        epoch=null;parent.reader.rejectServerIdentity();wire.release.complete(Unit);runCurrent()
        val db=com.legitimateapps.dulcet.database.DulcetDatabase(env.driver)
        assertNull(DiscoverStore(db,ReaderEnv.BINDING.serverId).read("search:artist:held"))
        assertEquals("identity-changed",pubs.last().access.error?.code)
        assertTrue(pubs.last().groups.all {it.page==null && !it.loading});c.close()
    }
    @Test fun failedDetectionRetainsKnownMetadataWithoutDrainingQueuedWish()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire);val c=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>();c.observe {pubs+=it}
        c.refreshWishlist(WishFilter());runCurrent();assertTrue(pubs.last().access.canAddWish)
        parent.setOnline(false);c.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent()
        wire.extensionFailure=true;parent.reader.reconnect();runCurrent()
        assertEquals(1L,parent.pendingChangeCount());assertEquals(0,wire.requests.count {it=="addToDulcetWishlist"})
        assertEquals("unresolved",pubs.last().access.detection);assertTrue(pubs.last().access.discoverAvailable);assertFalse(pubs.last().access.canAddWish);c.close()
    }
    @Test fun withdrawnWishlistRefusesPendingIntentWithoutSendingIt()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire);val c=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>();c.observe {pubs+=it}
        c.refreshWishlist(WishFilter());runCurrent();parent.setOnline(false)
        c.add(sampleDiscoverItem(),WishSource("search",null)){};runCurrent();assertEquals(1L,parent.pendingChangeCount())
        wire.advertised=false;parent.reader.reconnect();runCurrent()
        assertEquals(0L,parent.pendingChangeCount());assertEquals(0,wire.requests.count {it=="addToDulcetWishlist"})
        assertEquals("unsupported",pubs.last().wishes.single().error?.code);c.close()
    }
    @Test fun bindingAndRemovalCountsIncludeInactiveWishNamespaces()=readerTest {env ->
        val db=com.legitimateapps.dulcet.database.DulcetDatabase(env.driver);val wire=DiscoverSessionWire();val parent=open(env,wire)
        DiscoverStore(db,ReaderEnv.BINDING.serverId).enqueue(sampleAdd());assertEquals(1L,parent.pendingChangeCount())
        val store=DiscoverStore(db,"inactive");store.enqueue(sampleAdd("other"));assertEquals(1L,store.pendingCount())
        val engine=DownloadPolicyEngine(db,DownloadFileStore((okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY/"dulcet-empty-inactive-wish-fixture").toString(),okio.FileSystem.SYSTEM));engine.removeAccountData("inactive")
        assertEquals(0L,store.pendingCount());assertEquals(1L,parent.pendingChangeCount())
    }
    @Test fun storageFailurePublishesUnknownPendingAndDisabledGates()=readerTest {env ->
        val parent=open(env,DiscoverSessionWire());val client=parent.openDiscoverClient()
        val values=mutableListOf<DiscoverPublication>();client.observe {values+=it}
        client.refreshWishlist(WishFilter());runCurrent();assertTrue(values.last().access.canAddWish)
        env.driver.execute(null,"DROP TABLE wish_intent",0).value
        var failed:DiscoverPublication?=null
        client.observe {failed=it}
        assertNotNull(failed,"storage failure must publish, never silently leave a writable snapshot")
        assertEquals("storage",failed.access.error?.code);assertNull(failed.pendingChangeCount)
        assertFalse(failed.access.canAddWish);assertFalse(failed.wishlistSettled);client.close()
    }
    @Test fun clearCacheRetainsWishIntentAndAcceptedLineage()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire)
        val db=com.legitimateapps.dulcet.database.DulcetDatabase(env.driver)
        val store=DiscoverStore(db,ReaderEnv.BINDING.serverId)
        store.enqueue(sampleAdd());store.putRead("positive",byteArrayOf(1),false,1)
        val identity=ServerIdentityPolicy.identity("dulcet","accepted-lineage")
        ServerIdentityStore(db).accept(ReaderEnv.BINDING.serverId,identity)
        assertNotNull(store.read("positive"));assertEquals(1L,parent.pendingChangeCount())
        db.serverDataQueries.deleteSeenCacheForServer(ReaderEnv.BINDING.serverId)
        assertNull(store.read("positive"));assertEquals(sampleAdd(),store.pending().single().mutation)
        assertEquals(1L,parent.pendingChangeCount());assertEquals(identity,ServerIdentityStore(db).read(ReaderEnv.BINDING.serverId))
    }
    @Test fun replacementConsentCancelKeepsEverything()=readerTest {env ->
        val wire=DiscoverSessionWire();val parent=open(env,wire)
        val db=com.legitimateapps.dulcet.database.DulcetDatabase(env.driver)
        val store=DiscoverStore(db,ReaderEnv.BINDING.serverId);store.enqueue(sampleAdd())
        store.putRead("positive",byteArrayOf(1),false,1)
        val identity=ServerIdentityPolicy.identity("dulcet","lineage")
        ServerIdentityStore(db).accept(ReaderEnv.BINDING.serverId,identity)
        assertTrue(ServerIdentityPolicy.isReplacement(identity,ServerIdentityPolicy.identity("dulcet","other")))
        // The consent UI's cancel makes no bind/accept/remove call. Closing its child only retires reads.
        parent.openDiscoverClient().close()
        assertEquals(sampleAdd(),store.pending().single().mutation);assertNotNull(store.read("positive"))
        assertEquals(identity,ServerIdentityStore(db).read(ReaderEnv.BINDING.serverId));assertEquals(1L,parent.pendingChangeCount())
    }
    @Test fun canonicalReconnectKeepsNamespaceButBindingChangeCountsAndPurgesWishes()=readerTest {env ->
        val db=com.legitimateapps.dulcet.database.DulcetDatabase(env.driver);env.store.bind(ReaderEnv.BINDING)
        val store=DiscoverStore(db,ReaderEnv.BINDING.serverId);store.enqueue(sampleAdd())
        env.store.bind(ReaderEnv.BINDING.copy(normalizedBaseUrl="https://MUSIC.example:443/"));assertEquals(1L,store.pendingCount())
        val rebound=env.store.bind(ReaderEnv.BINDING.copy(username="another"))
        assertEquals(1L,rebound.discardedPendingChanges);assertEquals(0L,store.pendingCount())
    }
}
