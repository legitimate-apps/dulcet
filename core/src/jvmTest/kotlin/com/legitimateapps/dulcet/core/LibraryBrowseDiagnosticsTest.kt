package com.legitimateapps.dulcet.core

import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryBrowseDiagnosticsTest {
    @Test
    fun handlerWithheldHeadersAreDistinctFromWithheldBody() = runBlocking {
        for (holdBody in listOf(false, true)) {
            withTimeout(5_000) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    val handlerEntered = CompletableDeferred<Unit>()
                    val headersObserved = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val events = Collections.synchronizedList(mutableListOf<String>())
                    val diagnostics = LibraryBrowseDiagnostics { event ->
                        events += event
                        if (event.endsWith("headers-received")) headersObserved.complete(Unit)
                    }
                    val handler = async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream().bufferedReader()
                            val requestLine = input.readLine()
                            while (!input.readLine().isNullOrEmpty()) { /* consume headers */ }
                            assertTrue(requestLine.startsWith("GET /rest/getMusicFolders.view?"))
                            val output = socket.getOutputStream()
                            fun headers() {
                                output.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n" +
                                    "X-Canary: response-secret-canary\r\nConnection: close\r\n\r\n").toByteArray())
                                output.flush()
                            }
                            if (holdBody) headers()
                            handlerEntered.complete(Unit) // the handler owns this precondition
                            release.await()
                            if (!holdBody) headers()
                            output.write("{}".toByteArray())
                            output.flush()
                        }
                    }
                    val client = AuthenticatedEndpointClient(
                        AuthenticatedEndpointCredentials(
                            "http://127.0.0.1:${server.localPort}",
                            "username-secret-canary", "password-secret-canary", true,
                        ),
                        "library.browse",
                        saltSource = SaltSource { "0123456789abcdef0123456789abcdef" },
                        diagnostics = diagnostics,
                    )
                    try {
                        val response = async { client.request("getMusicFolders", mapOf("canary" to "query-secret-canary")) }
                        handlerEntered.await()
                        if (holdBody) headersObserved.await()
                        assertFalse(response.isCompleted, "the handler's withheld phase must still be pending")
                        assertEquals(holdBody, headersObserved.isCompleted)
                        assertFalse(events.any { "body-completed" in it })
                        assertTrue(events.any { "http-started" in it })
                        release.complete(Unit)
                        assertEquals("{}", response.await().body.decodeToString())
                        handler.await()
                        assertTrue(events.any { "headers-received" in it })
                        assertTrue(events.any { "body-completed" in it })
                        val text = events.joinToString("\n")
                        val token = AccountConnectionContract.saltedToken(
                            "password-secret-canary", "0123456789abcdef0123456789abcdef",
                        )
                        for (secret in listOf("secret-canary", "0123456789abcdef", token, "?", "127.0.0.1")) {
                            assertFalse(secret in text, "diagnostics exposed credential-bearing material")
                        }
                        println("LIBRARY DIAGNOSTIC CONTROL handler-held=${if (holdBody) "body" else "headers"} observed=true")
                    } finally {
                        release.complete(Unit)
                        client.close()
                        withContext(Dispatchers.IO) { server.close() }
                    }
                }
            }
        }
    }

    @Test
    fun endpointNamesAreAllowlistedAndOverlappingRequestsHaveSeparateIds() {
        val events = mutableListOf<String>()
        val trace = LibraryBrowseDiagnostics(events::add)
        val first = trace.request("getAlbum")
        val second = trace.request("getAlbum")
        first("http-started")
        second("http-started")
        first("body-completed")
        trace.request("getAlbum?entire-query-secret")("http-started")
        assertTrue(events[0].contains("request=0 endpoint=getAlbum"))
        assertTrue(events[1].contains("request=1 endpoint=getAlbum"))
        assertTrue(events[2].contains("request=0 endpoint=getAlbum"))
        assertTrue(events[3].contains("endpoint=other"))
        assertFalse(events.joinToString().contains("entire-query-secret"))
    }
}
