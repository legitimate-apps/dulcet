package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.RatingConformanceContract
import com.legitimateapps.dulcet.core.RatingConformanceRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * CONF-84's rating half against the disposable reference server (spec §16.20, §18.3): the PRODUCTION
 * session rates a track, and the server's own `userRating` is read back raw after every write.
 */
class RatingConformanceTest {
    @Test
    fun conf84ARatingShowsBeforeItsSendReachesTheServerCompactsAndZeroRemovesIt() = runTest(timeout = 5.minutes) {
        val result = RatingConformanceContract.roundTrip(request())
        println(
            "CONF-84 rating OBSERVED before=${result.serverRatingBefore} " +
                "rate=${result.rate.serverRating} compacted=${result.compacted.sends}->${result.compacted.serverRating} " +
                "cleared=${result.cleared.serverRating}",
        )
        assertEquals(0, result.serverRatingBefore, "control: the run starts from no rating: $result")

        // Rate 4: in the next publication before any send, one send, adopted, held by the server.
        with(result.rate) {
            assertEquals(4, firstPublicationAfterChange, "CONF-84: the rating is in the next publication: $this")
            assertEquals(0, sendsBeforeFirstPublication, "CONF-84: the publication came before the send: $this")
            assertEquals("Saved", outcome, "CONF-84: $this")
            assertEquals(4, savedValue, "CONF-84: the acknowledged value is the one set: $this")
            assertEquals(listOf("4"), sends, "CONF-84: one setRating of 4: $this")
            assertEquals(4, serverRating, "CONF-84: the server holds the rating: $this")
            assertEquals(4, publishedAfterOutcome, "CONF-84: the adopted value is what shows: $this")
        }

        // Offline, 5 then 2: nothing sent offline, one send of 2 on reconnect.
        assertEquals(0, result.offlineSends, "CONF-84: nothing is sent while offline: $result")
        with(result.compacted) {
            assertEquals(5, firstPublicationAfterChange, "CONF-84: the offline change shows at once: $this")
            assertEquals(0, sendsBeforeFirstPublication, "CONF-84: $this")
            assertEquals("Saved", outcome, "CONF-84: $this")
            assertEquals(listOf("2"), sends, "CONF-84: compaction sends only the last value: $this")
            assertEquals(2, serverRating, "CONF-84: the server holds the last value: $this")
            assertEquals(2, publishedAfterOutcome, "CONF-84: $this")
        }

        // 0 removes the rating, on the server too.
        with(result.cleared) {
            assertEquals(0, firstPublicationAfterChange, "CONF-84: the removal shows at once: $this")
            assertEquals(0, sendsBeforeFirstPublication, "CONF-84: $this")
            assertEquals("Saved", outcome, "CONF-84: $this")
            assertEquals(listOf("0"), sends, "CONF-84: one setRating of 0: $this")
            assertEquals(0, serverRating, "CONF-84: the server no longer holds a rating: $this")
            assertEquals(0, publishedAfterOutcome, "CONF-84: $this")
        }
    }

    private fun request() = RatingConformanceRequest(
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
