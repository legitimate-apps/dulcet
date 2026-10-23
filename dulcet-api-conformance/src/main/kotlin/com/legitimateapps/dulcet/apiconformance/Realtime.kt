package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ---- Server-Sent Events (README "Events") ---------------------------------------------------

/** One event as the stream delivered it. */
class SseEvent(val id: String?, val type: String, val data: String, val receivedAt: Instant) {
    val json: JsonNode? by lazy { runCatching { com.legitimateapps.dulcet.apiconformance.json.readTree(data) }.getOrNull() }
}

/** The `components.schemas` name for each event type's `data` (openapi `/events`). */
val EVENT_SCHEMAS = mapOf(
    "changes" to "ChangesEvent", "queue" to "QueueEvent", "playback" to "PlaybackEvent", "command" to "CommandEvent",
    "nowPlaying" to "NowPlayingEvent", "scan" to "ScanEvent", "revoked" to "RevokedEvent", "heartbeat" to "HeartbeatEvent",
)

/**
 * An open `/events` stream read on its own thread. Every event of a known type has its `data`
 * validated against the contract; the violations join the owning check's when it ends.
 */
class EventStream(private val contract: Contract, val label: String, private val input: InputStream) : AutoCloseable {
    val received = CopyOnWriteArrayList<SseEvent>()
    val violations = CopyOnWriteArrayList<String>()
    @Volatile var ended = false
        private set

    private val reader = Thread({ read() }, "dulcet-conformance-events-$label").also { it.isDaemon = true; it.start() }

    private fun read() {
        try {
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { r ->
                var id: String? = null
                var type: String? = null
                val data = StringBuilder()
                var hasData = false
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty()) {
                        if (hasData) dispatch(SseEvent(id, type ?: "message", data.toString(), Instant.now()))
                        id = null; type = null; data.setLength(0); hasData = false
                        continue
                    }
                    if (line.startsWith(":")) continue
                    val field = line.substringBefore(':')
                    val value = if (line.contains(':')) line.substringAfter(':').removePrefix(" ") else ""
                    when (field) {
                        "id" -> id = value
                        "event" -> type = value
                        "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
                    }
                }
            }
        } catch (_: Exception) {
            // A closed or reset connection ends the stream; [ended] records it.
        } finally {
            ended = true
        }
    }

    private fun dispatch(event: SseEvent) {
        received += event
        val schema = EVENT_SCHEMAS[event.type] ?: return // an unknown type is allowed (open set)
        val node = event.json
        if (node == null) {
            violations += "$label: the data of a `${event.type}` event is not JSON"
            return
        }
        contract.validateComponent(schema, node).forEach { violations += "$label `${event.type}` event: $it" }
    }

    /** The index the next event will have, for [await] to look only at what arrives later. */
    fun mark(): Int = received.size

    /** The first event of [type] at or after [from] matching [predicate], waiting up to [timeoutMs]. */
    fun await(type: String, timeoutMs: Long, from: Int = 0, predicate: (SseEvent) -> Boolean = { true }): SseEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            received.drop(from).firstOrNull { it.type == type && predicate(it) }?.let { return it }
            if (ended) return received.drop(from).firstOrNull { it.type == type && predicate(it) }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(25)
        }
    }

    fun since(from: Int, type: String): List<SseEvent> = received.drop(from).filter { it.type == type }

    /** Waits up to [timeoutMs] for the server to end the stream. */
    fun awaitEnd(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!ended && System.currentTimeMillis() < deadline) Thread.sleep(25)
        return ended
    }

    override fun close() {
        runCatching { input.close() }
        reader.join(2000)
    }
}

/**
 * Opens `/events` as [token]. [deviceId] makes the stream count as that device being online.
 * Fails the check unless the server answers 200 `text/event-stream`.
 */
fun Ctx.openEvents(
    token: String? = tokenA,
    deviceId: String? = null,
    types: String? = null,
    lastEventId: String? = null,
    label: String = deviceId ?: "stream",
): EventStream {
    val opened = eventsRequest(token, deviceId, types, lastEventId)
    val stream = opened.stream ?: fail("${opened.exchange.describe()}: expected 200 text/event-stream for /events" +
        (opened.exchange.json?.str("type")?.let { " ($it)" } ?: ""))
    return EventStream(run.contract, label, stream).also { streams += it }
}

/** The raw `/events` request, for checks that expect a refusal. */
fun Ctx.eventsRequest(token: String?, deviceId: String? = null, types: String? = null, lastEventId: String? = null): Http.Opened {
    val headers = LinkedHashMap<String, String>()
    if (token != null) headers["Authorization"] = "Bearer $token"
    headers["Accept"] = "text/event-stream"
    deviceId?.let { headers["Dulcet-Device-Id"] = it }
    lastEventId?.let { headers["Last-Event-ID"] = it }
    val opened = run.http.openStream("streamEvents", url("/events", listOf("types" to types)), headers)
    opened.stream?.let { s -> if (opened.exchange.status != 200) runCatching { s.close() } }
    return opened
}

val Ctx.eventTimeoutMs: Long get() = config.eventTimeoutSeconds * 1000L
val Ctx.quietMs: Long get() = config.quietSeconds * 1000L

// ---- Webhooks (README "Webhooks", Standard Webhooks) ------------------------------------------

/** One request the receiver got. Header names are lower-cased. */
class Delivery(val path: String, val rawQuery: String?, val headers: Map<String, String>, val body: ByteArray, val receivedAt: Instant, val status: Int) {
    val text: String get() = String(body, StandardCharsets.UTF_8)
    val json: JsonNode? by lazy { runCatching { com.legitimateapps.dulcet.apiconformance.json.readTree(body) }.getOrNull() }
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * The suite's own webhook receiver: a plain HTTP listener on [listen] (`host:port`). Each webhook
 * the suite creates gets its own path, so deliveries are attributed exactly. [publicUrl] is the base
 * the server is given (for example an HTTPS proxy in front of this listener); without it the
 * listener's own URL is used.
 */
class WebhookListener(listen: String, publicUrl: String?, private val runId: String) : AutoCloseable {
    private val server: HttpServer
    val base: String
    private val counter = AtomicInteger()
    private val deliveries = CopyOnWriteArrayList<Delivery>()
    /** Scripted statuses per path, used in order; after they run out a path answers 204. */
    private val scripts = ConcurrentHashMap<String, ArrayDeque<Int>>()
    private val redirects = ConcurrentHashMap<String, String>()

    init {
        val host = listen.substringBeforeLast(':')
        val port = listen.substringAfterLast(':').toIntOrNull() ?: throw UsageError("--webhook-listen takes host:port")
        server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "dulcet-conformance-webhooks").also { it.isDaemon = true } }
        server.createContext("/") { ex ->
            val path = ex.requestURI.rawPath
            val body = ex.requestBody.readBytes()
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(", ") }
            val redirect = redirects[path]
            val status = when {
                redirect != null -> 307
                else -> synchronized(scripts) { scripts[path]?.removeFirstOrNull() } ?: 204
            }
            deliveries += Delivery(path, ex.requestURI.rawQuery, headers, body, Instant.now(), status)
            if (redirect != null) ex.responseHeaders.add("Location", base + redirect)
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
        val listenerBase = "http://${host.removeSurrounding("[", "]").let { if (it.contains(':')) "[$it]" else it }}:${server.address.port}"
        base = (publicUrl ?: listenerBase).trimEnd('/')
    }

    /** A fresh path for one webhook. */
    fun newPath(label: String): String = "/dulcet-conformance/$runId/${counter.incrementAndGet()}-$label"

    fun url(path: String): String = base + path

    /** Makes [path] answer these statuses to its next requests, in order. */
    fun script(path: String, vararg statuses: Int) { synchronized(scripts) { scripts.getOrPut(path) { ArrayDeque() }.addAll(statuses.toList()) } }

    /** Makes [path] answer 307 to [target] (another path on this receiver). */
    fun redirect(path: String, target: String) { redirects[path] = target }

    fun at(path: String): List<Delivery> = deliveries.filter { it.path == path }

    /** Waits up to [timeoutMs] for a delivery to [path] matching [predicate]. */
    fun await(path: String, timeoutMs: Long, predicate: (Delivery) -> Boolean = { true }): Delivery? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            at(path).firstOrNull(predicate)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(25)
        }
    }

    override fun close() { server.stop(0) }
}

/** Standard Webhooks signing: base64 HMAC-SHA256 over `id.timestamp.body` with the base64 key after `whsec_`. */
object WebhookSignature {
    fun sign(secret: String, id: String, timestamp: String, body: ByteArray): String {
        val key = Base64.getDecoder().decode(secret.removePrefix("whsec_"))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        mac.update("$id.$timestamp.".toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(mac.doFinal(body))
    }

    /** True when one of the space-separated `v1,<signature>` entries in [header] is the right one. */
    fun verify(secret: String, id: String, timestamp: String, body: ByteArray, header: String): Boolean {
        val expected = sign(secret, id, timestamp, body).toByteArray(StandardCharsets.US_ASCII)
        return header.split(' ').filter { it.startsWith("v1,") }.any {
            MessageDigest.isEqual(it.removePrefix("v1,").toByteArray(StandardCharsets.US_ASCII), expected)
        }
    }
}

// ---- MCP (README "AI and MCP") ----------------------------------------------------------------

const val MCP_PROTOCOL_VERSION = "2025-11-25"

/**
 * A minimal MCP client over Streamable HTTP. Each response is read whole, as JSON or as an SSE
 * stream of messages; tool results with `structuredContent` are validated against the tool's
 * published `outputSchema`, and departures join the check's violations.
 */
class McpClient(private val ctx: Ctx, val token: String?, private val origin: String? = null) {
    var sessionId: String? = null
    var protocolVersion: String? = null
    private var nextId = 1
    val url: String get() = ctx.run.apiBase + "/mcp"

    fun headers(extra: Map<String, String> = emptyMap(), session: String? = sessionId): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Accept"] = "application/json, text/event-stream"
        protocolVersion?.let { h["MCP-Protocol-Version"] = it }
        session?.let { h["Mcp-Session-Id"] = it }
        origin?.let { h["Origin"] = it }
        h.putAll(extra)
        return h
    }

    /** Sends one JSON-RPC message; returns the exchange and the response message with the same id, if any. */
    fun request(method: String, params: Any? = null, token: String? = this.token, session: String? = sessionId): Pair<Exchange, JsonNode?> {
        val id = nextId++
        val message = linkedMapOf<String, Any?>("jsonrpc" to "2.0", "id" to id, "method" to method)
        if (params != null) message["params"] = params
        val ex = ctx.send("POST", "mcpMessage", url, token, headers(session = session), message)
        return ex to responseMessage(ex, id)
    }

    fun notify(method: String): Exchange =
        ctx.send("POST", "mcpMessage", url, token, headers(), linkedMapOf("jsonrpc" to "2.0", "method" to method))

    private fun responseMessage(ex: Exchange, id: Int): JsonNode? {
        if (ex.status != 200) return null
        val messages = when (ex.contentType) {
            "application/json" -> listOfNotNull(ex.json)
            "text/event-stream" -> ex.text().split(Regex("\r?\n\r?\n")).mapNotNull { block ->
                val data = block.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
                if (data.isEmpty()) null else runCatching { json.readTree(data) }.getOrNull()
            }
            else -> emptyList()
        }
        return messages.flatMap { if (it.isArray) it.toList() else listOf(it) }.firstOrNull { it.path("id").asInt(-1) == id }
    }

    /** `initialize` then `notifications/initialized`; fails the check unless both succeed. */
    fun initialize(): JsonNode {
        val (ex, msg) = request("initialize", mapOf(
            "protocolVersion" to MCP_PROTOCOL_VERSION,
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "dulcet-api-conformance", "version" to SUITE_VERSION)))
        ctx.expectStatus(ex, 200, what = "MCP initialize")
        val result = msg?.path("result")?.takeIf { it.isObject } ?: ctx.fail("MCP initialize: no result (${msg?.path("error")})")
        protocolVersion = result.str("protocolVersion") ?: ctx.fail("MCP initialize: the result has no protocolVersion")
        sessionId = ex.header("Mcp-Session-Id")
        val ack = notify("notifications/initialized")
        ctx.expectStatus(ack, 202, what = "MCP notifications/initialized")
        return result
    }

    /** Every tool `tools/list` returns, following `nextCursor`. */
    fun listTools(): List<JsonNode> {
        val tools = mutableListOf<JsonNode>()
        var cursor: String? = null
        repeat(50) {
            val (ex, msg) = request("tools/list", cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>())
            ctx.expectStatus(ex, 200, what = "MCP tools/list")
            val result = msg?.path("result")?.takeIf { it.isObject } ?: ctx.fail("MCP tools/list: no result (${msg?.path("error")})")
            result.path("tools").forEach { tools.add(it) }
            cursor = result.str("nextCursor") ?: return tools
        }
        ctx.fail("MCP tools/list: still paging after 50 pages")
    }

    /** Calls a tool. A refusal is a JSON-RPC error, a result with `isError`, or an HTTP error status. */
    fun callTool(name: String, arguments: Map<String, Any?>): ToolResult {
        val (ex, msg) = request("tools/call", mapOf("name" to name, "arguments" to arguments))
        val result = msg?.path("result")?.takeIf { it.isObject }
        val refused = ex.status != 200 || msg == null || msg.has("error") || result == null || result.path("isError").asBoolean(false)
        val structured = result?.path("structuredContent")?.takeIf { it.isObject }
        if (!refused && structured != null) {
            val schema = McpTools.byName[name]?.path("outputSchema")
            if (schema != null && !schema.isMissingNode) {
                ctx.run.contract.validateStandalone(schema, structured).forEach { ctx.violations += "MCP $name structuredContent: $it" }
            }
        }
        return ToolResult(ex, msg, refused, structured)
    }

    class ToolResult(val exchange: Exchange, val message: JsonNode?, val refused: Boolean, val structured: JsonNode?)
}

/** The published tool definitions and the fields `tools/list` must return unchanged. */
object McpTools {
    val document: JsonNode by lazy { Contract.mcpTools() }
    val all: List<JsonNode> by lazy { document.path("tools").toList() }
    val byName: Map<String, JsonNode> by lazy { all.associateBy { it.path("name").asText() } }
    val compared = listOf("name", "title", "description", "inputSchema", "outputSchema", "annotations")

    fun scopeOf(tool: JsonNode): String = tool.path("x-dulcet-scope").asText()
    fun forScopes(scopes: Collection<String>, capabilities:JsonNode?=null): List<JsonNode> = all.filter { tool ->
        scopeOf(tool) in scopes && (!tool.has("x-dulcet-capability") || capabilities?.path(tool.path("x-dulcet-capability").asText())?.path("versions")?.any {it.asInt()==1}==true)
    }
}
