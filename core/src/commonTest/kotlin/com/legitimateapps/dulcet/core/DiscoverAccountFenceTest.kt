@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class DiscoverSessionWire:LibraryEndpointTransport {
    val baseline=SessionTestServer(FakeReaderServer(albumCount=3))
    val requests=mutableListOf<String>()
    var advertised=true
    var hold:String?=null
    var entered=CompletableDeferred<Unit>()
    var release=CompletableDeferred<Unit>()
    var extensionFailure=false
    override suspend fun request(endpoint:String,parameters:Map<String,String>):LibraryEndpointResponse {
        requests+=endpoint
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
        backgroundScope,formPost=false,foreground=false,identityAuthorization=authorization,validateServerIdentity=validate
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
