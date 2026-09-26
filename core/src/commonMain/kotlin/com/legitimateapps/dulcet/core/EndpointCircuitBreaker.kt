package com.legitimateapps.dulcet.core

import kotlin.time.TimeSource

/**
 * The per-endpoint circuit breaker of §10.4. It records OBSERVED operational health and never
 * touches the ADVERTISED capability set: an open breaker stops calls to one endpoint for a bounded
 * period, and the endpoint gate — which reads only the advertised set — is unchanged throughout
 * (CORPUS §4 line 7: one failed request never revokes a capability; neither do three).
 *
 * - **Opens** after [failureThreshold] consecutive failures of the same endpoint, of any error
 *   class: a proxy alternating a gateway error page with a timeout is one unhealthy endpoint. The
 *   error survives only as [Admission.Open.lastError], the shell's diagnostic. A success resets
 *   the count.
 * - **Stays open** for [openMillis] of monotonic time (§18.8: a duration is measured on the
 *   monotonic clock, never the wall clock, and is never persisted).
 * - **A server that asks for quiet opens it at once.** A [DomainError.Server.Busy] answer (an HTTP
 *   429) opens the endpoint on that one failure, for the longer of [openMillis] and the server's
 *   `Retry-After` (trap 24), which is never taken beyond [LIBRARY_BUSY_CAP] — the one cap the
 *   reader applies to every wait a 429 asks for (§18.6), not a second one. **A hold already running
 *   is never shortened by any later failure**: a 429 that lands while the endpoint is open only
 *   ever lengthens it, a straggler's never ends the trial in flight, and the trial's own failure
 *   holds until the later of its new period and the running hold. An explicit request is still
 *   admitted inside the hold, as below.
 * - **Then admits exactly one trial.** While the trial is in flight every other call is refused.
 *   The trial's success closes the breaker. **Only the trial's own outcome ends the trial**; its
 *   failure reopens the breaker for another full period. A straggler — a call admitted before the
 *   breaker opened, answering late — is not the trial: its failure is recorded as the latest error
 *   and changes nothing else, and its success closes nothing, so the trial stays in flight and no
 *   second one can be admitted beside it. The trial is recognised by its [Admission.Allowed]
 *   instance, never by a flag a caller passes, so a trial admitted before a [reset] cannot end one
 *   admitted after it.
 * - **An explicit request is admitted as the trial at once**, inside the period: the person asked
 *   (a Retry control), which is not the automatic traffic the period exists to hold back. It is
 *   still the single trial — refused while another is in flight — and its failure starts a new
 *   full period.
 * - **[reset] forgets everything**, for a new observation window such as a reconnect: failures
 *   seen before the device lost its network say nothing about the endpoint after it. That includes
 *   the calls still in flight across it: every admission carries the [reset] generation it was
 *   handed out in, and an outcome of an older generation — failure, success or cancellation — is
 *   ignored, so three requests that went out before the reset and time out after it cannot open
 *   the breaker in the new window (third review).
 *
 * Callers classify answers. An item-scoped answer — "not found" (code 70) in a well-formed
 * envelope, or an answer refused for its size — is a well-formed answer from a healthy endpoint,
 * and the caller records it with [recordSuccess]. Cancellation is neither success nor failure: a
 * cancelled call hands its admission to [abandon], which gives the slot back only when that
 * admission is the trial in flight. Nor is a failure that is not the request's — the device's own
 * database failing before anything was sent: the caller abandons that admission too.
 *
 * Confined to its owner's thread like the reader that holds it; it has no lock.
 */
internal class EndpointCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openMillis: Long = 5 * 60_000L,
    private val monotonicMillis: () -> Long = monotonicMillisSinceCreation(),
) {
    init {
        require(failureThreshold > 0)
        require(openMillis > 0)
    }

    sealed interface Admission {
        /**
         * Send the request, and hand this admission back with its outcome. [trial] is true for the
         * single call a reopened period admits; [generation] is the [reset] generation it was
         * handed out in. Equal by value like any data class, but the breaker tells its trial apart
         * by identity: the instance it handed out.
         */
        data class Allowed(val trial: Boolean, val generation: Long) : Admission

        /** Do not send. [lastError] is the latest failure recorded, for the shell's diagnostic. */
        data class Open(val lastError: DomainError, val retryInMillis: Long) : Admission
    }

    private class State {
        var consecutive = 0

        /** When the open period ends, on the monotonic clock; null while the endpoint is closed. */
        var openUntil: Long? = null
        var lastError: DomainError? = null

        /** The admission handed to the trial now in flight, or null when none is. */
        var trial: Admission.Allowed? = null
    }

    private val states = mutableMapOf<String, State>()

    /**
     * How many times [reset] has run: the generation of every admission handed out now. A caller
     * reads it to tell work of the current observation window from work of an earlier one (the
     * lyrics flights of §18.4).
     */
    var generation = 0L
        private set

    /**
     * Whether a call to [endpoint] may be sent now. An allowed trial reserves the single slot.
     * [explicit] is a request the person made; it does not wait out the period.
     */
    fun admit(endpoint: String, explicit: Boolean = false): Admission {
        val state = states[endpoint] ?: return Admission.Allowed(trial = false, generation)
        val openUntil = state.openUntil ?: return Admission.Allowed(trial = false, generation)
        val remaining = openUntil - monotonicMillis()
        if (state.trial != null || (remaining > 0 && !explicit)) {
            return Admission.Open(
                lastError = checkNotNull(state.lastError),
                retryInMillis = remaining.coerceAtLeast(0),
            )
        }
        return Admission.Allowed(trial = true, generation).also { state.trial = it }
    }

    /**
     * A successful call to [endpoint], by the call [admission] admitted; ignored across a [reset].
     * Closed, any success resets the count. Open, only the trial's own success closes it: a
     * straggler's ends neither the hold nor the trial in flight.
     */
    fun recordSuccess(endpoint: String, admission: Admission.Allowed) {
        if (admission.generation != generation) return
        val state = states[endpoint] ?: return
        if (state.openUntil != null && state.trial !== admission) return
        states.remove(endpoint)
    }

    /** A failed call to [endpoint], by the call [admission] admitted; ignored across a [reset]. */
    fun recordFailure(endpoint: String, error: DomainError, admission: Admission.Allowed) {
        if (admission.generation != generation) return
        val state = states.getOrPut(endpoint) { State() }
        state.lastError = error
        val now = monotonicMillis()
        val busyUntil = (error as? DomainError.Server.Busy)?.let { now + busyHoldMillis(it) }
        if (state.trial === admission) {
            // The trial's own failure: a full new period — the server's, when it asked for longer —
            // and never shorter than a hold already running, such as a straggler's longer 429.
            state.trial = null
            val reopened = busyUntil ?: (now + openMillis)
            state.openUntil = maxOf(reopened, state.openUntil ?: reopened)
            return
        }
        // Any other call, the trial's straggling predecessors included: a count, never the trial.
        state.consecutive += 1
        val opened = state.openUntil
        state.openUntil = when {
            // The server asked for quiet: open now, and never shorten a hold already running.
            busyUntil != null -> maxOf(opened ?: busyUntil, busyUntil)
            opened == null && state.consecutive >= failureThreshold -> now + openMillis
            else -> opened
        }
    }

    /** How long a 429 holds the endpoint: the period, or the server's longer `Retry-After` up to [LIBRARY_BUSY_CAP]. */
    private fun busyHoldMillis(busy: DomainError.Server.Busy): Long {
        val asked = busy.retryAfter?.inWholeMilliseconds ?: 0
        return maxOf(openMillis, asked.coerceIn(0, LIBRARY_BUSY_CAP.inWholeMilliseconds))
    }

    /**
     * A call that ended without an answer (cancelled). When [admission] is the trial in flight its
     * slot is given back and the period stands; any other call held no slot and changes nothing.
     */
    fun abandon(endpoint: String, admission: Admission.Allowed) {
        if (admission.generation != generation) return
        val state = states[endpoint] ?: return
        if (state.trial === admission) state.trial = null
    }

    /**
     * The end of one open period from now, in the window [admission] was handed out in: for a
     * memory of one call's outcome that should last as long as an opening would and be forgotten
     * by a [reset] (the lyrics timeout of §18.4 residual 1). An admission from before a reset gives
     * a mark that is never current.
     */
    fun periodMark(admission: Admission.Allowed): PeriodMark =
        PeriodMark(admission.generation, monotonicMillis() + openMillis)

    /** Whether [mark] is still inside its period, and no [reset] has run since it was made. */
    fun isCurrent(mark: PeriodMark): Boolean = mark.generation == generation && monotonicMillis() < mark.untilMillis

    /** See [periodMark]. A monotonic time: never persisted (§18.8). */
    data class PeriodMark(val generation: Long, val untilMillis: Long)

    /**
     * Forgets every endpoint's history: a new observation window (a reconnect). The calls still in
     * flight belong to the old window, and their outcomes are ignored when they land.
     */
    fun reset() {
        states.clear()
        generation += 1
    }

    /**
     * Whether [admit] would refuse an automatic call to [endpoint] now. A pure question: it never
     * takes the trial.
     */
    fun isOpen(endpoint: String): Boolean {
        val state = states[endpoint] ?: return false
        val openUntil = state.openUntil ?: return false
        return state.trial != null || monotonicMillis() < openUntil
    }
}

private fun monotonicMillisSinceCreation(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
