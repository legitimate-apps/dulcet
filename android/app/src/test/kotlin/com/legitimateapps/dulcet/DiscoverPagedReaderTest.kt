package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.*
import kotlin.test.*
import org.junit.Test

class DiscoverPagedReaderTest {
    private data class Page(val rows: List<Int>, val next: String?)
    private fun reader() = DiscoverPagedReader<Page>({ it.next }, { a,b -> b.copy(rows=a.rows+b.rows) })
    private val failure=DiscoverFailure("upstream-unavailable",null,true)
    @Test fun fullPageAppendsUsingExactOpaqueCursorAndRetainsRowsOnFailure() {
        val state=reader(); val reads=mutableListOf<String?>(); var failed=false
        state.mount { cursor,done -> reads+=cursor;done(if(failed) DiscoverResult.Failed(failure) else DiscoverResult.Value(Page(listOf(if(cursor==null)1 else 2),if(cursor==null)"A:/Opaque" else null))); noop() }
        state.more();assertEquals(listOf(null,"A:/Opaque"),reads);assertEquals(listOf(1,2),state.value!!.rows)
        failed=true;state.refresh();assertEquals(listOf(1,2),state.value!!.rows);assertEquals(failure,state.error);assertFalse(state.loading)
    }
    @Test fun cursorExpiryReplacesWholePageRatherThanAppendingReplacement() {
        val state=reader();var first=0
        state.mount { cursor,done -> done(if(cursor!=null) DiscoverResult.Failed(DiscoverFailure("cursor-expired",null,true)) else DiscoverResult.Value(Page(listOf(++first),"next")));noop() }
        state.more();assertEquals(listOf(2),state.value!!.rows)
    }
    @Test fun departedAndSupersededCallbacksCannotPublishAndOperationsCancel() {
        val state=reader();val callbacks=mutableListOf<(DiscoverResult<Page>)->Unit>();var cancelled=0
        state.mount { _,done -> callbacks+=done;object:DiscoverOperation {override fun cancel(){cancelled++}} }
        state.refresh();callbacks[0](DiscoverResult.Value(Page(listOf(1),null)));assertNull(state.value)
        state.close();callbacks[1](DiscoverResult.Value(Page(listOf(2),null)));assertNull(state.value);assertEquals(2,cancelled)
    }
    @Test fun backKeepsLoadedPagesAndOnlyMountsWhenNeeded() {
        val state=reader();var reads=0
        val fetch:(String?,(DiscoverResult<Page>)->Unit)->DiscoverOperation={_,done -> reads++;done(DiscoverResult.Value(Page(listOf(1),null)));noop()}
        state.mount(read=fetch);state.close();state.mount(read=fetch);assertEquals(1,reads);assertEquals(listOf(1),state.value!!.rows)
    }
    @Test fun playlistMemoryPreservesDuplicateRecordingsButNotOverlappingPositions() {
        val memory=DiscoverBrowseMemory();val state=memory.playlist("opaque:id")
        val item= com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource().item("song")
        state.mount { cursor,done -> done(DiscoverResult.Value(DiscoverPlaylist(DiscoverPlaylistSummary("opaque:id","Playlist",null,null),
            if(cursor==null) listOf(DiscoverPlaylistEntry(1,item),DiscoverPlaylistEntry(2,item)) else listOf(DiscoverPlaylistEntry(2,item),DiscoverPlaylistEntry(3,item)),
            if(cursor==null) "more" else null,DiscoverAge(null,null,0,false,false))));noop() }
        state.more();assertEquals(listOf(1,2,3),state.value!!.entries.map{it.position});assertEquals(3,state.value!!.entries.count{it.item.id==item.id})
    }
    @Test fun recreationReplaysOnlyPreviouslyLoadedPagesAndFailureRetainsRows() {
        val state=reader();val reads=mutableListOf<String?>()
        state.mount(restorePages=2) { cursor,done -> reads+=cursor;done(DiscoverResult.Value(Page(listOf(if(cursor==null)1 else 2),if(cursor==null)"again" else "third")));noop() }
        assertEquals(listOf(null,"again"),reads);assertEquals(2,state.pagesLoaded);assertEquals(listOf(1,2),state.value!!.rows)
        state.refresh();assertEquals(listOf(1),state.value!!.rows);assertEquals(1,state.pagesLoaded)
    }
    @Test fun libraryRoutingRequiresHeldSingleScopedReferenceAndKnownAlbumCompleteness() {
        val item=com.legitimateapps.dulcet.search.conformance.FakeDiscoverWishlistSource().item("album")
        val ref=DiscoverLibraryRef("provider:test","album","local:opaque")
        val held=item.copy(availability=item.availability.copy(status="held",libraryItems=listOf(ref)))
        assertEquals(ref,shelfLibraryRoute(held,"provider:test"));assertNull(shelfLibraryRoute(held,"other"))
        assertNull(shelfLibraryRoute(held.copy(availability=held.availability.copy(status="partlyHeld")),"provider:test"))
        assertNull(shelfLibraryRoute(held.copy(availability=held.availability.copy(completeness="unknown")),"provider:test"))
        val heldArtist=held.copy(kind="artist",availability=held.availability.copy(completeness="unknown",libraryItems=listOf(ref.copy(type="artist"))))
        assertEquals(ref.copy(type="artist"),shelfLibraryRoute(heldArtist,"provider:test"))
        assertNull(shelfLibraryRoute(held.copy(availability=held.availability.copy(libraryItems=listOf(ref,ref.copy(id="second")))),"provider:test"))
    }
    private fun noop()=object:DiscoverOperation{override fun cancel(){}}
}
