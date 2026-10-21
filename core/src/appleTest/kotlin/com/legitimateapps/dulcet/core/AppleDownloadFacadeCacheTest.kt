package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
