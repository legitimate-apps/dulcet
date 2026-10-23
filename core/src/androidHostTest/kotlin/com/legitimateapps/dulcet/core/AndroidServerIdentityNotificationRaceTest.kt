package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.*

/** Gate-to-reader delivery races, through the registered Android reader and durable mutation outbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidServerIdentityNotificationRaceTest {
    @Test fun aDelayedRejectionCannotCloseANewerReconnectOrStrandItsChanges() = exercise(consent = false, delayed = true)
    @Test fun aDelayedRejectionCannotCloseANewerConsentOrStrandItsChanges() = exercise(consent = true, delayed = true)
    @Test fun reconnectAfterDeliveredRejectionReopensWithANewReplyFence() = exercise(consent = false, delayed = false)
    @Test fun consentAfterDeliveredRejectionReopensWithANewReplyFence() = exercise(consent = true, delayed = false)

    private fun exercise(consent: Boolean, delayed: Boolean) = runBlocking {
        withTimeout(30_000) {
            val id = "provider:notification:${UUID.randomUUID()}"
            val account = AndroidLibraryReaderAccount(id, "https://fixture.invalid", "user", "password", false)
            val driver = createTestDriver()
            val database = DulcetDatabaseStore.open(driver)
            val server = SessionTestServer()
            val held = CompletableDeferred<Unit>()
            val releaseReply = CompletableDeferred<Unit>()
            val transport = object : LibraryEndpointTransport {
                override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
                    val reply = server.request(endpoint, parameters)
                    if (endpoint == "getArtists") { held.complete(Unit); releaseReply.await() }
                    return reply
                }
            }
            val sessionReady = CompletableDeferred<LibraryReaderSession>()
            val scopeReady = CompletableDeferred<kotlinx.coroutines.CoroutineScope>()
            val reader = AndroidLibraryReader.obtain(account) { previous ->
                AndroidLibraryReader(account, { scope, foreground ->
                    val session = LibraryReaderSession(database.database,
                        SeenCacheStore(database, ManualWallClock(2_000_000)).bind(CacheBinding(id, account.normalizedBaseUrl, account.username)),
                        transport, scope, formPost = false, foreground = foreground,
                        identityAuthorization = { AndroidServerIdentity.authorizationEpoch(id) },
                        validateServerIdentity = { AndroidServerIdentity.validate(id) { null } },
                    )
                    scopeReady.complete(scope)
                    sessionReady.complete(session)
                    AndroidLibraryReaderComposition(session)
                }, newLibraryReaderDispatcher(), Dispatchers.Unconfined, previous, initiallyForeground = false)
            }
            val releaseNotification = CompletableDeferred<Unit>()
            var oldProbe: kotlinx.coroutines.Deferred<DomainError?>? = null
            try {
                val session = sessionReady.await()
                val scope = scopeReady.await()
                suspend fun <T> onReader(block: () -> T): T {
                    val answer = CompletableDeferred<T>()
                    reader.onReader { answer.complete(block()) }
                    return answer.await()
                }
                suspend fun reconnect(): AndroidLibraryConnection {
                    val answer = CompletableDeferred<AndroidLibraryConnection>()
                    reader.reconnect { answer.complete(it) }
                    return answer.await()
                }
                suspend fun pending(): Long? {
                    val answer = CompletableDeferred<Long?>()
                    reader.pendingChangeCount { answer.complete(it) }
                    return answer.await()
                }
                suspend fun awaitFlushed() {
                    repeat(1_000) {
                        if (pending() == 0L) return
                        kotlinx.coroutines.delay(5)
                    }
                    fail("The accepted account must flush its pending favourite")
                }
                assertTrue(reconnect().readerOnline)
                val originalEpoch = AndroidServerIdentity.authorizationEpoch(id)
                val lateReply = CompletableDeferred<Throwable?>()
                reader.onReader {
                    scope.launch {
                        lateReply.complete(runCatching { session.reader.sendChecked("getArtists") }.exceptionOrNull())
                    }
                }
                held.await()
                val decided = CompletableDeferred<Unit>()
                oldProbe = async(Dispatchers.Default) {
                    AndroidServerIdentity.validate(id, beforeRejectedNotification = {
                        decided.complete(Unit)
                        releaseNotification.await()
                    }) { DomainError.Protocol.ServerIdentityChanged }
                }
                decided.await()
                assertNull(AndroidServerIdentity.authorizationEpoch(id), "P1 has actually rejected the account")
                assertTrue(reader.setFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "queued"), true))
                assertEquals(1L, pending())
                assertEquals(0, onReader { server.count("star") })
                if (!delayed) {
                    releaseNotification.complete(Unit)
                    oldProbe.await()
                    onReader { Unit } // drain delivery before acceptance
                    assertFalse(reader.serverAvailableForPlayback)
                }
                if (consent) AndroidServerIdentity.accept(RuntimeEnvironment.getApplication(), id,
                    ServerIdentityPolicy.identity("navidrome", "accepted-instance"))
                assertTrue(reconnect().readerOnline, "The newer saved-identity reconnect must reopen the account")
                awaitFlushed()
                val recoveredEpoch = AndroidServerIdentity.authorizationEpoch(id)
                assertNotNull(recoveredEpoch)
                assertNotEquals(originalEpoch, recoveredEpoch)
                releaseNotification.complete(Unit)
                oldProbe.await()
                onReader { Unit } // drain the stale notification before testing the reopened account
                assertTrue(reader.serverAvailableForPlayback, "A stale rejection notification cannot close the reopened account")
                assertTrue(reader.setFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "after-delivery"), true))
                awaitFlushed()
                assertEquals(setOf("queued", "after-delivery"), onReader { server.starredIds.toSet() })
                releaseReply.complete(Unit)
                assertIs<ReaderSendRefused>(lateReply.await(), "Reopening must still discard a reply crossing rejection")
                assertEquals(recoveredEpoch, AndroidServerIdentity.authorizationEpoch(id))
            } finally {
                releaseNotification.complete(Unit)
                releaseReply.complete(Unit)
                oldProbe?.cancel()
                reader.close()
                reader.awaitTermination()
                driver.close()
            }
        }
    }
}
