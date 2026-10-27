package com.legitimateapps.dulcet.search.conformance

import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSource

/** Shell presentation fixture only; device proofs use the disposable production server. */
class FakeDiscoverWishlistSource:DiscoverWishlistSource {
    var state=DiscoverPublication("provider:test",1,DiscoverFeatureAccess("known",null,true,true,true,true,true,true,true),"online","",emptyList(),emptyList(),false,null,true,0)
    private val listeners=mutableListOf<(DiscoverPublication)->Unit>()
    val queries=mutableListOf<String>(); val selected=mutableListOf<String?>()
    val albums=mutableMapOf<String?,DiscoverAlbum>()
    var filter=WishFilter(); var removed:String?=null; var added=0;var rejected=0
    val operation=object:DiscoverOperation {override fun cancel(){}}
    fun publish(value:DiscoverPublication=state){state=value;listeners.toList().forEach{it(value)}}
    fun item(kind:String,title:String="Fixture $kind")=DiscoverItem(kind,kind,DiscoverTarget(when(kind){"artist"->DiscoverKind.Artist;"album"->DiscoverKind.Album;else->DiscoverKind.Song},kind),title,"Artist",null,null,null,null,null,null,null,emptyList(),true,DiscoverAvailability("notHeld","known",null,0,12,null,emptyList()))
    fun page(items:List<DiscoverItem>,stale:Boolean=false)=DiscoverPage(items,null,DiscoverAge(null,if(stale)7200 else 0,0,stale,false))
    override fun observe(listener:(DiscoverPublication)->Unit):DiscoverRegistration {listeners+=listener;listener(state);return object:DiscoverRegistration{override fun close(){listeners-=listener}}}
    override fun setQuery(query:String){queries+=query;publish(state.copy(query=query))}
    override fun loadMore(kind:String){}
    override fun artist(mbid:String,completion:(DiscoverResult<DiscoverArtist>)->Unit):DiscoverOperation {completion(DiscoverResult.Value(DiscoverArtist(item("artist"),page(listOf(item("album"))))));return operation}
    override fun artistAlbums(mbid:String,cursor:String?,completion:(DiscoverResult<DiscoverPage>)->Unit)=operation.also{completion(DiscoverResult.Value(page(emptyList())))}
    override fun album(mbid:String,releaseId:String?,completion:(DiscoverResult<DiscoverAlbum>)->Unit)=operation.also {selected+=releaseId;completion(DiscoverResult.Value(albums[releaseId]?:DiscoverAlbum(item("album"),emptyList(),null,page(emptyList()))))}
    override fun albumTracks(mbid:String,releaseId:String,cursor:String?,completion:(DiscoverResult<DiscoverPage>)->Unit)=operation.also{completion(DiscoverResult.Value(page(emptyList())))}
    override fun artwork(artworkId:String,completion:(DiscoverResult<ByteArray>)->Unit)=operation.also{completion(DiscoverResult.Failed(DiscoverFailure("missing-art",null,false)))}
    override fun refreshWishlist(filter:WishFilter){this.filter=filter;publish()}
    override fun add(item:DiscoverItem,source:WishSource,completion:(DiscoverResult<WishEnqueued>)->Unit)=operation.also{added++;completion(DiscoverResult.Value(WishEnqueued("op","op")))}
    override fun remove(rowKey:String,completion:(DiscoverResult<WishEnqueued>)->Unit)=operation.also{removed=rowKey;completion(DiscoverResult.Value(WishEnqueued("remove",rowKey)))}
    override fun reject(item:DiscoverItem,libraryItem:DiscoverLibraryRef,wishId:String?,completion:(DiscoverResult<WishEnqueued>)->Unit)=operation.also{rejected++;completion(DiscoverResult.Value(WishEnqueued("reject","reject")))}
    override fun close(){listeners.clear()}
}
