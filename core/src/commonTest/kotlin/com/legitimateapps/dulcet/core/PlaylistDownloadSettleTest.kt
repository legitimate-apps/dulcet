package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.advanceUntilIdle
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A playlist downloaded while its create was still pending (spec §14.5.2): the create settles on
 * the server under a new id, and the download moves with it rather than staying on the local id.
 */
class PlaylistDownloadSettleTest {
    @Test
    fun aDownloadChosenBeforeACreateSyncsFollowsThePlaylistToItsServerId() = playlistTest { env ->
        val serverId = PlaylistEnv.BINDING.serverId
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "dulcet-playlist-settle-${secureRandomBytes(8).toLowerHex()}"
        val db = env.database.database
        val engine = DownloadPolicyEngine(db, DownloadFileStore(root.toString(), FileSystem.SYSTEM))
        engine.reconcile(emptyList(), mapOf(serverId to 1L))
        val policy = OfflineLibraryPolicy(db, engine)
        try {
            val session = env.session()
            env.open(session, LibraryQuery.Playlists)
            advanceUntilIdle()
            // The songs' formats are known, so a projection can queue them.
            val cache = session.reader.cache
            cache.writeEntities(CacheWriteStamp(cache.issue(), 1, null), CacheEntitySource.ListPage, CacheEntities(tracks =
                listOf("song-1", "song-2").map { CacheTrackRecord(it, null, it, sourceContainer = AudioContainer.Mp3) }))
            policy.setAutomaticDownloadsEnabled(serverId, false)
            session.setOnline(false)
            val localId = assertNotNull(session.playlists.create("Mix", listOf("song-1", "song-2")).localId)
            policy.setPlaylistDownload(serverId, localId, true, 10)
            policy.reconcileFromSeenCache(serverId, 1, 10)
            val kept = engine.records(serverId).map { it.identity.rawId }.toSet()
            assertEquals(setOf("song-1", "song-2"), kept, "the pending playlist's songs are downloading")

            session.setOnline(true)
            advanceUntilIdle()
            val created = env.outcomes.filterIsInstance<PlaylistEditOutcome.Created>().single()
            assertEquals(localId, created.localId)
            assertFalse(created.playlistId == localId, "the server gave the playlist a new id")

            assertEquals(mapOf(created.playlistId to true), policy.playlistDownloadIntents(serverId),
                "the intent moved to the server id and left nothing under the local id")
            assertTrue(policy.playlistDownloadSummary(serverId, created.playlistId).wanted)
            policy.reconcileFromSeenCache(serverId, 1, 20)
            assertEquals(kept, engine.records(serverId).map { it.identity.rawId }.toSet(),
                "the songs stay downloaded after the create syncs")
        } finally {
            policy.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }
}
