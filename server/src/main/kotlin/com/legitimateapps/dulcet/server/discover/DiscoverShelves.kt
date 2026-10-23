package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.auth.AccountAccess

/** Public answers are shared. All local preferences and visibility are applied after cache reads. */
class DiscoverShelves(private val db:Db,private val access:AccountAccess,private val client:ListenBrainzClient) {
    companion object {
        const val TRENDING_LABEL="Trending on ListenBrainz"
        const val CREDIT="Data from MusicBrainz and ListenBrainz"
    }
    suspend fun playlistShelf()=client.searchPlaylists("",100)
    suspend fun shelf(userId:String,shelfId:String):Cached<List<DiscoverItem>> = when(shelfId) {
        "trendingArtists"->client.trending(DiscoverKind.Artist)
        "trendingAlbums"->client.trending(DiscoverKind.Album)
        "trendingSongs"->client.trending(DiscoverKind.Song)
        "newReleases"->client.freshReleases().map { rows ->
            val preferred=myArtists(userId)
            rows.sortedWith(compareByDescending<DiscoverItem> { it.externalIds[DiscoverKind.Artist.registryKey] in preferred || it.artistMbids.any { id -> id in preferred } }.thenByDescending { it.releaseDate ?: "" }.thenBy { it.externalIds[DiscoverKind.Album.registryKey] })
        }
        "similarArtists"->similar(userId)
        else->throw IllegalArgumentException("Unknown discover shelf")
    }
    private fun myArtists(userId:String):Set<String> = db.read {
        query("""SELECT DISTINCT a.mbz_artist_id FROM artist a JOIN track_artist ta ON ta.artist_id=a.id JOIN track t ON t.id=ta.track_id
            WHERE a.mbz_artist_id IS NOT NULL AND ${access.catalogPredicate(userId,"track","t")}
            AND EXISTS(SELECT 1 FROM library_item l WHERE l.user_id=? AND
            ((l.item_type='artist' AND l.item_id=a.id) OR (l.item_type='album' AND l.item_id=t.album_id) OR (l.item_type='song' AND l.item_id=t.id)))""",userId) { it.string("mbz_artist_id") }.toSet()
    }
    private suspend fun similar(userId:String):Cached<List<DiscoverItem>> {
        val visible=access.catalogPredicate(userId,"track","t")
        val candidates=db.read {
            query("""SELECT a.id,a.mbz_artist_id,
                coalesce((SELECT sum(n.play_count) FROM annotation n WHERE n.user_id=? AND
                ((n.item_type='artist' AND n.item_id=a.id) OR
                 (n.item_type='song' AND n.item_id IN (SELECT t.id FROM track t JOIN track_artist ta ON ta.track_id=t.id WHERE ta.artist_id=a.id AND $visible)) OR
                 (n.item_type='album' AND n.item_id IN (SELECT t.album_id FROM track t JOIN track_artist ta ON ta.track_id=t.id WHERE ta.artist_id=a.id AND $visible)))),0) AS plays,
                EXISTS(SELECT 1 FROM annotation n WHERE n.user_id=? AND n.starred IS NOT NULL AND
                ((n.item_type='artist' AND n.item_id=a.id) OR
                 (n.item_type='song' AND n.item_id IN (SELECT t.id FROM track t JOIN track_artist ta ON ta.track_id=t.id WHERE ta.artist_id=a.id AND $visible)) OR
                 (n.item_type='album' AND n.item_id IN (SELECT t.album_id FROM track t JOIN track_artist ta ON ta.track_id=t.id WHERE ta.artist_id=a.id AND $visible)))) AS starred
                FROM artist a WHERE a.mbz_artist_id IS NOT NULL AND ${access.catalogPredicate(userId,"artist","a")}
                ORDER BY plays DESC,starred DESC,a.id""",userId,userId) { Triple(it.string("mbz_artist_id"),it.long("plays"),it.bool("starred")) }
        }
        val held=candidates.map { it.first }.toSet()
        val seeds=candidates.filter { it.second>0 || it.third }.mapNotNull { mbidOrNull(it.first) }.distinct().take(5)
        if(seeds.isEmpty()) return Cached(emptyList(),CacheAge(System.currentTimeMillis(),0,false),"no-seeds")
        val parts=seeds.map { client.similarArtists(it) }
        val seen=HashSet<String>(); val result=mutableListOf<DiscoverItem>()
        for(index in 0 until parts.maxOf { it.value.size }) for(part in parts) part.value.getOrNull(index)?.let { item ->
            val id=item.externalIds[DiscoverKind.Artist.registryKey]!!
            if(id !in held && seen.add(id)) result+=item
        }
        return combineCached(parts,result)
    }
}
