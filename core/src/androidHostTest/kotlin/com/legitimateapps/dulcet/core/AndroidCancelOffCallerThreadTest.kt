package com.legitimateapps.dulcet.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host half of the cancel-on-main proof. A JVM has no network policy, so this cannot see
 * NetworkOnMainThreadException: it pins the cause, that closing the body never happens on the thread
 * cancelling the request. `AndroidCancelOnMainDeviceTest` runs the same race under the real policy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidCancelOffCallerThreadTest {
    @Test fun aChunkedBodyIsNotClosedOnTheCancellingThread() = race(CancelRaceFraming.Chunked)

    @Test fun aFixedLengthBodyIsNotClosedOnTheCancellingThread() = race(CancelRaceFraming.FixedLength)

    @Test fun aTlsBodyIsNotClosedOnTheCancellingThread() = race(CancelRaceFraming.Chunked, CancelRaceTls())

    private fun race(framing: CancelRaceFraming, tls: CancelRaceTls? = null) {
        HeldWriterRace(framing, tls).use { race ->
            race.reachTheGap()
            val canceller = Executors.newSingleThreadExecutor { Thread(it, "cancelling-thread") }
            val cancellingThread = canceller.submit<Thread> {
                race.cancelOwner()
                Thread.currentThread()
            }.get(HARNESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            canceller.shutdown()

            assertTrue(race.writerRetiredBeforeFinish, "The writer must retire inside the cancel for this to test the gap")
            assertTrue(race.awaitBodyClosed(), "The body must still be closed, or its connection leaks")
            assertEquals(0, race.probe.reads.get(), "The writer never started, so only the cancel path closes the body")
            assertEquals(1, race.probe.closes.get())
            assertNotEquals(cancellingThread, race.probe.closeThreads.single(), "The close reads the socket")
            assertTrue(race.nextRequestSucceeds(), "The same client must keep working after the cancel")
        }
    }
}
