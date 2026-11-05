package com.legitimateapps.dulcet.core

import android.os.Handler
import android.os.Looper
import android.os.NetworkOnMainThreadException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Leaving a page while a response is open must not kill the process. Each cancel runs uncaught on
 * Android's real main thread, under the platform's own network policy, as `LaunchedEffect.onForgotten`
 * does: a close that reaches the socket there throws out of the cancel and ends the instrumentation.
 *
 * `tools/ci/run-android-device-proof` runs one method per process, so one crash reports one method.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCancelOnMainDeviceTest {
    private val mainThread get() = Looper.getMainLooper().thread

    /** Without this the rest prove nothing: reading a socket on main must really be forbidden here. */
    @Test fun theMainThreadForbidsSocketReads() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            lateinit var peer: Socket
            val connected = CountDownLatch(1)
            kotlin.concurrent.thread {
                peer = Socket(server.inetAddress, server.localPort)
                connected.countDown()
            }
            server.accept().use {
                assertTrue(connected.await(HARNESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                val failure = AtomicReference<Throwable?>()
                onMain { failure.set(runCatching { peer.getInputStream().read() }.exceptionOrNull()) }
                assertIs<NetworkOnMainThreadException>(failure.get())
                peer.close()
            }
        }
    }

    @Test fun heldWriterChunked() = heldWriter(CancelRaceFraming.Chunked)

    @Test fun heldWriterFixedLength() = heldWriter(CancelRaceFraming.FixedLength)

    @Test fun heldWriterTls() = heldWriter(CancelRaceFraming.Chunked, CancelRaceTls())

    private fun heldWriter(framing: CancelRaceFraming, tls: CancelRaceTls? = null) {
        HeldWriterRace(framing, tls).use { race ->
            race.reachTheGap()
            onMain { race.cancelOwner() }

            assertTrue(race.writerRetiredBeforeFinish, "The writer must retire inside the cancel for this to test the gap")
            assertTrue(race.awaitBodyClosed(), "The body must still be closed, or its connection leaks")
            assertEquals(0, race.probe.reads.get(), "The writer never started, so only the cancel path closes the body")
            assertFalse(race.probe.closeThreads.single() === mainThread, "The close reads the socket")
            assertTrue(race.nextRequestSucceeds(), "The same client must keep working after the cancel")
        }
    }

    /**
     * The ordinary case: part of a body sent, then a stall, with the writer blocked in its read. This
     * alone never reproduced the crash, because the blocked writer keeps the call job open and closes
     * the body on its own thread. It stays as the check that `Call.cancel` on main is safe, TLS included.
     */
    @Test fun stalledChunkedBody() = stalledBody(CancelRaceFraming.Chunked)

    @Test fun stalledTlsBody() = stalledBody(CancelRaceFraming.Chunked, CancelRaceTls())

    private fun stalledBody(framing: CancelRaceFraming, tls: CancelRaceTls? = null) {
        val probe = ResponseBodyProbe()
        StalledBodyServer(framing, tls).use { server ->
            cancelRaceClient(probe, tls).use { client ->
                val owner = request(client, server)
                owner.start()
                assertTrue(pollUntil { probe.reads.get() > 0 }, "The writer must be reading before the cancel")
                onMain { owner.cancel() }
                assertTrue(probe.closeFinished.await(HARNESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertFalse(probe.closeThreads.single() === mainThread, "The close reads the socket")
            }
        }
    }

    /** No forcing: real cancels on main, jittered around the moment the response headers arrive. */
    @Test fun unforcedCancelsAroundTheResponse() {
        val probe = ResponseBodyProbe()
        val main = Handler(Looper.getMainLooper())
        val current = AtomicReference<Job?>()
        var jitter = 0
        probe.onStalledResponse = {
            val spinNanos = (jitter++ % 40) * 10_000L
            main.postAtFrontOfQueue {
                val until = System.nanoTime() + spinNanos
                while (System.nanoTime() < until) { /* Spin: a timer is far coarser than this window. */ }
                current.get()?.cancel()
            }
        }
        StalledBodyServer(CancelRaceFraming.Chunked).use { server ->
            cancelRaceClient(probe).use { client ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STORM_SECONDS)
                var iterations = 0
                while (System.nanoTime() < deadline) {
                    val owner = request(client, server)
                    current.set(owner)
                    owner.start()
                    runBlocking { withTimeout(HARNESS_TIMEOUT_MILLIS) { owner.join() } }
                    iterations++
                }
                assertTrue(pollUntil { probe.closes.get() > 0 }, "A storm that closes nothing tested nothing")
                val onMain = probe.closeThreads.count { it === mainThread }
                Log.i(
                    "DulcetCancelProof",
                    "storm iterations=$iterations closes=${probe.closes.get()} reads=${probe.reads.get()} closesOnMain=$onMain",
                )
                assertEquals(0, onMain, "A close reached the main thread and survived only because its socket was idle")
            }
        }
    }

    private fun request(client: HttpClient, server: StalledBodyServer): Job =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
            withContext(Dispatchers.IO) {
                client.prepareGet(server.url("/stalled")).execute { awaitCancellation() }
            }
        }

    /** Deliberately uncaught, as a composition's apply is: a failure here takes the process down. */
    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            block()
            done.countDown()
        }
        assertTrue(done.await(HARNESS_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS), "The main thread never finished the cancel")
    }

    private companion object {
        const val STORM_SECONDS = 45L
    }
}
