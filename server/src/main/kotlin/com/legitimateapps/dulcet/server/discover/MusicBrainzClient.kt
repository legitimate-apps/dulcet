package com.legitimateapps.dulcet.server.discover

import kotlinx.serialization.json.*

internal fun JsonObject.text(key:String):String?=(get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.requiredText(key:String):String=text(key)?.takeIf { it.isNotBlank() } ?: throw UpstreamUnavailable()
internal fun JsonObject.rows(key:String):JsonArray=get(key) as? JsonArray ?: throw UpstreamUnavailable()
internal fun JsonElement.obj():JsonObject=this as? JsonObject ?: throw UpstreamUnavailable()
internal fun mbidOrNull(value:String?):String?=value?.let { runCatching { canonicalMbid(it) }.getOrNull() }
internal fun JsonObject.artistCredit():String = (get("artist-credit") as? JsonArray)?.joinToString("") { element ->
    val credit=element.obj(); (credit.text("name") ?: (credit["artist"] as? JsonObject)?.text("name").orEmpty())+credit.text("joinphrase").orEmpty()
}.orEmpty()
internal fun JsonObject.relationLinks():List<ExternalLink> = (get("relations") as? JsonArray).orEmpty().mapNotNull { element ->
    val relation=element as? JsonObject ?: return@mapNotNull null
    val url=(relation["url"] as? JsonObject)?.text("resource") ?: return@mapNotNull null
    val uri=runCatching { java.net.URI(url) }.getOrNull() ?: return@mapNotNull null
    if(uri.scheme !in listOf("https","http") || uri.host==null || uri.userInfo!=null) null else ExternalLink(relation.text("type") ?: "website",url)
}

class MusicBrainzClient(private val http:PoliteHttp,private val cache:DiscoverCache,private val settings:DiscoverSettings) {
    init { http.configureOrigin(settings.musicBrainzUrl,settings.musicBrainzMinGapMillis) }
    private suspend fun <T> load(path:String,query:Map<String,String> = emptyMap(),ttl:Long=DiscoverCache.DAY,decode:(JsonObject)->T):Cached<T> {
        val params=query+mapOf("fmt" to "json")
        return cache.read(CacheKey(settings.musicBrainzUrl,path,params),ttl,{ decode(Json.parseToJsonElement(it.decodeToString()).obj()) }) { http.get(settings.musicBrainzUrl,path,params) }
    }
    suspend fun search(kind:DiscoverKind,query:String,offset:Int,limit:Int):Cached<UpstreamPage<DiscoverItem>> {
        require(offset>=0 && limit in 1..100 && query.isNotBlank() && query.length<=1000)
        val entity=when(kind) { DiscoverKind.Artist->"artist"; DiscoverKind.Album->"release-group"; DiscoverKind.Song->"recording" }
        return load("/ws/2/$entity",mapOf("query" to query,"offset" to "$offset","limit" to "$limit"),DiscoverCache.HOUR) { root ->
            val rows=root.rows(when(kind) { DiscoverKind.Artist->"artists"; DiscoverKind.Album->"release-groups"; DiscoverKind.Song->"recordings" })
            val total=root.text("$entity-count")?.toIntOrNull() ?: root.text("count")?.toIntOrNull() ?: throw UpstreamUnavailable()
            require(total>=0)
            UpstreamPage(rows.map { item(it.obj(),kind) },total,offset)
        }
    }
    suspend fun artist(mbid:String)=load("/ws/2/artist/${canonicalMbid(mbid)}",mapOf("inc" to "url-rels")) { item(it,DiscoverKind.Artist) }
    suspend fun album(groupMbid:String)=load("/ws/2/release-group/${canonicalMbid(groupMbid)}",mapOf("inc" to "artist-credits+url-rels")) { item(it,DiscoverKind.Album) }
    suspend fun discography(artistMbid:String):Cached<List<DiscoverItem>> {
        val parts=mutableListOf<Cached<UpstreamPage<DiscoverItem>>>()
        var offset=0
        do {
            val page=load("/ws/2/release-group",mapOf("artist" to canonicalMbid(artistMbid),"inc" to "artist-credits","limit" to "100","offset" to "$offset")) { root ->
                UpstreamPage(root.rows("release-groups").map { item(it.obj(),DiscoverKind.Album) },root.text("release-group-count")?.toIntOrNull() ?: throw UpstreamUnavailable(),offset)
            }
            if(page.value.total<0 || (page.value.items.isEmpty() && offset<page.value.total)) throw UpstreamUnavailable()
            parts+=page; offset+=page.value.items.size
            if(parts.size>1000) throw UpstreamUnavailable()
        } while(offset<parts.last().value.total)
        val rows=parts.flatMap { it.value.items }.distinctBy { it.externalIds[DiscoverKind.Album.registryKey] }
            .sortedWith(compareByDescending<DiscoverItem> { it.releaseDate ?: "" }.thenBy { it.externalIds[DiscoverKind.Album.registryKey] })
        return combineCached(parts,rows)
    }
    suspend fun editions(groupMbid:String):Cached<List<Edition>> {
        val group=canonicalMbid(groupMbid); val parts=mutableListOf<Cached<UpstreamPage<Edition>>>(); var offset=0
        do {
            val page=load("/ws/2/release",mapOf("release-group" to group,"inc" to "recordings+artist-credits+release-groups","limit" to "100","offset" to "$offset")) { root ->
                UpstreamPage(root.rows("releases").map { edition(it.obj(),group) },root.text("release-count")?.toIntOrNull() ?: throw UpstreamUnavailable(),offset)
            }
            if(page.value.total<0 || (page.value.items.isEmpty() && offset<page.value.total)) throw UpstreamUnavailable()
            parts+=page; offset+=page.value.items.size
            if(parts.size>1000) throw UpstreamUnavailable()
        } while(offset<parts.last().value.total)
        return combineCached(parts,parts.flatMap { it.value.items }.distinctBy { it.releaseMbid })
    }
    suspend fun release(releaseMbid:String)=load("/ws/2/release/${canonicalMbid(releaseMbid)}",mapOf("inc" to "recordings+artist-credits+release-groups+url-rels")) { edition(it,null) }
    private fun item(root:JsonObject,kind:DiscoverKind):DiscoverItem {
        val id=canonicalMbid(root.requiredText("id")); val date=root.text("first-release-date")
        val ids=mutableMapOf(kind.registryKey to id)
        (root["artist-credit"] as? JsonArray)?.firstOrNull()?.let { credit -> mbidOrNull((credit.obj()["artist"] as? JsonObject)?.text("id"))?.let { ids[DiscoverKind.Artist.registryKey]=it } }
        return DiscoverItem(kind=kind,title=root.requiredText(if(kind==DiscoverKind.Artist) "name" else "title"),artist=if(kind==DiscoverKind.Artist) null else root.artistCredit(),year=date?.take(4)?.toIntOrNull(),releaseDate=date,externalIds=ids,links=root.relationLinks())
    }
    private fun edition(root:JsonObject,expectedGroup:String?):Edition {
        val group=canonicalMbid((root["release-group"] as? JsonObject)?.requiredText("id") ?: throw UpstreamUnavailable())
        if(expectedGroup!=null && group!=expectedGroup) throw UpstreamUnavailable()
        val media=root.rows("media")
        val tracks=media.flatMap { element ->
            val medium=element.obj(); val disc=medium.text("position")?.toIntOrNull() ?: throw UpstreamUnavailable()
            val slots=medium.rows("tracks"); val count=medium.text("track-count")?.toIntOrNull() ?: throw UpstreamUnavailable()
            if(count!=slots.size || disc<1) throw UpstreamUnavailable()
            slots.map { element ->
                val track=element.obj(); val recording=track["recording"] as? JsonObject ?: throw UpstreamUnavailable()
                val position=track.text("position")?.toIntOrNull()?.takeIf { it>0 } ?: throw UpstreamUnavailable()
                EditionTrack(disc,position,mbidOrNull(recording.text("id")),track.text("title") ?: recording.requiredText("title"),track.artistCredit().ifBlank { recording.artistCredit().ifBlank { root.artistCredit() } },track.text("length")?.toLongOrNull() ?: recording.text("length")?.toLongOrNull())
            }
        }
        return Edition(canonicalMbid(root.requiredText("id")),group,tracks,root.relationLinks())
    }
}
