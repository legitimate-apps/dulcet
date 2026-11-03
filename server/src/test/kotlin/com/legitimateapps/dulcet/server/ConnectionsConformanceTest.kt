package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** MP-01/11 server evidence. All origins are synthetic and every server/database is disposable. */
class ConnectionsConformanceTest {
    private val home = ClientRoute("home", "https://home.example.test/music")
    private val remote = ClientRoute("remote", "https://remote.example.test/music", "public", 20)
    private fun config(root: Path, routes: List<ClientRoute> = listOf(home, remote)) = Config(
        root.resolve("music").also(Files::createDirectories), root.resolve("data").also(Files::createDirectories),
        4533, "127.0.0.1", Fixtures.ffmpeg, Fixtures.ffprobe, null, scanOnStart = false,
        publicUrl = "https://global.example.test", trustedProxies = listOf("127.0.0.0/8"), routes = routes,
        legacyOwnershipAnchors = listOf("http://legacy.example.test:4533/music"),
        discover = com.legitimateapps.dulcet.server.discover.DiscoverSettings(enabled = false),
    )
    private fun context(routes: List<ClientRoute> = listOf(home, remote)): ServerContext =
        ServerContext(config(Files.createTempDirectory("connections-server"), routes), Db.inMemory())
    private fun form(vararg values: Pair<String, String>) = FormDataContent(Parameters.build { values.forEach { (key, value) -> append(key, value) } })
    private fun auth(password: String = "fixture-secret"): FormDataContent {
        val salt = "f0e1d2c3b4a59687"
        val token = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        return form("u" to "member", "t" to token, "s" to salt, "v" to "1.16.1", "c" to "fixture", "f" to "json")
    }
    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.directory() = getValue("subsonic-response").jsonObject.getValue("dulcetConnections").jsonObject

    @Test fun `MP01 directory uses body auth is private and returns same revision and principal on both bases`() = testApplication {
        val ctx = context(); val user = ctx.users.create("member", "fixture-secret", false)
        application { dulcetModule(ctx) }
        val fixtureClient = createClient { defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            val directory = fixtureClient.post("/music/rest/getDulcetConnections.view") { header("Host", "home.example.test"); setBody(auth()) }
            assertEquals(HttpStatusCode.OK, directory.status)
            assertEquals("private, no-store", directory.headers[HttpHeaders.CacheControl])
            val first = directory.obj().directory()
            assertEquals(1, first.getValue("version").jsonPrimitive.int)
            assertEquals(ctx.journal.instanceId, first.text("instanceId"))
            assertEquals(user.id, first.getValue("principal").jsonObject.text("id"))
            assertEquals("member", first.getValue("principal").jsonObject.text("username"))
            assertEquals(setOf(home.base, remote.base), first.getValue("routes").jsonArray.map { it.jsonObject.text("baseUrl") }.toSet())
            assertEquals("http://legacy.example.test:4533/music", first.getValue("legacyOwnershipAnchors").jsonArray.single().jsonPrimitive.content)
            val other = fixtureClient.post("/music/rest/getDulcetConnections.view") { header("Host", "remote.example.test"); setBody(auth()) }.obj().directory()
            assertEquals(first, other)
            val discovery = fixtureClient.get("/music/.well-known/dulcet") { header("Host", "home.example.test") }.obj()
            assertFalse(discovery.toString().contains("remote.example.test"), "Public discovery must not publish private topology")
            assertEquals("https://home.example.test/music/dulcet/v1", discovery.getValue("apis").jsonArray.single().jsonObject.text("baseUrl"))
            assertEquals("https://home.example.test/music/rest/getDulcetConnections.view", discovery.getValue("routeDirectory").jsonObject.text("url"))
            val extensions = fixtureClient.get("/music/rest/getOpenSubsonicExtensions?f=json") { header("Host", "home.example.test") }.obj().toString()
            assertTrue(extensions.contains("dulcetRoutes")); assertTrue(extensions.contains("formPost"))
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `MP01 GET query credentials failed authentication and oversized request fail without directory`() = testApplication {
        val ctx = context(); ctx.users.create("member", "fixture-secret", false)
        application { dulcetModule(ctx) }
        val fixtureClient = createClient { defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            val rejected = listOf(
                fixtureClient.get("/music/rest/getDulcetConnections.view?u=member&p=fixture-secret&f=json") { header("Host", "home.example.test") },
                fixtureClient.post("/music/rest/getDulcetConnections.view?u=member") { header("Host", "home.example.test"); setBody(auth()) },
                fixtureClient.post("/music/rest/getDulcetConnections.view") { header("Host", "home.example.test"); setBody(auth("wrong")) },
                fixtureClient.post("/music/rest/getDulcetConnections.view") { header("Host", "home.example.test"); setBody(form("u" to "member", "p" to "fixture-secret", "f" to "json")) },
            )
            rejected.forEach { response ->
                assertEquals("private, no-store", response.headers["Cache-Control"])
                assertFalse(response.bodyAsText().contains("dulcetConnections"))
                assertNull(response.headers["Location"])
            }
            val tooLarge = fixtureClient.post("/music/rest/getDulcetConnections.view") {
                header("Host", "home.example.test"); contentType(ContentType.Application.FormUrlEncoded); setBody("x=" + "x".repeat(65536))
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertEquals("private, no-store", tooLarge.headers["Cache-Control"])
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `MP01 unknown host forwarded authority and escaped base fail before authentication`() = testApplication {
        val ctx = context()
        application { dulcetModule(ctx) }
        val fixtureClient = createClient { defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            for ((path, host, forwarded) in listOf(
                Triple("/music/rest/getDulcetConnections.view", "evil.example.test", null),
                Triple("/music2/rest/getDulcetConnections.view", "home.example.test", null),
                Triple("/music/rest/getDulcetConnections.view", "home.example.test", "evil.example.test"),
            )) {
                val response = fixtureClient.post(path) { header("Host", host); forwarded?.let { header("X-Forwarded-Host", it) }; setBody(auth()) }
                assertEquals(421, response.status.value)
                assertNull(response.headers["Location"])
                assertFalse(response.bodyAsText().contains("Wrong username"))
            }
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `MP11 issuers use receiving configured TLS base despite publicUrl and HTTP backend`() = testApplication {
        val ctx = context()
        application { dulcetModule(ctx) }
        val fixtureClient = createClient { defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            for (route in listOf(home, remote)) {
                val metadata = fixtureClient.get("/music/.well-known/oauth-authorization-server") { header("Host", route.uri.authority) }.obj()
                assertEquals(route.base, metadata.text("issuer"))
                assertEquals("${route.base}/oauth/token", metadata.text("token_endpoint"))
            }
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `MP01 route relative grant preserves base and actual media bytes`() = testApplication {
        val ctx = context(); val user = ctx.users.create("member", "fixture-secret", false)
        Fixtures.track(ctx.config.musicFolder.resolve("fixture.flac"), tags = mapOf("title" to "Fixture", "artist" to "Fixture", "album" to "Fixture")); ctx.scanner.scan()
        val track = ctx.db.read { string("SELECT id FROM track LIMIT 1")!! }
        val token = ctx.apiAuth.bootstrapPersonal(user, setOf("media:read", "catalog:read"))
        application { dulcetModule(ctx) }
        val fixtureClient = createClient { defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            for (route in listOf(home, remote)) {
                val plan = fixtureClient.post("/music/dulcet/v1/playback/decisions") {
                    header("Host", route.uri.authority); bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(j("trackId" to track, "profile" to j("directPlay" to listOf(j("container" to "flac", "codec" to "flac")))).toString())
                }
                assertEquals(HttpStatusCode.OK, plan.status)
                val resource = plan.obj().text("url")
                assertTrue(resource.startsWith("dulcet/v1/media/")); assertFalse(resource.startsWith('/'))
                val bytes = fixtureClient.get("/music/$resource") { header("Host", route.uri.authority) }
                assertEquals(HttpStatusCode.OK, bytes.status)
                assertContentEquals(Files.readAllBytes(ctx.config.musicFolder.resolve("fixture.flac")), bytes.readRawBytes())
            }
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `MP11 code issuer stays pinned and refresh rotates across explicitly authorized aliases`() = testApplication {
        val root = Files.createTempDirectory("connections-oauth")
        val resourceOnly = ClientRoute("resource", "https://resource.example.test/music", authAlias = false)
        val ctx = ServerContext(config(root, listOf(home, remote, resourceOnly)).copy(testMode = true), Db.inMemory())
        val user = ctx.users.create("member", "fixture-secret", false)
        val cid = "https://client.example.test/client.json"; val redirect = "com.example.player:/oauth/callback"
        ctx.testing.replace(j("autoApprove" to listOf(j("clientId" to cid, "userId" to user.id)),
            "trustedClients" to listOf(j("client_id" to cid, "client_name" to "Fixture", "redirect_uris" to listOf(redirect)))))
        application { dulcetModule(ctx) }
        val fixtureClient = createClient { followRedirects = false; defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            val verifier = "v".repeat(64)
            val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val started = fixtureClient.get("/music/oauth/authorize") {
                header("Host", home.uri.authority); parameter("client_id", cid); parameter("scope", "user:read offline_access")
                parameter("response_type", "code"); parameter("state", "fixture-state"); parameter("redirect_uri", redirect)
                parameter("code_challenge", challenge); parameter("code_challenge_method", "S256")
            }
            assertEquals(HttpStatusCode.Found, started.status)
            val query = parseQueryString(started.headers[HttpHeaders.Location]!!.substringAfter('?'))
            assertEquals(home.base, query["iss"])
            suspend fun exchange(route: ClientRoute) = fixtureClient.post("/music/oauth/token") {
                header("Host", route.uri.authority); setBody(form("grant_type" to "authorization_code", "client_id" to cid,
                    "redirect_uri" to redirect, "code" to query["code"]!!, "code_verifier" to verifier))
            }
            assertEquals(HttpStatusCode.BadRequest, exchange(remote).status, "Code exchange cannot change issuer")
            val minted = exchange(home); assertEquals(HttpStatusCode.OK, minted.status); val pair = minted.obj()
            val access = ctx.apiAuth.authenticate("Bearer " + pair.text("access_token"))!!
            assertEquals(home.base, ctx.db.read { string("SELECT issuance_origin FROM api_grant WHERE id=?", access.tokenId) })
            suspend fun refresh(route: ClientRoute) = fixtureClient.post("/music/oauth/token") {
                header("Host", route.uri.authority); setBody(form("grant_type" to "refresh_token", "client_id" to cid, "refresh_token" to pair.text("refresh_token")))
            }
            assertEquals(HttpStatusCode.BadRequest, refresh(resourceOnly).status, "Resource aliases do not authorize refresh")
            val refreshed = refresh(remote); assertEquals(HttpStatusCode.OK, refreshed.status); val rotated = refreshed.obj()
            assertNotEquals(pair.text("refresh_token"), rotated.text("refresh_token"))
            assertEquals(user.id, ctx.apiAuth.authenticate("Bearer " + rotated.text("access_token"))!!.user.id)
            assertEquals(rotated, refresh(home).obj(), "Rotation retry returns the identical pair across aliases")
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `MP01 actual HTTP backend accepts only configured Host from explicitly trusted socket proxy`() {
        val ctx = context(); ctx.users.create("member", "fixture-secret", false)
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) { dulcetModule(ctx) }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }
            fun request(host: String, method: String = "getDulcetConnections.view", prefix: String = "/music", discovery: Boolean = false): String = java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val salt = "fixture-salt"; val token = MessageDigest.getInstance("MD5").digest(("fixture-secret" + salt).toByteArray()).joinToString("") { "%02x".format(it) }
                val body = "u=member&t=$token&s=$salt&v=1.16.1&c=fixture&f=json"
                val path = if(discovery) "$prefix/.well-known/dulcet" else "$prefix/rest/$method"
                val wire = "POST $path HTTP/1.0\r\nHost: $host\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                socket.getOutputStream().write(wire.toByteArray()); socket.getOutputStream().flush()
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            val valid = request("home.example.test")
            assertTrue(valid.startsWith("HTTP/1.1 200") || valid.startsWith("HTTP/1.0 200"), valid.substringBefore("\r\n"))
            assertTrue(valid.contains("dulcetConnections")); assertTrue(valid.contains("private, no-store"))
            val legacy = request("legacy.example.test:4533", "ping.view")
            assertTrue(legacy.contains("\"status\":\"ok\""), legacy.substringBefore("\r\n"))
            val legacyIdentity = request("legacy.example.test:4533", discovery = true)
            assertTrue(legacyIdentity.contains(ctx.journal.instanceId))
            assertFalse(legacyIdentity.contains("remote.example.test"))
            assertFalse(legacyIdentity.contains("routeDirectory"))
            val legacyDirectory = request("legacy.example.test:4533")
            assertTrue(legacyDirectory.substringBefore("\r\n").contains("421"))
            val rejected = request("foreign.example.test")
            assertTrue(rejected.substringBefore("\r\n").contains("421"))
            assertFalse(rejected.contains("dulcetConnections"))
        } finally { server.stop(0, 1000); ctx.close(); ctx.db.close() }
    }

    @Test fun `MP11 shared idempotency receipt renders Location for the receiving route`() = testApplication {
        val ctx = context(); val user = ctx.users.create("member", "fixture-secret", false)
        val token = ctx.apiAuth.bootstrapPersonal(user, setOf("user:write", "user:read"))
        application { dulcetModule(ctx) }
        val fixtureClient = createClient { defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            val key = java.util.UUID.randomUUID().toString()
            suspend fun create(route: ClientRoute) = fixtureClient.post("/music/dulcet/v1/playlists") {
                header("Host", route.uri.authority); header("Idempotency-Key", key); bearerAuth(token)
                contentType(ContentType.Application.Json); setBody(j("name" to "Fixture playlist", "itemIds" to emptyList<String>()).toString())
            }
            val first = create(home); assertEquals(HttpStatusCode.Created, first.status); val body = first.obj()
            val replay = create(remote); assertEquals(HttpStatusCode.Created, replay.status); assertEquals(body, replay.obj())
            assertEquals("${home.base}/dulcet/v1/playlists/${body.text("id")}", first.headers["Location"])
            assertEquals("${remote.base}/dulcet/v1/playlists/${body.text("id")}", replay.headers["Location"])
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `MP11 route configuration revision survives restart and increases on removal`() {
        val root = Files.createTempDirectory("connections-restart"); val settings = config(root)
        val dbFile = root.resolve("data/server.db")
        fun open(config: Config) = ServerContext(config, Db.open(dbFile))
        val a = open(settings); val rev = a.connections.revision; val lineage = a.journal.instanceId; a.close(); a.db.close()
        val b = open(settings); assertEquals(rev, b.connections.revision); assertEquals(lineage, b.journal.instanceId); b.close(); b.db.close()
        val c = open(settings.copy(routes = listOf(remote))); assertEquals(rev + 1, c.connections.revision); assertEquals(lineage, c.journal.instanceId); c.close(); c.db.close()
    }

    @Test fun `MP11 principal directory revision advances on role changes and persists stable output`() {
        val root = Files.createTempDirectory("connections-principal-revision")
        val adminRoute = ClientRoute("admin", "https://admin.example.test/music", audience = "admin")
        val settings = config(root, listOf(home, adminRoute))
        val file = root.resolve("data/server.db")
        val member = com.legitimateapps.dulcet.server.auth.User("principal", "member", false)
        val admin = member.copy(isAdmin = true)
        fun open() = ServerContext(settings, Db.open(file))
        fun revision(node: JsonObject) = node["revision"]!!.jsonPrimitive.long
        val first = open()
        val ordinary: JsonObject
        val demoted: JsonObject
        try {
            ordinary = first.connections.directory(member).toJson()
            assertEquals(listOf("home"), ordinary["routes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            assertEquals(ordinary, first.connections.directory(member).toJson())
            val promoted = first.connections.directory(admin).toJson()
            assertEquals(revision(ordinary) + 1, revision(promoted))
            assertEquals(2, promoted["routes"]!!.jsonArray.size)
            assertEquals(2, promoted["authAliases"]!!.jsonArray.size)
            assertEquals(promoted, first.connections.directory(admin).toJson())
            demoted = first.connections.directory(member).toJson()
            assertEquals(revision(promoted) + 1, revision(demoted))
            assertEquals(1, demoted["routes"]!!.jsonArray.size)
            assertEquals(1, demoted["authAliases"]!!.jsonArray.size)
        } finally { first.close(); first.db.close() }
        val restarted = open()
        try {
            assertEquals(demoted, restarted.connections.directory(member).toJson())
            val other = restarted.connections.directory(member.copy(id = "other-principal")).toJson()
            assertEquals(restarted.connections.revision, revision(other), "Another principal has an independent revision scope")
            assertEquals(demoted, restarted.connections.directory(member).toJson())
        } finally { restarted.close(); restarted.db.close() }
    }

    @Test fun `MP01 invalid plaintext and ambiguous routes fail at configuration`() {
        for (url in listOf("http://home.example.test/music", "https://u:p@home.example.test/music", "https://home.example.test/music/../other", "https://home.example.test/music%2fother", "https://home.example.test/music?token=x")) {
            assertFailsWith<IllegalArgumentException> { ClientRoute("bad", url) }
        }
        assertFailsWith<IllegalArgumentException> {
            parseLegacyAnchors("""["https://old.example.test/music"]""")
        }
        assertEquals(listOf("http://legacy.example.test:4533/music"),
            parseLegacyAnchors("""["http://LEGACY.example.test:4533/music/"]"""))
        Db.inMemory().use { db ->
            val settings = config(Files.createTempDirectory("connections-invalid-anchor"))
                .copy(legacyOwnershipAnchors = listOf("https://old.example.test/music"))
            assertFailsWith<IllegalArgumentException> { ServerContext(settings, db) }
        }
        assertFailsWith<IllegalArgumentException> { context(listOf(home, home)) }
        assertFailsWith<IllegalArgumentException> { context((1..9).map { ClientRoute("r$it", "https://r$it.example.test") }) }
    }
}
