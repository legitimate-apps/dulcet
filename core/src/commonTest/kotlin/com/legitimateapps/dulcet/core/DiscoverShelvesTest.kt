@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class DiscoverShelvesTest {
    @Test fun restShelvesAndPlaylistsPreserveOpaqueCursorsDuplicatesAndMissingTargets() = readerTest {env ->
        val wire=DiscoverWireFixture()
        val transport=RestDiscoverTransport(env.reader(transport=wire),{123L})
        for(id in discoverShelfIds.filter {it!="publicPlaylists"}) {
            wire.json={_,_ -> discoverEnvelope("\"dulcetDiscover\":"+discoverPageJson(extra=",\"nextCursor\":\"opaque:/+?\""))}
            val page=assertIs<DiscoverResult.Value<DiscoverShelfPage>>(transport.shelf(id,"cursor:/+?",12)).value
            assertEquals(id,page.id);assertEquals("opaque:/+?",page.nextCursor);assertTrue(page.age.stale)
            assertEquals(123L,page.age.receivedAtMillis);assertEquals(DISCOVER_MBID,page.items.single().target?.mbid)
            assertEquals(mapOf("id" to id,"cursor" to "cursor:/+?","size" to "12"),wire.requests.last().second)
        }
        val summary="""{"id":"playlist:/+?","title":"Public mix","creator":"Curator","trackCount":3}"""
        wire.json={_,_ -> discoverEnvelope("\"dulcetDiscoverPlaylists\":{\"playlist\":[$summary],\"nextCursor\":\"more\"}")}
        val shelf=assertIs<DiscoverResult.Value<DiscoverShelfPage>>(transport.shelf("publicPlaylists",null)).value
        assertTrue(shelf.items.isEmpty());assertEquals("playlist:/+?",shelf.playlists.single().id)
        val list=assertIs<DiscoverResult.Value<DiscoverPlaylistList>>(transport.playlists("A & B",null)).value
        assertEquals(shelf.playlists,list.items)
        assertEquals(mapOf("query" to "A & B","size" to "20"),wire.requests.last().second)
        val missing=discoverItemJson.replace("\"musicbrainzRecordingId\":\"$DISCOVER_MBID\"","")
        wire.json={_,_ -> discoverEnvelope("\"dulcetDiscoverPlaylist\":{\"summary\":$summary,\"entries\":{\"entry\":[{\"position\":1,\"item\":$discoverItemJson},{\"position\":2,\"item\":$discoverItemJson},{\"position\":3,\"item\":$missing}],\"stale\":true}}")}
        val playlist=assertIs<DiscoverResult.Value<DiscoverPlaylist>>(transport.playlist("playlist:/+?","next:/+?")).value
        assertEquals(listOf(1,2,3),playlist.entries.map {it.position})
        assertEquals(playlist.entries[0].item.id,playlist.entries[1].item.id)
        assertFalse(playlist.entries[2].item.wishable);assertNull(playlist.entries[2].item.target)
        assertEquals(mapOf("mbid" to "playlist:/+?","cursor" to "next:/+?","size" to "20"),wire.requests.last().second)
        wire.json={_,_ -> discoverEnvelope("\"dulcetDiscoverPlaylist\":{\"summary\":$summary,\"entries\":{}}")}
        assertEquals("invalid-response",assertIs<DiscoverResult.Failed>(transport.playlist("p",null)).error.code)
        val before=wire.requests.size
        assertIs<DiscoverResult.Failed>(transport.shelf("unknown",null));assertEquals(before,wire.requests.size)
    }

    @Test fun shelfCacheRetainsAgeAndErrorAndDoesNotCrossCursorOrSize() = discoverStoreTest {env ->
        val wire=DiscoverTestTransport()
        var calls=0
        wire.onShelf={id,cursor,size -> calls++;DiscoverResult.Value(DiscoverShelfPage(id,listOf(sampleDiscoverItem("$cursor:$size")),emptyList(),"more",DiscoverAge(100,4,200,true,false)))}
        var online=true
        val pages=DiscoverPages(this,wire,env.store(),{online})
        suspend fun read(cursor:String?=null,size:Int=20):DiscoverResult<DiscoverShelfPage> {
            var answer:DiscoverResult<DiscoverShelfPage>?=null
            pages.shelf("newReleases",cursor,{answer=it},size);advanceUntilIdle();return answer!!
        }
        assertEquals("null:12",assertIs<DiscoverResult.Value<DiscoverShelfPage>>(read(size=12)).value.items.single().id)
        wire.onShelf={_,_,_ -> DiscoverResult.Failed(DiscoverFailure("upstream-unavailable",null,true))}
        val cached=assertIs<DiscoverResult.Value<DiscoverShelfPage>>(read(size=12)).value
        assertEquals("upstream-unavailable",cached.refreshError?.code);assertTrue(cached.age.fromDeviceCache)
        assertEquals(100L,cached.age.fetchedAtMillis);assertEquals(200L,cached.age.receivedAtMillis)
        assertIs<DiscoverResult.Failed>(read());assertIs<DiscoverResult.Failed>(read("other",12))
        online=false
        assertEquals("offline",assertIs<DiscoverResult.Value<DiscoverShelfPage>>(read(size=12)).value.refreshError?.code)
        assertEquals(1,calls);pages.close()
    }

    @Test fun homeRefreshesOldOrIncompleteSnapshotsButKeepsFreshCompleteShelves() = discoverStoreTest { env ->
        val store = env.store()
        val pages = DiscoverPages(this, DiscoverTestTransport(), store, { true })
        val received = 1_000L
        val image = "artist-photo"
        val artist = sampleDiscoverItem(kind = "artist").copy(artworkId = image)
        val fresh = DiscoverShelfPage("trendingArtists", listOf(artist), emptyList(), null,
            DiscoverAge(received, 0, received, false, true))

        assertTrue(pages.needsHomeRefresh(null, received))
        assertTrue(pages.needsHomeRefresh(fresh, received), "the snapshot names a photo the device lacks")
        store.putRead("artwork:$image", byteArrayOf(1), true, store.nextIssue())
        assertFalse(pages.needsHomeRefresh(fresh, received + 29 * 60 * 1000))
        assertTrue(pages.needsHomeRefresh(fresh, received + 30 * 60 * 1000))
        assertTrue(pages.needsHomeRefresh(fresh.copy(age = fresh.age.copy(stale = true)), received))
        assertTrue(pages.needsHomeRefresh(fresh.copy(items = listOf(artist.copy(artworkId = null))), received))
        assertFalse(pages.needsHomeRefresh(fresh.copy(items = listOf(artist.copy(artworkId = null, target = null))), received))
        pages.close()
    }

    @Test fun playlistCacheAndShelfReadsShareMonotonicRateLimitAcrossWallRollback() = discoverStoreTest {env ->
        val wire=DiscoverTestTransport();var playlistCalls=0;var shelfCalls=0;var now=1000L
        val summary=DiscoverPlaylistSummary("playlist","Mix",null,2)
        wire.onPlaylist={_,_ -> playlistCalls++;DiscoverResult.Value(DiscoverPlaylist(summary,listOf(DiscoverPlaylistEntry(1,sampleDiscoverItem()),DiscoverPlaylistEntry(2,sampleDiscoverItem())),"cursor",sampleDiscoverPage().age))}
        wire.onShelf={_,_,_ -> shelfCalls++;DiscoverResult.Failed(DiscoverFailure("rate-limited",61000L,true))}
        val pages=DiscoverPages(this,wire,env.store(),{true},backoff=DiscoverBackoff({now},testScheduler.timeSource))
        var first:DiscoverResult<DiscoverPlaylist>?=null
        pages.playlist("playlist",null){first=it};runCurrent()
        assertEquals(2,assertIs<DiscoverResult.Value<DiscoverPlaylist>>(first).value.entries.size)
        pages.shelf("trendingSongs",null,{});runCurrent();assertEquals(1,shelfCalls)
        now=-100000L
        var cached:DiscoverResult<DiscoverPlaylist>?=null
        pages.playlist("playlist",null){cached=it};runCurrent()
        val value=assertIs<DiscoverResult.Value<DiscoverPlaylist>>(cached).value
        assertTrue(value.age.fromDeviceCache);assertEquals("rate-limited",value.refreshError?.code)
        assertEquals(1,playlistCalls)
        pages.shelf("newReleases",null,{});runCurrent();assertEquals(1,shelfCalls)
        advanceTimeBy(60001)
        pages.playlist("playlist",null){cached=it};runCurrent()
        assertEquals(2,playlistCalls);assertNull(assertIs<DiscoverResult.Value<DiscoverPlaylist>>(cached).value.refreshError)
        pages.close()
    }

    @Test fun closedAndRetiredShelfCannotPublishOrCacheLateResult() = discoverStoreTest {env ->
        val release=CompletableDeferred<Unit>();val wire=DiscoverTestTransport()
        wire.onShelf={id,_,_ -> withContext(NonCancellable){release.await()};DiscoverResult.Value(DiscoverShelfPage(id,listOf(sampleDiscoverItem()),emptyList(),null,sampleDiscoverPage().age))}
        var active=true
        val pages=DiscoverPages(this,wire,env.store(),{true},{active})
        val answers=mutableListOf<DiscoverResult<DiscoverShelfPage>>()
        pages.shelf("trendingSongs",null,{answers+=it},12);runCurrent();active=false;pages.close()
        release.complete(Unit);advanceUntilIdle()
        assertEquals("cancelled",assertIs<DiscoverResult.Failed>(answers.single()).error.code)
        assertNull(pages.cachedShelf("trendingSongs"))
    }

    @Test fun homePublishesIndependentStatesAndRecentWishesAndNavidromeSendsNoFeatureReads() = readerTest {env ->
        val ordinary=DiscoverSessionWire()
        val shelfRequests=mutableListOf<String>()
        val wire=object:LibraryEndpointTransport by ordinary {
            override suspend fun request(endpoint:String,parameters:Map<String,String>):LibraryEndpointResponse {
                if(endpoint!="getDulcetDiscoverShelf")return ordinary.request(endpoint,parameters)
                val id=parameters.getValue("id");shelfRequests+=id
                val child=when(id) {
                    "similarArtists" -> "\"dulcetDiscover\":{\"item\":[]}"
                    "publicPlaylists" -> "\"dulcetDiscoverPlaylists\":{\"playlist\":[]}"
                    else -> "\"dulcetDiscover\":"+discoverPageJson()
                }
                val body=if(id=="trendingAlbums") """{"subsonic-response":{"status":"failed","error":{"code":0,"reason":"upstream-unavailable"}}}""" else discoverEnvelope(child)
                return LibraryEndpointResponse(200,body,"https://fixture.invalid?<redacted>")
            }
        }
        val parent=LibraryReaderSession(com.legitimateapps.dulcet.database.DulcetDatabase(env.driver),env.store.bind(ReaderEnv.BINDING),wire,
            backgroundScope,formPost=false,foreground=false)
        parent.reader.connect()
        val client=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>()
        client.observe {pubs+=it};assertNull(pubs.last().home)
        client.refreshHome();advanceUntilIdle();runCurrent()
        val home=assertNotNull(pubs.last().home)
        assertEquals(discoverShelfIds,home.shelves.map {it.id})
        assertEquals(discoverShelfIds,shelfRequests)
        assertTrue(home.shelves.none {it.loading})
        assertEquals("upstream-unavailable",home.shelves.first {it.id=="trendingAlbums"}.error?.code)
        assertTrue(home.shelves.first {it.id=="trendingSongs"}.page!!.items.isNotEmpty())
        assertTrue(home.shelves.first {it.id=="similarArtists"}.page!!.items.isEmpty())
        assertTrue(home.wishesSettled)
        assertTrue(ordinary.requests.contains("getDulcetWishlist"))
        client.close()
        // Same production session, positive catalog baseline, definitively absent extension.
        val navWire=DiscoverSessionWire().apply {advertised=false}
        val nav=LibraryReaderSession(com.legitimateapps.dulcet.database.DulcetDatabase(env.driver),env.store.bind(ReaderEnv.BINDING.copy(serverId="nav-control")),navWire,
            backgroundScope,formPost=false,foreground=false)
        nav.reader.connect();val navClient=nav.openDiscoverClient()
        val control=nav.reader.sendChecked("getAlbumList2",mapOf("type" to "alphabeticalByName","size" to "1"))
        assertTrue(parseReaderAlbumList(control.response.body).isNotEmpty())
        navClient.refreshHome();advanceUntilIdle();runCurrent()
        assertTrue(navWire.requests.contains("getOpenSubsonicExtensions"))
        assertTrue(navWire.requests.none {it.contains("Dulcet")})
        navClient.close();parent.closeDiscoverClients();nav.closeDiscoverClients()
    }

    @Test fun homeShelvesPlaylistsAndRecentWishesRecoverWithValidatedReaderAuthority() = readerTest {env ->
        val ordinary=DiscoverSessionWire()
        var recovered=false;var wishlistReads=0;var shelfReads=0;var epoch:Long?=0
        val summary="""{"id":"public-mix","title":"Public mix","trackCount":1}"""
        val wire=object:LibraryEndpointTransport by ordinary {
            override suspend fun request(endpoint:String,parameters:Map<String,String>):LibraryEndpointResponse {
                val child=when(endpoint) {
                    "getDulcetDiscoverShelf" -> {
                        shelfReads++
                        if(parameters["id"]=="publicPlaylists") "\"dulcetDiscoverPlaylists\":{\"playlist\":[$summary]}"
                        else "\"dulcetDiscover\":"+discoverPageJson()
                    }
                    "getDulcetWishlist" -> {
                        wishlistReads++
                        "\"dulcetWishlist\":{\"wish\":[${wishJson(if(recovered) "renewed-wish" else "initial-wish")}]}"
                    }
                    "searchDulcetDiscoverPlaylists" -> "\"dulcetDiscoverPlaylists\":{\"playlist\":[$summary]}"
                    "getDulcetDiscoverPlaylist" -> "\"dulcetDiscoverPlaylist\":{\"summary\":$summary,\"entries\":{\"entry\":[{\"position\":1,\"item\":$discoverItemJson}]}}"
                    else -> return ordinary.request(endpoint,parameters)
                }
                return LibraryEndpointResponse(200,discoverEnvelope(child),"https://fixture.invalid?<redacted>")
            }
        }
        val parent=LibraryReaderSession(com.legitimateapps.dulcet.database.DulcetDatabase(env.driver),env.store.bind(ReaderEnv.BINDING),wire,
            backgroundScope,config=LibraryReaderConfig(monotonic=testScheduler.timeSource),formPost=false,foreground=false,
            identityAuthorization={epoch},validateServerIdentity={null})
        parent.reader.connect()
        val client=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>()
        client.observe {pubs+=it};client.refreshHome();runCurrent()
        assertEquals("initial-wish",pubs.last().home!!.recentWishes.single().wish!!.id)
        assertTrue(shelfReads>=6)
        val wishlistReadsBefore=wishlistReads
        assertTrue(wishlistReadsBefore>0)
        epoch=null;parent.reader.rejectServerIdentity();runCurrent()
        assertEquals("identity-changed",pubs.last().access.error?.code)
        assertTrue(pubs.last().home!!.shelves.isEmpty())
        recovered=true;epoch=1
        assertIs<ReaderConnectionOutcome.Read>(parent.reader.reconnect());runCurrent()
        val home=assertNotNull(pubs.last().home)
        assertEquals(discoverShelfIds,home.shelves.map {it.id})
        assertTrue(home.shelves.all {it.page!=null && !it.loading && it.error==null})
        assertTrue(shelfReads>=12)
        assertTrue(wishlistReads>wishlistReadsBefore,"accepted recovery must refresh the home wishes reader too")
        assertTrue(home.wishesSettled);assertNull(home.wishesError)
        assertEquals("renewed-wish",home.recentWishes.single().wish!!.id)
        var list:DiscoverResult<DiscoverPlaylistList>?=null
        client.playlists("",null){list=it};runCurrent()
        assertEquals("public-mix",assertIs<DiscoverResult.Value<DiscoverPlaylistList>>(list).value.items.single().id)
        var page:DiscoverResult<DiscoverPlaylist>?=null
        client.playlist("public-mix",null){page=it};runCurrent()
        assertEquals(1,assertIs<DiscoverResult.Value<DiscoverPlaylist>>(page).value.entries.single().position)
        client.close();parent.closeDiscoverClients()
    }

    @Test fun reconnectKeepsHomeShelvesAcrossAnUnresolvedExtensionCheck() = readerTest { env ->
        val wire = HomeRecoveryWire()
        val parent = homeRecoverySession(env, wire)
        val client = parent.openDiscoverClient()
        val homes = mutableListOf<DiscoverHome>()
        var access: DiscoverFeatureAccess? = null
        client.observe { publication ->
            access = publication.access
            publication.home?.let(homes::add)
        }
        client.openHome()
        runCurrent()
        val initial = homes.last().shelves.map { assertNotNull(it.page).id }
        assertEquals(discoverShelfIds, initial)
        homes.clear()

        parent.setOnline(false)
        runCurrent()
        wire.server.extensionFailure = true
        parent.reader.setOnline(true, newPath = true)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(wire.server.requests.count { it == "getOpenSubsonicExtensions" } >= 2)
        assertEquals("unresolved", access?.detection)
        val shelvesAtThreeSeconds = homes.last().shelves.mapNotNull { it.page?.id }
        wire.server.extensionFailure = false
        assertIs<ReaderConnectionOutcome.Read>(parent.reader.reconnect())
        runCurrent()

        assertEquals(initial, shelvesAtThreeSeconds, "a failed extension probe must not empty Home at +3s")
        assertTrue(homes.all { home -> home.shelves.mapNotNull { it.page?.id } == initial },
            "every publication through recovery keeps the shelves")

        wire.server.advertised = false
        assertIs<ReaderConnectionOutcome.Read>(parent.reader.reconnect())
        runCurrent()
        assertEquals("known", access?.detection)
        assertEquals(false, access?.discoverAvailable)
        assertTrue(homes.last().shelves.isEmpty(), "a successful probe can establish that Discover was disabled")
        client.close()
        parent.closeDiscoverClients()
    }

    @Test fun reconnectWithShelvesKeepsLateRecentWishesFailureQuiet() = readerTest { env ->
        val wire = HomeRecoveryWire()
        val parent = homeRecoverySession(env, wire)
        val client = parent.openDiscoverClient()
        val homes = mutableListOf<DiscoverHome>()
        client.observe { it.home?.let(homes::add) }
        client.openHome()
        runCurrent()
        val initial = homes.last().shelves.map { assertNotNull(it.page).id }
        assertEquals(discoverShelfIds, initial)
        homes.clear()

        parent.setOnline(false)
        runCurrent()
        wire.wishFailureDelay = 30_000
        wire.albumShelfFailureDelay = 30_000
        parent.reader.setOnline(true, newPath = true)
        runCurrent()
        assertTrue(parent.reader.online)
        assertEquals(1, wire.delayedWishReads, "reconnect itself issued getDulcetWishlist")
        val restoredAt = testScheduler.currentTime
        val samples = mutableMapOf<Long, List<String>>()
        for (second in listOf(3L, 6L, 10L, 30L, 60L)) {
            advanceTimeBy(restoredAt + second * 1000 - testScheduler.currentTime)
            runCurrent()
            samples[second] = homes.last().refreshFeedback()
        }

        assertEquals(1, wire.deliveredWishFailures, "the working path returned HTTP 504 for getDulcetWishlist")
        assertEquals(1, wire.deliveredAlbumShelfFailures, "trendingAlbums failed and fell back to its cached page")
        assertTrue(homes.all { home -> home.shelves.mapNotNull { it.page?.id } == initial })
        assertEquals(samples.keys.associateWith { emptyList<String>() }, samples)
        assertTrue(homes.all { it.refreshFeedback().isEmpty() }, "no intermediate publication should show Try Again")

        homes.clear()
        client.refreshHome()
        runCurrent()
        assertTrue(homes.isNotEmpty() && homes.all { it.refreshFeedback().isEmpty() },
            "an explicit refresh starts without replaying the automatic failure")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, wire.deliveredWishFailures)
        assertEquals(listOf("trendingAlbums:upstream-unavailable", "recentWishes:upstream-unavailable"),
            homes.last().refreshFeedback(), "a failed refresh the listener requested can report a failure")
        client.close()
        parent.closeDiscoverClients()
    }

    @Test fun replacedPathDiscardsAnOldManualRefreshBeforeTheNewEpochAnswers() = readerTest { env ->
        val wire = HomeRecoveryWire()
        val parent = homeRecoverySession(env, wire)
        val client = parent.openDiscoverClient()
        val homes = mutableListOf<DiscoverHome>()
        client.observe { it.home?.let(homes::add) }
        client.openHome()
        runCurrent()

        wire.wishFailureDelay = 5_000
        client.refreshHome()
        runCurrent()
        assertEquals(1, wire.delayedWishReads)
        advanceTimeBy(1_000)
        homes.clear()

        // A satisfied-to-satisfied handoff leaves the reader online. The epoch cannot answer yet.
        val epoch = CompletableDeferred<Unit>()
        wire.holdScanStatus = epoch
        parent.reader.setOnline(true, newPath = true)
        val reconnect = backgroundScope.async { parent.reader.reconnect() }
        runCurrent()
        assertTrue(parent.reader.online)
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(reconnect.isActive)
        assertEquals(1, wire.deliveredWishFailures, "the retired path completed its request")
        assertTrue(homes.isNotEmpty(), "the old request settled")
        assertTrue(homes.all { it.refreshFeedback().isEmpty() }, "only the restored path can report an outcome")

        wire.wishFailureDelay = null
        epoch.complete(Unit)
        runCurrent()
        assertIs<ReaderConnectionOutcome.Read>(reconnect.await())
        assertTrue(homes.last().refreshFeedback().isEmpty())
        client.close()
        parent.closeDiscoverClients()
    }

    private fun DiscoverHome.refreshFeedback() = buildList {
        for (shelf in shelves) {
            val failure = shelf.error ?: shelf.page?.refreshError
            if (failure != null) add("${shelf.id}:${failure.code}")
            if (shelf.page?.age?.stale == true) add("${shelf.id}:stale")
        }
        wishesError?.let { add("recentWishes:${it.code}") }
    }

    private suspend fun TestScope.homeRecoverySession(env: ReaderEnv, wire: HomeRecoveryWire) =
        LibraryReaderSession(com.legitimateapps.dulcet.database.DulcetDatabase(env.driver),
            env.store.bind(ReaderEnv.BINDING), wire, backgroundScope,
            config = LibraryReaderConfig(monotonic = testScheduler.timeSource), formPost = false, foreground = false)
            .also { it.reader.connect() }

    private class HomeRecoveryWire(val server: DiscoverSessionWire = DiscoverSessionWire()) :
        LibraryEndpointTransport by server {
        var wishFailureDelay: Long? = null
        var albumShelfFailureDelay: Long? = null
        var holdScanStatus: CompletableDeferred<Unit>? = null
        var delayedWishReads = 0
        var deliveredWishFailures = 0
        var deliveredAlbumShelfFailures = 0

        override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
            if (endpoint == "getDulcetWishlist") wishFailureDelay?.let { wait ->
                delayedWishReads++
                delay(wait)
                deliveredWishFailures++
                return LibraryEndpointResponse(504, "Gateway Timeout", "https://fixture.invalid?<redacted>")
            }
            if (endpoint == "getScanStatus") holdScanStatus?.await()
            if (endpoint != "getDulcetDiscoverShelf") return server.request(endpoint, parameters)
            val id = parameters["id"]
            if (id == "trendingAlbums") albumShelfFailureDelay?.let { wait ->
                delay(wait)
                deliveredAlbumShelfFailures++
                return LibraryEndpointResponse(504, "Gateway Timeout", "https://fixture.invalid?<redacted>")
            }
            val field = if (id == "publicPlaylists") {
                """"dulcetDiscoverPlaylists":{"playlist":[]}"""
            } else {
                """"dulcetDiscover":{"item":[$discoverItemJson],"ageSeconds":1}"""
            }
            return LibraryEndpointResponse(200, discoverEnvelope(field), "https://fixture.invalid?<redacted>")
        }
    }

    @Test fun homeCacheReadFailurePublishesStorageWithoutEscapingReader() = readerTest {env ->
        val wire=DiscoverSessionWire()
        val parent=LibraryReaderSession(com.legitimateapps.dulcet.database.DulcetDatabase(env.driver),env.store.bind(ReaderEnv.BINDING),wire,
            env.reader().scope,formPost=false,foreground=false)
        parent.reader.connect()
        val client=parent.openDiscoverClient();val pubs=mutableListOf<DiscoverPublication>()
        client.observe {pubs+=it};client.refreshWishlist(WishFilter());advanceUntilIdle()
        env.driver.failRead={it.contains("discover_read",ignoreCase=true)}
        client.refreshHome();advanceUntilIdle()
        assertTrue(env.driver.failedReads>0,"cache read fault must fire")
        assertTrue(env.uncaught.isEmpty() && parent.reader.uncaughtFailures.isEmpty(),"storage failure must be published instead of reaching the reader backstop")
        val home=assertNotNull(pubs.last().home)
        assertEquals(6,home.shelves.size)
        assertTrue(home.shelves.all {it.error?.code=="storage" && !it.loading})
        env.driver.failRead=null;client.close();parent.closeDiscoverClients()
    }

    @Test fun recentWishesUseActionTimeAndStableTieBreakAndKeepPendingRows() {
        val rows=(1..8).map {WishRow("$it",sampleWish("$it").copy(addedAtMillis=it.toLong()),null,false,null)}+
            WishRow("pending",null,sampleAdd(time=20),true,null)
        val recent=recentDiscoverWishes(rows)
        assertEquals(listOf("pending","8","7","6","5","4"),recent.map {it.key})
        assertTrue(recent.first().pending)
    }

    @Test fun shelfAndPlaylistWishSourcesUseExistingDurableOutbox() = discoverStoreTest {env ->
        val store=env.store();var serial=0
        val outbox=WishOutbox(store,WishlistTestWire(),{allDiscoverAccess()},uuid={"operation-${++serial}"})
        for(source in listOf(WishSource("shelf","trendingSongs"),WishSource("playlist","playlist:/+?"))) {
            assertIs<DiscoverResult.Value<WishEnqueued>>(outbox.enqueueAdd(sampleDiscoverItem(),source))
        }
        assertEquals(listOf("shelf","playlist"),store.intents().map {(it.mutation as WishMutation.Add).source.type})
        assertEquals("playlist:/+?",(store.intents().last().mutation as WishMutation.Add).source.id)
        outbox.close()
    }
}
