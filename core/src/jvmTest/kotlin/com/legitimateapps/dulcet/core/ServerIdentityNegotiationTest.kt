package com.legitimateapps.dulcet.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real connect at one hostname: replacements change lineage; version bumps and restarts do not. */
class ServerIdentityNegotiationTest {
    @Test fun navidromeToDulcetAndBackChangesIdentityAtTheSameAddress() = fixture { f ->
        val navidrome = f.connect()
        f.type = "dulcet"
        val dulcet = f.connect()
        assertNotEquals(navidrome, dulcet)
        f.type = "Navidrome"
        assertEquals(navidrome, f.connect())
    }

    @Test fun softwareUpgradeAndRestartRetainIdentityForBothImplementations() = fixture { f ->
        for (type in listOf("Navidrome", "dulcet")) {
            f.type = type
            val before = f.connect()
            f.version = "99.0.0"
            assertEquals(before, f.connect(), "a version upgrade must not count as a swap")
            assertEquals(before, f.connect(), "a fresh connect/restart must retain lineage")
        }
    }

    @Test fun advertisedInstanceRotationChangesIdentityWithoutAProductOrUrlChange() = fixture { f ->
        f.type = "dulcet"
        val before = f.connect()
        f.instance = "replacement-history"
        assertNotEquals(before, f.connect())
    }

    @Test fun discoveryRedirectCannotSupplyAnotherOriginsIdentity() = fixture { f ->
        f.type = "dulcet"
        f.redirect = true
        assertIs<AccountConnectionResult.Failed>(f.result())
        assertEquals(0, f.foreignRequests)
    }

    @Test fun reconnectProbeAuthenticatesPingWithoutRenegotiatingUserPermissions() = fixture { f ->
        val result = runBlocking { AccountConnector().identify(AccountConnectionRequest(
            "http://127.0.0.1:${f.server.address.port}", "alice", "fixture", true)) }
        assertIs<AccountConnectionResult.Connected>(result)
        assertEquals(listOf("/rest/ping.view"), f.paths)
    }

    private fun fixture(body: (Fixture) -> Unit) {
        val f = Fixture()
        try { body(f) } finally { f.server.stop(0) }
    }

    private class Fixture {
        var type = "Navidrome"
        var version = "0.1.0"
        var instance = "original-history"
        var redirect = false
        var foreignRequests = 0
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                paths += path
                val body = if (path == "/.well-known/dulcet") {
                    // Discovery must be unauthenticated even though the preceding ping was not.
                    check(exchange.requestURI.rawQuery == null)
                    check(exchange.requestHeaders.getFirst("Authorization") == null)
                    if (redirect) exchange.responseHeaders.add("Location", "/foreign")
                    """{"standard":"dulcet","server":{"instanceId":"$instance"}}"""
                } else if (path == "/foreign") {
                    foreignRequests++
                    "{}"
                } else {
                    val payload = when {
                        "getOpenSubsonicExtensions" in path -> "\"openSubsonicExtensions\":[]"
                        "getUser" in path -> "\"user\":{\"username\":\"alice\"}"
                        else -> "\"type\":\"$type\",\"serverVersion\":\"$version\""
                    }
                    """{"subsonic-response":{"status":"ok","version":"1.16.1",$payload}}"""
                }
                val bytes = body.toByteArray()
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (redirect && path == "/.well-known/dulcet") 302 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        fun result(): AccountConnectionResult = runBlocking {
            AccountConnector().connect(AccountConnectionRequest("http://127.0.0.1:${server.address.port}", "alice", "fixture", true))
        }
        // Reflection also runs these acceptance tests against the parent revision for fail-before.
        fun connect(): String {
            val account = assertIs<AccountConnectionResult.Connected>(result()).account
            val identity = account.javaClass.methods.firstOrNull { it.name == "getServerIdentity" }?.invoke(account) as? String
            return assertNotNull(identity, "successful negotiation must publish a stable server identity")
        }
    }
}
