package com.legitimateapps.dulcet.core

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineLibraryPolicyTest {
    @Test fun automaticRemovalAndDisabledSettingNeverDeleteExplicitOwnership() = fixture { engine, policy ->
        policy.setAutomaticDownloadsEnabled("a", true)
        val explicit = engine.enqueue(request("explicit"))
        val auto = request("automatic")
        policy.reconcileAutomaticDownloads("a", listOf(AutomaticDownloadMember(auto, 1), AutomaticDownloadMember(request("explicit"), 2)))
        val removed = policy.reconcileAutomaticDownloads("a", emptyList()).removed
        assertEquals(listOf(auto.identity), removed.map { it.identity })
        assertNotNull(engine.record(explicit.identity))
        policy.setAutomaticDownloadsEnabled("a", false)
        assertNotNull(engine.record(explicit.identity))
    }

    @Test fun newestAutomaticAdditionMovesAheadOfPreviouslyQueuedSongs() = fixture { engine, policy ->
        policy.setAutomaticDownloadsEnabled("a", true)
        val old = AutomaticDownloadMember(request("old"), 1)
        policy.reconcileAutomaticDownloads("a", listOf(old))
        policy.reconcileAutomaticDownloads("a", listOf(old, AutomaticDownloadMember(request("new"), 2)))
        assertEquals("new", assertIs<DownloadScheduleResult.Start>(engine.schedule(context())).record.identity.rawId)
    }

    @Test fun savedLibraryCannotCreateNewTransfersBeforeContactIsPermittedButCanReleaseOwnership() = fixture { engine, policy ->
        val existing = AutomaticDownloadMember(request("already-queued"), 1)
        val fresh = AutomaticDownloadMember(request("new-library-member"), 2)
        policy.reconcileAutomaticDownloads("a", listOf(existing))
        val explicit = engine.enqueue(request("explicit"))
        val beforeContact = policy.reconcileAutomaticDownloads("a", listOf(existing, fresh), allowNewDownloads = false)
        assertTrue(beforeContact.queued.isEmpty())
        assertNull(engine.record(fresh.request.identity))
        assertNotNull(engine.record(existing.request.identity))
        // Previously queued work keeps the existing launch recovery exception.
        assertEquals(explicit.identity, assertIs<DownloadScheduleResult.Start>(engine.schedule(context())).record.identity)
        assertEquals(existing.request.identity, assertIs<DownloadScheduleResult.Start>(engine.schedule(context())).record.identity)
        val released = policy.reconcileAutomaticDownloads("a", listOf(fresh), allowNewDownloads = false)
        assertEquals(listOf(existing.request.identity), released.removed.map { it.identity })
        assertNotNull(engine.record(explicit.identity))
        assertNull(engine.record(fresh.request.identity))
        policy.reconcileAutomaticDownloads("a", listOf(fresh), allowNewDownloads = true)
        assertNotNull(engine.record(fresh.request.identity))
        policy.setAutomaticDownloadsEnabled("a", false)
        assertNull(engine.record(fresh.request.identity))
        assertNotNull(engine.record(explicit.identity))
    }

    @Test fun incompleteAlbumExpansionCannotDeleteExistingAutomaticDownloads() = fixture { engine, policy ->
        policy.setAutomaticDownloadsEnabled("a", true)
        policy.reconcileAutomaticDownloads("a", listOf(AutomaticDownloadMember(request("kept"), 1)))
        assertTrue(policy.reconcileAutomaticDownloads("a", emptyList(), membershipComplete = false).removed.isEmpty())
        assertNotNull(engine.record(request("kept").identity))
    }

    @Test fun streamCachePromotesActualBytesAndNeverBecomesAnExplicitDownloadedItem() = fixture { engine, policy ->
        val write = assertNotNull(policy.beginCacheWrite(request("cached", AUDIO.size.toLong()), 200))
        assertTrue(policy.appendCacheWrite(write.writeId, AUDIO, 0))
        assertIs<DownloadPromotionResult.Promoted>(policy.finishCacheWrite(write.writeId, metadata(), 10))
        val result = assertIs<OfflinePlaybackPlanResult.Available>(policy.offlinePlaybackPlan("a", "cached", 11))
        assertEquals(AUDIO.size.toLong(), result.plan.exactByteLength)
        assertEquals(AUDIO.size.toLong(), policy.stats("a").cacheBytes)
        assertEquals(0, policy.stats("a").downloadBytes)
        assertIs<DownloadScheduleResult.NothingQueued>(engine.schedule(context()))
    }

    @Test fun partialRangesAndSeekHolesCannotPromote() = fixture { engine, policy ->
        assertNull(policy.beginCacheWrite(request("range-no-total"), 206))
        val tail = assertNotNull(policy.beginCacheWrite(request("range-offset", 6), 206, 3))
        assertTrue(policy.appendCacheWrite(tail.writeId, AUDIO.copyOfRange(3, 6), 3))
        assertFalse(policy.isCacheWriteComplete(tail.writeId))
        assertIs<DownloadPromotionResult.Rejected>(policy.finishCacheWrite(tail.writeId, metadata(), 10))
        val partial = assertNotNull(policy.beginCacheWrite(request("partial", 100), 206))
        assertTrue(policy.appendCacheWrite(partial.writeId, AUDIO, 0))
        assertIs<DownloadPromotionResult.Rejected>(policy.finishCacheWrite(partial.writeId, metadata(), 10))
        assertNull(engine.record(request("partial").identity))
        val seek = assertNotNull(policy.beginCacheWrite(request("seek", 100), 206))
        assertTrue(policy.appendCacheWrite(seek.writeId, AUDIO, 50))
        assertFalse(policy.isCacheWriteComplete(seek.writeId))
        assertIs<DownloadPromotionResult.Rejected>(policy.finishCacheWrite(seek.writeId, metadata(), 10))
        assertNull(engine.record(request("seek").identity))
        val unknown = assertNotNull(policy.beginCacheWrite(request("unknown"), 200))
        assertFalse(policy.appendCacheWrite(unknown.writeId, AUDIO, 1))
    }

    @Test fun lruEvictsOnlyUnprotectedCacheAndRespectsAccountAndDiskBudget() = fixture { engine, policy ->
        policy.setCacheBudgetBytes("a", AUDIO.size.toLong() * 2)
        engine.enqueue(request("explicit"))
        cache(policy, "old", 1)
        cache(policy, "recent", 2)
        policy.setProtectedIdentities(setOf(request("old").identity))
        cache(policy, "next", 3)
        assertNotNull(engine.record(request("old").identity))
        assertNull(engine.record(request("recent").identity))
        assertNotNull(engine.record(request("explicit").identity))
        assertEquals(AUDIO.size.toLong() * 2, policy.stats("a").cacheBytes)
        assertNull(policy.beginCacheWrite(request("oversized", 100), 200))
    }

    @Test fun cachedServerChangeIsStaleJustLikeDownload() = fixture { engine, policy ->
        cache(policy, "changed", 1)
        val result = engine.reconcileServerItem(request("changed").identity, DownloadServerSnapshot(1000, 99), 2)
        assertEquals(DownloadState.Stale, result?.state)
        assertIs<OfflinePlaybackPlanResult.Available>(policy.offlinePlaybackPlan("a", "changed", 3))
    }

    @Test fun protectedAutomaticRemovalRetriesAfterPlaybackReleasesIt() = fixture { engine, policy ->
        policy.setAutomaticDownloadsEnabled("a", true)
        policy.reconcileAutomaticDownloads("a", listOf(AutomaticDownloadMember(request("playing"), 1)))
        policy.setProtectedIdentities(setOf(request("playing").identity))
        policy.setAutomaticDownloadsEnabled("a", false)
        assertNotNull(engine.record(request("playing").identity))
        policy.setProtectedIdentities(emptySet())
        policy.reconcileAutomaticDownloads("a", emptyList())
        assertNull(engine.record(request("playing").identity))
    }

    @Test fun sharedProjectionExpandsAlbumsAndPendingPlaylistEditsAndRemovesOnlyFormerMembers() = fixtureDetailed { engine, policy, database ->
        val cache = SeenCacheStore(database, SeenCacheWallClock { 1L }).bind(CacheBinding("a", "http://127.0.0.1", "fixture"))
        fun stamp() = CacheWriteStamp(cache.issue(), 1, null)
        val albumSong = CacheTrackRecord("album-song", "album", "Album song", sourceContainer = AudioContainer.Mp3)
        val playlistSong = CacheTrackRecord("playlist-song", null, "Playlist song", sourceContainer = AudioContainer.Mp3)
        val replacement = CacheTrackRecord("replacement", null, "Replacement", sourceContainer = AudioContainer.Mp3)
        cache.writeAlbumDetail(stamp(), CacheAlbumRecord("album", "Album"), listOf(albumSong))
        val library = database.database.myLibraryQueries
        library.ensureState("a")
        library.upsertItem("a", "album", "album", 1, 1, cache.issue())
        library.updateRead(null, 1, cache.issue(), 1, "a")
        fun list(key: String, members: List<CacheListMember>, entities: CacheEntities) {
            val write = stamp()
            cache.writeWholeList(write, CacheEntitySource.ListPage,
                CachedListState(key, null, null, 0, members.size, members.size, CacheCoverage.Complete, 1, 1, write.issueSeq), members, entities)
        }
        list(canonicalListKey("getPlaylists", emptyMap()), listOf(CacheListMember(CacheItemKind.Playlist, "playlist")),
            CacheEntities(playlists = listOf(CachePlaylistRecord("playlist", "Playlist"))))
        assertEquals(listOf("playlist"), policy.missingAutomaticDownloadPlaylistIds("a"))
        list(playlistDetailListKey("playlist"), listOf(CacheListMember(CacheItemKind.Track, "playlist-song")), CacheEntities(tracks = listOf(playlistSong)))
        cache.markPlaylistDetail(cache.issue(), "playlist")
        assertTrue(policy.missingAutomaticDownloadAlbumIds("a").isEmpty())
        assertTrue(policy.missingAutomaticDownloadPlaylistIds("a").isEmpty())
        policy.reconcileFromSeenCache("a", 1, 1)
        assertEquals(setOf("album-song", "playlist-song"), engine.records("a").map { it.identity.rawId }.toSet())
        cache.writeEntities(stamp(), CacheEntitySource.ListPage, CacheEntities(tracks = listOf(replacement)))
        PlaylistOutbox(database.database, cache).put(PendingPlaylistRow.Entries("playlist",
            listOf("playlist-song"), listOf("replacement"), emptyList(), false, 0, 1))
        policy.reconcileFromSeenCache("a", 1, 2)
        assertEquals(setOf("album-song", "replacement"), engine.records("a").map { it.identity.rawId }.toSet())
        assertNull(engine.record(DownloadIdentity("a", "playlist-song", "original")))
    }

    @Test fun liveSourceSizeChangeStalesCachedRepresentationWithoutComparingItsTranscodedLength() = fixtureDetailed { engine, policy, database ->
        val cache = SeenCacheStore(database, SeenCacheWallClock { 1L }).bind(CacheBinding("a", "http://127.0.0.1", "fixture"))
        fun write(size: Long) = cache.writeEntities(CacheWriteStamp(cache.issue(), 1, null), CacheEntitySource.Detail,
            CacheEntities(tracks = listOf(CacheTrackRecord("live", null, "Live", durationMilliseconds = 1000,
                sourceContainer = AudioContainer.Mp3, sourceSizeBytes = size))))
        write(100)
        val request = request("live", AUDIO.size.toLong()).copy(serverSnapshot = DownloadServerSnapshot(1000, null))
        val writer = assertNotNull(policy.beginCacheWrite(request, 200))
        assertTrue(policy.appendCacheWrite(writer.writeId, AUDIO, 0))
        assertIs<DownloadPromotionResult.Promoted>(policy.finishCacheWrite(writer.writeId, metadata(), 1))
        assertEquals(100, engine.record(request.identity)?.serverSnapshot?.sizeBytes)
        write(100)
        assertEquals(DownloadState.Complete, engine.record(request.identity)?.state)
        write(101)
        assertEquals(DownloadState.Stale, engine.record(request.identity)?.state)
    }

    @Test fun corePinsRetainedMetadataUntilTheFinalRepresentationIsRemoved() = fixtureDetailed { engine, _, database ->
        val original = request("pinned")
        val alternate = original.copy(identity = original.identity.copy(transcodeProfile = "stream:mp3"))
        engine.enqueue(original)
        engine.enqueue(alternate)
        fun pins() = database.database.seenCacheQueries.selectPins("a").executeAsList().count { it.raw_id == "pinned" && it.reason == "download" }
        assertEquals(1, pins())
        engine.remove(original.identity)
        assertEquals(1, pins())
        engine.remove(alternate.identity)
        assertEquals(0, pins())
    }

    @Test fun interruptedCacheTombstoneCanBeRebuiltWithoutSchedulingADownload() = fixtureDetailed { engine, policy, database ->
        val interrupted = request("interrupted", AUDIO.size.toLong())
        val writer = assertNotNull(policy.beginCacheWrite(interrupted, 200))
        assertTrue(policy.appendCacheWrite(writer.writeId, AUDIO.copyOfRange(0, 3), 0))
        // Emulate process loss after the sink closes but before any terminal response.
        policy.close()
        val tombstone = engine.enqueueRetained(interrupted, explicit = false)
        database.database.downloadsQueries.markDownloadInterrupted(tombstone.downloadId.value)
        assertIs<DownloadScheduleResult.NothingQueued>(engine.schedule(context()))
        val retry = assertNotNull(policy.beginCacheWrite(interrupted, 200))
        assertTrue(policy.appendCacheWrite(retry.writeId, AUDIO, 0))
        assertIs<DownloadPromotionResult.Promoted>(policy.finishCacheWrite(retry.writeId, metadata(), 2))
    }

    @Test fun cacheStalenessSurvivesLaunchRecoveryOfAnExistingValidFile() = fixtureDetailed { engine, policy, database ->
        cache(policy, "stale-relaunch", 1)
        engine.reconcileServerItem(request("stale-relaunch").identity, DownloadServerSnapshot(2000, 99), 2)
        val relaunched = DownloadPolicyEngine(database.database, engine.files)
        relaunched.reconcile(emptyList(), mapOf("a" to 1L))
        assertEquals(DownloadState.Stale, relaunched.record(request("stale-relaunch").identity)?.state)
    }

    @Test fun schedulingADownloadEvictsOrdinaryCacheBeforeReportingDiskPressure() = fixture { engine, policy ->
        cache(policy, "evictable", 1)
        val explicit = engine.enqueue(request("priority", AUDIO.size.toLong()))
        val result = assertIs<DownloadScheduleResult.Start>(engine.schedule(context().copy(diskBudgetBytes = AUDIO.size.toLong())))
        assertEquals(explicit.downloadId, result.record.downloadId)
        assertNull(engine.record(request("evictable").identity))
    }

    @Test fun schedulingCannotEvictAProtectedCacheFileToFitADownload() = fixture { engine, policy ->
        cache(policy, "protected", 1)
        policy.setProtectedIdentities(setOf(request("protected").identity))
        engine.enqueue(request("blocked", AUDIO.size.toLong()))
        assertIs<DownloadScheduleResult.DiskBudgetExceeded>(engine.schedule(context().copy(diskBudgetBytes = AUDIO.size.toLong())))
        assertNotNull(engine.record(request("protected").identity))
    }

    @Test fun launchPurgesInterruptedCacheRowsAndTheirPinsButKeepsExplicitAndCompletedMedia() = fixtureDetailed { engine, policy, database ->
        cache(policy, "complete-cache", 1)
        val interrupted = engine.enqueueRetained(request("orphan-cache"), explicit = false)
        database.database.downloadsQueries.markDownloadInterrupted(interrupted.downloadId.value)
        val explicit = engine.enqueue(request("explicit-partial"))
        val relaunched = DownloadPolicyEngine(database.database, engine.files)
        OfflineLibraryPolicy(database.database, relaunched)
        relaunched.reconcile(emptyList(), mapOf("a" to 1L))
        assertNull(relaunched.record(interrupted.identity))
        assertNotNull(relaunched.record(explicit.identity))
        assertNotNull(relaunched.record(request("complete-cache").identity))
        assertFalse(database.database.seenCacheQueries.selectPins("a").executeAsList().any { it.raw_id == "orphan-cache" })
    }

    @Test fun mp4TailThenHeaderThenOverlappingMiddlePromotesOnlyAfterFullCoverage() = fixture { engine, policy ->
        val mp4 = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 77, 52, 65, 32, 0, 0, 0, 0)
        val request = request("sparse-mp4", mp4.size.toLong()).copy(expectedContainer = AudioContainer.Mp4)
        val writer = assertNotNull(policy.beginCacheWrite(request, 206, 12))
        assertTrue(policy.appendCacheWrite(writer.writeId, mp4.copyOfRange(12, 16), 12))
        assertFalse(policy.isCacheWriteComplete(writer.writeId))
        assertTrue(policy.appendCacheWrite(writer.writeId, mp4.copyOfRange(0, 8), 0))
        assertFalse(policy.isCacheWriteComplete(writer.writeId))
        assertTrue(policy.appendCacheWrite(writer.writeId, mp4.copyOfRange(4, 14), 4))
        assertTrue(policy.isCacheWriteComplete(writer.writeId))
        assertIs<DownloadPromotionResult.Promoted>(policy.finishCacheWrite(writer.writeId,
            DownloadResponseMetadata("audio/mp4", PlaybackContentLength.Exact(mp4.size.toLong())), 2))
        val plan = assertIs<OfflinePlaybackPlanResult.Available>(policy.offlinePlaybackPlan("a", "sparse-mp4", 3)).plan
        val loaded = assertIs<OfflinePlaybackLoadResult.Audio>(engine.loadOffline(plan))
        kotlin.test.assertContentEquals(mp4, loaded.bytes)
    }

    @Test fun inFlightExactResourceReservesItsWholeBudgetBeforeAnyRangeArrives() = fixture { _, policy ->
        policy.setCacheBudgetBytes("a", 10)
        val first = assertNotNull(policy.beginCacheWrite(request("first", 10), 206, 9))
        assertNull(policy.beginCacheWrite(request("second", 1), 200))
        assertFalse(policy.appendCacheWrite(first.writeId, AUDIO, 9))
        assertNotNull(policy.beginCacheWrite(request("second", 1), 200))
    }

    @Test fun artworkIsTrimmedBeforeMediaAndSharesTheSameCacheBudget() = fixture { engine, policy ->
        policy.setCacheBudgetBytes("a", AUDIO.size.toLong() * 2)
        cache(policy, "kept-stream", 1)
        val plan = policy.planArtworkCache("a", AUDIO.size.toLong(), 0, AUDIO.size.toLong())
        assertEquals(0, plan.targetEvictableBytes)
        // Until the executor reports artwork trimmed, media keeps its higher priority.
        assertNull(policy.beginCacheWrite(request("new-stream", AUDIO.size.toLong()), 200))
        assertNotNull(engine.record(request("kept-stream").identity))
        policy.planArtworkCache("a", 0, 0, AUDIO.size.toLong())
        cache(policy, "new-stream", 2)
        assertNotNull(engine.record(request("kept-stream").identity))
        assertEquals(0, policy.planArtworkCache("a", 0, 0).targetEvictableBytes)
    }

    @Test fun downloadedArtworkIsPinnedButStreamingArtworkRemainsEvictable() = fixtureDetailed { engine, policy, database ->
        val bound = SeenCacheStore(database, SeenCacheWallClock { 1L }).bind(CacheBinding("a", "http://127.0.0.1", "fixture"))
        bound.writeEntities(CacheWriteStamp(bound.issue(), 1, null), CacheEntitySource.Detail,
            CacheEntities(tracks = listOf(CacheTrackRecord("art-download", null, "Downloaded", sourceContainer = AudioContainer.Mp3, artworkKey = "download-cover"),
                CacheTrackRecord("art-stream", null, "Streamed", sourceContainer = AudioContainer.Mp3, artworkKey = "stream-cover"))))
        val row = engine.enqueue(request("art-download", AUDIO.size.toLong()))
        engine.writeCompletedTemporaryFile(row.downloadId, AUDIO)
        assertIs<DownloadPromotionResult.Promoted>(engine.promote(row.downloadId, metadata()))
        cache(policy, "art-stream", 1)
        val plan = policy.planArtworkCache("a", 10, 10)
        assertEquals(listOf("download-cover"), plan.protectedArtworkKeys)
    }

    @Test fun releasingExplicitOwnershipKeepsAutomaticFileUntilLibraryMembershipIsRemoved() = fixture { engine, policy ->
        val requested = request("both", AUDIO.size.toLong())
        val row = engine.enqueue(requested)
        engine.writeCompletedTemporaryFile(row.downloadId, AUDIO)
        assertIs<DownloadPromotionResult.Promoted>(engine.promote(row.downloadId, metadata()))
        policy.reconcileAutomaticDownloads("a", listOf(AutomaticDownloadMember(requested, 1)))
        assertNull(engine.releaseExplicitDownload(requested.identity))
        assertNotNull(engine.record(requested.identity))
        assertTrue(engine.destinationExists(row.downloadId))
        assertEquals(0, engine.retention(requested.identity)?.explicit_download)
        policy.reconcileAutomaticDownloads("a", emptyList())
        assertNull(engine.record(requested.identity))
    }

    private fun cache(policy: OfflineLibraryPolicy, id: String, wall: Long) {
        val write = assertNotNull(policy.beginCacheWrite(request(id, AUDIO.size.toLong()), 200))
        assertTrue(policy.appendCacheWrite(write.writeId, AUDIO, 0))
        assertIs<DownloadPromotionResult.Promoted>(policy.finishCacheWrite(write.writeId, metadata(), wall))
    }

    private fun fixture(block: (DownloadPolicyEngine, OfflineLibraryPolicy) -> Unit) = fixtureDetailed { engine, policy, _ -> block(engine, policy) }

    private fun fixtureDetailed(block: (DownloadPolicyEngine, OfflineLibraryPolicy, DulcetDatabaseStore) -> Unit) {
        val database = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-offline-policy-${secureRandomBytes(8).toLowerHex()}"
        try {
            val engine = DownloadPolicyEngine(database.database, DownloadFileStore(root.toString(), FileSystem.SYSTEM))
            engine.reconcile(emptyList(), mapOf("a" to 1L))
            val policy = OfflineLibraryPolicy(database.database, engine)
            try { block(engine, policy, database) } finally { policy.close() }
        } finally {
            database.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private fun request(id: String, exact: Long? = null) = DownloadRequest(
        DownloadIdentity("a", id, "original"), AudioContainer.Mp3,
        exact?.let(PlaybackContentLength::Exact), DownloadServerSnapshot(1000, AUDIO.size.toLong()), 1, 1,
    )
    private fun metadata() = DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(AUDIO.size.toLong()))
    private fun context() = DownloadSchedulingContext("a", 1, 1024, 100, ActiveTranscodeCounts(0, 0, 0))
    private companion object { val AUDIO = byteArrayOf(73, 68, 51, 4, 0, 0) }
}
