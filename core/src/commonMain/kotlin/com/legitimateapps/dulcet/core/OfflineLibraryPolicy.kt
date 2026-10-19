package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okio.BufferedSink

public data class OfflineLibrarySettings(val automaticDownloadsEnabled: Boolean, val cacheBudgetBytes: Long)
public data class AutomaticDownloadMember(val request: DownloadRequest, val addedAtWallClock: Long)
public data class OfflineLibraryReconciliation(val queued: List<DownloadRecord>, val removed: List<DownloadRecord>)
public data class OfflineLibraryStats(
    val downloadBytes: Long,
    val cacheBytes: Long,
    val queuedAutomaticCount: Int,
    val completedAutomaticCount: Int,
)
public data class StreamCacheWrite(val writeId: String)

/**
 * Account-scoped retention decisions. All methods run on the download executor's confined thread.
 * Cache bytes tee from the player's actual response; this policy never starts a network request.
 */
internal class OfflineLibraryPolicy(
    private val database: DulcetDatabase,
    private val engine: DownloadPolicyEngine,
) {
    private val queries get() = database.offlineLibraryQueries
    private val writes = mutableMapOf<String, CacheWrite>()
    private var protected = emptySet<DownloadIdentity>()

    init {
        engine.cacheReconciliationHandler = {
            database.downloadsQueries.selectAllDownloads().executeAsList().filter {
                it.state !in setOf("complete", "stale") && it.download_id !in writes
            }.forEach { row ->
                val identity = DownloadIdentity(row.server_id, row.raw_id, row.transcode_profile)
                if (isCache(identity)) engine.remove(identity)
            }
        }
        engine.cachePressureHandler = { serverId, requiredBytes ->
            var remaining = requiredBytes
            val candidates = engine.records(serverId).filter {
                isCache(it.identity) && it.identity !in protected && it.downloadId.value !in writes
            }.sortedBy { engine.retention(it.identity)?.last_access_wall ?: Long.MIN_VALUE }
            for (row in candidates) {
                if (remaining <= 0) break
                if (engine.remove(row.identity) != null) remaining -= row.fileSizeBytes
            }
        }
    }

    fun settings(serverId: String): OfflineLibrarySettings {
        require(serverId.isNotBlank())
        queries.ensureSettings(serverId)
        val row = queries.selectSettings(serverId).executeAsOne()
        return OfflineLibrarySettings(row.automatic_downloads == 1L, row.cache_budget_bytes)
    }

    fun setAutomaticDownloadsEnabled(serverId: String, enabled: Boolean): OfflineLibraryReconciliation {
        settings(serverId)
        queries.setAutomaticDownloads(if (enabled) 1 else 0, serverId)
        return if (enabled) OfflineLibraryReconciliation(emptyList(), emptyList())
        else reconcileAutomaticDownloads(serverId, emptyList())
    }

    /** Incomplete expansion may ADD resolved songs, but cannot remove unresolved album children. */
    fun reconcileAutomaticDownloads(
        serverId: String,
        members: List<AutomaticDownloadMember>,
        membershipComplete: Boolean = true,
    ): OfflineLibraryReconciliation {
        require(members.all { it.request.identity.serverId == serverId })
        val enabled = settings(serverId).automaticDownloadsEnabled
        val wanted = if (enabled) members.associateBy { it.request.identity } else emptyMap()
        val queued = mutableListOf<DownloadRecord>()
        wanted.values.sortedByDescending { it.addedAtWallClock }.forEach { member ->
            val identity = member.request.identity
            val previous = engine.record(identity)
            val row = previous ?: engine.enqueueRetained(member.request, explicit = false)
            val old = engine.retention(identity)
            // An existing row without a retention row predates this feature and is explicit.
            queries.upsertRetention(
                serverId, identity.rawId, identity.transcodeProfile,
                old?.explicit_download ?: if (previous != null) 1L else 0L,
                1, member.addedAtWallClock, old?.last_access_wall ?: member.request.wallClockMilliseconds,
            )
            if (previous == null) queued += row
        }
        val removed = mutableListOf<DownloadRecord>()
        if (membershipComplete || !enabled) {
            queries.selectServerRetentions(serverId).executeAsList().filter { it.automatic_download == 1L }.forEach { old ->
                val id = DownloadIdentity(serverId, old.raw_id, old.transcode_profile)
                if (id !in wanted) {
                    if (old.explicit_download == 0L && id in protected) return@forEach
                    queries.upsertRetention(serverId, id.rawId, id.transcodeProfile, old.explicit_download, 0,
                        old.added_rank, old.last_access_wall)
                    if (old.explicit_download == 0L) {
                        // Protected files remain named until a later reconcile can remove them.
                        if (id !in protected) engine.remove(id)?.let(removed::add)
                    }
                }
            }
        }
        return OfflineLibraryReconciliation(queued, removed)
    }

    /**
     * Shared My Library projection, including pending local membership. Unknown album detail never
     * authorizes deletion. Shells request missing album detail through their existing reader.
     */
    fun reconcileFromSeenCache(serverId: String, credentialGeneration: Long, wallClockMilliseconds: Long): OfflineLibraryReconciliation {
        val library = database.myLibraryQueries
        val members = memberRanks(serverId)
        var complete = library.selectState(serverId).executeAsOneOrNull()?.complete == 1L
        val tracks = mutableMapOf<String, Long>()
        members.forEach { (key, rank) ->
            when (key.first) {
                "track" -> tracks[key.second] = maxOf(tracks[key.second] ?: Long.MIN_VALUE, rank)
                "album" -> {
                    val album = database.seenCacheQueries.selectAlbum(serverId, key.second).executeAsOneOrNull()
                    if (album?.detail_complete != 1L) complete = false
                    database.seenCacheQueries.selectAlbumTracks(serverId, key.second).executeAsList().forEach {
                        tracks[it.raw_id] = maxOf(tracks[it.raw_id] ?: Long.MIN_VALUE, rank)
                    }
                }
            }
        }
        val playlistProjection = playlistTracks(serverId)
        complete = complete && playlistProjection.complete
        playlistProjection.tracks.forEach { (id, rank) -> tracks[id] = maxOf(tracks[id] ?: Long.MIN_VALUE, rank) }
        val requests = tracks.mapNotNull { (id, rank) ->
            val track = database.seenCacheQueries.selectTrack(serverId, id).executeAsOneOrNull()
            val container = track?.source_container?.let { runCatching { audioContainerFromCacheWireName(it) }.getOrNull() }
            if (container == null) { complete = false; return@mapNotNull null }
            AutomaticDownloadMember(DownloadRequest(DownloadIdentity(serverId, id, DownloadIdentity.ORIGINAL_PROFILE),
                container, null, DownloadServerSnapshot(track.duration_milliseconds, null), credentialGeneration, wallClockMilliseconds), rank)
        }
        return reconcileAutomaticDownloads(serverId, requests, complete)
    }

    fun needsAutomaticPlaylistIndex(serverId: String): Boolean =
        settings(serverId).automaticDownloadsEnabled && database.seenCacheQueries.selectList(
            serverId, canonicalListKey("getPlaylists", emptyMap()),
        ).executeAsOneOrNull() == null

    fun missingAutomaticDownloadAlbumIds(serverId: String): List<String> =
        if (!settings(serverId).automaticDownloadsEnabled) emptyList() else memberRanks(serverId).entries
            .filter { it.key.first == "album" && database.seenCacheQueries.selectAlbum(serverId, it.key.second).executeAsOneOrNull()?.detail_complete != 1L }
            .sortedByDescending { it.value }.map { it.key.second }

    fun missingAutomaticDownloadTrackIds(serverId: String): List<String> {
        if (!settings(serverId).automaticDownloadsEnabled) return emptyList()
        val ids = memberRanks(serverId).filterKeys { it.first == "track" }.keys.map { it.second } + playlistTracks(serverId).tracks.keys
        return ids.distinct().filter { database.seenCacheQueries.selectTrack(serverId, it).executeAsOneOrNull()?.source_container == null }
    }

    fun missingAutomaticDownloadPlaylistIds(serverId: String): List<String> =
        if (!settings(serverId).automaticDownloadsEnabled) emptyList() else playlistTracks(serverId).missingDetails

    private fun memberRanks(serverId: String): Map<Pair<String, String>, Long> {
        val members = database.myLibraryQueries.selectMembers(serverId).executeAsList()
            .associate { (it.item_kind to it.raw_id) to it.added_rank }.toMutableMap()
        database.protectedReservedDataQueries.selectPendingMutations(serverId).executeAsList().forEach { row ->
            if (row.field_.endsWith(".inLibrary")) {
                val kind = row.field_.substringBefore('.')
                val value = runCatching { Json.parseToJsonElement(row.value_).jsonObject["value"]?.jsonPrimitive?.intOrNull }.getOrNull()
                if (value == 1) members[kind to row.target_id] = Long.MAX_VALUE
                if (value == 0) members.remove(kind to row.target_id)
            }
        }
        return members
    }

    private data class PlaylistTracks(val tracks: Map<String, Long>, val complete: Boolean, val missingDetails: List<String>)

    /** Account playlist index plus the same pending-edit decoder used by PlaylistEditor. */
    private fun playlistTracks(serverId: String): PlaylistTracks {
        val indexKey = canonicalListKey("getPlaylists", emptyMap())
        val index = database.seenCacheQueries.selectList(serverId, indexKey).executeAsOneOrNull()
        var complete = index != null && index.coverage != "open" && index.first_loaded_offset == 0L
        val ids = database.seenCacheQueries.selectListMembers(serverId, indexKey).executeAsList()
            .filter { it.item_kind == "playlist" }.map { it.raw_id }.toMutableSet()
        val pending = database.protectedReservedDataQueries.selectPendingMutations(serverId).executeAsList()
            .mapNotNull { decodePendingPlaylistRow(it.target_id, it.field_, it.value_, it.local_sequence, it.wall_clock) }
        pending.filterIsInstance<PendingPlaylistRow.Create>().filter { !it.cancelled }.forEach { ids += it.playlistId }
        pending.filterIsInstance<PendingPlaylistRow.Delete>().forEach { ids -= it.playlistId }
        pending.filterIsInstance<PendingPlaylistRow.Create>().filter { it.cancelled }.forEach { ids -= it.playlistId }
        val tracks = mutableMapOf<String, Long>()
        val missing = mutableListOf<String>()
        ids.forEach { id ->
            val create = pending.filterIsInstance<PendingPlaylistRow.Create>().firstOrNull { it.playlistId == id }
            val edit = pending.filterIsInstance<PendingPlaylistRow.Entries>().firstOrNull { it.playlistId == id }
            val header = database.seenCacheQueries.selectPlaylist(serverId, id).executeAsOneOrNull()
            val detail = database.seenCacheQueries.selectList(serverId, playlistDetailListKey(id)).executeAsOneOrNull()
            val known = create != null || edit?.target != null || (header?.detail_complete == 1L && detail != null)
            if (!known) { complete = false; missing += id }
            val cached = database.seenCacheQueries.selectListMembers(serverId, playlistDetailListKey(id)).executeAsList()
                .filter { it.item_kind == "track" }.map { it.raw_id }
            val songs = create?.songs ?: edit?.target ?: (cached + edit?.suffix.orEmpty().drop(edit?.appended ?: 0))
            val rank = create?.wallClock ?: edit?.wallClock ?: header?.fetched_at_wall ?: 0
            songs.forEach { tracks[it] = maxOf(tracks[it] ?: Long.MIN_VALUE, rank) }
        }
        return PlaylistTracks(tracks, complete, missing)
    }

    fun stats(serverId: String): OfflineLibraryStats {
        val rows = engine.records(serverId)
        val cache = rows.filter { isCache(it.identity) }
        val automatic = rows.filter { engine.retention(it.identity)?.automatic_download == 1L }
        return OfflineLibraryStats(
            rows.filterNot { isCache(it.identity) }.sumOf { it.fileSizeBytes },
            cache.sumOf { it.fileSizeBytes },
            automatic.count { it.state !in setOf(DownloadState.Complete, DownloadState.Stale) },
            automatic.count { it.state in setOf(DownloadState.Complete, DownloadState.Stale) },
        )
    }

    fun setProtectedIdentities(identities: Set<DownloadIdentity>) { protected = identities.toSet() }

    fun markCacheAccess(identity: DownloadIdentity, wallClockMilliseconds: Long) {
        val old = engine.retention(identity) ?: return
        queries.upsertRetention(identity.serverId, identity.rawId, identity.transcodeProfile,
            old.explicit_download, old.automatic_download, old.added_rank, wallClockMilliseconds)
    }

    fun offlinePlaybackPlan(serverId: String, rawId: String, wallClockMilliseconds: Long): OfflinePlaybackPlanResult {
        val rows = engine.recordsForItem(serverId, rawId)
            .sortedWith(compareBy<DownloadRecord> { isCache(it.identity) }.thenBy { it.identity.isTranscoded })
        rows.forEach { row ->
            val result = engine.offlinePlaybackPlan(row.identity)
            if (result is OfflinePlaybackPlanResult.Available && engine.verifyOffline(result.plan) == OfflinePlaybackVerification.Valid) {
                markCacheAccess(row.identity, wallClockMilliseconds)
                return result
            }
        }
        return OfflinePlaybackPlanResult.NotDownloaded
    }

    fun setCacheBudgetBytes(serverId: String, bytes: Long): List<DownloadRecord> {
        require(bytes >= 0)
        settings(serverId)
        queries.setCacheBudget(bytes, serverId)
        return evict(serverId, 0)
    }

    fun clearCache(serverId: String): List<DownloadRecord> {
        writes.values.filter { it.row.identity.serverId == serverId }.map { it.row.downloadId.value }.forEach(::abortCacheWrite)
        return engine.records(serverId).filter { isCache(it.identity) && it.identity !in protected }
            .mapNotNull { engine.remove(it.identity) }
    }

    /**
     * 206 is accepted only for contiguous reads beginning at zero with an exact FULL resource
     * length. A caller must not finish a range at its own EOF; all resource bytes must arrive.
     */
    fun beginCacheWrite(request: DownloadRequest, responseStatus: Int, rangeStart: Long = 0,
        acceptedContainers: List<AudioContainer> = listOf(request.expectedContainer)): StreamCacheWrite? {
        if (rangeStart != 0L || responseStatus !in setOf(200, 206)) return null
        if (responseStatus == 206 && request.declaredContentLength !is PlaybackContentLength.Exact) return null
        val budget = settings(request.identity.serverId).cacheBudgetBytes
        if (budget == 0L) return null
        engine.record(request.identity)?.let { existing ->
            if (!isCache(existing.identity) || existing.downloadId.value in writes || existing.identity in protected) return null
            val local = engine.offlinePlaybackPlan(existing.identity)
            if (local is OfflinePlaybackPlanResult.Available && engine.verifyOffline(local.plan) == OfflinePlaybackVerification.Valid) return null
            // A launch-interrupted cache transfer has no resumable platform task. Its tombstone
            // must not block this actual playback response from rebuilding the cache.
            engine.remove(existing.identity)
        }
        val expected = (request.declaredContentLength as? PlaybackContentLength.Exact)?.byteCount
        if (expected != null && expected > budget) return null
        evict(request.identity.serverId, expected ?: 0)
        if (usedCacheBytes(request.identity.serverId) + (expected ?: 0) > budget) return null
        val row = engine.enqueueRetained(request, explicit = false)
        queries.upsertRetention(row.identity.serverId, row.identity.rawId, row.identity.transcodeProfile, 0, 0, 0, request.wallClockMilliseconds)
        database.downloadsQueries.markDownloadStarted(row.downloadId.value)
        return try {
            writes[row.downloadId.value] = CacheWrite(row, engine.files.openTemporarySink(row), expected, acceptedContainers.toList())
            StreamCacheWrite(row.downloadId.value)
        } catch (_: Exception) {
            engine.remove(row.identity)
            null
        }
    }

    fun appendCacheWrite(writeId: String, bytes: ByteArray, offset: Long): Boolean {
        val write = writes[writeId] ?: return false
        val next = write.bytes + bytes.size
        if (offset != write.bytes || next < write.bytes || (write.expected != null && next > write.expected)) {
            abortCacheWrite(writeId); return false
        }
        evict(write.row.identity.serverId, bytes.size.toLong())
        if (usedCacheBytes(write.row.identity.serverId) + bytes.size > settings(write.row.identity.serverId).cacheBudgetBytes) {
            abortCacheWrite(writeId); return false
        }
        return try { write.sink.write(bytes); write.bytes = next; true }
        catch (_: Exception) { abortCacheWrite(writeId); false }
    }

    fun finishCacheWrite(writeId: String, metadata: DownloadResponseMetadata, wallClockMilliseconds: Long): DownloadPromotionResult {
        val write = writes.remove(writeId) ?: return DownloadPromotionResult.UnknownDownload
        try { write.sink.close() } catch (_: Exception) {
            discardUnfinishedWrite(write)
            return DownloadPromotionResult.Rejected(DomainError.Protocol.UnexpectedBinary)
        }
        if (write.expected != null && write.expected != write.bytes) {
            discardUnfinishedWrite(write)
            return DownloadPromotionResult.Rejected(DomainError.Protocol.UnexpectedBinary)
        }
        val result = engine.promote(write.row.downloadId, DownloadResponseMetadata(metadata.contentType,
            write.expected?.let(PlaybackContentLength::Exact) ?: metadata.contentLength), write.acceptedContainers)
        if (result is DownloadPromotionResult.Promoted || result is DownloadPromotionResult.AlreadyPromoted) {
            markCacheAccess(write.row.identity, wallClockMilliseconds)
            evict(write.row.identity.serverId, 0)
        } else discardUnfinishedWrite(write)
        return result
    }

    fun abortCacheWrite(writeId: String) {
        val write = writes.remove(writeId) ?: return
        try { write.sink.close() } finally { discardUnfinishedWrite(write) }
    }

    private fun discardUnfinishedWrite(write: CacheWrite) {
        // An explicit click can upgrade an in-flight cache row. Never delete that ownership.
        if (isCache(write.row.identity)) engine.remove(write.row.identity)
        else {
            engine.files.deleteTemporary(write.row)
            database.downloadsQueries.markDownloadInterrupted(write.row.downloadId.value)
        }
    }

    fun close() { writes.keys.toList().forEach(::abortCacheWrite) }

    private fun usedCacheBytes(serverId: String): Long = engine.records(serverId).filter { isCache(it.identity) }
        .sumOf { row -> writes[row.downloadId.value]?.bytes ?: row.fileSizeBytes }

    private fun evict(serverId: String, additional: Long): List<DownloadRecord> {
        val removed = mutableListOf<DownloadRecord>()
        val budget = settings(serverId).cacheBudgetBytes
        val candidates = engine.records(serverId).filter {
            isCache(it.identity) && it.identity !in protected && it.downloadId.value !in writes
        }.sortedBy { engine.retention(it.identity)?.last_access_wall ?: Long.MIN_VALUE }
        for (row in candidates) {
            if (usedCacheBytes(serverId) <= (budget - additional).coerceAtLeast(0)) break
            engine.remove(row.identity)?.let(removed::add)
        }
        return removed
    }

    private fun isCache(identity: DownloadIdentity): Boolean = engine.retention(identity)?.let {
        it.explicit_download == 0L && it.automatic_download == 0L
    } ?: false

    private data class CacheWrite(val row: DownloadRecord, val sink: BufferedSink, val expected: Long?, val acceptedContainers: List<AudioContainer>, var bytes: Long = 0)
}
