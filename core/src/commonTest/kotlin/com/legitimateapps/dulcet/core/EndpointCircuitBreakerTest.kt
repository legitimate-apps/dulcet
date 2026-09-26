package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** §10.4's breaker in isolation, on a manual monotonic clock. */
class EndpointCircuitBreakerTest {
    private var now = 10_000L
    private val breaker = EndpointCircuitBreaker(failureThreshold = 3, openMillis = 60_000, monotonicMillis = { now })
    private val timeout = DomainError.Transport.Timeout
    private val unreachable = DomainError.Transport.Unreachable

    @Test
    fun threeConsecutiveFailuresOpenItAndTwoDoNot() {
        repeat(2) { breaker.recordFailure(E, timeout, ordinary()) }
        assertOrdinary(breaker.admit(E))
        breaker.recordFailure(E, timeout, ordinary())
        val open = assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        assertEquals(timeout, open.lastError)
        assertEquals(60_000, open.retryInMillis)
        assertTrue(breaker.isOpen(E))
    }

    // ---- A server that asks for quiet (a 429, trap 24): open at once, for as long as it asked ----

    @Test
    fun oneBusyAnswerOpensItAtOnceForTheLongerOfThePeriodAndItsRetryAfter() {
        breaker.recordFailure(E, DomainError.Server.Busy(200.seconds), ordinary())
        val open = assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        assertEquals(200_000, open.retryInMillis)
        now += 199_999
        assertTrue(breaker.isOpen(E))
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now += 1
        assertFalse(breaker.isOpen(E))
        trial(breaker.admit(E))
    }

    @Test
    fun aBusyAnswerWithoutALongerRetryAfterOpensForThePeriod() {
        for (retryAfter in listOf(null, 5.seconds)) {
            val fresh = EndpointCircuitBreaker(failureThreshold = 3, openMillis = 60_000, monotonicMillis = { now })
            fresh.recordFailure(E, DomainError.Server.Busy(retryAfter), fresh.admit(E) as EndpointCircuitBreaker.Admission.Allowed)
            assertEquals(60_000, assertIs<EndpointCircuitBreaker.Admission.Open>(fresh.admit(E)).retryInMillis, "$retryAfter")
        }
    }

    /** A `Retry-After` beyond the reader's busy cap holds for that cap, the same one the outbox flushes use. */
    @Test
    fun aBusyHoldIsCappedAtTheSharedBusyCap() {
        for (asked in listOf(3_600.seconds, 2.days)) {
            val fresh = EndpointCircuitBreaker(failureThreshold = 3, openMillis = 60_000, monotonicMillis = { now })
            fresh.recordFailure(E, DomainError.Server.Busy(asked), fresh.admit(E) as EndpointCircuitBreaker.Admission.Allowed)
            assertEquals(
                LIBRARY_BUSY_CAP.inWholeMilliseconds,
                assertIs<EndpointCircuitBreaker.Admission.Open>(fresh.admit(E)).retryInMillis,
                "Retry-After $asked",
            )
        }
    }

    @Test
    fun theExplicitRequestIsStillAdmittedInsideABusyHoldAndItsOwnBusyHoldsAgain() {
        breaker.recordFailure(E, DomainError.Server.Busy(200.seconds), ordinary())
        now += 60_000
        val trial = trial(breaker.admit(E, explicit = true))
        breaker.recordFailure(E, DomainError.Server.Busy(200.seconds), trial)
        now += 199_999
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now += 1
        trial(breaker.admit(E))
    }

    /** A late 429 from a call admitted before the breaker opened extends the hold to its own `Retry-After`. */
    @Test
    fun aStragglersBusyAnswerExtendsAnOpenPeriod() {
        val straggler = ordinary()
        val later = ordinary()
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 10
        breaker.recordFailure(E, DomainError.Server.Busy(200.seconds), straggler)
        // A later 429 asking for less never shortens the hold already running.
        breaker.recordFailure(E, DomainError.Server.Busy(null), later)
        now += 60_000
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E), "the ordinary period ended the server's hold")
        now += 200_000 - 60_000 - 1
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now += 1
        trial(breaker.admit(E))
    }

    /** ...and never ends a trial in flight: only the trial's own outcome does. */
    @Test
    fun aStragglersBusyAnswerNeverEndsTheTrial() {
        val straggler = ordinary()
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val trial = trial(breaker.admit(E))
        breaker.recordFailure(E, DomainError.Server.Busy(3_600.seconds), straggler)
        // The trial is still the one in flight: nothing else is admitted, explicit or not.
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E, explicit = true))
        breaker.recordSuccess(E, trial)
        assertFalse(breaker.isOpen(E), "the trial's success closes it, as always")
    }

    /**
     * A hold already running is never shortened by a later failure — not even the trial's own. A
     * straggler's 429 holds until +200 s; the trial then times out, which alone would hold for one
     * 60 s period. The longer end stands (round-7 review, NIT R7-1: the trial's failure replaced
     * the hold and re-admitted about 140 s before the server's `Retry-After` ended).
     */
    @Test
    fun aTrialsOrdinaryFailureNeverShortensAStragglersLongerBusyHold() {
        val straggler = ordinary()
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val trial = trial(breaker.admit(E))
        val heldUntil = now + 200_000
        breaker.recordFailure(E, DomainError.Server.Busy(200.seconds), straggler)
        now += 1_000
        breaker.recordFailure(E, timeout, trial)
        // One period after the trial's failure: where the replaced hold re-admitted.
        now += 60_000
        val open = assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E), "re-admitted inside the server's hold")
        assertEquals(heldUntil - now, open.retryInMillis)
        assertEquals(timeout, open.lastError, "the trial's error is still the diagnostic")
        now = heldUntil - 1
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now = heldUntil
        trial(breaker.admit(E))
    }

    /** ...nor by the trial's own 429 asking for less than the hold already running. */
    @Test
    fun aTrialsShorterBusyAnswerNeverShortensAStragglersLongerBusyHold() {
        val straggler = ordinary()
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val trial = trial(breaker.admit(E))
        val heldUntil = now + 200_000
        breaker.recordFailure(E, DomainError.Server.Busy(200.seconds), straggler)
        now += 1_000
        breaker.recordFailure(E, DomainError.Server.Busy(null), trial)
        now = heldUntil - 1
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now = heldUntil
        trial(breaker.admit(E))
    }

    /**
     * Only the trial's own success closes an open breaker. A straggler's success — a call admitted
     * before the breaker opened, answering late — ends neither the hold nor the trial in flight:
     * it was sent before the server asked for quiet, so it says nothing against that request.
     */
    @Test
    fun aStragglersSuccessEndsNeitherAHoldNorTheTrial() {
        val stragglers = List(2) { ordinary() }
        val heldUntil = now + 200_000
        breaker.recordFailure(E, DomainError.Server.Busy(200.seconds), ordinary())
        now += 10
        breaker.recordSuccess(E, stragglers[0])
        assertTrue(breaker.isOpen(E), "a straggler's success ended the server's hold")
        now = heldUntil - 1
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now = heldUntil
        val trial = trial(breaker.admit(E))
        breaker.recordSuccess(E, stragglers[1])
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E, explicit = true), "a straggler's success ended the trial")
        // The positive control: the trial's own success does close it.
        breaker.recordSuccess(E, trial)
        assertFalse(breaker.isOpen(E))
        assertOrdinary(breaker.admit(E))
    }

    /**
     * The same holds for the whole open state, not only while a hold runs or a trial is out, and
     * for an ordinary three-failure opening as much as a 429: one trial decides. A concurrent
     * call's late success — during the period, or after it with no trial admitted yet — closes
     * nothing, so recovery waits for the period and then the trial's own answer. Deliberate.
     */
    @Test
    fun aStragglersSuccessAfterTheHoldEndedStillLeavesTheNextCallTheTrial() {
        val stragglers = List(2) { ordinary() }
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 10
        breaker.recordSuccess(E, stragglers[0])
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E), "a concurrent call's success closed an ordinary opening")
        // The period ends and nothing has been admitted since.
        now += 60_000
        assertFalse(breaker.isOpen(E), "the hold has ended: the window this test is about")
        breaker.recordSuccess(E, stragglers[1])
        // Still the trial, not an ordinary admission: the straggler's success closed nothing.
        val trial = trial(breaker.admit(E))
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E), "no second call beside the trial")
        breaker.recordSuccess(E, trial)
        assertOrdinary(breaker.admit(E))
    }

    @Test
    fun failuresOfAnyClassAddUp() {
        // A proxy alternating a 502 page with a timeout is one unhealthy endpoint, not two.
        breaker.recordFailure(E, DomainError.Protocol.MalformedEnvelope, ordinary())
        breaker.recordFailure(E, timeout, ordinary())
        assertFalse(breaker.isOpen(E))
        breaker.recordFailure(E, DomainError.Server.Unknown(0), ordinary())
        val open = assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        assertEquals(DomainError.Server.Unknown(0), open.lastError, "the class survives only as the diagnostic")
    }

    @Test
    fun anExplicitRequestIsAdmittedAsTheTrialInsideThePeriod() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 1
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        val trial = trial(breaker.admit(E, explicit = true))
        // Still one at a time.
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E, explicit = true))
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        // Its failure starts a full period at that moment.
        now += 10
        breaker.recordFailure(E, unreachable, trial)
        now += 59_999
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now += 1
        trial(breaker.admit(E))
    }

    @Test
    fun anExplicitRequestOnAClosedBreakerIsAnOrdinaryOne() {
        assertOrdinary(breaker.admit(E, explicit = true))
        repeat(2) { breaker.recordFailure(E, timeout, ordinary()) }
        assertOrdinary(breaker.admit(E, explicit = true))
    }

    @Test
    fun aSuccessResetsTheCount() {
        repeat(2) { breaker.recordFailure(E, timeout, ordinary()) }
        breaker.recordSuccess(E, ordinary())
        repeat(2) { breaker.recordFailure(E, timeout, ordinary()) }
        assertFalse(breaker.isOpen(E))
    }

    @Test
    fun endpointsAreIndependent() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        assertTrue(breaker.isOpen(E))
        assertFalse(breaker.isOpen("getLyrics"))
    }

    @Test
    fun itStaysOpenForExactlyThePeriodThenAdmitsOneTrial() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 59_999
        assertEquals(1, assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E)).retryInMillis)
        now += 1
        trial(breaker.admit(E))
        // While the trial is out, everything else is refused.
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
    }

    @Test
    fun aStragglerFailureWhileOpenDoesNotExtendThePeriod() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 30_000
        // A request admitted before the breaker opened, answering late.
        breaker.recordFailure(E, timeout, ordinary())
        now += 30_000
        trial(breaker.admit(E))
    }

    @Test
    fun aFailedTrialReopensForAFullPeriodAndASuccessfulOneCloses() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val trial = trial(breaker.admit(E))
        breaker.recordFailure(E, unreachable, trial)
        val reopened = assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        assertEquals(unreachable, reopened.lastError)
        assertEquals(60_000, reopened.retryInMillis)
        now += 60_000
        breaker.recordSuccess(E, trial(breaker.admit(E)))
        assertOrdinary(breaker.admit(E))
        // Closed means closed: it takes a full threshold again to reopen.
        repeat(2) { breaker.recordFailure(E, timeout, ordinary()) }
        assertFalse(breaker.isOpen(E))
    }

    @Test
    fun anAbandonedTrialGivesItsSlotBack() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        breaker.abandon(E, trial(breaker.admit(E)))
        trial(breaker.admit(E))
    }

    @Test
    fun askingWhetherItIsOpenDoesNotConsumeTheTrial() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        assertFalse(breaker.isOpen(E))
        trial(breaker.admit(E))
    }

    @Test
    fun aTrialInFlightReadsAsOpenUntilItIsAbandoned() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val trial = trial(breaker.admit(E))
        assertTrue(breaker.isOpen(E))
        breaker.abandon(E, trial)
        assertFalse(breaker.isOpen(E))
    }

    @Test
    fun resetForgetsEveryEndpoint() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        repeat(2) { breaker.recordFailure(OTHER, timeout, ordinary()) }
        assertTrue(breaker.isOpen(E))
        breaker.reset()
        assertOrdinary(breaker.admit(E))
        breaker.recordFailure(OTHER, timeout, ordinary())
        assertFalse(breaker.isOpen(OTHER), "the other endpoint's count restarted too")
        repeat(2) { breaker.recordFailure(OTHER, timeout, ordinary()) }
        assertTrue(breaker.isOpen(OTHER), "and a full threshold still opens it")
    }

    @Test
    fun aStragglersFailureWhileTheTrialIsOutLeavesTheTrialInFlight() {
        // The reviewer's probe: a call admitted while closed answers late, after the trial went out.
        val straggler = assertIs<EndpointCircuitBreaker.Admission.Allowed>(breaker.admit(E))
        assertFalse(straggler.trial)
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val trial = trial(breaker.admit(E))
        now += 5
        breaker.recordFailure(E, unreachable, straggler)
        // No second trial beside the first, however it is asked for...
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E, explicit = true))
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        assertTrue(breaker.isOpen(E))
        // ...the straggler's error is still the diagnostic...
        assertEquals(unreachable, assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E)).lastError)
        // ...and the straggler's cancellation would not free the slot either.
        breaker.abandon(E, straggler)
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E, explicit = true))
        // The trial's own failure is what ends it, with a full period from that moment.
        now += 1_000
        breaker.recordFailure(E, timeout, trial)
        now += 59_999
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E))
        now += 1
        trial(breaker.admit(E))
    }

    @Test
    fun aTrialAdmittedBeforeAResetCannotEndOneAdmittedAfterIt() {
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val before = trial(breaker.admit(E))
        breaker.reset()
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        now += 60_000
        val after = trial(breaker.admit(E))
        breaker.recordFailure(E, timeout, before)
        breaker.abandon(E, before)
        assertIs<EndpointCircuitBreaker.Admission.Open>(breaker.admit(E, explicit = true))
        // The positive control: the trial it did admit ends it.
        breaker.abandon(E, after)
        trial(breaker.admit(E, explicit = true))
    }

    @Test
    fun admissionsFromBeforeAResetSayNothingAfterIt() {
        // The reviewer's probe: three calls go out while the network is failing...
        val inFlight = List(3) { assertIs<EndpointCircuitBreaker.Admission.Allowed>(breaker.admit(E)) }
        // ...the device comes back online: a new observation window...
        breaker.reset()
        // ...and the three pre-reset requests time out afterwards.
        inFlight.forEach { breaker.recordFailure(E, timeout, it) }
        assertFalse(breaker.isOpen(E), "failures from the old window opened the breaker in the new one")
        // The positive control: three failures of this window's calls do open it.
        val current = List(3) { assertIs<EndpointCircuitBreaker.Admission.Allowed>(breaker.admit(E)) }
        current.forEach { breaker.recordFailure(E, timeout, it) }
        assertTrue(breaker.isOpen(E))
    }

    @Test
    fun aSuccessFromBeforeAResetDoesNotCloseTheBreakerAfterIt() {
        val old = assertIs<EndpointCircuitBreaker.Admission.Allowed>(breaker.admit(E))
        breaker.reset()
        repeat(3) { breaker.recordFailure(E, timeout, ordinary()) }
        assertTrue(breaker.isOpen(E))
        breaker.recordSuccess(E, old)
        assertTrue(breaker.isOpen(E), "a success from the old window closed the breaker in the new one")
        // The positive control: this window's success does close it.
        now += 60_000
        breaker.recordSuccess(E, trial(breaker.admit(E)))
        assertFalse(breaker.isOpen(E))
    }

    /**
     * An admission that was never the trial: a call admitted while the breaker was closed, in the
     * current generation. Taken from the breaker, on an endpoint nothing has touched.
     */
    private fun ordinary(): EndpointCircuitBreaker.Admission.Allowed =
        assertIs<EndpointCircuitBreaker.Admission.Allowed>(breaker.admit(UNTOUCHED)).also { assertFalse(it.trial) }

    private fun assertOrdinary(admission: EndpointCircuitBreaker.Admission) {
        assertFalse(assertIs<EndpointCircuitBreaker.Admission.Allowed>(admission).trial, "a trial: $admission")
    }

    private fun trial(admission: EndpointCircuitBreaker.Admission): EndpointCircuitBreaker.Admission.Allowed =
        assertIs<EndpointCircuitBreaker.Admission.Allowed>(admission).also { assertTrue(it.trial, "not the trial: $it") }

    private companion object {
        const val E = "getLyricsBySongId"
        const val OTHER = "getLyrics"
        const val UNTOUCHED = "an endpoint no test records anything for"
    }
}
