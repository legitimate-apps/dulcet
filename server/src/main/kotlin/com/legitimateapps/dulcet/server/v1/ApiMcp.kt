package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.configuredRoute
import com.legitimateapps.dulcet.server.fold
import com.legitimateapps.dulcet.server.discover.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Streamable HTTP uses ordinary authenticated requests; a session never substitutes for a grant. */
class ApiMcp(private val ctx: ServerContext, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Session(val grant: String, val protocol: String, @Volatile var used: Long)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val codec = CursorCodec(ctx)
    val definitions: List<JsonObject> by lazy {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("dulcet-api/mcp-tools.json")) { "Missing MCP tool contract" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
    }
    private val protocols = setOf("2025-03-26", "2025-06-18", "2025-11-25")

    private fun enabled(d:JsonObject):Boolean=when(d["x-dulcet-capability"]?.jsonPrimitive?.content) {
        "dulcet.discover"->ctx.config.discover.enabled
        "dulcet.wishlist"->ctx.config.discover.wishlistEnabled
        null->true
        else->false
    }
    fun tools(p: Principal): List<JsonObject> = definitions.filter { it["x-dulcet-scope"]!!.jsonPrimitive.content in p.scopes && enabled(it) }.map { definition ->
        JsonObject(definition.filterKeys { it !in setOf("x-dulcet-scope","x-dulcet-capability") } + j("_meta" to JsonObject(j("dulcet/scopes" to listOf(definition["x-dulcet-scope"]!!)) + (definition["x-dulcet-capability"]?.let {j("dulcet/capability" to it)} ?: j()))))
    }

    fun validateOrigin(call: ApplicationCall) {
        val incoming = call.request.headers[HttpHeaders.Origin] ?: return
        fun canonical(value: String): String? = runCatching {
            val uri = URI(value)
            if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath.orEmpty() !in setOf("", "/")) return@runCatching null
            val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}:$port"
        }.getOrNull()
        val expected = call.configuredRoute()?.let { canonical("${it.uri.scheme}://${it.uri.rawAuthority}") } ?: ctx.config.publicUrl?.let(::canonical) ?: run {
            // A request's Host alone is not a trust anchor: an attacker can rebind its own DNS
            // name to a local listener. Remote browser origins require an explicit public URL.
            val local = URI(call.origin())
            val allowedHosts = setOf("localhost", "127.0.0.1", "::1") + ctx.config.bind.takeUnless { it in setOf("0.0.0.0", "::") }.orEmpty()
            if (local.host?.lowercase() !in allowedHosts) null else canonical(call.origin())
        }
        if (expected == null || canonical(incoming) == null || canonical(incoming) != expected) throw ApiProblem(403, "forbidden", "Unexpected MCP Origin")
    }

    private fun authorized(call: ApplicationCall): Principal {
        validateOrigin(call)
        val p = call.principal(ctx)
        call.request.headers["MCP-Protocol-Version"]?.let { if (it !in protocols) throw ApiProblem(400, "invalid-request", "Unsupported MCP protocol version") }
        return p
    }

    private fun session(call: ApplicationCall, p: Principal): Session {
        val id = call.request.headers["Mcp-Session-Id"] ?: throw ApiProblem(400, "invalid-request", "Mcp-Session-Id is required")
        val value = sessions[id] ?: throw ApiProblem(404, "not-found", "Unknown MCP session")
        if (clock() - value.used > 3600000) { sessions.remove(id, value); throw ApiProblem(404, "not-found", "MCP session expired") }
        if (value.grant != p.tokenId) throw ApiProblem(403, "forbidden", "MCP session belongs to another grant")
        call.request.headers["MCP-Protocol-Version"]?.let { if (it != value.protocol) throw ApiProblem(400, "invalid-request", "MCP session protocol mismatch") }
        value.used = clock()
        return value
    }

    fun installMetadata(route: Route) {
        route.get("/.well-known/oauth-protected-resource/dulcet/v1/mcp") {
            val issuer = call.origin()
            call.send(j("resource" to "$issuer/dulcet/v1/mcp", "authorization_servers" to listOf(issuer), "bearer_methods_supported" to listOf("header"), "scopes_supported" to API_SCOPES.sorted()))
        }
    }

    fun install(route: Route) = with(route) {
        post("/mcp") {
            val p = authorized(call)
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) throw ApiProblem(415, "invalid-request", "MCP messages require application/json")
            val accept = call.request.headers[HttpHeaders.Accept]
            if (accept != null && accept != "*/*" && !(accept.contains("application/json") && accept.contains("text/event-stream"))) throw ApiProblem(406, "invalid-request", "Accept must include JSON and event-stream")
            val message = try { call.body() } catch (_: ApiProblem) { call.send(rpcError(JsonNull, -32700, "Parse error")); return@post }
            val id = message["id"] ?: JsonNull
            if (message["jsonrpc"] != JsonPrimitive("2.0") || (id != JsonNull && (id !is JsonPrimitive || (!id.isString && id.booleanOrNull != null)))) { call.send(rpcError(id, -32600, "Invalid Request")); return@post }
            val method = message["method"]?.jsonPrimitive?.contentOrNull
            if (method != "initialize") session(call, p)
            if (method == null) {
                if ("id" in message && ("result" in message || "error" in message)) call.respond(HttpStatusCode.Accepted)
                else call.send(rpcError(id, -32600, "Invalid Request"))
                return@post
            }
            if ("id" !in message) {
                if (method.startsWith("notifications/")) call.respond(HttpStatusCode.Accepted)
                else call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val params = message["params"] as? JsonObject ?: j()
            val result = when (method) {
                "initialize" -> {
                    if (call.request.headers["Mcp-Session-Id"] != null) session(call, p)
                    val proposed = params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: "2025-11-25"
                    val protocol = proposed.takeIf { it in protocols } ?: "2025-11-25"
                    sessions.entries.removeIf { clock() - it.value.used > 3600000 }
                    if (sessions.size >= 10000) throw ApiProblem(429, "rate-limited")
                    val newId = UUID.randomUUID().toString()
                    sessions[newId] = Session(p.tokenId, protocol, clock())
                    call.response.headers.append("Mcp-Session-Id", newId)
                    j("protocolVersion" to protocol, "capabilities" to j("tools" to j("listChanged" to false)), "serverInfo" to j("name" to "Dulcet Server", "version" to com.legitimateapps.dulcet.server.SERVER_VERSION))
                }
                "ping" -> j()
                "tools/list" -> j("tools" to tools(p))
                "tools/call" -> {
                    val name = params["name"]?.jsonPrimitive?.contentOrNull
                    val definition = definitions.firstOrNull { it["name"]!!.jsonPrimitive.content == name }
                    if (definition == null || !enabled(definition)) { call.send(rpcError(id, -32602, "Unknown tool")); return@post }
                    requireScopes(p, definition["x-dulcet-scope"]!!.jsonPrimitive.content)
                    val args = params["arguments"] as? JsonObject ?: if ("arguments" !in params) j() else null
                    if (args == null || !valid(args, definition["inputSchema"]!!.jsonObject)) { call.send(rpcError(id, -32602, "Arguments do not match the tool schema")); return@post }
                    try {
                        val value = execute(p, name!!, args)
                        val schema = definition["outputSchema"]!!.jsonObject
                        val output = project(value, schema).jsonObject
                        check(valid(output, schema)) { "MCP result violates its published schema" }
                        j("content" to listOf(j("type" to "text", "text" to output.toString())), "structuredContent" to output, "isError" to false)
                    } catch (error: ApiProblem) {
                        val problem = JsonObject(j("type" to "urn:dulcet:problem:${error.code}", "title" to error.code, "status" to error.status) + error.extra)
                        j("content" to listOf(j("type" to "text", "text" to problem.toString())), "isError" to true)
                    }
                }
                else -> { call.send(rpcError(id, -32601, "Method not found")); return@post }
            }
            call.send(j("jsonrpc" to "2.0", "id" to id, "result" to result))
        }
        get("/mcp") { val p = authorized(call); session(call, p); call.respond(HttpStatusCode.MethodNotAllowed) }
        delete("/mcp") { val p = authorized(call); session(call, p); sessions.remove(call.request.headers["Mcp-Session-Id"]); call.respond(HttpStatusCode.NoContent) }
    }

    private fun rpcError(id: JsonElement, code: Int, message: String) = j("jsonrpc" to "2.0", "id" to id, "error" to j("code" to code, "message" to message))

    private fun track(value: JsonObject): JsonObject = JsonObject(value + j("artist" to value["artistDisplay"], "album" to ((value["album"] as? JsonObject)?.get("title") ?: value["album"])))
    private fun catalog(p: Principal, type: String, id: String): JsonObject = ctx.apiCatalog.resource(type, id, p) ?: throw ApiProblem(404, "not-found")

    private suspend fun execute(p: Principal, name: String, args: JsonObject): JsonObject = when (name) {
        "search_discover" -> try {pageJson(ctx.discover.search(p.user.id,DiscoverKind.parse(args["kind"]!!.jsonPrimitive.content),args["query"]!!.jsonPrimitive.content,PageRequest(args["limit"]?.jsonPrimitive?.int ?: 20,args["cursor"]?.jsonPrimitive?.content)),::itemJson)}
            catch(e:UpstreamUnavailable){throw ApiProblem(503,"upstream-unavailable",extra=j("retryAfterSeconds" to ((e.retryAfterMillis ?: 60_000)+999)/1000))}
            catch(e:UpstreamNotFound){throw ApiProblem(404,"not-found")}
        "list_wishes" -> ctx.apiWishlist.page(p,false,args.mapValues {it.value.jsonPrimitive.content})
        "add_wish" -> ctx.wishlist.add(p.user.id,addWishInput(args,wishActionTime(args["actionTime"]?.jsonPrimitive?.content))).let {mutationJson(it.copy(wish=it.wish?.let(ctx.wishlist::present)))}
        "search_catalog" -> search(p, args)
        "get_artist" -> catalog(p, "artist", args["id"]!!.jsonPrimitive.content)
        "get_track" -> track(catalog(p, "track", args["id"]!!.jsonPrimitive.content))
        "get_album" -> {
            val id = args["id"]!!.jsonPrimitive.content
            val album = catalog(p, "album", id)
            val page = ctx.apiCatalog.page("track", p, mapOf("albumId" to id, "sort" to "album", "limit" to "100"), 100)
            JsonObject(album + j("artist" to album["artistDisplay"], "tracks" to page["items"]!!.jsonArray.map { track(it.jsonObject) }, "tracksNextCursor" to page["nextCursor"]))
        }
        "get_lyrics" -> {
            val id = args["trackId"]!!.jsonPrimitive.content
            catalog(p, "track", id)
            val requested = args["language"]?.jsonPrimitive?.content
            val document = ctx.lyrics.forTrack(id).orEmpty().firstOrNull { requested == null || it.language == requested } ?: throw ApiProblem(404, "not-found", "No matching lyrics")
            JsonObject(j("trackId" to id, "synced" to document.synced, "text" to document.lines.joinToString("\n") { it.text }) + (document.language?.let { j("language" to it) } ?: j()))
        }
        "get_queue" -> {
            val queue = ctx.apiQueue.getQueue(p)
            val entries = mutableListOf<JsonElement>()
            var next: String? = null
            do {
                val page = ctx.apiQueue.entries(p, 100, next)
                entries.addAll(page["items"]!!.jsonArray)
                next = page["nextCursor"]?.jsonPrimitive?.contentOrNull
            } while (next != null)
            val currentId = queue["currentEntryId"]?.jsonPrimitive?.contentOrNull
            val currentAt = entries.indexOfFirst { it.jsonObject["entryId"]?.jsonPrimitive?.content == currentId }
            fun entryTrack(entry: JsonElement): JsonElement = ctx.apiCatalog.resource("track", entry.jsonObject["itemId"]!!.jsonPrimitive.content, p)?.let(::track) ?: JsonNull
            j("activeDevice" to queue["activeDeviceId"], "state" to ctx.apiQueue.playback(p)["state"], "current" to entries.getOrNull(currentAt)?.let(::entryTrack), "upNext" to entries.drop(if (currentAt < 0) 0 else currentAt + 1).take(args["limit"]?.jsonPrimitive?.int ?: 20).map(::entryTrack).filter { it != JsonNull }, "entryCount" to queue["entryCount"])
        }
        "add_to_queue" -> {
            val result = ctx.apiQueue.insert(p, args)
            j("entryIds" to result["entryIds"], "entryCount" to result["queue"]!!.jsonObject["entryCount"])
        }
        "control_playback" -> {
            val result = ctx.apiQueue.command(p, JsonObject(args + j("id" to UUID.randomUUID().toString())))
            j("deviceId" to result["deviceId"], "accepted" to true)
        }
        else -> ctx.apiUserData.mcp(p, name, args)
    }

    private fun search(p: Principal, args: JsonObject): JsonObject {
        val params = mapOf("q" to args["query"]!!.jsonPrimitive.content, "limit" to "100", "scope" to (args["scope"]?.jsonPrimitive?.content ?: "all"))
        val types = args["types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("artist", "album", "track", "playlist")
        // Page through the same catalog filtering and visibility logic before combining result kinds.
        val rows = types.distinct().flatMap { type ->
            val values = mutableListOf<JsonObject>()
            var cursor: String? = null
            do {
                val page = ctx.apiCatalog.page(type, p, params + (cursor?.let { mapOf("cursor" to it) } ?: emptyMap()), 100)
                values += page["items"]!!.jsonArray.map { value -> val item = value.jsonObject; j("id" to item["id"], "type" to type, "name" to (item["title"] ?: item["name"])) }
                cursor = page["nextCursor"]?.jsonPrimitive?.contentOrNull
            } while (cursor != null)
            values
        }.sortedWith(compareBy({ fold(it["name"]!!.jsonPrimitive.content) }, { it["type"]!!.jsonPrimitive.content }, { it["id"]!!.jsonPrimitive.content }))
        val query = hash(JsonObject(args.filterKeys { it !in setOf("cursor", "limit") } + j("user" to p.user.id)).toString())
        val cursor = args["cursor"]?.jsonPrimitive?.content?.let(codec::decode)
        if (cursor != null && cursor["query"] != JsonPrimitive(query)) throw ApiProblem(400, "invalid-request", "Cursor query mismatch")
        val after = cursor?.get("key")?.jsonPrimitive?.content
        fun key(row: JsonObject) = fold(row["name"]!!.jsonPrimitive.content) + "\u0000" + row["type"]!!.jsonPrimitive.content + "\u0000" + row["id"]!!.jsonPrimitive.content
        val remaining = if (after == null) rows else rows.filter { key(it) > after }
        val limit = args["limit"]?.jsonPrimitive?.int ?: 20
        val selected = remaining.take(limit)
        return j("items" to selected, "nextCursor" to if (remaining.size > limit) codec.encode(j("query" to query, "key" to key(selected.last()))) else null)
    }

    /** Small JSON Schema evaluator for the published, closed v1 tool schemas. */
    fun valid(value: JsonElement, schema: JsonObject): Boolean {
        schema["anyOf"]?.jsonArray?.let { return it.any { option -> valid(value, option.jsonObject) } }
        val types = when (val type = schema["type"]) { is JsonPrimitive -> listOf(type.content); is JsonArray -> type.map { it.jsonPrimitive.content }; else -> emptyList() }
        fun typeMatches(type: String): Boolean = when (type) {
            "null" -> value == JsonNull
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value != JsonNull && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            else -> false
        }
        if (types.isNotEmpty() && types.none(::typeMatches)) return false
        if (value == JsonNull) return true
        schema["enum"]?.jsonArray?.let { if (value !in it) return false }
        if (value is JsonObject) {
            val properties = schema["properties"] as? JsonObject ?: j()
            if (schema["required"]?.jsonArray?.any { it.jsonPrimitive.content !in value } == true) return false
            if (schema["additionalProperties"] == JsonPrimitive(false) && value.keys.any { it !in properties }) return false
            if (value.any { (key, item) -> properties[key]?.let { !valid(item, it.jsonObject) } == true }) return false
        }
        if (value is JsonArray) {
            if (value.size < (schema["minItems"]?.jsonPrimitive?.int ?: 0) || value.size > (schema["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) return false
            if (schema["items"]?.let { itemSchema -> value.any { !valid(it, itemSchema.jsonObject) } } == true) return false
        }
        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            if (text.codePointCount(0,text.length) < (schema["minLength"]?.jsonPrimitive?.int ?: 0) || text.codePointCount(0,text.length) > (schema["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) return false
            when (schema["format"]?.jsonPrimitive?.content) {
                "date-time" -> if (runCatching { Instant.parse(text) }.isFailure) return false
                "uuid" -> if (runCatching { UUID.fromString(text).toString().equals(text, true) }.getOrDefault(false).not()) return false
            }
        }
        if (value is JsonPrimitive && !value.isString && value.doubleOrNull != null) {
            val n = value.double
            if (n < (schema["minimum"]?.jsonPrimitive?.double ?: Double.NEGATIVE_INFINITY) || n > (schema["maximum"]?.jsonPrimitive?.double ?: Double.POSITIVE_INFINITY)) return false
        }
        return true
    }

    private fun project(value: JsonElement, schema: JsonObject): JsonElement {
        schema["anyOf"]?.jsonArray?.let { options -> return project(value, options.firstOrNull { valid(project(value, it.jsonObject), it.jsonObject) }?.jsonObject ?: options.first().jsonObject) }
        if (value is JsonObject && schema["properties"] is JsonObject) {
            val properties = schema["properties"]!!.jsonObject
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            return JsonObject(value.mapNotNull { (name, item) -> properties[name]?.let { property -> val projected = project(item, property.jsonObject); if (name !in required && !valid(projected, property.jsonObject)) null else name to projected } }.toMap())
        }
        if (value is JsonArray && schema["items"] is JsonObject) return JsonArray(value.map { project(it, schema["items"]!!.jsonObject) })
        return value
    }
}
