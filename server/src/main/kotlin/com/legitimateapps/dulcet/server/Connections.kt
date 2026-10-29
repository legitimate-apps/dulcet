package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.api.*
import com.legitimateapps.dulcet.server.auth.User
import com.legitimateapps.dulcet.server.v1.ApiProblem
import com.legitimateapps.dulcet.server.v1.hash
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.*
import java.net.URI

/** An administrator's exact external TLS base, never inferred from a proxy scheme or arbitrary Host. */
data class ClientRoute(
    val id: String,
    val baseUrl: String,
    val role: String = "private",
    val priority: Int = 10,
    val label: String? = null,
    val audience: String = "all",
    val authAlias: Boolean = true,
) {
    val uri: URI = checkedBase(baseUrl, httpsOnly = true)
    val base: String = uri.toASCIIString().trimEnd('/')
    val prefix: String = uri.rawPath.orEmpty().trimEnd('/')
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid route ID" }
        require(role in setOf("local", "private", "public")) { "Invalid route role" }
        require(priority in 0..65535 && audience in setOf("all", "admin")) { "Invalid route policy" }
        require(label == null || (label.length <= 128 && label.none { it.code < 32 })) { "Invalid route label" }
    }
    fun visible(user: User) = audience == "all" || user.isAdmin
}

internal fun checkedBase(value: String, httpsOnly: Boolean): URI {
    require(value.length <= 2048 && value.none { it.code <= 32 || it == '\\' }) { "Invalid route base" }
    val uri = URI(value)
    require(uri.scheme in (if (httpsOnly) setOf("https") else setOf("http", "https")) && uri.host != null &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) { "Invalid route base" }
    val path = uri.rawPath.orEmpty()
    require(uri.host.trim('[', ']') !in setOf("0.0.0.0", "::") && !path.contains("//") &&
        path.split('/').none { it == "." || it == ".." } && !path.contains('%')) { "Invalid route base path" }
    return URI(uri.scheme, null, uri.host.lowercase(), uri.port.takeUnless { it == if(uri.scheme == "https") 443 else 80 } ?: -1, path.trimEnd('/'), null, null)
}

fun parseClientRoutes(text: String?): List<ClientRoute> {
    if (text == null) return emptyList()
    require(text.toByteArray().size <= 32768) { "Route configuration exceeds limit" }
    return Json.parseToJsonElement(text).jsonArray.map { element ->
        val obj = element.jsonObject
        fun text(name: String) = obj[name]?.jsonPrimitive?.content
        ClientRoute(text("id") ?: error("Route id required"), text("baseUrl") ?: error("Route baseUrl required"),
            text("role") ?: "private", obj["priority"]?.jsonPrimitive?.int ?: 10, text("label"),
            text("audience") ?: "all", obj["authAlias"]?.jsonPrimitive?.boolean ?: true)
    }
}
fun parseLegacyAnchors(text: String?): List<String> = text?.let {
    require(it.toByteArray().size <= 16384) { "Legacy anchors exceed limit" }
    Json.parseToJsonElement(it).jsonArray.map { value -> checkedBase(value.jsonPrimitive.content, false).toASCIIString() }
} ?: emptyList()

private val legacyDiscovery = AttributeKey<Boolean>("dulcet.legacy-discovery")
private val receivingRoute = AttributeKey<ClientRoute>("dulcet.receiving-route")
fun ApplicationCall.configuredRoute(): ClientRoute? = attributes.getOrNull(receivingRoute)

/** A durable configuration revision. No ownership row or account namespace is changed here. */
class Connections(private val ctx: ServerContext) {
    val routes = ctx.config.routes
    val revision: Long
    init {
        require(routes.size <= 8 && routes.map { it.id }.distinct().size == routes.size && routes.map { it.base }.distinct().size == routes.size) { "At most eight distinct routes are allowed" }
        require(ctx.config.legacyOwnershipAnchors.map { checkedBase(it, false).toASCIIString() }.distinct().size == ctx.config.legacyOwnershipAnchors.size) { "Legacy anchors must be distinct" }
        require(ctx.config.legacyOwnershipAnchors.size <= 8) { "At most eight legacy anchors are allowed" }
        val fingerprint = hash(routes.sortedBy { it.id }.joinToString("\n") { it.toString() } + "\n" + ctx.config.legacyOwnershipAnchors.sorted().joinToString("\n"))
        revision = ctx.db.write {
            val old = string("SELECT value FROM meta WHERE key='routes_fingerprint'")
            val current = string("SELECT value FROM meta WHERE key='routes_revision'")?.toLong() ?: 0
            val next = if (old == fingerprint) current else Math.addExact(current, 1)
            execute("INSERT OR REPLACE INTO meta(key,value) VALUES('routes_fingerprint',?)", fingerprint)
            execute("INSERT OR REPLACE INTO meta(key,value) VALUES('routes_revision',?)", next.toString())
            next
        }
        // Reserve room for the response envelope and authenticated principal at startup.
        val maximum = Envelope.root("ok").apply { put(directory(User("00000000-0000-0000-0000-000000000000", "principal", true))) }
        require(Envelope.render(maximum, Format.JSON, null).text.toByteArray().size <= 60 * 1024) { "Configured directory exceeds response limit" }
    }
    /** Runs before REST/OAuth/API authentication, and never trusts forwarded authority. Proxies preserve Host and prefix. */
    fun receive(call: ApplicationCall) {
        if (routes.isEmpty()) return
        val hostValues = call.request.headers.getAll(HttpHeaders.Host)
        if (hostValues?.size != 1) throw ApiProblem(421, "unrecognized-route")
        val authority = hostValues.single()
        val host = runCatching { URI("https://$authority") }.getOrNull()
        if (host?.host == null || host.rawUserInfo != null || !host.rawPath.isNullOrEmpty() || host.rawQuery != null || host.rawFragment != null) throw ApiProblem(421, "unrecognized-route")
        val path = call.request.path()
        // Deliberate compatibility for previously configured single-origin HTTP REST clients.
        // This metadata never becomes a route in the authenticated directory.
        if (call.request.local.scheme == "http" && path.substringAfterLast('/').removeSuffix(".view") != "getDulcetConnections" &&
            ctx.config.legacyOwnershipAnchors.any { anchor ->
                val legacy = checkedBase(anchor, false)
                val prefix = legacy.rawPath.orEmpty().trimEnd('/')
                legacy.scheme == "http" && legacy.host.equals(host.host, true) &&
                    (if(legacy.port < 0) 80 else legacy.port) == (if(host.port < 0) 80 else host.port) &&
                    (path.startsWith("$prefix/rest/") || path == "$prefix/.well-known/dulcet" || path == "/.well-known/dulcet")
            }) {
            if (path.endsWith("/.well-known/dulcet")) call.attributes.put(legacyDiscovery, true)
            return
        }
        // The numeric socket peer is the only authority allowed to carry an HTTP backend hop.
        if (call.request.local.scheme != "https" && !ctx.config.clientAddresses.isTrustedProxy(call.request.local.remoteAddress)) throw ApiProblem(421, "unrecognized-route")
        for (name in listOf("X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Forwarded-Prefix")) {
            val values = call.request.headers.getAll(name) ?: continue
            if (!ctx.config.clientAddresses.isTrustedProxy(call.request.local.remoteAddress) || values.size != 1) throw ApiProblem(421, "unrecognized-route")
            // These headers never select a route. Reject ambiguous/mismatched assertions, even from a proxy.
            when (name) {
                "X-Forwarded-Host" -> if (!values.single().equals(authority, true)) throw ApiProblem(421, "unrecognized-route")
                "X-Forwarded-Proto" -> if (values.single() != "https") throw ApiProblem(421, "unrecognized-route")
                "Forwarded" -> throw ApiProblem(421, "unrecognized-route")
            }
        }
        val route = routes.filter { it.uri.host.equals(host.host, true) && effectivePort(it.uri) == effectivePort(host) &&
            (it.prefix.isEmpty() || path == it.prefix || path.startsWith(it.prefix + "/")) }.maxByOrNull { it.prefix.length }
            ?: throw ApiProblem(421, "unrecognized-route")
        call.request.headers["X-Forwarded-Prefix"]?.let { if (it != route.prefix) throw ApiProblem(421, "unrecognized-route") }
        call.attributes.put(receivingRoute, route)
    }
    fun legacyDiscoveryDocument(call: ApplicationCall): JsonObject? = if (call.attributes.getOrNull(legacyDiscovery) == true)
        com.legitimateapps.dulcet.server.v1.j("standard" to "dulcet", "apis" to emptyList<String>(),
            "server" to com.legitimateapps.dulcet.server.v1.j("name" to "Dulcet Server", "version" to SERVER_VERSION, "instanceId" to ctx.journal.instanceId)) else null

    fun directory(user: User): Node = Node("dulcetConnections").apply {
        this["version"] = 1; this["revision"] = revision; this["instanceId"] = ctx.journal.instanceId
        child("principal") { this["id"] = user.id; this["username"] = user.name }
        emptyList("routes"); emptyList("authAliases")
        routes.filter { it.visible(user) }.forEach { route ->
            add("routes") { this["id"] = route.id; this["baseUrl"] = route.base; this["role"] = route.role; this["priority"] = route.priority; this["label"] = route.label }
            if (route.authAlias) add("authAliases") { this["routeId"] = route.id; this["issuer"] = route.base; this["tokenEndpoint"] = "${route.base}/oauth/token" }
        }
        // Node's scalar-list serializer omits empty arrays, so explicitly declare the empty member.
        if (ctx.config.legacyOwnershipAnchors.isEmpty()) emptyList("legacyOwnershipAnchors") else strings("legacyOwnershipAnchors", ctx.config.legacyOwnershipAnchors)
    }
    fun handlers(): Map<String, Handler> = if (routes.isEmpty()) emptyMap() else mapOf("getDulcetConnections" to { call ->
        if (call.ktor.configuredRoute()?.visible(call.user) != true) throw SubsonicError.notAuthorized()
        val response = Envelope.root("ok").apply { put(directory(call.user)) }
        check(Envelope.render(response, Format.JSON, null).text.toByteArray().size <= 65536) { "Directory exceeds wire limit" }
        Reply.Envelope(response)
    })
    private fun effectivePort(uri: URI) = if (uri.port < 0) 443 else uri.port
}
