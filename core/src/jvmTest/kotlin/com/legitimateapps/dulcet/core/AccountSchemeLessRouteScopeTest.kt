package com.legitimateapps.dulcet.core

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/** Scheme-less input is normalized by the connector, never treated as a prepared route URL. */
class AccountSchemeLessRouteScopeTest {
    @Test fun explicitLocalConsentStillNormalizesSchemeLessInputButCannotRestoreAnEnrolledHttpAnchor() {
        val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val authenticated = AtomicInteger()
        val worker = thread(isDaemon = true, name = "disposable-connect-normalization") {
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val first = input.read()
                    // This fixture offers HTTP only. Close a TLS ClientHello immediately so
                    // explicit local-HTTP consent exercises the connector's actual fallback.
                    if (first == 22 || first < 0) return@use
                    val reader = input.bufferedReader()
                    val line = first.toChar() + reader.readLine()
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    if (line.contains("/rest/")) authenticated.incrementAndGet()
                    val payload = when {
                        line.contains("getOpenSubsonicExtensions") -> "\"openSubsonicExtensions\":[]"
                        line.contains("getUser") -> "\"user\":{\"downloadRole\":true}"
                        else -> "\"type\":\"fixture\",\"openSubsonic\":false"
                    }
                    val body = "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",$payload}}".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: SocketException) { check(listener.isClosed) }
        }
        val base = "http://127.0.0.1:${listener.localPort}"
        val anchor = OwnershipAnchor("scheme-less-fixture", base, "fixture", "[\"dulcet\",\"fixture\"]")
        try {
            val request = AccountConnectionRequest(base.removePrefix("http://"), "fixture", "fixture", true)
            val result = runBlocking { AccountConnector().connect(request) }
            val account = assertIs<AccountConnectionResult.Connected>(result, "Normalized explicit HTTP fixture result: $result").account
            assertEquals(base, account.normalizedBaseUrl)
            assertTrue(account.allowsLocalHttp)
            assertTrue(authenticated.get() > 0)
            val count = authenticated.get()
            ConnectionRouteRegistry.publish(anchor, RouteLease("https://admitted.example.test/music", "home", "fixture", 1, 1), 1)
            val rejected = runBlocking { AccountConnector().connect(request) }
            assertIs<AccountConnectionResult.Failed>(rejected)
            assertEquals(count, authenticated.get(), "Scheme-less spelling cannot restore an enrolled account's retired HTTP anchor")
        } finally {
            ConnectionRouteRegistry.forget(anchor.accountId)
            listener.close(); worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }
}
