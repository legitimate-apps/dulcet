package com.legitimateapps.dulcet.core

internal fun sampleDiscoverPage(id: String = "item", cursor: String? = null, stale: Boolean = false) = DiscoverPage(listOf(sampleDiscoverItem(id)),cursor,DiscoverAge(1000,120,1000,stale,false))
internal open class DiscoverTestTransport : DiscoverTransport {
    val searches=mutableListOf<Triple<DiscoverKind,String,String?>>()
    var onSearch: suspend (DiscoverKind,String,String?) -> DiscoverResult<DiscoverPage> = { _,q,_ -> DiscoverResult.Value(sampleDiscoverPage(q)) }
    var onArtist: suspend (String) -> DiscoverResult<DiscoverArtist> = { DiscoverResult.Value(DiscoverArtist(sampleDiscoverItem(it),sampleDiscoverPage())) }
    var onAlbum: suspend (String,String?) -> DiscoverResult<DiscoverAlbum> = { id,edition -> DiscoverResult.Value(DiscoverAlbum(sampleDiscoverItem(id),emptyList(),edition,sampleDiscoverPage(edition ?: "default"))) }
    var onTracks: suspend (String,String,String?) -> DiscoverResult<DiscoverPage> = { _,edition,_ -> DiscoverResult.Value(sampleDiscoverPage(edition)) }
    val artworkRequests=mutableListOf<String>()
    override suspend fun search(kind:DiscoverKind,query:String,cursor:String?,limit:Int):DiscoverResult<DiscoverPage> { searches += Triple(kind,query,cursor); return onSearch(kind,query,cursor) }
    override suspend fun artist(mbid:String)=onArtist(mbid)
    override suspend fun artistAlbums(mbid:String,cursor:String?,limit:Int)=DiscoverResult.Value(sampleDiscoverPage())
    override suspend fun album(mbid:String,releaseId:String?)=onAlbum(mbid,releaseId)
    override suspend fun albumTracks(mbid:String,releaseId:String,cursor:String?,limit:Int)=onTracks(mbid,releaseId,cursor)
    override suspend fun artwork(artworkId:String):DiscoverResult<ByteArray> { artworkRequests += artworkId; return DiscoverResult.Value(byteArrayOf(-1,-40,-1)) }
}
