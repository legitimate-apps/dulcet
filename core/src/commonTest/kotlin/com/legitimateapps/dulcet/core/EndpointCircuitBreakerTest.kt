package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
