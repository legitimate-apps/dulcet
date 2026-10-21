package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.*

/** Independent acceptance for remote-contact publication ordering across asynchronous probes. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidServerIdentityRaceTest {
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
        assertFalse(AndroidServerIdentity.isVerified(id), "mismatch requires explicit account acceptance")
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
