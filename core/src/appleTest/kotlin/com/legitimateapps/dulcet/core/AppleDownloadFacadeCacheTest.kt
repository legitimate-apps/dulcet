package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableData
import platform.Foundation.NSUUID
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The exported bridge must supply local-file validation metadata as well as the original bytes. */
@OptIn(ExperimentalForeignApi::class)
class AppleDownloadFacadeCacheTest {
    @Test
    fun knownReplacementRejectsLateCachePromotionAndKeepsExistingLocalBytes() = fixture { client ->
        val body = waveAudio()
        fun write(rawId: String): String {
            val id = assertNotNull(client.beginStreamingCache(rawId, AudioContainer.Wav, body.size.toLong(), 1, 0, 1, 1))
            assertTrue(client.appendStreamingCache(id, body.toData(), 0))
            return id
        }
        val retained = write("retained")
        assertTrue(client.finishStreamingCache(retained, body.size.toLong(), 2))
        val late = write("late")
        client.rejectServerIdentity()
        assertFalse(client.appendStreamingCache(late, body.toData(), 0))
        assertFalse(client.finishStreamingCache(late, body.size.toLong(), 3))
        assertNull(client.localPlaybackPlan("late").plan)
        val existing = assertNotNull(client.localPlaybackPlan("retained").plan)
        assertContentEquals(body, FileSystem.SYSTEM.read(existing.filePath.toPath()) { readByteArray() })
    }

    @Test
    fun aSuspendedSuccessfulIdentityProbeCannotUndoAReplacementRejection() = fixture { client ->
        runBlocking {
            val answer = CompletableDeferred<Unit>()
            var probes = 0
            client.identityValidator = { probes++; answer.await(); null }
            val old = async(start = CoroutineStart.UNDISPATCHED) { client.validateServerIdentity() }
            client.rejectServerIdentity()
            answer.complete(Unit)
            assertEquals(DomainError.Protocol.ServerIdentityChanged, old.await())
            assertEquals(DomainError.Protocol.ServerIdentityChanged, client.validateServerIdentity())
            assertEquals(1, probes, "known rejection never implicitly reopens after a later matching answer")
        }
    }

    @Test
    fun savedMetadataOnlySeedsEmptyIdentityAndCannotOverwriteLearnedInstance() {
        val name = "dulcet-apple-identity-seed-${NSUUID().UUIDString}.db"
        val locator = DulcetDriverFactory(name).openDulcetDatabase()
        val path = locator.driver.executeQuery(null, "PRAGMA database_list", { cursor ->
            check(cursor.next().value); QueryResult.Value(requireNotNull(cursor.getString(2)))
        }, 0).value
        locator.close()
        try {
            val learned = ServerIdentityPolicy.identity("dulcet", "learned-instance")
            val metadata = ServerIdentityPolicy.identity("dulcet")
            assertTrue(AppleMediaBindingClient.seedServerIdentity(name, "server", learned))
            assertTrue(AppleMediaBindingClient.seedServerIdentity(name, "server", metadata))
            assertEquals(learned, AppleMediaBindingClient.acceptedServerIdentity(name, "server"))
        } finally {
            listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(path + it, null) }
        }
    }

    @Test
    fun streamedAudioPromotesThroughFacadeAndPlaysFromItsExactBytesWithoutNetworkAccess() = fixture { client ->
        val body = waveAudio()
        val write = assertNotNull(client.beginStreamingCache(
            rawId = "cached-wave", container = AudioContainer.Wav, totalLength = body.size.toLong(),
            durationMilliseconds = 1, rangeStart = 32, credentialGeneration = 1, wallClockMilliseconds = 1,
        ))
        assertTrue(client.appendStreamingCache(write, body.copyOfRange(32, body.size).toData(), 32))
        assertFalse(client.isStreamingCacheWriteComplete(write))
        assertTrue(client.appendStreamingCache(write, body.copyOfRange(0, 32).toData(), 0))
        assertTrue(client.isStreamingCacheWriteComplete(write))
        // Regression: the bridge formerly passed a missing content type, rejecting valid bytes.
        assertTrue(client.finishStreamingCache(write, body.size.toLong(), 2))
        client.closeNetworkAccess()
        val outcome = client.localPlaybackPlan("cached-wave")
        assertNull(outcome.errorKind)
        assertNull(outcome.unavailableKind)
        val plan = assertNotNull(outcome.plan)
        assertEquals("Wav", plan.expectedContainer)
        assertEquals(body.size.toLong(), plan.exactByteLength)
        assertContentEquals(body, FileSystem.SYSTEM.read(plan.filePath.toPath()) { readByteArray() })
        val storage = client.offlineLibraryStorage()
        assertNull(storage.errorKind)
        assertEquals(body.size.toLong(), storage.cacheBytes)
        assertEquals(0, storage.downloadBytes)
    }

    @Test
    fun completeNonAudioBodyIsRefusedByFacadeAndNeverBecomesLocallyPlayable() = fixture { client ->
        val body = "<html>an upstream error is not audio</html>".encodeToByteArray()
        val write = assertNotNull(client.beginStreamingCache(
            rawId = "invalid-wave", container = AudioContainer.Wav, totalLength = body.size.toLong(),
            durationMilliseconds = 1, rangeStart = 0, credentialGeneration = 1, wallClockMilliseconds = 1,
        ))
        assertTrue(client.appendStreamingCache(write, body.toData(), 0))
        assertTrue(client.isStreamingCacheWriteComplete(write))
        assertFalse(client.finishStreamingCache(write, body.size.toLong(), 2))
        client.closeNetworkAccess()
        val outcome = client.localPlaybackPlan("invalid-wave")
        assertNull(outcome.plan)
        assertNull(outcome.errorKind)
        assertEquals("notDownloaded", outcome.unavailableKind)
        assertEquals(0, client.offlineLibraryStorage().cacheBytes)
        assertFalse(client.isStreamingCacheWriteComplete(write))
    }

    private fun fixture(block: (AppleDownloadClient) -> Unit) {
        val name = "dulcet-apple-cache-bridge-${NSUUID().UUIDString}.db"
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-apple-cache-bridge-${NSUUID().UUIDString}"
        val locator = DulcetDriverFactory(name).openDulcetDatabase()
        val databasePath = locator.driver.executeQuery(null, "PRAGMA database_list", { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getString(2)))
        }, 0).value
        locator.close()
        val client = AppleDownloadClient(
            PlaybackEndpointAccount("provider:cache-bridge", "https://music.invalid", "fixture", "fixture", false),
            name, root.toString(),
        )
        try {
            assertNull(client.reconcile(emptyList(), credentialGeneration = 1).errorKind)
            block(client)
        } finally {
            client.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
            listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(databasePath + it, null) }
        }
    }

    private fun ByteArray.toData() = usePinned { pinned ->
        NSMutableData().also { data ->
            data.setLength(size.toULong())
            memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong())
        }
    }

    /** A complete PCM WAV: eight mono 16-bit samples at 8 kHz. */
    private fun waveAudio(): ByteArray = Buffer().apply {
        writeUtf8("RIFF"); writeIntLe(52); writeUtf8("WAVEfmt "); writeIntLe(16)
        writeShortLe(1); writeShortLe(1); writeIntLe(8_000); writeIntLe(16_000)
        writeShortLe(2); writeShortLe(16); writeUtf8("data"); writeIntLe(16)
        write(ByteArray(16))
    }.readByteArray()
}
