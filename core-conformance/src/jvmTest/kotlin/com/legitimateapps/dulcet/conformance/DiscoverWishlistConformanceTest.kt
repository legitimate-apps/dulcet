package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

private fun fixtureRequest(): DiscoverConformanceRequest {
    fun required(name:String)=requireNotNull(System.getenv("DULCET_DISCOVER_$name")) {"Run inside with-discover-wishlist-fixture; no default server is allowed"}
    check(required("DISPOSABLE")=="true")
    return DiscoverConformanceRequest(required("SERVER_URL"),required("NAVIDROME_URL"),required("CONTROL_URL"),
        required("USERNAME"),required("PASSWORD"),required("OTHER_USERNAME"),required("OTHER_PASSWORD"))
}

class DiscoverWishlistTransportComponentsTest {
    @Test fun restAdaptersAndDurableOutboxUseDisposableServer() = runBlocking {
        val result=DiscoverWishlistConformanceContract.observeComponents(fixtureRequest())
        assertEquals(20,result.firstPageRows);assertTrue(result.secondPageRows>0)
        assertEquals(8,result.partialMatchedTracks);assertTrue(result.artworkValid)
        assertEquals(1L,result.pendingAfterLostReceipt);assertEquals(0L,result.pendingAfterRemoval)
        assertTrue(result.wishAbsentAfterRemoval);assertTrue(result.otherOwnerUnchanged)
        assertTrue(result.restRequests.containsAll(listOf("getOpenSubsonicExtensions","searchDulcetDiscover","getDulcetDiscoverAlbum","getDulcetDiscoverCoverArt","addToDulcetWishlist","removeFromDulcetWishlist")))
    }
    @Test fun navidromeBaselineIsHealthyAndObservedBeforeAnyAbsenceClaim() = runBlocking {
        val result=DiscoverWishlistConformanceContract.navidromeBaseline(fixtureRequest())
        assertTrue(result.login);assertTrue(result.albumCount>0)
        assertTrue(result.requests.containsAll(listOf("ping","getAlbumList2","getOpenSubsonicExtensions")))
        assertFalse(result.discoverAdvertised);assertFalse(result.wishlistAdvertised)
    }
}

/** Reader-owned Discover session over disposable public REST endpoints. */
class DiscoverWishlistConformanceTest {
    @Test fun productionRestReaderAndOutboxRoundTrip() = runBlocking {
        val result=DiscoverWishlistConformanceContract.observe(fixtureRequest())
        assertTrue(result.restRequests.contains("addToDulcetWishlist"))
    }
    @Test fun plainNavidromeGetsOnlyBaselineRequests() = runBlocking {
        val request=fixtureRequest()
        val baseline=DiscoverWishlistConformanceContract.navidromeBaseline(request)
        assertTrue(baseline.login && baseline.albumCount>0)
        assertTrue(baseline.requests.contains("getAlbumList2"))
        val result=DiscoverWishlistConformanceContract.observe(request)
        assertTrue(result.navidromeAdditionalRequests.isEmpty())
    }
    @Test fun realScanArrivalAndLostResponseRemoval() = runBlocking {
        val result=DiscoverWishlistConformanceContract.observe(fixtureRequest())
        assertTrue(result.scanFulfilled);assertTrue(result.myLibraryContainsArrival);assertTrue(result.wishAbsentAfterRemoval)
    }
}

/** Shelf and playlist wire projections, cache states and healthy Navidrome control. */
class DiscoverShelvesConformanceTest {
    @Test fun shelvesPlaylistsSourcesCacheAndNavidromeControl() = runBlocking {
        assertTrue(DiscoverWishlistConformanceContract.verifyShelves(fixtureRequest()))
    }
}
