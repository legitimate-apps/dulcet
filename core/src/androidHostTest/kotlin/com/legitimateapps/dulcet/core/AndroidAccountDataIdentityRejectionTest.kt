package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Public Android sign-out composition, real HTTP sender and actual persistent outbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAccountDataIdentityRejectionTest {
    @Test fun aHeldSignOutReplyAfterExternalRejectionKeepsBothPlaysAndStopsLaterRows() = heldSignOut(reject = true)
    @Test fun aHeldSignOutReplyFromTheAcceptedServerDrainsBothPlays() = heldSignOut(reject = false)

    private fun heldSignOut(reject: Boolean) = runBlocking {
        Receiver().use { receiver ->
            val context = RuntimeEnvironment.getApplication()
            val owner = "provider:signout-identity:${UUID.randomUUID()}"
            val account = PlaybackEndpointAccount(owner, receiver.url, "fixture-user", "fixture-password", true)
            val store = DulcetDriverFactory(context).openDulcetDatabase()
            try {
                val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock(System::currentTimeMillis))
                repeat(2) { index -> outbox.persistSynchronously(RecordedPlaybackEvent.SubmittedPlay(
                    ProviderItemId(owner, "song-$index"), PlaybackWallClockTime(System.currentTimeMillis() - 120_000 + index))) }
            } finally { store.close() }
            val data = AndroidAccountData(context, owner)
            val before = assertNotNull(data.pendingPlays())
            assertEquals(2, before.size)
            val running = async(Dispatchers.IO) { data.submitPlays(account) }
            try {
                assertTrue(receiver.entered.await(10, TimeUnit.SECONDS), "The public production sender must reach its first scrobble")
                assertTrue(receiver.paths.contains("/rest/ping.view"), "The real sign-out identity check ran first")
                if (reject) AndroidServerIdentity.validate(owner) { DomainError.Protocol.ServerIdentityChanged }
                receiver.release.countDown()
                val remaining = running.await()
                if (reject) {
                    assertEquals(before, remaining, "A held success cannot acknowledge or delete the old account's plays")
                    assertEquals(1, receiver.paths.count { it == "/rest/scrobble.view" }, "Every later outbox row stays unsent")
                } else {
                    assertEquals(emptySet(), remaining)
                    assertEquals(2, receiver.paths.count { it == "/rest/scrobble.view" })
                }
            } finally { receiver.release.countDown() }
        }
    }

    private class Receiver : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val paths = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val executor = Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try { socket.accept().use { client ->
                        client.soTimeout = 10_000
                        val reader = client.getInputStream().bufferedReader()
                        val line = checkNotNull(reader.readLine()).split(' ')
                        val uri = URI(line[1])
                        val path = uri.path
                        while (!reader.readLine().isNullOrEmpty()) { }
                        paths += path
                        if (path.startsWith("/rest/")) {
                            val keys = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') }.toSet()
                            check(keys.containsAll(setOf("u", "t", "s"))) { "The production request must be signed" }
                        }
                        if (path == "/rest/scrobble.view" && paths.count { it == path } == 1) {
                            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                        }
                        val json = if (path == "/rest/ping.view")
                            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"1.0.0"}}"""
                            else """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
                        val bytes = json.toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    } } catch (failure: Throwable) { if (!socket.isClosed) failures += failure }
                }
            }
        }
        override fun close() {
            release.countDown(); socket.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "Sign-out receiver failed: $failures")
        }
    }
}
