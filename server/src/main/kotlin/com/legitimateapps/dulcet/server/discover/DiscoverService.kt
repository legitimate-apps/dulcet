package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.v1.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Cache public metadata first; visibility, rejected matches and availability are always local. */
class DiscoverService(private val ctx:ServerContext) {
    private val ids=DiscoverIdentity(ctx.db)
    private val cursors=CursorCodec(ctx)
    private val shelves=DiscoverShelves(ctx.db,ctx.access,ctx.listenBrainz)
    private val cacheOnly=MusicBrainzClient(ctx.discoverHttp,ctx.discoverCache,ctx.config.discover.copy(enabled=false))
    init { ctx.db.write {
        execute("CREATE TABLE IF NOT EXISTS discover_snapshot(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,binding TEXT NOT NULL,generation TEXT NOT NULL,data TEXT NOT NULL,dependencies TEXT NOT NULL,expires INTEGER NOT NULL,UNIQUE(user_id,binding,generation,data))")
        execute("CREATE TABLE IF NOT EXISTS discover_unknown_identity(key TEXT PRIMARY KEY,id TEXT NOT NULL UNIQUE)")
    } }
    private fun enabled() { if(!ctx.config.discover.enabled)throw ApiProblem(404,"not-found") }
    private data class Snapshot(val id:String,val generation:String,val data:JsonArray,val dependencies:Map<String,String>)
    private fun store(user:String,binding:String,cached:Cached<*>,data:JsonArray):Snapshot = ctx.db.write {
        val now=System.currentTimeMillis();execute("DELETE FROM discover_snapshot WHERE expires<?",now)
        val text=data.toString();if(text.toByteArray().size>8*1024*1024)throw UpstreamUnavailable()
        val id=string("SELECT id FROM discover_snapshot WHERE user_id=? AND binding=? AND generation=? AND data=?",user,binding,cached.generation,text) ?: "ds_"+UUID.randomUUID()
        execute("INSERT OR IGNORE INTO discover_snapshot VALUES(?,?,?,?,?,?,?)",id,user,binding,cached.generation,text,json(cached.dependencies).toString(),now+DiscoverCache.HOUR)
        // A bounded per-user cursor working set; eviction is explicitly cursor-expired.
        execute("DELETE FROM discover_snapshot WHERE user_id=? AND id NOT IN (SELECT id FROM discover_snapshot WHERE user_id=? ORDER BY expires DESC,id LIMIT 32)",user,user)
        var bytes=long("SELECT coalesce(sum(length(CAST(data AS BLOB))+length(CAST(dependencies AS BLOB))),0) FROM discover_snapshot")
        if(bytes>16*1024*1024)for((old,size) in query("SELECT id,length(CAST(data AS BLOB))+length(CAST(dependencies AS BLOB)) AS bytes FROM discover_snapshot ORDER BY expires,id") { it.string("id") to it.long("bytes") }) {
            execute("DELETE FROM discover_snapshot WHERE id=?",old);bytes-=size;if(bytes<=16*1024*1024)break
        }
        ctx.discoverCache.prune()
        Snapshot(id,cached.generation,data,cached.dependencies)
    }
    private fun continuation(user:String,binding:String,cursor:String):Pair<Snapshot,Int> {
        val value=cursors.decode(cursor)
        if(value.text("kind")!="discover" || value.text("user")!=user || value.text("binding")!=binding)throw ApiProblem(400,"invalid-request","Cursor does not match this collection")
        val snapshot=ctx.db.read { single("SELECT * FROM discover_snapshot WHERE id=? AND user_id=? AND binding=? AND expires>=?",value.text("snapshot"),user,binding,System.currentTimeMillis()) {
            Snapshot(it.string("id"),it.string("generation"),Json.parseToJsonElement(it.string("data")).jsonArray,Json.parseToJsonElement(it.string("dependencies")).jsonObject.mapValues { it.value.jsonPrimitive.content })
        } } ?: throw ApiProblem(410,"cursor-expired")
        if(!ctx.discoverCache.contains(snapshot.dependencies))throw ApiProblem(410,"cursor-expired")
        val offset=value.text("offset")?.toIntOrNull()?.takeIf { it>=0 } ?: throw ApiProblem(400,"invalid-request")
        return snapshot to offset
    }
    private fun cursor(user:String,binding:String,snapshot:Snapshot,offset:Int)=cursors.encode(j("kind" to "discover","user" to user,"binding" to binding,"snapshot" to snapshot.id,"offset" to offset))
    private suspend fun <T> page(user:String,binding:String,request:PageRequest,load:suspend()->Cached<List<T>>,encode:(T)->JsonElement,decode:(JsonElement)->T,project:suspend(T)->T={it}):DiscoverPage<T> {
        enabled()
        val prior=request.cursor?.let { continuation(user,binding,it) }
        val cached=load()
        if(prior!=null && prior.first.generation!=cached.generation)throw ApiProblem(410,"cursor-expired")
        val snapshot=prior?.first ?: store(user,binding,cached,JsonArray(cached.value.map(encode)))
        val offset=prior?.second ?: 0
        val items=snapshot.data.drop(offset).take(request.limit).map { project(decode(it)) }
        val next=offset+items.size
        return DiscoverPage(items,if(next<snapshot.data.size)cursor(user,binding,snapshot,next) else null,cached.age)
    }
    private fun unknown(key:String)=ctx.db.write {
        execute("INSERT OR IGNORE INTO discover_unknown_identity VALUES(?,?)",key,"di_"+UUID.randomUUID())
        string("SELECT id FROM discover_unknown_identity WHERE key=?",key)!!
    }
    private suspend fun project(user:String,item:DiscoverItem,editions:List<Edition>?=null):DiscoverItem {
        val metadata=if(item.kind==DiscoverKind.Album && editions==null)try { item.externalIds[item.kind.registryKey]?.let { cacheOnly.editions(it).value } } catch(_:UpstreamUnavailable){null} catch(_:UpstreamNotFound){null} else editions
        return ids.register(item).copy(availability=ctx.discoverMatcher.match(user,item,metadata))
    }
    suspend fun search(userId:String,kind:DiscoverKind,query:String,page:PageRequest):DiscoverPage<DiscoverItem> {
        enabled();require(query.isNotBlank() && query.codePointCount(0,query.length)<=500)
        val binding="search:${kind.wire}:$query"
        val prior=page.cursor?.let { continuation(userId,binding,it) }
        val head=ctx.musicBrainz.search(kind,query,0,100)
        if(prior!=null && prior.first.generation!=head.generation)throw ApiProblem(410,"cursor-expired")
        var snapshot=prior?.first ?: store(userId,binding,head,JsonArray(head.value.items.map(::itemJson)))
        var offset=prior?.second ?: 0;val result=mutableListOf<DiscoverItem>();val parts=mutableListOf<Cached<*>>(head)
        val dependencies=snapshot.dependencies.toMutableMap()
        while(result.size<page.limit && offset<head.value.total) {
            val start=(offset/100)*100
            val chunk=if(start==0)head else ctx.musicBrainz.search(kind,query,start,100)
            if(chunk.dependencies.any { (key,generation) -> dependencies[key]?.let { it!=generation }==true })throw ApiProblem(410,"cursor-expired")
            dependencies.putAll(chunk.dependencies);parts+=chunk
            val selected=chunk.value.items.drop(offset-start).take(page.limit-result.size)
            if(selected.isEmpty())throw UpstreamUnavailable()
            result+=selected;offset+=selected.size
        }
        ctx.db.write { execute("UPDATE discover_snapshot SET dependencies=? WHERE id=?",json(dependencies).toString(),snapshot.id) }
        return DiscoverPage(result.map { project(userId,it) },if(offset<head.value.total)cursor(userId,binding,snapshot,offset) else null,combineCached(parts,Unit).age)
    }
    suspend fun shelf(userId:String,id:String,page:PageRequest):DiscoverPage<DiscoverItem> = page(userId,"shelf:$id",page,{shelves.shelf(userId,id)},::itemJson,{readItem(it.obj())},{project(userId,it)})
    suspend fun playlists(userId:String,query:String,page:PageRequest):DiscoverPage<PlaylistSummary> {
        require(query.codePointCount(0,query.length)<=500)
        return page(userId,"playlists:$query",page,{ctx.listenBrainz.searchPlaylists(query,100)},::summaryJson,{readSummary(it.obj())})
    }
    suspend fun albums(userId:String,artistMbid:String,page:PageRequest):DiscoverPage<DiscoverItem> {
        val mbid=canonicalMbid(artistMbid)
        return page(userId,"albums:$mbid",page,{ctx.musicBrainz.discography(mbid)},::itemJson,{readItem(it.obj())},{project(userId,it)})
    }
    suspend fun artist(userId:String,mbid:String):DiscoverArtistPage {
        enabled();val item=ctx.musicBrainz.artist(canonicalMbid(mbid))
        return DiscoverArtistPage(project(userId,item.value),albums(userId,mbid,PageRequest()))
    }
    suspend fun album(userId:String,mbid:String,releaseId:String?=null):DiscoverAlbumPage {
        enabled();val group=canonicalMbid(mbid);val item=ctx.musicBrainz.album(group);val metadata=ctx.musicBrainz.editions(group)
        val editions=metadata.value.sortedWith(compareBy<Edition> { it.releaseDate ?: "9999" }.thenBy { it.releaseMbid })
        val selected=if(releaseId==null)editions.firstOrNull() else editions.firstOrNull { it.releaseMbid==canonicalMbid(releaseId) } ?: throw ApiProblem(404,"not-found")
        val tracks=if(selected==null)DiscoverPage(emptyList(),null,metadata.age) else albumTracks(userId,group,selected.releaseMbid,PageRequest())
        return DiscoverAlbumPage(project(userId,item.value,editions),editions,selected?.releaseMbid,tracks)
    }
    suspend fun albumTracks(userId:String,groupMbid:String,releaseMbid:String,page:PageRequest):DiscoverPage<DiscoverItem> {
        val group=canonicalMbid(groupMbid);val release=canonicalMbid(releaseMbid)
        val art=ids.register(DiscoverItem(kind=DiscoverKind.Album,title="Album",externalIds=mapOf(DiscoverKind.Album.registryKey to group))).artworkId
        return page(userId,"tracks:$group:$release",page,{
            ctx.musicBrainz.editions(group).map { editions ->
                val edition=editions.firstOrNull { it.releaseMbid==release } ?: throw ApiProblem(404,"not-found")
                edition.tracks.map { slot -> DiscoverItem(kind=DiscoverKind.Song,title=slot.title,artist=slot.artist,externalIds=mapOf(DiscoverKind.Album.registryKey to group)+if(slot.recordingMbid==null)emptyMap() else mapOf(DiscoverKind.Song.registryKey to slot.recordingMbid),artworkId=art,wishable=slot.recordingMbid!=null,disc=slot.disc,position=slot.position) }
            }
        },::itemJson,{readItem(it.obj())},{item -> project(userId,item).let { if(it.id.isEmpty())it.copy(id=unknown("release:$release:${it.disc}:${it.position}")) else it }})
    }
    suspend fun playlist(userId:String,mbid:String,page:PageRequest):DiscoverPlaylistPage {
        enabled();val id=canonicalMbid(mbid);val playlist=ctx.listenBrainz.playlist(id)
        val entries=page(userId,"playlist:$id",page,{ctx.listenBrainz.playlist(id).map { it.entries }},{j("position" to it.position,"item" to itemJson(it.item))},{PlaylistEntry(it.obj().requiredText("position").toInt(),readItem(it.obj()["item"]!!.obj()))},{entry ->
            val item=project(userId,entry.item);entry.copy(item=if(item.id.isEmpty())item.copy(id=unknown("playlist:$id:${entry.position}")) else item)
        })
        return DiscoverPlaylistPage(playlist.value.summary,entries)
    }
    suspend fun artwork(userId:String,id:String):DiscoverImage {
        // Artwork remains usable from existing references while discovery is disabled.
        val mbid=ids.artwork(id) ?: throw ApiProblem(404,"not-found")
        return ctx.discoverArtwork.thumbnail(mbid)
    }
}
