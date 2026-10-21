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
import kotlin.test.*

/** Exercises the exported native adapter against the actual database and promoted media files. */
@OptIn(ExperimentalForeignApi::class)
class AppleOfflineMediaBindingSafetyTest {
    @Test fun changedUsernameCannotPlayThePreviousUsersCachedOrExplicitBytes() = rebind("https://music.invalid", "bob")
    @Test fun changedEndpointCannotPlayThePreviousServersCachedOrExplicitBytes() = rebind("https://other.invalid", "alice")
    private fun rebind(url: String, user: String) = fixture { f ->
        val previous = f.client()
        val cached = f.cache(previous)
        val explicit = f.explicit()
        val replacement = f.client(url, user)
        for (raw in listOf("cached", "explicit")) {
            val result = replacement.localPlaybackPlan(raw)
            assertNull(result.errorKind, "a valid replacement adapter must answer its local lookup")
            assertNull(result.plan, "same raw id in the replacement account must not select previous binding bytes")
            assertEquals("notDownloaded", result.unavailableKind)
        }
        assertFalse(FileSystem.SYSTEM.exists(cached.toPath()))
        assertFalse(FileSystem.SYSTEM.exists(explicit.toPath()))
        assertNull(previous.localPlaybackPlan("cached").plan, "the prior exported adapter must stay revoked")
        assertNull(previous.beginStreamingCache("late", AudioContainer.Wav, AUDIO.size.toLong(), 1, 0, 1, 3),
            "a late old-binding stream cannot republish after rebind")
    }

    @Test fun passwordOnlyRefreshKeepsTheSameBindingsExactOfflineBytes() = fixture { f ->
        val first = f.client()
        val path = f.cache(first)
        val refreshed = f.client(password = "replacement-password")
        refreshed.closeNetworkAccess()
        val result = refreshed.localPlaybackPlan("cached")
        assertNull(result.errorKind)
        assertEquals(path, assertNotNull(result.plan).filePath)
        assertContentEquals(AUDIO, FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
    }

    @Test fun dnsCaseKeepsExplicitBytesThroughTheExportedAdapter() = equivalentUrl("https://MUSIC.invalid", "https://music.invalid")
    @Test fun defaultHttpsPortKeepsExplicitBytesThroughTheExportedAdapter() = equivalentUrl("https://music.invalid:443/", "https://music.invalid")
    @Test fun defaultHttpPortKeepsExplicitBytesThroughTheExportedAdapter() = equivalentUrl("http://music.invalid:80/base/", "http://MUSIC.invalid/base")
    @Test fun punycodeCaseKeepsExplicitBytesThroughTheExportedAdapter() = equivalentUrl("https://XN--BCHER-KVA.invalid", "https://xn--bcher-kva.invalid/")
    @Test fun equivalentIpv6KeepsExplicitBytesThroughTheExportedAdapter() = equivalentUrl("https://[2001:0DB8:0:0:0:0:0:1]:443/base/", "https://[2001:db8::1]/base")
    @Test fun percentTripletHexCaseKeepsExplicitBytesThroughTheExportedAdapter() = equivalentUrl("https://music.invalid/music%2fbase", "https://music.invalid/music%2Fbase")

    private fun equivalentUrl(before: String, after: String) = fixture { f ->
            val original = f.client(before)
            val path = f.explicit(before)
            val next = f.client(after, password = "new-password")
            next.closeNetworkAccess()
            assertEquals(path, assertNotNull(next.localPlaybackPlan("explicit").plan, "$before -> $after").filePath)
            assertContentEquals(AUDIO, FileSystem.SYSTEM.read(path.toPath()) { readByteArray() })
            assertNotNull(original.localPlaybackPlan("explicit").plan, "equivalent refresh must keep the old epoch valid")
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val name = "dulcet-apple-media-owner-${NSUUID().UUIDString}.db"
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-apple-media-owner-${NSUUID().UUIDString}"
        val store = DulcetDriverFactory(name).openDulcetDatabase()
        val path = store.driver.executeQuery(null, "PRAGMA database_list", { cursor ->
            check(cursor.next().value); QueryResult.Value(requireNotNull(cursor.getString(2)))
        }, 0).value
        val f = Fixture(name, root.toString(), store)
        try { block(f) }
        finally {
            f.clients.forEach { it.close() }; store.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
            listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(path + it, null) }
        }
    }
    private class Fixture(val name: String, val root: String, val store: DulcetDatabaseStore) {
        val clients = mutableListOf<AppleDownloadClient>()
        fun client(url: String = "https://music.invalid", user: String = "alice", password: String = "fixture") =
            AppleDownloadClient(PlaybackEndpointAccount("provider:apple-owner", url, user, password, false), name, root).also {
                clients += it; assertNull(it.reconcile(emptyList(), 1).errorKind)
            }
        fun cache(client: AppleDownloadClient): String {
            val write = assertNotNull(client.beginStreamingCache("cached", AudioContainer.Wav, AUDIO.size.toLong(), 1, 0, 1, 1))
            assertTrue(client.appendStreamingCache(write, AUDIO.toData(), 0))
            assertTrue(client.finishStreamingCache(write, AUDIO.size.toLong(), 2))
            return assertNotNull(client.localPlaybackPlan("cached").plan).filePath
        }
        fun explicit(url: String = "https://music.invalid"): String {
            val engine = DownloadPolicyEngine(store.database, DownloadFileStore(root, FileSystem.SYSTEM))
            engine.bindMedia(DownloadMediaBinding("provider:apple-owner", url, "alice"))
            engine.reconcile(emptyList(), mapOf("provider:apple-owner" to 1L))
            val row = engine.enqueue(DownloadRequest(DownloadIdentity("provider:apple-owner", "explicit", "original"), AudioContainer.Wav,
                PlaybackContentLength.Exact(AUDIO.size.toLong()), DownloadServerSnapshot(1, AUDIO.size.toLong()), 1, 1))
            engine.writeCompletedTemporaryFile(row.downloadId, AUDIO)
            assertIs<DownloadPromotionResult.Promoted>(engine.promote(row.downloadId,
                DownloadResponseMetadata("audio/wav", PlaybackContentLength.Exact(AUDIO.size.toLong()))))
            return assertIs<OfflinePlaybackPlanResult.Available>(engine.offlinePlaybackPlan(row.identity)).plan.absolutePath
        }
    }
    private companion object {
        val AUDIO = Buffer().apply {
            writeUtf8("RIFF"); writeIntLe(52); writeUtf8("WAVEfmt "); writeIntLe(16)
            writeShortLe(1); writeShortLe(1); writeIntLe(8_000); writeIntLe(16_000)
            writeShortLe(2); writeShortLe(16); writeUtf8("data"); writeIntLe(16); write(ByteArray(16))
        }.readByteArray()
        fun ByteArray.toData() = usePinned { pinned -> NSMutableData().also {
            it.setLength(size.toULong()); memcpy(it.mutableBytes, pinned.addressOf(0), size.toULong())
        } }
    }
}
