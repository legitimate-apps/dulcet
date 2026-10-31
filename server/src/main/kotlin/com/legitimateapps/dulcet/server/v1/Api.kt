package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.configuredRoute
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import com.legitimateapps.dulcet.server.auth.PasswordVault
import java.util.Base64
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

class ApiProblem(val status: Int, val code: String, val detail: String = "", val extra: JsonObject = JsonObject(emptyMap())) : RuntimeException(code)
fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
    is Iterable<*> -> JsonArray(value.map(::json))
    else -> error("Unsupported JSON value")
}
fun j(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(fields.associate { it.first to json(it.second) })
fun iso(at: Long): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(at))
fun hash(text: String): String = hash(text.toByteArray())
fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
val API_SCOPES = setOf("catalog:read", "media:read", "user:read", "user:write", "user:delete", "plays:write", "account", "admin", "offline_access")
private val bodyKey = AttributeKey<JsonObject>("dulcet.api.body")
private val contextKey = AttributeKey<ServerContext>("dulcet.api.context")
private val syncKey = AttributeKey<String>("dulcet.api.sync")
private val deferredKey = AttributeKey<Boolean>("dulcet.api.deferred")
private val responseKey = AttributeKey<CachedResponse>("dulcet.api.response")
private data class CachedResponse(val body: String, val status: Int, val etag: String?, val headers: JsonObject)

suspend fun ApplicationCall.body(): JsonObject {
    attributes.getOrNull(bodyKey)?.let { return it }
    val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (length != null && length > 1024 * 1024) throw ApiProblem(413,"invalid-request", "Body exceeds 1 MiB")
    val channel=receiveChannel()
    val bytes=ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {
        val count=channel.readAvailable(buffer,0,buffer.size)
        if(count<0) break
        if(bytes.size()+count>1024*1024) throw ApiProblem(413,"invalid-request","Body exceeds 1 MiB")
        bytes.write(buffer,0,count)
    }
    val text=bytes.toString(Charsets.UTF_8)
    val result = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw ApiProblem(400,"invalid-request","Expected a JSON object") }
    attributes.put(bodyKey, result)
    return result
}
fun ApplicationCall.principal(ctx: ServerContext, vararg scopes: String): Principal {
    val p = ctx.apiAuth.authenticate(request.headers[HttpHeaders.Authorization]) ?: throw ApiProblem(401,"unauthenticated")
    val missing = scopes.filter { it !in p.scopes }
    if (missing.isNotEmpty()) throw ApiProblem(403,"insufficient-scope",extra=j("requiredScopes" to missing))
    return p
}
fun syncTypes(p: Principal): String = buildList {
    if ("catalog:read" in p.scopes) addAll(listOf("artist","album","track","genre","source"))
    if ("user:read" in p.scopes) addAll(listOf("itemState","playlist","play","queue","wish"))
}.sorted().joinToString(",")
fun ApplicationCall.stamp(ctx: ServerContext, p: Principal) {
    attributes.put(syncKey, ctx.journal.cursor(p.user.id, syncTypes(p)))
}
suspend fun ApplicationCall.send(value: JsonElement, status: Int = 200, etag: String? = null, cacheable:Boolean=true) {
    val ctx = attributes.getOrNull(contextKey)
    val effectiveTag = etag ?: if (cacheable && request.httpMethod == HttpMethod.Get && status == 200 && ctx != null) "${ctx.journal.instanceId}.${hash(value.toString())}" else null
    val quoted = effectiveTag?.let { if (it.startsWith('"')) it else "\"$it\"" }
    if (quoted != null) {
        response.headers.append(HttpHeaders.ETag, quoted)
        response.headers.append(HttpHeaders.CacheControl, "private")
        response.headers.append(HttpHeaders.Vary, "Authorization")
    }
    attributes.getOrNull(syncKey)?.let { response.headers.append("Dulcet-Sync-Cursor",it) }
    if (quoted != null && request.httpMethod == HttpMethod.Get && request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.map(String::trim)?.any { it == quoted || it == "W/$quoted" || it == "*" } == true) {
        respond(HttpStatusCode.NotModified)
        return
    }
    attributes.put(responseKey, CachedResponse(value.toString(),status,quoted,j(*response.headers.allValues().entries().map { it.key to it.value.joinToString(",").let { value -> if(it.key.equals(HttpHeaders.Location,true) && configuredRoute()!=null) value.removePrefix(origin()) else value } }.toTypedArray())))
    if (attributes.getOrNull(deferredKey) == true) return
    if(status == 204) respond(HttpStatusCode.NoContent) else respondText(value.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))
}
suspend fun ApplicationCall.problem(error: ApiProblem) {
    if (error.status == 401) response.headers.append(HttpHeaders.WWWAuthenticate,"Bearer")
    if (error.status == 429 && response.headers[HttpHeaders.RetryAfter]==null) response.headers.append(HttpHeaders.RetryAfter,"60")
    respondText((j("type" to "urn:dulcet:problem:${error.code}","title" to error.code.replace('-',' '),"status" to error.status) + error.extra + if(error.detail.isNotEmpty()) j("detail" to error.detail) else emptyMap()).let(::JsonObject).toString(), ContentType.parse("application/problem+json"), HttpStatusCode.fromValue(error.status))
}

/** Address and authenticated identity buckets; never use credentials as a log or storage key. */
class ApiLimiter(var maxRequests: Int = 600, private val clock: () -> Long = System::currentTimeMillis) {
    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()
    fun check(key: String) {
        val minute = clock()/60000
        val value = buckets.compute(key) { _, old -> if(old?.first==minute) minute to old.second+1 else minute to 1 }!!
        if (value.second > maxRequests) throw ApiProblem(429,"rate-limited")
        if (buckets.size > 10000) buckets.entries.removeIf { it.value.first < minute }
    }
}

class DulcetApi(private val ctx: ServerContext) {
    val limiter = ApiLimiter()
    private val posts = Mutex()
    private val vault = PasswordVault.fromDataFolder(ctx.config.dataFolder)
    private val scans=ApiScans(ctx)
    init { ctx.db.write { execute("CREATE TABLE IF NOT EXISTS api_idempotency(user_id TEXT NOT NULL,key TEXT NOT NULL,fingerprint TEXT NOT NULL,status INTEGER NOT NULL,body TEXT NOT NULL,headers TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(user_id,key))") } }
    fun install(route: Route, mountPrefix: String) {
        route.intercept(ApplicationCallPipeline.Call) {
            call.attributes.put(contextKey,ctx)
            val path = call.request.path().removePrefix(mountPrefix)
            val isTest = path.startsWith("/dulcet/v1/admin/test/")
            val isApi = path=="/dulcet/v1" || path.startsWith("/dulcet/v1/")
            if (!isApi) {
                try { ctx.testing.checkLimit(call) } catch(e:ApiProblem) { call.problem(e);finish();return@intercept }
                if(path.startsWith("/.well-known/")) {
                    call.response.headers.append("Access-Control-Allow-Origin","*")
                    call.response.headers.append("Access-Control-Expose-Headers","ETag, Location, Retry-After")
                }
                proceed(); return@intercept
            }
            try {
                call.response.headers.append("Access-Control-Allow-Origin","*")
                call.response.headers.append("Access-Control-Expose-Headers","ETag, Location, Retry-After, Dulcet-Sync-Cursor, Deprecation, Sunset, RateLimit, RateLimit-Policy, Mcp-Session-Id")
                if(path=="/dulcet/v1/mcp") ctx.apiMcp.validateOrigin(call)
                if(call.request.httpMethod==HttpMethod.Options) {
                    call.response.headers.append("Access-Control-Allow-Methods","GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers","Authorization, Content-Type, Idempotency-Key, If-Match, If-None-Match, Last-Event-ID, Dulcet-Device-Id, Dulcet-Action-Time, Range, Mcp-Session-Id, MCP-Protocol-Version")
                    call.respond(HttpStatusCode.NoContent); finish(); return@intercept
                }
                if(!isTest) ctx.testing.checkLimit(call)
                // Unknown parameters are ignored; credential names and recognizable bearer values are refused.
                if (call.request.queryParameters.names().any { it.lowercase() in setOf("token","access_token","refresh_token","api_key","apikey","authorization","password","p","t","s") } || call.request.queryParameters.entries().any { (_,values) -> values.any { value -> listOf("dulcet_pat_","dulcet_at_","dulcet_rt_").any(value::startsWith) } }) {
                    if(call.request.headers[HttpHeaders.Authorization]==null) throw ApiProblem(401,"unauthenticated")
                    throw ApiProblem(400,"invalid-request","Credentials require an Authorization header")
                }
                if(!isTest) limiter.check("ip:"+ctx.config.clientAddresses.resolve(call))
                if(!isTest) call.request.headers[HttpHeaders.Authorization]?.let { h -> ctx.apiAuth.authenticate(h)?.let { limiter.check("user:"+it.user.id) } }
                call.request.headers["Dulcet-Device-Id"]?.let { device -> ctx.apiAuth.authenticate(call.request.headers[HttpHeaders.Authorization])?.let { p -> ctx.apiQueue.seen(p.user.id,device) } }
                val key = call.request.headers["Idempotency-Key"].takeIf { call.request.httpMethod==HttpMethod.Post }
                val webhookTest=path.startsWith("/dulcet/v1/webhooks/") && path.endsWith("/test")
                if(key == null || isTest || path=="/dulcet/v1/mcp" || webhookTest) {
                    // Ping owns its durable retry result; serialize the shared key namespace
                    // without holding SQLite during outbound delivery.
                    if(key!=null && webhookTest) {
                        posts.lock()
                        try { proceed() } finally { posts.unlock() }
                    } else proceed()
                    return@intercept
                }
                try { UUID.fromString(key) } catch (_:Exception) { throw ApiProblem(400,"invalid-request","Idempotency-Key must be a UUID") }
                val operation=path.removePrefix("/dulcet/v1")
                val scopes=when {
                    operation=="/me/wishes/cancellations" -> arrayOf("user:delete")
                    operation=="/me/wishes" || operation.startsWith("/me/wishes/") || operation=="/discover/rejected-matches" -> arrayOf("user:write")
                    operation=="/me/tokens" || operation=="/me/password" -> arrayOf("account")
                    operation=="/playlists" || operation=="/me/batch" || operation.startsWith("/playlists/") -> if(operation=="/me/batch") emptyArray() else arrayOf("user:write")
                    operation=="/me/plays" -> arrayOf("plays:write")
                    operation=="/playback/decisions" -> arrayOf("media:read")
                    operation.startsWith("/admin/") || operation=="/users" -> arrayOf("admin")
                    operation.startsWith("/me/queue") -> arrayOf("user:write")
                    operation.startsWith("/webhooks") -> arrayOf("account")
                    else -> emptyArray()
                }
                val p = call.principal(ctx,*scopes)
                if(operation.startsWith("/me/wishes") || operation=="/discover/rejected-matches")ctx.apiWishlist.requireEnabled(operation)
                val body = if(operation=="/admin/scans" && (call.request.headers["Content-Length"]=="0" || call.request.headers["Content-Type"]==null)) j() else call.body()
                val fingerprint = hash(p.tokenId+"|"+p.scopes.sorted().joinToString(" ")+"|"+operation + "|" + body.toString())
                posts.lock()
                try {
                    val saved = ctx.db.read { single("SELECT * FROM api_idempotency WHERE user_id=? AND key=? AND at>=?",p.user.id,key,System.currentTimeMillis()-86400000) { Triple(it.string("fingerprint"),it.int("status"),it.string("body") to it.string("headers")) } }
                    if(saved!=null) {
                        if(saved.first!=fingerprint) throw ApiProblem(422,"idempotency-key-reused")
                        if(operation=="/me/wishes") {
                            ctx.apiWishlist.replayAdd(call,p,body);finish();return@intercept
                        }
                        Json.parseToJsonElement(saved.third.second).jsonObject.forEach { (name,value) -> if(name.lowercase() !in setOf("content-type","content-length","access-control-allow-origin","access-control-expose-headers")) call.response.headers.append(name,if(name.equals(HttpHeaders.Location,true) && value.jsonPrimitive.content.startsWith("/dulcet/v1/")) call.apiLocation(value.jsonPrimitive.content) else value.jsonPrimitive.content) }
                        call.respondText(vault.open(Base64.getDecoder().decode(saved.third.first)),ContentType.Application.Json,HttpStatusCode.fromValue(saved.second)); finish()
                    } else {
                        call.attributes.put(deferredKey,true)
                        // The mutation and retry response commit together. runBlocking confines the
                        // synchronous SQLite session to its owning thread; responses are buffered.
                        val result = ctx.db.write {
                            runBlocking { proceed() }
                            val result=call.attributes.getOrNull(responseKey) ?: throw ApiProblem(500,"server-error")
                            if(result.status in 200..299) {
                                execute("INSERT OR REPLACE INTO api_idempotency VALUES(?,?,?,?,?,?,?)",p.user.id,key,fingerprint,result.status,Base64.getEncoder().encodeToString(vault.seal(result.body)),result.headers.toString(),System.currentTimeMillis())
                                execute("DELETE FROM api_idempotency WHERE at<?",System.currentTimeMillis()-86400000)
                            }
                            result
                        }
                        call.attributes.remove(deferredKey)
                        if(result.status==204) call.respond(HttpStatusCode.NoContent) else call.respondText(result.body,ContentType.Application.Json,HttpStatusCode.fromValue(result.status))
                    }
                } finally { posts.unlock() }
            } catch(e:com.legitimateapps.dulcet.server.discover.UpstreamUnavailable) {
                e.retryAfterMillis?.let { call.response.headers.append(HttpHeaders.RetryAfter,((it+999)/1000).toString()) }
                call.problem(ApiProblem(503,"upstream-unavailable"));finish()
            } catch(_:com.legitimateapps.dulcet.server.discover.UpstreamNotFound) {call.problem(ApiProblem(404,"not-found"));finish()}
            catch (e: ApiProblem) { call.problem(e); finish() }
            catch (_: IllegalArgumentException) { call.problem(ApiProblem(400,"invalid-request")); finish() }
            catch (_: Exception) { call.problem(ApiProblem(500,"server-error")); finish() }
        }
        ctx.apiAuth.installOAuth(route)
        ctx.apiMcp.installMetadata(route)
        route.get("/.well-known/dulcet") {
            val origin = call.origin()
            call.send(j("standard" to "dulcet","apis" to listOf(j("version" to "1","baseUrl" to "$origin/dulcet/v1","status" to "current")),"server" to serverInfo(),"authorizationServer" to origin,"openapi" to "$origin/dulcet/v1/openapi.yaml","testMode" to ctx.config.testMode,"capabilities" to (if(ctx.config.routes.isNotEmpty()) j("dulcet.routes" to j("versions" to listOf(1))) else j()),"routeDirectory" to (if(ctx.config.routes.isNotEmpty()) j("method" to "POST","url" to "$origin/rest/getDulcetConnections.view") else null)))
        }
        route.route("/dulcet/v1") {
            get("/capabilities") {
                val header=call.request.headers[HttpHeaders.Authorization]
                val p=header?.let { call.principal(ctx) }
                val fields=j("apiVersion" to "1.0","server" to serverInfo(),"capabilities" to j("dulcet.sync" to j("versions" to listOf(1)),"dulcet.home" to j("versions" to listOf(1))).let { kotlinx.serialization.json.JsonObject(it + (if(ctx.config.routes.isNotEmpty()) j("dulcet.routes" to j("versions" to listOf(1))) else j()) + (if(ctx.config.discover.enabled) j("dulcet.discover" to j("versions" to listOf(1))) else j()) + (if(ctx.config.discover.wishlistEnabled) j("dulcet.wishlist" to j("versions" to listOf(1))) else j())) },"limits" to j("maxPageSize" to 500,"maxBatchSize" to 500,"maxAlbumTracks" to 500,"syncRetentionDays" to 90,"maxTokenLifetimeDays" to 365,"mediaUrlTtlSeconds" to 21600,"artworkUrlTtlSeconds" to 604800),"auth" to j("flows" to listOf("authorization_code","device_code","refresh_token","personal_token"),"scopes" to API_SCOPES.toList()))
                call.send(if(p==null) fields else JsonObject(fields+j("permissions" to j("scopes" to p.scopes.toList(),"download" to ("media:read" in p.scopes && ctx.apiUsers.download(p.user.id)),"admin" to ("admin" in p.scopes)))))
            }
            get("/openapi.yaml") { call.respondText(DulcetApi::class.java.classLoader.getResourceAsStream("dulcet-api/openapi.yaml")!!.bufferedReader().use { it.readText() },ContentType.parse("application/yaml")) }
            ctx.apiAuth.install(this)
            ctx.apiUsers.install(this)
            get("/recommendations/home") {
                val p = call.principal(ctx, "catalog:read", "user:read")
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request") } ?: 20
                if (limit !in 1..50) throw ApiProblem(400,"invalid-request")
                val selected = ctx.home.shelves(p.user.id, limit)
                ctx.apiMedia.warmArtwork(selected.flatMap { shelf -> shelf.ids.map { if(shelf.type=="playlist") "pl-$it" else it } }, p)
                val shelves = selected.map { shelf ->
                    j("id" to shelf.id, "title" to shelf.title, "reason" to shelf.reason,
                        "items" to shelf.ids.mapNotNull { if (shelf.type=="playlist") ctx.apiUserData.resource("playlist",it,p) else ctx.apiCatalog.resource(shelf.type,it,p) })
                }
                call.send(j("shelves" to shelves))
            }
            ctx.apiWishlist.install(this)
            ctx.apiDiscover.install(this)
            ctx.apiCatalog.install(this)
            ctx.apiUserData.install(this)
            ctx.apiMedia.install(this)
            ctx.apiSync.install(this)
            ctx.apiQueue.install(this)
            ctx.apiEvents.install(this)
            ctx.apiWebhooks.install(this)
            ctx.apiMcp.install(this)
            scans.install(this)
            ctx.testing.install(this)
            route("/{path...}") { handle { throw ApiProblem(501,"unavailable","This operation is not implemented") } }
        }
    }
    private fun serverInfo() = j("name" to "Dulcet Server","version" to com.legitimateapps.dulcet.server.SERVER_VERSION,"instanceId" to ctx.journal.instanceId)
}
fun ApplicationCall.origin(): String {
    configuredRoute()?.let { return it.base }
    attributes.getOrNull(contextKey)?.config?.publicUrl?.let { return it }
    val host=request.headers[HttpHeaders.Host] ?: "localhost"
    if(host.any { it=='\r'||it=='\n'||it=='/'||it=='\\' }) throw ApiProblem(400,"invalid-request")
    return "${request.local.scheme}://$host"
}

/** Absolute configured route plus API path; legacy Locations retain their original relative form. */
fun ApplicationCall.apiLocation(path: String): String = configuredRoute()?.let { it.base + path } ?: path
