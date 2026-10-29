package com.legitimateapps.dulcet.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** The production connector/factory/probe over real TLS, trusting only this disposable test root. */
class ConnectionTlsEnrollmentTest {
    @Test fun authenticatedTlsEnrollmentAdmitsOnlyExpectedLineageAndTlsNamesWithoutChangingOwnership() {
        TlsFixture().use { fixture ->
            fixture.assertTlsNameControl()
            val previousStore = System.getProperty("javax.net.ssl.trustStore")
            val previousPassword = System.getProperty("javax.net.ssl.trustStorePassword")
            System.setProperty("javax.net.ssl.trustStore", fixture.keyStore.toString())
            System.setProperty("javax.net.ssl.trustStorePassword", PASSWORD)
            try {
                withSeenCache { env, _ ->
                    val anchor = OwnershipAnchor("tls-enrollment-fixture", fixture.base(0), "listener", ServerIdentityPolicy.identity("dulcet", "fixture-lineage"))
                    env.bind(CacheBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username))
                    ServerIdentityStore(env.database).accept(anchor.accountId, anchor.serverIdentity)
                    env.database.libraryDepthQueries.putEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY,
                        encodeMediaBinding(DownloadMediaBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username)), 0)
                    val storedBinding = env.database.seenCacheQueries.selectBinding(anchor.accountId).executeAsOne()
                    try {
                        val result = runBlocking {
                            ConnectionEnrollmentService(env.database).enroll(ConnectionEnrollmentRequest(anchor,
                                AccountConnectionRequest(anchor.normalizedBaseUrl, "listener", "fixture-password")))
                        }
                        val snapshot = assertIs<ConnectionEnrollmentResult.Enrolled>(result).snapshot
                        assertEquals(setOf("home", "remote"), snapshot.routes.filter { it.enabled }.map { it.id }.toSet())
                        assertFalse(snapshot.routes.single { it.id == "different" }.enabled)
                        assertFalse(snapshot.routes.single { it.id == "bad-name" }.enabled)
                        assertFalse(snapshot.routes.any { it.id == "http" })
                        assertEquals(storedBinding, env.database.seenCacheQueries.selectBinding(anchor.accountId).executeAsOne())
                        val directory = fixture.requests.filter { it.path == "/music/rest/getDulcetConnections.view" }.single()
                        assertEquals("POST", directory.method)
                        assertNull(directory.query)
                        assertTrue(directory.authBody)
                        assertTrue(fixture.requests.filter { it.port != fixture.port(0) }.all { !it.authBody && !it.authQuery })
                        assertTrue(fixture.requests.any { it.port == fixture.port(1) && it.path == "/music/.well-known/dulcet" })
                        assertFalse(fixture.requests.any { it.port == fixture.port(3) })
                        assertFalse(fixture.requests.any { it.port == fixture.port(4) })
                        // A positively admitted alternate actually serves a fresh authenticated request.
                        ConnectionEnrollmentStore(env.database).selectForFixture(anchor, "remote")
                        val response = runBlocking {
                            val client = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(anchor.normalizedBaseUrl,
                                "listener", "fixture-password", false), "fixture.connections")
                            try { client.request("ping", emptyMap()) } finally { client.close() }
                        }
                        assertEquals(200, response.statusCode)
                        assertTrue(fixture.requests.any { it.port == fixture.port(1) && it.path == "/music/rest/ping.view" && it.authQuery })
                        assertEquals(anchor.normalizedBaseUrl, snapshot.anchor.normalizedBaseUrl)
                    } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
                }
            } finally {
                restoreProperty("javax.net.ssl.trustStore", previousStore)
                restoreProperty("javax.net.ssl.trustStorePassword", previousPassword)
            }
        }
    }

    private data class Request(val port: Int, val path: String, val method: String, val query: String?, val authBody: Boolean, val authQuery: Boolean)
    private class TlsFixture : Closeable {
        private val directory = Files.createTempDirectory("dulcet-tls-enrollment-")
        val keyStore = directory.resolve("fixture.p12")
        val requests = CopyOnWriteArrayList<Request>()
        private val servers: List<HttpsServer>
        init {
            val keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "fixture key generation failed: $output" }
            val keys = KeyStore.getInstance("PKCS12").also { Files.newInputStream(keyStore).use { input -> it.load(input, PASSWORD.toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also { it.init(keys, PASSWORD.toCharArray()) }
            val ssl = SSLContext.getInstance("TLS").also { it.init(managers.keyManagers, null, null) }
            servers = List(5) { HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
                server.httpsConfigurator = HttpsConfigurator(ssl)
                server.createContext("/") { call ->
                    val body = call.requestBody.bufferedReader().readText()
                    val query = call.requestURI.rawQuery
                    requests += Request(server.address.port, call.requestURI.path, call.requestMethod, query,
                        body.contains("u=listener") && body.contains("t=") && body.contains("s="),
                        query?.contains("u=listener") == true)
                    val localBase = "https://localhost:${server.address.port}/music"
                    val response = when (call.requestURI.path) {
                        "/.well-known/dulcet", "/music/.well-known/dulcet" -> """{"standard":"dulcet","apis":[{"version":"1","baseUrl":"$localBase/dulcet/v1","status":"current"}],"server":{"instanceId":"${if (server.address.port == port(2)) "different-lineage" else "fixture-lineage"}"},"authorizationServer":"$localBase"}"""
                        "/music/rest/getOpenSubsonicExtensions.view" -> envelope("\"openSubsonicExtensions\":[{\"name\":\"formPost\",\"versions\":[1]},{\"name\":\"dulcetRoutes\",\"versions\":[1]}]")
                        "/music/rest/getUser.view" -> envelope("\"user\":{\"username\":\"listener\",\"downloadRole\":true}")
                        "/music/rest/getDulcetConnections.view" -> envelope(""""dulcetConnections":{"version":1,"revision":7,"instanceId":"fixture-lineage","principal":{"id":"fixture-user","username":"listener"},"routes":[{"id":"home","baseUrl":"${base(0)}","role":"private","priority":0},{"id":"remote","baseUrl":"${base(1)}","role":"public","priority":1},{"id":"different","baseUrl":"${base(2)}","role":"private","priority":2},{"id":"bad-name","baseUrl":"https://127.0.0.1:${port(3)}/music","role":"private","priority":3},{"id":"http","baseUrl":"http://localhost:${port(4)}/music","role":"local","priority":4}],"legacyOwnershipAnchors":[],"authAliases":[]}""")
                        else -> envelope()
                    }.encodeToByteArray()
                    call.responseHeaders.add("Content-Type", "application/json")
                    call.responseHeaders.add("Cache-Control", "private, no-store")
                    call.sendResponseHeaders(200, response.size.toLong())
                    call.responseBody.use { it.write(response) }
                }
                server.start()
            } }
        }
        fun assertTlsNameControl() {
            val keys = KeyStore.getInstance("PKCS12").also { Files.newInputStream(keyStore).use { input -> it.load(input, PASSWORD.toCharArray()) } }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(keys) }
            val ssl = SSLContext.getInstance("TLS").also { it.init(null, trust.trustManagers, null) }
            fun open(host: String): Int {
                val connection = URI("https://$host:${port(3)}/music/.well-known/dulcet").toURL().openConnection() as HttpsURLConnection
                connection.sslSocketFactory = ssl.socketFactory
                connection.connectTimeout = 3000; connection.readTimeout = 3000
                return try { connection.responseCode } finally { connection.disconnect() }
            }
            assertEquals(200, open("localhost"), "trusted matching SAN positive control")
            assertFailsWith<SSLHandshakeException>("trusted certificate without an IP SAN must reject the numeric host") { open("127.0.0.1") }
            requests.clear()
        }
        fun port(index: Int) = servers[index].address.port
        fun base(index: Int) = "https://localhost:${port(index)}/music"
        private fun envelope(member: String = "") = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"dulcet","openSubsonic":true${if (member.isEmpty()) "" else ",$member"}}}"""
        override fun close() { servers.forEach { it.stop(0) }; directory.toFile().deleteRecursively() }
    }
    companion object {
        private const val PASSWORD = "fixture-keystore-password"
        private fun restoreProperty(name: String, value: String?) { if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
    }
}
