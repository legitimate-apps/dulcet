package com.legitimateapps.dulcet.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * When the §10.4 breaker is reset (§28 revision 104 item 33). The reader's one offline-to-online
 * transition is a reconnect's, made once its epoch read succeeds (§16.14), and that is the only
 * reset. A reachability report only requests the reconnect: if that reconnect fails, the reader
 * stays offline and the breaker stays as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderReconnectBreakerTest {
    private fun status500() = LibraryEndpointResponse(500, "<html>no</html>", "http://fixture.invalid/rest")

    @Test
    fun aReconnectThatFailsResetsNothingAndTheOneThatSucceedsResetsTheBreaker() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        advanceUntilIdle()
        val breaker = session.reader.breaker
        repeat(3) {
            val admission = assertIs<EndpointCircuitBreaker.Admission.Allowed>(breaker.admit("getLyricsBySongId"))
            breaker.recordFailure("getLyricsBySongId", DomainError.Transport.Unreachable, admission)
        }
        assertTrue(breaker.isOpen("getLyricsBySongId"), "fixture: three failures open it")
        session.setOnline(false)
        advanceUntilIdle()

        // Reachable again, and the reconnect that report requests fails at its epoch read.
        env.server.answerInstead = { endpoint -> if (endpoint == "getScanStatus") status500() else null }
        val before = env.server.log.size
        session.setOnline(true)
        advanceUntilIdle()
        assertTrue(
            env.server.log.drop(before).any { it.endpoint == "getScanStatus" },
            "fixture: the report's reconnect read the epoch",
        )
        assertFalse(session.reader.online, "fixture: that reconnect failed")
        assertTrue(breaker.isOpen("getLyricsBySongId"), "a reconnect that failed reset the breaker")

        env.server.answerInstead = { null }
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertFalse(breaker.isOpen("getLyricsBySongId"), "the reconnect that succeeded did not reset it")
    }
}
