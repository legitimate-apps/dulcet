package com.legitimateapps.dulcet.core

import android.security.NetworkSecurityPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownServiceException
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread
import kotlin.test.*

/** Android's real cleartext check is in OkHttp; Robolectric supplies its platform policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ManifestNetworkSecurityPolicy::class])
class AndroidCleartextHttpTest {
    @Test fun phoneAllowsPlainHttpThroughProductionClients() = permitsHttp("android/app/src/main/AndroidManifest.xml")
    @Test fun tvAllowsPlainHttpThroughProductionClients() = permitsHttp("android/tv/src/main/AndroidManifest.xml")
    @Test fun conformanceAllowsPlainHttpThroughProductionClientsAndOkHttpProbe() =
        permitsHttp("core-conformance/src/androidDeviceTest/AndroidManifest.xml")

    @Test fun platformRefusalStopsTheStrictClientAndOkHttpProbeBeforeAnyRequest() = runBlocking {
        ManifestNetworkSecurityPolicy.permitted = false
        Fixture().use { server ->
            for (client in listOf(createAccountHttpClient(AccountClientTransport.Default(), true) {}, HttpClient(OkHttp))) {
                try {
                    val failure = assertFailsWith<UnknownServiceException> { client.get(server.url) }
                    assertTrue(failure.message.orEmpty().contains("CLEARTEXT"))
                    assertTrue(server.paths.isEmpty(), "the platform refused before sending HTTP")
                } finally { client.close() }
            }
        }
    }

    private fun permitsHttp(manifest: String) = runBlocking {
        // Robolectric does not derive NetworkSecurityPolicy from usesCleartextTraffic. Model
        // Android's target-28+ default (false) from the actual APK input, rather than granting
        // cleartext unconditionally in the test. The refusal control proves the bridge matters.
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(System.getProperty("dulcet.repositoryRoot"), manifest))
        val application = document.getElementsByTagName("application").item(0)
        val permitted = application?.attributes?.getNamedItemNS(
            "http://schemas.android.com/apk/res/android", "usesCleartextTraffic",
        )?.nodeValue == "true"
        ManifestNetworkSecurityPolicy.permitted = permitted
        Fixture().use { server ->
            val endpoint = AuthenticatedEndpointClient(
                AuthenticatedEndpointCredentials(server.url, "fixture-user", "fixture-password", true),
                "cleartext-control",
            )
            // Engine discovery differs between host and device classpaths. Pin the probe's
            // OkHttp path here so the host cannot silently exercise CIO's cleartext behavior.
            val probe = HttpClient(OkHttp) { followRedirects = false }
            try {
                for (name in listOf("search3", "stream")) {
                    val response = endpoint.request(name, emptyMap())
                    assertEquals(200, response.statusCode, "$manifest: $name")
                    assertEquals("fixture", response.body.decodeToString())
                }
                assertEquals("fixture", probe.get("${server.url}/probe").bodyAsText())
                assertEquals(listOf("/rest/search3.view", "/rest/stream.view", "/probe"), server.paths)
            } finally { endpoint.close(); probe.close() }
        }
    }

    private class Fixture : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val paths = CopyOnWriteArrayList<String>()
        private val worker = thread(isDaemon = true, name = "cleartext-http-fixture") {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 5000
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    paths += request.split(' ')[1].substringBefore('?')
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nfixture".toByteArray())
                        flush()
                    }
                }
            }
        }
        override fun close() {
            listener.close()
            worker.join(10_000)
            check(!worker.isAlive) { "cleartext fixture did not stop" }
        }
    }
}

// Supply just the platform decision; OkHttp still performs its real refusal and real socket I/O.
@Implements(NetworkSecurityPolicy::class)
class ManifestNetworkSecurityPolicy {
    @Implementation fun isCleartextTrafficPermitted(): Boolean = permitted
    @Implementation fun isCleartextTrafficPermitted(host: String): Boolean = permitted
    companion object { var permitted = false }
}
