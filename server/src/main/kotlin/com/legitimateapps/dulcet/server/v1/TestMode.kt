package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.auth.User
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Explicit startup-only testing mode; overrides are never persisted or enabled remotely. */
class TestMode(private val ctx:ServerContext) {
    private val normalMarker=ctx.config.dataFolder.resolve("served-without-test-mode")
    @Volatile private var value=j()
    private val buckets=ConcurrentHashMap<String,Pair<Long,Int>>()
    private val checkpoints=ConcurrentHashMap<String,Pair<Path,JsonObject>>()
    init {
        require(!ctx.config.testMode || (!Files.exists(normalMarker) && ctx.db.read { string("SELECT value FROM meta WHERE key='served_without_test_mode'") }!="true")) {
            "Test mode refused: these data were served without test mode"
        }
    }
    fun markServed() {
        if(!ctx.config.testMode) {
            Files.writeString(normalMarker,"true\n")
            ctx.db.write { execute("INSERT INTO meta VALUES('served_without_test_mode','true') ON CONFLICT(key) DO UPDATE SET value='true'") }
        }
    }
    fun settings():JsonObject=value
    val mediaTtlSeconds:Long get()=value["mediaUrlTtlSeconds"]?.jsonPrimitive?.long ?: 21600L
    val artworkTtlSeconds:Long get()=value["artworkUrlTtlSeconds"]?.jsonPrimitive?.long ?: 604800L
    val webhookAllowPrivateTargets:Boolean get()=ctx.config.testMode && (value["webhooks"] as? JsonObject)?.get("allowPrivateTargets")==JsonPrimitive(true)
    val webhookRetryScheduleSeconds:List<Long>? get()=if(ctx.config.testMode) (value["webhooks"] as? JsonObject)?.get("retryScheduleSeconds")?.jsonArray?.map { it.jsonPrimitive.long } else null
    val webhookDisableAfterFailures:Int? get()=if(ctx.config.testMode) (value["webhooks"] as? JsonObject)?.get("disableAfterFailures")?.jsonPrimitive?.int else null
    internal fun trustedClient(id:String):JsonObject?=if(ctx.config.testMode) value["trustedClients"]?.jsonArray?.firstOrNull { it.jsonObject["client_id"]==JsonPrimitive(id) }?.jsonObject else null
    fun autoUser(clientId:String):User? {
        if(!ctx.config.testMode) return null
        val id=value["autoApprove"]?.jsonArray?.firstOrNull { it.jsonObject["clientId"]!!.jsonPrimitive.content==clientId }?.jsonObject?.get("userId")?.jsonPrimitive?.content ?: return null
        return ctx.users.byId(id)
    }
    fun replace(body:JsonObject):JsonObject {
        if(!ctx.config.testMode) throw ApiProblem(404,"not-found")
        fun positive(raw:JsonElement?):JsonPrimitive {
            val n=raw as? JsonPrimitive ?: throw ApiProblem(400,"invalid-request")
            if(n.isString || n.longOrNull==null || n.long<=0 || n.long>Int.MAX_VALUE) throw ApiProblem(400,"invalid-request")
            return n
        }
        val parsed=body.filterKeys { it in setOf("autoApprove","syncRetentionSeconds","mediaUrlTtlSeconds","artworkUrlTtlSeconds","rateLimit","trustedClients","webhooks") }.mapValues { (name,raw) ->
            when(name) {
                "autoApprove" -> {
                    val array=raw as? JsonArray ?: throw ApiProblem(400,"invalid-request")
                    JsonArray(array.map { item ->
                        val row=item as? JsonObject ?: throw ApiProblem(400,"invalid-request")
                        val client=(row["clientId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiProblem(400,"invalid-request")
                        val user=(row["userId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ApiProblem(400,"invalid-request")
                        if(client.isBlank() || ctx.users.byId(user)==null) throw ApiProblem(400,"invalid-request")
                        j("clientId" to client,"userId" to user)
                    })
                }
                "trustedClients" -> {
                    val array=raw as? JsonArray ?: throw ApiProblem(400,"invalid-request")
                    JsonArray(array.map { item ->
                        val document=item as? JsonObject ?: throw ApiProblem(400,"invalid-request")
                        try { ClientIdentities.fromDocument(document["client_id"]!!.jsonPrimitive.content,document) }
                        catch(_:Exception) { throw ApiProblem(400,"invalid-request") }
                        document
                    })
                }
                "webhooks" -> {
                    val row=raw as? JsonObject ?: throw ApiProblem(400,"invalid-request")
                    JsonObject(row.filterKeys { it in setOf("allowPrivateTargets","retryScheduleSeconds","disableAfterFailures") }.mapValues { (key,v) ->
                        when(key) {
                            "allowPrivateTargets" -> { val flag=v as? JsonPrimitive ?: throw ApiProblem(400,"invalid-request");if(flag.isString || flag.booleanOrNull==null)throw ApiProblem(400,"invalid-request");flag }
                            "disableAfterFailures" -> positive(v)
                            else -> { val delays=v as? JsonArray ?: throw ApiProblem(400,"invalid-request");JsonArray(delays.map { delay -> val n=delay as? JsonPrimitive ?: throw ApiProblem(400,"invalid-request");if(n.isString || n.longOrNull==null || n.long<0 || n.long>Int.MAX_VALUE)throw ApiProblem(400,"invalid-request");n }) }
                        }
                    })
                }
                "rateLimit" -> {
                    val row=raw as? JsonObject ?: throw ApiProblem(400,"invalid-request")
                    j("requestsPerWindow" to positive(row["requestsPerWindow"]),"windowSeconds" to positive(row["windowSeconds"]))
                }
                else -> positive(raw)
            }
        }
        value=JsonObject(parsed)
        buckets.clear()
        ctx.journal.retentionMillis=value["syncRetentionSeconds"]?.jsonPrimitive?.long?.times(1000) ?: 90L*24*60*60*1000
        ctx.journal.prune()
        // Existing art signatures must not override a newly shortened lifetime.
        ctx.db.write { execute("DELETE FROM api_artwork_grant") }
        return value
    }
    fun checkLimit(call:ApplicationCall) {
        val rate=value["rateLimit"] as? JsonObject ?: return
        val p=ctx.apiAuth.authenticate(call.request.headers[HttpHeaders.Authorization]) ?: return
        val seconds=rate["windowSeconds"]!!.jsonPrimitive.long
        val now=System.currentTimeMillis()/1000
        val window=now/seconds
        val count=buckets.compute(p.tokenId) { _,old -> if(old?.first==window) window to old.second+1 else window to 1 }!!.second
        if(count>rate["requestsPerWindow"]!!.jsonPrimitive.int) {
            call.response.headers.append(HttpHeaders.RetryAfter,(seconds-now%seconds).toString())
            throw ApiProblem(429,"rate-limited")
        }
    }
    private fun admin(call:ApplicationCall):Principal {
        if(!ctx.config.testMode) throw ApiProblem(404,"not-found")
        return call.principal(ctx,"admin")
    }
    fun install(route:Route) {
        route.get("/admin/test/settings") { admin(call);call.send(settings()) }
        route.put("/admin/test/settings") { admin(call);call.send(replace(call.body())) }
        route.post("/admin/test/checkpoints") {
            admin(call)
            val id="ckpt_${UUID.randomUUID()}"
            val folder=ctx.config.dataFolder.resolve("test-checkpoints")
            Files.createDirectories(folder,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val path=folder.resolve("$id.db")
            val checkpoint=ctx.db.exclusive {
                ctx.db.checkpoint(path)
                Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
                j("id" to id,"createdAt" to iso(System.currentTimeMillis()),"instanceId" to ctx.journal.instanceId)
            }
            checkpoints[id]=path to checkpoint
            call.send(checkpoint,201)
        }
        route.post("/admin/test/checkpoints/{checkpointId}/restore") {
            val p=admin(call)
            val checkpoint=checkpoints[call.parameters["checkpointId"]] ?: throw ApiProblem(404,"not-found")
            ctx.apiAuth.withCarriedGrant(p) {
                ctx.db.restore(checkpoint.first)
                ctx.journal.rotateInstance()
            }
            call.send(j("name" to "Dulcet Server","version" to com.legitimateapps.dulcet.server.SERVER_VERSION,"instanceId" to ctx.journal.instanceId))
        }
        route.route("/admin/test/{path...}") { handle { admin(call);throw ApiProblem(404,"not-found") } }
    }
}
