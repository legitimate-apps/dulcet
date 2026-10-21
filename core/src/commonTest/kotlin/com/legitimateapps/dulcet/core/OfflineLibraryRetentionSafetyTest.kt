package com.legitimateapps.dulcet.core

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Retention and deletion acceptance checks owned separately from the policy implementation. */
class OfflineLibraryRetentionSafetyTest {
    @Test
    fun anExplicitDownloadSurvivesLibraryRemovalAndDisablingAutomaticDownloadsAfterRelaunch() = withFixture { f ->
        val explicit = f.engine.enqueue(request("account-a", "song"))
        f.engine.writeCompletedTemporaryFile(explicit.downloadId, AUDIO)
        assertIs<DownloadPromotionResult.Promoted>(f.engine.promote(explicit.downloadId, response()))
        f.policy.setAutomaticDownloadsEnabled("account-a", true)
        f.policy.reconcileAutomaticDownloads("account-a", listOf(AutomaticDownloadMember(request("account-a", "song"), 9)))
        f.policy.reconcileAutomaticDownloads("account-a", emptyList())
        assertIs<OfflinePlaybackPlanResult.Available>(f.engine.offlinePlaybackPlan(explicit.identity))
        f.policy.setAutomaticDownloadsEnabled("account-a", false)
        val reopened = DownloadPolicyEngine(f.database.database, f.files)
        reopened.reconcile(emptyList(), mapOf("account-a" to 1L))
        assertIs<OfflinePlaybackPlanResult.Available>(reopened.offlinePlaybackPlan(explicit.identity))
        assertEquals(explicit.downloadId, reopened.record(explicit.identity)?.downloadId)
    }

    @Test
    fun aDownloadExplicitlyKeptAfterAutomaticAdditionSurvivesButAutomaticOnlyFilesAreDeleted() = withFixture { f ->
        f.policy.setAutomaticDownloadsEnabled("account-a", true)
        val members = listOf("keep", "remove").map { AutomaticDownloadMember(request("account-a", it), 9) }
        f.policy.reconcileAutomaticDownloads("account-a", members)
        for (member in members) {
            val row = assertNotNull(f.engine.record(member.request.identity))
            f.engine.writeCompletedTemporaryFile(row.downloadId, AUDIO)
            assertIs<DownloadPromotionResult.Promoted>(f.engine.promote(row.downloadId, response()))
        }
        f.engine.enqueue(request("account-a", "keep")) // The explicit Download action upgrades retention.
        f.policy.setAutomaticDownloadsEnabled("account-a", false)
        assertIs<OfflinePlaybackPlanResult.Available>(f.engine.offlinePlaybackPlan(request("account-a", "keep").identity))
        assertNull(f.engine.record(request("account-a", "remove").identity))
    }

    @Test
    fun clearingOneAccountsCacheCannotDeleteItsDownloadsOrAnotherAccountsIdenticalTrack() = withFixture { f ->
        cache(f, request("account-a", "same-id"))
        cache(f, request("account-b", "same-id"))
        val explicit = f.engine.enqueue(request("account-a", "explicit"))
        f.engine.writeCompletedTemporaryFile(explicit.downloadId, AUDIO)
        assertIs<DownloadPromotionResult.Promoted>(f.engine.promote(explicit.downloadId, response()))
        f.policy.clearCache("account-a")
        assertNull(f.engine.record(request("account-a", "same-id").identity))
        assertIs<OfflinePlaybackPlanResult.Available>(f.engine.offlinePlaybackPlan(request("account-b", "same-id").identity))
        assertIs<OfflinePlaybackPlanResult.Available>(f.engine.offlinePlaybackPlan(explicit.identity))
    }

    @Test
    fun cacheCannotPromoteTruncatedResponsesOrIncompleteSparseRanges() = withFixture { f ->
        val full = request("account-a", "truncated")
        val write = assertNotNull(f.policy.beginCacheWrite(full, 200))
        f.policy.appendCacheWrite(write.writeId, AUDIO.copyOfRange(0, 3), 0)
        assertIs<DownloadPromotionResult.Rejected>(f.policy.finishCacheWrite(write.writeId, response(), 10))
        assertNull(f.engine.record(full.identity))
        val seek = request("account-a", "seek")
        val sparse = assertNotNull(f.policy.beginCacheWrite(seek, 206, rangeStart = 3))
        assertEquals(true, f.policy.appendCacheWrite(sparse.writeId, AUDIO.copyOfRange(3, 6), 3))
        assertEquals(false, f.policy.isCacheWriteComplete(sparse.writeId))
        assertIs<DownloadPromotionResult.Rejected>(f.policy.finishCacheWrite(sparse.writeId, response(), 10))
        assertNull(f.engine.record(seek.identity))
        val outside = assertNotNull(f.policy.beginCacheWrite(seek, 206, rangeStart = 3))
        assertEquals(false, f.policy.appendCacheWrite(outside.writeId, AUDIO, 3))
        assertNull(f.engine.record(seek.identity))
    }

    @Test
    fun loweringCacheBudgetProtectsCurrentAndPreloadedFilesWhileEvictingOtherCache() = withFixture { f ->
        listOf("current", "preloaded", "old").forEach { cache(f, request("account-a", it)) }
        f.policy.setProtectedIdentities(setOf(request("account-a", "current").identity, request("account-a", "preloaded").identity))
        f.policy.setCacheBudgetBytes("account-a", 0)
        assertNull(f.engine.record(request("account-a", "old").identity))
        assertIs<OfflinePlaybackPlanResult.Available>(f.engine.offlinePlaybackPlan(request("account-a", "current").identity))
        assertIs<OfflinePlaybackPlanResult.Available>(f.engine.offlinePlaybackPlan(request("account-a", "preloaded").identity))
        f.policy.setProtectedIdentities(emptySet())
        assertNull(f.engine.record(request("account-a", "current").identity))
        assertNull(f.engine.record(request("account-a", "preloaded").identity))
    }

    @Test
    fun automaticSchedulingReprioritizesNewLibraryAdditionsAndCacheNeverBecomesADownloadTask() = withFixture { f ->
        f.policy.setAutomaticDownloadsEnabled("account-a", true)
        val old = AutomaticDownloadMember(request("account-a", "older"), 1)
        f.policy.reconcileAutomaticDownloads("account-a", listOf(old))
        val recent = AutomaticDownloadMember(request("account-a", "recent"), 99)
        f.policy.reconcileAutomaticDownloads("account-a", listOf(old, recent))
        cache(f, request("account-a", "cached"))
        val scheduled = assertIs<DownloadScheduleResult.Start>(f.engine.schedule(
            DownloadSchedulingContext("account-a", 100, 1_000_000, 100_000, ActiveTranscodeCounts()),
        ))
        assertEquals("recent", scheduled.record.identity.rawId)
        val source = DownloadedTrackSource.fromDownloads(f.database.database)
        assertEquals(false, "cached" in source.downloadedTrackRawIds("account-a"))
        assertEquals(true, "cached" in source.localTrackRawIds("account-a"))
    }

    @Test
    fun aChangedServerItemMarksTheCachedFileStaleWithoutDestroyingOfflinePlayback() = withFixture { f ->
        val request = request("account-a", "changed")
        cache(f, request)
        val reconciled = f.engine.reconcileServerItem(request.identity, DownloadServerSnapshot(181_000, 100), 20)
        assertEquals(DownloadState.Stale, reconciled?.state)
        assertIs<OfflinePlaybackPlanResult.Available>(f.policy.offlinePlaybackPlan("account-a", "changed", 21))
    }

    private fun cache(f: Fixture, request: DownloadRequest) {
        val write = assertNotNull(f.policy.beginCacheWrite(request, 200))
        assertEquals(true, f.policy.appendCacheWrite(write.writeId, AUDIO, 0))
        assertIs<DownloadPromotionResult.Promoted>(f.policy.finishCacheWrite(write.writeId, response(), 10))
    }

    private fun request(server: String, rawId: String) = DownloadRequest(
        DownloadIdentity(server, rawId, DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Mp3,
        PlaybackContentLength.Exact(AUDIO.size.toLong()), DownloadServerSnapshot(180_000, AUDIO.size.toLong()), 1, 1,
    )
    private fun response() = DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(AUDIO.size.toLong()))

    private fun withFixture(block: (Fixture) -> Unit) {
        val database = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-retention-acceptance-${secureRandomBytes(8).toLowerHex()}"
        val files = DownloadFileStore(root.toString(), FileSystem.SYSTEM)
        try {
            val engine = DownloadPolicyEngine(database.database, files)
            engine.reconcile(emptyList(), mapOf("account-a" to 1L, "account-b" to 1L))
            block(Fixture(database, files, engine, OfflineLibraryPolicy(database.database, engine)))
        } finally {
            database.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }
    private data class Fixture(val database: DulcetDatabaseStore, val files: DownloadFileStore, val engine: DownloadPolicyEngine, val policy: OfflineLibraryPolicy)
    private companion object { val AUDIO = byteArrayOf(73, 68, 51, 4, 0, 0) }
}
