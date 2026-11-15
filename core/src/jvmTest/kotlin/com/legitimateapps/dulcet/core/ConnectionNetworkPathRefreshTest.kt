package com.legitimateapps.dulcet.core

import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Wire-level idle-pool invalidation, with a healthy active body on the same production client. */
class ConnectionNetworkPathRefreshTest {
    @Test fun topologyHintRetiresIdleTlsSocketWithoutCancellingAnActiveBodyOrProbing() {
        if (!java.lang.Boolean.getBoolean(ISOLATED)) {
            // Default JSSE trust may already be cached by unrelated tests. Trust only this
            // disposable root in a fresh process before constructing the actual factory.
            val locations = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
                .flatMap { it.getURLs().asSequence() }.map { File(it.toURI()).path }.toList()
            val classpath = (locations + System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                "-D$ISOLATED=true", "-cp", classpath,
                "com.legitimateapps.dulcet.core.ConnectionNetworkPathRefreshProcess").redirectErrorStream(true).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly(); fail("isolated topology TLS fixture deadline")
            }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), "isolated production-client topology proof failed:\n$output")
            return
        }
        PersistentTlsFixture().use { fixture ->
            fixture.assertHostnameVerification()
            System.setProperty("javax.net.ssl.trustStore", fixture.keyStore.toString())
            System.setProperty("javax.net.ssl.trustStorePassword", PASSWORD)
            runBlocking {
                val client = createAccountHttpClient(AccountClientTransport.Default()) {
                    expectSuccess = false; followRedirects = false
                }
                try {
                    suspend fun fresh(path: String): Int {
                        assertEquals("fixture-ok", client.get(fixture.base+path).bodyAsText())
                        return fixture.requests.last { it.path == path }.socket
                    }
                    val first = fresh("/warm-one")
                    assertEquals(first, fresh("/warm-two"), "positive control: the actual client must reuse HTTP/1.1 TLS keep-alive")
                    val prefixRead = CompletableDeferred<Unit>()
                    val active = async(Dispatchers.IO) {
                        client.prepareGet(fixture.base+"/held").execute { response ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(64)
                            val channel = response.bodyAsChannel()
                            while (true) {
                                val count = channel.readAvailable(buffer, 0, buffer.size)
                                if (count == -1) break
                                if (count > 0) {
                                    bytes.write(buffer, 0, count)
                                    if (bytes.toString(Charsets.UTF_8).startsWith(PREFIX)) prefixRead.complete(Unit)
                                }
                            }
                            bytes.toString(Charsets.UTF_8)
                        }
                    }
                    try {
                        withTimeout(10_000) { prefixRead.await() }
                        val heldSocket = fixture.requests.single { it.path == "/held" }.socket
                        assertEquals(first, heldSocket, "the already-active body starts on the positively reused socket")
                        val idle = fresh("/idle-one")
                        assertNotEquals(heldSocket, idle, "a held body requires a second actual socket")
                        assertEquals(idle, fresh("/idle-two"), "positive control: second socket is reusable and idle before the hint")
                        val requestCount = fixture.requests.size
                        val socketCount = fixture.accepted.get()
                        ConnectionNetworkEvents.topologyChanged()
                        delay(300)
                        assertEquals(requestCount, fixture.requests.size, "topology hints must not send idle probes")
                        assertEquals(socketCount, fixture.accepted.get(), "topology hints must not open speculative TLS sockets")
                        assertFalse(active.isCompleted, "topology hints must preserve an already-active body")
                        val after = fresh("/after-hint")
                        fixture.releaseHeld.countDown()
                        assertEquals(PREFIX+SUFFIX, withTimeout(10_000) { active.await() }, "healthy active body must finish intact")
                        assertEquals(requestCount+1, fixture.requests.size, "only the explicit fresh request may reach the listener")
                        println("TOPOLOGY TLS keepalive-reused=true held-socket=$heldSocket idle-before=$idle fresh-after=$after active-body-complete=true idle-probes=0 speculative-sockets=0")
                        assertNotEquals(idle, after, "meaningful topology change must retire the app-owned idle TLS socket before the next fresh request")
                    } finally {
                        fixture.releaseHeld.countDown()
                        active.cancel()
                    }
                } finally { client.close() }
            }
        }
    }

    private data class Request(val socket: Int, val path: String)
    private class PersistentTlsFixture : Closeable {
        private val directory = Files.createTempDirectory("dulcet-topology-tls-")
        val keyStore = directory.resolve("fixture.p12")
        val accepted = AtomicInteger()
        val requests = CopyOnWriteArrayList<Request>()
        val releaseHeld = CountDownLatch(1)
        private val sockets = CopyOnWriteArrayList<SSLSocket>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "owned-topology-tls").apply { isDaemon = true } }
        private val server: SSLServerSocket
        private val trust: SSLContext
        val base: String get() = "https://localhost:${server.localPort}"
        init {
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "owned fixture certificate generation failed: $output" }
            val keys = KeyStore.getInstance("PKCS12").also { Files.newInputStream(keyStore).use { input -> it.load(input, PASSWORD.toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also { it.init(keys, PASSWORD.toCharArray()) }
            val ssl = SSLContext.getInstance("TLS").also { it.init(managers.keyManagers, null, null) }
            val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(keys) }
            trust = SSLContext.getInstance("TLS").also { it.init(null, trusts.trustManagers, null) }
            server = ssl.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            executor.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() as SSLSocket } catch (_: java.io.IOException) { break }
                    val id = accepted.incrementAndGet()
                    sockets += socket
                    executor.execute { serve(socket, id) }
                }
            }
        }
        private fun serve(socket: SSLSocket, id: Int) {
            try {
                socket.soTimeout = 20_000
                val input = socket.inputStream.bufferedReader(Charsets.US_ASCII)
                val output = socket.outputStream
                while (!socket.isClosed) {
                    val request = input.readLine() ?: break
                    val path = request.split(' ').also { check(it.size == 3 && it[0] == "GET") }[1]
                    while (true) { if ((input.readLine() ?: return).isEmpty()) break }
                    requests += Request(id, path)
                    val body = if (path == "/held") PREFIX+SUFFIX else "fixture-ok"
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nContent-Type: text/plain\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    if (path == "/held") {
                        output.write(PREFIX.toByteArray()); output.flush()
                        check(releaseHeld.await(20, TimeUnit.SECONDS)) { "held body release deadline" }
                        output.write(SUFFIX.toByteArray())
                    } else output.write(body.toByteArray())
                    output.flush()
                }
            } catch (_: java.io.IOException) {
                // Includes the required wrong-hostname handshake rejection and fixture shutdown.
            } finally { socket.close() }
        }
        fun assertHostnameVerification() {
            fun open(host: String): Int {
                val connection = URI("https://$host:${server.localPort}/tls-name-control").toURL().openConnection() as HttpsURLConnection
                connection.sslSocketFactory = trust.socketFactory
                connection.connectTimeout = 3000; connection.readTimeout = 3000
                return try { connection.responseCode } finally { connection.disconnect() }
            }
            assertEquals(200, open("localhost"), "fresh-root matching SAN positive control")
            assertFailsWith<SSLHandshakeException> { open("127.0.0.1") }
            requests.clear()
        }
        override fun close() {
            releaseHeld.countDown(); server.close(); sockets.forEach { it.close() }
            executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS)
            directory.toFile().deleteRecursively()
        }
    }
    companion object {
        private const val ISOLATED = "dulcet.fixture.topology.isolated"
        private const val PASSWORD = "disposable-topology-keystore"
        private const val PREFIX = "active-prefix:"
        private const val SUFFIX = "healthy-body-completed"
    }
}

internal object ConnectionNetworkPathRefreshProcess {
    @JvmStatic fun main(args: Array<String>) {
        ConnectionNetworkPathRefreshTest().topologyHintRetiresIdleTlsSocketWithoutCancellingAnActiveBodyOrProbing()
    }
}
