package com.legitimateapps.dulcet.core

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Real socket tests of the profile used for enrollment (HTTP here tests only transport limits). */
class ConnectionDirectoryRequestProfileTest {
    @Test fun formPostKeepsEveryCredentialOutOfTheUrlAndReturnsTheBoundedBody() {
        Fixture().use { fixture ->
            val response = call(fixture)
            assertEquals("{}", response.body.decodeToString())
            val request = fixture.received.single()
            assertEquals("POST /rest/getDulcetConnections.view HTTP/1.1", request.first)
            assertTrue(request.second.contains("u=directory-canary-user"))
            assertTrue(request.second.contains("t="))
            assertTrue(request.second.contains("s="))
            assertFalse(request.second.contains("directory-canary-password"))
            assertEquals(AuthenticationLocation.FormBody, response.requestTrace.authenticationLocation)
            assertTrue(response.requestTrace.queryAuthenticationParameters.isEmpty())
        }
    }

    @Test fun sameOriginRedirectNeverIssuesASecondRequest() {
        Fixture(status = 307).use { fixture ->
            val failure = assertIs<AuthenticatedEndpointFailure>(runCatching { call(fixture) }.exceptionOrNull())
            assertEquals(DomainError.Security.ResourceOutsideRoute, failure.error)
            assertEquals(1, fixture.received.size)
        }
    }

    @Test fun declaredAndStreamedOversizedFormResponsesAreRefused() {
        for (declared in listOf(true, false)) Fixture(bodyBytes = LIMIT + 1, declared = declared).use { fixture ->
            val failure = assertIs<AuthenticatedEndpointFailure>(runCatching { call(fixture) }.exceptionOrNull())
            assertEquals(DomainError.Protocol.TooLarge, failure.error, "declared=$declared")
            assertEquals(1, fixture.received.size)
        }
    }

    @Test fun authenticationStatusSurvivesAnOversizedDirectoryReply() {
        Fixture(status = 401, bodyBytes = LIMIT + 1).use { fixture ->
            val failure = assertIs<AuthenticatedEndpointFailure>(runCatching { call(fixture) }.exceptionOrNull())
            assertEquals(DomainError.Auth.InvalidCredentials, failure.error)
        }
    }

    private fun call(fixture: Fixture): AuthenticatedEndpointResponse = runBlocking {
        val client = AuthenticatedEndpointClient(
            AuthenticatedEndpointCredentials(fixture.base, "directory-canary-user", "directory-canary-password", true),
            operationName = "connections.directory",
        )
        try {
            client.requestRepeated("getDulcetConnections", emptyList(), formPost = true,
                options = AuthenticatedEndpointRequestOptions(maxBodyBytes = LIMIT, rejectRedirects = true))
        } finally { client.close() }
    }

    private class Fixture(val status: Int = 200, val bodyBytes: Int = 2, val declared: Boolean = true) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val clients = CopyOnWriteArrayList<Socket>()
        val received = CopyOnWriteArrayList<Pair<String, String>>()
        val base = "http://127.0.0.1:${server.localPort}"
        private val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                clients += socket
                thread(isDaemon = true) {
                    runCatching {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val line = input.readLine()
                            var length = 0
                            while (true) {
                                val header = input.readLine() ?: break
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            }
                            val body = CharArray(length)
                            var read = 0
                            while (read < length) {
                                val n = input.read(body, read, length - read)
                                if (n < 0) break
                                read += n
                            }
                            received += line to String(body, 0, read)
                            val output = it.getOutputStream()
                            output.write(buildString {
                                append("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\n")
                                if (declared) append("Content-Length: $bodyBytes\r\n")
                                if (status == 307) append("Location: $base/redirected\r\n")
                                append("Connection: close\r\n\r\n")
                            }.toByteArray())
                            output.write(if (bodyBytes == 2) "{}".toByteArray() else ByteArray(bodyBytes) { 'a'.code.toByte() })
                            output.flush()
                        }
                    }
                }
            }
        }
        override fun close() {
            server.close()
            clients.forEach { runCatching { it.close() } }
            acceptor.join(1000)
        }
    }
    companion object { const val LIMIT = 65_536 }
}
