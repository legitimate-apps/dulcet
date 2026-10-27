package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/** DTO-only checks; Task 8 also needs real owner/main-thread/close tests in its facade class. */
@OptIn(ExperimentalForeignApi::class)
class AppleDiscoverDtosTest {
    @Test fun shelvesPlaylistsAndHomeCopyListsAndPreserveNullableMetadata() {
        val summary=DiscoverPlaylistSummary("opaque","Mix",null,null)
        val summaries=mutableListOf(summary)
        val items=mutableListOf(sampleDiscoverItem())
        val age=DiscoverAge(null,null,100,true,true)
        val page=DiscoverShelfPage("publicPlaylists",items,summaries,"cursor",age,DiscoverFailure("offline",null,true)).appleDiscover()
        items.clear();summaries.clear()
        assertEquals(1,page.itemCount);assertEquals(1,page.playlistCount)
        assertNull(page.itemAt(-1));assertNull(page.playlistAt(1))
        assertNull(page.playlistAt(0)!!.trackCount);assertNull(page.playlistAt(0)!!.creator)
        assertEquals("offline",page.refreshError!!.code)
        val entries=mutableListOf(DiscoverPlaylistEntry(1,sampleDiscoverItem()),DiscoverPlaylistEntry(2,sampleDiscoverItem()))
        val playlist=DiscoverPlaylist(summary,entries,null,age).appleDiscover();entries.clear()
        assertEquals(2,playlist.entryCount);assertNull(playlist.entryAt(2));assertEquals(2,playlist.entryAt(1)!!.position)
        val home=DiscoverHome(listOf(DiscoverShelfState("publicPlaylists",null,false,null)),emptyList(),false,true,null).appleDiscover()
        assertEquals(1,home.shelfCount);assertNull(home.shelfAt(1));assertNull(home.recentWishAt(0))
    }

    @Test
    fun nullableNumbersRemainNullAndZeroRemainsZero() {
        val unknown = sampleDiscoverItem().appleDiscover()
        assertNull(unknown.year)
        assertNull(unknown.disc)
        assertNull(unknown.position)
        assertNull(unknown.listenCount)
        assertNull(unknown.availability.matchedTrackCount)
        assertNull(unknown.availability.totalTrackCount)
        val zero = sampleDiscoverItem().copy(year=0,disc=0,position=0,listenCount=0,
            availability=sampleDiscoverItem().availability.copy(matchedTrackCount=0,totalTrackCount=0)).appleDiscover()
        assertEquals(0,zero.year)
        assertEquals(0,zero.disc)
        assertEquals(0,zero.position)
        assertEquals(0L,zero.listenCount)
        assertEquals(0,zero.availability.matchedTrackCount)
        assertEquals(0,zero.availability.totalTrackCount)
        assertNull(DiscoverAge(null,null,1,false,true).appleDiscover().fetchedAtMillis)
        assertEquals(0L,DiscoverAge(0,0,1,false,true).appleDiscover().sourceAgeSeconds)
        assertNull(DiscoverFailure("offline",null,true).appleDiscover().retryAtMillis)
        assertEquals(0L,DiscoverFailure("rate-limited",0,true).appleDiscover().retryAtMillis)
        assertNull(DiscoverEdition("edition",null,null,emptyList()).appleDiscover().trackCount)
        assertEquals(0,DiscoverEdition("edition",null,0,emptyList()).appleDiscover().trackCount)
        assertNull(WishSnapshot("title",null,null,null).appleDiscover().year)
        assertEquals(0,WishSnapshot("title",null,0,null).appleDiscover().year)
        assertNull(sampleWish().appleDiscover().fulfilledAtMillis)
        assertNull(sampleWish().appleDiscover().noteAtMillis)
        assertEquals(0L,sampleWish().copy(noteAtMillis=0,fulfilledAtMillis=0).appleDiscover().fulfilledAtMillis)
        assertNull(publication().appleDiscover().pendingChangeCount)
        assertEquals(0L,publication().copy(pendingChangeCount=0).appleDiscover().pendingChangeCount)
    }

    @Test
    fun everyListAccessorReturnsNullOutsideBoundsAndCopiesItsInput() {
        val ref=DiscoverLibraryRef("provider","song","song")
        val link=DiscoverLink("homepage","https://example.invalid")
        val item=sampleDiscoverItem(refs=listOf(ref)).copy(links=listOf(link))
        val inputs=mutableListOf(item)
        val page=DiscoverPage(inputs,null,DiscoverAge(null,null,1,false,false)).appleDiscover()
        inputs.clear()
        assertEquals(1,page.itemCount)
        assertNull(page.itemAt(-1));assertNull(page.itemAt(1));assertNotNull(page.itemAt(0))
        val copy=page.itemAt(0)!!
        assertEquals(1,copy.linkCount);assertNull(copy.linkAt(-1));assertNull(copy.linkAt(1))
        assertEquals("homepage",copy.linkAt(0)?.relation)
        val availability=copy.availability
        assertEquals(1,availability.libraryItemCount)
        assertNull(availability.libraryItemAt(-1));assertNull(availability.libraryItemAt(1))
        assertEquals("song",availability.libraryItemAt(0)?.id)
        val edition=DiscoverEdition("edition",null,null,listOf(link)).appleDiscover()
        assertEquals(1,edition.linkCount);assertNull(edition.linkAt(-1));assertNull(edition.linkAt(1))
        val album=DiscoverAlbum(item,listOf(DiscoverEdition("edition",null,1,emptyList())),"edition",sampleDiscoverPage()).appleDiscover()
        assertEquals(1,album.editionCount);assertNull(album.editionAt(-1));assertNull(album.editionAt(1))
        assertEquals("edition",album.editionAt(0)?.releaseId)
        val pub=publication().appleDiscover()
        assertEquals(1,pub.groupCount);assertNull(pub.groupAt(-1));assertNull(pub.groupAt(1))
        assertEquals(1,pub.wishCount);assertNull(pub.wishAt(-1));assertNull(pub.wishAt(1))
        assertEquals("song",pub.groupAt(0)?.kind);assertEquals("pending",pub.wishAt(0)?.key)
    }

    @Test
    fun commandInputsRoundTripWithoutCommonTypesCrossingThePublicSurface() {
        val item=sampleDiscoverItem().copy(links=listOf(DiscoverLink("artist","https://example.invalid")))
        assertEquals(item,item.appleDiscover().common())
        assertEquals(WishSource("album","origin"),AppleWishSource("album","origin").common())
        assertEquals(WishFilter("album","wanted",true),AppleWishFilter("album","wanted",true).common())
        assertEquals(DiscoverLibraryRef("p","song","s"),AppleDiscoverLibraryRef("p","song","s").common())
        for (kind in DiscoverKind.entries) {
            val target=DiscoverTarget(kind,"mbid")
            assertEquals(kind.wire,target.appleDiscover().kind)
            assertEquals(target,target.appleDiscover().common())
        }
        assertNull(AppleDiscoverTarget("future","mbid").common())
        val future=sampleDiscoverItem(mbid=null).copy(kind="future",wishable=false)
        assertEquals(future,future.appleDiscover().common())
    }

    @Test
    fun pendingAddCarriesOnlyTheImmutableAppleSnapshotAndWireKind() {
        val add=sampleAdd()
        val row=WishRow("stable",null,add,true,null).appleDiscover()
        assertEquals("stable",row.key);assertNull(row.wish)
        val pending=assertNotNull(row.pendingAdd)
        assertEquals(add.operationId,pending.operationId)
        assertEquals(add.actionTimeMillis,pending.actionTimeMillis)
        assertEquals("song",pending.target.kind)
        assertEquals(add.snapshot.title,pending.snapshot.title)
        assertEquals(add.source.type,pending.source.type)
        assertTrue(row.pending)
    }

    @Test
    fun everyTypedFailureResultHasOnlyAnErrorAndPreservesRetryTime() {
        val failure=DiscoverResult.Failed(DiscoverFailure("rate-limited",120_000,true))
        val artist=failure.appleDiscoverArtistResult()
        val album=failure.appleDiscoverAlbumResult()
        val page=failure.appleDiscoverPageResult()
        val enqueue=failure.appleWishEnqueueResult()
        val artwork=failure.appleDiscoverArtworkResult()
        assertNull(artist.value);assertNull(album.value);assertNull(page.value);assertNull(enqueue.value);assertNull(artwork.bytes)
        for (error in listOf(artist.error,album.error,page.error,enqueue.error,artwork.error)) {
            assertEquals("rate-limited",assertNotNull(error).code)
            assertEquals(120_000L,error.retryAtMillis)
            assertTrue(error.retryable)
        }
        assertNotNull(DiscoverResult.Value(DiscoverArtist(sampleDiscoverItem(),sampleDiscoverPage())).appleDiscoverArtistResult().value)
        assertNull(DiscoverResult.Value(DiscoverAlbum(sampleDiscoverItem(),emptyList(),null,sampleDiscoverPage())).appleDiscoverAlbumResult().error)
        assertNotNull(DiscoverResult.Value(sampleDiscoverPage()).appleDiscoverPageResult().value)
        assertEquals("operation",DiscoverResult.Value(WishEnqueued("operation","row")).appleWishEnqueueResult().value?.operationId)
    }

    @Test
    fun artworkCopiesBytesIntoNSDataAndHandlesEmptyInput() {
        val input=byteArrayOf(1,2,3)
        val result=DiscoverResult.Value(input).appleDiscoverArtworkResult()
        input[0]=9
        val bytes=assertNotNull(result.bytes)
        assertEquals(3UL,bytes.length);assertNull(result.error)
        val actual=ByteArray(3)
        actual.usePinned { memcpy(it.addressOf(0),bytes.bytes,bytes.length) }
        assertEquals(listOf<Byte>(1,2,3),actual.toList())
        assertEquals(0UL,DiscoverResult.Value(byteArrayOf()).appleDiscoverArtworkResult().bytes?.length)
    }

    private fun publication()=DiscoverPublication("provider",1,
        DiscoverFeatureAccess("known",null,true,true,true,true,true,true,true),"online","query",
        listOf(DiscoverGroup("song",sampleDiscoverPage(),false,null)),
        listOf(WishRow("pending",null,sampleAdd(),true,null)),false,null,true,null)
}
