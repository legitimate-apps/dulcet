package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.StarConformanceContract
import com.legitimateapps.dulcet.core.StarConformanceRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * CONF-84's star half against the disposable reference server (spec §16.20, §18.3), beside
 * [RatingConformanceTest]'s rating half: the PRODUCTION session stars a track, a re-read of the
 * screen lands while the `star` send is held before the server, and the server's own record is read
 * back raw.
 */
class StarConformanceTest {
    @Test
    fun conf84AStarShowsWithTheTapSurvivesARevalidationBeforeItsSendAndTheEchoIsAdopted() = runTest(timeout = 5.minutes) {
        val result = StarConformanceContract.roundTrip(request())
        println(
            "CONF-84 star OBSERVED before=${result.serverStarredBefore} first=${result.firstPublicationAfterTap} " +
                "held=${result.sendHeld} rereads=${result.revalidationReads} serverDuringHold=${result.serverStarredDuringHold} " +
                "frames=${result.starredFromTapToOutcome} outcome=${result.outcome} sends=${result.sends} " +
                "serverAfter=${result.serverStarredAfter} pending=${result.pendingAfterOutcome} adopted=${result.adoptedStarred} " +
                "reread=${result.publishedAfterReread}",
        )
        assertEquals(false, result.serverStarredBefore, "control: the run starts from no star: $result")

        // The star is in the next publication, before any request.
        assertEquals(true, result.firstPublicationAfterTap, "CONF-84: the star is in the next publication: $result")
        assertEquals(0, result.requestsBeforeFirstPublication, "CONF-84: the publication came before any request: $result")

        // A revalidation that lands before the send completes does not remove it. The fixture lines
        // prove the condition was met: the send was held, the re-read reached the server, the server
        // said not starred, and a live frame was delivered meanwhile.
        assertTrue(result.sendHeld, "fixture: the star send reached the transport and was held: $result")
        assertTrue(result.revalidationReads >= 1, "fixture: the re-read reached the server while the send was held: $result")
        assertEquals(false, result.serverStarredDuringHold, "fixture: the server answered that re-read with no star: $result")
        assertTrue(result.livePublicationDuringHold, "fixture: a live frame was delivered while the send was held: $result")
        assertTrue(result.starredFromTapToOutcome.isNotEmpty(), "fixture: frames were delivered: $result")
        assertTrue(
            result.starredFromTapToOutcome.all { it == true },
            "CONF-84: no frame from the tap to the outcome shows the star off: $result",
        )

        // One send, saved, held by the server; the echo is adopted with nothing left pending.
        assertEquals(listOf("star"), result.sends, "CONF-84: one star request: $result")
        assertEquals("Saved", result.outcome, "CONF-84: $result")
        assertEquals(1, result.savedValue, "CONF-84: the acknowledged value is the star: $result")
        assertEquals(true, result.serverStarredAfter, "CONF-84: the server holds the star: $result")
        assertEquals(0L, result.pendingAfterOutcome, "CONF-84: the acknowledged row is removed: $result")
        assertEquals(true, result.adoptedStarred, "CONF-84: the echoed value is adopted: $result")
        assertEquals(true, result.publishedAfterReread, "CONF-84: a fresh re-read still shows the star: $result")
    }

    private fun request() = StarConformanceRequest(
        normalizedBaseUrl = disposableConformanceBaseUrl(),
        username = environmentOrNull("DULCET_CONFORMANCE_USERNAME") ?: ADMIN_USER,
        password = environmentOrNull("DULCET_CONFORMANCE_PASSWORD") ?: ADMIN_PASSWORD,
        allowLocalHttp = true,
    )

    private companion object {
        // The disposable fixture's published constants (tools/conformance-env/health-check).
        const val ADMIN_USER = "dulcet-admin"
        const val ADMIN_PASSWORD = "dulcet-ci-canary-password"
    }
}
