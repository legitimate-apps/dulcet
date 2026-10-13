package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.db.Db
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/** The queue is shared user state; playback reports deliberately live in a separate table. */
class ApiQueue(private val ctx:ServerContext, private val clock:()->Long=System::currentTimeMillis) {
    private val db=ctx.db
    private val cursors=CursorCodec(ctx)
    private data class Entry(val id:String,val item:String,val addedBy:String?)
    init { db.write {
        execute("CREATE TABLE IF NOT EXISTS api_device(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,id TEXT NOT NULL,name TEXT NOT NULL,can_play INTEGER NOT NULL,kind TEXT,client TEXT,last_seen INTEGER NOT NULL,PRIMARY KEY(user_id,id))")
        execute("CREATE TABLE IF NOT EXISTS api_queue(user_id TEXT PRIMARY KEY REFERENCES user(id) ON DELETE CASCADE,current_entry TEXT,shuffle INTEGER NOT NULL DEFAULT 0,repeat_mode TEXT NOT NULL DEFAULT 'off',context TEXT,active_device TEXT,updated INTEGER NOT NULL,updated_by TEXT)")
        execute("CREATE TABLE IF NOT EXISTS api_queue_entry(user_id TEXT NOT NULL REFERENCES api_queue(user_id) ON DELETE CASCADE,id TEXT PRIMARY KEY,item_id TEXT NOT NULL,position INTEGER NOT NULL,added_by TEXT)")
        execute("CREATE INDEX IF NOT EXISTS api_queue_order ON api_queue_entry(user_id,position)")
        execute("CREATE TABLE IF NOT EXISTS api_queue_playback(user_id TEXT PRIMARY KEY REFERENCES api_queue(user_id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        execute("CREATE TABLE IF NOT EXISTS api_queue_command(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,id TEXT NOT NULL,fingerprint TEXT NOT NULL,device_id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(user_id,id))")
        for((action,ref,op) in listOf(Triple("INSERT","NEW","upsert"),Triple("UPDATE","NEW","upsert"),Triple("DELETE","OLD","delete"))) {
            execute("CREATE TRIGGER IF NOT EXISTS api_queue_${action.lowercase()} AFTER $action ON api_queue BEGIN INSERT INTO api_journal(type,id,user_id,op,at) VALUES('queue','queue',$ref.user_id,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
            execute("CREATE TRIGGER IF NOT EXISTS api_device_${action.lowercase()} AFTER $action ON api_device BEGIN INSERT INTO api_journal(type,id,user_id,op,at) VALUES('device',$ref.id,$ref.user_id,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
            execute("CREATE TRIGGER IF NOT EXISTS api_queue_entry_${action.lowercase()} AFTER $action ON api_queue_entry BEGIN UPDATE api_queue SET updated=CAST((julianday('now')-2440587.5)*86400000 AS INTEGER) WHERE user_id=$ref.user_id; END")
            // Playback is volatile and never bumps the queue revision or joins its change feed.
            // It still records its own journal row in the mutation transaction (§30.4).
            execute("CREATE TRIGGER IF NOT EXISTS api_queue_playback_${action.lowercase()} AFTER $action ON api_queue_playback BEGIN INSERT INTO api_journal(type,id,user_id,op,at) VALUES('playback','queue',$ref.user_id,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
        }
        execute("CREATE TRIGGER IF NOT EXISTS api_queue_new_user AFTER INSERT ON user BEGIN INSERT INTO api_queue(user_id,updated) VALUES(NEW.id,CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
        execute("INSERT OR IGNORE INTO api_queue(user_id,updated) SELECT id,? FROM user",clock())
    } }
    private fun text(body:JsonObject,key:String):String=(body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: throw ApiProblem(400,"invalid-request")
    private fun bool(body:JsonObject,key:String,default:Boolean):Boolean=if(key !in body) default else (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw ApiProblem(400,"invalid-request")
    private fun number(body:JsonObject,key:String,default:Long=0):Long=if(key !in body) default else (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it>=0 } ?: throw ApiProblem(400,"invalid-request")
    private fun ids(body:JsonObject,empty:Boolean):List<String> {
        val array=body["itemIds"] as? JsonArray ?: throw ApiProblem(400,"invalid-request")
        if(array.size>10000 || (!empty && array.isEmpty())) throw ApiProblem(400,"invalid-request")
        return array.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: throw ApiProblem(400,"invalid-request") }
    }
    private fun validate(p:Principal,items:List<String>) { if(items.any { ctx.apiCatalog.resource("track",it,p)==null }) throw ApiProblem(422,"unknown-item") }
    private fun Db.Session.order(user:String)=query("SELECT id,item_id,added_by FROM api_queue_entry WHERE user_id=? ORDER BY position,id",user) { Entry(it.string("id"),it.string("item_id"),it.stringOrNull("added_by")) }.toMutableList()
    private fun Db.Session.save(user:String,entries:List<Entry>) {
        execute("DELETE FROM api_queue_entry WHERE user_id=?",user)
        batch("INSERT INTO api_queue_entry(user_id,id,item_id,position,added_by) VALUES(?,?,?,?,?)",entries.mapIndexed { i,e -> listOf(user,e.id,e.item,i,e.addedBy) })
    }
    private fun queue(p:Principal):JsonObject=db.read {
        single("SELECT * FROM api_queue WHERE user_id=?",p.user.id) { row ->
            j("revision" to ctx.journal.revision("queue","queue",p.user.id),"entryCount" to long("SELECT COUNT(*) FROM api_queue_entry WHERE user_id=?",p.user.id),"currentEntryId" to row.stringOrNull("current_entry"),"shuffle" to row.bool("shuffle"),"repeat" to row.string("repeat_mode"),"context" to row.stringOrNull("context")?.let(Json::parseToJsonElement),"activeDeviceId" to row.stringOrNull("active_device"),"updatedAt" to iso(row.long("updated")),"updatedBy" to row.stringOrNull("updated_by")).let { q -> JsonObject(q.filter { (name,value) -> name!="updatedBy" || value!=JsonNull }) }
        } ?: throw ApiProblem(401,"unauthenticated")
    }
    fun getQueue(p:Principal):JsonObject { requireScopes(p,"user:read");return queue(p) }
    fun resource(p:Principal):JsonObject=queue(p)
    private fun match(p:Principal,tag:String?,required:Boolean=false) {
        if(tag==null) { if(required) throw ApiProblem(428,"precondition-required");return }
        val current=queue(p)
        if(tag!="\"${current["revision"]!!.jsonPrimitive.content}\"") throw ApiProblem(412,"revision-mismatch",extra=j("current" to current))
    }
    private fun Db.Session.changed(p:Principal,device:String?,fields:List<String>):JsonObject {
        execute("UPDATE api_queue SET updated=?,updated_by=? WHERE user_id=?",clock(),device,p.user.id)
        val q=queue(p)
        ctx.apiEvents.publish(p.user.id,"queue",j("revision" to q["revision"],"activeDeviceId" to q["activeDeviceId"],"changed" to fields.map { when(it) { "currentEntry"->"current";"shuffle","repeat"->"mode";else->it } }.distinct()),setOf("user:read"))
        return q
    }
    fun seen(userId:String,deviceId:String) { db.write { execute("UPDATE api_device SET last_seen=? WHERE user_id=? AND id=?",clock(),userId,deviceId) } }
    private fun device(user:String,id:String):JsonObject?=db.read { single("SELECT * FROM api_device WHERE user_id=? AND id=?",user,id) {
        j("id" to it.string("id"),"name" to it.string("name"),"canPlay" to it.bool("can_play"),"kind" to it.stringOrNull("kind"),"client" to it.stringOrNull("client"),"lastSeenAt" to iso(it.long("last_seen")),"online" to (it.long("last_seen")>clock()-120000 || ctx.apiEvents.onlineAny(user,id)),"active" to (string("SELECT active_device FROM api_queue WHERE user_id=?",user)==id)).let { d -> JsonObject(d.filter { (name,value) -> name !in setOf("kind","client") || value!=JsonNull }) }
    } }
    private fun available(user:String,id:String) { val d=device(user,id);if(d==null || d["canPlay"]!=JsonPrimitive(true) || d["online"]!=JsonPrimitive(true)) throw ApiProblem(409,"device-unavailable") }
    fun register(p:Principal,id:String,body:JsonObject):JsonObject {
        requireScopes(p,"user:write");if(id.isBlank() || id.length>200) throw ApiProblem(400,"invalid-request")
        val name=text(body,"name");if(name.length>100) throw ApiProblem(400,"invalid-request")
        for(key in listOf("kind","client")) if(key in body)text(body,key)
        val canPlay=bool(body,"canPlay",false);if("canPlay" !in body) throw ApiProblem(400,"invalid-request")
        return db.write {
            execute("INSERT INTO api_device VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET name=excluded.name,can_play=excluded.can_play,kind=excluded.kind,client=excluded.client,last_seen=excluded.last_seen",p.user.id,id,name,canPlay,body["kind"]?.jsonPrimitive?.content,body["client"]?.jsonPrimitive?.content,clock())
            if(!canPlay && string("SELECT active_device FROM api_queue WHERE user_id=?",p.user.id)==id) { execute("UPDATE api_queue SET active_device=NULL WHERE user_id=?",p.user.id);changed(p,id,listOf("activeDevice")) }
            device(p.user.id,id)!!
        }
    }
    fun forget(p:Principal,id:String) { requireScopes(p,"user:write");db.write {
        execute("DELETE FROM api_device WHERE user_id=? AND id=?",p.user.id,id)
        if(string("SELECT active_device FROM api_queue WHERE user_id=?",p.user.id)==id) { execute("UPDATE api_queue SET active_device=NULL WHERE user_id=?",p.user.id);changed(p,null,listOf("activeDevice")) }
    } }
    fun entries(p:Principal,limit:Int=100,cursor:String?=null):JsonObject {
        requireScopes(p,"user:read");if(limit !in 1..500) throw ApiProblem(400,"invalid-request")
        return db.read {
            val revision=queue(p)["revision"]!!.jsonPrimitive.content
            val offset=cursor?.let { raw -> val c=cursors.decode(raw)
                if(c["user"]!=JsonPrimitive(p.user.id) || c["kind"]!=JsonPrimitive("queue")) throw ApiProblem(400,"invalid-request")
                if(c["revision"]!=JsonPrimitive(revision)) throw ApiProblem(409,"conflict")
                c["offset"]!!.jsonPrimitive.int.also { if(it<0) throw ApiProblem(400,"invalid-request") }
            } ?: 0
            val all=order(p.user.id);val selected=all.drop(offset).take(limit)
            val items=selected.mapIndexed { i,e -> JsonObject(j("entryId" to e.id,"position" to offset+i,"itemId" to e.item)+e.addedBy?.let { j("addedBy" to it) }.orEmpty()+if("catalog:read" in p.scopes) ctx.apiCatalog.resource("track",e.item,p)?.let { j("track" to it) }.orEmpty() else j()) }
            j("items" to items,"revision" to revision,"nextCursor" to if(offset+selected.size<all.size) cursors.encode(j("user" to p.user.id,"kind" to "queue","revision" to revision,"offset" to offset+selected.size)) else null)
        }
    }
    fun replace(p:Principal,body:JsonObject,deviceId:String?,ifMatch:String?):JsonObject {
        requireScopes(p,"user:write")
        return db.write {
            match(p,ifMatch,true);val items=ids(body,true);validate(p,items)
            val index=number(body,"currentIndex").also { if((items.isEmpty() && it!=0L) || (items.isNotEmpty() && it>=items.size)) throw ApiProblem(400,"invalid-request") }.toInt()
            val position=number(body,"positionMs");val play=bool(body,"play",false);val shuffle=bool(body,"shuffle",false)
            val repeat=if("repeat" in body)text(body,"repeat") else "off";if(repeat !in setOf("off","one","all")) throw ApiProblem(400,"invalid-request")
            val context=body["context"]?.also { if(it!=JsonNull && it !is JsonObject) throw ApiProblem(400,"invalid-request") }
            if(play) available(p.user.id,deviceId ?: throw ApiProblem(400,"invalid-request"))
            val order=items.map { Entry(UUID.randomUUID().toString(),it,deviceId) }.toMutableList();val current=order.getOrNull(index)?.id
            if(shuffle && order.size>index+1) { val tail=order.subList(index+1,order.size);tail.shuffle() }
            save(p.user.id,order)
            execute("UPDATE api_queue SET current_entry=?,shuffle=?,repeat_mode=?,context=?,active_device=CASE WHEN ? THEN ? ELSE active_device END WHERE user_id=?",current,shuffle,repeat,context?.takeUnless { it==JsonNull }?.toString(),play,deviceId,p.user.id)
            val active=string("SELECT active_device FROM api_queue WHERE user_id=?",p.user.id) ?: ""
            storePlayback(p,j("deviceId" to active,"entryId" to current,"state" to if(play) "playing" else "paused","positionMs" to position,"positionAt" to iso(clock())))
            changed(p,deviceId,listOf("entries","currentEntry","shuffle","repeat")+if(play)listOf("activeDevice") else emptyList())
        }
    }
    fun update(p:Principal,body:JsonObject,deviceId:String?,ifMatch:String?):JsonObject {
        requireScopes(p,"user:write");return db.write {
            match(p,ifMatch,true);val old=queue(p);val fields=mutableListOf<String>()
            if("currentEntryId" in body) {
                val entry=text(body,"currentEntryId");if(long("SELECT COUNT(*) FROM api_queue_entry WHERE user_id=? AND id=?",p.user.id,entry)==0L) throw ApiProblem(404,"not-found")
                if(old["currentEntryId"]!=JsonPrimitive(entry)) { execute("UPDATE api_queue SET current_entry=? WHERE user_id=?",entry,p.user.id);fields+="currentEntry" }
            }
            if("shuffle" in body) {
                val on=bool(body,"shuffle",false)
                if(old["shuffle"]!=JsonPrimitive(on)) {
                    if(on) { val order=order(p.user.id);val current=string("SELECT current_entry FROM api_queue WHERE user_id=?",p.user.id);val at=order.indexOfFirst { it.id==current };if(at+1<order.size)order.subList(at+1,order.size).shuffle();save(p.user.id,order) }
                    execute("UPDATE api_queue SET shuffle=? WHERE user_id=?",on,p.user.id);fields+="shuffle"
                }
            }
            if("repeat" in body) { val repeat=text(body,"repeat");if(repeat !in setOf("off","one","all")) throw ApiProblem(400,"invalid-request");if(old["repeat"]!=JsonPrimitive(repeat)) { execute("UPDATE api_queue SET repeat_mode=? WHERE user_id=?",repeat,p.user.id);fields+="repeat" } }
            if(fields.isEmpty()) old else {
                if("currentEntry" in fields) { val pb=currentPlayback(p);storePlayback(p,JsonObject(pb+j("entryId" to queue(p)["currentEntryId"],"positionMs" to 0,"positionAt" to iso(clock())))) }
                changed(p,deviceId,fields)
            }
        }
    }
    fun insert(p:Principal,body:JsonObject,deviceId:String?=null,ifMatch:String?=null):JsonObject {
        requireScopes(p,"user:write");return db.write {
            match(p,ifMatch);val items=ids(body,false);validate(p,items);val order=order(p.user.id)
            if(order.size+items.size>10000) throw ApiProblem(422,"queue-too-large")
            val at=if("at" in body)text(body,"at") else "end"
            val pos=when(at) { "end"->order.size;"next"->(order.indexOfFirst { it.id==queue(p)["currentEntryId"]?.jsonPrimitive?.contentOrNull }+1).coerceAtLeast(0);"before"->order.indexOfFirst { it.id==text(body,"beforeEntryId") }.also { if(it<0) throw ApiProblem(422,"unknown-item") };else->throw ApiProblem(400,"invalid-request") }
            val entries=items.map { Entry(UUID.randomUUID().toString(),it,deviceId) };order.addAll(pos,entries);save(p.user.id,order)
            if(queue(p)["currentEntryId"]==JsonNull) execute("UPDATE api_queue SET current_entry=? WHERE user_id=?",order.first().id,p.user.id)
            j("entryIds" to entries.map { it.id },"queue" to changed(p,deviceId,listOf("entries")))
        }
    }
    fun move(p:Principal,id:String,body:JsonObject,deviceId:String?,ifMatch:String?):JsonObject {
        requireScopes(p,"user:write");return db.write {
            match(p,ifMatch);val order=order(p.user.id);val entry=order.find { it.id==id } ?: throw ApiProblem(404,"not-found")
            val before=body["beforeEntryId"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content
            if(before==id)return@write queue(p)
            order.remove(entry);val at=if(before==null)order.size else order.indexOfFirst { it.id==before }.also { if(it<0)throw ApiProblem(404,"not-found") }
            order.add(at,entry);save(p.user.id,order);changed(p,deviceId,listOf("entries"))
        }
    }
    fun remove(p:Principal,id:String,deviceId:String?,ifMatch:String?) { requireScopes(p,"user:write");db.write {
        match(p,ifMatch);val order=order(p.user.id);val at=order.indexOfFirst { it.id==id };if(at<0)return@write
        order.removeAt(at);save(p.user.id,order)
        if(queue(p)["currentEntryId"]==JsonPrimitive(id)) {
            val current=order.getOrNull(at)?.id ?: order.lastOrNull()?.id
            execute("UPDATE api_queue SET current_entry=? WHERE user_id=?",current,p.user.id)
            val pb=currentPlayback(p);storePlayback(p,JsonObject(pb+j("entryId" to current,"positionMs" to 0,"positionAt" to iso(clock()),"state" to if(current==null)"stopped" else pb["state"])))
        }
        changed(p,deviceId,listOf("entries","currentEntry"))
    } }
    fun playback(p:Principal):JsonObject { requireScopes(p,"user:read");return currentPlayback(p) }
    private fun currentPlayback(p:Principal):JsonObject { return db.read { string("SELECT payload FROM api_queue_playback WHERE user_id=?",p.user.id)?.let { Json.parseToJsonElement(it).jsonObject } ?: j("deviceId" to (queue(p)["activeDeviceId"]?.jsonPrimitive?.contentOrNull ?: ""),"entryId" to queue(p)["currentEntryId"],"state" to "stopped","positionMs" to 0,"positionAt" to iso(clock())) } }
    private fun storePlayback(p:Principal,payload:JsonObject) { db.write { execute("INSERT INTO api_queue_playback VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload",p.user.id,payload.toString());ctx.apiEvents.publish(p.user.id,"playback",payload,setOf("user:read")) } }
    fun report(p:Principal,body:JsonObject,deviceId:String?):JsonObject {
        requireScopes(p,"user:write");return db.write {
            val device=text(body,"deviceId");val q=queue(p)
            if((deviceId!=null && deviceId!=device) || q["activeDeviceId"]!=JsonPrimitive(device)) throw ApiProblem(409,"not-active-device",extra=j("current" to q))
            val state=text(body,"state");if(state !in setOf("playing","paused","buffering","stopped")) throw ApiProblem(400,"invalid-request")
            val position=number(body,"positionMs");if("positionMs" !in body)throw ApiProblem(400,"invalid-request")
            val at=text(body,"positionAt");try { Instant.parse(at) } catch(_:Exception) { throw ApiProblem(400,"invalid-request") }
            val entry=body["entryId"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content ?: q["currentEntryId"]?.jsonPrimitive?.contentOrNull
            if(entry!=q["currentEntryId"]?.jsonPrimitive?.contentOrNull) throw ApiProblem(409,"conflict",extra=j("current" to q))
            val volume=body["volume"]?.jsonPrimitive?.takeUnless { it.isString }?.doubleOrNull
            if("volume" in body && (volume==null || !volume.isFinite() || volume !in 0.0..1.0))throw ApiProblem(400,"invalid-request")
            val result=JsonObject(j("deviceId" to device,"entryId" to entry,"state" to state,"positionMs" to position,"positionAt" to at)+volume?.let { j("volume" to it) }.orEmpty())
            seen(p.user.id,device);storePlayback(p,result);result
        }
    }
    fun transfer(p:Principal,body:JsonObject,deviceId:String?,ifMatch:String?):JsonObject {
        requireScopes(p,"user:write");return db.write {
            match(p,ifMatch);val target=text(body,"deviceId");available(p.user.id,target)
            val play=bool(body,"play",true);val pb=currentPlayback(p);val now=clock();val delta=if(pb["state"]==JsonPrimitive("playing")) maxOf(0,now-Instant.parse(pb["positionAt"]!!.jsonPrimitive.content).toEpochMilli()) else 0
            val position=pb["positionMs"]!!.jsonPrimitive.long.let { if(it>Long.MAX_VALUE-delta)Long.MAX_VALUE else it+delta }
            execute("UPDATE api_queue SET active_device=? WHERE user_id=?",target,p.user.id)
            storePlayback(p,JsonObject(pb+j("deviceId" to target,"entryId" to queue(p)["currentEntryId"],"state" to if(play)"playing" else "paused","positionMs" to position,"positionAt" to iso(now))))
            changed(p,deviceId,listOf("activeDevice"))
        }
    }
    fun command(p:Principal,body:JsonObject,deviceId:String?=null):JsonObject {
        requireScopes(p,"user:write");return db.write {
            val id=text(body,"id");try { if(UUID.fromString(id).toString()!=id.lowercase())throw IllegalArgumentException() } catch(_:Exception) {throw ApiProblem(400,"invalid-request") }
            val name=text(body,"command")
            if("positionMs" in body)number(body,"positionMs")
            if("entryId" in body)text(body,"entryId")
            if("volume" in body) {
                val volume=(body["volume"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                if(volume==null || !volume.isFinite() || volume !in 0.0..1.0)throw ApiProblem(400,"invalid-request")
            }
            val fingerprint=hash(JsonObject(body.filterKeys { it in setOf("id","command","positionMs","entryId","volume") }.toSortedMap()).toString())
            val existing=single("SELECT fingerprint,device_id FROM api_queue_command WHERE user_id=? AND id=?",p.user.id,id) { it.string("fingerprint") to it.string("device_id") }
            if(existing!=null) { if(existing.first!=fingerprint)throw ApiProblem(422,"idempotency-key-reused");return@write j("id" to id,"deviceId" to existing.second) }
            val active=queue(p)["activeDeviceId"]?.jsonPrimitive?.contentOrNull ?: throw ApiProblem(409,"device-unavailable")
            if(!ctx.apiEvents.online(p.user.id,active))throw ApiProblem(409,"device-unavailable")
            when(name) {
                "seek"->{ number(body,"positionMs");if("positionMs" !in body)throw ApiProblem(400,"invalid-request") }
                "skipTo"->{ if(long("SELECT COUNT(*) FROM api_queue_entry WHERE user_id=? AND id=?",p.user.id,text(body,"entryId"))==0L)throw ApiProblem(422,"unknown-item") }
                "setVolume"->{val v=(body["volume"] as? JsonPrimitive)?.takeUnless {it.isString}?.doubleOrNull;if(v==null || !v.isFinite() || v !in 0.0..1.0)throw ApiProblem(400,"invalid-request") }
            }
            execute("INSERT INTO api_queue_command VALUES(?,?,?,?,?)",p.user.id,id,fingerprint,active,clock())
            ctx.apiEvents.publish(p.user.id,"command",JsonObject(body.filterKeys { it in setOf("id","command","positionMs","entryId","volume") }+j("from" to (deviceId ?: "controller"))),setOf("user:read"),active)
            j("id" to id,"deviceId" to active)
        }
    }
    fun install(route:Route)=with(route) {
        get("/me/devices") { val p=call.principal(ctx,"user:read");val devices=db.read { query("SELECT id FROM api_device WHERE user_id=? AND last_seen>? ORDER BY last_seen DESC,id",p.user.id,clock()-30L*86400000) { it.string("id") } }.mapNotNull { device(p.user.id,it) };call.send(j("items" to devices)) }
        put("/me/devices/{deviceId}") { call.send(register(call.principal(ctx,"user:write"),call.parameters["deviceId"]!!,call.body())) }
        delete("/me/devices/{deviceId}") { forget(call.principal(ctx,"user:write"),call.parameters["deviceId"]!!);call.send(JsonNull,204) }
        get("/me/queue") { val p=call.principal(ctx,"user:read");val q=getQueue(p);call.stamp(ctx,p);call.send(q,etag=q["revision"]!!.jsonPrimitive.content) }
        put("/me/queue") { val q=replace(call.principal(ctx,"user:write"),call.body(),call.request.headers["Dulcet-Device-Id"],call.request.headers["If-Match"]);call.send(q,etag=q["revision"]!!.jsonPrimitive.content) }
        patch("/me/queue") { val q=update(call.principal(ctx,"user:write"),call.body(),call.request.headers["Dulcet-Device-Id"],call.request.headers["If-Match"]);call.send(q,etag=q["revision"]!!.jsonPrimitive.content) }
        get("/me/queue/entries") { val p=call.principal(ctx,"user:read");val limit=call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request") } ?: 100;val page=entries(p,limit,call.request.queryParameters["cursor"]);call.send(page,etag=page["revision"]!!.jsonPrimitive.content) }
        post("/me/queue/entries") { val result=insert(call.principal(ctx,"user:write"),call.body(),call.request.headers["Dulcet-Device-Id"],call.request.headers["If-Match"]);call.send(result,etag=result["queue"]!!.jsonObject["revision"]!!.jsonPrimitive.content) }
        patch("/me/queue/entries/{entryId}") { val q=move(call.principal(ctx,"user:write"),call.parameters["entryId"]!!,call.body(),call.request.headers["Dulcet-Device-Id"],call.request.headers["If-Match"]);call.send(q,etag=q["revision"]!!.jsonPrimitive.content) }
        delete("/me/queue/entries/{entryId}") { remove(call.principal(ctx,"user:write"),call.parameters["entryId"]!!,call.request.headers["Dulcet-Device-Id"],call.request.headers["If-Match"]);call.send(JsonNull,204) }
        get("/me/queue/playback") { call.response.headers.append("Cache-Control","no-store");call.send(playback(call.principal(ctx,"user:read")),cacheable=false) }
        put("/me/queue/playback") { call.send(report(call.principal(ctx,"user:write"),call.body(),call.request.headers["Dulcet-Device-Id"])) }
        post("/me/queue/transfer") { val q=transfer(call.principal(ctx,"user:write"),call.body(),call.request.headers["Dulcet-Device-Id"],call.request.headers["If-Match"]);call.send(q,etag=q["revision"]!!.jsonPrimitive.content) }
        post("/me/queue/commands") { call.send(command(call.principal(ctx,"user:write"),call.body(),call.request.headers["Dulcet-Device-Id"]),202) }
    }
}
