package com.legitimateapps.dulcet.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.*

/** Authenticated getUser is the authority, never a client-side username case fold. */
class AccountCanonicalUsernameTest {
    @Test fun authenticatedGetUserSuppliesTheCanonicalUsernameExactly() = answer("\"username\":\"Canonical-Alice\",") { account ->
        assertEquals("Canonical-Alice", canonicalUsername(account))
    }
    @Test fun serversWithoutCanonicalUsernamesDoNotInventOne() = answer("") { account ->
        assertNull(canonicalUsername(account))
    }
    @Test fun blankCanonicalUsernameDoesNotReplaceTheSubmittedIdentity() = answer("\"username\":\"\",") { account ->
        assertNull(canonicalUsername(account))
    }
    // Reflection deliberately keeps this acceptance runnable against the historical account DTO.
    private fun canonicalUsername(account: ConnectedAccount): String? = account.javaClass.methods
        .firstOrNull { it.name == "getCanonicalUsername" }?.invoke(account) as? String
    private fun answer(usernameField: String, check: (ConnectedAccount) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest/") { exchange ->
            val payload = when {
                exchange.requestURI.path.contains("getOpenSubsonicExtensions") -> "\"openSubsonicExtensions\":[]"
                exchange.requestURI.path.contains("getUser") -> "\"user\":{$usernameField\"downloadRole\":true}"
                else -> "\"type\":\"fixture\",\"openSubsonic\":false"
            }
            val bytes = "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",$payload}}".toByteArray()
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = runBlocking { AccountConnector().connect(AccountConnectionRequest(
                "http://127.0.0.1:${server.address.port}", "ALIce", "fixture", true)) }
            check(assertIs<AccountConnectionResult.Connected>(result).account)
        } finally { server.stop(0) }
    }
}
