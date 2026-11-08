package com.legitimateapps.dulcet.core

import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host half of the cancel-on-main proof. A JVM has no network policy, so this cannot see
 * NetworkOnMainThreadException: it pins the cause, that a cancel on the main thread never closes the body
 * there. `AndroidCancelOnMainDeviceTest` runs the same race under the real policy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCancelOffCallerThreadTest {
    // TLS runs only in AndroidCancelOnMainDeviceTest: on Linux hosts this harness's loopback TLS
    // connection ends before the response, which the emulator and macOS hosts do not do.
    @Test fun aChunkedBodyIsNotClosedOnTheMainThread() = raceOnMain(CancelRaceFraming.Chunked)

    @Test fun aFixedLengthBodyIsNotClosedOnTheMainThread() = raceOnMain(CancelRaceFraming.FixedLength)

    /** Only main is policed: everywhere else the close stays synchronous, so its release keeps its order. */
    @Test fun aWorkerCancelStillClosesTheBodyBeforeReturning() {
        HeldWriterRace(CancelRaceFraming.Chunked).use { race ->
            race.reachTheGap()
            val worker = Executors.newSingleThreadExecutor { Thread(it, "cancelling-worker") }
            val (thread, closesWhenReturned) = worker.submit<Pair<Thread, Int>> {
                race.cancelOwner()
                Thread.currentThread() to race.probe.closes.get()
            }.get(HARNESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            worker.shutdown()

            assertTrue(race.writerRetiredBeforeFinish, "The writer must retire inside the cancel for this to test the gap")
            assertEquals(1, closesWhenReturned)
            assertSame(thread, race.probe.closeThreads.single())
        }
    }

    private fun raceOnMain(framing: CancelRaceFraming) {
        assertTrue(Looper.getMainLooper().isCurrentThread, "Robolectric's test thread stands in for main")
        HeldWriterRace(framing).use { race ->
            race.reachTheGap()
            race.cancelOwner()

            assertTrue(race.writerRetiredBeforeFinish, "The writer must retire inside the cancel for this to test the gap")
            assertTrue(race.awaitBodyClosed(), "The body must still be closed, or its connection leaks")
            assertEquals(0, race.probe.reads.get(), "The writer never started, so only the cancel path closes the body")
            assertEquals(1, race.probe.closes.get())
            assertNotEquals(Thread.currentThread(), race.probe.closeThreads.single(), "The close reads the socket")
            assertTrue(race.nextRequestSucceeds(), "The same client must keep working after the cancel")
        }
    }
}
