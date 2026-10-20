package com.legitimateapps.dulcet.core

import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The actual controller's HTTP resource factory, real socket requests, and one retained resource.
 * PlayerProbe observes preparation/intent only; this makes no decoder claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlaybackCredentialRefreshTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun theSameInstalledResourceSignsItsNextRangeWithTheRefreshedPasswordAndStopsAtRebind() {
        RangeServer().use { server ->
            val context = RuntimeEnvironment.getApplication()
            val name = "credential-refresh-${UUID.randomUUID()}.db"
            val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, name).createDriver())
            val account = PlaybackEndpointAccount("provider:credential-refresh", server.url, "alice", "old-password", true)
            val probe = PlayerProbe()
            val prepared = mutableListOf<RemotePlaybackWirePlan>()
            val controller = AndroidPlaybackController(context, account, AndroidPlaybackControllerBoundaries(
                store, probe.player, { prepared += it }, { id -> song(id) }, { request ->
                    PlaybackResolutionResult.Resolved(RemotePlaybackWirePlan(request.playbackSessionId, request.attemptId,
                        request.itemId, PlaybackDeliveryPath.Legacy, PlaybackDeliveryProtocol.HttpProgressive,
                        request.sourceContainer, PlaybackWireTranscodeDecision.LegacyHint(null, null),
                        endpoint = "stream", parameters = mapOf("id" to request.itemId.rawId), resolutionRequest = request))
                }, {}))
            try {
                controller.setAutoplay(false)
                controller.playQueue(listOf(AndroidTrack(account.providerInstanceId, "song", "Song", null, null, 40_000, null)),
                    0, AndroidQueueSource.Search, "Search")
                settle { prepared.size == 1 && probe.requested }
                val attempt = controller.state.value.attemptId
                // This is the factory called by production preparePlaybackSource, including its
                // live authorizer supplier. The resource itself is never rebuilt for the seek.
                val resource = controller.httpPlaybackResource(prepared.single())
                val first = resource.open(0, 16)
                try { assertEquals(16, first.input.readBytes().size) } finally { first.close() }
                assertEquals("bytes=0-15", server.requests.single().range)
                assertToken(server.requests.single().query, "old-password")

                assertTrue(controller.refreshAccount(account.copy(password = "new-password")))
                assertEquals(attempt, controller.state.value.attemptId)
                assertEquals(1, prepared.size, "Credential refresh must not prepare another engine item")
                assertTrue(probe.requested)
                assertFalse(probe.released)
                val second = resource.open(16, 16)
                try { assertEquals(16, second.input.readBytes().size) } finally { second.close() }
                assertEquals(2, server.requests.size, "One HTTP request for each open of the same resource")
                assertEquals("bytes=16-31", server.requests.last().range)
                assertToken(server.requests.last().query, "new-password")
                assertNotEquals(token("old-password", server.requests.last().query.getValue("s")), server.requests.last().query["t"])

                AndroidPlaybackController.prepareForMediaBinding(account.copy(username = "another-user"))
                assertTrue(controller.isClosed)
                val failure = assertFailsWith<AndroidPlaybackIOException> { resource.open(32, 16) }
                assertEquals(DomainError.Transport.Cancelled, failure.error)
                assertEquals(2, server.requests.size, "A retired binding cannot sign or send another range")
            } finally {
                controller.close()
                store.close()
                context.deleteDatabase(name)
            }
        }
    }

    private fun assertToken(query: Map<String, String>, password: String) {
        assertEquals("alice", query["u"])
        assertEquals(token(password, assertNotNull(query["s"])), query["t"])
    }
    private fun token(password: String, salt: String) = MessageDigest.getInstance("MD5")
        .digest((password + salt).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun settle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Current remote plan never reached the engine" }
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
    }
    private fun song(id: String) = AuthenticatedEndpointResponse(200,
        """{"subsonic-response":{"status":"ok","song":{"id":"$id","suffix":"wav","duration":40}}}""".toByteArray(),
        "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
        RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
            emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))

    private data class Request(val query: Map<String, String>, val range: String?)
    private class RangeServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val executor = Executors.newSingleThreadExecutor()
        init { executor.submit {
            while (!listener.isClosed) try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val target = URI(checkNotNull(reader.readLine()).split(' ')[1])
                    val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    val query = target.rawQuery.split('&').associate {
                        val pair = it.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                    requests += Request(query, headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim())
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/wav\r\nContent-Length: 16\r\nConnection: close\r\n\r\n".toByteArray())
                        write(ByteArray(16)); flush()
                    }
                }
            } catch (error: Exception) { if (!listener.isClosed) failures += error }
        } }
        override fun close() {
            listener.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "HTTP fixture failures: $failures")
        }
    }
}
