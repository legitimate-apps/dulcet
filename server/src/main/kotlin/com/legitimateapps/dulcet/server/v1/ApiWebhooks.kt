package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.auth.PasswordVault
import com.legitimateapps.dulcet.server.db.Db
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class WebhookResult(val status: Int?, val error: String? = null)

/** Injectable only by server code/tests. Target validation is also applied at registration. */
interface WebhookTransport {
    fun validate(url: String)
    fun send(url: String, body: ByteArray, headers: Map<String,String>, permitted: () -> Boolean): WebhookResult
    fun cancel(url: String) {}
}

/** DNS is checked on each attempt and the checked address is used directly by the TLS socket. */
class WebhookHttpTransport(
    private val allowPrivate: Boolean = false,
    private val allowPorts: Boolean = false,
    private val nanoClock: () -> Long = System::nanoTime,
    private val testingOverride: () -> Boolean = { false },
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : WebhookTransport {
    private val sockets = ConcurrentHashMap<Socket,String>()
    private fun target(url: String): Pair<URI,InetAddress> {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw ApiProblem(422,"webhook-target-refused")
        if (url.length > 2048 || !permittedUri(uri)) throw ApiProblem(422,"webhook-target-refused")
        val addresses = try { CompletableFuture.supplyAsync { resolver(uri.host) }.get(5,TimeUnit.SECONDS) } catch (_: Exception) { throw ApiProblem(422,"webhook-target-refused") }
        if (addresses.isEmpty() || addresses.any { !allowed(it) }) throw ApiProblem(422,"webhook-target-refused")
        return uri to addresses.first()
    }
    private fun permittedUri(uri:URI):Boolean = uri.host!=null && uri.userInfo==null && uri.fragment==null && uri.port!=0 && uri.port<=65535 &&
        (uri.scheme=="https" || uri.scheme=="http" && testingOverride()) &&
        (uri.port==-1 || uri.port==443 || allowPorts || testingOverride())
    internal fun allowed(a: InetAddress): Boolean {
        val b = a.address.map { it.toInt() and 255 }
        if (a.isAnyLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress) return false
        // IPv4-mapped/compatible addresses are checked as IPv4, never as public IPv6.
        if (b.size == 16 && b.take(10).all { it == 0 } && ((b[10] == 255 && b[11] == 255) || (b[10] == 0 && b[11] == 0))) return allowed(InetAddress.getByAddress(a.address.takeLast(4).toByteArray()))
        if (b.size == 4 && (b[0] == 169 && b[1] == 254 || b == listOf(100,100,100,200))) return false
        if (b.size == 16 && b.take(4) == listOf(253,0,14,194)) return false // AWS metadata's IPv6 range.
        if (allowPrivate || testingOverride()) return true
        if (a.isLoopbackAddress || a.isSiteLocalAddress) return false
        return if (b.size == 4) b[0] != 0 && b[0] != 127 && b[0] < 224 && !(b[0] == 100 && b[1] in 64..127)
        else b[0] and 0xfe != 0xfc
    }
    override fun validate(url: String) { target(url) }
    override fun cancel(url: String) { sockets.filterValues { it == url }.keys.forEach { runCatching { it.close() } } }
    override fun send(url: String, body: ByteArray, headers: Map<String,String>, permitted: () -> Boolean): WebhookResult = try {
        val deadline=nanoClock()+10_000_000_000L
        val (uri,address) = target(url)
        val remaining=((deadline-nanoClock())/1_000_000).coerceAtMost(10_000).toInt()
        if(remaining <= 0) return WebhookResult(null,"delivery-timeout")
        if (!permitted()) return WebhookResult(null,"grant-revoked")
        Socket().use { socket ->
            sockets[socket] = url
            val timeout=object : java.util.TimerTask() { override fun run() { runCatching { socket.close() } } }
            DEADLINES.schedule(timeout,remaining.toLong())
            try {
                val port = if(uri.port==-1) if(uri.scheme=="http") 80 else 443 else uri.port
                socket.connect(InetSocketAddress(address,port),minOf(5000,remaining))
                socket.soTimeout = minOf(5000,remaining)
                if (!allowed(socket.inetAddress) || !permittedUri(uri)) return WebhookResult(null,"webhook-target-refused")
                if (!permitted()) return WebhookResult(null,"grant-revoked")
                val wire=if(uri.scheme=="https") (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket,uri.host,port,true) else socket
                wire.use {
                    if(wire is SSLSocket) { wire.sslParameters=wire.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS" };wire.startHandshake() }
                    if(!permittedUri(uri) || !allowed(socket.inetAddress)) return WebhookResult(null,"webhook-target-refused")
                    if (!permitted()) return WebhookResult(null,"grant-revoked")
                    val path = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                    require(!path.contains('\r') && !path.contains('\n'))
                    val host = uri.host + if (port == 443) "" else ":$port"
                    val request = "POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" + headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                    wire.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII)); wire.outputStream.write(body); wire.outputStream.flush()
                    val first = StringBuilder()
                    while (first.length < 1024) { val c=wire.inputStream.read();if(c < 0 || c == 10) break;first.append(c.toChar()) }
                    val code = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*|\\r)?").matchEntire(first.toString().trimEnd('\r'))?.groupValues?.get(1)?.toIntOrNull()
                    if(nanoClock()>=deadline) return WebhookResult(null,"delivery-timeout")
                    // Redirects are failed deliveries; their Location is never inspected or followed.
                    WebhookResult(code,if(code == null) "invalid-response" else if(code in 300..399) "redirect-refused" else if(code !in 200..299) "receiver-rejected" else null)
                }
            } finally { timeout.cancel();DEADLINES.purge();sockets.remove(socket) }
        }
    } catch (_: ApiProblem) { WebhookResult(null,"webhook-target-refused") } catch (_: Exception) { WebhookResult(null,"delivery-failed") }
    companion object { private val DEADLINES=java.util.Timer("dulcet-webhook-deadlines",true) }
}

class ApiWebhooks(
    private val ctx: ServerContext,
    allowPrivateTargets: Boolean = false,
    allowOtherPorts: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: WebhookTransport = WebhookHttpTransport(allowPrivateTargets,allowOtherPorts,testingOverride={ctx.config.testMode && ctx.testing.webhookAllowPrivateTargets}),
) {
    private val db = ctx.db
    private val vault = PasswordVault.fromDataFolder(ctx.config.dataFolder)
    private data class Flight(val url:String,val event:String)
    private val inFlight = ConcurrentHashMap<String,Flight>()
    private val deliveryLock = Any()
    private data class Hook(val id:String,val user:String,val grant:String,val url:String,val scopes:Set<String>,val events:Set<String>,val description:String?,val active:Boolean,val created:Long,val last:Long?,val failures:Int,val reason:String?,val secret:ByteArray)
    private data class Delivery(val id:String,val hook:String,val event:String,val body:String,val created:Long,val attempts:Int)
    private val eventScopes = mapOf("wishlist.changed" to "user:read","wishlist.requested" to "admin","play.recorded" to "user:read","nowPlaying.changed" to "user:read","itemState.changed" to "user:read","playlist.changed" to "user:read","queue.changed" to "user:read","catalog.changed" to "catalog:read","scan.completed" to "admin","ping" to "account")
    init {
        db.write {
            execute("""CREATE TABLE IF NOT EXISTS api_webhook(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,grant_id TEXT NOT NULL REFERENCES api_grant(id) ON DELETE CASCADE,url TEXT NOT NULL,scopes TEXT NOT NULL,events TEXT NOT NULL,description TEXT,active INTEGER NOT NULL,created INTEGER NOT NULL,last_delivery INTEGER,failures INTEGER NOT NULL DEFAULT 0,disabled_reason TEXT,secret BLOB NOT NULL)""")
            execute("""CREATE TABLE IF NOT EXISTS api_webhook_delivery(id TEXT PRIMARY KEY,hook_id TEXT NOT NULL REFERENCES api_webhook(id) ON DELETE CASCADE,event TEXT NOT NULL,body TEXT NOT NULL,event_key TEXT,created INTEGER NOT NULL,next_attempt INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,UNIQUE(hook_id,event_key))""")
            execute("CREATE INDEX IF NOT EXISTS api_webhook_due ON api_webhook_delivery(next_attempt)")
            execute("CREATE TABLE IF NOT EXISTS api_webhook_event_cursor(instance TEXT PRIMARY KEY,seq INTEGER NOT NULL)")
            execute("""CREATE TABLE IF NOT EXISTS api_webhook_request(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,key TEXT NOT NULL,fingerprint TEXT NOT NULL,message_id TEXT NOT NULL,result BLOB,created INTEGER NOT NULL,PRIMARY KEY(user_id,key))""")
            execute("INSERT OR IGNORE INTO api_webhook_event_cursor(instance,seq) SELECT ?,COALESCE(MAX(seq),0) FROM api_event",ctx.journal.instanceId)
            execute("""CREATE TRIGGER IF NOT EXISTS api_webhook_grant_revoked AFTER UPDATE OF revoked ON api_grant WHEN NEW.revoked=1 BEGIN DELETE FROM api_webhook WHERE grant_id IN (WITH RECURSIVE descendants(id) AS (SELECT NEW.id UNION ALL SELECT g.id FROM api_grant g JOIN descendants d ON g.parent_id=d.id) SELECT id FROM descendants); END""")
            for((action,ref,op) in listOf(Triple("INSERT","NEW","upsert"),Triple("UPDATE","NEW","upsert"),Triple("DELETE","OLD","delete"))) {
                execute("CREATE TRIGGER IF NOT EXISTS api_webhook_journal_${action.lowercase()} AFTER $action ON api_webhook BEGIN INSERT INTO api_journal(type,id,user_id,op,at) VALUES('webhook',$ref.id,$ref.user_id,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
            }
        }
        // Stage the durable outbox before the event commits. A process loss between commit and
        // notification cannot drop a delivery, and rolled-back mutations produce no delivery.
        db.beforeCommit { session ->
            val instance=ctx.journal.instanceId
            val seen=session.long("SELECT seq FROM api_webhook_event_cursor WHERE instance=?",instance)
            val pending=session.query("SELECT seq,user_id,type,payload FROM api_event WHERE instance=? AND seq>? ORDER BY seq",instance,seen) { r ->
                Triple(r.long("seq"),r.stringOrNull("user_id"),r.string("type") to Json.parseToJsonElement(r.string("payload")).jsonObject)
            }
            pending.forEach { (seq,user,event) ->
                val type=if(event.first == "queue") "queue.changed" else event.first
                if(type in eventScopes) enqueue(type,user,event.second,eventKey="ev_${instance}_$seq")
            }
            pending.lastOrNull()?.first?.let { latest -> session.execute("INSERT INTO api_webhook_event_cursor(instance,seq) VALUES(?,?) ON CONFLICT(instance) DO UPDATE SET seq=excluded.seq",instance,latest) }
        }
        // A revoked grant's trigger deletes hooks in that transaction. Close any connection before
        // that revocation commits, rather than waiting for the delivery coroutine to finish.
        db.beforeCommit { inFlight.forEach { (id,flight) -> if(!permitted(id,flight.event)) transport.cancel(flight.url) } }
    }
    private fun hook(s:Db.Session,id:String):Hook? = s.single("SELECT * FROM api_webhook WHERE id=?",id) { r -> Hook(r.string("id"),r.string("user_id"),r.string("grant_id"),r.string("url"),r.string("scopes").split(' ').toSet(),Json.parseToJsonElement(r.string("events")).jsonArray.map { it.jsonPrimitive.content }.toSet(),r.stringOrNull("description"),r.bool("active"),r.long("created"),r.longOrNull("last_delivery"),r.int("failures"),r.stringOrNull("disabled_reason"),r.bytes("secret")!!) }
    private fun owned(p:Principal,id:String):Hook = db.read { hook(this,id)?.takeIf { it.user == p.user.id && ctx.apiAuth.isActive(it.grant) } } ?: throw ApiProblem(404,"not-found")
    private fun resource(h:Hook):JsonObject = j("id" to h.id,"url" to h.url,"events" to h.events.sorted(),"active" to h.active,"createdAt" to iso(h.created),"lastDeliveryAt" to h.last?.let(::iso),"consecutiveFailures" to h.failures,"disabledReason" to h.reason).let { if(h.description == null) it else JsonObject(it+j("description" to h.description)) }
    private fun events(body:JsonObject,scopes:Set<String>,required:Boolean):Set<String>? {
        val raw=body["events"] ?: if(required) throw ApiProblem(400,"invalid-request","events is required") else return null
        val values=(raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ApiProblem(400,"invalid-request") }?.toSet() ?: throw ApiProblem(400,"invalid-request")
        if(values.isEmpty() || values.any { it != "*" && it !in eventScopes }) throw ApiProblem(422,"invalid-event")
        val refused=values.filter { it!="*" && eventScopes[it] !in scopes }
        if(refused.isNotEmpty()) throw ApiProblem(403,"insufficient-scope",extra=j("events" to refused,"requiredScopes" to refused.mapNotNull { eventScopes[it] }.distinct()))
        return values
    }
    private fun description(body:JsonObject):String? = body["description"]?.let { if(it == JsonNull) null else (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { d -> d.length <= 2000 } ?: throw ApiProblem(400,"invalid-request") }
    fun create(p:Principal,body:JsonObject):JsonObject {
        if("account" !in p.scopes) throw ApiProblem(403,"insufficient-scope")
        val url=(body["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiProblem(400,"invalid-request","url is required")
        val subscribed=events(body,p.scopes,true)!!;val desc=description(body)
        transport.validate(url)
        val secret="whsec_"+Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes));val id="wh_"+UUID.randomUUID()
        db.write { execute("INSERT INTO api_webhook(id,user_id,grant_id,url,scopes,events,description,active,created,secret) VALUES(?,?,?,?,?,?,?,1,?,?)",id,p.user.id,p.tokenId,url,p.scopes.sorted().joinToString(" "),json(subscribed.sorted()).toString(),desc,clock(),vault.seal(secret)) }
        return JsonObject(resource(owned(p,id))+j("secret" to secret))
    }
    fun list(p:Principal):JsonObject = j("items" to db.read { query("SELECT id FROM api_webhook WHERE user_id=? ORDER BY created,id",p.user.id) { it.string("id") }.mapNotNull { hook(this,it) }.filter { ctx.apiAuth.isActive(it.grant) }.map(::resource) })
    fun get(p:Principal,id:String):JsonObject = resource(owned(p,id))
    fun update(p:Principal,id:String,body:JsonObject):JsonObject {
        val h=owned(p,id);val subscribed=events(body,currentScopes(h).intersect(p.scopes),false) ?: h.events
        val active=body["active"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: throw ApiProblem(400,"invalid-request") } ?: h.active
        val desc=if(body.containsKey("description")) description(body) else h.description
        val reason=if(active) null else if(body.containsKey("active")) "admin" else h.reason
        if(active) transport.validate(h.url)
        db.write { execute("UPDATE api_webhook SET events=?,description=?,active=?,failures=CASE WHEN ? THEN 0 ELSE failures END,disabled_reason=? WHERE id=?",json(subscribed.sorted()).toString(),desc,active,active,reason,id);if(!active) execute("DELETE FROM api_webhook_delivery WHERE hook_id=?",id) }
        if(!active) transport.cancel(h.url)
        return resource(owned(p,id))
    }
    fun remove(p:Principal,id:String) { val h=owned(p,id);db.write { execute("DELETE FROM api_webhook WHERE id=?",id) };transport.cancel(h.url) }
    /** Stage before event commit. The body is saved once, so retries keep id and data. */
    fun enqueue(type:String,userId:String?,data:JsonObject,eventKey:String?=null) {
        val scope=eventScopes[type] ?: return
        db.write {
            query("SELECT id FROM api_webhook WHERE active=1") { it.string("id") }.mapNotNull { hook(this,it) }.filter { h -> scope in currentScopes(h) && (type in h.events || "*" in h.events) && (userId == null || h.user == userId) && ctx.apiAuth.isActive(h.grant) && (type != "catalog.changed" || ctx.apiUsers.sourceIds(h.user).isNotEmpty()) }.forEach { h ->
                val visibleData=if(type == "catalog.changed") j("cursor" to ctx.journal.cursor(h.user,scopeTypes(currentScopes(h))),"types" to (data["types"] ?: json(emptyList<String>()))) else data
                enqueue(this,h,type,visibleData,eventKey)
            }
        }
    }
    private fun scopeTypes(scopes:Set<String>)=syncTypes(Principal(com.legitimateapps.dulcet.server.auth.User("","",false),"",null,scopes))
    private fun enqueue(s:Db.Session,h:Hook,type:String,data:JsonObject,key:String?=null):String {
        val id="msg_"+UUID.randomUUID();val body=j("id" to id,"type" to type,"timestamp" to iso(clock()),"userId" to h.user,"data" to data).toString()
        s.execute("INSERT OR IGNORE INTO api_webhook_delivery(id,hook_id,event,body,event_key,created,next_attempt) VALUES(?,?,?,?,?,?,?)",id,h.id,type,body,key,clock(),clock())
        return id
    }
    private fun currentScopes(h:Hook):Set<String> = h.scopes.intersect(ctx.apiAuth.currentScopes(h.grant) ?: emptySet())
    private fun permitted(id:String,event:String)=db.read { hook(this,id)?.let { it.active && eventScopes[event] in currentScopes(it) } ?: false }
    fun signature(secret:String,id:String,timestamp:Long,body:ByteArray):String {
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(Base64.getDecoder().decode(secret.removePrefix("whsec_")),"HmacSHA256"));mac.update("$id.$timestamp.".toByteArray(StandardCharsets.UTF_8))
        return "v1,"+Base64.getEncoder().encodeToString(mac.doFinal(body))
    }
    private fun cacheRequest(s:Db.Session,messageId:String,value:JsonObject) {
        val request=s.single("SELECT user_id,key,fingerprint FROM api_webhook_request WHERE message_id=? AND result IS NULL",messageId) { Triple(it.string("user_id"),it.string("key"),it.string("fingerprint")) } ?: return
        s.execute("UPDATE api_webhook_request SET result=? WHERE user_id=? AND key=? AND result IS NULL",vault.seal(value.toString()),request.first,request.second)
        s.execute("UPDATE api_idempotency SET status=200,body=?,headers='{}' WHERE user_id=? AND key=? AND fingerprint=?",Base64.getEncoder().encodeToString(vault.seal(value.toString())),request.first,request.second,request.third)
    }
    private fun attempt(d:Delivery):JsonObject {
        val h=db.read { hook(this,d.hook) } ?: return j("id" to d.id,"event" to d.event,"attemptedAt" to iso(clock()),"responseStatus" to null,"error" to "grant-revoked").also { value -> db.write { cacheRequest(this,d.id,value) } }
        val attempted=clock();val timestamp=attempted/1000;val bytes=d.body.toByteArray(StandardCharsets.UTF_8)
        val result=if(!permitted(h.id,d.event)) WebhookResult(null,"grant-revoked") else {
            inFlight[h.id]=Flight(h.url,d.event)
            try { transport.send(h.url,bytes,mapOf("webhook-id" to d.id,"webhook-timestamp" to timestamp.toString(),"webhook-signature" to signature(vault.open(h.secret),d.id,timestamp,bytes))) { permitted(h.id,d.event) } } catch (_: Exception) { WebhookResult(null,"delivery-failed") } finally { inFlight.remove(h.id) }
        }
        val value=j("id" to d.id,"event" to d.event,"attemptedAt" to iso(attempted),"responseStatus" to result.status,"error" to result.error)
        db.write {
            // Cache only the first attempt, including a failed receiver status. Its retry response
            // and the delivery health/outbox state are one commit; later background retries keep it.
            cacheRequest(this,d.id,value)
            val current=hook(this,h.id) ?: return@write
            if(!ctx.apiAuth.isActive(current.grant)) { execute("DELETE FROM api_webhook WHERE id=?",h.id);return@write }
            if(!current.active) { execute("DELETE FROM api_webhook_delivery WHERE hook_id=?",h.id);return@write }
            if(eventScopes[d.event] !in currentScopes(current)) { execute("DELETE FROM api_webhook_delivery WHERE id=?",d.id);return@write }
            val ok=result.status?.let { it in 200..299 } == true && result.error == null
            execute("UPDATE api_webhook SET last_delivery=?,failures=CASE WHEN ? THEN 0 ELSE failures+1 END WHERE id=?",attempted,ok,h.id)
            if(ok) execute("DELETE FROM api_webhook_delivery WHERE id=?",d.id)
            else if(attempted-d.created >= 86_400_000 || ctx.testing.webhookDisableAfterFailures?.let { current.failures+1>=it }==true) { execute("UPDATE api_webhook SET active=0,disabled_reason='delivery-failures' WHERE id=?",h.id);execute("DELETE FROM api_webhook_delivery WHERE hook_id=?",h.id) }
            else {
                val schedule=ctx.testing.webhookRetryScheduleSeconds
                if(schedule!=null && d.attempts>=schedule.size) execute("DELETE FROM api_webhook_delivery WHERE id=?",d.id)
                else { val delay=if(schedule==null) (60_000L * (1L shl minOf(d.attempts,6))).coerceAtMost(3_600_000L) else schedule[minOf(d.attempts,schedule.lastIndex)]*1000
                    execute("UPDATE api_webhook_delivery SET attempts=attempts+1,next_attempt=? WHERE id=?",attempted+delay,d.id) }
            }
        }
        return value
    }
    fun test(p:Principal,id:String,key:String?=null):JsonObject = synchronized(deliveryLock) {
        if("account" !in p.scopes) throw ApiProblem(403,"insufficient-scope")
        if(key!=null) try { UUID.fromString(key) } catch (_:Exception) { throw ApiProblem(400,"invalid-request","Idempotency-Key must be a UUID") }
        val fingerprint=hash(p.tokenId+"|"+p.scopes.sorted().joinToString(" ")+"|/webhooks/$id/test|{}")
        if(key!=null) {
            val existing=db.read { single("SELECT fingerprint,message_id,result FROM api_webhook_request WHERE user_id=? AND key=? AND created>=?",p.user.id,key,clock()-86400000) { Triple(it.string("fingerprint"),it.string("message_id"),it.bytes("result")) } }
            if(existing!=null) {
                if(existing.first!=fingerprint) throw ApiProblem(422,"idempotency-key-reused")
                existing.third?.let { return@synchronized Json.parseToJsonElement(vault.open(it)).jsonObject }
                val pending=db.read { single("SELECT * FROM api_webhook_delivery WHERE id=?",existing.second,map=::delivery) }
                if(pending!=null) return@synchronized attempt(pending)
                val missing=j("id" to existing.second,"event" to "ping","attemptedAt" to iso(clock()),"responseStatus" to null,"error" to "delivery-unavailable")
                db.write { cacheRequest(this,existing.second,missing) }
                return@synchronized missing
            }
            // Keys have one namespace across all POST routes, including the generic middleware.
            if(db.read { long("SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND key=? AND at>=?",p.user.id,key,clock()-86400000) } > 0) throw ApiProblem(422,"idempotency-key-reused")
        }
        val h=owned(p,id);if(!h.active) throw ApiProblem(409,"webhook-disabled")
        val delivery=db.write {
            if(key!=null && long("SELECT COUNT(*) FROM api_idempotency WHERE user_id=? AND key=? AND at>=?",p.user.id,key,clock()-86400000)>0) throw ApiProblem(422,"idempotency-key-reused")
            val messageId=enqueue(this,h,"ping",j())
            if(key!=null) {
                execute("DELETE FROM api_webhook_request WHERE created<?",clock()-86400000)
                execute("DELETE FROM api_idempotency WHERE at<?",clock()-86400000)
                execute("INSERT INTO api_webhook_request(user_id,key,fingerprint,message_id,created) VALUES(?,?,?,?,?)",p.user.id,key,fingerprint,messageId,clock())
                execute("INSERT INTO api_idempotency(user_id,key,fingerprint,status,body,headers,at) VALUES(?,?,?,0,?,'{}',?)",p.user.id,key,fingerprint,Base64.getEncoder().encodeToString(vault.seal("{}")),clock())
            }
            single("SELECT * FROM api_webhook_delivery WHERE id=?",messageId,map=::delivery)!!
        }
        attempt(delivery)
    }
    private fun delivery(r:Db.Row)=Delivery(r.string("id"),r.string("hook_id"),r.string("event"),r.string("body"),r.long("created"),r.int("attempts"))
    /** Run from the server's IO coroutine; no background thread survives application shutdown. */
    fun maintenance() = synchronized(deliveryLock) {
        val dead=db.read { query("SELECT id FROM api_webhook") { it.string("id") }.filter { id -> hook(this,id)?.let { !ctx.apiAuth.isActive(it.grant) } ?: true } }
        dead.forEach { id -> inFlight[id]?.url?.let(transport::cancel) }
        db.write { dead.forEach { execute("DELETE FROM api_webhook WHERE id=?",it) } }
        inFlight.forEach { (id,flight) -> if(!permitted(id,flight.event)) transport.cancel(flight.url) }
        val due=db.read { query("SELECT d.* FROM api_webhook_delivery d JOIN api_webhook h ON h.id=d.hook_id WHERE h.active=1 AND d.next_attempt<=? ORDER BY d.next_attempt LIMIT 20",clock(),map=::delivery) }
        due.forEach(::attempt)
    }
    fun install(route:Route) = with(route) {
        get("/webhooks") { call.send(list(call.principal(ctx,"account"))) }
        post("/webhooks") { val value=create(call.principal(ctx,"account"),call.body());call.response.headers.append(HttpHeaders.Location,call.apiLocation("/dulcet/v1/webhooks/${value["id"]!!.jsonPrimitive.content}"));call.send(value,201) }
        get("/webhooks/{webhookId}") { call.send(get(call.principal(ctx,"account"),call.parameters["webhookId"]!!)) }
        patch("/webhooks/{webhookId}") { call.send(update(call.principal(ctx,"account"),call.parameters["webhookId"]!!,call.body())) }
        delete("/webhooks/{webhookId}") { remove(call.principal(ctx,"account"),call.parameters["webhookId"]!!);call.respond(HttpStatusCode.NoContent) }
        post("/webhooks/{webhookId}/test") { val p=call.principal(ctx,"account");val id=call.parameters["webhookId"]!!;call.send(test(p,id,call.request.headers["Idempotency-Key"])) }
    }
}
