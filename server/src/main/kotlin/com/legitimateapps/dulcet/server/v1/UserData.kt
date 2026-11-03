package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.auth.PasswordVault
import com.legitimateapps.dulcet.server.api.markLibraryChanged
import com.legitimateapps.dulcet.server.db.Db
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.respondOutputStream
import java.nio.file.Files
import java.io.BufferedWriter
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** User data stays in the OpenSubsonic tables; these tables retain the extra v1 semantics. */
class ApiUserData(private val ctx: ServerContext) {
    private val db = ctx.db
    private val cursorKey = PasswordVault.keyFromDataFolder(ctx.config.dataFolder)
    init {
        db.write {
            execute("CREATE TABLE IF NOT EXISTS api_state_clock(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,item_id TEXT NOT NULL,field TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(user_id,item_id,field))")
            execute("CREATE TABLE IF NOT EXISTS api_state_mutation(user_id TEXT NOT NULL,item_id TEXT NOT NULL,PRIMARY KEY(user_id,item_id))")
            // Legacy incremental reads need receipt time even when an offline API action is old.
            execute("CREATE TABLE IF NOT EXISTS api_library_receipt(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,item_id TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(user_id,item_id))")
            val wall="CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)"
            for((column,field) in listOf("starred" to "favorite","rating" to "rating")) {
                execute("CREATE TRIGGER IF NOT EXISTS api_clock_annotation_$field AFTER UPDATE OF $column ON annotation WHEN NOT EXISTS(SELECT 1 FROM api_state_mutation WHERE user_id=NEW.user_id AND item_id=NEW.item_id) BEGIN INSERT INTO api_state_clock VALUES(NEW.user_id,NEW.item_id,'$field',$wall) ON CONFLICT(user_id,item_id,field) DO UPDATE SET at=excluded.at; END")
                execute("CREATE TRIGGER IF NOT EXISTS api_clock_annotation_insert_$field AFTER INSERT ON annotation WHEN NEW.$column ${if(column=="starred") "IS NOT NULL" else ">0"} AND NOT EXISTS(SELECT 1 FROM api_state_mutation WHERE user_id=NEW.user_id AND item_id=NEW.item_id) BEGIN INSERT INTO api_state_clock VALUES(NEW.user_id,NEW.item_id,'$field',$wall) ON CONFLICT(user_id,item_id,field) DO UPDATE SET at=excluded.at; END")
            }
            for((action,ref) in listOf("INSERT" to "NEW","UPDATE" to "NEW","DELETE" to "OLD")) {
                execute("CREATE TRIGGER IF NOT EXISTS api_clock_library_${action.lowercase()} AFTER $action ON library_item WHEN NOT EXISTS(SELECT 1 FROM api_state_mutation WHERE user_id=$ref.user_id AND item_id=$ref.item_id) BEGIN INSERT INTO api_state_clock VALUES($ref.user_id,$ref.item_id,'library',$wall) ON CONFLICT(user_id,item_id,field) DO UPDATE SET at=excluded.at; END")
            }
            execute("CREATE TABLE IF NOT EXISTS api_rating(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,item_id TEXT NOT NULL,value INTEGER NOT NULL,legacy_value INTEGER NOT NULL,PRIMARY KEY(user_id,item_id))")
            execute("CREATE TRIGGER IF NOT EXISTS api_rating_legacy_update AFTER UPDATE OF rating ON annotation BEGIN DELETE FROM api_rating WHERE user_id=NEW.user_id AND item_id=NEW.item_id; END")
            execute("CREATE TABLE IF NOT EXISTS api_play(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,id TEXT NOT NULL,track_id TEXT,started_at INTEGER,payload TEXT,recorded_at INTEGER,PRIMARY KEY(user_id,id))")
            execute("CREATE INDEX IF NOT EXISTS api_play_history ON api_play(user_id,started_at,id)")
            execute("CREATE TABLE IF NOT EXISTS api_play_id(id TEXT PRIMARY KEY)")
            execute("INSERT OR IGNORE INTO api_play_id SELECT id FROM api_play")
            execute("CREATE TABLE IF NOT EXISTS api_now_playing(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,device_id TEXT NOT NULL,payload TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(user_id,device_id))")
            execute("CREATE TABLE IF NOT EXISTS api_playlist_entry(playlist_id TEXT NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,position INTEGER NOT NULL,track_id TEXT NOT NULL,entry_id TEXT NOT NULL,added INTEGER NOT NULL,PRIMARY KEY(playlist_id,position),UNIQUE(playlist_id,entry_id))")
            for((table,device) in listOf("api_now_playing" to "device_id","now_playing" to "client")) {
                for((action,ref) in listOf("INSERT" to "NEW","UPDATE" to "NEW","DELETE" to "OLD")) {
                    val op=if(action=="DELETE") "delete" else "upsert"
                    // Heartbeats are journaled for atomicity; the Core+Sync feed does not advertise this transient type.
                    execute("CREATE TRIGGER IF NOT EXISTS api_${table}_journal_${action.lowercase()} AFTER $action ON $table BEGIN INSERT INTO api_journal(type,id,user_id,op,at) VALUES('nowPlaying',$ref.$device,$ref.user_id,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
                }
            }
        }
    }

    fun install(route: Route) = with(route) {
        get("/me/library") {
            val p = call.principal(ctx, "user:read")
            val page = members(call, p, true)
            call.send(page)
        }
        get("/me/favorites") { val p = call.principal(ctx,"user:read"); call.send(members(call,p,false)) }
        put("/me/library/{itemId}") { val p=call.principal(ctx,"user:write"); call.send(change(p,call.parameters["itemId"]!!,"libraryAdd",null,call.request.headers["Dulcet-Action-Time"])) }
        delete("/me/library/{itemId}") { val p=call.principal(ctx,"user:delete"); change(p,call.parameters["itemId"]!!,"libraryRemove",null,call.request.headers["Dulcet-Action-Time"]); call.send(JsonNull,204) }
        put("/me/favorites/{itemId}") { val p=call.principal(ctx,"user:write"); call.send(change(p,call.parameters["itemId"]!!,"favorite",null,call.request.headers["Dulcet-Action-Time"])) }
        delete("/me/favorites/{itemId}") { val p=call.principal(ctx,"user:write"); change(p,call.parameters["itemId"]!!,"unfavorite",null,call.request.headers["Dulcet-Action-Time"]); call.send(JsonNull,204) }
        put("/me/ratings/{itemId}") { val p=call.principal(ctx,"user:write"); call.send(change(p,call.parameters["itemId"]!!,"rate",call.body()["rating"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull,call.request.headers["Dulcet-Action-Time"])) }
        delete("/me/ratings/{itemId}") { val p=call.principal(ctx,"user:write"); change(p,call.parameters["itemId"]!!,"unrate",null,call.request.headers["Dulcet-Action-Time"]); call.send(JsonNull,204) }
        post("/me/batch") {
            val p=call.principal(ctx)
            val ops=array(call.body(),"operations",1,500)
            val results=ops.map { raw ->
                try {
                    val op=raw.jsonObject; val name=text(op,"op"); val id=text(op,"itemId")
                    val result=change(p,id,name,op["rating"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull,op["at"]?.jsonPrimitive?.contentOrNull)
                    j("status" to if(name in setOf("unfavorite","unrate","libraryRemove")) 204 else 200,"applied" to result["applied"])
                } catch (e:ApiProblem) { j("status" to e.status,"problem" to JsonObject(j("type" to "urn:dulcet:problem:${e.code}","title" to e.code,"status" to e.status)+e.extra)) }
                catch (e: IllegalArgumentException) { j("status" to 400,"problem" to j("type" to "urn:dulcet:problem:invalid-request","title" to "Invalid operation","status" to 400,"code" to "invalid-request")) }
            }
            call.send(j("results" to JsonArray(results)))
        }
        post("/me/plays") { val p=call.principal(ctx,"plays:write"); val plays=array(call.body(),"plays",1,500); call.send(j("results" to JsonArray(plays.map { recordPlay(p,it.jsonObject) }))) }
        get("/me/plays") {
            val p=call.principal(ctx,"user:read"); val q=call.request.queryParameters
            val since=q["since"]?.let(::parseTime); val track=q["trackId"]
            val result=db.read {
                val limit=call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request") } ?: 100
                if(limit !in 1..500) throw ApiProblem(400,"invalid-request")
                val after=call.request.queryParameters["cursor"]?.let { raw -> (decodeCursor(raw)["key"] as? JsonArray)?.map { it.jsonPrimitive.content }?.takeIf { it.size==2 } ?: throw ApiProblem(400,"invalid-request") }
                val afterTime=after?.first()?.let { key -> runCatching { (Long.MAX_VALUE.toBigInteger()-key.toBigInteger()).longValueExact() }.getOrNull() }
                if(after!=null && afterTime==null) throw ApiProblem(400,"invalid-request")
                val afterId=after?.get(1)?.map { c -> (Char.MAX_VALUE.code-c.code).toChar() }?.joinToString("")
                val rows=query("SELECT payload FROM api_play WHERE user_id=? AND payload IS NOT NULL AND (? IS NULL OR started_at>=?) AND (? IS NULL OR track_id=?) AND (? IS NULL OR started_at<? OR (started_at=? AND id<?)) ORDER BY started_at DESC,id DESC LIMIT ?",p.user.id,since,since,track,track,afterTime,afterTime,afterTime,afterId,limit+1) { Json.parseToJsonElement(it.string("payload")).jsonObject }
                val total=long("SELECT COUNT(*) FROM api_play WHERE user_id=? AND payload IS NOT NULL AND (? IS NULL OR started_at>=?) AND (? IS NULL OR track_id=?)",p.user.id,since,since,track,track)
                JsonObject(page(call,p,"plays",rows)+j("total" to total))
            }
            call.send(result)
        }
        delete("/me/plays/{playId}") { val p=call.principal(ctx,"user:delete"); deletePlay(p,call.parameters["playId"]!!); call.send(JsonNull,204) }
        put("/me/now-playing") {
            val p=call.principal(ctx,"plays:write"); val body=call.body(); val device=text(body,"deviceId"); val track=text(body,"trackId")
            if(itemType(track)!="track") throw ApiProblem(404,"not-found")
            val state=text(body,"state"); if(state !in setOf("playing","paused","buffering")) throw ApiProblem(400,"invalid-request")
            val position=body["positionMs"]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull ?: throw ApiProblem(400,"invalid-request")
            val ttl=if("ttlSeconds" in body) body["ttlSeconds"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: throw ApiProblem(400,"invalid-request") else 120
            if(position<0 || ttl !in 30..600) throw ApiProblem(400,"invalid-request")
            val now=System.currentTimeMillis(); val stored=JsonObject(body.filterKeys { it in setOf("deviceId","deviceName","trackId","state","positionMs","ttlSeconds") }+j("ttlSeconds" to ttl,"userId" to p.user.id,"updatedAt" to stamp(now),"expiresAt" to stamp(now+ttl*1000L)))
            db.write {
                execute("INSERT INTO api_now_playing VALUES (?,?,?,?) ON CONFLICT(user_id,device_id) DO UPDATE SET payload=excluded.payload,expires_at=excluded.expires_at",p.user.id,device,stored.toString(),now+ttl*1000L)
                execute("INSERT INTO now_playing VALUES (?,?,?,?) ON CONFLICT(user_id,client) DO UPDATE SET track_id=excluded.track_id,at=excluded.at",p.user.id,device,track,now)
            }
            call.send(stored)
        }
        delete("/me/now-playing") { val p=call.principal(ctx,"plays:write"); val device=call.request.queryParameters["deviceId"] ?: call.request.headers["Dulcet-Device-Id"] ?: throw ApiProblem(400,"invalid-request"); if(device.isBlank()) throw ApiProblem(400,"invalid-request"); db.write { execute("DELETE FROM api_now_playing WHERE user_id=? AND device_id=?",p.user.id,device); execute("DELETE FROM now_playing WHERE user_id=? AND client=?",p.user.id,device) }; call.send(JsonNull,204) }
        get("/now-playing") {
            val p=call.principal(ctx,"user:read")
            val items=visibleNowPlaying(p).map { row ->
                if("catalog:read" in p.scopes) JsonObject(row+mapOf("track" to (ctx.apiCatalog.resource("track",row["trackId"]!!.jsonPrimitive.content,p) ?: JsonNull))) else row
            }
            call.send(j("items" to JsonArray(items)))
        }
        get("/playlists") {
            val p=call.principal(ctx,"user:read"); val owner=call.request.queryParameters["owner"]?.let { if(it=="me") p.user.id else it }
            val sort=when(call.request.queryParameters["sort"] ?: "name") { "name" -> "name COLLATE NOCASE,id"; "-updatedAt" -> "changed DESC,id"; "-createdAt" -> "created DESC,id"; else -> throw ApiProblem(400,"invalid-request") }
            val result=db.read {
                val ids=query("SELECT id FROM playlist WHERE (owner_id=? OR public=1) AND (? IS NULL OR owner_id=?) ORDER BY $sort",p.user.id,owner,owner) { it.string("id") }
                page(call,p,"playlists",ids.mapNotNull { resource("playlist",it,p) })
            }
            call.send(result)
        }
        post("/playlists") {
            val p=call.principal(ctx,"user:write")
            val result=createPlaylist(p,call.body())
            call.response.headers.append("Location",call.apiLocation("/dulcet/v1/playlists/${result["id"]!!.jsonPrimitive.content}"))
            call.send(result,201,tag(result))
        }
        get("/playlists/{playlistId}") { val p=call.principal(ctx,"user:read"); val result=db.read { val row=resource("playlist",call.parameters["playlistId"]!!,p) ?: throw ApiProblem(404,"not-found"); call.stamp(ctx,p); row }; call.send(result,etag=tag(result)) }
        patch("/playlists/{playlistId}") {
            val p=call.principal(ctx,"user:write"); val id=call.parameters["playlistId"]!!; val body=call.body()
            db.write {
                owned(id,p); checkMatch(call,id,p)
                val requestedVisibility=if("visibility" in body) visibility(body) else null
                if(requestedVisibility!=null) authorizePublication(p,requestedVisibility)
                if("name" in body) execute("UPDATE playlist SET name=? WHERE id=?",playlistName(body),id)
                if("description" in body) execute("UPDATE playlist SET comment=? WHERE id=?",body["description"]?.jsonPrimitive?.contentOrNull ?: "",id)
                if(requestedVisibility!=null) execute("UPDATE playlist SET public=? WHERE id=?",requestedVisibility=="public",id)
                execute("UPDATE playlist SET changed=? WHERE id=?",System.currentTimeMillis(),id)
            }
            val result=resource("playlist",id,p)!!; call.send(result,etag=tag(result))
        }
        delete("/playlists/{playlistId}") {
            val p=call.principal(ctx,"user:delete"); val id=call.parameters["playlistId"]!!
            db.write { if(long("SELECT COUNT(*) FROM playlist WHERE id=?",id)>0) { owned(id,p); checkMatch(call,id,p); execute("DELETE FROM playlist WHERE id=?",id) } }; call.send(JsonNull,204)
        }
        get("/playlists/{playlistId}/items") {
            val p=call.principal(ctx,"user:read"); val id=call.parameters["playlistId"]!!
            val result=db.write {
                val playlist=resource("playlist",id,p) ?: throw ApiProblem(404,"not-found")
                val revision=playlist["revision"]!!.jsonPrimitive.content
                val items=entries(id).mapIndexed { i,e ->
                    val base=j("entryId" to e.id,"position" to i,"itemId" to e.track,"addedAt" to stamp(e.added))
                    if("catalog:read" in p.scopes) JsonObject(base+mapOf("track" to (ctx.apiCatalog.resource("track",e.track,p) ?: JsonNull))) else base
                }
                JsonObject(page(call,p,"playlist:$id",items,revision)+mapOf("revision" to JsonPrimitive(revision)))
            }
            call.send(result,etag="\"${result["revision"]!!.jsonPrimitive.content}\"")
        }
        put("/playlists/{playlistId}/items") { mutateEntries(call,"replace") }
        post("/playlists/{playlistId}/items") { mutateEntries(call,"insert") }
        patch("/playlists/{playlistId}/items/{entryId}") { mutateEntries(call,"move") }
        delete("/playlists/{playlistId}/items/{entryId}") { mutateEntries(call,"remove") }
        get("/me/export") {
            val p=call.principal(ctx,"user:read")
            val file=Files.createTempFile(ctx.config.dataFolder,"api-export-",".ndjson")
            try {
            db.write {
            Files.newBufferedWriter(file).use { writer ->
            val records=RecordSink(writer)
            records+=j("kind" to "header","standard" to "dulcet","apiVersion" to "1.0","exportedAt" to stamp(System.currentTimeMillis()),"userId" to p.user.id)
            db.read { query("SELECT item_id FROM annotation WHERE user_id=? UNION SELECT item_id FROM library_item WHERE user_id=?",p.user.id,p.user.id) { it.string("item_id") } }.forEach { id ->
                val state=itemState(id,p)
                val type=state["itemType"]!!.jsonPrimitive.content
                val external=if(type=="playlist") null else ctx.apiCatalog.resource(type,id,p)?.get("externalIds")
                val base=j("itemId" to id,"itemType" to type,"externalIds" to external)
                records+=JsonObject(state+j("kind" to "itemState","externalIds" to external))
                if(state["favoritedAt"]!=JsonNull) records+=JsonObject(base+j("kind" to "favorite","favoritedAt" to state["favoritedAt"]))
                if(state["rating"]!=JsonNull) records+=JsonObject(base+j("kind" to "rating","rating" to state["rating"]))
                if(state["libraryAddedAt"]!=JsonNull) records+=JsonObject(base+j("kind" to "libraryMember","libraryAddedAt" to state["libraryAddedAt"]))
            }
            db.read { query("SELECT id FROM playlist WHERE owner_id=? ORDER BY id",p.user.id) { it.string("id") } }.forEach { id ->
                val pl=resource("playlist",id,p)!!; val items=db.write { entries(id).map { j("entryId" to it.id,"itemId" to it.track,"addedAt" to stamp(it.added)) } }
                records+=JsonObject(pl+mapOf("kind" to JsonPrimitive("playlist"),"items" to JsonArray(items)))
            }
            ctx.apiUsers.resource(p.user.id)?.let { records+=JsonObject(it+j("kind" to "account")) }
            connection.prepareStatement("SELECT payload FROM api_play WHERE user_id=? AND payload IS NOT NULL ORDER BY started_at,id").use { statement ->
                statement.setString(1,p.user.id)
                statement.executeQuery().use { rows -> while(rows.next()) {
                    val play=Json.parseToJsonElement(rows.getString("payload")).jsonObject
                    records+=JsonObject(play+j("kind" to "play","externalIds" to ctx.apiCatalog.resource("track",play["trackId"]!!.jsonPrimitive.content,p)?.get("externalIds")))
                } }
            }
            } }
            call.response.headers.append("Cache-Control","private, no-store")
            call.respondOutputStream(ContentType.parse("application/x-ndjson")) {
                try { Files.newInputStream(file).use { it.copyTo(this) } } finally { Files.deleteIfExists(file) }
            }
            } catch(error:Throwable) { Files.deleteIfExists(file); throw error }
        }
    }

    fun itemState(id:String,p:Principal):JsonObject = db.read {
        val type=itemType(id) ?: string("SELECT item_type FROM annotation WHERE user_id=? AND item_id=? UNION SELECT item_type FROM library_item WHERE user_id=? AND item_id=?",p.user.id,id,p.user.id,id)?.let(::apiType) ?: "track"
        val legacy=legacyType(type)
        val annotation=single("SELECT starred,rating,play_count,played FROM annotation WHERE user_id=? AND item_type=? AND item_id=?",p.user.id,legacy,id) { r ->
            val rating=r.int("rating"); val exact=single("SELECT value FROM api_rating WHERE user_id=? AND item_id=? AND legacy_value=?",p.user.id,id,rating) { it.int("value") }
            j("favoritedAt" to r.longOrNull("starred")?.let(::stamp),"rating" to (exact ?: (rating*2).takeIf { it>0 }),"playCount" to r.long("play_count"),"lastPlayedAt" to r.longOrNull("played")?.let(::stamp))
        } ?: j("favoritedAt" to null,"rating" to null,"playCount" to 0,"lastPlayedAt" to null)
        val added=single("SELECT added FROM library_item WHERE user_id=? AND item_type=? AND item_id=?",p.user.id,legacy,id) { it.long("added") }
        JsonObject(annotation+j("itemId" to id,"itemType" to type,"libraryAddedAt" to added?.let(::stamp),"revision" to ctx.journal.revision("itemState",id,p.user.id)))
    }

    fun resource(type:String,id:String,p:Principal):JsonObject? = when(type) {
        "itemState" -> itemState(id,p)
        "play" -> db.read { single("SELECT payload FROM api_play WHERE user_id=? AND id=? AND payload IS NOT NULL",p.user.id,id) { Json.parseToJsonElement(it.string("payload")).jsonObject } }
        "playlist" -> db.read { single("""SELECT p.*,u.name AS owner_name,(SELECT COUNT(*) FROM playlist_track WHERE playlist_id=p.id) AS item_count,
            (SELECT COALESCE(SUM(t.duration),0) FROM playlist_track pt JOIN track t ON t.id=pt.track_id WHERE pt.playlist_id=p.id) AS duration FROM playlist p JOIN user u ON u.id=p.owner_id WHERE p.id=? AND (p.owner_id=? OR p.public=1)""",id,p.user.id) { r ->
            j("id" to id,"type" to "playlist","name" to r.string("name"),"description" to r.stringOrNull("comment"),"owner" to j("id" to r.string("owner_id"),"name" to r.string("owner_name")),"visibility" to if(r.bool("public")) "public" else "private","readOnly" to (r.string("owner_id")!=p.user.id),"itemCount" to r.long("item_count"),"durationMs" to (r.double("duration")*1000).toLong(),"createdAt" to stamp(r.long("created")),"updatedAt" to stamp(r.long("changed")),"revision" to ctx.journal.revision("playlist",id))
        } }
        else -> null
    }

    internal fun change(p:Principal,id:String,op:String,rating:Int?,at:String?):JsonObject {
        val field=when(op) { "favorite","unfavorite" -> "favorite"; "rate","unrate" -> "rating"; "libraryAdd","libraryRemove" -> "library"; else -> throw ApiProblem(400,"invalid-request") }
        requireScope(p,if(op=="libraryRemove") "user:delete" else "user:write")
        if(op=="rate" && (rating==null || rating !in 1..10)) throw ApiProblem(400,"invalid-request")
        val time=at?.let(::parseTime) ?: System.currentTimeMillis()
        return db.write {
            val type=itemType(id) ?: if(op in setOf("libraryRemove","unfavorite","unrate")) string("SELECT item_type FROM annotation WHERE user_id=? AND item_id=? UNION SELECT item_type FROM library_item WHERE user_id=? AND item_id=?",p.user.id,id,p.user.id,id)?.let(::apiType) ?: "track" else throw ApiProblem(404,"not-found")
            if(field=="library" && type !in setOf("artist","album","track")) throw ApiProblem(400,"invalid-request")
            if(type=="playlist" && resource("playlist",id,p)==null) throw ApiProblem(404,"not-found")
            val last=single("SELECT at FROM api_state_clock WHERE user_id=? AND item_id=? AND field=?",p.user.id,id,field) { it.long("at") }
            if(last!=null && time<last) return@write JsonObject(itemState(id,p)+j("applied" to false))
            val legacy=legacyType(type)
            execute("INSERT INTO api_state_mutation VALUES (?,?)",p.user.id,id)
            execute("INSERT OR IGNORE INTO annotation(user_id,item_type,item_id) VALUES (?,?,?)",p.user.id,legacy,id)
            when(op) {
                "favorite" -> execute("UPDATE annotation SET starred=COALESCE(starred,?) WHERE user_id=? AND item_type=? AND item_id=?",time,p.user.id,legacy,id)
                "unfavorite" -> execute("UPDATE annotation SET starred=NULL WHERE user_id=? AND item_type=? AND item_id=?",p.user.id,legacy,id)
                "rate" -> {
                    val stars=(rating!!+1)/2
                    execute("UPDATE annotation SET rating=? WHERE user_id=? AND item_type=? AND item_id=?",stars,p.user.id,legacy,id)
                    execute("INSERT INTO api_rating VALUES (?,?,?,?) ON CONFLICT(user_id,item_id) DO UPDATE SET value=excluded.value,legacy_value=excluded.legacy_value",p.user.id,id,rating,stars)
                }
                "unrate" -> { execute("UPDATE annotation SET rating=0 WHERE user_id=? AND item_type=? AND item_id=?",p.user.id,legacy,id); execute("DELETE FROM api_rating WHERE user_id=? AND item_id=?",p.user.id,id) }
                "libraryAdd" -> execute("INSERT OR IGNORE INTO library_item VALUES (?,?,?,?)",p.user.id,legacy,id,time)
                "libraryRemove" -> execute("DELETE FROM library_item WHERE user_id=? AND item_type=? AND item_id=?",p.user.id,legacy,id)
            }
            execute("DELETE FROM api_state_mutation WHERE user_id=? AND item_id=?",p.user.id,id)
            execute("INSERT INTO api_state_clock VALUES (?,?,?,?) ON CONFLICT(user_id,item_id,field) DO UPDATE SET at=excluded.at",p.user.id,id,field,time)
            // /rest readers probe this clock before deciding whether to refresh My Library.
            if(field=="library") {
                val received=System.currentTimeMillis()
                markLibraryChanged(p.user.id,received)
                if(op=="libraryAdd") execute("INSERT INTO api_library_receipt(user_id,item_id,at) SELECT ?,?,CAST(value AS INTEGER) FROM meta WHERE key=? ON CONFLICT(user_id,item_id) DO UPDATE SET at=excluded.at",p.user.id,id,"library_changed:${p.user.id}")
            }
            // An odd half-star may change while the rounded legacy value does not.
            if(op=="rate") journal("itemState",id,p.user.id,"upsert")
            JsonObject(itemState(id,p)+j("applied" to true))
        }
    }

    internal fun recordPlay(p:Principal,body:JsonObject):JsonObject {
        val id=text(body,"id"); if(runCatching { UUID.fromString(id).toString().equals(id,true) }.getOrDefault(false).not()) throw ApiProblem(400,"invalid-request")
        val track=text(body,"trackId"); val started=parseTime(text(body,"startedAt")); val listened=body["listenedMs"]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull ?: throw ApiProblem(400,"invalid-request")
        if(listened<0) throw ApiProblem(400,"invalid-request")
        return db.write {
            if(long("SELECT COUNT(*) FROM api_play_id WHERE id=?",id)>0) return@write j("id" to id,"status" to "duplicate")
            if(itemType(track)!="track") return@write j("id" to id,"status" to "unknownTrack")
            val now=System.currentTimeMillis()
            if(listened==0L || started>now+300000L) return@write j("id" to id,"status" to "ignored")
            val stored=JsonObject(body+j("startedAt" to stamp(started),"recordedAt" to stamp(now)))
            execute("INSERT INTO api_play_id VALUES (?)",id)
            execute("INSERT INTO api_play VALUES (?,?,?,?,?,?)",p.user.id,id,track,started,stored.toString(),now)
            execute("INSERT OR IGNORE INTO annotation(user_id,item_type,item_id) VALUES (?,'song',?)",p.user.id,track)
            execute("UPDATE annotation SET play_count=play_count+1,played=MAX(COALESCE(played,0),?) WHERE user_id=? AND item_type='song' AND item_id=?",started,p.user.id,track)
            journal("play",id,p.user.id,"upsert")
            j("id" to id,"status" to "recorded")
        }
    }

    internal fun deletePlay(p:Principal,id:String) = db.write {
        val row=single("SELECT track_id,started_at FROM api_play WHERE user_id=? AND id=? AND payload IS NOT NULL",p.user.id,id) { it.string("track_id") to it.long("started_at") } ?: return@write
        execute("DELETE FROM api_play WHERE user_id=? AND id=?",p.user.id,id)
        val latest=single("SELECT MAX(started_at) AS at FROM api_play WHERE user_id=? AND track_id=? AND payload IS NOT NULL",p.user.id,row.first) { it.longOrNull("at") }
        execute("UPDATE annotation SET play_count=MAX(play_count-1,0),played=CASE WHEN played=? THEN ? ELSE played END WHERE user_id=? AND item_type='song' AND item_id=?",row.second,latest,p.user.id,row.first)
        journal("play",id,p.user.id,"delete")
    }

    private fun members(call:ApplicationCall,p:Principal,library:Boolean):JsonObject = db.read {
        call.stamp(ctx,p)
        members(p,library,call.request.queryParameters.entries().associate { it.key to it.value.first() })
    }
    private fun members(p:Principal,library:Boolean,params:Map<String,String>):JsonObject = db.read {
        val types=params["types"]?.split(',')?.toSet()
        val allowed=if(library) setOf("artist","album","track") else setOf("artist","album","track","playlist")
        if(types!=null && !allowed.containsAll(types)) throw ApiProblem(400,"invalid-request")
        val rows=db.read {
            if(library) query("SELECT item_type,item_id,added AS at FROM library_item WHERE user_id=? ORDER BY added DESC,item_id",p.user.id) { Triple(apiType(it.string("item_type")),it.string("item_id"),it.long("at")) }
            else query("SELECT item_type,item_id,starred AS at FROM annotation WHERE user_id=? AND starred IS NOT NULL ORDER BY starred DESC,item_id",p.user.id) { Triple(apiType(it.string("item_type")),it.string("item_id"),it.long("at")) }
        }
        val items=rows.filter { types==null || it.first in types }.mapNotNull { (type,id,at) ->
            val item=if(type=="playlist") resource(type,id,p) else ctx.apiCatalog.resource(type,id,p)
            item?.let { j("item" to j("id" to id,"type" to type,"name" to (it[if(type=="track" || type=="album") "title" else "name"] ?: it["name"])),(if(library) "libraryAddedAt" else "favoritedAt") to stamp(at)) }
        }
        val result=page(p,if(library) "library" else "favorites",items,params=params)
        if(library) JsonObject(result+mapOf("counts" to j("artist" to rows.count { it.first=="artist" },"album" to rows.count { it.first=="album" },"track" to rows.count { it.first=="track" }))) else result
    }

    private fun visibleNowPlaying(p:Principal,includeAdmin:Boolean=true):List<JsonObject> = db.read {
        query("SELECT n.payload FROM api_now_playing n LEFT JOIN api_profile profile ON profile.user_id=n.user_id WHERE n.expires_at>? AND (n.user_id=? OR ?=1 OR json_extract(profile.settings,'$.shareNowPlaying')=1)",System.currentTimeMillis(),p.user.id,includeAdmin && p.user.isAdmin) { Json.parseToJsonElement(it.string("payload")).jsonObject }
    }

    private fun createPlaylist(p:Principal,body:JsonObject):JsonObject {
        requireScopes(p,"user:write")
        val name=playlistName(body); val ids=itemIds(body,false)
        val visibility=visibility(body); val now=System.currentTimeMillis(); val id=UUID.randomUUID().toString()
        db.write {
            validateTracks(ids,p)
            authorizePublication(p,visibility)
            execute("INSERT INTO playlist(id,owner_id,name,comment,public,created,changed) VALUES (?,?,?,?,?,?,?)",id,p.user.id,name,body["description"]?.jsonPrimitive?.contentOrNull ?: "",visibility=="public",now,now)
            saveEntries(id,ids.map { Entry(UUID.randomUUID().toString(),it,now) })
        }
        return resource("playlist",id,p)!!
    }

    /** MCP shares the same account isolation, mutation helpers and paging as the HTTP routes. */
    fun mcp(p:Principal,name:String,args:JsonObject):JsonObject {
        val params=args.entries.associate { (key,value) -> key to if(value is JsonArray) value.joinToString(",") { it.jsonPrimitive.content } else value.jsonPrimitive.content }.toMutableMap()
        params.putIfAbsent("limit","20")
        fun track(id:String):JsonObject {
            val row=ctx.apiCatalog.resource("track",id,p) ?: throw ApiProblem(404,"not-found")
            return JsonObject(row+j("artist" to row["artistDisplay"],"album" to (row["album"] as? JsonObject)?.get("title")))
        }
        fun checkRevision(id:String) {
            val row=resource("playlist",id,p) ?: throw ApiProblem(404,"not-found")
            if(args["revision"]!=row["revision"]) throw ApiProblem(412,"revision-mismatch",extra=j("current" to row))
        }
        return when(name) {
            "list_my_library","list_favorites" -> {
                requireScopes(p,"user:read")
                val page=members(p,name=="list_my_library",params)
                j("items" to page["items"]!!.jsonArray.map { it.jsonObject["item"]!! },"nextCursor" to page["nextCursor"])
            }
            "list_playlists" -> {
                requireScopes(p,"user:read")
                val rows=db.read { query("SELECT id FROM playlist WHERE owner_id=? OR public=1 ORDER BY name COLLATE NOCASE,id",p.user.id) { it.string("id") } }.mapNotNull { resource("playlist",it,p) }
                page(p,"playlists",rows,params=params)
            }
            "get_playlist" -> {
                requireScopes(p,"user:read")
                val id=text(args,"id")
                val playlist=resource("playlist",id,p) ?: throw ApiProblem(404,"not-found")
                val result=db.write {
                    val rows=entries(id).mapIndexed { index,entry -> j("position" to index,"entryId" to entry.id,"track" to track(entry.track)) }
                    page(p,"playlist:$id",rows,playlist["revision"]!!.jsonPrimitive.content,params)
                }
                j("playlist" to playlist,"entries" to result["items"],"nextCursor" to result["nextCursor"])
            }
            "list_recent_plays" -> {
                requireScopes(p,"user:read")
                val since=args["since"]?.jsonPrimitive?.content?.let(::parseTime)
                val rows=db.read { query("SELECT payload FROM api_play WHERE user_id=? AND payload IS NOT NULL AND (? IS NULL OR started_at>=?) ORDER BY started_at DESC,id DESC",p.user.id,since,since) { Json.parseToJsonElement(it.string("payload")).jsonObject } }
                val result=page(p,"plays",rows,params=params)
                j("items" to result["items"]!!.jsonArray.map { value -> val row=value.jsonObject; JsonObject(row+j("playId" to row["id"],"track" to track(row["trackId"]!!.jsonPrimitive.content))) },"nextCursor" to result["nextCursor"])
            }
            "get_listening_stats" -> {
                requireScopes(p,"user:read")
                val period=params["period"] ?: "month"
                val from=when(period) { "week" -> System.currentTimeMillis()-7*86400000L; "month" -> System.currentTimeMillis()-30*86400000L; "year" -> System.currentTimeMillis()-365*86400000L; else -> null }
                val rows=db.read { query("SELECT track_id,payload FROM api_play WHERE user_id=? AND payload IS NOT NULL AND (? IS NULL OR started_at>=?)",p.user.id,from,from) { it.string("track_id") to Json.parseToJsonElement(it.string("payload")).jsonObject } }
                val top=args["top"]?.jsonPrimitive?.int ?: 10
                val counts=rows.groupingBy { it.first }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String,Int>> { it.value }.thenBy { it.key })
                val topTracks=counts.take(top).mapNotNull { (id,count) -> runCatching { j("track" to track(id),"plays" to count) }.getOrNull() }
                val artists=rows.flatMap { (id,_) -> db.read { query("SELECT DISTINCT artist_id,name FROM track_artist WHERE track_id=? AND role='artist'",id) { it.string("artist_id") to it.string("name") } } }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Pair<String,String>,Int>> { it.value }.thenBy { it.key.first }).take(top).map { j("id" to it.key.first,"name" to it.key.second,"plays" to it.value) }
                j("period" to period,"from" to from?.let(::stamp),"plays" to rows.size,"listenedMs" to rows.sumOf { it.second["listenedMs"]?.jsonPrimitive?.longOrNull ?: 0 },"topTracks" to topTracks,"topArtists" to artists)
            }
            "get_now_playing" -> {
                requireScopes(p,"user:read")
                val rows=visibleNowPlaying(p,includeAdmin=false)
                j("items" to rows.map { row -> JsonObject(row+j("userName" to (ctx.users.byId(row["userId"]!!.jsonPrimitive.content)?.name ?: ""),"track" to track(row["trackId"]!!.jsonPrimitive.content))) })
            }
            "create_playlist" -> createPlaylist(p,args)
            "add_to_playlist","remove_from_playlist" -> {
                requireScopes(p,if(name=="remove_from_playlist") "user:delete" else "user:write")
                val id=text(args,"playlistId")
                db.write {
                    owned(id,p)
                    if(name=="remove_from_playlist") checkRevision(id)
                    val current=entries(id)
                    val original=current.toList()
                    if(name=="add_to_playlist") {
                        val ids=itemIds(args,true);validateTracks(ids,p)
                        current.addAll(ids.map { Entry(UUID.randomUUID().toString(),it,System.currentTimeMillis()) })
                    } else {
                        val ids=args["entryIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                        current.removeAll { it.id in ids }
                    }
                    if(original!=current) saveEntries(id,current)
                }
                resource("playlist",id,p)!!
            }
            "set_favorite","set_rating","add_to_my_library","remove_from_my_library" -> {
                val op=when(name) { "set_favorite" -> if(args["favorite"]!!.jsonPrimitive.boolean) "favorite" else "unfavorite"; "set_rating" -> if(args["rating"]==JsonNull) "unrate" else "rate"; "add_to_my_library" -> "libraryAdd"; else -> "libraryRemove" }
                change(p,text(args,"itemId"),op,args["rating"]?.jsonPrimitive?.intOrNull,null)
            }
            "delete_playlist" -> {
                requireScopes(p,"user:delete")
                val id=text(args,"playlistId")
                db.write { if(long("SELECT COUNT(*) FROM playlist WHERE id=?",id)>0) { owned(id,p);checkRevision(id);execute("DELETE FROM playlist WHERE id=?",id) } }
                j("deleted" to true)
            }
            "delete_play" -> { requireScopes(p,"user:delete");deletePlay(p,text(args,"playId"));j("deleted" to true) }
            else -> throw ApiProblem(400,"invalid-request","Unknown MCP tool")
        }
    }

    private class RecordSink(private val writer:BufferedWriter) {
        operator fun plusAssign(record:JsonObject) { writer.write(record.toString()); writer.newLine() }
    }
    private data class Entry(val id:String,val track:String,val added:Long)
    /** Reconcile legacy rewrites by occurrence, retaining identities for surviving duplicate tracks. */
    private fun Db.Session.entries(id:String):MutableList<Entry> {
        val old=query("SELECT entry_id,track_id,added FROM api_playlist_entry WHERE playlist_id=? ORDER BY position",id) { Entry(it.string("entry_id"),it.string("track_id"),it.long("added")) }.toMutableList()
        val now=System.currentTimeMillis()
        val result=query("SELECT track_id FROM playlist_track WHERE playlist_id=? ORDER BY position",id) { it.string("track_id") }.map { track ->
            val index=old.indexOfFirst { it.track==track }
            if(index>=0) old.removeAt(index) else Entry(UUID.randomUUID().toString(),track,now)
        }.toMutableList()
        execute("DELETE FROM api_playlist_entry WHERE playlist_id=?",id)
        result.forEachIndexed { i,e -> execute("INSERT INTO api_playlist_entry VALUES (?,?,?,?,?)",id,i,e.track,e.id,e.added) }
        return result
    }
    private fun Db.Session.saveEntries(id:String,entries:List<Entry>) {
        execute("DELETE FROM playlist_track WHERE playlist_id=?",id)
        execute("DELETE FROM api_playlist_entry WHERE playlist_id=?",id)
        entries.forEachIndexed { i,e ->
            execute("INSERT INTO playlist_track VALUES (?,?,?)",id,i,e.track)
            execute("INSERT INTO api_playlist_entry VALUES (?,?,?,?,?)",id,i,e.track,e.id,e.added)
        }
        execute("UPDATE playlist SET changed=? WHERE id=?",System.currentTimeMillis(),id)
    }
    private suspend fun mutateEntries(call:ApplicationCall,op:String) {
        val p=call.principal(ctx,if(op=="remove") "user:delete" else "user:write"); val id=call.parameters["playlistId"]!!
        val body=if(op=="remove") JsonObject(emptyMap()) else call.body()
        db.write {
            owned(id,p); checkMatch(call,id,p,op=="replace")
            val current=entries(id)
            val original=current.toList()
            val next=when(op) {
                "replace" -> {
                    val ids=itemIds(body,true); validateTracks(ids,p)
                    val remaining=ids.toMutableList(); val dropped=current.any { val i=remaining.indexOf(it.track); if(i<0) true else { remaining.removeAt(i); false } }
                    if(dropped) requireScope(p,"user:delete")
                    val pool=current.toMutableList()
                    ids.map { track -> val index=pool.indexOfFirst { it.track==track }; if(index>=0) pool.removeAt(index) else Entry(UUID.randomUUID().toString(),track,System.currentTimeMillis()) }
                }
                "insert" -> {
                    val ids=itemIds(body,true); if(ids.isEmpty()) throw ApiProblem(400,"invalid-request"); validateTracks(ids,p)
                    val before=body["beforeEntryId"]?.jsonPrimitive?.contentOrNull
                    val index=if(before==null) current.size else current.indexOfFirst { it.id==before }.takeIf { it>=0 } ?: throw ApiProblem(404,"not-found")
                    current.apply { addAll(index,ids.map { Entry(UUID.randomUUID().toString(),it,System.currentTimeMillis()) }) }
                }
                "move" -> {
                    val entry=call.parameters["entryId"]!!; val from=current.indexOfFirst { it.id==entry }; if(from<0) throw ApiProblem(404,"not-found")
                    val before=body["beforeEntryId"]?.jsonPrimitive?.contentOrNull
                    if(before==entry) current else {
                        val moving=current.removeAt(from)
                        val index=if(before==null) current.size else current.indexOfFirst { it.id==before }.takeIf { it>=0 } ?: throw ApiProblem(404,"not-found")
                        current.apply { add(index,moving) }
                    }
                }
                else -> current.apply { removeAll { it.id==call.parameters["entryId"] } }
            }
            if(next!=original) saveEntries(id,next)
        }
        if(op=="remove") call.send(JsonNull,204) else { val result=resource("playlist",id,p)!!; call.send(result,etag=tag(result)) }
    }
    private fun Db.Session.owned(id:String,p:Principal) {
        val owner=string("SELECT owner_id FROM playlist WHERE id=?",id) ?: throw ApiProblem(404,"not-found")
        if(owner!=p.user.id) throw ApiProblem(403,"forbidden")
    }
    private fun checkMatch(call:ApplicationCall,id:String,p:Principal,required:Boolean=false) {
        val given=call.request.headers["If-Match"] ?: if(required) throw ApiProblem(428,"precondition-required") else return
        val current=resource("playlist",id,p) ?: throw ApiProblem(404,"not-found")
        if(given!=tag(current)) throw ApiProblem(412,"revision-mismatch",extra=j("current" to current))
    }
    private fun Db.Session.validateTracks(ids:List<String>,p:Principal) {
        if(ids.any { !ctx.access.canReadCatalog(p.user.id,"track",it) }) throw ApiProblem(404,"not-found")
    }
    /** Called within the playlist mutation transaction, using the account's current policy. */
    private fun Db.Session.authorizePublication(p:Principal,visibility:String) {
        if(visibility=="public" && !ctx.access.share(p.user.id)) throw ApiProblem(403,"permission-denied")
    }
    private fun itemIds(body:JsonObject,required:Boolean):List<String> {
        val raw=body["itemIds"] ?: if(required) throw ApiProblem(400,"invalid-request") else return emptyList()
        val items=raw as? JsonArray ?: throw ApiProblem(400,"invalid-request")
        if(items.size>10000) throw ApiProblem(400,"invalid-request")
        return items.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw ApiProblem(400,"invalid-request") }
    }
    private fun playlistName(body:JsonObject):String=text(body,"name").also { if(it.length !in 1..200) throw ApiProblem(400,"invalid-request") }
    private fun visibility(body:JsonObject):String=(if("visibility" in body) text(body,"visibility") else "private").also { if(it !in setOf("private","public")) throw ApiProblem(400,"invalid-request") }
    private fun requireScope(p:Principal,scope:String) = requireScopes(p,scope)
    private fun itemType(id:String):String? = db.read { listOf("track","album","artist","playlist").firstOrNull { long("SELECT COUNT(*) FROM $it WHERE id=?",id)>0 } }
    private fun legacyType(type:String)=if(type=="track") "song" else type
    private fun apiType(type:String)=if(type=="song") "track" else type
    private fun Db.Session.journal(type:String,id:String,user:String,op:String) { execute("INSERT INTO api_journal(type,id,user_id,op,at) VALUES (?,?,?,?,?)",type,id,user,op,System.currentTimeMillis()) }
    private fun tag(resource:JsonObject)="\"${resource["revision"]!!.jsonPrimitive.content}\""
    private fun text(body:JsonObject,key:String):String=(body[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() } ?: throw ApiProblem(400,"invalid-request")
    private fun array(body:JsonObject,key:String,min:Int,max:Int):JsonArray=(body[key] as? JsonArray)?.takeIf { it.size in min..max } ?: throw ApiProblem(400,"invalid-request")
    private fun parseTime(value:String):Long=try { Instant.parse(value).toEpochMilli() } catch(e:Exception) { throw ApiProblem(400,"invalid-request") }
    private fun stamp(value:Long):String=DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(value))

    private fun page(call:ApplicationCall,p:Principal,kind:String,items:List<JsonObject>,revision:String?=null):JsonObject {
        call.stamp(ctx,p)
        return page(p,kind,items,revision,call.request.queryParameters.entries().associate { it.key to it.value.first() })
    }
    private fun page(p:Principal,kind:String,items:List<JsonObject>,revision:String?=null,params:Map<String,String>):JsonObject {
        val rawLimit=params["limit"]
        val limit=if(rawLimit==null) 100 else rawLimit.toIntOrNull() ?: throw ApiProblem(400,"invalid-request")
        if(limit !in 1..500) throw ApiProblem(400,"invalid-request")
        // Bind a walk to its query and user so tokens cannot be reused to read a different list.
        val query=params.entries.filter { it.key !in setOf("cursor","limit") }.sortedBy { it.key }.joinToString("&") { it.key+"="+it.value }
        val binding="${ctx.journal.instanceId}:${p.user.id}:$kind:$query"
        var after:List<String>?=null
        params["cursor"]?.let { raw ->
            val cursor=decodeCursor(raw)
            if(cursor["binding"]?.jsonPrimitive?.content!=binding) throw ApiProblem(400,"invalid-request")
            if(cursor["revision"]?.jsonPrimitive?.contentOrNull!=revision) throw ApiProblem(409,"conflict")
            after=(cursor["key"] as? JsonArray)?.map { it.jsonPrimitive.content }?.takeIf { it.size==2 } ?: throw ApiProblem(400,"invalid-request")
        }
        fun desc(value:JsonElement?):String = (Long.MAX_VALUE.toBigInteger() - parseTime(value!!.jsonPrimitive.content).toBigInteger()).toString().padStart(20,'0')
        fun key(item:JsonObject):List<String> = when {
            kind=="plays" -> listOf(desc(item["startedAt"]),item["id"]!!.jsonPrimitive.content).let { listOf(it[0],it[1].map { c -> (Char.MAX_VALUE.code-c.code).toChar() }.joinToString("")) }
            kind=="library" || kind=="favorites" -> listOf(desc(item[if(kind=="library") "libraryAddedAt" else "favoritedAt"]),item["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            kind.startsWith("playlist:") -> listOf(item["position"]!!.jsonPrimitive.int.toString().padStart(10,'0'),item["entryId"]!!.jsonPrimitive.content)
            else -> listOf(when(params["sort"] ?: "name") { "-updatedAt" -> desc(item["updatedAt"]); "-createdAt" -> desc(item["createdAt"]); else -> item["name"]!!.jsonPrimitive.content.map { c -> if(c in 'A'..'Z') (c.code+32).toChar() else c }.joinToString("").toByteArray().joinToString("") { "%02x".format(it) } },item["id"]!!.jsonPrimitive.content)
        }
        fun compare(a:List<String>,b:List<String>):Int = a[0].compareTo(b[0]).takeIf { it!=0 } ?: a[1].compareTo(b[1])
        val remaining=items.filter { item -> after?.let { compare(key(item),it)>0 } ?: true }
        val selected=remaining.take(limit)
        return j("items" to JsonArray(selected),"nextCursor" to if(remaining.size>limit) encodeCursor(j("binding" to binding,"key" to key(selected.last()),"revision" to revision)) else null,"total" to items.size)
    }
    private fun mac(value:ByteArray):ByteArray=Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(cursorKey,"HmacSHA256")); doFinal(value) }
    private fun encodeCursor(value:JsonObject):String { val bytes=value.toString().toByteArray(); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(bytes)) }
    private fun decodeCursor(raw:String):JsonObject = try {
        val parts=raw.split('.'); if(parts.size!=2) throw IllegalArgumentException()
        val bytes=Base64.getUrlDecoder().decode(parts[0]); if(!MessageDigest.isEqual(mac(bytes),Base64.getUrlDecoder().decode(parts[1]))) throw IllegalArgumentException()
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch(e:Exception) { throw ApiProblem(400,"invalid-request") }
}
