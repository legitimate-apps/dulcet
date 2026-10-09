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

    private fun awaitReport(relay: ServerRelay, marker: Long, containing: String, ready: () -> Boolean = { true }): String {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            val report = relay.connectionDiagnostics(marker)
            if (report.contains(containing) && ready()) return report
            check(System.nanoTime() < deadline) { "Relay diagnostic never contained $containing:\n$report" }
            Thread.sleep(10)
        }
    }

    private fun assertSafe(report: String) {
        listOf("user-canary", "token-canary", "salt-canary", "password-canary", "host-canary", "header-canary", "?")
            .forEach { assertFalse("Diagnostic exposed $it", report.contains(it)) }
    }
}
