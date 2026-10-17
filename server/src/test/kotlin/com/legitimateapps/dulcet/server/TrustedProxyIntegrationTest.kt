package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real sockets pin the trust decision to the actual numeric loopback peer. */
class TrustedProxyIntegrationTest {
    private class Fixture(trusted: String? = null) : AutoCloseable {
        private val root = Files.createTempDirectory("trusted-proxy")
        private val config = Config.load(
            mapOf("music" to root.resolve("music").also(Files::createDirectories).toString(),
                "data" to root.resolve("data").also(Files::createDirectories).toString(),
                "bind" to "127.0.0.1", "port" to "0", "lrclib" to "off", "scan-on-start" to "false"),
            trusted?.let { mapOf("DULCET_TRUSTED_PROXIES" to it) } ?: emptyMap(),
        )
        val ctx = ServerContext(config, Db.open(config.dataFolder.resolve("dulcet.db")))
        private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { dulcetModule(ctx) }.start(wait = false)
        private val port = runBlocking { server.engine.resolvedConnectors().first().port }
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        init { ctx.users.create("listener", "correct-password", false) }

        fun request(path: String, headers: List<Pair<String, String>>, form: String? = null): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
            headers.forEach { (name, value) -> request.header(name, value) }
            if (form == null) request.GET() else request.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun rest(password: String, headers: List<Pair<String, String>>): String {
            val response = request("/rest/ping?u=listener&p=$password&v=1.16.1&c=proxy-test&f=json", headers)
            assertEquals(200, response.statusCode(), "REST auth errors retain the Subsonic transport contract")
            val root = Json.parseToJsonElement(response.body()).jsonObject["subsonic-response"]!!.jsonObject
            val status = root["status"]!!.jsonPrimitive.content
            if (status == "failed") assertEquals("40", root["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            return status
        }

        fun lock(headers: (Int) -> List<Pair<String, String>>) {
            repeat(10) { assertEquals("failed", rest("wrong-password", headers(it))) }
        }

        override fun close() {
            server.stop(100, 1000)
            ctx.db.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun forwarded(ip: String) = listOf("CF-Connecting-IP" to ip, "X-Real-IP" to ip, "X-Forwarded-For" to ip)

    @Test fun `empty default ignores all spoofed forwarding headers for REST lockout`() {
        Fixture().use { fixture ->
            assertEquals("ok", fixture.rest("correct-password", forwarded("198.51.100.1")))
            fixture.lock { forwarded("198.51.100.${it + 1}") }
            assertEquals("failed", fixture.rest("correct-password", forwarded("203.0.113.99")))
        }
    }

    @Test fun `untrusted socket peer ignores all forwarding headers even when proxies are configured`() {
        Fixture("192.0.2.0/24").use { fixture ->
            assertEquals("ok", fixture.rest("correct-password", forwarded("198.51.100.1")))
            fixture.lock { forwarded("198.51.100.${it + 1}") }
            assertEquals("failed", fixture.rest("correct-password", forwarded("203.0.113.99")))
        }
    }

    private fun isolatesRestClients(header: String) {
        Fixture("127.0.0.1/32").use { fixture ->
            val first = listOf(header to "198.51.100.1")
            val other = listOf(header to "198.51.100.2")
            assertEquals("ok", fixture.rest("correct-password", first))
            fixture.lock { first }
            assertEquals("ok", fixture.rest("correct-password", other), "A failed client must not lock out its proxy's other clients")
            assertEquals("failed", fixture.rest("correct-password", first), "The original client remains locked out")
        }
    }

    @Test fun `trusted socket peer isolates REST clients using CF Connecting IP`() = isolatesRestClients("CF-Connecting-IP")
    @Test fun `trusted socket peer isolates REST clients using X Real IP`() = isolatesRestClients("X-Real-IP")
    @Test fun `trusted socket peer isolates REST clients using X Forwarded For`() = isolatesRestClients("X-Forwarded-For")

    @Test fun `XFF uses rightmost untrusted boundary rather than attacker controlled prefix`() {
        Fixture("127.0.0.1,10.0.0.0/8").use { fixture ->
            fun chain(prefix: Int, boundary: Int = 1) = listOf("X-Forwarded-For" to "203.0.113.$prefix, 198.51.100.$boundary, 10.1.2.3, 10.4.5.6")
            assertEquals("ok", fixture.rest("correct-password", chain(1)))
            fixture.lock { chain(it + 1) }
            assertEquals("failed", fixture.rest("correct-password", chain(99)))
            assertEquals("ok", fixture.rest("correct-password", chain(99, 2)))
        }
    }

    @Test fun `malformed or repeated preferred headers fail closed to socket peer`() {
        val invalid = listOf(
            listOf("CF-Connecting-IP" to "not-an-address", "X-Real-IP" to "198.51.100.1"),
            listOf("CF-Connecting-IP" to "198.51.100.1, 198.51.100.2"),
            listOf("CF-Connecting-IP" to "198.51.100.1", "CF-Connecting-IP" to "198.51.100.2"),
            listOf("X-Real-IP" to "198.51.100.1", "X-Real-IP" to "198.51.100.2"),
            listOf("X-Forwarded-For" to "198.51.100.1, invalid, 10.1.2.3"),
            listOf("X-Forwarded-For" to "198.51.100.1", "X-Forwarded-For" to "198.51.100.2"),
        )
        Fixture("127.0.0.1,10.0.0.0/8").use { fixture ->
            fixture.lock { emptyList() }
            invalid.forEach { headers ->
                assertEquals("failed", fixture.rest("correct-password", headers), "Invalid headers must retain the locked socket bucket: $headers")
            }
            assertEquals("ok", fixture.rest("correct-password", listOf("CF-Connecting-IP" to "198.51.100.3")))
        }
    }

    @Test fun `equivalent IPv6 spellings and mapped IPv4 cannot evade REST lockout`() {
        Fixture("127.0.0.1").use { fixture ->
            fixture.lock { listOf("CF-Connecting-IP" to "2001:db8:0:0:0:0:0:1") }
            assertEquals("failed", fixture.rest("correct-password", listOf("CF-Connecting-IP" to "2001:db8::1")))
            assertEquals("ok", fixture.rest("correct-password", listOf("CF-Connecting-IP" to "2001:db8::2")))
            fixture.lock { listOf("CF-Connecting-IP" to "198.51.100.1") }
            assertEquals("failed", fixture.rest("correct-password", listOf("CF-Connecting-IP" to "::ffff:198.51.100.1")))
        }
    }

    @Test fun `OAuth IP rate bucket isolates clients behind a trusted proxy`() {
        Fixture("127.0.0.1").use { fixture ->
            // Omitting client_id reaches OAuth validation without also filling a shared client bucket.
            fun token(ip: String) = fixture.request("/oauth/token", listOf("X-Real-IP" to ip), "grant_type=invalid")
            repeat(30) { assertEquals(400, token("198.51.100.1").statusCode()) }
            val limited = token("198.51.100.1")
            assertEquals(429, limited.statusCode())
            assertEquals("60", limited.headers().firstValue("Retry-After").orElse(null))
            assertEquals(400, token("198.51.100.2").statusCode(), "A distinct client must reach OAuth validation")
        }
    }

    @Test fun `API IP rate bucket isolates clients behind a trusted proxy`() {
        Fixture("127.0.0.1").use { fixture ->
            fixture.ctx.dulcetApi.limiter.maxRequests = 3
            fun me(ip: String) = fixture.request("/dulcet/v1/me", listOf("CF-Connecting-IP" to ip))
            repeat(3) { assertEquals(401, me("198.51.100.1").statusCode()) }
            val limited = me("198.51.100.1")
            assertEquals(429, limited.statusCode())
            assertEquals("60", limited.headers().firstValue("Retry-After").orElse(null))
            assertEquals(401, me("198.51.100.2").statusCode(), "A distinct client must reach authentication")
        }
    }
}
