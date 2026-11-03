package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.DiscoverSettings
import com.legitimateapps.dulcet.server.v1.j
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Unequal-prefix mounts must not borrow another configured authority's handlers or policies. */
class ConnectionsMountConformanceTest {
    private fun context(routes: List<ClientRoute>): ServerContext {
        val root = Files.createTempDirectory("connections-mounts")
        return ServerContext(Config(
            root.resolve("music").also(Files::createDirectories), root.resolve("data").also(Files::createDirectories),
            4533, "127.0.0.1", Fixtures.ffmpeg, Fixtures.ffprobe, null, scanOnStart = false,
            routes = routes, discover = DiscoverSettings(enabled = false),
        ), Db.inMemory())
    }

    private fun checkMounts(firstBase: String, secondBase: String, reverse: Boolean) = testApplication {
        val routes = listOf(ClientRoute("first", firstBase), ClientRoute("second", secondBase)).let {
            if (reverse) it.reversed() else it
        }
        val ctx = context(routes)
        val user = ctx.users.create("member", "fixture-secret", false)
        val token = ctx.apiAuth.bootstrapPersonal(user, setOf("user:read", "user:write"))
        application { dulcetModule(ctx) }
        val fixture = createClient {
            defaultRequest { url.protocol = URLProtocol.HTTPS }
            install(HttpTimeout) { requestTimeoutMillis = 5_000 }
        }
        try {
            for (route in routes) {
                // On the right authority/base, the ordinary API remains reachable.
                val me = fixture.get("${route.prefix}/dulcet/v1/me") { header("Host", route.uri.authority); bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, me.status, route.base)
                val query = fixture.get("${route.prefix}/dulcet/v1/me?token=synthetic-canary") {
                    header("Host", route.uri.authority); bearerAuth(token)
                }
                assertEquals(HttpStatusCode.BadRequest, query.status, "Every API mount must reject query credentials: ${route.base}")
                val key = UUID.randomUUID().toString()
                val name = "Fixture-${route.id}"
                suspend fun create() = fixture.post("${route.prefix}/dulcet/v1/playlists") {
                    header("Host", route.uri.authority); bearerAuth(token); header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json); setBody(j("name" to name, "itemIds" to emptyList<String>()).toString())
                }
                val first = create(); val replay = create()
                assertEquals(HttpStatusCode.Created, first.status)
                assertEquals(HttpStatusCode.Created, replay.status)
                val body = first.bodyAsText()
                assertEquals(body, replay.bodyAsText(), "A repeated key must return the identical resource")
                val id = Json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content
                assertEquals("${route.base}/dulcet/v1/playlists/$id", replay.headers[HttpHeaders.Location])
                assertEquals(1L, ctx.db.read { long("SELECT COUNT(*) FROM playlist WHERE name=?", name) })
            }
            // A globally installed prefix on another authority must be rejected before any handler.
            if (routes[0].uri.authority != routes[1].uri.authority) {
                for (receiving in routes) for (mounted in routes.filter { it != receiving }) {
                    // The shorter base can accept the path during receiving-route selection. The
                    // matched longer mount must nevertheless refuse it, instead of bypassing policy.
                    if (mounted.prefix.startsWith(receiving.prefix + "/") || receiving.prefix.isEmpty()) {
                        val before = ctx.db.read { long("SELECT COUNT(*) FROM playlist") }
                        for (endpoint in listOf("/dulcet/v1/me?token=synthetic-canary", "/.well-known/dulcet", "/.well-known/oauth-authorization-server", "/rest/getOpenSubsonicExtensions?f=json")) {
                            val response = fixture.get(mounted.prefix + endpoint) { header("Host", receiving.uri.authority); bearerAuth(token) }
                            assertEquals(HttpStatusCode.fromValue(421), response.status, "${receiving.base} must not expose ${mounted.prefix}$endpoint")
                        }
                        val key = UUID.randomUUID().toString()
                        repeat(2) {
                            val rejected = fixture.post("${mounted.prefix}/dulcet/v1/playlists") {
                                header("Host", receiving.uri.authority); bearerAuth(token); header("Idempotency-Key", key)
                                contentType(ContentType.Application.Json); setBody(j("name" to "Rejected fixture", "itemIds" to emptyList<String>()).toString())
                            }
                            assertEquals(HttpStatusCode.fromValue(421), rejected.status)
                        }
                        assertEquals(before, ctx.db.read { long("SELECT COUNT(*) FROM playlist") })
                    }
                }
            }
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `nested API mount applies the limiter exactly once per request`() = testApplication {
        val ctx = context(listOf(ClientRoute("root", "https://home.example.test"), ClientRoute("nested", "https://home.example.test/music/private")))
        val user = ctx.users.create("member", "fixture-secret", false)
        val token = ctx.apiAuth.bootstrapPersonal(user, setOf("user:read"))
        ctx.dulcetApi.limiter.maxRequests = 2
        application { dulcetModule(ctx) }
        val fixture = createClient { defaultRequest { url.protocol = URLProtocol.HTTPS } }
        try {
            suspend fun get() = fixture.get("/music/private/dulcet/v1/me") { header("Host", "home.example.test"); bearerAuth(token) }
            repeat(2) { assertEquals(HttpStatusCode.OK, get().status) }
            val limited = get()
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        } finally { ctx.close(); ctx.db.close() }
    }

    @Test fun `different authorities root then prefix`() = checkMounts("https://root.example.test", "https://home.example.test/music", false)
    @Test fun `different authorities prefix then root`() = checkMounts("https://root.example.test", "https://home.example.test/music", true)
    @Test fun `different authorities nested prefixes`() = checkMounts("https://root.example.test/music", "https://home.example.test/music/private", false)
    @Test fun `different authorities reverse nested prefixes`() = checkMounts("https://root.example.test/music", "https://home.example.test/music/private", true)
    @Test fun `same authority root and prefix controls`() = checkMounts("https://home.example.test", "https://home.example.test/music", false)
    @Test fun `same authority nested prefixes controls`() = checkMounts("https://home.example.test/music", "https://home.example.test/music/private", true)
}
