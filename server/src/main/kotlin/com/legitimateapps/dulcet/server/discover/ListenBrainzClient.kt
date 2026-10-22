package com.legitimateapps.dulcet.server.discover

import kotlinx.serialization.json.*

class ListenBrainzClient(private val http:PoliteHttp,private val cache:DiscoverCache,private val settings:DiscoverSettings) {
    companion object { const val SIMILAR_ALGORITHM="session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30" }
    private suspend fun <T> load(path:String,query:Map<String,String>,ttl:Long,base:String=settings.listenBrainzUrl,decode:(JsonElement)->T)=
        cache.read(CacheKey(base,path,query),ttl,{ decode(Json.parseToJsonElement(it.decodeToString())) }) { if(!settings.enabled)throw UpstreamUnavailable(); http.get(base,path,query) }
    suspend fun trending(kind:DiscoverKind):Cached<List<DiscoverItem>> {
        val entity=when(kind) { DiscoverKind.Artist->"artists"; DiscoverKind.Album->"release-groups"; DiscoverKind.Song->"recordings" }
        return load("/1/stats/sitewide/$entity",mapOf("range" to "week"),6*DiscoverCache.HOUR) { element ->
            val payload=element.obj()["payload"]?.obj() ?: throw UpstreamUnavailable()
            payload.rows(entity.replace('-','_')).map { source ->
                val row=source.obj()
                val id=mbidOrNull(row.text(when(kind) { DiscoverKind.Artist->"artist_mbid"; DiscoverKind.Album->"release_group_mbid"; DiscoverKind.Song->"recording_mbid" }))
                val title=row.requiredText(when(kind) { DiscoverKind.Artist->"artist_name"; DiscoverKind.Album->"release_group_name"; DiscoverKind.Song->"track_name" })
                val ids=mutableMapOf<String,String>(); if(id!=null) ids[kind.registryKey]=id
                (row["artist_mbids"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let(::mbidOrNull)?.let { ids[DiscoverKind.Artist.registryKey]=it }
                DiscoverItem(kind=kind,title=title,artist=if(kind==DiscoverKind.Artist) null else row.text("artist_name"),externalIds=ids,wishable=id!=null,listenCount=row.text("listen_count")?.toLongOrNull() ?: throw UpstreamUnavailable())
            }
        }
    }
    suspend fun freshReleases(days:Int=7):Cached<List<DiscoverItem>> {
        require(days==7)
        return load("/1/explore/fresh-releases/",mapOf("days" to "$days"),6*DiscoverCache.HOUR) { element ->
            (element.obj()["payload"]?.obj() ?: throw UpstreamUnavailable()).rows("releases").mapNotNull { source ->
                val row=source.obj(); val group=mbidOrNull(row.text("release_group_mbid")) ?: return@mapNotNull null
                val ids=mutableMapOf(DiscoverKind.Album.registryKey to group)
                mbidOrNull(row.text("release_mbid"))?.let { ids["musicbrainzReleaseId"]=it }
                mbidOrNull(row.text("caa_release_mbid"))?.let { ids["coverArtArchiveReleaseId"]=it }
                row.text("caa_id")?.let { ids["coverArtArchiveId"]=it }
                (row["artist_mbids"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let(::mbidOrNull)?.let { ids[DiscoverKind.Artist.registryKey]=it }
                val date=row.text("release_date")
                DiscoverItem(kind=DiscoverKind.Album,title=row.requiredText("release_name"),artist=row.text("artist_credit_name"),releaseDate=date,year=date?.take(4)?.toIntOrNull(),externalIds=ids,artistMbids=(row["artist_mbids"] as? JsonArray).orEmpty().mapNotNull { mbidOrNull((it as? JsonPrimitive)?.contentOrNull) },primaryType=row.text("release_group_primary_type"))
            }.distinctBy { it.externalIds[DiscoverKind.Album.registryKey] }
        }
    }
    suspend fun similarArtists(seedMbid:String):Cached<List<DiscoverItem>> = load("/similar-artists/json",mapOf("artist_mbids" to canonicalMbid(seedMbid),"algorithm" to SIMILAR_ALGORITHM),7*DiscoverCache.DAY,settings.listenBrainzLabsUrl) { element ->
        val rows=element as? JsonArray ?: throw UpstreamUnavailable()
        rows.mapNotNull { source ->
            val row=source.obj(); val id=mbidOrNull(row.text("artist_mbid")) ?: return@mapNotNull null
            DiscoverItem(kind=DiscoverKind.Artist,title=row.requiredText("name"),externalIds=mapOf(DiscoverKind.Artist.registryKey to id))
        }
    }
    suspend fun searchPlaylists(query:String,count:Int):Cached<List<PlaylistSummary>> {
        require(query.length<=1000 && count in 1..100)
        return load("/1/playlist/search",mapOf("query" to query,"count" to "$count"),DiscoverCache.HOUR) { element ->
            element.obj().rows("playlists").map { source -> summary(source.obj()["playlist"]?.obj() ?: throw UpstreamUnavailable(),null) }
        }
    }
    suspend fun playlist(mbid:String):Cached<DiscoverPlaylist> {
        val id=canonicalMbid(mbid)
        return load("/1/playlist/$id",emptyMap(),DiscoverCache.DAY) { element ->
            val root=element.obj()["playlist"]?.obj() ?: throw UpstreamUnavailable()
            val entries=root.rows("track").mapIndexed { index,source ->
                val row=source.obj()
                val identifiers=when(val value=row["identifier"]) { is JsonArray->value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }; is JsonPrimitive->listOf(value.content); else->emptyList() }
                val recording=identifiers.firstNotNullOfOrNull { identifier ->
                    val uri=runCatching { java.net.URI(identifier) }.getOrNull()
                    if(uri?.host in listOf("musicbrainz.org","www.musicbrainz.org") && uri?.path?.startsWith("/recording/")==true) mbidOrNull(uri.path.removePrefix("/recording/").trimEnd('/')) else null
                }
                PlaylistEntry(index+1,DiscoverItem(kind=DiscoverKind.Song,title=row.requiredText("title"),artist=row.text("creator"),externalIds=if(recording==null) emptyMap() else mapOf(DiscoverKind.Song.registryKey to recording),wishable=recording!=null))
            }
            DiscoverPlaylist(summary(root,id).copy(trackCount=entries.size),entries)
        }
    }
    private fun summary(root:JsonObject,fallback:String?):PlaylistSummary {
        val raw=root.text("identifier")
        val id=raw?.let { runCatching { canonicalMbid(java.net.URI(it).path.trimEnd('/').substringAfterLast('/')) }.getOrNull() } ?: fallback ?: throw UpstreamUnavailable()
        return PlaylistSummary(id,root.requiredText("title"),root.text("creator"),(root["track"] as? JsonArray)?.size ?: (root["extension"] as? JsonObject)?.values?.firstNotNullOfOrNull { (it as? JsonObject)?.text("track_count")?.toIntOrNull() })
    }
}
