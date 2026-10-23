package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.*

/** Independent acceptance for remote-contact publication ordering across asynchronous probes. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidServerIdentityRaceTest {
    @Test fun validationWaitsForEveryProbeAndCancellationOrFailureReleasesTheWaiter() = runTest {
        for (throws in listOf(false, true)) {
            val id = UUID.randomUUID().toString()
            val release = CompletableDeferred<Unit>()
            val cancelled = async { AndroidServerIdentity.validate(id) { kotlinx.coroutines.awaitCancellation() } }
            val probe = async {
                runCatching { AndroidServerIdentity.validate(id) {
                    release.await()
                    if (throws) error("identity fixture failure") else null
                } }
            }
            runCurrent()
            val waiter = async { AndroidServerIdentity.awaitValidation(id) }
            runCurrent()
            assertFalse(waiter.isCompleted)
            cancelled.cancel()
            runCurrent()
            assertFalse(waiter.isCompleted, "One cancelled probe cannot release another active probe")
            release.complete(Unit)
            probe.await()
            waiter.await()
            assertEquals(!throws, AndroidServerIdentity.isVerified(id))
        }
    }

    @Test fun establishedContactStaysOpenDuringBackgroundValidationButClosesOnFailure() = runTest {
        for (failure in listOf("success", "transport", "throw", "swap")) {
            val id = UUID.randomUUID().toString()
            assertNull(AndroidServerIdentity.validate(id) { null })
            val acceptedAuthorization = AndroidServerIdentity.authorizationEpoch(id)
            val release = CompletableDeferred<Unit>()
            val probe = async { runCatching { AndroidServerIdentity.validate(id) {
                release.await()
                when (failure) {
                    "transport" -> DomainError.Transport.Cancelled
                    "throw" -> error("identity fixture failure")
                    "swap" -> DomainError.Protocol.ServerIdentityChanged
                    else -> null
                }
            } } }
            runCurrent()
            assertTrue(AndroidServerIdentity.isVerified(id),
                "Starting an independent background probe cannot revoke already established contact")
            release.complete(Unit)
            probe.await()
            assertEquals(failure == "success", AndroidServerIdentity.isVerified(id))
            if (failure == "success") assertEquals(acceptedAuthorization, AndroidServerIdentity.authorizationEpoch(id),
                "An ordinary matching probe must not retire accepted work")
            if (failure == "swap") {
                AndroidServerIdentity.validate(id) { null }
                assertTrue(AndroidServerIdentity.isVerified(id), "A fresh saved-identity negotiation reopens contact")
            }
        }
    }

    @Test fun aLateSuccessfulProbeCannotReopenContactAfterANewerSwapDetection() = runTest {
        val id = UUID.randomUUID().toString()
        val releaseOld = CompletableDeferred<Unit>()
        val old = async { AndroidServerIdentity.validate(id) { releaseOld.await(); null } }
        runCurrent()
        assertEquals(DomainError.Protocol.ServerIdentityChanged,
            AndroidServerIdentity.validate(id) { DomainError.Protocol.ServerIdentityChanged })
        assertFalse(AndroidServerIdentity.isVerified(id))
        releaseOld.complete(Unit)
        old.await()
        assertFalse(AndroidServerIdentity.isVerified(id), "late success must not restore old remote IDs")
        AndroidServerIdentity.validate(id) { null }
        assertTrue(AndroidServerIdentity.isVerified(id), "A fresh saved-identity negotiation reopens contact")
    }

    @Test fun reconnectRecoveryCannotAdoptAReadStartedBeforeRejection() = readerTest { env ->
        val id = UUID.randomUUID().toString()
        var failure: DomainError? = null
        val reader = env.reader(
            identityAuthorization = { AndroidServerIdentity.authorizationEpoch(id) },
            validateServerIdentity = { AndroidServerIdentity.validate(id) { failure } },
        )
        reader.connect()
        val originalAuthorization = AndroidServerIdentity.authorizationEpoch(id)
        env.server.holdMatching = { it.endpoint == "getArtists" }
        val oldReply = async { runCatching { reader.sendChecked("getArtists") } }
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount)
        failure = DomainError.Protocol.ServerIdentityChanged
        assertIs<ReaderConnectionOutcome.Failed>(reader.reconnect())
        assertNull(AndroidServerIdentity.authorizationEpoch(id))
        failure = null
        assertIs<ReaderConnectionOutcome.Read>(reader.reconnect(), "Saved-identity Reconnect must recover")
        assertNotEquals(originalAuthorization, AndroidServerIdentity.authorizationEpoch(id),
            "Reopening cannot restore the authorization captured before rejection")
        env.server.release()
        assertIs<ReaderSendRefused>(oldReply.await().exceptionOrNull(),
            "A recovered account must still discard a reply crossing its rejection")
    }

    @Test fun olderNegotiationsCannotRevokeOrReopenTheRecoveredAuthorization() = runTest {
        for (oldResult in listOf(null, DomainError.Protocol.ServerIdentityChanged)) {
            val id = UUID.randomUUID().toString()
            AndroidServerIdentity.validate(id) { null }
            val originalAuthorization = AndroidServerIdentity.authorizationEpoch(id)
            val release = CompletableDeferred<Unit>()
            val oldProbe = async { AndroidServerIdentity.validate(id) { release.await(); oldResult } }
            runCurrent()
            AndroidServerIdentity.validate(id) { DomainError.Protocol.ServerIdentityChanged }
            assertNull(AndroidServerIdentity.authorizationEpoch(id))
            assertNull(AndroidServerIdentity.validate(id) { null }, "The current saved-identity negotiation recovers")
            val recoveredAuthorization = AndroidServerIdentity.authorizationEpoch(id)
            assertNotNull(recoveredAuthorization)
            assertNotEquals(originalAuthorization, recoveredAuthorization)
            release.complete(Unit)
            assertEquals(DomainError.Transport.Cancelled, oldProbe.await(), "An older negotiation is never adopted")
            assertTrue(AndroidServerIdentity.isVerified(id))
            assertEquals(recoveredAuthorization, AndroidServerIdentity.authorizationEpoch(id))
        }
    }

    @Test fun invalidatingAProbeBeforeItCompletesLeavesContactClosedAndOtherAccountsVerified() = runTest {
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        AndroidServerIdentity.validate(other) { null }
        assertTrue(AndroidServerIdentity.isVerified(other))
        val release = CompletableDeferred<Unit>()
        val probe = async { AndroidServerIdentity.validate(id) { release.await(); null } }
        runCurrent()
        AndroidServerIdentity.requireValidation(id)
        release.complete(Unit)
        probe.await()
        assertFalse(AndroidServerIdentity.isVerified(id))
        assertTrue(AndroidServerIdentity.isVerified(other), "independent server verification cannot be lost")
        AndroidServerIdentity.validate(id) { null }
        assertTrue(AndroidServerIdentity.isVerified(id))
    }
}
