package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidArtworkIdentityRejectionTest {
    @Test fun aHeldArtworkReplyAfterIdentityRejectionCannotReachTheCallerOrDisk() = runBlocking {
        withTimeout(10_000) {
            Fixture().use { f ->
                assertNull(AndroidServerIdentity.validate(f.account.providerInstanceId) { null })
                assertContentEquals(ORIGINAL, f.repository.load("kept", 256), "The real fetcher accepts the positive control")
                val before = f.imageFiles().associate { it.name to it.readBytes().toList() }
                val pending = async { f.repository.load("held", 256) }
                try {
                    assertEquals("held", f.started.await().getValue("id"))
                    assertEquals(DomainError.Protocol.ServerIdentityChanged,
                        AndroidServerIdentity.validate(f.account.providerInstanceId) { DomainError.Protocol.ServerIdentityChanged })
                    f.release.complete(REPLACEMENT)
                    val returned = pending.await()
                    assertEquals(before, f.imageFiles().associate { it.name to it.readBytes().toList() },
                        "A late replacement image must neither create nor overwrite the saved account's cache")
                    assertNull(returned, "Validated image bytes from a rejected server must not reach presentation")
                    assertContentEquals(ORIGINAL, f.repository.load("kept", 256, cachedOnly = true),
                        "Rejection preserves the old account's offline artwork")
                } finally { f.release.complete(REPLACEMENT) }
            }
        }
    }

    @Test fun aKnownIdentityRejectionKeepsCachedArtworkAndSendsNoCacheMiss() = runBlocking {
        Fixture().use { f ->
            assertContentEquals(ORIGINAL, f.repository.load("kept", 256))
            AndroidServerIdentity.validate(f.account.providerInstanceId) { DomainError.Protocol.ServerIdentityChanged }
            assertContentEquals(ORIGINAL, f.repository.load("kept", 256, cachedOnly = true))
            assertNull(f.repository.load("missing", 256), "A rejected account's cache miss cannot contact the replacement")
            assertEquals(1, f.requests.get(), "Only the pre-rejection positive-control request may be sent")
        }
    }

    @Test fun aHeldReplyFromTheStillAcceptedServerIsReturnedAndCached() = runBlocking {
        withTimeout(10_000) {
            Fixture().use { f ->
                assertNull(AndroidServerIdentity.validate(f.account.providerInstanceId) { null })
                val pending = async { f.repository.load("held", 256) }
                try {
                    f.started.await()
                    f.release.complete(ORIGINAL)
                    assertContentEquals(ORIGINAL, pending.await())
                    assertContentEquals(ORIGINAL, f.repository.load("held", 256, cachedOnly = true))
                    assertEquals(1, f.requests.get())
                    assertContentEquals(ORIGINAL, f.imageFiles().single().readBytes())
                } finally { f.release.complete(ORIGINAL) }
            }
        }
    }

    @Test fun explicitAcceptanceCannotReviveAnArtworkReplyFromTheRetiredAuthorization() = runBlocking {
        withTimeout(10_000) {
            Fixture().use { f ->
                val pending = async { f.repository.load("held", 256) }
                try {
                    f.started.await()
                    AndroidServerIdentity.validate(f.account.providerInstanceId) { DomainError.Protocol.ServerIdentityChanged }
                    AndroidServerIdentity.accept(RuntimeEnvironment.getApplication(), f.account.providerInstanceId,
                        ServerIdentityPolicy.identity("navidrome", "confirmed-instance"))
                    assertTrue(AndroidServerIdentity.isVerified(f.account.providerInstanceId), "Consent opens the new authorization")
                    f.release.complete(REPLACEMENT)
                    assertNull(pending.await(), "Consent cannot authorize a reply requested before rejection")
                    assertTrue(f.imageFiles().isEmpty(), "The retired reply cannot seed the confirmed account's cache")
                } finally { f.release.complete(REPLACEMENT) }
            }
        }
    }

    @Test fun aTransientBackgroundProbeDoesNotRetireAnAcceptedArtworkReply() = runBlocking {
        withTimeout(10_000) {
            Fixture().use { f ->
                AndroidServerIdentity.validate(f.account.providerInstanceId) { null }
                val pending = async { f.repository.load("held", 256) }
                try {
                    f.started.await()
                    AndroidServerIdentity.validate(f.account.providerInstanceId) { DomainError.Transport.Unreachable }
                    f.release.complete(ORIGINAL)
                    assertContentEquals(ORIGINAL, pending.await(), "A transient probe is not a server replacement")
                    assertContentEquals(ORIGINAL, f.repository.load("held", 256, cachedOnly = true))
                } finally { f.release.complete(ORIGINAL) }
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        val account = PlaybackEndpointAccount("provider:artwork-rejection:${UUID.randomUUID()}",
            "http://127.0.0.1:9", "artwork-user", "artwork-password", true)
        private val root = AndroidAccountData.artworkRootFor(context, account.providerInstanceId)
        val requests = AtomicInteger()
        val started = CompletableDeferred<Map<String, String>>()
        val release = CompletableDeferred<ByteArray>()
        val repository = AndroidArtworkRepository(context, account, ArtworkFetcher(ArtworkEndpointTransport { parameters ->
            requests.incrementAndGet()
            val bytes = if (parameters["id"] == "held") {
                started.complete(parameters)
                release.await()
            } else ORIGINAL
            ArtworkEndpointResponse(200, bytes, "<redacted-url>")
        }), 1_000_000)
        fun imageFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()
        override fun close() { release.complete(REPLACEMENT); root.deleteRecursively() }
    }

    private companion object {
        val ORIGINAL = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val REPLACEMENT = ORIGINAL + byteArrayOf(5, 6, 7, 8)
    }
}
