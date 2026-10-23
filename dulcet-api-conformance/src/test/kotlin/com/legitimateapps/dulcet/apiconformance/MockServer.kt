package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Deliberate defects. Each one breaks exactly one behaviour of the otherwise contract-valid mock, so
 * the self-tests can prove the check that covers it fails (CONFORMANCE.md "Negative controls").
 */
enum class Fault {
    HOME_DUPLICATE_ITEM,
    PLAYS_DOUBLE_COUNT,
    TOKEN_IN_QUERY_ACCEPTED,
    ECHO_QUERY_IN_ERROR,
    IGNORE_IF_NONE_MATCH,
    ETAG_IGNORES_USER_STATE,
    NO_REFRESH_RETRY_WINDOW,
    PLAIN_PKCE_ACCEPTED,
    REVOKE_KEEPS_MEDIA,
    PAT_SCOPE_ESCALATION,
    DELETE_WITHOUT_DELETE_SCOPE,
    IGNORE_IDEMPOTENCY_KEY,
    PLAIN_JSON_ERRORS,
    NO_RANGE,
    CORS_MISSING,
    SECRET_IN_URL,
    LOGS_SECRETS,
    DROP_TOMBSTONES,
    CURSOR_TYPES_UNBOUND,
    FOREIGN_CURSOR_ACCEPTED,
    HIDDEN_NOT_EMITTED,
    RETAG_NEW_ID,
    BAD_TRACK_SCHEMA,
    DEVICE_TOKEN_BEFORE_APPROVAL,
    HOOK_WITHOUT_TEST_MODE,
    HOOK_NON_ADMIN,
    AUTO_APPROVE_SKIPS_PKCE,
    TTL_IGNORED,
    RETENTION_IGNORED,
    RESTORE_KEEPS_INSTANCE,
    MERGE_NO_REPLACED_BY,
    RATE_LIMITS_HOOKS,
    TOKEN_LIFETIME_UNBOUNDED,
    STARTS_ON_SERVED_DATA,
    RESTART_KEEPS_OVERRIDES,
    NOW_PLAYING_CLEAR_HEADER_ONLY,
    STALE_QUEUE_WRITE_ACCEPTED,
    NON_ACTIVE_MOVES_POSITION,
    WEBHOOK_UNSIGNED,
    MCP_DELETE_WITHOUT_SCOPE,
    WEBHOOK_SURVIVES_REVOCATION,
    PRIVATE_TARGET_WITHOUT_OVERRIDE,
    WEBHOOK_TARGET_REFUSED_AS_400,
    WEBHOOK_SCOPE_FILTERED,
    WEBHOOK_RETRY_IGNORES_SCHEDULE,
    WEBHOOK_NEVER_DISABLED,
    WEBHOOK_DISABLED_ON_REVOCATION,
    TRUSTED_CLIENTS_IGNORED,
    MCP_TOOLS_WITHOUT_META,
    PRESENCE_NEEDS_STREAM,
    EVENTS_DEFAULT_REFUSES_UNREADABLE,
}

/** The mock's webhook backoff and auto-disable threshold when no test override is set. */
private val DEFAULT_RETRY_SCHEDULE = listOf(30L, 120L, 600L)
private const val DEFAULT_DISABLE_AFTER = 20

/**
 * A small in-memory Dulcet API server that follows the contract for everything the suite touches.
 * It exists only for the suite's self-tests: it is not a reference server and holds no real data.
 */
class MockServer(
    private val faults: Set<Fault> = emptySet(),
    private val refreshWindowSeconds: Int = 1,
    val libraryDir: Path? = null,
    val logFile: Path,
    val testMode: Boolean = true,
    private val heartbeatMillis: Long = 1000,
) : AutoCloseable {
    private val random = SecureRandom()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin: String = "http://127.0.0.1:${server.address.port}"
    private val base = "$origin/dulcet/v1"
    var instanceId: String = "inst-" + secret(6)
        private set

    private fun secret(bytes: Int): String {
        val b = ByteArray(bytes); random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private var counter = 0
    private fun newId(prefix: String) = "${prefix}_${++counter}"
    private var revCounter = 0L
    private fun newRev() = "$instanceId.${++revCounter}"
    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private fun ts(i: Instant?) = i?.toString()

    // ---- users and tokens --------------------------------------------------------------------

    class User(val id: String, val username: String, val roles: List<String>, var sourceIds: List<String>, val createdAt: Instant)
    class Pat(val id: String, val secret: String, val userId: String, val scopes: List<String>, val name: String, val parentId: String?, val createdAt: Instant, val expiresAt: Instant?) { var revoked = false }
    class OAuthGrant(val id: String, val userId: String, val scopes: List<String>, val createdAt: Instant) { var revoked = false }
    class Refresh(val grantId: String) { var rotatedAt: Instant? = null; var rotatedTo: Pair<String, String>? = null }
    class Code(val clientId: String, val redirectUri: String, val challenge: String, val method: String, val scopes: List<String>, val userId: String, val autoApproved: Boolean) { var used = false }

    /** Who a request acts as. [tokenId] is the personal token's id or the OAuth grant's id. */
    class Principal(val user: User, val tokenId: String, val kind: String, val scopes: List<String>)

    private val users = LinkedHashMap<String, User>()
    private val pats = LinkedHashMap<String, Pat>()
    private val grants = LinkedHashMap<String, OAuthGrant>()
    private val accessTokens = HashMap<String, String>()
    private val refreshTokens = HashMap<String, Refresh>()
    private val codes = HashMap<String, Code>()

    val tokenA: String
    val tokenB: String
    val adminToken: String

    // ---- catalog -----------------------------------------------------------------------------

    class Artist(val id: String, var name: String, var revision: String)
    class Album(val id: String, var title: String, val artistId: String, val source: String, var revision: String, val artworkId: String?, val addedAt: Instant, val releaseId: String? = null)
    class Track(val id: String, var title: String, var albumId: String, val artistId: String, val trackNumber: Int, var bytes: ByteArray, val source: String, var revision: String, val addedAt: Instant)

    private val sources = linkedMapOf("src_1" to "Main library", "src_2" to "Second library")
    private val artists = LinkedHashMap<String, Artist>()
    private val albums = LinkedHashMap<String, Album>()
    private val tracks = LinkedHashMap<String, Track>()
    private val pathIds = HashMap<Path, String>()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    // ---- user state ----------------------------------------------------------------------------

    class State {
        var favoritedAt: Instant? = null; var favAction: Instant? = null
        var rating: Int? = null; var ratingAction: Instant? = null
        var libraryAddedAt: Instant? = null; var libAction: Instant? = null
        var playCount = 0; var lastPlayedAt: Instant? = null
        var revision = ""
    }
    private val states = HashMap<Pair<String, String>, State>()
    private fun state(userId: String, itemId: String) = states.getOrPut(userId to itemId) { State() }

    class PlayRec(val id: String, val userId: String, val trackId: String, val body: JsonNode, val recordedAt: Instant) { var deleted = false }
    private val plays = LinkedHashMap<String, PlayRec>()

    class Entry(val playlistEntryId: String, val itemId: String, val addedAt: Instant)
    class Playlist(val id: String, val owner: String, var name: String, var description: String?, var visibility: String, val entries: MutableList<Entry>, var revision: String, val createdAt: Instant, var updatedAt: Instant) { var deleted = false }
    private val playlists = LinkedHashMap<String, Playlist>()

    private val nowPlaying = LinkedHashMap<Pair<String, String>, Map<String, Any?>>()
    private val idempotency = HashMap<Pair<String, String>, Pair<String, Resp>>()

    class MediaGrant(val tokenId: String, val kind: String, val id: String, val expiresAt: Instant)
    private val mediaGrants = HashMap<String, MediaGrant>()
    private val artworkUrls = HashMap<Triple<String, String, Int?>, Pair<String, Instant>>()
    private val scans = LinkedHashMap<String, Map<String, Any?>>()

    // ---- the change journal ------------------------------------------------------------------

    class Change(val seq: Long, val type: String, val id: String, val op: String, val user: String?, val source: String?, val resource: Any?, val reason: String?, val revision: String, val at: Instant, val replacedBy: List<String>? = null)
    private val journal = mutableListOf<Change>()
    private fun head(): Long = journal.lastOrNull()?.seq ?: 0
    private fun journal(type: String, id: String, op: String, user: String?, source: String?, resource: Any?, reason: String? = null, revision: String = newRev(), replacedBy: List<String>? = null) {
        journal += Change(head() + 1, type, id, op, user, source, resource, reason, revision, now(), replacedBy)
        compact()
        emit("changes", mapOf("cursor" to syncCursorHeader(), "types" to listOf(type))) { user == null || it.userId == user }
    }

    // ---- Testing profile ---------------------------------------------------------------------

    /** The overrides `PUT /admin/test/settings` set; `{}` is normal behaviour. */
    private var settings: ObjectNode = com.legitimateapps.dulcet.apiconformance.json.createObjectNode()
    /** Cursors at or below this sequence were compacted away and answer 410. */
    private var compactedThrough = 0L
    private val rateWindows = HashMap<String, Pair<Instant, Int>>()

    private fun compact() {
        val retention = settings.path("syncRetentionSeconds").takeIf { it.isInt }?.asLong() ?: return
        if (Fault.RETENTION_IGNORED in faults) return
        val cutoff = now().minusSeconds(retention)
        journal.lastOrNull { it.at < cutoff }?.let { compactedThrough = maxOf(compactedThrough, it.seq) }
    }

    private fun autoApproveUser(clientId: String?): String? =
        settings.path("autoApprove").firstOrNull { it.str("clientId") == clientId }?.str("userId")

    private class Checkpoint(val id: String, val createdAt: Instant, val instanceId: String, val restore: () -> Unit)
    private val checkpoints = HashMap<String, Checkpoint>()

    private fun testHook(req: Req, segs: List<String>): Resp {
        if (!testMode && Fault.HOOK_WITHOUT_TEST_MODE !in faults) halt(404, "not-found", "No such resource.")
        val p = auth(req)
        if (Fault.HOOK_NON_ADMIN !in faults && ("admin" !in p.scopes || "admin" !in p.user.roles)) halt(403, "permission-denied", "Test hooks need an admin.")
        val m = req.method
        return when {
            segs == listOf("admin", "test", "settings") && m == "GET" -> jsonResp(200, settings)
            segs == listOf("admin", "test", "settings") && m == "PUT" -> {
                val b = req.json as? ObjectNode ?: halt(400, "invalid-request", "A JSON object is required.")
                for (k in listOf("syncRetentionSeconds", "mediaUrlTtlSeconds", "artworkUrlTtlSeconds")) {
                    if (b.has(k) && (!b.path(k).isInt || b.path(k).asInt() < 1)) halt(400, "invalid-request", "$k must be a positive integer.")
                }
                val wh = b.path("webhooks")
                if (wh.has("retryScheduleSeconds") && (!wh.path("retryScheduleSeconds").isArray || wh.path("retryScheduleSeconds").any { !it.isInt || it.asInt() < 0 }))
                    halt(400, "invalid-request", "webhooks.retryScheduleSeconds must be an array of non-negative integers.")
                if (wh.has("disableAfterFailures") && (!wh.path("disableAfterFailures").isInt || wh.path("disableAfterFailures").asInt() < 1))
                    halt(400, "invalid-request", "webhooks.disableAfterFailures must be a positive integer.")
                if (b.path("trustedClients").any { it.str("client_id") == null || !it.path("redirect_uris").isArray })
                    halt(400, "invalid-request", "Each trusted client needs client_id and redirect_uris.")
                settings = b.deepCopy()
                rateWindows.clear()
                compact()
                jsonResp(200, settings)
            }
            segs == listOf("admin", "test", "checkpoints") && m == "POST" -> {
                val id = newId("ckpt")
                val savedPlaylists = playlists.mapValues { (_, pl) -> Playlist(pl.id, pl.owner, pl.name, pl.description, pl.visibility, pl.entries.toMutableList(), pl.revision, pl.createdAt, pl.updatedAt).also { it.deleted = pl.deleted } }
                val savedStates = states.mapValues { (_, st) -> State().also { c ->
                    c.favoritedAt = st.favoritedAt; c.favAction = st.favAction; c.rating = st.rating; c.ratingAction = st.ratingAction
                    c.libraryAddedAt = st.libraryAddedAt; c.libAction = st.libAction; c.playCount = st.playCount; c.lastPlayedAt = st.lastPlayedAt; c.revision = st.revision } }
                val savedPlays = plays.toMap()
                val ck = Checkpoint(id, now(), instanceId) {
                    playlists.clear(); playlists.putAll(savedPlaylists)
                    states.clear(); states.putAll(savedStates)
                    plays.clear(); plays.putAll(savedPlays)
                }
                checkpoints[id] = ck
                jsonResp(201, mapOf("id" to id, "createdAt" to ts(ck.createdAt), "instanceId" to ck.instanceId))
            }
            segs.size == 5 && segs[2] == "checkpoints" && segs[4] == "restore" && m == "POST" -> {
                val ck = checkpoints[segs[3]] ?: halt(404, "not-found", "No such checkpoint.")
                ck.restore()
                if (Fault.RESTORE_KEEPS_INSTANCE !in faults) {
                    // A restore is a new history: new instance id, and every revision (so every ETag) is new.
                    instanceId = "inst-" + secret(6)
                    artists.values.forEach { it.revision = newRev() }
                    albums.values.forEach { it.revision = newRev() }
                    tracks.values.forEach { it.revision = newRev() }
                    playlists.values.forEach { it.revision = newRev() }
                    states.values.forEach { it.revision = newRev() }
                }
                jsonResp(200, serverInfo())
            }
            else -> halt(404, "not-found", "No such resource.")
        }
    }

    /** Per-token rate limit from the `rateLimit` override; hooks are never limited. */
    private fun rateLimit(req: Req, hook: Boolean) {
        val rl = settings.path("rateLimit").takeIf { it.isObject } ?: return
        if (hook && Fault.RATE_LIMITS_HOOKS !in faults) return
        val key = req.header("Authorization") ?: return
        val window = rl.path("windowSeconds").asLong(60)
        val now = now()
        val (start, count) = rateWindows[key]?.takeIf { it.first.plusSeconds(window) > now } ?: (now to 0)
        if (count >= rl.path("requestsPerWindow").asInt(1)) {
            val retry = maxOf(1L, java.time.Duration.between(now, start.plusSeconds(window)).seconds)
            halt(429, "rate-limited", "Too many requests.", headers = mapOf("Retry-After" to retry.toString()))
        }
        rateWindows[key] = start to count + 1
    }

    // ---- devices, queue, events, webhooks, MCP (README §11-13) ---------------------------------
    // Declared before init: init's journal() calls already emit change events.

    class Device(val id: String, val userId: String, var name: String, var kind: String?, var canPlay: Boolean, var client: String?, var lastSeenAt: Instant)
    private val devices = LinkedHashMap<Pair<String, String>, Device>()
    class QEntry(val entryId: String, val itemId: String)
    class QueueState(var revision: String, var updatedAt: Instant) {
        val entries = mutableListOf<QEntry>()
        var currentEntryId: String? = null; var shuffle = false; var repeat = "off"; var activeDeviceId: String? = null
        var updatedBy: String? = null; var playback: Map<String, Any?>? = null
    }
    private val queues = HashMap<String, QueueState>()

    class Subscriber(val userId: String, val tokenId: String, val deviceId: String?, val types: Set<String>) {
        val outbox = java.util.concurrent.LinkedBlockingQueue<Pair<String, Any?>>()
        @Volatile var open = true
    }
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<Subscriber>()
    private val eventSeq = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false

    class Hook(val id: String, val userId: String, val tokenId: String, val url: String, val events: List<String>, val secret: String, val createdAt: Instant) {
        var active = true; var lastDeliveryAt: Instant? = null; var failures = 0; var disabledReason: String? = null
    }
    private val hooks = LinkedHashMap<String, Hook>()
    private val deliveryPool = java.util.concurrent.Executors.newScheduledThreadPool(2) { r -> Thread(r, "mock-webhooks").also { it.isDaemon = true } }
    private val outbound = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .connectTimeout(java.time.Duration.ofSeconds(5)).build()

    class McpSession(val id: String, val tokenId: String)
    private val mcpSessions = HashMap<String, McpSession>()

    init {
        val created = now()
        users["u_a"] = User("u_a", "conformance-a", listOf("user"), listOf("src_1", "src_2"), created)
        users["u_b"] = User("u_b", "conformance-b", listOf("user"), listOf("src_1", "src_2"), created)
        users["u_admin"] = User("u_admin", "conformance-admin", listOf("admin"), listOf("src_1", "src_2"), created)
        tokenA = bootstrapToken("u_a", REQUIRED_SCOPES)
        tokenB = bootstrapToken("u_b", listOf("catalog:read", "media:read", "user:read", "user:write", "user:delete", "plays:write"))
        adminToken = bootstrapToken("u_admin", listOf("admin", "catalog:read", "user:read"))
        val ar1 = addArtist("Aurora Tester")
        val ar2 = addArtist("Second Artist")
        val al1 = addAlbum("Alpha Tones", ar1, "src_1", "art_1")
        val al2 = addAlbum("Beta Noise", ar1, "src_1", null)
        val al3 = addAlbum("Gamma Source", ar2, "src_2", null)
        listOf("Alpha One", "Alpha Two", "Alpha Three").forEachIndexed { i, t -> addTrack(t, al1, ar1, i + 1, "src_1") }
        listOf("Beta One", "Beta Two", "Beta Three").forEachIndexed { i, t -> addTrack(t, al2, ar1, i + 1, "src_1") }
        listOf("Gamma One", "Gamma Two").forEachIndexed { i, t -> addTrack(t, al3, ar2, i + 1, "src_2") }
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "mock-http").also { it.isDaemon = true } }
        server.start()
    }

    private fun bootstrapToken(userId: String, scopes: List<String>): String {
        val s = "dulcet_pat_" + secret(24)
        val id = newId("tok")
        pats[id] = Pat(id, s, userId, scopes, "bootstrap", null, now(), null)
        return s
    }

    private fun addArtist(name: String) = Artist(newId("art"), name, newRev()).also {
        artists[it.id] = it
        journal("artist", it.id, "upsert", null, null, artistJson(it, null), revision = it.revision)
    }
    private fun addAlbum(title: String, artist: Artist, source: String, art: String?, releaseId: String? = null) = Album(newId("alb"), title, artist.id, source, newRev(), art, now(), releaseId).also {
        albums[it.id] = it
        journal("album", it.id, "upsert", null, source, albumJson(it, null), revision = it.revision)
    }
    private fun addTrack(title: String, album: Album, artist: Artist, n: Int, source: String, bytes: ByteArray? = null) =
        Track(newId("trk"), title, album.id, artist.id, n, bytes ?: LibraryFixture.mp3(title, artist.name, album.title, n), source, newRev(), now()).also {
            tracks[it.id] = it
            journal("track", it.id, "upsert", null, source, trackJson(it, null), revision = it.revision)
        }

    override fun close() {
        closed = true
        subscribers.forEach { it.open = false }
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        deliveryPool.shutdownNow()
    }

    // ---- HTTP plumbing -----------------------------------------------------------------------

    class Resp(val status: Int, val body: ByteArray = ByteArray(0), val contentType: String? = null, val headers: Map<String, String> = emptyMap())
    class Halt(val resp: Resp) : Exception()

    inner class Req(val ex: HttpExchange) {
        val method: String = ex.requestMethod
        val uri: URI = ex.requestURI
        val path: String = uri.rawPath
        val query: Map<String, String> = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
        }
        val body: ByteArray = ex.requestBody.readBytes()
        val json: JsonNode? by lazy { if (body.isEmpty()) null else runCatching { com.legitimateapps.dulcet.apiconformance.json.readTree(body) }.getOrNull() }
        fun header(name: String): String? = ex.requestHeaders.getFirst(name)
        val form: Map<String, String> by lazy {
            String(body, StandardCharsets.UTF_8).split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
            }
        }
    }

    private val lock = Any()

    private fun handle(ex: HttpExchange) {
        if (ex.requestMethod == "GET" && ex.requestURI.rawPath == "/dulcet/v1/events") return streamEvents(ex)
        send(ex, synchronized(lock) {
            val req = Req(ex)
            log(req)
            try { route(req) } catch (h: Halt) { h.resp } catch (e: Exception) {
                problem(500, "server-error", "The mock failed: ${e.javaClass.simpleName}")
            }
        })
    }

    private fun send(ex: HttpExchange, resp: Resp) {
        val headers = ex.responseHeaders
        if (Fault.CORS_MISSING !in faults) {
            headers.add("Access-Control-Allow-Origin", "*")
            headers.add("Access-Control-Expose-Headers", "ETag, Location, Retry-After, Dulcet-Sync-Cursor, Deprecation, Sunset")
        }
        resp.contentType?.let { headers.add("Content-Type", it) }
        resp.headers.forEach { (k, v) -> headers.add(k, v) }
        val noBody = resp.status == 204 || resp.status == 304 || resp.body.isEmpty()
        ex.sendResponseHeaders(resp.status, if (noBody) -1 else resp.body.size.toLong())
        if (!noBody) ex.responseBody.use { it.write(resp.body) } else ex.close()
    }

    private fun log(req: Req) {
        val line = if (Fault.LOGS_SECRETS in faults) {
            "${req.method} ${req.uri} authorization=${req.header("Authorization")} body=${String(req.body, StandardCharsets.UTF_8)}"
        } else {
            "${req.method} ${req.path.replace(Regex("/media/[^/]+"), "/media/***")}"
        }
        Files.writeString(logFile, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    private fun jsonResp(status: Int, value: Any?, headers: Map<String, String> = emptyMap()) =
        Resp(status, json.writeValueAsBytes(value), "application/json", headers)

    private fun problem(status: Int, type: String, title: String, extra: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()): Resp {
        val body = linkedMapOf<String, Any?>("type" to "urn:dulcet:problem:$type", "title" to title, "status" to status)
        body.putAll(extra)
        val ct = if (Fault.PLAIN_JSON_ERRORS in faults) "application/json" else "application/problem+json"
        return Resp(status, json.writeValueAsBytes(body), ct, headers)
    }

    private fun halt(status: Int, type: String, title: String, extra: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()): Nothing =
        throw Halt(problem(status, type, title, extra, headers))

    private fun unauthenticated(req: Req): Nothing {
        val extra = if (Fault.ECHO_QUERY_IN_ERROR in faults && req.uri.rawQuery != null) mapOf("detail" to "rejected request ?${req.query.entries.joinToString("&") { "${it.key}=${it.value}" }}") else emptyMap()
        halt(401, "unauthenticated", "No valid token.", extra, mapOf("WWW-Authenticate" to "Bearer resource_metadata=\"$origin/.well-known/oauth-protected-resource\""))
    }

    private fun principal(req: Req): Principal? {
        var token = req.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        if (token == null && Fault.TOKEN_IN_QUERY_ACCEPTED in faults) token = req.query["access_token"] ?: req.query["token"] ?: req.query["api_key"]
        token ?: return null
        pats.values.firstOrNull { it.secret == token }?.let { pat ->
            if (pat.revoked || (pat.expiresAt != null && pat.expiresAt < now())) return null
            return Principal(users[pat.userId]!!, pat.id, "personal", pat.scopes)
        }
        val grantId = accessTokens[token] ?: return null
        val grant = grants[grantId]!!
        if (grant.revoked) return null
        return Principal(users[grant.userId]!!, grant.id, "oauth", grant.scopes)
    }

    private fun auth(req: Req, scope: String? = null): Principal {
        val p = principal(req) ?: unauthenticated(req)
        req.header("Dulcet-Device-Id")?.let { d -> devices[p.user.id to d]?.lastSeenAt = now() }
        if (scope != null) requireScope(p, scope)
        return p
    }

    private fun requireScope(p: Principal, scope: String) {
        if (scope !in p.scopes) halt(403, "insufficient-scope", "The token does not hold the required scope.", mapOf("requiredScopes" to listOf(scope)))
    }

    private fun tokenAlive(tokenId: String): Boolean = pats[tokenId]?.let { !it.revoked } ?: grants[tokenId]?.let { !it.revoked } ?: false

    private fun revokeToken(tokenId: String) {
        pats[tokenId]?.revoked = true
        grants[tokenId]?.revoked = true
        pats.values.filter { it.parentId == tokenId && !it.revoked }.forEach { revokeToken(it.id) }
        subscribers.filter { it.tokenId == tokenId }.forEach { it.outbox.offer("revoked" to mapOf("reason" to "revoked")) }
        when {
            Fault.WEBHOOK_SURVIVES_REVOCATION in faults -> {}
            Fault.WEBHOOK_DISABLED_ON_REVOCATION in faults -> hooks.values.filter { it.tokenId == tokenId }.forEach { it.active = false; it.disabledReason = "revoked" }
            else -> hooks.values.removeIf { it.tokenId == tokenId }
        }
    }

    // ---- paging ----------------------------------------------------------------------------

    private fun limit(req: Req, max: Int = 500, default: Int = 100): Int {
        val raw = req.query["limit"] ?: return default
        val n = raw.toIntOrNull()
        if (n == null || n < 1 || n > max) halt(400, "invalid-request", "limit must be between 1 and $max.")
        return n
    }

    private fun <T> page(req: Req, items: List<T>, render: (T) -> Any?, extra: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()): Resp {
        val n = limit(req)
        val offset = req.query["cursor"]?.let { decodeOffset(it) } ?: 0
        val slice = items.drop(offset).take(n)
        val next = if (offset + n < items.size) "c_" + Base64.getUrlEncoder().withoutPadding().encodeToString("${offset + n}".toByteArray()) else null
        val body = linkedMapOf<String, Any?>("items" to slice.map(render), "nextCursor" to next, "total" to items.size)
        body.putAll(extra)
        return jsonResp(200, body, headers + ("Dulcet-Sync-Cursor" to syncCursorHeader()))
    }

    private fun decodeOffset(cursor: String): Int =
        runCatching { String(Base64.getUrlDecoder().decode(cursor.removePrefix("c_"))).toInt() }.getOrNull()
            ?: halt(400, "invalid-request", "Unreadable cursor.")

    // ---- representations ---------------------------------------------------------------------

    private fun stateJson(userId: String, itemId: String): Map<String, Any?> {
        val s = states[userId to itemId] ?: State()
        return linkedMapOf("favoritedAt" to ts(s.favoritedAt), "rating" to s.rating, "playCount" to s.playCount, "lastPlayedAt" to ts(s.lastPlayedAt), "libraryAddedAt" to ts(s.libraryAddedAt))
    }

    private fun withState(m: MutableMap<String, Any?>, user: Principal?, itemId: String): Map<String, Any?> {
        if (user != null && "user:read" in user.scopes) m["userState"] = stateJson(user.user.id, itemId)
        return m
    }

    private fun artistJson(a: Artist, p: Principal?) = withState(linkedMapOf("id" to a.id, "type" to "artist", "name" to a.name, "revision" to a.revision), p, a.id)

    private fun albumJson(a: Album, p: Principal?): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to a.id, "type" to "album", "title" to a.title,
            "artistDisplay" to artists[a.artistId]?.name, "trackCount" to tracks.values.count { it.albumId == a.id },
            "addedAt" to ts(a.addedAt), "revision" to a.revision)
        if (a.artworkId != null) m["artwork"] = mapOf("id" to a.artworkId, "width" to 1, "height" to 1)
        if (a.releaseId != null) m["externalIds"] = mapOf("musicbrainzReleaseId" to a.releaseId)
        return withState(m, p, a.id)
    }

    private fun trackJson(t: Track, p: Principal?, detail: Boolean = false): Map<String, Any?> {
        val album = albums[t.albumId]
        val m = linkedMapOf<String, Any?>("id" to t.id, "type" to "track", "title" to t.title,
            "album" to mapOf("id" to t.albumId, "title" to (album?.title ?: "")),
            "artistDisplay" to artists[t.artistId]?.name, "discNumber" to 1, "trackNumber" to t.trackNumber,
            "durationMs" to if (detail && Fault.BAD_TRACK_SCHEMA in faults) "1045" else 1045,
            "audio" to mapOf("codec" to "mp3", "container" to "mp3", "bitrate" to 128000, "sampleRate" to 44100, "channels" to 1, "sizeBytes" to t.bytes.size.toLong(), "lossless" to false),
            "addedAt" to ts(t.addedAt), "revision" to t.revision)
        return withState(m, p, t.id)
    }

    private fun itemType(id: String): String? = when {
        tracks.containsKey(id) -> "track"
        albums.containsKey(id) -> "album"
        artists.containsKey(id) -> "artist"
        else -> null
    }
    private fun itemName(id: String): String = tracks[id]?.title ?: albums[id]?.title ?: artists[id]?.name ?: id

    private fun itemStateJson(userId: String, itemId: String, applied: Boolean = true): Map<String, Any?> {
        val s = state(userId, itemId)
        val m = linkedMapOf<String, Any?>("itemId" to itemId, "itemType" to (itemType(itemId) ?: "track"), "revision" to s.revision.ifEmpty { "$instanceId.0" }, "applied" to applied)
        m.putAll(stateJson(userId, itemId))
        return m
    }

    private fun stateChanged(userId: String, itemId: String) {
        val s = state(userId, itemId)
        s.revision = newRev()
        journal("itemState", itemId, "upsert", userId, null, itemStateJson(userId, itemId).minus("applied"), revision = s.revision)
    }

    private fun playlistJson(pl: Playlist, p: Principal): Map<String, Any?> = linkedMapOf(
        "id" to pl.id, "type" to "playlist", "name" to pl.name, "description" to pl.description,
        "owner" to mapOf("id" to pl.owner, "name" to users[pl.owner]!!.username), "visibility" to pl.visibility,
        "readOnly" to (pl.owner != p.user.id), "itemCount" to pl.entries.size, "durationMs" to pl.entries.size * 1045,
        "createdAt" to ts(pl.createdAt), "updatedAt" to ts(pl.updatedAt), "revision" to pl.revision,
    ).filterValues { it != null }

    private fun visibleTrack(t: Track, p: Principal) = t.source in p.user.sourceIds
    private fun visibleAlbum(a: Album, p: Principal) = a.source in p.user.sourceIds

    private fun etagFor(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
        return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(22) + "\""
    }

    private fun conditional(req: Req, etag: String, body: Any?, extra: Map<String, String> = emptyMap()): Resp {
        val headers = mapOf("ETag" to etag, "Cache-Control" to "private", "Vary" to "Authorization", "Dulcet-Sync-Cursor" to syncCursorHeader()) + extra
        if (Fault.IGNORE_IF_NONE_MATCH !in faults && req.header("If-None-Match") == etag) return Resp(304, headers = headers)
        return jsonResp(200, body, headers)
    }

    // ---- routing ---------------------------------------------------------------------------

    private fun route(req: Req): Resp {
        val path = req.path
        if (req.method == "OPTIONS") {
            if (Fault.CORS_MISSING in faults) return Resp(204)
            return Resp(204, headers = mapOf(
                "Access-Control-Allow-Methods" to "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "Authorization, Content-Type, Idempotency-Key, If-Match, If-None-Match, Last-Event-ID, Dulcet-Device-Id, Dulcet-Action-Time",
                "Access-Control-Max-Age" to "600"))
        }
        when (path) {
            // Stand-ins for process control, which is outside the API: the self-tests' start and restart commands call these.
            "/__mock/restart" -> {
                if (Fault.RESTART_KEEPS_OVERRIDES !in faults) { settings = com.legitimateapps.dulcet.apiconformance.json.createObjectNode(); rateWindows.clear() }
                return Resp(204)
            }
            "/__mock/start-on-served-data" ->
                return if (Fault.STARTS_ON_SERVED_DATA in faults) Resp(204) else problem(409, "conflict", "Refusing to start in test mode on data served without it.")
            "/.well-known/dulcet" -> return jsonResp(200, mapOf(
                "standard" to "dulcet",
                "apis" to listOf(mapOf("version" to "1", "baseUrl" to base, "status" to "stable")),
                "server" to serverInfo(), "authorizationServer" to origin, "openapi" to "$base/openapi.yaml", "testMode" to testMode))
            "/.well-known/oauth-authorization-server" -> return jsonResp(200, mapOf(
                "issuer" to origin, "authorization_endpoint" to "$origin/oauth/authorize", "token_endpoint" to "$origin/oauth/token",
                "revocation_endpoint" to "$origin/oauth/revoke", "device_authorization_endpoint" to "$origin/oauth/device_authorization", "response_types_supported" to listOf("code"),
                "grant_types_supported" to listOf("authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:device_code"),
                "code_challenge_methods_supported" to listOf("S256"), "token_endpoint_auth_methods_supported" to listOf("none"),
                "client_id_metadata_document_supported" to true, "authorization_response_iss_parameter_supported" to true,
                "scopes_supported" to REQUIRED_SCOPES + listOf("admin", "offline_access")))
            "/.well-known/oauth-protected-resource" -> return jsonResp(200, mapOf(
                "resource" to base, "authorization_servers" to listOf(origin), "bearer_methods_supported" to listOf("header")))
            "/.well-known/oauth-protected-resource/dulcet/v1/mcp" -> return jsonResp(200, mapOf(
                "resource" to "$base/mcp", "authorization_servers" to listOf(origin), "bearer_methods_supported" to listOf("header"),
                "scopes_supported" to listOf("catalog:read", "user:read", "user:write", "user:delete")))
            "/oauth/authorize" -> return authorize(req)
            "/oauth/token" -> return token(req)
            "/oauth/revoke" -> return revoke(req)
            "/oauth/device_authorization" -> return deviceAuthorization(req)
        }
        if (!path.startsWith("/dulcet/v1/")) halt(404, "not-found", "No such resource.")
        val segs = path.removePrefix("/dulcet/v1/").split('/').map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        val m = req.method
        val hook = segs.size >= 3 && segs[0] == "admin" && segs[1] == "test"
        rateLimit(req, hook)
        if (hook) return testHook(req, segs)
        return when {
            segs == listOf("recommendations", "home") && m == "GET" -> {
                val p = auth(req, "catalog:read"); requireScope(p, "user:read")
                val limit = req.query["limit"]?.toIntOrNull() ?: 20
                if (limit !in 1..50) halt(400,"invalid-request","Invalid limit")
                val items = albums.values.filter { it.source in p.user.sourceIds }.take(limit).map { albumJson(it,p) }
                val shown = if (Fault.HOME_DUPLICATE_ITEM in faults && items.isNotEmpty()) items + items.first() else items
                jsonResp(200, mapOf("shelves" to listOf(mapOf("id" to "newest", "title" to "New", "reason" to "Recently added", "items" to shown))))
            }
            segs == listOf("capabilities") && m == "GET" -> capabilities(req)
            segs == listOf("me") && m == "GET" -> me(req)
            segs == listOf("me", "tokens") && m == "GET" -> listTokens(req)
            segs == listOf("me", "tokens") && m == "POST" -> createToken(req)
            segs.size == 3 && segs[0] == "me" && segs[1] == "tokens" && m == "DELETE" -> deleteToken(req, segs[2])
            segs == listOf("me", "password") && m == "POST" -> { auth(req, "account"); halt(403, "permission-denied", "Wrong password.") }
            segs == listOf("sources") && m == "GET" -> { val p = auth(req, "catalog:read"); page(req, sources.entries.filter { it.key in p.user.sourceIds }.toList(), { mapOf("id" to it.key, "type" to "source", "name" to it.value) }) }
            segs == listOf("artists") && m == "GET" -> listArtists(req)
            segs == listOf("albums") && m == "GET" -> listAlbums(req)
            segs == listOf("tracks") && m == "GET" -> listTracks(req)
            segs.size == 2 && segs[0] == "artists" && m == "GET" -> getArtist(req, segs[1])
            segs.size == 2 && segs[0] == "albums" && m == "GET" -> getAlbum(req, segs[1])
            segs.size == 2 && segs[0] == "tracks" && m == "GET" -> getTrack(req, segs[1])
            segs == listOf("search") && m == "GET" -> search(req)
            segs.size == 2 && segs[0] == "artwork" && m == "GET" -> artwork(req, segs[1])
            segs == listOf("playback", "decisions") && m == "POST" -> decision(req)
            segs == listOf("media", "urls") && m == "POST" -> mediaUrls(req)
            segs.size == 2 && segs[0] == "media" && m == "GET" -> media(req, segs[1])
            segs == listOf("me", "library") && m == "GET" -> listLibrary(req)
            segs.size == 3 && segs[0] == "me" && segs[1] == "library" -> stateWrite(req, "library", segs[2])
            segs == listOf("me", "favorites") && m == "GET" -> listFavorites(req)
            segs.size == 3 && segs[0] == "me" && segs[1] == "favorites" -> stateWrite(req, "favorite", segs[2])
            segs.size == 3 && segs[0] == "me" && segs[1] == "ratings" -> stateWrite(req, "rating", segs[2])
            segs == listOf("me", "batch") && m == "POST" -> batch(req)
            segs == listOf("me", "plays") && m == "GET" -> listPlays(req)
            segs == listOf("me", "plays") && m == "POST" -> submitPlays(req)
            segs.size == 3 && segs[0] == "me" && segs[1] == "plays" && m == "DELETE" -> deletePlay(req, segs[2])
            segs == listOf("me", "now-playing") -> nowPlayingWrite(req)
            segs == listOf("now-playing") && m == "GET" -> listNowPlaying(req)
            segs == listOf("playlists") && m == "GET" -> listPlaylists(req)
            segs == listOf("playlists") && m == "POST" -> createPlaylist(req)
            segs.size == 2 && segs[0] == "playlists" -> playlist(req, segs[1])
            segs.size == 3 && segs[0] == "playlists" && segs[2] == "items" -> playlistItems(req, segs[1])
            segs.size == 4 && segs[0] == "playlists" && segs[2] == "items" -> playlistEntry(req, segs[1], segs[3])
            segs == listOf("sync", "changes") && m == "GET" -> changes(req)
            segs.size == 2 && segs[0] == "users" && m == "PATCH" -> updateUser(req, segs[1])
            segs == listOf("admin", "scans") && m == "POST" -> startScan(req)
            segs == listOf("me", "devices") && m == "GET" -> listDevices(req)
            segs.size == 3 && segs[0] == "me" && segs[1] == "devices" -> device(req, segs[2])
            segs == listOf("me", "queue") -> queueRoot(req)
            segs == listOf("me", "queue", "entries") -> queueEntries(req)
            segs.size == 4 && segs[0] == "me" && segs[1] == "queue" && segs[2] == "entries" && m == "DELETE" -> removeQueueEntry(req, segs[3])
            segs == listOf("me", "queue", "playback") -> queuePlayback(req)
            segs == listOf("me", "queue", "transfer") && m == "POST" -> transfer(req)
            segs == listOf("me", "queue", "commands") && m == "POST" -> command(req)
            segs == listOf("webhooks") && m == "GET" -> { val p = auth(req, "account"); page(req, hooks.values.filter { it.userId == p.user.id }, { hookJson(it) }) }
            segs == listOf("webhooks") && m == "POST" -> createWebhook(req)
            segs.size == 2 && segs[0] == "webhooks" -> webhook(req, segs[1])
            segs.size == 3 && segs[0] == "webhooks" && segs[2] == "test" && m == "POST" -> testWebhook(req, segs[1])
            segs == listOf("mcp") -> mcp(req)
            segs.size == 3 && segs[0] == "admin" && segs[1] == "scans" && m == "GET" -> getScan(req, segs[2])
            else -> halt(404, "not-found", "No such resource.")
        }
    }

    private fun serverInfo() = mapOf("name" to "Dulcet conformance mock", "version" to "0", "instanceId" to instanceId)

    // ---- discovery and account ---------------------------------------------------------------

    private fun capabilities(req: Req): Resp {
        val body = linkedMapOf<String, Any?>(
            "apiVersion" to "1.0", "server" to serverInfo(),
            "capabilities" to mapOf("dulcet.sync" to mapOf("versions" to listOf(1)), "dulcet.home" to mapOf("versions" to listOf(1))),
            "limits" to mapOf("maxPageSize" to 500, "maxBatchSize" to 500, "syncRetentionDays" to 90, "maxTokenLifetimeDays" to 365, "mediaUrlTtlSeconds" to 21600, "artworkUrlTtlSeconds" to 604800),
            "auth" to mapOf("flows" to listOf("authorization_code", "refresh_token"), "scopes" to REQUIRED_SCOPES + listOf("admin", "offline_access")))
        if (req.header("Authorization") != null) {
            val p = auth(req)
            body["permissions"] = mapOf("scopes" to p.scopes, "download" to true, "share" to true, "admin" to ("admin" in p.user.roles))
        }
        return jsonResp(200, body)
    }

    private fun userJson(u: User) = linkedMapOf<String, Any?>("id" to u.id, "username" to u.username, "roles" to u.roles,
        "permissions" to mapOf("download" to true, "share" to true), "sourceIds" to u.sourceIds, "createdAt" to ts(u.createdAt))

    private fun me(req: Req): Resp {
        val p = auth(req)
        val body = userJson(p.user)
        body["token"] = mapOf("id" to p.tokenId, "kind" to p.kind, "scopes" to p.scopes)
        return jsonResp(200, body)
    }

    private fun tokenJson(t: Pat) = linkedMapOf<String, Any?>("id" to t.id, "kind" to "personal", "name" to t.name, "scopes" to t.scopes,
        "createdAt" to ts(t.createdAt), "expiresAt" to ts(t.expiresAt), "hint" to t.secret.take(14), "parentId" to t.parentId)

    private fun listTokens(req: Req): Resp {
        val p = auth(req, "account")
        return page(req, pats.values.filter { it.userId == p.user.id && !it.revoked }, { tokenJson(it) })
    }

    private fun createToken(req: Req): Resp {
        val p = auth(req, "account")
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val name = b.str("name")?.takeIf { it.isNotEmpty() } ?: halt(400, "invalid-request", "name is required.")
        val scopes = b.path("scopes").map { it.asText() }
        if (scopes.isEmpty()) halt(400, "invalid-request", "scopes is required.")
        val above = scopes.filter { it !in p.scopes }
        if (above.isNotEmpty() && Fault.PAT_SCOPE_ESCALATION !in faults) halt(403, "insufficient-scope", "A token cannot hold scopes its creator lacks.", mapOf("requiredScopes" to above))
        val days = b.path("expiresInDays")
        if (!b.path("noExpiry").asBoolean(false) && (!days.isInt || days.asInt() < 1)) halt(400, "invalid-request", "expiresInDays must be a positive integer.")
        if (days.isInt && days.asInt() > 365 && Fault.TOKEN_LIFETIME_UNBOUNDED !in faults) halt(422, "token-lifetime-too-long", "expiresInDays is above limits.maxTokenLifetimeDays (365).")
        val id = newId("tok")
        val s = "dulcet_pat_" + secret(24)
        val pat = Pat(id, s, p.user.id, scopes, name, p.tokenId, now(), if (days.isInt) now().plus(days.asLong(), ChronoUnit.DAYS) else null)
        pats[id] = pat
        val body = tokenJson(pat); body["secret"] = s
        return jsonResp(201, body, mapOf("Location" to "$base/me/tokens/$id"))
    }

    private fun deleteToken(req: Req, id: String): Resp {
        val p = auth(req, "account")
        val target = pats[id]?.takeIf { it.userId == p.user.id } ?: grants[id]?.takeIf { it.userId == p.user.id }
        if (target != null) revokeToken(id)
        return Resp(204)
    }

    // ---- OAuth -----------------------------------------------------------------------------

    private fun authorize(req: Req): Resp {
        val q = req.query
        val redirect = q["redirect_uri"] ?: halt(400, "invalid-request", "redirect_uri is required.")
        val trusted = settings.path("trustedClients").firstOrNull { it.str("client_id") == q["client_id"] }?.takeIf { Fault.TRUSTED_CLIENTS_IGNORED !in faults }
        if (trusted != null) {
            if (trusted.path("redirect_uris").none { it.asText() == redirect }) halt(400, "invalid-request", "redirect_uri is not listed in the client's metadata.")
        } else if (!redirect.startsWith("http://127.0.0.1:") && !redirect.startsWith("http://[::1]:")) halt(400, "invalid-request", "An unverified client may use only a loopback redirect.")
        val state = q["state"] ?: ""
        fun back(params: Map<String, String>): Resp {
            val all = params + mapOf("state" to state, "iss" to origin)
            return Resp(302, headers = mapOf("Location" to redirect + "?" + Http.query(all.toList())))
        }
        val method = q["code_challenge_method"]
        // Without a person, only an autoApprove entry for this client signs anyone in.
        val autoUser = autoApproveUser(q["client_id"])
            ?: return Resp(200, "<!doctype html><title>Sign in</title><form method=post><input name=username><input name=password type=password></form>".toByteArray(), "text/html; charset=utf-8")
        val skipPkce = Fault.AUTO_APPROVE_SKIPS_PKCE in faults
        val methodOk = method == "S256" || (method == "plain" && (Fault.PLAIN_PKCE_ACCEPTED in faults || skipPkce))
        if (q["response_type"] != "code" || q["code_challenge"].isNullOrEmpty() || !methodOk) return back(mapOf("error" to "invalid_request"))
        val asked = (q["scope"] ?: "").split(' ').filter { it.isNotEmpty() }
        val scopes = if (trusted != null) asked.filter { it != "admin" || "admin" in users[autoUser]!!.roles } else asked.filter { it != "account" && it != "admin" }
        val code = "dulcet_ac_" + secret(18)
        codes[code] = Code(q["client_id"] ?: "", redirect, q["code_challenge"]!!, method!!, scopes, autoUser, autoApproved = true)
        return back(mapOf("code" to code))
    }

    private fun oauthError(code: String, description: String) = jsonResp(400, mapOf("error" to code, "error_description" to description), mapOf("Cache-Control" to "no-store"))

    private fun issue(grant: OAuthGrant): Pair<String, String?> {
        val access = "dulcet_at_" + secret(24)
        accessTokens[access] = grant.id
        val refresh = if ("offline_access" in grant.scopes) "dulcet_rt_" + secret(24) else null
        refresh?.let { refreshTokens[it] = Refresh(grant.id) }
        return access to refresh
    }

    private fun tokenResponse(grant: OAuthGrant, pair: Pair<String, String?>): Resp {
        val body = linkedMapOf<String, Any?>("access_token" to pair.first, "token_type" to "Bearer", "expires_in" to 3600, "scope" to grant.scopes.joinToString(" "))
        pair.second?.let { body["refresh_token"] = it }
        return jsonResp(200, body, mapOf("Cache-Control" to "no-store"))
    }

    private fun token(req: Req): Resp {
        val f = req.form
        return when (f["grant_type"]) {
            "authorization_code" -> {
                val code = codes[f["code"]] ?: return oauthError("invalid_grant", "Unknown code.")
                if (code.used) return oauthError("invalid_grant", "The code was already used.")
                code.used = true
                if (f["redirect_uri"] != code.redirectUri) return oauthError("invalid_grant", "redirect_uri does not match.")
                val skipPkce = code.autoApproved && Fault.AUTO_APPROVE_SKIPS_PKCE in faults
                if (!skipPkce) {
                    val verifier = f["code_verifier"] ?: return oauthError("invalid_grant", "code_verifier is required.")
                    val computed = if (code.method == "plain") verifier else Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
                    if (computed != code.challenge) return oauthError("invalid_grant", "PKCE verification failed.")
                }
                val grant = OAuthGrant(newId("grant"), code.userId, code.scopes, now())
                grants[grant.id] = grant
                tokenResponse(grant, issue(grant))
            }
            "refresh_token" -> {
                val rec = refreshTokens[f["refresh_token"]] ?: return oauthError("invalid_grant", "Unknown refresh token.")
                val grant = grants[rec.grantId]!!
                if (grant.revoked) return oauthError("invalid_grant", "The grant was revoked.")
                val rotated = rec.rotatedTo
                if (rotated == null) {
                    val pair = issue(grant)
                    rec.rotatedTo = pair.first to pair.second!!
                    rec.rotatedAt = now()
                    tokenResponse(grant, pair)
                } else {
                    val inWindow = rec.rotatedAt!!.plusSeconds(refreshWindowSeconds.toLong()) > now()
                    if (inWindow && Fault.NO_REFRESH_RETRY_WINDOW !in faults) tokenResponse(grant, rotated)
                    else { revokeToken(grant.id); oauthError("invalid_grant", "A rotated refresh token was replayed; the grant is revoked.") }
                }
            }
            "urn:ietf:params:oauth:grant-type:device_code" -> {
                val d = deviceCodes[f["device_code"]] ?: return oauthError("invalid_grant", "Unknown device code.")
                if (d.approvedUser == null && Fault.DEVICE_TOKEN_BEFORE_APPROVAL !in faults) return oauthError("authorization_pending", "Nobody has approved this code yet.")
                deviceCodes.remove(f["device_code"])
                val grant = OAuthGrant(newId("grant"), d.approvedUser ?: "u_a", d.scopes.filter { it != "account" && it != "admin" }, now())
                grants[grant.id] = grant
                tokenResponse(grant, issue(grant))
            }
            else -> oauthError("unsupported_grant_type", "Unsupported grant type.")
        }
    }

    /** Device codes, by code. Only autoApprove approves one (at once): the mock has no person. */
    private class DeviceCode(val scopes: List<String>, val approvedUser: String?)
    private val deviceCodes = HashMap<String, DeviceCode>()

    private fun deviceAuthorization(req: Req): Resp {
        if (req.form["client_id"].isNullOrEmpty()) return oauthError("invalid_request", "client_id is required.")
        val code = "dulcet_dc_" + secret(24)
        deviceCodes[code] = DeviceCode((req.form["scope"] ?: "").split(' ').filter { it.isNotEmpty() }, autoApproveUser(req.form["client_id"]))
        return jsonResp(200, mapOf("device_code" to code, "user_code" to "WDJB-MJHT", "verification_uri" to "$origin/device",
            "verification_uri_complete" to "$origin/device?user_code=WDJB-MJHT", "expires_in" to 900, "interval" to 1), mapOf("Cache-Control" to "no-store"))
    }

    private fun revoke(req: Req): Resp {
        val t = req.form["token"] ?: return oauthError("invalid_request", "token is required.")
        val grantId = refreshTokens[t]?.grantId ?: accessTokens[t]
        grantId?.let { revokeToken(it) }
        pats.values.firstOrNull { it.secret == t }?.let { revokeToken(it.id) }
        return Resp(200)
    }

    // ---- catalog -----------------------------------------------------------------------------

    private fun libraryMembers(userId: String) = states.filter { it.key.first == userId && it.value.libraryAddedAt != null }.keys.map { it.second }.toSet()

    private fun idsFilter(req: Req): Set<String>? = req.query["ids"]?.split(',')?.map { it.trim() }?.toSet()

    private fun listArtists(req: Req): Resp {
        val p = auth(req, "catalog:read")
        val ids = idsFilter(req)
        var list = artists.values.sortedWith(compareBy({ it.name.lowercase() }, { it.id })).filter { ids == null || it.id in ids }
        if (req.query["scope"] == "library") {
            val members = libraryMembers(p.user.id)
            list = list.filter { a -> a.id in members || albums.values.any { it.artistId == a.id && it.id in members } || tracks.values.any { it.artistId == a.id && it.id in members } }
        }
        return page(req, list, { artistJson(it, p) })
    }

    private fun listAlbums(req: Req): Resp {
        val p = auth(req, "catalog:read")
        val ids = idsFilter(req)
        var list = albums.values.filter { visibleAlbum(it, p) }.sortedWith(compareBy({ it.title.lowercase() }, { it.id })).filter { ids == null || it.id in ids }
        req.query["sourceId"]?.let { s -> list = list.filter { it.source == s } }
        if (req.query["scope"] == "library") {
            val members = libraryMembers(p.user.id)
            list = list.filter { a -> a.id in members || tracks.values.any { it.albumId == a.id && it.id in members } }
        }
        return page(req, list, { albumJson(it, p) })
    }

    private fun listTracks(req: Req): Resp {
        val p = auth(req, "catalog:read")
        val ids = idsFilter(req)
        var list = tracks.values.filter { visibleTrack(it, p) }.filter { ids == null || it.id in ids }
        list = if (req.query["sort"] == "-addedAt") list.sortedByDescending { it.addedAt } else list.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
        req.query["albumId"]?.let { a -> list = list.filter { it.albumId == a } }
        req.query["sourceId"]?.let { s -> list = list.filter { it.source == s } }
        if (req.query["scope"] == "library") { val members = libraryMembers(p.user.id); list = list.filter { it.id in members } }
        return page(req, list, { trackJson(it, p) })
    }

    private fun getArtist(req: Req, id: String): Resp {
        val p = auth(req, "catalog:read")
        val a = artists[id] ?: halt(404, "not-found", "No such artist.")
        return conditional(req, etagFor(a.revision, state(p.user.id, id).revision, p.user.id), artistJson(a, p))
    }

    private fun getAlbum(req: Req, id: String): Resp {
        val p = auth(req, "catalog:read")
        val a = albums[id]?.takeIf { visibleAlbum(it, p) } ?: halt(404, "not-found", "No such album.")
        val body = albumJson(a, p).toMutableMap()
        body["discs"] = listOf(mapOf("number" to 1, "tracks" to tracks.values.filter { it.albumId == id }.sortedBy { it.trackNumber }.map { trackJson(it, p) }))
        body["tracksNextCursor"] = null
        val etag = if (Fault.ETAG_IGNORES_USER_STATE in faults) etagFor(a.revision) else etagFor(a.revision, state(p.user.id, id).revision, p.user.id)
        return conditional(req, etag, body)
    }

    private fun getTrack(req: Req, id: String): Resp {
        val p = auth(req, "catalog:read")
        val t = tracks[id]?.takeIf { visibleTrack(it, p) } ?: halt(404, "not-found", "No such track.")
        return conditional(req, etagFor(t.revision, state(p.user.id, id).revision, p.user.id), trackJson(t, p, detail = true))
    }

    private fun search(req: Req): Resp {
        val p = auth(req, "catalog:read")
        val q = (req.query["q"] ?: halt(400, "invalid-request", "q is required.")).lowercase()
        val types = req.query["types"]?.split(',')?.toSet() ?: setOf("artist", "album", "track")
        val body = linkedMapOf<String, Any?>()
        if ("artist" in types) body["artists"] = mapOf("items" to artists.values.filter { it.name.lowercase().contains(q) }.map { artistJson(it, p) }, "nextCursor" to null)
        if ("album" in types) body["albums"] = mapOf("items" to albums.values.filter { visibleAlbum(it, p) && it.title.lowercase().contains(q) }.map { albumJson(it, p) }, "nextCursor" to null)
        if ("track" in types) body["tracks"] = mapOf("items" to tracks.values.filter { visibleTrack(it, p) && it.title.lowercase().contains(q) }.map { trackJson(it, p) }, "nextCursor" to null)
        return jsonResp(200, body)
    }

    private fun artwork(req: Req, id: String): Resp {
        auth(req, "catalog:read")
        if (albums.values.none { it.artworkId == id }) halt(404, "not-found", "No such artwork.")
        return Resp(200, png, "image/png", mapOf("Cache-Control" to "private, max-age=31536000, immutable", "ETag" to "\"$id\""))
    }

    // ---- media -----------------------------------------------------------------------------

    private fun bearer(req: Req) = req.header("Authorization")?.removePrefix("Bearer ") ?: ""

    private fun grantUrl(p: Principal, kind: String, id: String, req: Req): Pair<String, Instant> {
        val g = "mg_" + secret(24)
        val ttlKey = if (kind == "artwork") "artworkUrlTtlSeconds" else "mediaUrlTtlSeconds"
        val ttl = settings.path(ttlKey).takeIf { it.isInt && Fault.TTL_IGNORED !in faults }?.asLong() ?: if (kind == "artwork") 604800L else 21600L
        val expires = now().plusSeconds(ttl)
        mediaGrants[g] = MediaGrant(p.tokenId, kind, id, expires)
        val url = "$base/media/$g" + if (Fault.SECRET_IN_URL in faults) "?access_token=${bearer(req)}" else ""
        return url to expires
    }

    private fun decision(req: Req): Resp {
        val p = auth(req, "media:read")
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val trackId = b.str("trackId") ?: halt(400, "invalid-request", "trackId is required.")
        if (!b.path("profile").path("directPlay").isArray) halt(400, "invalid-request", "profile.directPlay is required.")
        val t = tracks[trackId]?.takeIf { visibleTrack(it, p) } ?: halt(404, "not-found", "No such track.")
        val (url, expires) = grantUrl(p, "track", t.id, req)
        return jsonResp(200, linkedMapOf("id" to newId("plan"), "trackId" to t.id, "mode" to "direct", "url" to url, "expiresAt" to ts(expires),
            "mimeType" to "audio/mpeg", "container" to "mp3", "codec" to "mp3", "bitrate" to 128000, "offsetMs" to 0, "durationMs" to 1045,
            "streamDurationMs" to 1045, "contentLength" to t.bytes.size.toLong(), "lengthExact" to true, "byteRanges" to true),
            mapOf("Cache-Control" to "no-store"))
    }

    private fun mediaUrls(req: Req): Resp {
        val p = auth(req, "media:read")
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val urls = b.path("requests").map { r ->
            val kind = r.str("kind") ?: halt(400, "invalid-request", "kind is required.")
            val id = r.str("id") ?: halt(400, "invalid-request", "id is required.")
            val size = if (r.has("size")) r.path("size").asInt() else null
            val (url, expires) = when (kind) {
                "artwork" -> {
                    if (albums.values.none { it.artworkId == id }) halt(404, "not-found", "No such artwork.")
                    artworkUrls.getOrPut(Triple(p.user.id, id, size)) { grantUrl(p, "artwork", id, req) }
                }
                else -> grantUrl(p, "track", tracks[id]?.id ?: halt(404, "not-found", "No such track."), req)
            }
            mapOf("kind" to kind, "id" to id, "url" to url, "expiresAt" to ts(expires))
        }
        return jsonResp(200, mapOf("urls" to urls), mapOf("Cache-Control" to "no-store"))
    }

    private fun media(req: Req, grant: String): Resp {
        val g = mediaGrants[grant]
        val alive = g != null && g.expiresAt > now() && (tokenAlive(g.tokenId) || Fault.REVOKE_KEEPS_MEDIA in faults)
        if (!alive) halt(403, "media-grant-expired", "The media URL expired or was revoked.")
        if (g!!.kind == "artwork") return Resp(200, png, "image/png", mapOf("Accept-Ranges" to "bytes"))
        val bytes = tracks[g.id]?.bytes ?: halt(404, "not-found", "The track is gone.")
        val range = req.header("Range")
        if (range == null || Fault.NO_RANGE in faults) return Resp(200, bytes, "audio/mpeg", mapOf("Accept-Ranges" to "bytes"))
        val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range) ?: return Resp(200, bytes, "audio/mpeg", mapOf("Accept-Ranges" to "bytes"))
        val start = m.groupValues[1].toLong()
        val end = (m.groupValues[2].toLongOrNull() ?: (bytes.size - 1).toLong()).coerceAtMost((bytes.size - 1).toLong())
        if (start >= bytes.size || start > end) return Resp(416, headers = mapOf("Content-Range" to "bytes */${bytes.size}"))
        return Resp(206, bytes.copyOfRange(start.toInt(), end.toInt() + 1), "audio/mpeg", mapOf("Content-Range" to "bytes $start-$end/${bytes.size}", "Accept-Ranges" to "bytes"))
    }

    // ---- user state ------------------------------------------------------------------------

    private fun actionTime(req: Req): Instant = req.header("Dulcet-Action-Time")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now()

    /** Applies one state operation; returns (status, applied). Unknown items: 404 for additions, success for removals. */
    private fun applyState(p: Principal, op: String, itemId: String, at: Instant, rating: Int?): Pair<Int, Boolean> {
        val known = itemType(itemId) != null
        val removal = op in setOf("unfavorite", "unrate", "libraryRemove")
        if (!known && !removal) return 404 to false
        if (op == "libraryRemove" && "user:delete" !in p.scopes && Fault.DELETE_WITHOUT_DELETE_SCOPE !in faults) return 403 to false
        if (!known) return 204 to true
        val s = state(p.user.id, itemId)
        fun stale(last: Instant?) = last != null && at < last
        val applied = when (op) {
            "favorite" -> if (stale(s.favAction)) false else { if (s.favoritedAt == null) s.favoritedAt = at.coerceAtMost(now()); s.favAction = at; true }
            "unfavorite" -> if (stale(s.favAction)) false else { s.favoritedAt = null; s.favAction = at; true }
            "rate" -> if (stale(s.ratingAction)) false else { s.rating = rating; s.ratingAction = at; true }
            "unrate" -> if (stale(s.ratingAction)) false else { s.rating = null; s.ratingAction = at; true }
            "libraryAdd" -> if (stale(s.libAction)) false else { if (s.libraryAddedAt == null) s.libraryAddedAt = now(); s.libAction = at; true }
            "libraryRemove" -> if (stale(s.libAction)) false else { s.libraryAddedAt = null; s.libAction = at; true }
            else -> false
        }
        if (applied) stateChanged(p.user.id, itemId)
        return (if (removal) 204 else 200) to applied
    }

    private fun stateWrite(req: Req, kind: String, itemId: String): Resp {
        val isPut = req.method == "PUT"
        if (req.method != "PUT" && req.method != "DELETE") halt(404, "not-found", "No such resource.")
        val p = auth(req, if (isPut) "user:write" else if (kind == "library") (if (Fault.DELETE_WITHOUT_DELETE_SCOPE in faults) "user:write" else "user:delete") else "user:write")
        val op = when (kind) {
            "favorite" -> if (isPut) "favorite" else "unfavorite"
            "rating" -> if (isPut) "rate" else "unrate"
            else -> if (isPut) "libraryAdd" else "libraryRemove"
        }
        var rating: Int? = null
        if (op == "rate") {
            val r = req.json?.path("rating")
            if (r == null || !r.isInt || r.asInt() !in 1..10) halt(400, "invalid-request", "rating must be an integer from 1 to 10.")
            rating = r.asInt()
        }
        val (status, applied) = applyState(p, op, itemId, actionTime(req), rating)
        if (status == 404) halt(404, "not-found", "No such item.")
        if (status == 403) halt(403, "insufficient-scope", "The token does not hold the required scope.", mapOf("requiredScopes" to listOf("user:delete")))
        if (!isPut) return Resp(204)
        return jsonResp(200, itemStateJson(p.user.id, itemId, applied))
    }

    private fun batch(req: Req): Resp {
        val p = auth(req, "user:write")
        val ops = req.json?.path("operations") ?: halt(400, "invalid-request", "operations is required.")
        val results = ops.map { o ->
            val at = o.str("at")?.let { Instant.parse(it) } ?: actionTime(req)
            val (status, applied) = applyState(p, o.str("op") ?: "", o.str("itemId") ?: "", at, if (o.has("rating")) o.path("rating").asInt() else null)
            val r = linkedMapOf<String, Any?>("status" to status, "applied" to applied)
            when (status) {
                404 -> r["problem"] = mapOf("type" to "urn:dulcet:problem:not-found", "title" to "No such item.", "status" to 404)
                403 -> r["problem"] = mapOf("type" to "urn:dulcet:problem:insufficient-scope", "title" to "The token does not hold the required scope.", "status" to 403, "requiredScopes" to listOf("user:delete"))
            }
            r
        }
        return jsonResp(200, mapOf("results" to results))
    }

    private fun itemRef(id: String) = mapOf("id" to id, "type" to (itemType(id) ?: "track"), "name" to itemName(id))

    private fun listLibrary(req: Req): Resp {
        val p = auth(req, "user:read")
        val all = states.filter { it.key.first == p.user.id && it.value.libraryAddedAt != null }
            .map { it.key.second to it.value.libraryAddedAt!! }
            .sortedWith(compareByDescending<Pair<String, Instant>> { it.second }.thenBy { it.first })
        val counts = mapOf("artist" to all.count { itemType(it.first) == "artist" }, "album" to all.count { itemType(it.first) == "album" }, "track" to all.count { itemType(it.first) == "track" })
        val types = req.query["types"]?.split(',')?.toSet()
        return page(req, all.filter { types == null || itemType(it.first) in types }, { mapOf("item" to itemRef(it.first), "libraryAddedAt" to ts(it.second)) }, mapOf("counts" to counts))
    }

    private fun listFavorites(req: Req): Resp {
        val p = auth(req, "user:read")
        val all = states.filter { it.key.first == p.user.id && it.value.favoritedAt != null }
            .map { it.key.second to it.value.favoritedAt!! }.sortedByDescending { it.second }
        return page(req, all, { mapOf("item" to itemRef(it.first), "favoritedAt" to ts(it.second)) })
    }

    // ---- plays -----------------------------------------------------------------------------

    private fun listPlays(req: Req): Resp {
        val p = auth(req, "user:read")
        return page(req, plays.values.filter { it.userId == p.user.id && !it.deleted }.reversed(), { playJson(it) })
    }

    private fun playJson(r: PlayRec) = linkedMapOf<String, Any?>("id" to r.id, "trackId" to r.trackId, "startedAt" to r.body.str("startedAt"),
        "listenedMs" to r.body.path("listenedMs").asLong(), "recordedAt" to ts(r.recordedAt))

    private fun submitPlays(req: Req): Resp {
        val p = auth(req, "plays:write")
        val list = req.json?.path("plays")?.takeIf { it.isArray && it.size() > 0 } ?: halt(400, "invalid-request", "plays is required.")
        val results = list.map { play ->
            val id = play.str("id") ?: halt(400, "invalid-request", "Every play needs an id.")
            val trackId = play.str("trackId") ?: ""
            val status = when {
                plays.containsKey(id) && Fault.PLAYS_DOUBLE_COUNT !in faults -> "duplicate"
                tracks[trackId] == null -> "unknownTrack"
                else -> {
                    if (!plays.containsKey(id)) plays[id] = PlayRec(id, p.user.id, trackId, play, now())
                    val s = state(p.user.id, trackId)
                    s.playCount++
                    val started = play.str("startedAt")?.let { Instant.parse(it) }
                    if (started != null && (s.lastPlayedAt == null || started > s.lastPlayedAt)) s.lastPlayedAt = started
                    journal("play", id, "upsert", p.user.id, null, playJson(plays[id]!!))
                    hooks.values.filter { it.userId == p.user.id && ("play.recorded" in it.events || "*" in it.events) }.forEach { deliverAsync(it, "play.recorded", playJson(plays[id]!!)) }
                    stateChanged(p.user.id, trackId)
                    "recorded"
                }
            }
            mapOf("id" to id, "status" to status)
        }
        return jsonResp(200, mapOf("results" to results))
    }

    private fun deletePlay(req: Req, id: String): Resp {
        val p = auth(req, if (Fault.DELETE_WITHOUT_DELETE_SCOPE in faults) "user:write" else "user:delete")
        val rec = plays[id]
        if (rec != null && rec.userId == p.user.id && !rec.deleted) {
            rec.deleted = true
            state(p.user.id, rec.trackId).playCount--
            journal("play", id, "delete", p.user.id, null, null, "deleted")
            stateChanged(p.user.id, rec.trackId)
        }
        return Resp(204)
    }

    private fun nowPlayingWrite(req: Req): Resp {
        val p = auth(req, "plays:write")
        if (req.method == "DELETE") {
            val query = if (Fault.NOW_PLAYING_CLEAR_HEADER_ONLY in faults) null else req.query["deviceId"]
            val device = query ?: req.header("Dulcet-Device-Id") ?: halt(400, "invalid-request", "Name the device with deviceId or Dulcet-Device-Id.")
            nowPlaying.keys.removeIf { it.first == p.user.id && it.second == device }
            emit("nowPlaying", mapOf("userId" to p.user.id, "deviceId" to device, "state" to "ended")) { it.userId == p.user.id }
            return Resp(204)
        }
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val device = b.str("deviceId") ?: halt(400, "invalid-request", "deviceId is required.")
        val ttl = if (b.has("ttlSeconds")) b.path("ttlSeconds").asLong() else 120
        val body = linkedMapOf<String, Any?>("deviceId" to device, "trackId" to b.str("trackId"), "state" to b.str("state"), "positionMs" to b.path("positionMs").asLong(),
            "ttlSeconds" to ttl, "userId" to p.user.id, "userName" to p.user.username, "updatedAt" to ts(now()), "expiresAt" to ts(now().plusSeconds(ttl)))
        nowPlaying[p.user.id to device] = body
        emit("nowPlaying", mapOf("userId" to p.user.id, "deviceId" to device, "trackId" to b.str("trackId"), "state" to (b.str("state") ?: "playing"), "positionMs" to b.path("positionMs").asLong())) { it.userId == p.user.id }
        return jsonResp(200, body)
    }

    private fun listNowPlaying(req: Req): Resp {
        val p = auth(req, "user:read")
        return jsonResp(200, mapOf("items" to nowPlaying.filter { it.key.first == p.user.id }.values.toList()))
    }

    // ---- playlists ---------------------------------------------------------------------------

    private fun visiblePlaylist(id: String, p: Principal): Playlist =
        playlists[id]?.takeIf { !it.deleted && (it.owner == p.user.id || it.visibility == "public") } ?: halt(404, "not-found", "No such playlist.")

    private fun ownPlaylist(id: String, p: Principal): Playlist =
        visiblePlaylist(id, p).takeIf { it.owner == p.user.id } ?: halt(403, "permission-denied", "Only the owner can edit this playlist.")

    private fun checkIfMatch(req: Req, pl: Playlist, p: Principal, required: Boolean) {
        val ifMatch = req.header("If-Match")
        if (ifMatch == null) {
            if (required) halt(428, "precondition-required", "This write needs If-Match.")
            return
        }
        if (ifMatch != "\"${pl.revision}\"") halt(412, "revision-mismatch", "If-Match does not match.", mapOf("current" to playlistJson(pl, p), "currentRevision" to pl.revision))
    }

    private fun touched(pl: Playlist) {
        pl.revision = newRev(); pl.updatedAt = now()
        journal("playlist", pl.id, "upsert", pl.owner, null, playlistJson(pl, Principal(users[pl.owner]!!, "", "", emptyList())), revision = pl.revision)
    }

    private fun playlistResp(status: Int, pl: Playlist, p: Principal, extra: Map<String, String> = emptyMap()) =
        jsonResp(status, playlistJson(pl, p), mapOf("ETag" to "\"${pl.revision}\"") + extra)

    private fun listPlaylists(req: Req): Resp {
        val p = auth(req, "user:read")
        return page(req, playlists.values.filter { !it.deleted && it.owner == p.user.id }, { playlistJson(it, p) })
    }

    private fun createPlaylist(req: Req): Resp {
        val p = auth(req, "user:write")
        val key = req.header("Idempotency-Key")?.takeIf { Fault.IGNORE_IDEMPOTENCY_KEY !in faults }
        val fingerprint = String(req.body, StandardCharsets.UTF_8)
        if (key != null) idempotency[p.user.id to key]?.let { (fp, resp) ->
            if (fp != fingerprint) halt(422, "idempotency-key-reused", "This Idempotency-Key was used with a different request.")
            return resp
        }
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val name = b.str("name")?.takeIf { it.isNotEmpty() } ?: halt(400, "invalid-request", "name is required.")
        val items = b.path("itemIds").map { it.asText() }
        items.firstOrNull { tracks[it] == null }?.let { halt(422, "unknown-item", "An item id is not in the catalog.") }
        val pl = Playlist(newId("pl"), p.user.id, name, b.str("description"), b.str("visibility") ?: "private",
            items.map { Entry(newId("ent"), it, now()) }.toMutableList(), newRev(), now(), now())
        playlists[pl.id] = pl
        journal("playlist", pl.id, "upsert", pl.owner, null, playlistJson(pl, p), revision = pl.revision)
        val resp = playlistResp(201, pl, p, mapOf("Location" to "$base/playlists/${pl.id}"))
        if (key != null) idempotency[p.user.id to key] = fingerprint to resp
        return resp
    }

    private fun playlist(req: Req, id: String): Resp = when (req.method) {
        "GET" -> { val p = auth(req, "user:read"); val pl = visiblePlaylist(id, p); conditional(req, "\"${pl.revision}\"", playlistJson(pl, p)) }
        "PATCH" -> {
            val p = auth(req, "user:write")
            val pl = ownPlaylist(id, p)
            checkIfMatch(req, pl, p, required = false)
            req.json?.let { b -> b.str("name")?.let { pl.name = it }; if (b.has("description")) pl.description = b.str("description"); b.str("visibility")?.let { pl.visibility = it } }
            touched(pl)
            playlistResp(200, pl, p)
        }
        "DELETE" -> {
            val p = auth(req, if (Fault.DELETE_WITHOUT_DELETE_SCOPE in faults) "user:write" else "user:delete")
            playlists[id]?.takeIf { !it.deleted && it.owner == p.user.id }?.let { pl ->
                checkIfMatch(req, pl, p, required = false)
                pl.deleted = true
                journal("playlist", pl.id, "delete", pl.owner, null, null, "deleted")
            }
            Resp(204)
        }
        else -> halt(404, "not-found", "No such resource.")
    }

    private fun playlistItems(req: Req, id: String): Resp {
        when (req.method) {
            "GET" -> {
                val p = auth(req, "user:read")
                val pl = visiblePlaylist(id, p)
                val n = limit(req)
                val offset = req.query["cursor"]?.let { c ->
                    val raw = runCatching { String(Base64.getUrlDecoder().decode(c.removePrefix("pc_"))) }.getOrNull() ?: halt(400, "invalid-request", "Unreadable cursor.")
                    if (raw.substringBefore('|') != pl.revision) halt(409, "conflict", "The playlist changed during the walk; start again.")
                    raw.substringAfter('|').toInt()
                } ?: 0
                val slice = pl.entries.withIndex().drop(offset).take(n)
                val next = if (offset + n < pl.entries.size) "pc_" + Base64.getUrlEncoder().withoutPadding().encodeToString("${pl.revision}|${offset + n}".toByteArray()) else null
                return jsonResp(200, mapOf("revision" to pl.revision, "nextCursor" to next,
                    "items" to slice.map { (i, e) -> mapOf("entryId" to e.playlistEntryId, "position" to i, "itemId" to e.itemId, "addedAt" to ts(e.addedAt)) }),
                    mapOf("ETag" to "\"${pl.revision}\"", "Dulcet-Sync-Cursor" to syncCursorHeader()))
            }
            "PUT" -> {
                val p = auth(req, "user:write")
                val pl = ownPlaylist(id, p)
                checkIfMatch(req, pl, p, required = true)
                val ids = req.json?.path("itemIds")?.map { it.asText() } ?: halt(400, "invalid-request", "itemIds is required.")
                ids.firstOrNull { tracks[it] == null }?.let { halt(422, "unknown-item", "An item id is not in the catalog.") }
                val pool = pl.entries.toMutableList()
                val next = ids.map { item -> pool.firstOrNull { it.itemId == item }?.also { pool.remove(it) } ?: Entry(newId("ent"), item, now()) }
                if (pool.isNotEmpty() && "user:delete" !in p.scopes && Fault.DELETE_WITHOUT_DELETE_SCOPE !in faults)
                    halt(403, "insufficient-scope", "Dropping entries needs user:delete.", mapOf("requiredScopes" to listOf("user:delete")))
                pl.entries.clear(); pl.entries.addAll(next)
                touched(pl)
                return playlistResp(200, pl, p)
            }
            "POST" -> {
                val p = auth(req, "user:write")
                val pl = ownPlaylist(id, p)
                checkIfMatch(req, pl, p, required = false)
                val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
                val ids = b.path("itemIds").map { it.asText() }
                if (ids.isEmpty()) halt(400, "invalid-request", "itemIds is required.")
                ids.firstOrNull { tracks[it] == null }?.let { halt(422, "unknown-item", "An item id is not in the catalog.") }
                val before = b.str("beforeEntryId")
                val at = if (before == null) pl.entries.size else pl.entries.indexOfFirst { it.playlistEntryId == before }.takeIf { it >= 0 } ?: halt(400, "invalid-request", "Unknown beforeEntryId.")
                pl.entries.addAll(at, ids.map { Entry(newId("ent"), it, now()) })
                touched(pl)
                return playlistResp(200, pl, p)
            }
        }
        halt(404, "not-found", "No such resource.")
    }

    private fun playlistEntry(req: Req, id: String, entryId: String): Resp {
        when (req.method) {
            "PATCH" -> {
                val p = auth(req, "user:write")
                val pl = ownPlaylist(id, p)
                checkIfMatch(req, pl, p, required = false)
                val entry = pl.entries.firstOrNull { it.playlistEntryId == entryId } ?: halt(404, "not-found", "No such entry.")
                pl.entries.remove(entry)
                val before = req.json?.str("beforeEntryId")
                val at = if (before == null) pl.entries.size else pl.entries.indexOfFirst { it.playlistEntryId == before }.takeIf { it >= 0 } ?: pl.entries.size
                pl.entries.add(at, entry)
                touched(pl)
                return jsonResp(200, playlistJson(pl, p))
            }
            "DELETE" -> {
                val p = auth(req, if (Fault.DELETE_WITHOUT_DELETE_SCOPE in faults) "user:write" else "user:delete")
                val pl = ownPlaylist(id, p)
                checkIfMatch(req, pl, p, required = false)
                if (pl.entries.removeIf { it.playlistEntryId == entryId }) touched(pl)
                return Resp(204)
            }
        }
        halt(404, "not-found", "No such resource.")
    }

    // ---- sync ------------------------------------------------------------------------------

    private val catalogTypes = setOf("artist", "album", "track", "genre", "source")
    private val userTypes = setOf("itemState", "playlist", "play", "queue")

    private fun encodeCursor(vararg parts: Any) = "sc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString("|").toByteArray())
    private fun syncCursorHeader() = encodeCursor("c", instanceId, head(), "")

    private fun visibleChange(c: Change, p: Principal, types: Set<String>): Boolean {
        if (c.type !in types) return false
        if (c.user != null && c.user != p.user.id) return false
        if (c.user == null && c.source != null && c.source !in p.user.sourceIds) return false
        if (c.op == "delete" && Fault.DROP_TOMBSTONES in faults) return false
        return true
    }

    private fun changeJson(c: Change) = linkedMapOf<String, Any?>("op" to c.op, "type" to c.type, "id" to c.id, "revision" to c.revision, "at" to ts(c.at),
        "resource" to c.resource, "reason" to c.reason, "replacedBy" to c.replacedBy).filterValues { it != null }

    private fun snapshotEntities(p: Principal, types: Set<String>): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        fun up(type: String, id: String, revision: String, resource: Any?) { out += linkedMapOf("op" to "upsert", "type" to type, "id" to id, "revision" to revision, "at" to ts(now()), "resource" to resource) }
        if ("artist" in types) artists.values.forEach { up("artist", it.id, it.revision, artistJson(it, null)) }
        if ("album" in types) albums.values.filter { visibleAlbum(it, p) }.forEach { up("album", it.id, it.revision, albumJson(it, null)) }
        if ("track" in types) tracks.values.filter { visibleTrack(it, p) }.forEach { up("track", it.id, it.revision, trackJson(it, null)) }
        if ("source" in types) sources.filter { it.key in p.user.sourceIds }.forEach { (id, name) -> up("source", id, "$instanceId.0", mapOf("id" to id, "type" to "source", "name" to name)) }
        if ("playlist" in types) playlists.values.filter { !it.deleted && it.owner == p.user.id }.forEach { up("playlist", it.id, it.revision, playlistJson(it, p)) }
        if ("itemState" in types) states.filter { it.key.first == p.user.id && it.value.revision.isNotEmpty() }.forEach { (k, s) -> up("itemState", k.second, s.revision, itemStateJson(p.user.id, k.second).minus("applied")) }
        return out
    }

    private fun changes(req: Req): Resp {
        val p = auth(req)
        val typesParam = req.query["types"]?.takeIf { it.isNotEmpty() }
        val requested = typesParam?.split(',')?.map { it.trim() }?.toSet()
        requested?.firstOrNull { it !in catalogTypes && it !in userTypes }?.let { halt(400, "invalid-request", "Unknown type $it.") }
        requested?.forEach { t -> requireScope(p, if (t in catalogTypes) "catalog:read" else "user:read") }
        val types = requested ?: ((if ("catalog:read" in p.scopes) catalogTypes else emptySet()) + (if ("user:read" in p.scopes) userTypes else emptySet()))
        val typesKey = typesParam?.split(',')?.map { it.trim() }?.sorted()?.joinToString(",") ?: ""
        val n = limit(req, default = 500)
        val cursor = req.query["cursor"]
        val start = req.query["start"]
        fun page(changes: List<Any?>, next: String, hasMore: Boolean, phase: String) =
            jsonResp(200, mapOf("changes" to changes, "nextCursor" to next, "hasMore" to hasMore, "phase" to phase), mapOf("Dulcet-Sync-Cursor" to syncCursorHeader()))
        if (cursor == null) {
            return when (start) {
                "now" -> page(emptyList<Any>(), encodeCursor("c", instanceId, head(), typesKey), false, "changes")
                "snapshot" -> snapshotPage(p, types, typesKey, head(), 0, n, ::page)
                else -> halt(400, "invalid-request", "Send cursor or start.")
            }
        }
        val parts = runCatching { String(Base64.getUrlDecoder().decode(cursor.removePrefix("sc_"))).split('|') }.getOrNull()
        if (parts == null || parts.size < 4) halt(400, "invalid-request", "Unreadable cursor.")
        if (parts[1] != instanceId && Fault.FOREIGN_CURSOR_ACCEPTED !in faults) halt(410, "cursor-expired", "The cursor is from another history; start again.")
        val cursorTypes = parts.last()
        if (cursorTypes != typesKey && Fault.CURSOR_TYPES_UNBOUND !in faults) halt(400, "cursor-types-mismatch", "The cursor was made for different types.")
        val seq = parts[2].toLongOrNull() ?: halt(400, "invalid-request", "Unreadable cursor.")
        if (seq < compactedThrough) halt(410, "cursor-expired", "The journal was compacted past this cursor; start again.")
        if (parts[0] == "s") return snapshotPage(p, types, typesKey, seq, parts[3].toInt(), n, ::page)
        if (seq > head() && Fault.FOREIGN_CURSOR_ACCEPTED !in faults) halt(410, "cursor-expired", "The cursor is ahead of the journal.")
        val latest = LinkedHashMap<Pair<String, String>, Change>()
        journal.filter { it.seq > seq && visibleChange(it, p, types) }.forEach { c -> latest.remove(c.type to c.id); latest[c.type to c.id] = c }
        val ordered = latest.values.sortedBy { it.seq }
        val slice = ordered.take(n)
        val more = ordered.size > n
        val next = if (more) slice.last().seq else maxOf(head(), seq)
        return page(slice.map { changeJson(it) }, encodeCursor("c", instanceId, next, typesKey), more, "changes")
    }

    private fun snapshotPage(p: Principal, types: Set<String>, typesKey: String, at: Long, offset: Int, n: Int, page: (List<Any?>, String, Boolean, String) -> Resp): Resp {
        val all = snapshotEntities(p, types)
        val slice = all.drop(offset).take(n)
        val next = if (offset + n < all.size) encodeCursor("s", instanceId, at, offset + n, typesKey) else encodeCursor("c", instanceId, at, typesKey)
        return page(slice, next, true, "snapshot")
    }

    // ---- admin -----------------------------------------------------------------------------

    private fun updateUser(req: Req, id: String): Resp {
        auth(req, "admin")
        val u = users[id] ?: halt(404, "not-found", "No such user.")
        val ids = req.json?.path("sourceIds")
        if (ids != null && ids.isArray) {
            val next = ids.map { it.asText() }
            val removed = u.sourceIds - next.toSet()
            val added = next - u.sourceIds.toSet()
            u.sourceIds = next
            if (Fault.HIDDEN_NOT_EMITTED !in faults) {
                for (a in albums.values.filter { it.source in removed }) journal("album", a.id, "delete", u.id, null, null, "hidden")
                for (t in tracks.values.filter { it.source in removed }) journal("track", t.id, "delete", u.id, null, null, "hidden")
                for (a in albums.values.filter { it.source in added }) journal("album", a.id, "upsert", u.id, null, albumJson(a, null), revision = a.revision)
                for (t in tracks.values.filter { it.source in added }) journal("track", t.id, "upsert", u.id, null, trackJson(t, null), revision = t.revision)
            }
        }
        return jsonResp(200, userJson(u))
    }

    private fun startScan(req: Req): Resp {
        auth(req, "admin")
        val id = newId("scan")
        val started = now()
        val counts = scanLibrary()
        scans[id] = linkedMapOf("id" to id, "kind" to (req.json?.str("kind") ?: "quick"), "state" to "completed", "startedAt" to ts(started), "finishedAt" to ts(now()), "counts" to counts)
        emit("scan", mapOf("scanId" to id, "state" to "completed", "counts" to counts)) { true }
        return jsonResp(202, scans[id]!! + ("state" to "queued"), mapOf("Location" to "$base/admin/scans/$id"))
    }

    private fun getScan(req: Req, id: String): Resp {
        auth(req, "admin")
        return jsonResp(200, scans[id] ?: halt(404, "not-found", "No such scan."))
    }

    /** Folds [from] into [into]: tracks and every user's state move first, then [from] is tombstoned. */
    private fun mergeAlbum(from: Album, into: Album) {
        for (t in tracks.values.filter { it.albumId == from.id }) {
            t.albumId = into.id; t.revision = newRev()
            journal("track", t.id, "upsert", null, t.source, trackJson(t, null), revision = t.revision)
        }
        for ((key, st) in states.entries.filter { it.key.second == from.id }.toList()) {
            val target = state(key.first, into.id)
            if (target.favoritedAt == null) { target.favoritedAt = st.favoritedAt; target.favAction = st.favAction }
            if (target.rating == null) { target.rating = st.rating; target.ratingAction = st.ratingAction }
            if (target.libraryAddedAt == null) { target.libraryAddedAt = st.libraryAddedAt; target.libAction = st.libAction }
            target.playCount += st.playCount
            states.remove(key)
            stateChanged(key.first, into.id)
        }
        albums.remove(from.id)
        into.revision = newRev()
        journal("album", into.id, "upsert", null, into.source, albumJson(into, null), revision = into.revision)
        journal("album", from.id, "delete", null, from.source, null, "merged", replacedBy = if (Fault.MERGE_NO_REPLACED_BY in faults) null else listOf(into.id))
    }

    private fun scanLibrary(): Map<String, Int> {
        var added = 0; var updated = 0; var removed = 0
        val dir = libraryDir ?: return mapOf("added" to 0, "updated" to 0, "removed" to 0)
        val files = Files.walk(dir).use { s -> s.filter { it.isRegularFile() && it.extension == "mp3" }.toList() }
        for (f in files) {
            val bytes = Files.readAllBytes(f)
            val tags = LibraryFixture.readTags(bytes)
            val title = tags["TIT2"] ?: f.fileName.toString()
            val existing = pathIds[f]?.let { tracks[it] }
            if (existing == null) {
                val artist = artists.values.firstOrNull { it.name == tags["TPE1"] } ?: addArtist(tags["TPE1"] ?: "Unknown")
                val releaseId = tags["TXXX:${LibraryFixture.MUSICBRAINZ_ALBUM_ID}"]
                val album = (if (releaseId != null) albums.values.firstOrNull { it.releaseId == releaseId } else albums.values.firstOrNull { it.title == tags["TALB"] })
                    ?: addAlbum(tags["TALB"] ?: "Unknown", artist, "src_1", null, releaseId)
                pathIds[f] = addTrack(title, album, artist, tags["TRCK"]?.toIntOrNull() ?: 1, "src_1", bytes).id
                added++
            } else if (!existing.bytes.contentEquals(bytes)) {
                if (Fault.RETAG_NEW_ID in faults) {
                    tracks.remove(existing.id)
                    journal("track", existing.id, "delete", null, existing.source, null, "deleted")
                    pathIds[f] = addTrack(title, albums[existing.albumId]!!, artists[existing.artistId]!!, existing.trackNumber, existing.source, bytes).id
                } else {
                    existing.title = title; existing.bytes = bytes; existing.revision = newRev()
                    journal("track", existing.id, "upsert", null, existing.source, trackJson(existing, null), revision = existing.revision)
                    val releaseId = tags["TXXX:${LibraryFixture.MUSICBRAINZ_ALBUM_ID}"]
                    val current = albums[existing.albumId]
                    if (releaseId != null && current != null && current.releaseId != releaseId) {
                        val same = albums.values.filter { it.releaseId == releaseId || it.id == current.id }
                        val survivor = same.minByOrNull { it.id.substringAfter('_').toLong() }!!
                        if (survivor.id != current.id) mergeAlbum(current, survivor)
                    }
                }
                updated++
            }
        }
        for ((path, id) in pathIds.entries.toList()) {
            if (Files.exists(path)) continue
            pathIds.remove(path)
            tracks.remove(id)?.let { t -> journal("track", id, "delete", null, t.source, null, "deleted"); removed++ }
        }
        return mapOf("added" to added, "updated" to updated, "removed" to removed)
    }

    // ---- events ------------------------------------------------------------------------------

    /** Which principals may read each event type (README §12: an event needs the scope of its data). */
    private fun mayRead(type: String, p: Principal): Boolean = when (type) {
        "changes" -> "user:read" in p.scopes || "catalog:read" in p.scopes
        "queue", "playback", "command", "nowPlaying" -> "user:read" in p.scopes
        "scan" -> "admin" in p.scopes && "admin" in p.user.roles
        "revoked", "heartbeat" -> true
        else -> false
    }
    private val eventTypes = listOf("changes", "queue", "playback", "command", "nowPlaying", "scan", "revoked", "heartbeat")

    private fun emit(type: String, data: Any?, to: (Subscriber) -> Boolean) {
        subscribers.filter { it.open && type in it.types && to(it) }.forEach { it.outbox.offer(type to data) }
    }

    private fun streamEvents(ex: HttpExchange) {
        val opened: Any = synchronized(lock) {
            val req = Req(ex)
            log(req)
            try {
                val p = auth(req)
                val asked = req.query["types"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                val denied = (asked ?: if (Fault.EVENTS_DEFAULT_REFUSES_UNREADABLE in faults) eventTypes.toSet() - "scan" else emptySet()).filter { it in eventTypes && !mayRead(it, p) }
                if (denied.isNotEmpty()) halt(403, "insufficient-scope", "The token cannot read these event types: ${denied.joinToString()}.")
                val types = (asked ?: eventTypes.filter { mayRead(it, p) }.toSet()) + setOf("revoked", "heartbeat")
                val sub = Subscriber(p.user.id, p.tokenId, req.header("Dulcet-Device-Id"), types)
                req.header("Dulcet-Device-Id")?.let { d -> devices[p.user.id to d]?.lastSeenAt = now() }
                // The mock keeps no replay log: a resume is reported as the gap the contract describes.
                if (req.header("Last-Event-ID") != null) {
                    if ("changes" in types) sub.outbox.offer("changes" to mapOf("cursor" to syncCursorHeader()))
                    if ("queue" in types) sub.outbox.offer("queue" to queueEvent(queueOf(p.user.id), listOf("entries", "current", "activeDevice")))
                }
                subscribers += sub
                sub
            } catch (h: Halt) { h.resp }
        }
        if (opened is Resp) return send(ex, opened)
        val sub = opened as Subscriber
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        try {
            while (sub.open && !closed) {
                val (type, data) = sub.outbox.poll(heartbeatMillis, java.util.concurrent.TimeUnit.MILLISECONDS) ?: ("heartbeat" to emptyMap<String, Any>())
                out.write("id: ev_${eventSeq.incrementAndGet()}\nevent: $type\ndata: ${json.writeValueAsString(data)}\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
                if (type == "revoked") break
            }
        } catch (_: java.io.IOException) {
        } catch (_: InterruptedException) {
        } finally {
            sub.open = false
            subscribers.remove(sub)
            runCatching { out.close() }
            ex.close()
        }
    }

    // ---- devices and the queue ---------------------------------------------------------------

    private fun online(userId: String, deviceId: String): Boolean =
        subscribers.any { it.open && it.userId == userId && it.deviceId == deviceId } ||
            (Fault.PRESENCE_NEEDS_STREAM !in faults && devices[userId to deviceId]?.let { it.lastSeenAt > now().minusSeconds(120) } == true)

    private fun deviceJson(d: Device) = linkedMapOf<String, Any?>("id" to d.id, "name" to d.name, "kind" to d.kind, "canPlay" to d.canPlay,
        "online" to online(d.userId, d.id), "active" to (queues[d.userId]?.activeDeviceId == d.id), "lastSeenAt" to ts(d.lastSeenAt), "client" to d.client).filterValues { it != null }

    private fun seen(req: Req, p: Principal): String? = req.header("Dulcet-Device-Id")?.also { d -> devices[p.user.id to d]?.lastSeenAt = now() }

    private fun listDevices(req: Req): Resp {
        val p = auth(req, "user:read")
        return jsonResp(200, mapOf("items" to devices.values.filter { it.userId == p.user.id }.map { deviceJson(it) }))
    }

    private fun device(req: Req, id: String): Resp {
        val p = auth(req, "user:write")
        return when (req.method) {
            "PUT" -> {
                val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
                val name = b.str("name")?.takeIf { it.isNotEmpty() } ?: halt(400, "invalid-request", "name is required.")
                if (!b.path("canPlay").isBoolean) halt(400, "invalid-request", "canPlay is required.")
                val d = devices.getOrPut(p.user.id to id) { Device(id, p.user.id, name, null, false, null, now()) }
                d.name = name; d.kind = b.str("kind"); d.canPlay = b.path("canPlay").asBoolean(); d.client = b.str("client"); d.lastSeenAt = now()
                jsonResp(200, deviceJson(d))
            }
            "DELETE" -> {
                devices.remove(p.user.id to id)
                val q = queueOf(p.user.id)
                if (q.activeDeviceId == id) { q.activeDeviceId = null; q.playback = null; queueChanged(q, p, null, listOf("activeDevice")) }
                Resp(204)
            }
            else -> halt(404, "not-found", "No such resource.")
        }
    }

    private fun queueOf(userId: String) = queues.getOrPut(userId) { QueueState(newRev(), now()) }

    private fun queueJson(q: QueueState) = linkedMapOf<String, Any?>("revision" to q.revision, "entryCount" to q.entries.size, "currentEntryId" to q.currentEntryId,
        "shuffle" to q.shuffle, "repeat" to q.repeat, "context" to null, "activeDeviceId" to q.activeDeviceId, "updatedAt" to ts(q.updatedAt), "updatedBy" to q.updatedBy).filterKeys { it != "updatedBy" || q.updatedBy != null }

    private fun queueEvent(q: QueueState, changed: List<String>) = mapOf("revision" to q.revision, "activeDeviceId" to q.activeDeviceId, "changed" to changed)

    private fun queueChanged(q: QueueState, p: Principal, device: String?, changed: List<String>) {
        q.revision = newRev(); q.updatedAt = now(); q.updatedBy = device
        emit("queue", queueEvent(q, changed)) { it.userId == p.user.id }
    }

    private fun queueResp(status: Int, q: QueueState) = jsonResp(status, queueJson(q), mapOf("ETag" to "\"${q.revision}\""))

    private fun checkQueueMatch(req: Req, q: QueueState, required: Boolean) {
        val ifMatch = req.header("If-Match") ?: if (required) halt(428, "precondition-required", "This write needs If-Match.") else return
        if (Fault.STALE_QUEUE_WRITE_ACCEPTED in faults) return
        if (ifMatch != "\"${q.revision}\"") halt(412, "revision-mismatch", "If-Match does not match.", mapOf("current" to queueJson(q), "currentRevision" to q.revision))
    }

    private fun queueRoot(req: Req): Resp {
        val m = req.method
        val p = auth(req, if (m == "GET") "user:read" else "user:write")
        val device = seen(req, p)
        val q = queueOf(p.user.id)
        return when (m) {
            "GET" -> conditional(req, "\"${q.revision}\"", queueJson(q))
            "PUT" -> {
                checkQueueMatch(req, q, required = true)
                val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
                val items = b.path("itemIds").map { it.asText() }
                items.firstOrNull { tracks[it] == null }?.let { halt(422, "unknown-item", "An item id is not in the catalog.") }
                q.entries.clear(); items.forEach { q.entries += QEntry(newId("qe"), it) }
                q.currentEntryId = q.entries.getOrNull(b.path("currentIndex").asInt(0))?.entryId
                q.shuffle = b.path("shuffle").asBoolean(false); q.repeat = b.str("repeat") ?: "off"
                val changed = mutableListOf("entries", "current", "mode")
                if (b.path("play").asBoolean(false) && device != null) { q.activeDeviceId = device; changed += "activeDevice" }
                queueChanged(q, p, device, changed)
                queueResp(200, q)
            }
            "PATCH" -> {
                checkQueueMatch(req, q, required = true)
                val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
                b.str("currentEntryId")?.let { id -> if (q.entries.none { it.entryId == id }) halt(422, "unknown-item", "No such entry."); q.currentEntryId = id }
                if (b.has("shuffle")) q.shuffle = b.path("shuffle").asBoolean()
                b.str("repeat")?.let { q.repeat = it }
                queueChanged(q, p, device, listOf("current", "mode"))
                queueResp(200, q)
            }
            else -> halt(404, "not-found", "No such resource.")
        }
    }

    private fun queueEntries(req: Req): Resp {
        val m = req.method
        val p = auth(req, if (m == "GET") "user:read" else "user:write")
        val device = seen(req, p)
        val q = queueOf(p.user.id)
        if (m == "GET") return page(req, q.entries.withIndex().toList(), { (i, e) -> mapOf("entryId" to e.entryId, "position" to i, "itemId" to e.itemId) }, mapOf("revision" to q.revision))
        if (m != "POST") halt(404, "not-found", "No such resource.")
        checkQueueMatch(req, q, required = false)
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val items = b.path("itemIds").map { it.asText() }
        if (items.isEmpty()) halt(400, "invalid-request", "itemIds is required.")
        items.firstOrNull { tracks[it] == null }?.let { halt(422, "unknown-item", "An item id is not in the catalog.") }
        val added = items.map { QEntry(newId("qe"), it) }
        val at = when {
            b.has("beforeEntryId") -> q.entries.indexOfFirst { it.entryId == b.str("beforeEntryId") }.takeIf { it >= 0 } ?: halt(422, "unknown-item", "No such entry.")
            b.str("at") == "next" -> q.entries.indexOfFirst { it.entryId == q.currentEntryId } + 1
            else -> q.entries.size
        }
        q.entries.addAll(at, added)
        if (q.currentEntryId == null) q.currentEntryId = q.entries.first().entryId
        queueChanged(q, p, device, listOf("entries"))
        return jsonResp(200, mapOf("entryIds" to added.map { it.entryId }, "queue" to queueJson(q)), mapOf("ETag" to "\"${q.revision}\""))
    }

    private fun removeQueueEntry(req: Req, entryId: String): Resp {
        val p = auth(req, "user:write")
        val device = seen(req, p)
        val q = queueOf(p.user.id)
        checkQueueMatch(req, q, required = false)
        val i = q.entries.indexOfFirst { it.entryId == entryId }
        if (i < 0) return Resp(204)
        q.entries.removeAt(i)
        val changed = mutableListOf("entries")
        if (q.currentEntryId == entryId) { q.currentEntryId = q.entries.getOrNull(i)?.entryId; changed += "current" }
        queueChanged(q, p, device, changed)
        return Resp(204, headers = mapOf("ETag" to "\"${q.revision}\""))
    }

    private fun queuePlayback(req: Req): Resp {
        val m = req.method
        val p = auth(req, if (m == "GET") "user:read" else "user:write")
        val header = seen(req, p)
        val q = queueOf(p.user.id)
        if (m == "GET") return jsonResp(200, q.playback ?: halt(404, "not-found", "Nothing is playing."))
        if (m != "PUT") halt(404, "not-found", "No such resource.")
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val device = b.str("deviceId") ?: header ?: halt(400, "invalid-request", "deviceId is required.")
        if (q.activeDeviceId != device && Fault.NON_ACTIVE_MOVES_POSITION !in faults) {
            halt(409, "not-active-device", "Only the active device reports playback.", mapOf("current" to queueJson(q)))
        }
        val pb = linkedMapOf<String, Any?>("deviceId" to device, "entryId" to b.str("entryId"), "state" to (b.str("state") ?: "playing"),
            "positionMs" to b.path("positionMs").asLong(), "positionAt" to (b.str("positionAt") ?: ts(now())))
        if (b.has("volume")) pb["volume"] = b.path("volume").asDouble()
        q.playback = pb
        emit("playback", pb) { it.userId == p.user.id && it.deviceId != device }
        return jsonResp(200, pb)
    }

    private fun transfer(req: Req): Resp {
        val p = auth(req, "user:write")
        val caller = seen(req, p)
        val q = queueOf(p.user.id)
        checkQueueMatch(req, q, required = false)
        val target = req.json?.str("deviceId") ?: halt(400, "invalid-request", "deviceId is required.")
        val d = devices[p.user.id to target]
        if (d == null || !d.canPlay || !online(p.user.id, target)) halt(409, "device-unavailable", "That device is not online or cannot play.")
        q.activeDeviceId = target
        q.playback = q.playback?.let { it + ("deviceId" to target) }
        queueChanged(q, p, caller, listOf("activeDevice"))
        return queueResp(200, q)
    }

    private fun command(req: Req): Resp {
        val p = auth(req, "user:write")
        val from = seen(req, p) ?: "token:${p.tokenId}"
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val id = b.str("id")?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess } ?: halt(400, "invalid-request", "id must be a UUID.")
        val cmd = b.str("command") ?: halt(400, "invalid-request", "command is required.")
        val active = queueOf(p.user.id).activeDeviceId
        if (active == null || subscribers.none { it.open && it.userId == p.user.id && it.deviceId == active }) halt(409, "device-unavailable", "No active device is listening.")
        val event = linkedMapOf<String, Any?>("id" to id, "command" to cmd)
        for (k in listOf("positionMs", "entryId", "volume")) if (b.has(k)) event[k] = b.path(k)
        event["from"] = from
        emit("command", event) { it.userId == p.user.id && it.deviceId == active }
        return jsonResp(202, mapOf("id" to id, "deviceId" to active))
    }

    // ---- webhooks ----------------------------------------------------------------------------

    private val webhookEventScopes = mapOf("play.recorded" to "user:read", "nowPlaying.changed" to "user:read", "itemState.changed" to "user:read",
        "playlist.changed" to "user:read", "queue.changed" to "user:read", "catalog.changed" to "catalog:read", "scan.completed" to "admin")

    private fun hookJson(h: Hook) = linkedMapOf<String, Any?>("id" to h.id, "url" to h.url, "events" to h.events, "active" to h.active,
        "createdAt" to ts(h.createdAt), "lastDeliveryAt" to ts(h.lastDeliveryAt), "consecutiveFailures" to h.failures, "disabledReason" to h.disabledReason)

    /** HTTPS only; never link-local or metadata; loopback http only when the mock stands in for an admin allowing it. */
    private fun targetAllowed(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val host = u.host?.removeSurrounding("[", "]") ?: return false
        if (u.rawUserInfo != null) return false
        val literal = host.contains(':') || host.matches(Regex("[0-9.]+"))
        val addr = if (literal) runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false else null
        if (addr != null && (addr.isLinkLocalAddress || addr.isAnyLocalAddress || host == "169.254.169.254")) return false
        val loopback = host == "localhost" || addr?.isLoopbackAddress == true
        val allowPrivate = (testMode && settings.path("webhooks").path("allowPrivateTargets").asBoolean(false)) || Fault.PRIVATE_TARGET_WITHOUT_OVERRIDE in faults
        val uniqueLocal = addr is java.net.Inet6Address && (addr.address[0].toInt() and 0xFE) == 0xFC
        if (loopback || uniqueLocal || addr?.isSiteLocalAddress == true) return allowPrivate && (u.scheme == "http" || u.scheme == "https")
        return u.scheme == "https" || (allowPrivate && u.scheme == "http")
    }

    private fun createWebhook(req: Req): Resp {
        val p = auth(req, "account")
        val b = req.json ?: halt(400, "invalid-request", "A JSON body is required.")
        val url = b.str("url") ?: halt(400, "invalid-request", "url is required.")
        val events = b.path("events").map { it.asText() }
        if (events.isEmpty()) halt(400, "invalid-request", "events is required.")
        if (!targetAllowed(url)) {
            if (Fault.WEBHOOK_TARGET_REFUSED_AS_400 in faults) halt(400, "invalid-request", "That webhook target is not allowed.")
            halt(422, "webhook-target-refused", "That webhook target is not allowed.")
        }
        val unreadable = events.filter { e -> webhookEventScopes[e]?.let { it !in p.scopes } == true }
        if (unreadable.isNotEmpty() && Fault.WEBHOOK_SCOPE_FILTERED !in faults) halt(403, "insufficient-scope", "The token cannot read these events.",
            mapOf("requiredScopes" to unreadable.map { webhookEventScopes[it]!! }.distinct(), "events" to unreadable))
        val key = ByteArray(24).also { random.nextBytes(it) }
        val h = Hook(newId("wh"), p.user.id, p.tokenId, url, events, "whsec_" + Base64.getEncoder().encodeToString(key), now())
        hooks[h.id] = h
        return jsonResp(201, hookJson(h) + ("secret" to h.secret), mapOf("Location" to "$base/webhooks/${h.id}"))
    }

    private fun ownHook(p: Principal, id: String): Hook = hooks[id]?.takeIf { it.userId == p.user.id } ?: halt(404, "not-found", "No such webhook.")

    private fun webhook(req: Req, id: String): Resp {
        val p = auth(req, "account")
        return when (req.method) {
            "GET" -> jsonResp(200, hookJson(ownHook(p, id)))
            "PATCH" -> {
                val h = ownHook(p, id)
                req.json?.path("active")?.takeIf { it.isBoolean }?.let { h.active = it.asBoolean(); if (h.active) { h.disabledReason = null; h.failures = 0 } else h.disabledReason = "admin" }
                jsonResp(200, hookJson(h))
            }
            "DELETE" -> { hooks.values.removeIf { it.id == id && it.userId == p.user.id }; Resp(204) }
            else -> halt(404, "not-found", "No such resource.")
        }
    }

    private fun testWebhook(req: Req, id: String): Resp {
        val p = auth(req, "account")
        val h = ownHook(p, id)
        val msgId = "msg_" + secret(12)
        val body = deliveryBody(h, msgId, "ping", emptyMap<String, Any>())
        val attempted = now()
        val (status, error) = attempt(h, msgId, body)
        return jsonResp(200, mapOf("id" to msgId, "event" to "ping", "attemptedAt" to ts(attempted), "responseStatus" to status, "error" to error))
    }

    private fun deliveryBody(h: Hook, msgId: String, type: String, data: Any?): ByteArray =
        json.writeValueAsBytes(linkedMapOf("id" to msgId, "type" to type, "timestamp" to ts(now()), "userId" to h.userId, "data" to data))

    private fun hookLive(h: Hook): Boolean = synchronized(lock) {
        hooks[h.id] === h && h.active && (Fault.WEBHOOK_SURVIVES_REVOCATION in faults || tokenAlive(h.tokenId))
    }

    /** One signed delivery attempt; returns the receiver's status, or null and an error. */
    private fun attempt(h: Hook, msgId: String, body: ByteArray): Pair<Int?, String?> {
        if (!hookLive(h)) return null to "webhook removed"
        val ts = Instant.now().epochSecond.toString()
        val request = java.net.http.HttpRequest.newBuilder(URI(h.url)).timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("webhook-id", msgId).header("webhook-timestamp", ts)
        if (Fault.WEBHOOK_UNSIGNED !in faults) request.header("webhook-signature", "v1," + WebhookSignature.sign(h.secret, msgId, ts, body))
        return try {
            val status = outbound.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
            synchronized(lock) { h.lastDeliveryAt = now(); if (status in 200..299) h.failures = 0 else failed(h) }
            status to null
        } catch (e: Exception) { synchronized(lock) { failed(h) }; null to e.javaClass.simpleName }
    }

    private fun failed(h: Hook) {
        h.failures++
        val limit = settings.path("webhooks").path("disableAfterFailures").asInt(DEFAULT_DISABLE_AFTER)
        if (h.failures >= limit && Fault.WEBHOOK_NEVER_DISABLED !in faults) { h.active = false; h.disabledReason = "delivery-failures" }
    }

    /** The delays before each retry: the test override, or the normal backoff. */
    private fun retrySchedule(): List<Long> {
        val override = settings.path("webhooks").path("retryScheduleSeconds")
        return if (override.isArray && Fault.WEBHOOK_RETRY_IGNORES_SCHEDULE !in faults) override.map { it.asLong() } else DEFAULT_RETRY_SCHEDULE
    }

    private fun deliverAsync(h: Hook, type: String, data: Any?) {
        val msgId = "msg_" + secret(12)
        val body = deliveryBody(h, msgId, type, data)
        val schedule = synchronized(lock) { retrySchedule() }
        fun go(n: Int) {
            val (status, _) = attempt(h, msgId, body)
            if ((status == null || status !in 200..299) && n <= schedule.size && hookLive(h) && !closed) {
                deliveryPool.schedule({ go(n + 1) }, schedule[n - 1] * 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        deliveryPool.execute { go(1) }
    }

    // ---- MCP ---------------------------------------------------------------------------------

    private fun rpc(id: JsonNode, result: Any?) = jsonResp(200, linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    private fun rpcError(id: JsonNode?, code: Int, message: String) = jsonResp(200, linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message)))

    private fun mcp(req: Req): Resp {
        req.header("Origin")?.let { if (it != origin) halt(403, "permission-denied", "That Origin may not use this server.") }
        val p = principal(req) ?: halt(401, "unauthenticated", "No valid token.", headers = mapOf("WWW-Authenticate" to "Bearer resource_metadata=\"$origin/.well-known/oauth-protected-resource/dulcet/v1/mcp\""))
        if (req.method != "POST") return Resp(405, headers = mapOf("Allow" to "POST"))
        val msg = req.json ?: return rpcError(null, -32700, "Parse error")
        val method = msg.str("method") ?: return rpcError(msg.path("id"), -32600, "Invalid request")
        if (method != "initialize") {
            val sid = req.header("Mcp-Session-Id")
            if (sid != null) {
                val s = mcpSessions[sid] ?: return problem(404, "not-found", "No such MCP session.")
                if (s.tokenId != p.tokenId) halt(403, "permission-denied", "This MCP session belongs to another token.")
            }
        }
        val id = msg.get("id")?.takeIf { !it.isNull } ?: return Resp(202)
        val params = msg.path("params")
        return when (method) {
            "initialize" -> {
                val s = McpSession("mcp_" + secret(12), p.tokenId)
                mcpSessions[s.id] = s
                jsonResp(200, linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to mapOf(
                    "protocolVersion" to (params.str("protocolVersion") ?: MCP_PROTOCOL_VERSION),
                    "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                    "serverInfo" to mapOf("name" to "Dulcet conformance mock", "version" to "0"))), mapOf("Mcp-Session-Id" to s.id))
            }
            "tools/list" -> rpc(id, mapOf("tools" to McpTools.forScopes(p.scopes).map { t ->
                (t.deepCopy() as ObjectNode).apply {
                    remove("x-dulcet-scope")
                    if (Fault.MCP_TOOLS_WITHOUT_META !in faults) putObject("_meta").putArray("dulcet/scopes").add(McpTools.scopeOf(t))
                }
            }))
            "tools/call" -> callTool(p, id, params)
            else -> rpcError(id, -32601, "Method not found")
        }
    }

    private fun trackBrief(t: Track) = linkedMapOf<String, Any?>("id" to t.id, "title" to t.title, "artist" to artists[t.artistId]?.name, "album" to albums[t.albumId]?.title, "durationMs" to 1045)

    private fun callTool(p: Principal, id: JsonNode, params: JsonNode): Resp {
        val name = params.str("name") ?: return rpcError(id, -32602, "name is required")
        val tool = McpTools.byName[name] ?: return rpcError(id, -32602, "Unknown tool $name")
        val scope = McpTools.scopeOf(tool)
        val effective = if (scope == "user:delete" && Fault.MCP_DELETE_WITHOUT_SCOPE in faults) "user:write" else scope
        if (effective !in p.scopes) return rpcError(id, -32602, "The token does not hold $scope")
        val a = params.path("arguments")
        val structured: Any? = try {
            when (name) {
                "get_track" -> tracks[a.str("id")]?.takeIf { visibleTrack(it, p) }?.let { trackBrief(it) } ?: halt(404, "not-found", "No such track.")
                "set_favorite" -> {
                    val item = a.str("itemId") ?: halt(400, "invalid-request", "itemId is required.")
                    val (status, _) = applyState(p, if (a.path("favorite").asBoolean()) "favorite" else "unfavorite", item, now(), null)
                    if (status == 404) halt(404, "not-found", "No such item.")
                    val s = stateJson(p.user.id, item)
                    mapOf("itemId" to item, "favoritedAt" to s["favoritedAt"], "rating" to s["rating"], "libraryAddedAt" to s["libraryAddedAt"])
                }
                "list_playlists" -> mapOf("items" to playlists.values.filter { !it.deleted && it.owner == p.user.id }.take(a.path("limit").asInt(20).coerceIn(1, 100))
                    .map { mapOf("id" to it.id, "name" to it.name, "itemCount" to it.entries.size, "revision" to it.revision) }, "nextCursor" to null)
                "get_playlist" -> {
                    val pl = visiblePlaylist(a.str("id") ?: "", p)
                    mapOf("playlist" to mapOf("id" to pl.id, "name" to pl.name, "itemCount" to pl.entries.size, "revision" to pl.revision),
                        "entries" to pl.entries.take(a.path("limit").asInt(20)).mapNotNull { e -> tracks[e.itemId]?.let { mapOf("entryId" to e.playlistEntryId, "track" to trackBrief(it)) } },
                        "nextCursor" to null)
                }
                "delete_playlist" -> {
                    val pl = playlists[a.str("playlistId")]?.takeIf { !it.deleted && it.owner == p.user.id } ?: halt(404, "not-found", "No such playlist.")
                    if (a.str("revision") != pl.revision) halt(412, "revision-mismatch", "The playlist changed since that revision.")
                    pl.deleted = true
                    journal("playlist", pl.id, "delete", pl.owner, null, null, "deleted")
                    mapOf("deleted" to true)
                }
                "get_queue" -> {
                    val q = queueOf(p.user.id)
                    val i = q.entries.indexOfFirst { it.entryId == q.currentEntryId }
                    mapOf("activeDevice" to q.activeDeviceId, "state" to q.playback?.get("state"),
                        "current" to q.entries.getOrNull(i)?.let { tracks[it.itemId] }?.let { trackBrief(it) },
                        "upNext" to q.entries.drop(i + 1).take(a.path("limit").asInt(20)).mapNotNull { tracks[it.itemId]?.let { t -> trackBrief(t) } },
                        "entryCount" to q.entries.size)
                }
                else -> halt(501, "server-error", "The mock does not implement $name.")
            }
        } catch (h: Halt) {
            return rpc(id, mapOf("content" to listOf(mapOf("type" to "text", "text" to String(h.resp.body, StandardCharsets.UTF_8))), "isError" to true))
        }
        return rpc(id, mapOf("content" to listOf(mapOf("type" to "text", "text" to json.writeValueAsString(structured))), "structuredContent" to structured, "isError" to false))
    }
}
