package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.auth.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList

/** A bounded durable hint log, delivered only after the mutation that produced it commits. */
class ApiEvents(private val ctx: ServerContext, private val clock: () -> Long = System::currentTimeMillis) {
    data class Event(val id: String, val userId: String?, val type: String, val data: JsonObject, val scopes: Set<String>, val deviceId: String? = null, val at:Long = System.currentTimeMillis())
    private data class Connection(val principal: Principal, val device: String?, val types: Set<String>, val channel: Channel<Event>)
    private val connections = CopyOnWriteArrayList<Connection>()
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()
    private var observing = false
    private var active = false
    private var journalHead = ctx.journal.head()
    private var delivered = 0L
    private var instance=ctx.journal.instanceId
    init {
        ctx.db.write {
            execute("CREATE TABLE IF NOT EXISTS api_event(seq INTEGER PRIMARY KEY AUTOINCREMENT,instance TEXT NOT NULL,user_id TEXT,type TEXT NOT NULL,payload TEXT NOT NULL,scopes TEXT NOT NULL,device_id TEXT,at INTEGER NOT NULL)")
            delivered = long("SELECT COALESCE(MAX(seq),0) FROM api_event")
            execute("CREATE TABLE IF NOT EXISTS api_event_scan_seen(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        }
        ctx.db.beforeCommit {
            if(active && !observing) {
                observing=true
                try {
                    if(instance!=ctx.journal.instanceId) {
                        instance=ctx.journal.instanceId
                        journalHead=0L
                        delivered=it.long("SELECT COALESCE(MAX(seq),0) FROM api_event")
                        connections.distinctBy { c->c.principal.user.id }.forEach { c ->
                            val p=c.principal
                            if("changes" in c.types) publish(p.user.id,"changes",j("cursor" to ctx.journal.cursor(p.user.id,syncTypes(p)),"types" to emptyList<String>()),setOf(if("user:read" in p.scopes) "user:read" else "catalog:read"))
                            if("queue" in c.types) {
                                val q=ctx.apiQueue.getQueue(p)
                                publish(p.user.id,"queue",j("revision" to q["revision"],"activeDeviceId" to q["activeDeviceId"],"changed" to listOf("entries","current","mode","activeDevice")),setOf("user:read"))
                            }
                        }
                    }
                    translateJournal()
                } finally { observing=false }
            }
        }
        ctx.db.afterCommit { committed() }
    }
    fun activate() { active = true }
    fun subscribe(listener: (Event) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
    fun onlineAny(userId:String,deviceId:String):Boolean = connections.any { it.principal.user.id==userId && it.device==deviceId && ctx.apiAuth.isActive(it.principal) }
    fun online(userId: String, deviceId: String): Boolean = connections.any {
        it.principal.user.id == userId && it.device == deviceId && "command" in it.types && ctx.apiAuth.isActive(it.principal)
    }
    fun publish(userId: String?, type: String, data: JsonObject, scopes: Set<String>, deviceId: String? = null) {
        ctx.db.write {
            execute("INSERT INTO api_event(instance,user_id,type,payload,scopes,device_id,at) VALUES(?,?,?,?,?,?,?)",ctx.journal.instanceId,userId,type,data.toString(),scopes.sorted().joinToString(" "),deviceId,clock())
        }
    }
    private fun event(row: com.legitimateapps.dulcet.server.db.Db.Row): Event = Event(
        "ev_${row.string("instance")}_${row.long("seq")}",row.stringOrNull("user_id"),row.string("type"),Json.parseToJsonElement(row.string("payload")).jsonObject,row.string("scopes").split(' ').filter(String::isNotEmpty).toSet(),row.stringOrNull("device_id"),row.long("at"))
    private fun committed() {
        if (observing) return
        observing = true
        try {
            if (active) journalHead=ctx.journal.head()
            val pending = ctx.db.read { query("SELECT * FROM api_event WHERE seq>? ORDER BY seq",delivered) { it.long("seq") to event(it) } }
            pending.forEach { (seq,e) ->
                delivered = seq
                listeners.forEach { listener -> runCatching { listener(e) } }
                connections.forEach { connection ->
                    if (visible(connection,e) && !connection.channel.trySend(e).isSuccess) connection.channel.close()
                }
            }
            // Commands become unsafe to carry out after ten seconds. Other hints retain one day,
            // with a hard row cap so an unattended listener cannot grow storage without bound.
            ctx.db.write {
                execute("DELETE FROM api_event WHERE at<? OR seq<(SELECT COALESCE(MAX(seq),0)-10000 FROM api_event)",clock()-86400000)
            }
        } finally { observing = false }
    }
    private fun translateJournal() {
        data class Change(val seq:Long,val type:String,val id:String,val user:String?,val op:String)
        val rows=ctx.db.read { query("SELECT seq,type,id,user_id,op FROM api_journal WHERE seq>? ORDER BY seq",journalHead) { Change(it.long("seq"),it.string("type"),it.string("id"),it.stringOrNull("user_id"),it.string("op")) } }
        rows.groupBy { it.user to if(it.type in catalogTypes) "catalog:read" else "user:read" }.forEach { (group,changes) ->
            val (owner,scope)=group
            val types=changes.map { it.type }.filter { it in catalogTypes || it in userTypes }.distinct()
            if(types.isNotEmpty()) {
                val data=j("cursor" to ctx.journal.cursor(owner ?: "",types.joinToString(",")),"types" to types)
                publish(owner,"changes",data,setOf(scope))
                if(scope=="catalog:read") publish(null,"catalog.changed",data,setOf(scope))
            }
        }
        rows.distinctBy { Triple(it.type,it.id,it.user) }.forEach { row ->
            val resourceOwner=row.user ?: if(row.type=="playlist") ctx.db.read { string("SELECT owner_id FROM playlist WHERE id=?",row.id) } else null
            val user=resourceOwner?.let { id -> ctx.db.read { single("SELECT id,name,is_admin FROM user WHERE id=?",id) { User(it.string("id"),it.string("name"),it.bool("is_admin")) } } }
            val p=user?.let { Principal(it,"",null,API_SCOPES) }
            if(p!=null && row.type in setOf("itemState","play","playlist") && row.op=="upsert") {
                ctx.apiUserData.resource(row.type,row.id,p)?.let { value ->
                    val name=when(row.type) { "play"->"play.recorded";"itemState"->"itemState.changed";else->"playlist.changed" }
                    publish(resourceOwner,name,value,setOf("user:read"))
                }
            }
            if(row.type=="nowPlaying" && row.user!=null) {
                val payload=ctx.db.read {
                    string("SELECT payload FROM api_now_playing WHERE user_id=? AND device_id=? AND expires_at>?",row.user,row.id,clock())?.let { Json.parseToJsonElement(it).jsonObject }
                        ?: single("SELECT track_id FROM now_playing WHERE user_id=? AND client=? AND at>?",row.user,row.id,clock()-120000) { j("userId" to row.user,"deviceId" to row.id,"trackId" to it.string("track_id"),"state" to "playing","positionMs" to 0) }
                }
                val data=if(payload==null) j("userId" to row.user,"deviceId" to row.id,"state" to "ended","trackId" to null) else JsonObject(payload.filterKeys { it in setOf("userId","deviceId","state","trackId","positionMs") })
                val recipients=ctx.db.read { query("SELECT id FROM user WHERE id=? OR is_admin=1 OR EXISTS(SELECT 1 FROM api_profile WHERE user_id=? AND json_extract(settings,'$.shareNowPlaying')=1)",row.user,row.user) { it.string("id") } }
                recipients.forEach { publish(it,"nowPlaying",data,setOf("user:read")) }
                publish(row.user,"nowPlaying.changed",data,setOf("user:read"))
            }
        }
        // Scans are transient and intentionally absent from /sync/changes. Persisted scan payloads
        // still provide an atomic commit observer without introducing a second source of truth.
        val scans=ctx.db.read { query("SELECT s.id,s.payload FROM api_scan s LEFT JOIN api_event_scan_seen seen ON seen.id=s.id WHERE seen.payload IS NULL OR seen.payload!=s.payload") { it.string("id") to Json.parseToJsonElement(it.string("payload")).jsonObject } }
        scans.forEach { (id,value) ->
            ctx.db.write { execute("INSERT INTO api_event_scan_seen VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",id,value.toString()) }
            publish(null,"scan",j("scanId" to id,"state" to value["state"],"counts" to (value["counts"] ?: j())),setOf("admin"))
            if(value["state"]?.jsonPrimitive?.content=="completed") publish(null,"scan.completed",value,setOf("admin"))
        }
    }
    private fun visible(connection:Connection,e:Event):Boolean {
        val p=connection.principal
        if(e.type !in connection.types || !p.scopes.containsAll(e.scopes)) return false
        if(e.userId!=null && e.userId!=p.user.id) return false
        if(e.deviceId!=null && e.deviceId!=connection.device) return false
        if(e.type=="command" && clock()-e.at>=10000) return false
        if(e.type=="scan" && !p.user.isAdmin) return false
        if(e.type=="nowPlaying" && !canReadNowPlaying(p,e.data)) return false
        if(e.type=="changes" && "catalog:read" in e.scopes && ctx.apiUsers.sourceIds(p.user.id).isEmpty()) return false
        return true
    }
    internal fun canReadNowPlaying(p:Principal,data:JsonObject):Boolean {
        val owner=(data["userId"] as? JsonPrimitive)?.contentOrNull ?: return false
        if(owner==p.user.id)return true
        return ctx.db.read {
            long("SELECT COUNT(*) FROM user WHERE id=? AND is_admin=1",p.user.id)>0 ||
                long("SELECT COUNT(*) FROM api_profile WHERE user_id=? AND json_extract(settings,'$.shareNowPlaying')=1",owner)>0
        }
    }
    private fun allowed(p:Principal):Set<String> = buildSet {
        addAll(listOf("revoked","heartbeat"))
        if("catalog:read" in p.scopes || "user:read" in p.scopes) add("changes")
        if("user:read" in p.scopes) addAll(listOf("queue","playback","command","nowPlaying"))
        if("admin" in p.scopes && p.user.isAdmin) add("scan")
    }
    private fun data(p:Principal,e:Event):JsonObject = if(e.type=="changes") {
        val types=e.data["types"]?.jsonArray?.filter { it.jsonPrimitive.content in syncTypes(p).split(',') } ?: JsonArray(emptyList())
        j("cursor" to ctx.journal.cursor(p.user.id,syncTypes(p)),"types" to types)
    } else e.data
    fun install(route:Route) {
        activate()
        route.get("/events") {
            val p=call.principal(ctx)
            if(p.scopes.none { it in setOf("catalog:read","user:read") }) throw ApiProblem(403,"insufficient-scope",extra=j("requiredScopes" to listOf("catalog:read","user:read")))
            val permitted=allowed(p)
            val requested=call.request.queryParameters["types"]?.split(',')?.filter(String::isNotEmpty)?.toSet() ?: permitted
            if(requested.any { it !in allTypes }) throw ApiProblem(400,"invalid-request","Unknown event type")
            if(!permitted.containsAll(requested)) throw ApiProblem(403,"insufficient-scope")
            val device=call.request.headers["Dulcet-Device-Id"]
            if(device!=null && ctx.db.read { long("SELECT COUNT(*) FROM api_device WHERE user_id=? AND id=?",p.user.id,device) }==0L) throw ApiProblem(404,"not-found","Register the device before opening its stream")
            val channel=Channel<Event>(256)
            val connection=Connection(p,device,requested,channel)
            val resume=call.request.headers["Last-Event-ID"]
            var gap=false
            ctx.db.exclusive {
                if(resume!=null) {
                    val prefix="ev_${ctx.journal.instanceId}_"
                    val seq=resume.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull()
                    val exists=seq!=null && ctx.db.read { long("SELECT COUNT(*) FROM api_event WHERE instance=? AND seq=?",ctx.journal.instanceId,seq) }>0
                    if(!exists) gap=true else {
                        ctx.db.read { query("SELECT * FROM api_event WHERE instance=? AND seq>? AND (type!='command' OR at>?) ORDER BY seq",ctx.journal.instanceId,seq,clock()-10000, map=::event) }.filter { visible(connection,it) }.forEach { if(!channel.trySend(it).isSuccess) gap=true }
                    }
                }
                connections.add(connection)
            }
            call.response.headers.append(HttpHeaders.CacheControl,"no-store")
            call.response.headers.append("X-Accel-Buffering","no")
            try {
                call.respondTextWriter(ContentType.Text.EventStream) {
                    suspend fun emit(e:Event) {
                        if(e.id.startsWith("ev_${ctx.journal.instanceId}_")) write("id: ${e.id}\n")
                        write("event: ${e.type}\ndata: ${data(p,e)}\n\n");flush()
                    }
                    if(gap) {
                        if("changes" in requested) emit(Event("ev_gap","", "changes",j(),emptySet()))
                        if("queue" in requested) {
                            val q=ctx.apiQueue.getQueue(p)
                            emit(Event("ev_gap",p.user.id,"queue",j("revision" to q["revision"],"activeDeviceId" to q["activeDeviceId"],"changed" to listOf("entries","current","mode","activeDevice")),emptySet()))
                        }
                    }
                    // Initial heartbeat also flushes the headers, making this device reachable now.
                    emit(Event("ev_heartbeat",p.user.id,"heartbeat",j(),emptySet()))
                    var heartbeatAt=clock()
                    while(true) {
                        ctx.db.read {
                            if(long("SELECT COUNT(*) FROM api_now_playing WHERE expires_at<=?",clock())>0) {
                                execute("DELETE FROM now_playing WHERE EXISTS(SELECT 1 FROM api_now_playing n WHERE n.user_id=now_playing.user_id AND n.device_id=now_playing.client AND n.expires_at<=?)",clock())
                                execute("DELETE FROM api_now_playing WHERE expires_at<=?",clock())
                            }
                            if(long("SELECT COUNT(*) FROM now_playing WHERE at<=?",clock()-120000)>0) execute("DELETE FROM now_playing WHERE at<=?",clock()-120000)
                        }
                        if(!ctx.apiAuth.isActive(p)) {
                            emit(Event("ev_revoked",p.user.id,"revoked",j("reason" to "Token revoked or expired"),emptySet()));break
                        }
                        val received=withTimeoutOrNull(1000) { channel.receiveCatching() }
                        if(received!=null) { if(received.isClosed) break; val e=received.getOrThrow(); if(ctx.apiAuth.isActive(p) && visible(connection,e)) emit(e) }
                        if(clock()-heartbeatAt>=15000) { emit(Event("ev_heartbeat",p.user.id,"heartbeat",j(),emptySet()));heartbeatAt=clock() }
                    }
                }
            } finally { connections.remove(connection);channel.close() }
        }
    }
    companion object {
        private val catalogTypes=setOf("artist","album","track","genre","source")
        private val userTypes=setOf("itemState","playlist","play","queue")
        private val allTypes=setOf("changes","queue","playback","command","nowPlaying","scan","revoked","heartbeat")
    }
}
