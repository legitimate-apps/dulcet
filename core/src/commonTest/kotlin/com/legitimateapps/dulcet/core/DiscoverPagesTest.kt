@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class DiscoverPagesTest {
    @Test fun editionChangeCannotAppendPreviousReleaseTracks() = discoverStoreTest { env ->
        val release=CompletableDeferred<Unit>();val wire=DiscoverTestTransport()
        wire.onTracks={_,edition,_ -> withContext(NonCancellable){release.await()};DiscoverResult.Value(sampleDiscoverPage(edition))}
        val pages=DiscoverPages(this,wire,env.store(),{true})
        var first:DiscoverResult<DiscoverPage>?=null;var second:DiscoverResult<DiscoverAlbum>?=null
        pages.albumTracks("group","A",null){first=it};runCurrent()
        pages.album("group","B"){second=it};runCurrent()
        release.complete(Unit);advanceUntilIdle()
        assertEquals("cancelled",assertIs<DiscoverResult.Failed>(first).error.code)
        assertEquals("B",assertIs<DiscoverResult.Value<DiscoverAlbum>>(second).value.selectedReleaseId)
        pages.close()
    }
    @Test fun partial8of12IsNotPlayableAsCompleteAlbum() {
        val ref=DiscoverLibraryRef("A","album","album:opaque")
        val partial=sampleDiscoverItem(kind="album",status="partlyHeld",completeness="known",refs=listOf(ref)).let {
            it.copy(availability=it.availability.copy(matchedTrackCount=8,totalTrackCount=12)) }
        assertNull(DiscoverRoutePolicy.libraryTarget(partial))
        val song=sampleDiscoverItem(status="held",refs=listOf(ref.copy(type="song")))
        assertEquals("song",DiscoverRoutePolicy.libraryTarget(song)?.type)
        assertEquals(ref,DiscoverRoutePolicy.libraryTarget(partial.copy(availability=partial.availability.copy(status="held"))))
    }
    @Test fun unknownEnumsAndMissingMbidStayVisibleButUnwishable() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport().apply {onArtist={DiscoverResult.Value(DiscoverArtist(sampleDiscoverItem(mbid=null).copy(kind="future",wishable=false),sampleDiscoverPage()))}}
        val pages=DiscoverPages(this,wire,env.store(),{true})
        var result:DiscoverResult<DiscoverArtist>?=null
        pages.artist("opaque"){result=it};advanceUntilIdle()
        val item=assertIs<DiscoverResult.Value<DiscoverArtist>>(result).value.item
        assertEquals("future",item.kind);assertFalse(item.wishable);assertNull(item.target)
        assertNull(DiscoverRoutePolicy.libraryTarget(item));pages.close()
    }
    @Test fun serverVersionMismatchNeverClientRematches() {
        val live=sampleDiscoverItem().copy(title="Studio (Live)")
        assertNull(DiscoverRoutePolicy.libraryTarget(live))
        assertNull(DiscoverRoutePolicy.libraryTarget(live.copy(title="Studio")))
    }
    @Test fun ambiguousLibraryRefsRequireChoice() {
        val one=DiscoverLibraryRef("A","song","song:1")
        assertNull(DiscoverRoutePolicy.libraryTarget(sampleDiscoverItem(status="held",refs=listOf(one,one.copy(id="song:2")))))
        assertNull(DiscoverRoutePolicy.libraryTarget(sampleDiscoverItem(status="future",refs=listOf(one))))
        assertNull(DiscoverRoutePolicy.libraryTarget(sampleDiscoverItem(kind="album",status="held",refs=listOf(one))))
    }
    @Test fun artworkIsScopedAndNeverLoadsExternalLinks() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport();val store=env.store();val pages=DiscoverPages(this,wire,store,{true})
        var result:DiscoverResult<ByteArray>?=null
        pages.artwork("opaque:art"){result=it};advanceUntilIdle()
        assertContentEquals(byteArrayOf(-1,-40,-1),assertIs<DiscoverResult.Value<ByteArray>>(result).value)
        assertEquals(listOf("opaque:art"),wire.artworkRequests)
        assertFalse(DiscoverRoutePolicy.safeLink("javascript:alert(1)"))
        assertFalse(DiscoverRoutePolicy.safeLink("https://user:pass@example.test/pay"))
        assertTrue(DiscoverRoutePolicy.safeLink("https://example.test/buy"))
        pages.close()
    }
    @Test fun offlineFallbackRetainsAgeAndCancellationCompletesOnce() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport();val store=env.store();var online=true
        var result:DiscoverResult<DiscoverAlbum>?=null
        val first=DiscoverPages(this,wire,store,{online})
        first.album("group",null){result=it};advanceUntilIdle();first.close()
        online=false
        val second=DiscoverPages(this,wire,store,{online})
        second.album("group",null){result=it};advanceUntilIdle()
        val cached=assertIs<DiscoverResult.Value<DiscoverAlbum>>(result).value
        assertTrue(cached.tracks.age.fromDeviceCache)
        assertEquals(120L,cached.tracks.age.sourceAgeSeconds)
        second.close()
        online=true;val release=CompletableDeferred<Unit>()
        wire.onAlbum={id,edition ->withContext(NonCancellable){release.await()};DiscoverResult.Value(DiscoverAlbum(sampleDiscoverItem(id),emptyList(),edition,sampleDiscoverPage()))}
        val third=DiscoverPages(this,wire,store,{online});var completions=0
        val op=third.album("other",null){completions++};runCurrent();op.cancel();release.complete(Unit);advanceUntilIdle()
        assertEquals(1,completions);third.close()
    }
}
