package com.legitimateapps.dulcet.core

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A playlist's own Download (spec §14.5.2): kept whatever the setting, and following its edits. */
class PlaylistDownloadTest {
    @Test fun downloadingAPlaylistWithAutomaticDownloadsOffQueuesItsSongsAndFollowsLaterAdditions() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", false)
        f.index("mix", "other")
        f.detail("mix", "s1", "s2")
        f.detail("other", "o1")
        f.policy.reconcileFromSeenCache("a", 1, 1)
        assertTrue(f.ids().isEmpty(), "nothing is kept before a Download")
        assertFalse(f.policy.playlistDownloadSummary("a", "mix").wanted)

        f.policy.setPlaylistDownload("a", "mix", true, 10)
        f.policy.reconcileFromSeenCache("a", 1, 10)
        assertEquals(setOf("s1", "s2"), f.ids(), "only the downloaded playlist's songs")
        val summary = f.policy.playlistDownloadSummary("a", "mix")
        assertTrue(summary.wanted)
        assertFalse(summary.automatic)
        assertEquals(2, summary.songCount)
        assertEquals(2, summary.pendingCount)
        assertEquals(0, summary.downloadedCount)
        assertTrue(summary.songsKnown)

        // A song added on this device later is kept too, without another Download.
        f.song("s3")
        PlaylistOutbox(f.database.database, f.cache).put(PendingPlaylistRow.Entries("mix",
            null, null, listOf("s3"), false, 0, 20))
        f.policy.reconcileFromSeenCache("a", 1, 20)
        assertEquals(setOf("s1", "s2", "s3"), f.ids())
    }

    @Test fun removingAPlaylistDownloadReleasesItsSongsButNeverAnExplicitDownload() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", false)
        f.index("mix")
        f.detail("mix", "s1", "s2")
        f.engine.enqueue(f.request("s2"))
        f.policy.setPlaylistDownload("a", "mix", true, 10)
        f.policy.reconcileFromSeenCache("a", 1, 10)
        assertEquals(setOf("s1", "s2"), f.ids())

        f.policy.setPlaylistDownload("a", "mix", false, 11)
        val removed = f.policy.reconcileFromSeenCache("a", 1, 11).removed
        assertEquals(listOf("s1"), removed.map { it.identity.rawId })
        assertEquals(setOf("s2"), f.ids(), "a song downloaded on its own stays")
        assertFalse(f.policy.playlistDownloadSummary("a", "mix").wanted)
    }

    @Test fun turningAutomaticDownloadsOffKeepsDownloadedPlaylistsAndReleasesTheRest() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", true)
        f.index("kept", "auto")
        f.detail("kept", "k1")
        f.detail("auto", "a1")
        f.policy.reconcileFromSeenCache("a", 1, 1)
        assertEquals(setOf("k1", "a1"), f.ids())
        assertTrue(f.policy.playlistDownloadSummary("a", "auto").automatic)

        f.policy.setPlaylistDownload("a", "kept", true, 5)
        val removed = f.policy.setAutomaticDownloadsEnabled("a", false).removed
        assertEquals(listOf("a1"), removed.map { it.identity.rawId })
        assertEquals(setOf("k1"), f.ids())
        f.policy.reconcileFromSeenCache("a", 1, 6)
        assertEquals(setOf("k1"), f.ids(), "the projection agrees with the setting change")
        assertFalse(f.policy.playlistDownloadSummary("a", "auto").wanted)
    }

    @Test fun removingTheDownloadOfAnAutomaticPlaylistKeepsItOffWhileTheSettingIsOn() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", true)
        f.libraryRead()
        f.index("p1", "p2")
        f.detail("p1", "shared", "only1")
        f.detail("p2", "shared", "only2")
        f.policy.reconcileFromSeenCache("a", 1, 1)
        assertEquals(setOf("shared", "only1", "only2"), f.ids())

        f.policy.setPlaylistDownload("a", "p1", false, 2)
        f.policy.reconcileFromSeenCache("a", 1, 2)
        assertEquals(setOf("shared", "only2"), f.ids(), "a song another downloaded playlist holds stays")
        val summary = f.policy.playlistDownloadSummary("a", "p1")
        assertFalse(summary.wanted)
        assertFalse(summary.automatic)
    }

    @Test fun anUnknownPlaylistIndexNeverReleasesADownloadedPlaylistsSongs() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", true)
        f.index("mix")
        f.detail("mix", "s1")
        f.policy.setPlaylistDownload("a", "mix", true, 1)
        f.policy.reconcileFromSeenCache("a", 1, 1)
        // The index is reopened as an incomplete window: membership is not known any more.
        val key = canonicalListKey("getPlaylists", emptyMap())
        val write = f.stamp()
        f.cache.writeWholeList(write, CacheEntitySource.ListPage,
            CachedListState(key, null, null, 0, 1, 2, CacheCoverage.Open, 1, 1, write.issueSeq),
            listOf(CacheListMember(CacheItemKind.Playlist, "mix")), CacheEntities())
        assertTrue(f.policy.setAutomaticDownloadsEnabled("a", false).removed.isEmpty())
        assertTrue(f.policy.reconcileFromSeenCache("a", 1, 2).removed.isEmpty())
        assertEquals(setOf("s1"), f.ids())
    }

    @Test fun aPlaylistWhoseSongsAreUnknownIsHydratedEvenWithAutomaticDownloadsOff() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", false)
        f.index("mix")
        assertFalse(f.policy.hydratesDownloadDetails("a"))
        f.policy.setPlaylistDownload("a", "mix", true, 1)
        assertTrue(f.policy.hydratesDownloadDetails("a"))
        assertEquals(listOf("mix"), f.policy.missingAutomaticDownloadPlaylistIds("a"))
        assertFalse(f.policy.playlistDownloadSummary("a", "mix").songsKnown)
    }

    @Test fun aStreamedCopyIsNotCountedAsDownloadedAndAFinishedFileIs() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", false)
        f.index("mix")
        f.detail("mix", "s1", "s2")
        val cached = assertNotNull(f.policy.beginCacheWrite(f.request("s2", AUDIO.size.toLong()), 200))
        assertTrue(f.policy.appendCacheWrite(cached.writeId, AUDIO, 0))
        assertIs<DownloadPromotionResult.Promoted>(f.policy.finishCacheWrite(cached.writeId, metadata(), 1))
        assertEquals(0, f.policy.playlistDownloadSummary("a", "mix").downloadedCount, "a streamed copy is not a download")
        f.policy.setPlaylistDownload("a", "mix", true, 2)
        f.policy.reconcileFromSeenCache("a", 1, 2)
        // The playlist now owns the streamed copy of s2, so it is kept and counts; s1 is queued.
        assertEquals(1, f.policy.playlistDownloadSummary("a", "mix").downloadedCount)
        assertEquals(1, f.policy.playlistDownloadSummary("a", "mix").pendingCount)
        val first = f.engine.record(DownloadIdentity("a", "s1", "original"))!!
        f.engine.writeCompletedTemporaryFile(first.downloadId, AUDIO)
        assertIs<DownloadPromotionResult.Promoted>(f.engine.promote(first.downloadId, metadata()))
        val summary = f.policy.playlistDownloadSummary("a", "mix")
        assertEquals(2, summary.downloadedCount)
        assertEquals(0, summary.pendingCount)
        assertEquals(2, summary.songCount)
        assertFalse(f.policy.playlistDownloadSummary("a", "missing").wanted)
    }

    @Test fun downloadedPlaylistsListOnlyWantedPlaylistsWithADownloadedSong() = fixture { f ->
        f.policy.setAutomaticDownloadsEnabled("a", false)
        f.index("mix", "other", "empty")
        f.detail("mix", "s1")
        f.detail("other", "s1")
        f.detail("empty", "s2")
        f.policy.setPlaylistDownload("a", "mix", true, 1)
        f.policy.setPlaylistDownload("a", "empty", true, 1)
        f.policy.reconcileFromSeenCache("a", 1, 1)
        val source = DownloadedTrackSource.fromDownloads(f.database.database)
        assertTrue(source.downloadedPlaylistRawIds("a").isEmpty(), "nothing has finished yet")
        listOf("s1").forEach { id ->
            val row = f.engine.record(DownloadIdentity("a", id, "original"))!!
            f.engine.writeCompletedTemporaryFile(row.downloadId, AUDIO)
            assertIs<DownloadPromotionResult.Promoted>(f.engine.promote(row.downloadId, metadata()))
        }
        // "other" shares the song but was never downloaded, and the setting is off.
        assertEquals(listOf("mix"), source.downloadedPlaylistRawIds("a"))
        f.policy.setAutomaticDownloadsEnabled("a", true)
        assertEquals(setOf("mix", "other"), source.downloadedPlaylistRawIds("a").toSet())
    }

    @Test fun aCacheResetKeepsEveryPlaylistDownloadChoice() = fixture { f ->
        f.policy.setPlaylistDownload("a", "mix", true, 10)
        f.policy.setPlaylistDownload("a", "other", false, 11)
        val chosen = mapOf("mix" to true, "other" to false)
        // A binding that no longer matches purges the seen-cache namespace (spec §16.10).
        SeenCacheStore(f.database, SeenCacheWallClock { 1L }).bind(CacheBinding("a", "http://127.0.0.2", "fixture"))
        assertEquals(chosen, f.policy.playlistDownloadIntents("a"), "a namespace purge keeps the choices")
        f.database.database.serverDataQueries.deleteSeenCacheForServer("a")
        assertEquals(chosen, f.policy.playlistDownloadIntents("a"), "a seen-cache delete keeps the choices")
    }

    private class Fixture(
        val engine: DownloadPolicyEngine,
        val policy: OfflineLibraryPolicy,
        val database: DulcetDatabaseStore,
        val cache: BoundSeenCache,
    ) {
        fun stamp() = CacheWriteStamp(cache.issue(), 1, null)
        /** My Library has been read in full, so automatic membership is known and can shrink. */
        fun libraryRead() {
            val library = database.database.myLibraryQueries
            library.ensureState("a")
            library.updateRead(null, 1, cache.issue(), 1, "a")
        }
        fun ids() = engine.records("a").map { it.identity.rawId }.toSet()
        fun request(id: String, exact: Long? = null) = DownloadRequest(
            DownloadIdentity("a", id, "original"), AudioContainer.Mp3,
            exact?.let(PlaybackContentLength::Exact), DownloadServerSnapshot(1000, AUDIO.size.toLong()), 1, 1,
        )
        fun song(id: String) = cache.writeEntities(stamp(), CacheEntitySource.ListPage,
            CacheEntities(tracks = listOf(CacheTrackRecord(id, null, id, sourceContainer = AudioContainer.Mp3))))
        private fun list(key: String, members: List<CacheListMember>, entities: CacheEntities) {
            val write = stamp()
            cache.writeWholeList(write, CacheEntitySource.ListPage,
                CachedListState(key, null, null, 0, members.size, members.size, CacheCoverage.Complete, 1, 1, write.issueSeq), members, entities)
        }
        fun index(vararg playlists: String) = list(canonicalListKey("getPlaylists", emptyMap()),
            playlists.map { CacheListMember(CacheItemKind.Playlist, it) },
            CacheEntities(playlists = playlists.map { CachePlaylistRecord(it, it) }))
        fun detail(playlist: String, vararg songs: String) {
            list(playlistDetailListKey(playlist), songs.map { CacheListMember(CacheItemKind.Track, it) },
                CacheEntities(tracks = songs.map { CacheTrackRecord(it, null, it, sourceContainer = AudioContainer.Mp3) }))
            cache.markPlaylistDetail(cache.issue(), playlist)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val database = DulcetDatabaseStore.open(createTestDriver())
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-playlist-download-${secureRandomBytes(8).toLowerHex()}"
        try {
            val engine = DownloadPolicyEngine(database.database, DownloadFileStore(root.toString(), FileSystem.SYSTEM))
            engine.reconcile(emptyList(), mapOf("a" to 1L))
            val policy = OfflineLibraryPolicy(database.database, engine)
            val cache = SeenCacheStore(database, SeenCacheWallClock { 1L }).bind(CacheBinding("a", "http://127.0.0.1", "fixture"))
            try { block(Fixture(engine, policy, database, cache)) } finally { policy.close() }
        } finally {
            database.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private fun metadata() = DownloadResponseMetadata("audio/mpeg", PlaybackContentLength.Exact(AUDIO.size.toLong()))
    private companion object { val AUDIO = byteArrayOf(73, 68, 51, 4, 0, 0) }
}
