package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidHttpPlaybackResourceTest {
    @Test fun sameOriginRedirectPreservesTheSignedQueryOnTheReceivingSocket() {
        WireServer { request ->
            if (request.path == "/rest/stream.view") WireReply.redirect("/audio?${request.rawQuery}")
            else WireReply.audio()
        }.use { server ->
            consume(server.url)
            assertEquals(listOf("/rest/stream.view", "/audio"), server.requests.map { it.path })
            val initial = server.requests[0]
            assertSignedInventory(initial.query)
            assertEquals(initial.rawQuery, server.requests[1].rawQuery)
            assertEquals("127.0.0.1:${server.port}", initial.host)
        }
    }

    @Test fun crossOriginRedirectStripsAllCredentialCanariesOnTheReceivingSocket() {
        WireServer { WireReply.audio() }.use { target ->
            WireServer { request -> WireReply.redirect(
                "${target.url}/audio?${request.rawQuery}&p=$PASSWORD&U=UPPER_USER_CANARY&%74=ENCODED_TOKEN_CANARY&keep=opaque-canary")
            }.use { source ->
                consume(source.url)
                assertEquals(1, source.requests.size)
                assertSignedInventory(source.requests.single().query)
                assertEquals(1, target.requests.size, "Must actually reach the second origin")
                val received = target.requests.single()
                assertEquals(setOf("v", "c", "f", "id", "keep"), received.query.keys)
                assertEquals(listOf("opaque-canary"), received.query["keep"])
                for (canary in canaries + listOf("UPPER_USER_CANARY", "ENCODED_TOKEN_CANARY"))
                    assertFalse(received.rawQuery.contains(canary), "Credential canary reached the second origin")
            }
        }
    }

    @ConscryptMode(ConscryptMode.Mode.OFF)
    @Test fun httpsSameOriginRedirectPreservesExactlyOneSignedQuery() = withTls { tls ->
        WireServer(tls) { request ->
            if (request.path == "/rest/stream.view") WireReply.redirect("/audio?${request.rawQuery}")
            else WireReply.audio()
        }.use { server ->
            consume(server.url)
            assertEquals(listOf("/rest/stream.view", "/audio"), server.requests.map { it.path })
            assertSignedInventory(server.requests.first().query)
            assertEquals(server.requests.first().rawQuery, server.requests.last().rawQuery)
            assertSignedInventory(server.requests.last().query)
        }
    }

    @ConscryptMode(ConscryptMode.Mode.OFF)
    @Test fun httpsCrossOriginRedirectStripsCredentialsAndPreservesMetadataExactlyOnce() = withTls { tls ->
        WireServer(tls) { WireReply.audio() }.use { target ->
            WireServer(tls) { request -> WireReply.redirect(
                "${target.url}/audio?${request.rawQuery}&p=$PASSWORD&U=UPPER_USER_CANARY&%74=ENCODED_TOKEN_CANARY&keep=opaque-canary")
            }.use { source ->
                consume(source.url)
                assertEquals(1, source.requests.size)
                assertSignedInventory(source.requests.single().query)
                assertEquals(1, target.requests.size)
                val received = target.requests.single()
                val expected = source.requests.single().query.filterKeys { it !in setOf("u", "t", "s") } +
                    ("keep" to listOf("opaque-canary"))
                assertEquals(expected, received.query)
                for (canary in canaries + listOf("UPPER_USER_CANARY", "ENCODED_TOKEN_CANARY"))
                    assertFalse(received.rawQuery.contains(canary))
            }
        }
    }

    private fun withTls(block: (SSLContext) -> Unit) {
        FixtureTls().use { tls ->
            val prior = HttpsURLConnection.getDefaultSSLSocketFactory()
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.context.socketFactory)
            try { block(tls.context) }
            finally { HttpsURLConnection.setDefaultSSLSocketFactory(prior) }
        }
    }

    // This fixture uses host-JVM sockets. Conscrypt reflects into java.net.InetAddress on JDK 21,
    // failing before HTTP when java.net is not opened. Use Robolectric's host-JVM provider mode,
    // as on Apple Silicon, while retaining certificate trust and hostname verification.
    @ConscryptMode(ConscryptMode.Mode.OFF)
    @Test fun httpsDowngradeIsRejectedAfterTheTlsServerActuallyReturnsItsRedirect() {
        FixtureTls().use { tls ->
            val prior = HttpsURLConnection.getDefaultSSLSocketFactory()
            // Trust exactly the generated fixture certificate; hostname verification stays enabled.
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.context.socketFactory)
            try {
                WireServer { WireReply.audio() }.use { target ->
                    WireServer(tls.context) { WireReply.redirect("${target.url}/audio") }.use { source ->
                        val failure = assertFailsWith<AndroidPlaybackIOException> { consume(source.url) }
                        assertEquals(1, source.requests.size, "TLS failure before the redirect is not this proof")
                        assertSignedInventory(source.requests.single().query)
                        assertEquals(0, target.requests.size)
                        assertSanitized(failure)
                    }
                }
            } finally { HttpsURLConnection.setDefaultSSLSocketFactory(prior) }
        }
    }

    @Test fun realErrorAndTruncatedResponsesFailWithoutSurfacingTheirSignedUrl() {
        for (reply in listOf(
            WireReply(200, "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"$PASSWORD\"}}}".toByteArray()),
            WireReply(403, "https://invalid/?u=$USER&t=$TOKEN&s=$SALT".toByteArray()),
            WireReply(200, AUDIO, declaredLength = AUDIO.size + 50),
        )) {
            WireServer { reply }.use { server ->
                val failure = assertFailsWith<AndroidPlaybackIOException> { consume(server.url) }
                assertEquals(1, server.requests.size)
                assertSignedInventory(server.requests.single().query)
                assertSanitized(failure)
            }
        }
    }

    private fun consume(baseUrl: String) {
        val account = PlaybackEndpointAccount("provider:opaque", baseUrl, USER, PASSWORD, true)
        val authorizer = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(baseUrl, USER, PASSWORD, true),
            "playback-resource-test", SaltSource { SALT })
        val plan = playbackPlan()
        val resource = AndroidHttpPlaybackResource(account, plan, authorizer)
        val source = AndroidPlaybackDataSourceFactory(plan, resource).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse("dulcet://resource")).build())
            val actual = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                actual.write(buffer, 0, n)
            }
            assertContentEquals(AUDIO, actual.toByteArray())
        } finally { source.close(); authorizer.close() }
    }

    private fun assertSignedInventory(query: Map<String, List<String>>) {
        // Every emitted key must be explicitly classified. A new credential or unknown metadata
        // parameter fails here even if nobody updates the enum or the redirect stripping list.
        val metadata = setOf("v", "c", "f", "id")
        assertEquals(setOf("u", "t", "s"), query.keys - metadata)
        assertTrue((query.keys - metadata).all { it in ANDROID_PLAYBACK_CREDENTIAL_QUERY_NAMES })
        assertEquals(setOf("u", "t", "s", "p"), ANDROID_PLAYBACK_CREDENTIAL_QUERY_NAMES)
        assertEquals(listOf(USER), query["u"])
        assertEquals(listOf(TOKEN), query["t"])
        assertEquals(listOf(SALT), query["s"])
    }

    private fun assertSanitized(error: Throwable) {
        assertNull(error.cause)
        val text = error.stackTraceToString()
        for (canary in canaries) assertFalse(text.contains(canary))
        assertFalse(text.contains("http://")); assertFalse(text.contains("https://"))
        assertFalse(text.contains("/rest/"))
    }

    companion object {
        private const val USER = "USER_CANARY"
        private const val PASSWORD = "PASSWORD_CANARY"
        private const val SALT = "cafe0123456789abcafe0123456789ab"
        private val TOKEN = AccountConnectionContract.saltedToken(PASSWORD, SALT)
        private val canaries = listOf(USER, PASSWORD, SALT, TOKEN)
        private val AUDIO = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray() + ByteArray(9000)
    }

    private data class WireRequest(val target: String, val host: String?) {
        private val uri get() = URI(target)
        val path get() = uri.path
        val rawQuery get() = uri.rawQuery.orEmpty()
        val query: Map<String, List<String>> get() = rawQuery.split('&').filter { it.isNotEmpty() }.map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })
    }
    private data class WireReply(val code: Int, val bytes: ByteArray, val location: String? = null, val declaredLength: Int = bytes.size) {
        companion object {
            fun redirect(location: String) = WireReply(302, byteArrayOf(), location)
            fun audio() = WireReply(200, AUDIO)
        }
    }
    private class WireServer(tls: SSLContext? = null, private val respond: (WireRequest) -> WireReply) : AutoCloseable {
        private val socket = (tls?.serverSocketFactory?.createServerSocket() ?: ServerSocket()).apply {
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val port = socket.localPort
        val url = "${if (tls == null) "http" else "https"}://127.0.0.1:$port"
        val requests = CopyOnWriteArrayList<WireRequest>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val executor = Executors.newSingleThreadExecutor()
        init { executor.submit {
            while (!socket.isClosed) {
                try { socket.accept().use(::serve) }
                catch (e: Exception) { if (!socket.isClosed) failures += e }
            }
        } }
        private fun serve(client: Socket) {
            client.soTimeout = 10_000
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val line = checkNotNull(reader.readLine())
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val request = WireRequest(line.split(' ')[1], headers.firstOrNull { it.startsWith("Host:", true) }?.substringAfter(':')?.trim())
            requests += request
            val reply = respond(request)
            val header = "HTTP/1.1 ${reply.code} Fixture\r\nContent-Type: audio/wav\r\nContent-Length: ${reply.declaredLength}\r\nConnection: close\r\n" +
                (reply.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
            client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(reply.bytes); flush() }
        }
        override fun close() {
            socket.close(); executor.shutdown()
            check(executor.awaitTermination(15, TimeUnit.SECONDS)) { "Fixture server did not stop" }
            assertTrue(failures.isEmpty(), "Fixture server failed")
        }
    }
    private class FixtureTls : AutoCloseable {
        private val directory = Files.createTempDirectory("dulcet-playback-tls")
        val context: SSLContext
        init {
            val file = directory.resolve("fixture.p12")
            val process = ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair",
                "-alias", "fixture", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "fixture-password", "-keypass", "fixture-password", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "Fixture certificate generation failed" }
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, "fixture-password".toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-password".toCharArray()) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        }
        override fun close() { directory.toFile().deleteRecursively() }
    }
}
