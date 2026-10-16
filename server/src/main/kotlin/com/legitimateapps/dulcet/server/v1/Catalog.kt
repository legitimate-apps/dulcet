package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.fold
import io.ktor.server.response.respond
import com.legitimateapps.dulcet.server.db.Db
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.UUID

class ApiCatalog(private val ctx: ServerContext) {
    private val codec=CursorCodec(ctx)
    fun itemState(id:String,p:Principal)=ctx.apiUserData.itemState(id,p)
    private fun state(id:String,p:Principal)=JsonObject(itemState(id,p).filterKeys { it !in setOf("itemId","itemType","revision","applied") })
    fun kind(id:String):String? = ctx.db.read {
        listOf("track","album","artist","playlist").firstOrNull { long("SELECT COUNT(*) FROM $it WHERE id=?",id)>0 }
    }
    fun liveReplacements(type:String,ids:List<String>):List<String> = ctx.db.read {
        fun resolve(id:String,seen:Set<String>):List<String> {
            if(id in seen) return emptyList()
            val gone=single("SELECT reason,replaced_by FROM api_identity WHERE type=? AND id=?",type,id) { it.stringOrNull("reason") to it.stringOrNull("replaced_by") }
            if(gone==null || gone.first==null) return listOf(id)
            val next=gone.second?.let { Json.parseToJsonElement(it).jsonArray.map { value -> value.jsonPrimitive.content } } ?: return emptyList()
            return next.flatMap { resolve(it,seen+id) }
        }
        ids.flatMap { resolve(it,emptySet()) }.distinct()
    }
    private fun art(id:String,p:Principal):JsonObject? = ctx.apiMedia.artworkRef(id,p)
    private fun credits(id:String,album:Boolean):List<JsonObject> = ctx.db.read {
        if(album) query("SELECT artist_id,name FROM album_artist WHERE album_id=? ORDER BY position",id) { j("artist" to j("id" to it.string("artist_id"),"name" to it.string("name")),"role" to "albumArtist") }
        else query("SELECT artist_id,name,role FROM track_artist WHERE track_id=? ORDER BY position,role",id) { j("artist" to j("id" to it.string("artist_id"),"name" to it.string("name")),"role" to if(it.string("role")=="albumartist") "albumArtist" else it.string("role")) }
    }
    private fun genreId(name:String):String = ctx.db.read { string("SELECT id FROM api_group_identity WHERE type='genre' AND key=?",name) } ?: ctx.db.write {
        execute("INSERT OR IGNORE INTO api_group_identity(type,key,id) VALUES('genre',?,?)",name,"gen_${UUID.randomUUID()}")
        string("SELECT id FROM api_group_identity WHERE type='genre' AND key=?",name)!!
    }
    private fun genres(id:String,album:Boolean,p:Principal):List<JsonObject> = ctx.db.read {
        query(if(album) "SELECT DISTINCT g.genre FROM track_genre g JOIN track t ON t.id=g.track_id WHERE t.album_id=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")} ORDER BY g.genre" else "SELECT genre FROM track_genre WHERE track_id=? ORDER BY genre",id) { it.string("genre") }
    }.map { j("id" to genreId(it),"name" to it) }
    private fun optional(vararg pairs:Pair<String,Any?>)=j(*pairs.filter { it.second!=null && it.second!="" }.toTypedArray())
    fun resource(type:String,id:String,p:Principal):JsonObject? = ctx.db.read {
        if(!ctx.access.canReadCatalog(p.user.id,type,id)) return@read null
        when(type) {
            "artist" -> single("SELECT * FROM artist WHERE id=?",id) { row -> j("id" to id,"type" to type,"name" to row.string("name"),"sortName" to row.string("sort_name"),"albumCount" to long("SELECT COUNT(DISTINCT t.album_id) FROM track t JOIN album_artist aa ON aa.album_id=t.album_id WHERE aa.artist_id=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",id),"trackCount" to long("SELECT COUNT(DISTINCT t.id) FROM track t JOIN track_artist ta ON ta.track_id=t.id WHERE ta.artist_id=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",id),"userState" to state(id,p),"revision" to ctx.journal.revision(type,id)) + optional("artwork" to art(id,p),"externalIds" to row.stringOrNull("mbz_artist_id")?.let { j("musicbrainzArtistId" to it) }) }.let { it?.let(::JsonObject) }
            "album" -> single("SELECT * FROM album WHERE id=?",id) { row -> j("id" to id,"type" to type,"title" to row.string("name"),"sortTitle" to row.string("sort_name"),"artistDisplay" to row.string("album_artist"),"credits" to credits(id,true),"isCompilation" to row.bool("compilation"),"trackCount" to long("SELECT COUNT(*) FROM track t WHERE album_id=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",id),"discCount" to long("SELECT COUNT(DISTINCT COALESCE(disc_no,1)) FROM track t WHERE album_id=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",id),"durationMs" to (single("SELECT COALESCE(SUM(duration),0) AS value FROM track t WHERE album_id=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",id) { it.double("value") }!!*1000).toLong(),"addedAt" to iso(row.long("created")),"genres" to genres(id,true,p),"userState" to state(id,p),"revision" to ctx.journal.revision(type,id)) + optional("releaseDate" to row.intOrNull("year")?.toString(),"artwork" to art(id,p),"externalIds" to row.stringOrNull("mbz_album_id")?.let { j("musicbrainzReleaseId" to it) }) }.let { it?.let(::JsonObject) }
            "track" -> single("SELECT * FROM track WHERE id=?",id) { row ->
                val audio=optional("codec" to row.stringOrNull("codec"),"container" to container(row.string("suffix")),"bitrate" to row.long("bitrate")*1000,"sampleRate" to row.intOrNull("sample_rate"),"bitDepth" to row.intOrNull("bit_depth"),"channels" to row.intOrNull("channels"),"sizeBytes" to row.long("size"),"lossless" to (row.string("codec") in setOf("flac","alac","wavpack","ape") || row.string("codec").startsWith("pcm_")))
                j("id" to id,"type" to type,"title" to row.string("title"),"album" to j("id" to row.string("album_id"),"title" to row.string("album")),"artistDisplay" to row.string("artist"),"credits" to credits(id,false),"durationMs" to (row.double("duration")*1000).toLong(),"discNumber" to (row.intOrNull("disc_no") ?: 1),"genres" to genres(id,false,p),"audio" to audio,"addedAt" to iso(row.long("created")),"userState" to state(id,p),"revision" to ctx.journal.revision(type,id)) + optional("sortTitle" to row.stringOrNull("sort_title"),"trackNumber" to row.intOrNull("track_no"),"releaseDate" to (row.stringOrNull("release_date") ?: row.intOrNull("year")?.toString()),"bpm" to row.intOrNull("bpm"),"artwork" to art(id,p),"externalIds" to optional("musicbrainzTrackId" to row.stringOrNull("mbz_track_id"),"musicbrainzRecordingId" to row.stringOrNull("mbz_recording_id")),"loudness" to optional("trackGainDb" to row.doubleOrNull("rg_track_gain"),"trackPeak" to row.doubleOrNull("rg_track_peak"),"albumGainDb" to row.doubleOrNull("rg_album_gain"),"albumPeak" to row.doubleOrNull("rg_album_peak")))
            }.let { it?.let(::JsonObject) }
            "genre" -> string("SELECT key FROM api_group_identity WHERE type='genre' AND id=?",id)?.let { name -> if(long("SELECT COUNT(*) FROM track_genre g JOIN track t ON t.id=g.track_id WHERE g.genre=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",name)==0L) null else j("id" to id,"type" to type,"name" to name,"trackCount" to long("SELECT COUNT(*) FROM track_genre g JOIN track t ON t.id=g.track_id WHERE g.genre=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",name),"albumCount" to long("SELECT COUNT(DISTINCT t.album_id) FROM track t JOIN track_genre g ON t.id=g.track_id WHERE g.genre=? AND ${ctx.access.catalogPredicate(p.user.id,"track","t")}",name),"revision" to ctx.journal.revision(type,id)) }
            "source" -> single("SELECT name FROM music_folder WHERE CAST(id AS TEXT)=?",id) { j("id" to id,"type" to type,"name" to it.string("name"),"trackCount" to long("SELECT COUNT(*) FROM track WHERE CAST(folder_id AS TEXT)=?",id),"revision" to ctx.journal.revision(type,id)) }
            else -> null
        }
    }
    fun ids(type:String,p:Principal):List<String> = ctx.db.read {
        when(type) {
            "playlist" -> query("SELECT id FROM playlist WHERE owner_id=? OR public=1 ORDER BY id",p.user.id) { it.string("id") }
            "artist","album","track" -> query("SELECT id FROM $type item WHERE ${ctx.access.catalogPredicate(p.user.id,type,"item")} ORDER BY id") { it.string("id") }
            "genre" -> query("SELECT DISTINCT genre FROM track_genre ORDER BY genre") { it.string("genre") }.map(::genreId).filter { ctx.access.canReadCatalog(p.user.id,"genre",it) }
            "source" -> ctx.access.sourceIds(p.user.id).filter { ctx.access.canReadCatalog(p.user.id,"source",it) }
            else -> emptyList()
        }
    }
    // Only page members need full credits, genres and artwork. Candidate projections contain
    // the fields needed to filter/sort; a large walk must not extract every cover on every page.
    private fun candidate(type:String,id:String,p:Principal,includeState:Boolean):JsonObject? = ctx.db.read {
        when(type) {
            "artist" -> single("SELECT name,sort_name FROM artist WHERE id=?",id) { j("name" to it.string("name"),"sortName" to it.string("sort_name")) }
            "album" -> single("SELECT name,sort_name,album_artist,year,created FROM album WHERE id=?",id) { j("title" to it.string("name"),"sortTitle" to it.string("sort_name"),"artistDisplay" to it.string("album_artist"),"releaseDate" to it.intOrNull("year")?.toString(),"addedAt" to iso(it.long("created"))) }
            "track" -> single("SELECT title,sort_title,artist,album_id,album,disc_no,track_no,release_date,year,created FROM track WHERE id=?",id) { j("title" to it.string("title"),"sortTitle" to it.stringOrNull("sort_title"),"artistDisplay" to it.string("artist"),"album" to j("id" to it.string("album_id"),"title" to it.string("album")),"discNumber" to (it.intOrNull("disc_no") ?: 1),"trackNumber" to it.intOrNull("track_no"),"releaseDate" to (it.stringOrNull("release_date") ?: it.intOrNull("year")?.toString()),"addedAt" to iso(it.long("created"))) }
            "playlist" -> single("SELECT name FROM playlist WHERE id=? AND (owner_id=? OR public=1)",id,p.user.id) { j("name" to it.string("name")) }
            else -> resource(type,id,p)
        }?.let { value -> if(includeState) JsonObject(value+j("userState" to state(id,p))) else value }
    }
    private fun rendered(type:String,id:String,p:Principal):JsonObject? = if(type=="playlist") ctx.apiUserData.resource(type,id,p) else resource(type,id,p)
    private data class Entry(val id:String,val key:List<JsonPrimitive>)
    private fun compare(a:List<JsonPrimitive>,b:List<JsonPrimitive>):Int {
        for(i in a.indices) {
            val av=a[i];val bv=b.getOrNull(i) ?: return 1
            val n=if(av.isString) av.content.compareTo(bv.content) else (av.doubleOrNull ?: 0.0).compareTo(bv.doubleOrNull ?: 0.0)
            if(n!=0) return n
        };return 0
    }
    fun page(type:String,p:Principal,requestParams:Map<String,String>,max:Int=500):JsonObject {
        val params=requestParams.filterKeys { it in setOf("cursor","limit","scope","sort","ids","albumId","artistId","sourceId","genreId","role","year","q") }
        val limit=params["limit"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request","Invalid limit") } ?: if("q" in params) 20 else 100
        if(limit !in 1..max) throw ApiProblem(400,"invalid-request","Invalid limit")
        if(params["scope"] != null && params["scope"] !in setOf("all","library","favorites")) throw ApiProblem(400,"invalid-request","Invalid scope")
        val sort=params["sort"] ?: if(type=="artist" || type in setOf("genre","source","playlist")) "name" else "title"
        val accepted=when(type) { "artist" -> setOf("name","-addedAt","-playCount","-lastPlayedAt","-libraryAddedAt"); "album" -> setOf("title","artist","-addedAt","-releaseDate","releaseDate","-playCount","-lastPlayedAt","-libraryAddedAt","-favoritedAt","random"); "track" -> setOf("title","artist","album","-addedAt","-playCount","-lastPlayedAt","-libraryAddedAt","-favoritedAt","random"); else -> setOf("name") }
        if(sort !in accepted) throw ApiProblem(400,"invalid-request","Unknown sort")
        val query=hash(j("type" to type,"user" to p.user.id,"params" to params.filterKeys { it !in setOf("cursor","limit") }.toSortedMap()).toString())
        val cursor=params["cursor"]?.let(codec::decode)
        if(cursor!=null && cursor["query"]?.jsonPrimitive?.content!=query) throw ApiProblem(400,"invalid-request","Cursor query mismatch")
        val seed=cursor?.get("seed")?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
        val yearRange=params["year"]?.let { raw ->
            val parts=raw.split("..")
            if(parts.size !in 1..2) throw ApiProblem(400,"invalid-request","Invalid year")
            val low=parts.first().toIntOrNull() ?: throw ApiProblem(400,"invalid-request","Invalid year")
            val high=parts.last().toIntOrNull() ?: throw ApiProblem(400,"invalid-request","Invalid year")
            if(low>high) throw ApiProblem(400,"invalid-request","Invalid year")
            low..high
        }
        val wanted=params["ids"]?.split(',')?.toSet()?.also { if(it.size>500) throw ApiProblem(400,"invalid-request","Too many ids") }
        val entries=ids(type,p).mapNotNull { id ->
            if(wanted!=null && id !in wanted) return@mapNotNull null
            val value=candidate(type,id,p,sort in setOf("-playCount","-lastPlayedAt","-libraryAddedAt","-favoritedAt")) ?: return@mapNotNull null
            if(params["scope"]=="library" && !inLibrary(type,id,p)) return@mapNotNull null
            if(params["scope"]=="favorites" && itemState(id,p)["favoritedAt"]==JsonNull) return@mapNotNull null
            val matches=ctx.db.read {
                when(type) {
                    "track" -> long("""SELECT COUNT(*) FROM track t WHERE id=? AND (? IS NULL OR album_id=?) AND (? IS NULL OR t.id IN (SELECT track_id FROM track_artist WHERE artist_id=?)) AND (? IS NULL OR CAST(folder_id AS TEXT)=?) AND (? IS NULL OR t.id IN (SELECT track_id FROM track_genre WHERE genre=(SELECT key FROM api_group_identity WHERE type='genre' AND id=?)))""",id,params["albumId"],params["albumId"],params["artistId"],params["artistId"],params["sourceId"],params["sourceId"],params["genreId"],params["genreId"])>0
                    "album" -> if(params["artistId"]!=null && params["role"]!=null && params["role"]!="albumArtist") {
                        long("SELECT COUNT(*) FROM track t JOIN track_artist ta ON t.id=ta.track_id WHERE t.album_id=? AND ta.artist_id=? AND ta.role=?",id,params["artistId"],params["role"])>0
                    } else long("""SELECT COUNT(*) FROM album a WHERE id=? AND (? IS NULL OR a.id IN (SELECT album_id FROM album_artist WHERE artist_id=?)) AND (? IS NULL OR a.id IN (SELECT album_id FROM track WHERE CAST(folder_id AS TEXT)=?)) AND (? IS NULL OR a.id IN (SELECT album_id FROM track t JOIN track_genre g ON t.id=g.track_id WHERE g.genre=(SELECT key FROM api_group_identity WHERE type='genre' AND id=?)))""",id,params["artistId"],params["artistId"],params["sourceId"],params["sourceId"],params["genreId"],params["genreId"])>0
                    "artist" -> {
                        val role=params["role"] ?: "albumArtist"
                        val dbRole=if(role=="albumArtist") "albumartist" else role
                        long("SELECT COUNT(*) FROM track_artist ta JOIN track t ON t.id=ta.track_id WHERE ta.artist_id=? AND ta.role=? AND (? IS NULL OR CAST(t.folder_id AS TEXT)=?)",id,dbRole,params["sourceId"],params["sourceId"])>0
                    }
                    else -> true
                }
            }
            if(!matches) return@mapNotNull null
            if(yearRange!=null) {
                val y=value["releaseDate"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull() ?: return@mapNotNull null
                if(y !in yearRange) return@mapNotNull null
            }
            val q=params["q"]?.let(::fold)
            if(q!=null && !com.legitimateapps.dulcet.server.fold((value["title"] ?: value["name"]).toString()+" "+value["artistDisplay"]).contains(q)) return@mapNotNull null
            val state=value["userState"] as? JsonObject ?: j()
            fun time(field:String)=state[field]?.jsonPrimitive?.contentOrNull?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(0) } ?: 0
            fun title(field:String)=value[field]?.jsonPrimitive?.contentOrNull?.let(::fold) ?: ""
            val key=when(sort) {
                "random" -> listOf(JsonPrimitive(hash(seed+id)))
                "name" -> listOf(JsonPrimitive(title("sortName").ifEmpty { title("name") }))
                "artist" -> listOf(JsonPrimitive(title("artistDisplay")),JsonPrimitive(title("sortTitle").ifEmpty { title("title") }))
                "album" -> listOf(JsonPrimitive((value["album"] as? JsonObject)?.get("title")?.jsonPrimitive?.content?.let(::fold) ?: ""),JsonPrimitive((value["album"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: ""),JsonPrimitive(value["discNumber"]?.jsonPrimitive?.intOrNull ?: 1),JsonPrimitive(value["trackNumber"]?.jsonPrimitive?.intOrNull ?: 0))
                "-addedAt" -> listOf(JsonPrimitive(-(value["addedAt"]?.jsonPrimitive?.contentOrNull?.let { java.time.Instant.parse(it).toEpochMilli() } ?: ctx.db.read { long("SELECT MIN(created) FROM track_artist ta JOIN track t ON t.id=ta.track_id WHERE ta.artist_id=?",id) })))
                "-releaseDate" -> listOf(JsonPrimitive(-(value["releaseDate"]?.jsonPrimitive?.contentOrNull?.replace("-","")?.padEnd(8,'0')?.toLongOrNull() ?: 0)))
                "releaseDate" -> listOf(JsonPrimitive(value["releaseDate"]?.jsonPrimitive?.contentOrNull ?: ""))
                "-playCount" -> listOf(JsonPrimitive(-(state["playCount"]?.jsonPrimitive?.longOrNull ?: 0)))
                "-lastPlayedAt" -> listOf(JsonPrimitive(-time("lastPlayedAt")))
                "-libraryAddedAt" -> listOf(JsonPrimitive(-time("libraryAddedAt")))
                "-favoritedAt" -> listOf(JsonPrimitive(-time("favoritedAt")))
                else -> listOf(JsonPrimitive(title("sortTitle").ifEmpty { title("title") }))
            }+JsonPrimitive(id)
            Entry(id,key)
        }.sortedWith { a,b -> compare(a.key,b.key) }
        val after=(cursor?.get("key") as? JsonArray)?.map { it.jsonPrimitive }
        val remaining=if(after==null) entries else entries.filter { compare(it.key,after)>0 }
        val selected=remaining.take(limit)
        val next=if(remaining.size>limit) codec.encode(j("query" to query,"seed" to seed,"key" to selected.last().key)) else null
        return j("items" to selected.mapNotNull { rendered(type,it.id,p) },"nextCursor" to next,"total" to entries.size)
    }
    private fun inLibrary(type:String,id:String,p:Principal):Boolean=ctx.db.read {
        val dbType=if(type=="track") "song" else type
        long("SELECT COUNT(*) FROM library_item WHERE user_id=? AND item_type=? AND item_id=?",p.user.id,dbType,id)>0 || when(type) {
            "album" -> long("SELECT COUNT(*) FROM library_item li JOIN track t ON t.id=li.item_id WHERE li.user_id=? AND li.item_type='song' AND t.album_id=?",p.user.id,id)>0
            "artist" -> long("SELECT COUNT(*) FROM library_item li JOIN album_artist aa ON aa.album_id=li.item_id WHERE li.user_id=? AND li.item_type='album' AND aa.artist_id=?",p.user.id,id)>0 || long("SELECT COUNT(*) FROM library_item li JOIN track_artist ta ON ta.track_id=li.item_id WHERE li.user_id=? AND li.item_type='song' AND ta.artist_id=?",p.user.id,id)>0
            else -> false
        }
    }
    fun install(route:Route) {
        for((type,path) in listOf("artist" to "artists","album" to "albums","track" to "tracks","genre" to "genres","source" to "sources")) {
            route.get("/$path") {
                val p=call.principal(ctx,"catalog:read")
                val value=ctx.db.read { call.stamp(ctx,p);page(type,p,call.request.queryParameters.entries().associate { it.key to it.value.first() }) }
                call.send(value)
            }
            if(type in setOf("artist","album","track")) route.get("/$path/{id}") {
                val p=call.principal(ctx,"catalog:read"); val id=call.parameters["id"]!!
                val value=ctx.db.read {
                    call.stamp(ctx,p)
                    val value=resource(type,id,p) ?: return@read null
                    if(type!="album") value else {
                        val params=mapOf("albumId" to id,"sort" to "album","limit" to "500")
                        val tracks=page("track",p,params)
                        val discs=tracks["items"]!!.jsonArray.groupBy { it.jsonObject["discNumber"]!!.jsonPrimitive.int }.map { (n,items) -> j("number" to n,"tracks" to items) }
                        JsonObject(value+j("discs" to discs,"tracksNextCursor" to tracks["nextCursor"]))
                    }
                }
                if(value==null) {
                    val gone=ctx.db.read { single("SELECT reason,replaced_by FROM api_identity WHERE type=? AND id=? AND reason IS NOT NULL",type,id) { it.string("reason") to it.stringOrNull("replaced_by") } }
                    if(gone!=null) {
                        val visible=ctx.db.read { query("SELECT source_id FROM api_catalog_visibility WHERE type=? AND id=?",type,id) { it.string("source_id") }.any { ctx.access.canReadSource(p.user.id,it) } }
                        if(!visible) throw ApiProblem(404,"not-found")
                        val replacements=JsonArray(liveReplacements(type,gone.second?.let { Json.parseToJsonElement(it).jsonArray.map { value -> value.jsonPrimitive.content } } ?: emptyList()).filter { ctx.access.canReadCatalog(p.user.id,type,it) }.map(::JsonPrimitive))
                        if(gone.first=="merged" && replacements.size==1) { call.response.headers.append("Location","/dulcet/v1/$path/${replacements[0].jsonPrimitive.content}"); call.respond(io.ktor.http.HttpStatusCode.PermanentRedirect) }
                        else throw ApiProblem(410,"gone",extra=j("replacedBy" to replacements))
                    } else throw ApiProblem(404,"not-found")
                } else call.send(value)
            }
        }
        route.get("/artists/{id}/albums") {
            val p=call.principal(ctx,"catalog:read")
            if(resource("artist",call.parameters["id"]!!,p)==null) throw ApiProblem(404,"not-found")
            val result=ctx.db.read { call.stamp(ctx,p);page("album",p,mapOf("sort" to "-releaseDate")+call.request.queryParameters.entries().associate { it.key to it.value.first() }+mapOf("artistId" to call.parameters["id"]!!)) };call.send(result)
        }
        route.get("/search") {
            val p=call.principal(ctx,"catalog:read")
            val params=call.request.queryParameters.entries().associate { it.key to it.value.first() }
            val q=params["q"] ?: throw ApiProblem(400,"invalid-request","Missing q")
            if(q.isBlank()||q.length>500) throw ApiProblem(400,"invalid-request","Invalid q")
            val types=params["types"]?.split(',') ?: listOf("artist","album","track","playlist")
            val result=ctx.db.read {
                call.stamp(ctx,p)
                j(*types.mapNotNull { type ->
                    if(type=="playlist") "playlists" to page(type,p,params,100)
                    else if(type in setOf("artist","album","track")) (type+"s") to page(type,p,params,100)
                    else null
                }.toTypedArray())
            };call.send(result)
        }
        route.get("/tracks/{id}/lyrics") {
            val p=call.principal(ctx,"catalog:read");val id=call.parameters["id"]!!
            if(resource("track",id,p)==null) throw ApiProblem(404,"not-found")
            val texts=ctx.lyrics.forTrack(id).orEmpty()
            call.stamp(ctx,p)
            call.send(j("trackId" to id,"documents" to texts.map { text -> j("kind" to "main","synced" to text.synced,"language" to text.language,"offsetMs" to text.offsetMillis,"source" to text.source,"lines" to text.lines.map { line -> optional("text" to line.text,"startMs" to line.startMillis) }) }))
        }
    }
}
fun container(suffix:String):String=when(suffix.lowercase()) { "m4a","m4b","mp4","alac" -> "mp4"; "oga","opus","ogg" -> "ogg";else -> suffix.lowercase() }
