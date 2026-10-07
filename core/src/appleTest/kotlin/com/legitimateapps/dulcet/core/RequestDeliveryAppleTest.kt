package com.legitimateapps.dulcet.core

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whether a request that got no answer may have reached the server, through the Darwin HTTP stack
 * against a real loopback socket (spec §18.3, §18.6): only a connection never made proves the
 * request never arrived, so only then may an outbox take a write back and send it again as if it
 * had never gone. The JVM's counterpart is RequestDeliveryTransportTest.
 *
 * NSURLSession sends a GET again by itself when its connection closes without an answer, and a POST
 * never: OBSERVED here, a GET reached the fixture three times (it serves three connections) and a POST
 * once, both then failing NSURLErrorNetworkConnectionLost (-1005). Subsonic writes are GETs unless the
 * server advertises `formPost` (§10.4), so the platform itself can send a write twice; the
 * classification below is therefore shown on a POST, as playlist writes travel with `formPost`.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class RequestDeliveryAppleTest {
    @Test
    fun aConnectionClosedWithoutAnAnswerMayHaveCarriedTheRequest() = runBlocking {
        val server = OneShotLoopbackServer(ByteArray(0))
        val serverThread = newSingleThreadContext("silent-fixture")
        try {
            val served = async(serverThread) { serveUpTo(server, 3) }
            val failure = transport(server.port) {
                runCatching { it.requestRepeated("createPlaylist", listOf("name" to "Road", "songId" to "song-1"), formPost = true) }.exceptionOrNull()
            }
            server.closeListener()
            // The control: the server read the request before it closed, and it was sent once.
            assertEquals(listOf("POST /rest/createPlaylist"), served.await().map { it.substringBefore(".view") }, "the requests the fixture read")
            assertNotNull(failure, "a request with no answer succeeded")
            assertEquals(DomainError.Transport.Unreachable, failure.asReaderError(), describe(failure))
            assertFalse(provesNeverConnected(failure), "a connection made and then closed was read as never made: ${describe(failure)}")
        } finally {
            server.closeListener()
            serverThread.close()
        }
    }

    /** Pins the platform fact above: if NSURLSession stops sending a GET again, this fails. */
    @Test
    fun aGetClosedWithoutAnAnswerIsSentAgainByThePlatform() = runBlocking {
        val server = OneShotLoopbackServer(ByteArray(0))
        val serverThread = newSingleThreadContext("silent-fixture")
        try {
            val served = async(serverThread) { serveUpTo(server, 3) }
            val failure = transport(server.port) { runCatching { it.request("star", mapOf("id" to "song-1")) }.exceptionOrNull() }
            server.closeListener()
            val heads = served.await()
            assertTrue(heads.size >= 2, "a GET with no answer was sent ${heads.size} time(s): $heads")
            assertTrue(heads.all { it.startsWith("GET /rest/star") }, "$heads")
            assertNotNull(failure, "a request with no answer succeeded")
            assertEquals(DomainError.Transport.Unreachable, failure.asReaderError(), describe(failure))
        } finally {
            server.closeListener()
            serverThread.close()
        }
    }

    @Test
    fun aRefusedConnectionProvesTheRequestNeverLeft() = runBlocking {
        val port = OneShotLoopbackServer(ByteArray(0)).run { closeListener(); port } // nothing listens there now
        val failure = transport(port) { runCatching { it.request("star", mapOf("id" to "song-1")) }.exceptionOrNull() }
        assertNotNull(failure, "a request to a closed port succeeded")
        assertEquals(DomainError.Transport.Unreachable, failure.asReaderError(), describe(failure))
        assertTrue(provesNeverConnected(failure), "a refused connection was not read as never made: ${describe(failure)}")
    }

    /** The request line of each connection served, up to [limit], until the listener is closed. */
    private fun serveUpTo(server: OneShotLoopbackServer, limit: Int): List<String> = buildList {
        repeat(limit) { add(runCatching { server.serveOnce() }.getOrNull()?.requestHead?.lineSequence()?.first() ?: return@buildList) }
    }

    private suspend fun <T> transport(port: Int, block: suspend (KtorLibraryEndpointTransport) -> T): T {
        val transport = KtorLibraryEndpointTransport(
            LibraryBrowseRequest(
                providerInstanceId = "provider-instance-delivery-fixture",
                normalizedBaseUrl = "http://127.0.0.1:$port",
                username = "listener",
                password = "fixture-password",
                allowLocalHttp = true,
            ),
            null, null, systemHostResolver(),
        )
        return try {
            block(transport)
        } finally {
            transport.close()
        }
    }

    /** The failure and the NSURLError code beneath it, for an assertion's message. */
    @OptIn(ExperimentalForeignApi::class)
    private fun describe(failure: Throwable): String {
        var current: Throwable? = failure
        repeat(16) {
            val origin = (current as? DarwinHttpRequestException)?.origin
            if (origin != null) return "${failure::class.simpleName} NSURLError ${origin.domain} ${origin.code}"
            current = current?.cause ?: return "${failure::class.simpleName} without an NSError"
        }
        return "${failure::class.simpleName}"
    }
}
