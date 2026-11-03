package com.legitimateapps.dulcet.emulator

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URI

/** Wire controls for public failure diagnostics: query canaries must never appear in the report. */
@RunWith(AndroidJUnit4::class)
class ServerRelayDiagnosticsTest {
    @Test fun forwardedFirstRequestIsReassembledAndOnlyItsPathIsReported() {
        ServerRelay(DisposableServerProbe.fromInstrumentation().baseUrl).use { relay ->
            val marker = System.nanoTime()
            Socket("127.0.0.1", URI(relay.url).port).use { socket ->
                socket.getOutputStream().apply {
                    write("GET http://host-canary.invalid/rest/ping.view?u=user-canary&t=token-canary".toByteArray())
                    flush()
                    write("&s=salt-canary&p=password-canary HTTP/1.1\r\nHost: header-canary\r\n\r\n".toByteArray())
                    flush()
                }
                val report = awaitReport(relay, marker, "GET /rest/ping.view HTTP/1.1")
                assertEquals(1, relay.forwardedConnections.get())
                assertEquals(0, relay.refusedConnections.get())
                assertTrue(report.contains("forwarded"))
                assertTrue(report.contains("accept="))
                assertTrue(report.contains("first-request="))
                assertFalse(report.contains("pending"))
                assertSafe(report)
            }
        }
    }

    @Test fun refusedConnectionStillCountsAndReportsItsRedactedRequest() {
        ServerRelay(DisposableServerProbe.fromInstrumentation().baseUrl).use { relay ->
            relay.cut()
            val marker = System.nanoTime()
            Socket("127.0.0.1", URI(relay.url).port).use { socket ->
                socket.getOutputStream().apply {
                    write("GET /rest/getCoverArt.view?u=user-canary&t=token-canary&s=salt-canary&p=password-canary HTTP/1.1\r\n\r\n".toByteArray())
                    flush()
                }
                val report = awaitReport(relay, marker, "GET /rest/getCoverArt.view HTTP/1.1")
                assertEquals(1, relay.refusedConnections.get())
                assertEquals(0, relay.forwardedConnections.get())
                assertTrue(report.contains("refused"))
                assertSafe(report)
            }
            Socket("127.0.0.1", URI(relay.url).port).use {
                val report = awaitReport(relay, marker, "#2", ready = { relay.refusedConnections.get() == 2 })
                // No HTTP request is needed to fail the saved-launch contact assertion.
                assertEquals(2, relay.refusedConnections.get())
                assertTrue(report.contains("<no request line observed>"))
            }
        }
    }

    @Test fun connectionAcceptedBeforeTheSnapshotButCountedAfterItIsIdentifiable() {
        ServerRelay(DisposableServerProbe.fromInstrumentation().baseUrl).use { relay ->
            relay.hold()
            Socket("127.0.0.1", URI(relay.url).port).use { socket ->
                socket.getOutputStream().apply {
                    write("GET /rest/ping.view?u=user-canary&t=token-canary HTTP/1.1\r\n\r\n".toByteArray())
                    flush()
                }
                awaitReport(relay, System.nanoTime(), "#1", ready = { relay.heldConnections.get() == 1 })
                assertEquals(1, relay.heldConnections.get())
                val baseline = relay.forwardedConnections.get() + relay.refusedConnections.get()
                val acceptedBefore = relay.acceptedConnections.get()
                val marker = System.nanoTime()
                relay.release()
                val report = awaitReport(relay, marker, "GET /rest/ping.view HTTP/1.1")
                // This deliberately reproduces a counted contact across the old snapshot boundary,
                // without claiming this ordering caused the production proof's CI failure.
                assertEquals(1, relay.forwardedConnections.get() + relay.refusedConnections.get() - baseline)
                assertEquals(0, relay.acceptedConnections.get() - acceptedBefore)
                // A new connection after that same snapshot still fails zero-contact immediately,
                // even held, with no request bytes and no upstream connection yet.
                relay.hold()
                Socket("127.0.0.1", URI(relay.url).port).use {
                    awaitReport(relay, marker, "#2", ready = { relay.acceptedConnections.get() == acceptedBefore + 1 })
                    assertEquals(1, relay.acceptedConnections.get() - acceptedBefore)
                    assertEquals(1, relay.forwardedConnections.get() + relay.refusedConnections.get() - baseline)
                }
                assertTrue(report.contains("accept=-"))
                assertFalse(report.contains("count=-"))
                assertSafe(report)
            }
        }
    }

    @Test fun heldRequestPathIdentifiesTheProbeAndReplaysItsExactBytesOnce() {
        ServerRelay(DisposableServerProbe.fromInstrumentation().baseUrl).use { relay ->
            relay.hold()
            val marker = System.nanoTime()
            Socket("127.0.0.1", URI(relay.url).port).use { socket ->
                val first = "GET /rest/ping.view?u=user-canary&t=token-canary".toByteArray()
                val rest = "&s=salt-canary HTTP/1.1\r\nHost: header-canary\r\n\r\n".toByteArray()
                socket.getOutputStream().apply { write(first); flush() }
                awaitReport(relay, marker, "#1", ready = { relay.heldConnections.get() == 1 })
                assertTrue(relay.heldRequestPaths().isEmpty())
                socket.getOutputStream().apply { write(rest); flush() }
                val report = awaitReport(relay, marker, "GET /rest/ping.view HTTP/1.1",
                    ready = { relay.heldRequestPaths() == listOf("/rest/ping.view") })
                assertEquals(0, relay.forwardedConnections.get())
                assertEquals(0L, relay.forwardedBytes.get())
                assertSafe(report)
                relay.release()
                awaitReport(relay, marker, "forwarded", ready = {
                    relay.connections().singleOrNull()?.sent()?.contentEquals(first + rest) == true
                })
                assertEquals(1, relay.forwardedConnections.get())
                assertTrue(relay.connections().single().sent().contentEquals(first + rest))
            }
        }
    }

    @Test fun cuttingAnIdentifiedHeldProbeRefusesItWithoutForwardingThePrefix() {
        ServerRelay(DisposableServerProbe.fromInstrumentation().baseUrl).use { relay ->
            relay.hold()
            val marker = System.nanoTime()
            Socket("127.0.0.1", URI(relay.url).port).use { socket ->
                socket.getOutputStream().apply {
                    write("GET /rest/ping.view?u=user-canary&t=token-canary HTTP/1.1\r\n\r\n".toByteArray())
                    flush()
                }
                awaitReport(relay, marker, "GET /rest/ping.view HTTP/1.1",
                    ready = { relay.heldRequestPaths() == listOf("/rest/ping.view") })
                relay.cut()
                relay.release()
                val report = awaitReport(relay, marker, "refused GET /rest/ping.view HTTP/1.1")
                assertEquals(1, relay.refusedConnections.get())
                assertEquals(0, relay.forwardedConnections.get())
                assertEquals(0L, relay.forwardedBytes.get())
                assertTrue(relay.connections().isEmpty())
                assertSafe(report)
            }
        }
    }

    @Test fun nonPersistentModeReplacesConnectionHeadersAndPreservesTheEntireRequestBody() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val body = ByteArray(49_157) { (it % 256).toByte() }
            val observed = java.util.concurrent.CompletableFuture<Pair<String, ByteArray>>()
            val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body
            kotlin.concurrent.thread(isDaemon = true, name = "relay-close-wire-control") {
                try {
                    server.accept().use { accepted ->
                        accepted.soTimeout = 5_000
                        val input = accepted.getInputStream().buffered()
                        val headers = readHeaders(input)
                        val receivedBody = ByteArray(body.size)
                        java.io.DataInputStream(input).readFully(receivedBody)
                        observed.complete(headers to receivedBody)
                        accepted.getOutputStream().apply { write(response); flush() }
                    }
                } catch (failure: Throwable) { observed.completeExceptionally(failure) }
            }
            ServerRelay("http://127.0.0.1:${server.localPort}", persistentConnections = false).use { relay ->
                relay.hold()
                val marker = System.nanoTime()
                Socket("127.0.0.1", URI(relay.url).port).use { socket ->
                    socket.soTimeout = 5_000
                    val request = ("POST /fixture-echo?u=user-canary&t=token-canary HTTP/1.1\r\n" +
                        "Host: header-canary\r\ncOnNeCtIoN: keep-alive\r\nConnection: upgrade\r\n" +
                        "X-Fixture: kept\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body
                    socket.getOutputStream().apply { write(request); flush() }
                    awaitReport(relay, marker, "POST /fixture-echo HTTP/1.1",
                        ready = { relay.heldRequestPaths() == listOf("/fixture-echo") })
                    assertEquals(0, relay.forwardedConnections.get())
                    relay.release()
                    // readBytes reaches EOF: the peer actually closed, beyond an asserted header.
                    assertTrue(socket.getInputStream().readBytes().contentEquals(response))
                    val (headers, receivedBody) = observed.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    val connectionHeaders = headers.split("\r\n").filter { it.substringBefore(':').equals("Connection", true) }
                    assertEquals(listOf("Connection: close"), connectionHeaders)
                    assertTrue(headers.startsWith("POST /fixture-echo?u=user-canary&t=token-canary HTTP/1.1\r\n"))
                    assertTrue(headers.contains("X-Fixture: kept\r\n"))
                    assertTrue(receivedBody.contentEquals(body))
                    assertTrue(relay.connections().single().sent().contentEquals(request))
                    assertSafe(relay.connectionDiagnostics(marker))
                }
            }
        }
    }

    @Test fun disposableServerActuallyClosesANonPersistentResponseWithItsBodyIntact() {
        ServerRelay(DisposableServerProbe.fromInstrumentation().baseUrl, persistentConnections = false).use { relay ->
            Socket("127.0.0.1", URI(relay.url).port).use { socket ->
                socket.soTimeout = 5_000
                val request = "GET /rest/ping.view?f=json HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: keep-alive\r\n\r\n".toByteArray()
                socket.getOutputStream().apply { write(request); flush() }
                // The disposable Navidrome/Dulcet peer must honor the fixture's close request.
                val response = socket.getInputStream().readBytes()
                val text = response.toString(Charsets.ISO_8859_1)
                val headers = text.substringBefore("\r\n\r\n")
                assertTrue(headers.startsWith("HTTP/1.0 ") ||
                    headers.split("\r\n").any { it.equals("Connection: close", true) })
                assertTrue(text.substringAfter("\r\n\r\n").contains("subsonic-response"))
                assertTrue(relay.connections().single().sent().contentEquals(request))
                assertTrue(relay.connections().single().answered().contentEquals(response))
            }
        }
    }

    private fun readHeaders(input: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        var last = 0
        while (bytes.size() < 65_536) {
            val byte = input.read()
            check(byte >= 0) { "Control request ended before its headers" }
            bytes.write(byte)
            last = (last shl 8) or byte
            if (last == 0x0d0a0d0a) return bytes.toString("ISO-8859-1")
        }
        error("Control request headers exceeded the fixture limit")
    }

    private fun awaitReport(relay: ServerRelay, marker: Long, containing: String, ready: () -> Boolean = { true }): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            val isReady = ready()
            val report = relay.connectionDiagnostics(marker)
            if (report.contains(containing) && isReady) return report
            check(System.nanoTime() < deadline) { "Relay diagnostic never contained $containing:\n$report" }
            Thread.sleep(10)
        }
    }

    private fun assertSafe(report: String) {
        listOf("user-canary", "token-canary", "salt-canary", "password-canary", "host-canary", "header-canary", "?")
            .forEach { assertFalse("Diagnostic exposed $it", report.contains(it)) }
    }
}
