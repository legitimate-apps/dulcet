package com.legitimateapps.dulcet.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Enrollment may commit while an old plaintext request is suspended in DNS admission. */
class ConnectionDispatchFenceTest {
    @Test fun aQueuedLegacyConnectorCannotDialHttpAfterEnrollmentCommits() = fixture { base, hits ->
        val anchor = OwnershipAnchor("connector-dispatch-fence", base, "listener", "fixture-lineage")
        val resolver = HostResolver {
            ConnectionRouteRegistry.publish(anchor, RouteLease("https://localhost:443/music", "secure", "fixture", 1, 1))
            listOf("127.0.0.1")
        }
        try {
            val result = runBlocking { AccountConnector(hostResolver = resolver).connect(
                AccountConnectionRequest(base, "listener", "synthetic-canary", true)) }
            assertEquals(DomainError.Transport.Cancelled, assertIs<AccountConnectionResult.Failed>(result).error)
            assertEquals(0, hits.get(), "No HTTP bytes may leave after the DNS suspension resumes")
        } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    @Test fun aQueuedLegacyEndpointCannotDialHttpAfterEnrollmentCommits() = fixture { base, hits ->
        val anchor = OwnershipAnchor("endpoint-dispatch-fence", base, "listener", "fixture-lineage")
        val resolver = HostResolver {
            ConnectionRouteRegistry.publish(anchor, null) // Enrolled and offline also forbids fallback.
            listOf("127.0.0.1")
        }
        val client = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(base, "listener", "synthetic-canary", true),
            "fixture.dispatch", hostResolver = resolver)
        try {
            val failure = assertFailsWith<AuthenticatedEndpointFailure> { runBlocking { client.request("ping", emptyMap()) } }
            assertEquals(DomainError.Transport.Cancelled, failure.error)
            assertEquals(0, hits.get())
        } finally { client.close(); ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    @Test fun preparedNativeRequestsDoNotRegainAuthorityAfterRouteRemovalAndReaddition() {
        val anchor = OwnershipAnchor("native-dispatch-fence", "http://fixture.local:4533", "listener", "fixture")
        val first = RouteLease("https://localhost:443/music", "secure", "fixture", 1, 7)
        try {
            ConnectionRouteRegistry.publish(anchor, first)
            assertTrue(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, first.baseUrl, 7))
            ConnectionRouteRegistry.publish(anchor, null)
            assertFalse(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, first.baseUrl, 7))
            ConnectionRouteRegistry.publish(anchor, first.copy(generation = 9))
            assertFalse(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, first.baseUrl, 7))
            assertTrue(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, first.baseUrl, 9))
            assertFalse(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, anchor.normalizedBaseUrl, null))
        } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    private fun fixture(check: (String, AtomicInteger) -> Unit) {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call ->
            hits.incrementAndGet()
            val body = "{}".toByteArray()
            call.sendResponseHeaders(200, body.size.toLong()); call.responseBody.use { it.write(body) }
        }
        server.start()
        try { check("http://fixture.local:${server.address.port}", hits) }
        finally { server.stop(0) }
    }
}
