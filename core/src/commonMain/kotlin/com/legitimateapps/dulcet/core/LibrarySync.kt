package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

internal enum class LibrarySyncStage(val wireName: String) {
    Folders("folders"),
    Artists("artists"),
    Albums("albums"),
    Tracks("tracks"),
    Playlists("playlists"),
    Starred("starred"),
    Genres("genres");

    fun nextOrNull(): LibrarySyncStage? = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromWireName(value: String): LibrarySyncStage =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown library sync stage")
    }
}

internal enum class LibrarySyncStability(val wireName: String) {
    Verified("verified"),
    Unverified("unverified"),
}

/** Credentials and provider identity for a durable library import. */
public data class LibrarySyncRequest(
    val providerInstanceId: String,
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "LibrarySyncRequest(<redacted>)"
}

public enum class LibrarySyncCompletionStability {
    Verified,
    Unverified,
}

/** Coarse durable-stage progress, including the explicit no-committed-generation UI state. */
public data class LibrarySyncProgress(
    val stage: String,
    val completedStageCount: Int,
    val totalStageCount: Int,
    val isFirstSync: Boolean,
)

/** A stable `(server_id, raw_id)` reference whose server row disappeared in this generation. */
public data class LibraryDeletionNotice(
    val generation: Long,
    val providerInstanceId: String,
    val rawId: String,
    val downloadedReferenceCount: Long,
    val queueReferenceCount: Long,
)

/** Objective-C-safe indexed access; no arbitrary Kotlin collection crosses the app boundary. */
public class LibraryDeletionNoticeList internal constructor(
    private val values: List<LibraryDeletionNotice>,
) {
    public val count: Int get() = values.size

    public fun noticeAt(index: Int): LibraryDeletionNotice? = values.getOrNull(index)
}

public sealed interface LibrarySyncResponse {
    public data class Completed(
        val generation: Long,
        val stability: LibrarySyncCompletionStability,
        val deletionNotices: LibraryDeletionNoticeList,
        val libraryChangedDuringScan: Boolean,
    ) : LibrarySyncResponse

    public data class Failed(val error: DomainError) : LibrarySyncResponse
}

/**
 * Presentation-safe durable sync surface. It returns closed results and catches every Kotlin failure
 * before an Objective-C caller can observe it.
 */
public class LibrarySyncManager internal constructor(
    private val engine: LibrarySyncEngine,
    private val repository: LibrarySyncRepository,
) {
    public suspend fun synchronize(
        request: LibrarySyncRequest,
        restart: Boolean = false,
        progress: (LibrarySyncProgress) -> Unit = {},
    ): LibrarySyncResponse = complete(request.providerInstanceId) {
        engine.synchronize(request.asBrowseRequest(), restart, progress)
    }

    internal suspend fun synchronize(
        providerInstanceId: String,
        source: LibrarySyncSource,
        restart: Boolean = false,
        progress: (LibrarySyncProgress) -> Unit = {},
    ): LibrarySyncResponse = complete(providerInstanceId) {
        engine.synchronize(providerInstanceId, source, restart, progress)
    }

    private suspend fun complete(
        providerInstanceId: String,
        synchronize: suspend () -> LibrarySyncResult,
    ): LibrarySyncResponse = try {
        when (val result = synchronize()) {
            is LibrarySyncResult.Completed -> {
                val stability = when (result.stability) {
                    LibrarySyncStability.Verified -> LibrarySyncCompletionStability.Verified
                    LibrarySyncStability.Unverified -> LibrarySyncCompletionStability.Unverified
                }
                val response = LibrarySyncResponse.Completed(
                    generation = result.generation,
                    stability = stability,
                    deletionNotices = LibraryDeletionNoticeList(
                        result.deletionReconciliations.map { notice ->
                            LibraryDeletionNotice(
                                generation = notice.generation,
                                providerInstanceId = providerInstanceId,
                                rawId = notice.rawId,
                                downloadedReferenceCount = notice.downloadedReferenceCount,
                                queueReferenceCount = notice.queueReferenceCount,
                            )
                        },
                    ),
                    libraryChangedDuringScan = stability == LibrarySyncCompletionStability.Unverified,
                )
                repository.observePostCommit(LibrarySyncPostCommitSite.BeforeReconciliationDelivery)
                repository.pruneClosedVersionsBestEffort()
                response
            }
            is LibrarySyncResult.Failed -> LibrarySyncResponse.Failed(result.error)
        }
    } catch (failure: Throwable) {
        LibrarySyncResponse.Failed(mapAccountConnectionFailure(failure))
    }
}

private fun LibrarySyncRequest.asBrowseRequest(): LibraryBrowseRequest = LibraryBrowseRequest(
    providerInstanceId = providerInstanceId,
    normalizedBaseUrl = normalizedBaseUrl,
    username = username,
    password = password,
    allowLocalHttp = allowLocalHttp,
)

internal data class LibraryPlaylistSummary(
    val id: ProviderItemId,
    val name: String,
)

internal data class LibraryPlaylist(
    val summary: LibraryPlaylistSummary,
    val trackIds: List<ProviderItemId>,
)

internal enum class LibraryStarredKind(val wireName: String) {
    Artist("artist"),
    Album("album"),
    Track("track"),
}

internal data class LibraryStarredItem(
    val kind: LibraryStarredKind,
    val id: ProviderItemId,
)

internal data class LibraryGenre(
    val name: String,
    val songCount: Long,
    val albumCount: Long,
)

internal interface LibrarySyncSource {
    suspend fun musicFolders(): List<LibraryMusicFolder>
    suspend fun artists(): List<LibraryArtist>
    suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary>
    suspend fun album(rawId: String): LibraryAlbum
    suspend fun playlists(): List<LibraryPlaylistSummary>
    suspend fun playlist(summary: LibraryPlaylistSummary): LibraryPlaylist
    suspend fun starred(): List<LibraryStarredItem>
    suspend fun genres(): List<LibraryGenre>
}

internal class HttpLibrarySyncSource(
    private val request: LibraryBrowseRequest,
    saltSource: SaltSource?,
    logSink: LogSink?,
    hostResolver: HostResolver,
) : LibrarySyncSource, AutoCloseableLibraryTransport {
    private val transport = KtorLibraryEndpointTransport(request, saltSource, logSink, hostResolver)

    override suspend fun musicFolders(): List<LibraryMusicFolder> =
        parseMusicFolders(request.providerInstanceId, transport.checkedRequest("getMusicFolders"))

    override suspend fun artists(): List<LibraryArtist> =
        parseArtists(request.providerInstanceId, transport.checkedRequest("getArtists"))

    override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> =
        parseAlbumList(
            request.providerInstanceId,
            transport.checkedRequest(
                "getAlbumList2",
                mapOf(
                    "type" to "alphabeticalByName",
                    "size" to size.toString(),
                    "offset" to offset.toString(),
                ),
            ),
        )

    override suspend fun album(rawId: String): LibraryAlbum {
        val summary = AlbumSummary(
            id = ProviderItemId(request.providerInstanceId, rawId),
            title = rawId,
            credits = emptyList(),
            year = null,
            duration = kotlin.time.Duration.ZERO,
            mediaSourceId = null,
            artworkKey = null,
        )
        return parseAlbum(
            request.providerInstanceId,
            summary,
            transport.checkedRequest("getAlbum", mapOf("id" to rawId)),
        )
    }

    override suspend fun playlists(): List<LibraryPlaylistSummary> {
        val payload = syncPayload(transport.checkedRequest("getPlaylists"))
        val container = payload["playlists"] as? JsonObject ?: syncMalformed()
        return container.syncArray("playlist").map { element ->
            val playlist = element as? JsonObject ?: syncMalformed()
            LibraryPlaylistSummary(
                ProviderItemId(request.providerInstanceId, playlist.syncOpaqueId("id")),
                playlist.syncRequiredString("name"),
            )
        }.distinctBy { it.id.rawId }
    }

    override suspend fun playlist(summary: LibraryPlaylistSummary): LibraryPlaylist {
        val payload = syncPayload(
            transport.checkedRequest("getPlaylist", mapOf("id" to summary.id.rawId)),
        )
        val playlist = payload["playlist"] as? JsonObject ?: syncMalformed()
        if (playlist.syncOpaqueId("id") != summary.id.rawId) syncMalformed()
        return LibraryPlaylist(
            summary.copy(name = playlist.syncString("name") ?: summary.name),
            playlist.syncArray("entry").map { element ->
                val entry = element as? JsonObject ?: syncMalformed()
                ProviderItemId(request.providerInstanceId, entry.syncOpaqueId("id"))
            },
        )
    }

    override suspend fun starred(): List<LibraryStarredItem> {
        val payload = syncPayload(transport.checkedRequest("getStarred2"))
        val container = payload["starred2"] as? JsonObject ?: syncMalformed()
        return buildList {
            listOf(
                "artist" to LibraryStarredKind.Artist,
                "album" to LibraryStarredKind.Album,
                "song" to LibraryStarredKind.Track,
            ).forEach { (field, kind) ->
                container.syncArray(field).forEach { element ->
                    val item = element as? JsonObject ?: syncMalformed()
                    add(
                        LibraryStarredItem(
                            kind,
                            ProviderItemId(request.providerInstanceId, item.syncOpaqueId("id")),
                        ),
                    )
                }
            }
        }.distinctBy { it.kind to it.id.rawId }
    }

    override suspend fun genres(): List<LibraryGenre> {
        val payload = syncPayload(transport.checkedRequest("getGenres"))
        val container = payload["genres"] as? JsonObject ?: syncMalformed()
        return container.syncArray("genre").map { element ->
            val genre = element as? JsonObject ?: syncMalformed()
            LibraryGenre(
                name = genre.syncRequiredString("value"),
                songCount = genre.syncNonNegativeLong("songCount"),
                albumCount = genre.syncNonNegativeLong("albumCount"),
            )
        }.distinctBy { it.name }
    }

    override fun close() = transport.close()
}

private fun syncPayload(body: String): JsonObject =
    parseLibraryEnvelope(body)?.payload ?: syncMalformed()

private fun JsonObject.syncArray(name: String): JsonArray = when (val value = get(name)) {
    null -> JsonArray(emptyList())
    is JsonArray -> value
    else -> syncMalformed()
}

private fun JsonObject.syncString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.syncRequiredString(name: String): String =
    syncString(name)?.takeIf(String::isNotBlank) ?: syncMalformed()

private fun JsonObject.syncOpaqueId(name: String): String =
    (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: syncMalformed()

private fun JsonObject.syncNonNegativeLong(name: String): Long {
    val value = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull
        ?.toLongOrNull() ?: syncMalformed()
    return value.takeIf { it >= 0 } ?: syncMalformed()
}

private fun syncMalformed(): Nothing =
    throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)

internal data class LibrarySyncCheckpoint(
    val generation: Long,
    val stage: LibrarySyncStage,
    val cursor: Long,
    val attempt: Int,
    val witness: LibrarySyncWitness,
    val unverified: Boolean,
)

internal data class LibrarySyncWitness(
    val ids: Set<String>,
    val pageCount: Long,
) {
    init {
        require(pageCount >= 0)
    }

    fun encodedIds(): String = JsonArray(ids.sorted().map(::JsonPrimitive)).toString()

    companion object {
        val Empty = LibrarySyncWitness(emptySet(), 0)

        fun decode(encodedIds: String, pageCount: Long): LibrarySyncWitness {
            val array = LIBRARY_JSON.parseToJsonElement(encodedIds) as? JsonArray
                ?: error("Invalid sync checkpoint witness")
            return LibrarySyncWitness(
                array.map { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: error("Invalid sync checkpoint witness id")
                }.toSet(),
                pageCount,
            )
        }
    }
}

internal data class LibraryDeletionReconciliation(
    val generation: Long,
    val rawId: String,
    val downloadedReferenceCount: Long,
    val queueReferenceCount: Long,
)

internal data class CommittedLibrarySnapshot(
    val generation: Long,
    val folderIds: List<String>,
    val artistIds: List<String>,
    val albumIds: List<String>,
    val trackIds: List<String>,
    val playlistIds: List<String>,
    val starredIds: List<String>,
    val genres: List<String>,
)

internal fun interface LibrarySyncCommitProbe {
    fun afterCommittedGenerationUpdate()

    companion object {
        val None = LibrarySyncCommitProbe {}
    }
}

internal enum class LibrarySyncPostCommitSite {
    AfterTransaction,
    BeforeReconciliationDelivery,
    BeforePruning,
}

internal fun interface LibrarySyncPostCommitProbe {
    fun visit(site: LibrarySyncPostCommitSite)

    companion object {
        val None = LibrarySyncPostCommitProbe {}
    }
}

internal data class LibrarySyncCommit(
    val deletionReconciliations: List<LibraryDeletionReconciliation>,
)

internal data class CommittedLibraryBrowseSnapshot(
    val generation: Long,
    val library: LibraryBrowseSnapshot,
)

private data class StoredLibraryCredit(
    val ownerKind: String,
    val ownerRawId: String,
    val credit: Credit,
)

internal class LibrarySyncRepository(
    private val store: DulcetDatabaseStore,
    private val postCommitProbe: LibrarySyncPostCommitProbe = LibrarySyncPostCommitProbe.None,
    private val commitProbe: LibrarySyncCommitProbe = LibrarySyncCommitProbe.None,
) {
    private val database = store.database
    private val queries = database.libraryQueries

    fun checkpoint(serverId: String): LibrarySyncCheckpoint? =
        queries.selectCheckpoint(serverId) { generation, stage, cursor, attempt, ids, pages, unverified ->
            LibrarySyncCheckpoint(
                generation,
                LibrarySyncStage.fromWireName(stage),
                cursor,
                attempt.toInt(),
                LibrarySyncWitness.decode(ids, pages),
                unverified == 1L,
            )
        }.executeAsOneOrNull()

    fun saveCheckpoint(serverId: String, checkpoint: LibrarySyncCheckpoint) {
        queries.saveCheckpoint(
            server_id = serverId,
            generation = checkpoint.generation,
            stage = checkpoint.stage.wireName,
            cursor = checkpoint.cursor,
            attempt = checkpoint.attempt.toLong(),
            witness_ids = checkpoint.witness.encodedIds(),
            witness_page_count = checkpoint.witness.pageCount,
            unverified = if (checkpoint.unverified) 1 else 0,
        )
    }

    fun targetGeneration(): Long = store.metadata().committedGeneration + 1

    fun committedGeneration(): Long = store.metadata().committedGeneration

    fun prepareCheckpoint(serverId: String, restart: Boolean): LibrarySyncCheckpoint =
        database.transactionWithResult {
            val otherServer = queries.selectOtherCheckpointServer(serverId).executeAsOneOrNull()
            check(otherServer == null) {
                "A different provider instance already owns the pending sync generation"
            }
            var existing = checkpoint(serverId)
            if (restart && existing != null) {
                restartGeneration(serverId, existing.generation)
                existing = null
            }
            val target = targetGeneration()
            check(existing == null || existing.generation == target) {
                "The pending sync generation no longer follows the committed generation"
            }
            existing ?: LibrarySyncCheckpoint(
                target,
                LibrarySyncStage.Folders,
                0,
                0,
                LibrarySyncWitness.Empty,
                false,
            ).also { saveCheckpoint(serverId, it) }
        }

    fun albumIds(serverId: String, generation: Long): List<String> =
        queries.selectAlbumIdsAtGeneration(serverId, generation).executeAsList()

    fun seenIds(serverId: String, generation: Long, stage: LibrarySyncStage): Set<String> =
        queries.selectSeenIds(serverId, generation, stage.wireName).executeAsList().toSet()

    fun seenIds(serverId: String, generation: Long, stage: String): Set<String> =
        queries.selectSeenIds(serverId, generation, stage).executeAsList().toSet()

    fun putFolders(serverId: String, generation: Long, values: List<LibraryMusicFolder>) =
        database.transaction {
        values.distinctBy { it.id.rawId }.forEach { value ->
            requireProvider(serverId, value.id)
            val key = contentKey("name" to value.name)
            queries.closeMusicFolderIfChanged(generation, serverId, value.id.rawId, key)
            queries.insertMusicFolderIfAbsent(serverId, value.id.rawId, value.name, key, generation)
            queries.markSeen(serverId, generation, LibrarySyncStage.Folders.wireName, value.id.rawId)
        }
    }

    fun putArtists(serverId: String, generation: Long, values: List<LibraryArtist>) =
        database.transaction {
        values.distinctBy { it.id.rawId }.forEach { value ->
            requireProvider(serverId, value.id)
            val key = contentKey("name" to value.name, "mediaSourceId" to value.mediaSourceId)
            queries.closeArtistIfChanged(generation, serverId, value.id.rawId, key)
            queries.insertArtistIfAbsent(
                serverId, value.id.rawId, value.name, value.mediaSourceId, key, generation,
            )
            queries.markSeen(serverId, generation, LibrarySyncStage.Artists.wireName, value.id.rawId)
        }
    }

    fun putAlbums(serverId: String, generation: Long, values: List<AlbumSummary>) =
        database.transaction {
        values.distinctBy { it.id.rawId }.forEach { value ->
            requireProvider(serverId, value.id)
            value.credits.mapNotNull(Credit::id).forEach { requireProvider(serverId, it) }
            val credit = value.credits.firstOrNull()
            val duration = value.duration.inWholeMilliseconds
            val key = contentKey(
                "title" to value.title,
                "artistName" to credit?.name,
                "artistRawId" to credit?.id?.rawId,
                "year" to value.year,
                "duration" to duration,
                "mediaSourceId" to value.mediaSourceId,
                "artworkKey" to value.artworkKey,
            )
            queries.closeAlbumIfChanged(generation, serverId, value.id.rawId, key)
            queries.insertAlbumIfAbsent(
                serverId, value.id.rawId, value.title, credit?.name, credit?.id?.rawId,
                value.year?.toLong(), duration, value.mediaSourceId, value.artworkKey, key, generation,
            )
            queries.markSeen(serverId, generation, LibrarySyncStage.Albums.wireName, value.id.rawId)
            putCredits(serverId, generation, "album", value.id.rawId, value.credits)
        }
    }

    fun putTracks(serverId: String, generation: Long, albums: List<LibraryAlbum>) =
        database.transaction {
        albums.forEach { album ->
            requireProvider(serverId, album.id)
            album.tracks.forEach { track ->
                requireProvider(serverId, track.id)
                track.credits.mapNotNull(Credit::id).forEach { requireProvider(serverId, it) }
            }
        }
        albums.flatMap { album -> album.tracks.map { album.id.rawId to it } }
            .distinctBy { it.second.id.rawId }
            .forEach { (albumRawId, value) ->
                val credit = value.credits.firstOrNull()
                val duration = value.duration.inWholeMilliseconds
                val container = value.sourceContainer?.wireName()
                val key = contentKey(
                    "albumRawId" to albumRawId,
                    "title" to value.title,
                    "artistName" to credit?.name,
                    "artistRawId" to credit?.id?.rawId,
                    "albumTitle" to value.albumTitle,
                    "disc" to value.discNumber,
                    "track" to value.trackNumber,
                    "duration" to duration,
                    "container" to container,
                    "mediaSourceId" to value.mediaSourceId,
                    "artworkKey" to value.artworkKey,
                )
                queries.closeTrackIfChanged(generation, serverId, value.id.rawId, key)
                queries.insertTrackIfAbsent(
                    serverId, value.id.rawId, albumRawId, value.title, credit?.name,
                    credit?.id?.rawId, value.albumTitle, value.discNumber?.toLong(),
                    value.trackNumber?.toLong(), duration, container, value.mediaSourceId,
                    value.artworkKey, key, generation,
                )
                queries.markSeen(serverId, generation, LibrarySyncStage.Tracks.wireName, value.id.rawId)
                putCredits(serverId, generation, "track", value.id.rawId, value.credits)
            }
    }

    private fun putCredits(
        serverId: String,
        generation: Long,
        ownerKind: String,
        ownerRawId: String,
        credits: List<Credit>,
    ) {
        credits.forEachIndexed { index, credit ->
            val seenKey = tupleKey(ownerKind, ownerRawId, credit.role.wireName(), index.toString())
            val key = contentKey("name" to credit.name, "artistRawId" to credit.id?.rawId)
            queries.closeCreditIfChanged(generation, serverId, seenKey, key)
            queries.insertCreditIfAbsent(
                serverId, seenKey, ownerKind, ownerRawId, credit.role.wireName(), index.toLong(),
                credit.name, credit.id?.rawId, key, generation,
            )
            queries.markSeen(serverId, generation, "$ownerKind:credit", seenKey)
        }
    }

    fun putPlaylists(serverId: String, generation: Long, values: List<LibraryPlaylist>) =
        database.transaction {
        values.distinctBy { it.summary.id.rawId }.forEach { value ->
            requireProvider(serverId, value.summary.id)
            value.trackIds.forEach { requireProvider(serverId, it) }
            val rawId = value.summary.id.rawId
            val playlistKey = contentKey("name" to value.summary.name)
            queries.closePlaylistIfChanged(generation, serverId, rawId, playlistKey)
            queries.insertPlaylistIfAbsent(serverId, rawId, value.summary.name, playlistKey, generation)
            queries.markSeen(serverId, generation, LibrarySyncStage.Playlists.wireName, rawId)
            value.trackIds.forEachIndexed { index, trackId ->
                val seenKey = tupleKey(rawId, index.toString())
                val entryKey = contentKey("trackRawId" to trackId.rawId)
                queries.closePlaylistEntryIfChanged(generation, serverId, seenKey, entryKey)
                if (queries.countTrackAtGeneration(serverId, trackId.rawId, generation)
                        .executeAsOne() > 0
                ) {
                    queries.insertPlaylistEntryIfAbsent(
                        serverId, seenKey, rawId, index.toLong(), trackId.rawId, entryKey, generation,
                    )
                    queries.markSeen(serverId, generation, "playlist:entry", seenKey)
                }
                queries.markSeen(
                    serverId,
                    generation,
                    "playlist:witness",
                    tupleKey(rawId, index.toString(), trackId.rawId),
                )
            }
        }
    }

    fun putStarred(serverId: String, generation: Long, values: List<LibraryStarredItem>) =
        database.transaction {
        values.distinctBy { it.kind to it.id.rawId }.forEach { value ->
            requireProvider(serverId, value.id)
            val seenKey = tupleKey(value.kind.wireName, value.id.rawId)
            val targetExists = when (value.kind) {
                LibraryStarredKind.Artist -> queries.countArtistAtGeneration(
                    serverId, value.id.rawId, generation,
                ).executeAsOne()
                LibraryStarredKind.Album -> queries.countAlbumAtGeneration(
                    serverId, value.id.rawId, generation,
                ).executeAsOne()
                LibraryStarredKind.Track -> queries.countTrackAtGeneration(
                    serverId, value.id.rawId, generation,
                ).executeAsOne()
            } > 0
            if (targetExists) {
                queries.insertStarredIfAbsent(
                    serverId, value.kind.wireName, value.id.rawId, seenKey, generation,
                )
                queries.markSeen(serverId, generation, LibrarySyncStage.Starred.wireName, seenKey)
            }
        }
    }

    fun putGenres(serverId: String, generation: Long, values: List<LibraryGenre>) =
        database.transaction {
        values.distinctBy { it.name }.forEach { value ->
            val key = contentKey("songCount" to value.songCount, "albumCount" to value.albumCount)
            queries.closeGenreIfChanged(generation, serverId, value.name, key)
            queries.insertGenreIfAbsent(
                serverId, value.name, value.songCount, value.albumCount, key, generation,
            )
            queries.markSeen(serverId, generation, LibrarySyncStage.Genres.wireName, value.name)
        }
    }

    fun completeStage(serverId: String, generation: Long, stage: LibrarySyncStage) =
        database.transaction {
        when (stage) {
            LibrarySyncStage.Folders -> queries.closeMissingMusicFolders(generation, serverId, stage.wireName)
            LibrarySyncStage.Artists -> queries.closeMissingArtists(generation, serverId, stage.wireName)
            LibrarySyncStage.Albums -> {
                queries.closeMissingAlbums(generation, serverId, stage.wireName)
                queries.closeMissingCredits(generation, serverId, "album", "album:credit")
            }
            LibrarySyncStage.Tracks -> {
                queries.closeMissingTracks(generation, serverId, stage.wireName)
                queries.closeMissingCredits(generation, serverId, "track", "track:credit")
            }
            LibrarySyncStage.Playlists -> {
                queries.closeMissingPlaylists(generation, serverId, stage.wireName)
                queries.closeMissingPlaylistEntries(generation, serverId, "playlist:entry")
            }
            LibrarySyncStage.Starred -> queries.closeMissingStarred(generation, serverId, stage.wireName)
            LibrarySyncStage.Genres -> queries.closeMissingGenres(generation, serverId, stage.wireName)
        }
    }

    fun resetStage(serverId: String, generation: Long, stage: LibrarySyncStage) {
        database.transaction {
            when (stage) {
                LibrarySyncStage.Folders -> {
                    queries.deleteMusicFoldersFromGeneration(serverId, generation)
                    queries.reopenMusicFoldersClosedAtGeneration(serverId, generation)
                }
                LibrarySyncStage.Artists -> {
                    queries.deleteArtistsFromGeneration(serverId, generation)
                    queries.reopenArtistsClosedAtGeneration(serverId, generation)
                }
                LibrarySyncStage.Albums -> {
                    queries.deleteCreditsFromGenerationByOwnerKind(serverId, "album", generation)
                    queries.reopenCreditsClosedAtGenerationByOwnerKind(serverId, "album", generation)
                    queries.deleteAlbumsFromGeneration(serverId, generation)
                    queries.reopenAlbumsClosedAtGeneration(serverId, generation)
                }
                LibrarySyncStage.Tracks -> {
                    queries.deleteCreditsFromGenerationByOwnerKind(serverId, "track", generation)
                    queries.reopenCreditsClosedAtGenerationByOwnerKind(serverId, "track", generation)
                    queries.deleteTracksFromGeneration(serverId, generation)
                    queries.reopenTracksClosedAtGeneration(serverId, generation)
                }
                LibrarySyncStage.Playlists -> {
                    queries.deletePlaylistEntriesFromGeneration(serverId, generation)
                    queries.reopenPlaylistEntriesClosedAtGeneration(serverId, generation)
                    queries.deletePlaylistsFromGeneration(serverId, generation)
                    queries.reopenPlaylistsClosedAtGeneration(serverId, generation)
                }
                LibrarySyncStage.Starred -> {
                    queries.deleteStarredFromGeneration(serverId, generation)
                    queries.reopenStarredClosedAtGeneration(serverId, generation)
                }
                LibrarySyncStage.Genres -> {
                    queries.deleteGenresFromGeneration(serverId, generation)
                    queries.reopenGenresClosedAtGeneration(serverId, generation)
                }
            }
            queries.clearSeenStage(serverId, generation, stage.wireName)
            if (stage == LibrarySyncStage.Albums) queries.clearSeenStage(serverId, generation, "album:credit")
            if (stage == LibrarySyncStage.Tracks) queries.clearSeenStage(serverId, generation, "track:credit")
            if (stage == LibrarySyncStage.Playlists) {
                queries.clearSeenStage(serverId, generation, "playlist:entry")
                queries.clearSeenStage(serverId, generation, "playlist:witness")
            }
        }
    }

    fun restartGeneration(serverId: String, generation: Long) {
        database.transaction {
            LibrarySyncStage.entries.reversed().forEach { resetStage(serverId, generation, it) }
            queries.deleteDeletionReconciliationsForGeneration(serverId, generation)
            queries.clearSeenGeneration(serverId, generation)
            queries.deleteCheckpoint(serverId)
        }
    }

    fun commit(serverId: String, generation: Long, stability: LibrarySyncStability): LibrarySyncCommit {
        check(store.metadata().committedGeneration + 1 == generation)
        val committed = database.transactionWithResult {
            queries.deleteDeletionReconciliationsForGeneration(serverId, generation)
            queries.insertDeletionReconciliations(generation, serverId)
            queries.insertSyncGeneration(generation, serverId, stability.wireName)
            database.schemaMetaQueries.updateCommittedGeneration(generation)
            commitProbe.afterCommittedGenerationUpdate()
            queries.deleteCheckpoint(serverId)
            queries.clearSeenGeneration(serverId, generation)
            LibrarySyncCommit(
                deletionReconciliations = deletionReconciliations(serverId, generation),
            )
        }
        observePostCommit(LibrarySyncPostCommitSite.AfterTransaction)
        return committed
    }

    fun readCommitted(serverId: String): CommittedLibrarySnapshot =
        database.transactionWithResult {
        val generation = store.metadata().committedGeneration
        CommittedLibrarySnapshot(
            generation = generation,
            folderIds = queries.selectFoldersAtGeneration(serverId, generation).executeAsList().map { it.raw_id },
            artistIds = queries.selectArtistsAtGeneration(serverId, generation).executeAsList().map { it.raw_id },
            albumIds = queries.selectAlbumsAtGeneration(serverId, generation).executeAsList().map { it.raw_id },
            trackIds = queries.selectTracksAtGeneration(serverId, generation).executeAsList().map { it.raw_id },
            playlistIds = queries.selectPlaylistsAtGeneration(serverId, generation).executeAsList().map { it.raw_id },
            starredIds = queries.selectStarredAtGeneration(serverId, generation).executeAsList()
                .map { tupleKey(it.item_kind, it.raw_id) },
            genres = queries.selectGenresAtGeneration(serverId, generation).executeAsList().map { it.name },
        )
    }

    fun readCommittedLibrary(serverId: String): CommittedLibraryBrowseSnapshot =
        database.transactionWithResult {
            val generation = store.metadata().committedGeneration
            val credits = queries.selectCreditsAtGeneration(serverId, generation) {
                    ownerKind, ownerRawId, role, _, name, artistRawId ->
                StoredLibraryCredit(
                    ownerKind = ownerKind,
                    ownerRawId = ownerRawId,
                    credit = Credit(
                        role = when (role) {
                            "artist" -> CreditRole.Artist
                            "album_artist" -> CreditRole.AlbumArtist
                            else -> error("Unknown stored library credit role")
                        },
                        name = name,
                        id = artistRawId?.let { ProviderItemId(serverId, it) },
                    ),
                )
            }.executeAsList().groupBy { it.ownerKind to it.ownerRawId }
            val tracks = queries.selectTracksAtGeneration(serverId, generation) {
                    rawId, albumRawId, title, _, _, albumTitle, discNumber, trackNumber,
                    durationMilliseconds, sourceContainer, mediaSourceId, artworkKey ->
                albumRawId to LibraryTrack(
                    id = ProviderItemId(serverId, rawId),
                    title = title,
                    credits = credits["track" to rawId].orEmpty().map(StoredLibraryCredit::credit),
                    albumTitle = albumTitle,
                    discNumber = discNumber?.toInt(),
                    trackNumber = trackNumber?.toInt(),
                    duration = durationMilliseconds.milliseconds,
                    sourceContainer = sourceContainer?.let(::audioContainerFromWireName),
                    mediaSourceId = mediaSourceId,
                    artworkKey = artworkKey,
                )
            }.executeAsList().groupBy({ it.first }, { it.second })
            CommittedLibraryBrowseSnapshot(
                generation = generation,
                library = LibraryBrowseSnapshot(
                    musicFolders = queries.selectFoldersAtGeneration(serverId, generation) {
                            rawId, name ->
                        LibraryMusicFolder(ProviderItemId(serverId, rawId), name)
                    }.executeAsList(),
                    artists = queries.selectArtistsAtGeneration(serverId, generation) {
                            rawId, name, mediaSourceId ->
                        LibraryArtist(ProviderItemId(serverId, rawId), name, mediaSourceId)
                    }.executeAsList(),
                    albums = queries.selectAlbumsAtGeneration(serverId, generation) {
                            rawId, title, _, _, year, durationMilliseconds, mediaSourceId, artworkKey ->
                        LibraryAlbum(
                            id = ProviderItemId(serverId, rawId),
                            title = title,
                            credits = credits["album" to rawId].orEmpty()
                                .map(StoredLibraryCredit::credit),
                            year = year?.toInt(),
                            duration = durationMilliseconds.milliseconds,
                            mediaSourceId = mediaSourceId,
                            artworkKey = artworkKey,
                            tracks = tracks[rawId].orEmpty(),
                        )
                    }.executeAsList(),
                ),
            )
        }

    fun deletionReconciliations(
        serverId: String,
        generation: Long,
    ): List<LibraryDeletionReconciliation> =
        queries.selectDeletionReconciliationsForGeneration(serverId, generation) {
                noticeGeneration, rawId, downloads, queue ->
            LibraryDeletionReconciliation(noticeGeneration, rawId, downloads, queue)
        }.executeAsList()

    fun visibleDanglingReferenceCount(serverId: String): Long =
        queries.countVisibleTracksWithoutAlbum(serverId).executeAsOne() +
            queries.countVisiblePlaylistEntriesWithoutTrack(serverId).executeAsOne() +
            queries.countVisibleStarredArtistsWithoutTarget(serverId).executeAsOne() +
            queries.countVisibleStarredAlbumsWithoutTarget(serverId).executeAsOne() +
            queries.countVisibleStarredTracksWithoutTarget(serverId).executeAsOne()

    fun pruneClosedVersions() {
        val retentionGeneration = store.metadata().committedGeneration - 2
        if (retentionGeneration < 1) return
        database.transaction {
            queries.prunePlaylistEntries(retentionGeneration)
            queries.pruneCredits(retentionGeneration)
            queries.pruneStarred(retentionGeneration)
            queries.pruneTracks(retentionGeneration)
            queries.prunePlaylists(retentionGeneration)
            queries.pruneAlbums(retentionGeneration)
            queries.pruneArtists(retentionGeneration)
            queries.pruneMusicFolders(retentionGeneration)
            queries.pruneGenres(retentionGeneration)
        }
    }

    fun observePostCommit(site: LibrarySyncPostCommitSite) {
        try {
            postCommitProbe.visit(site)
        } catch (_: Throwable) {
            // A committed generation is already authoritative. Diagnostics cannot revise its result.
        }
    }

    fun pruneClosedVersionsBestEffort() {
        try {
            postCommitProbe.visit(LibrarySyncPostCommitSite.BeforePruning)
            pruneClosedVersions()
        } catch (_: Throwable) {
            // Pruning is maintenance. A later pass may retry it without changing sync success.
        }
    }

    fun writeAtomically(block: () -> Unit) {
        database.transaction { block() }
    }
}

private fun requireProvider(serverId: String, id: ProviderItemId) {
    require(id.providerInstanceId == serverId) {
        "Library item belongs to a different provider instance"
    }
}

private fun contentKey(vararg fields: Pair<String, Any?>): String = buildJsonObject {
    fields.forEach { (name, value) ->
        when (value) {
            null -> put(name, kotlinx.serialization.json.JsonNull)
            is String -> put(name, value)
            is Int -> put(name, value)
            is Long -> put(name, value)
            else -> error("Unsupported library content-key value")
        }
    }
}.toString()

private fun tupleKey(vararg values: String): String =
    JsonArray(values.map(::JsonPrimitive)).toString()

private fun CreditRole.wireName(): String = when (this) {
    CreditRole.Artist -> "artist"
    CreditRole.AlbumArtist -> "album_artist"
}

private fun AudioContainer.wireName(): String = when (this) {
    AudioContainer.Mp3 -> "mp3"
    AudioContainer.Mp4 -> "mp4"
    AudioContainer.Wav -> "wav"
    AudioContainer.Flac -> "flac"
    AudioContainer.Ogg -> "ogg"
    AudioContainer.AdtsAac -> "adts_aac"
}

private fun audioContainerFromWireName(value: String): AudioContainer = when (value) {
    "mp3" -> AudioContainer.Mp3
    "mp4" -> AudioContainer.Mp4
    "wav" -> AudioContainer.Wav
    "flac" -> AudioContainer.Flac
    "ogg" -> AudioContainer.Ogg
    "adts_aac" -> AudioContainer.AdtsAac
    else -> error("Unknown stored audio container")
}

internal sealed interface LibrarySyncResult {
    data class Completed(
        val generation: Long,
        val stability: LibrarySyncStability,
        val deletionReconciliations: List<LibraryDeletionReconciliation>,
    ) : LibrarySyncResult

    data class Failed(val error: DomainError) : LibrarySyncResult
}

internal class LibrarySyncEngine(
    private val repository: LibrarySyncRepository,
    private val albumPageSize: Int = 500,
    private val maxInFlight: Int = 4,
    private val saltSource: SaltSource? = null,
    private val logSink: LogSink? = null,
    private val hostResolver: HostResolver = systemHostResolver(),
    private val syncMutex: Mutex = LibrarySyncCoordinator.syncMutex,
) {
    init {
        require(albumPageSize > 0)
        require(maxInFlight in 1..MAX_IN_FLIGHT_PER_SERVER)
    }

    suspend fun synchronize(
        request: LibraryBrowseRequest,
        restart: Boolean = false,
        progress: (LibrarySyncProgress) -> Unit = {},
    ): LibrarySyncResult {
        val source = HttpLibrarySyncSource(request, saltSource, logSink, hostResolver)
        return try {
            synchronize(request.providerInstanceId, source, restart, progress)
        } finally {
            source.close()
        }
    }

    suspend fun synchronize(
        serverId: String,
        source: LibrarySyncSource,
        restart: Boolean = false,
        progress: (LibrarySyncProgress) -> Unit = {},
    ): LibrarySyncResult = try {
        syncMutex.withLock {
            require(serverId.isNotBlank())
            val isFirstSync = repository.committedGeneration() == 0L
            var checkpoint = repository.prepareCheckpoint(serverId, restart)

            var completed: LibrarySyncResult.Completed? = null
            while (completed == null) {
                progress(
                    LibrarySyncProgress(
                        stage = checkpoint.stage.wireName,
                        completedStageCount = checkpoint.stage.ordinal,
                        totalStageCount = LibrarySyncStage.entries.size,
                        isFirstSync = isFirstSync,
                    ),
                )
                when (checkpoint.stage) {
                    LibrarySyncStage.Folders -> checkpoint = runSingleStage(
                        serverId, checkpoint, source::musicFolders,
                        repository::putFolders,
                    )
                    LibrarySyncStage.Artists -> checkpoint = runSingleStage(
                        serverId, checkpoint, source::artists,
                        repository::putArtists,
                    )
                    LibrarySyncStage.Albums -> checkpoint = runAlbumStage(serverId, checkpoint, source)
                    LibrarySyncStage.Tracks -> checkpoint = runTrackStage(serverId, checkpoint, source)
                    LibrarySyncStage.Playlists -> checkpoint = runPlaylistStage(serverId, checkpoint, source)
                    LibrarySyncStage.Starred -> checkpoint = runSingleStage(
                        serverId, checkpoint, source::starred,
                        repository::putStarred,
                    )
                    LibrarySyncStage.Genres -> {
                        val finished = runSingleStage(
                            serverId, checkpoint, source::genres,
                            repository::putGenres,
                        )
                        val stability = if (finished.unverified) {
                            LibrarySyncStability.Unverified
                        } else {
                            LibrarySyncStability.Verified
                        }
                        val commit = repository.commit(serverId, finished.generation, stability)
                        completed = LibrarySyncResult.Completed(
                            finished.generation, stability, commit.deletionReconciliations,
                        )
                        try {
                            progress(
                                LibrarySyncProgress(
                                    stage = "complete",
                                    completedStageCount = LibrarySyncStage.entries.size,
                                    totalStageCount = LibrarySyncStage.entries.size,
                                    isFirstSync = isFirstSync,
                                ),
                            )
                        } catch (_: Throwable) {
                            // Progress is advisory and cannot revise a durably committed result.
                        }
                    }
                }
            }
            completed
        }
    } catch (_: CancellationException) {
        LibrarySyncResult.Failed(DomainError.Transport.Cancelled)
    } catch (failure: LibraryRequestFailure) {
        LibrarySyncResult.Failed(failure.error)
    } catch (failure: AuthenticatedEndpointFailure) {
        LibrarySyncResult.Failed(failure.error)
    } catch (failure: Throwable) {
        LibrarySyncResult.Failed(mapAccountConnectionFailure(failure))
    }

    private suspend fun <T> runSingleStage(
        serverId: String,
        original: LibrarySyncCheckpoint,
        fetch: suspend () -> List<T>,
        put: (String, Long, List<T>) -> Unit,
    ): LibrarySyncCheckpoint {
        var checkpoint = original
        if (checkpoint.cursor == 0L) {
            repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
            val values = fetch()
            put(serverId, checkpoint.generation, values)
            repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
            checkpoint = checkpoint.copy(
                cursor = 1,
                attempt = 0,
                witness = LibrarySyncWitness(values.syncIds(), 1),
            ).also { repository.saveCheckpoint(serverId, it) }
        }
        var baseline = checkpoint.witness
        var attempt = checkpoint.attempt
        var unverified = checkpoint.unverified
        while (attempt < MAX_STABILITY_ATTEMPTS) {
            attempt += 1
            checkpoint = checkpoint.copy(attempt = attempt)
                .also { repository.saveCheckpoint(serverId, it) }
            val values = fetch()
            val current = LibrarySyncWitness(values.syncIds(), 1)
            if (current == baseline) return advance(serverId, checkpoint.copy(unverified = unverified))
            baseline = current
            checkpoint = checkpoint.copy(witness = baseline)
            repository.writeAtomically {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                put(serverId, checkpoint.generation, values)
                repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.saveCheckpoint(serverId, checkpoint)
            }
        }
        unverified = true
        return advance(serverId, checkpoint.copy(unverified = unverified))
    }

    private suspend fun runAlbumStage(
        serverId: String,
        original: LibrarySyncCheckpoint,
        source: LibrarySyncSource,
    ): LibrarySyncCheckpoint {
        var checkpoint = original
        var baseline: LibrarySyncWitness
        if (checkpoint.attempt == 0) {
            if (checkpoint.cursor == 0L) {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
            }
            if (checkpoint.cursor > 0) {
                val prefix = walkAlbumPages(source, checkpoint.witness.pageCount)
                if (prefix.witness != checkpoint.witness) {
                    repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                    checkpoint = checkpoint.copy(cursor = 0, witness = LibrarySyncWitness.Empty)
                }
            }
            var offset = checkpoint.cursor
            val ids = repository.seenIds(serverId, checkpoint.generation, checkpoint.stage).toMutableSet()
            var pages = checkpoint.witness.pageCount
            while (true) {
                val page = source.albumPage(offset, albumPageSize)
                pages += 1
                repository.putAlbums(serverId, checkpoint.generation, page)
                ids.addAll(page.map { it.id.rawId })
                if (page.size < albumPageSize) break
                offset += albumPageSize
                checkpoint = checkpoint.copy(
                    cursor = offset,
                    witness = LibrarySyncWitness(ids, pages),
                ).also { repository.saveCheckpoint(serverId, it) }
            }
            repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
            baseline = LibrarySyncWitness(ids, pages)
        } else {
            // Each attempt is durably reserved before its fallible walk. The baseline and staged
            // replacement then advance atomically, so a restart can spend at most three attempts.
            baseline = checkpoint.witness
        }
        var attempt = checkpoint.attempt
        while (attempt < MAX_STABILITY_ATTEMPTS) {
            attempt += 1
            checkpoint = checkpoint.copy(attempt = attempt)
                .also { repository.saveCheckpoint(serverId, it) }
            val current = walkAlbumPages(source)
            if (current.witness == baseline) return advance(serverId, checkpoint)
            baseline = current.witness
            checkpoint = checkpoint.copy(cursor = 0, witness = baseline)
            repository.writeAtomically {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.putAlbums(serverId, checkpoint.generation, current.albums)
                repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.saveCheckpoint(serverId, checkpoint)
            }
        }
        return advance(serverId, checkpoint.copy(unverified = true))
    }

    private data class AlbumWalk(val albums: List<AlbumSummary>, val witness: LibrarySyncWitness)

    private suspend fun walkAlbumPages(
        source: LibrarySyncSource,
        pageLimit: Long? = null,
    ): AlbumWalk {
        val albums = mutableListOf<AlbumSummary>()
        val ids = mutableSetOf<String>()
        var offset = 0L
        var pages = 0L
        while (pageLimit == null || pages < pageLimit) {
            val page = source.albumPage(offset, albumPageSize)
            pages += 1
            page.forEach { if (ids.add(it.id.rawId)) albums += it }
            if (page.size < albumPageSize) break
            offset += albumPageSize
        }
        return AlbumWalk(albums, LibrarySyncWitness(ids, pages))
    }

    private suspend fun runTrackStage(
        serverId: String,
        original: LibrarySyncCheckpoint,
        source: LibrarySyncSource,
    ): LibrarySyncCheckpoint {
        var checkpoint = original
        val albumIds = repository.albumIds(serverId, checkpoint.generation)
        var baseline: LibrarySyncWitness
        if (checkpoint.attempt == 0) {
            if (checkpoint.cursor == 0L) {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
            }
            if (checkpoint.cursor > 0) {
                val prefix = fetchAlbums(source, albumIds.take(checkpoint.cursor.toInt()))
                val prefixWitness = trackWitness(prefix, checkpoint.cursor)
                if (prefixWitness != checkpoint.witness) {
                    repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                    checkpoint = checkpoint.copy(cursor = 0, witness = LibrarySyncWitness.Empty)
                }
            }
            var cursor = checkpoint.cursor.toInt()
            val ids = repository.seenIds(serverId, checkpoint.generation, checkpoint.stage).toMutableSet()
            while (cursor < albumIds.size) {
                val chunkIds = albumIds.drop(cursor).take(maxInFlight)
                val albums = fetchAlbums(source, chunkIds)
                repository.putTracks(serverId, checkpoint.generation, albums)
                ids.addAll(albums.flatMap { it.tracks }.map { it.id.rawId })
                cursor += chunkIds.size
                checkpoint = checkpoint.copy(
                    cursor = cursor.toLong(),
                    witness = LibrarySyncWitness(ids, cursor.toLong()),
                ).also { repository.saveCheckpoint(serverId, it) }
            }
            repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
            baseline = LibrarySyncWitness(ids, albumIds.size.toLong())
        } else {
            baseline = checkpoint.witness
        }
        var attempt = checkpoint.attempt
        while (attempt < MAX_STABILITY_ATTEMPTS) {
            attempt += 1
            checkpoint = checkpoint.copy(attempt = attempt)
                .also { repository.saveCheckpoint(serverId, it) }
            val currentAlbums = fetchAlbums(source, albumIds)
            val current = trackWitness(currentAlbums, albumIds.size.toLong())
            if (current == baseline) return advance(serverId, checkpoint)
            baseline = current
            checkpoint = checkpoint.copy(cursor = 0, witness = baseline)
            repository.writeAtomically {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.putTracks(serverId, checkpoint.generation, currentAlbums)
                repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.saveCheckpoint(serverId, checkpoint)
            }
        }
        return advance(serverId, checkpoint.copy(unverified = true))
    }

    private suspend fun fetchAlbums(source: LibrarySyncSource, ids: List<String>): List<LibraryAlbum> =
        buildList {
            ids.chunked(maxInFlight).forEach { chunk ->
                addAll(
                    coroutineScope {
                        chunk.map { id ->
                            async {
                                LibrarySyncCoordinator.requestPermits.withPermit { source.album(id) }
                            }
                        }.awaitAll()
                    },
                )
            }
        }

    private fun trackWitness(albums: List<LibraryAlbum>, pages: Long): LibrarySyncWitness =
        LibrarySyncWitness(albums.flatMap { it.tracks }.map { it.id.rawId }.toSet(), pages)

    private suspend fun runPlaylistStage(
        serverId: String,
        original: LibrarySyncCheckpoint,
        source: LibrarySyncSource,
    ): LibrarySyncCheckpoint {
        var checkpoint = original
        var baseline: LibrarySyncWitness
        if (checkpoint.attempt == 0) {
            if (checkpoint.cursor == 0L) {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
            }
            var summaries = source.playlists().distinctBy { it.id.rawId }
            if (checkpoint.cursor > 0) {
                val prefixSummaries = summaries.take(checkpoint.cursor.toInt())
                val prefix = fetchPlaylists(source, prefixSummaries)
                val prefixWitness = playlistWitness(prefixSummaries, prefix, 1 + checkpoint.cursor)
                if (prefixWitness != checkpoint.witness) {
                    repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                    checkpoint = checkpoint.copy(cursor = 0, witness = LibrarySyncWitness.Empty)
                    summaries = source.playlists().distinctBy { it.id.rawId }
                }
            }
            var cursor = checkpoint.cursor.toInt()
            while (cursor < summaries.size) {
                val chunk = summaries.drop(cursor).take(maxInFlight)
                val playlists = fetchPlaylists(source, chunk)
                repository.putPlaylists(serverId, checkpoint.generation, playlists)
                cursor += chunk.size
                val seenPlaylists = repository.seenIds(
                    serverId, checkpoint.generation, LibrarySyncStage.Playlists,
                )
                val seenEntries = repository.seenIds(
                    serverId, checkpoint.generation, "playlist:witness",
                )
                checkpoint = checkpoint.copy(
                    cursor = cursor.toLong(),
                    witness = LibrarySyncWitness(seenPlaylists + seenEntries, 1 + cursor.toLong()),
                ).also { repository.saveCheckpoint(serverId, it) }
            }
            repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
            baseline = checkpoint.witness.takeIf { summaries.isNotEmpty() }
                ?: LibrarySyncWitness(emptySet(), 1)
        } else {
            baseline = checkpoint.witness
        }
        var attempt = checkpoint.attempt
        while (attempt < MAX_STABILITY_ATTEMPTS) {
            attempt += 1
            checkpoint = checkpoint.copy(attempt = attempt)
                .also { repository.saveCheckpoint(serverId, it) }
            val currentSummaries = source.playlists().distinctBy { it.id.rawId }
            val currentPlaylists = fetchPlaylists(source, currentSummaries)
            val current = playlistWitness(
                currentSummaries, currentPlaylists, 1 + currentSummaries.size.toLong(),
            )
            if (current == baseline) return advance(serverId, checkpoint)
            baseline = current
            checkpoint = checkpoint.copy(cursor = 0, witness = baseline)
            repository.writeAtomically {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.putPlaylists(serverId, checkpoint.generation, currentPlaylists)
                repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.saveCheckpoint(serverId, checkpoint)
            }
        }
        return advance(serverId, checkpoint.copy(unverified = true))
    }

    private suspend fun fetchPlaylists(
        source: LibrarySyncSource,
        summaries: List<LibraryPlaylistSummary>,
    ): List<LibraryPlaylist> = buildList {
        summaries.chunked(maxInFlight).forEach { chunk ->
            addAll(
                coroutineScope {
                    chunk.map { summary ->
                        async {
                            LibrarySyncCoordinator.requestPermits.withPermit {
                                source.playlist(summary)
                            }
                        }
                    }.awaitAll()
                },
            )
        }
    }

    private fun playlistWitness(
        summaries: List<LibraryPlaylistSummary>,
        playlists: List<LibraryPlaylist>,
        pages: Long,
    ): LibrarySyncWitness {
        val ids = summaries.map { it.id.rawId }.toMutableSet()
        playlists.forEach { playlist ->
            playlist.trackIds.forEachIndexed { index, track ->
                ids += tupleKey(playlist.summary.id.rawId, index.toString(), track.rawId)
            }
        }
        return LibrarySyncWitness(ids, pages)
    }

    private fun advance(serverId: String, checkpoint: LibrarySyncCheckpoint): LibrarySyncCheckpoint {
        val next = checkpoint.stage.nextOrNull() ?: return checkpoint
        return LibrarySyncCheckpoint(
            checkpoint.generation, next, 0, 0, LibrarySyncWitness.Empty, checkpoint.unverified,
        ).also { repository.saveCheckpoint(serverId, it) }
    }

    private fun List<*>.syncIds(): Set<String> = map { value ->
        when (value) {
            is LibraryMusicFolder -> value.id.rawId
            is LibraryArtist -> value.id.rawId
            is LibraryStarredItem -> tupleKey(value.kind.wireName, value.id.rawId)
            is LibraryGenre -> value.name
            else -> error("Unsupported library witness value")
        }
    }.toSet()

    private companion object {
        const val MAX_IN_FLIGHT_PER_SERVER = 4
        const val MAX_STABILITY_ATTEMPTS = 3
    }
}

private object LibrarySyncCoordinator {
    // One generation can be pending at a time because committed_generation is a singleton. The
    // database checkpoint ownership check protects separate stores; this mutex also prevents two
    // engine instances in this process from interleaving the same checkpoint.
    val syncMutex = Mutex()
    val controlMutex = Mutex()
    val requestPermits = Semaphore(4)
}

public sealed interface LibrarySyncControlResult {
    public data class GenerationPinnedReads(
        val generationBefore: Long,
        val generationObservedInsideCommit: Long,
        val generationAfter: Long,
        val starredCountBefore: Int,
        val starredCountObservedInsideCommit: Int,
        val starredCountAfter: Int,
    ) : LibrarySyncControlResult

    public data class AtomicCommit(
        val generationBefore: Long,
        val generationAfterInterruptedCommit: Long,
        val generationAfterRetry: Long,
        val interruptedCommitFailed: Boolean,
        val interruptionProbeInvoked: Boolean,
        val oldSnapshotRemainedVisible: Boolean,
    ) : LibrarySyncControlResult

    public data class BoundedWitness(
        val generation: Long,
        val stability: LibrarySyncCompletionStability,
        val albumListWalks: Int,
        val paginationMutations: Int,
        val committedSnapshotMatchesLastWitness: Boolean,
        val danglingReferenceCount: Long,
    ) : LibrarySyncControlResult

    public data class Failed(val error: DomainError) : LibrarySyncControlResult
}

/** Executable contract probes used by CONF-31, CONF-32, and CONF-33. */
public object LibrarySyncContract {
    public const val defaultMaximumInFlightPerServer: Int = 4
    public const val maximumStabilityAttempts: Int = 3

    public suspend fun generationPinnedReads(
        request: LibrarySyncRequest,
    ): LibrarySyncControlResult = runControl(request) { database, source, mutator, albumId ->
        var capture = false
        var observedInside: CommittedLibrarySnapshot? = null
        val observer = LibrarySyncRepository(database.observer)
        val primary = LibrarySyncRepository(database.primary) {
            if (capture) observedInside = observer.readCommitted(request.providerInstanceId)
        }
        val engine = LibrarySyncEngine(primary)
        val initial = engine.synchronize(request.providerInstanceId, source)
            .requireControlCompletion()
        val before = observer.readCommitted(request.providerInstanceId)
        check(initial.generation == before.generation)

        mutator.setAlbumStarred(albumId, starred = true)
        capture = true
        engine.synchronize(request.providerInstanceId, source).requireControlCompletion()
        capture = false
        val inside = requireNotNull(observedInside)
        val after = observer.readCommitted(request.providerInstanceId)
        LibrarySyncControlResult.GenerationPinnedReads(
            generationBefore = before.generation,
            generationObservedInsideCommit = inside.generation,
            generationAfter = after.generation,
            starredCountBefore = before.starredIds.size,
            starredCountObservedInsideCommit = inside.starredIds.size,
            starredCountAfter = after.starredIds.size,
        )
    }

    public suspend fun atomicCommit(
        request: LibrarySyncRequest,
    ): LibrarySyncControlResult = runControl(request) { database, source, mutator, albumId ->
        var interrupt = false
        var probeInvoked = false
        val observer = LibrarySyncRepository(database.observer)
        val primary = LibrarySyncRepository(database.primary) {
            if (interrupt) {
                probeInvoked = true
                throw AtomicControlInterruption
            }
        }
        val initialEngine = LibrarySyncEngine(primary)
        initialEngine.synchronize(request.providerInstanceId, source).requireControlCompletion()
        val before = observer.readCommitted(request.providerInstanceId)

        mutator.setAlbumStarred(albumId, starred = true)
        interrupt = true
        val interrupted = initialEngine.synchronize(request.providerInstanceId, source)
        interrupt = false
        val afterInterrupted = observer.readCommitted(request.providerInstanceId)

        val retryRepository = LibrarySyncRepository(database.primary)
        LibrarySyncEngine(retryRepository)
            .synchronize(request.providerInstanceId, source)
            .requireControlCompletion()
        val afterRetry = observer.readCommitted(request.providerInstanceId)
        LibrarySyncControlResult.AtomicCommit(
            generationBefore = before.generation,
            generationAfterInterruptedCommit = afterInterrupted.generation,
            generationAfterRetry = afterRetry.generation,
            interruptedCommitFailed = interrupted is LibrarySyncResult.Failed,
            interruptionProbeInvoked = probeInvoked,
            oldSnapshotRemainedVisible = afterInterrupted == before,
        )
    }

    public suspend fun boundedStabilityWitness(
        request: LibrarySyncRequest,
    ): LibrarySyncControlResult = runControl(request) { database, source, _, _ ->
        val mutatingSource = AlternatingAlbumPaginationSource(source)
        val repository = LibrarySyncRepository(database.primary)
        val completed = LibrarySyncEngine(repository, albumPageSize = 1)
            .synchronize(request.providerInstanceId, mutatingSource)
            .requireControlCompletion()
        val committed = repository.readCommitted(request.providerInstanceId)
        LibrarySyncControlResult.BoundedWitness(
            generation = completed.generation,
            stability = when (completed.stability) {
                LibrarySyncStability.Verified -> LibrarySyncCompletionStability.Verified
                LibrarySyncStability.Unverified -> LibrarySyncCompletionStability.Unverified
            },
            albumListWalks = mutatingSource.completedWalks,
            paginationMutations = mutatingSource.paginationMutations,
            committedSnapshotMatchesLastWitness =
                committed.albumIds.toSet() == mutatingSource.lastCompletedIds,
            danglingReferenceCount =
                repository.visibleDanglingReferenceCount(request.providerInstanceId),
        )
    }

    private suspend fun runControl(
        request: LibrarySyncRequest,
        body: suspend (
            LibrarySyncControlDatabase,
            HttpLibrarySyncSource,
            LibrarySyncMutationTransport,
            String,
        ) -> LibrarySyncControlResult,
    ): LibrarySyncControlResult = LibrarySyncCoordinator.controlMutex.withLock {
        var database: LibrarySyncControlDatabase? = null
        var source: HttpLibrarySyncSource? = null
        var mutator: LibrarySyncMutationTransport? = null
        var albumId: String? = null
        try {
            requireDisposableLoopback(request)
            database = createLibrarySyncControlDatabase()
            source = HttpLibrarySyncSource(
                request.asBrowseRequest(),
                saltSource = null,
                logSink = null,
                hostResolver = systemHostResolver(),
            )
            mutator = LibrarySyncMutationTransport(request.asBrowseRequest())
            albumId = source.albumPage(0, 1).singleOrNull()?.id?.rawId
                ?: error("Disposable library has no album for the sync controls")
            mutator.setAlbumStarred(albumId, starred = false)
            body(database, source, mutator, albumId)
        } catch (failure: LibraryRequestFailure) {
            LibrarySyncControlResult.Failed(failure.error)
        } catch (failure: AuthenticatedEndpointFailure) {
            LibrarySyncControlResult.Failed(failure.error)
        } catch (failure: LibraryControlFailure) {
            LibrarySyncControlResult.Failed(failure.domainError)
        } catch (failure: Throwable) {
            LibrarySyncControlResult.Failed(mapAccountConnectionFailure(failure))
        } finally {
            if (albumId != null) {
                try {
                    mutator?.setAlbumStarred(albumId, starred = false)
                } catch (_: Throwable) {
                    // The primary control result remains authoritative; the environment is disposable.
                }
            }
            try {
                mutator?.close()
            } catch (_: Throwable) {
            }
            try {
                source?.close()
            } catch (_: Throwable) {
            }
            try {
                database?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun requireDisposableLoopback(request: LibrarySyncRequest) {
        require(request.allowLocalHttp)
        require(Regex("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}").matches(request.normalizedBaseUrl)) {
            "Library sync controls require a declared disposable loopback instance"
        }
    }

    private fun LibrarySyncResult.requireControlCompletion(): LibrarySyncResult.Completed =
        when (this) {
            is LibrarySyncResult.Completed -> this
            is LibrarySyncResult.Failed -> throw LibraryControlFailure(error)
        }
}

private class LibrarySyncMutationTransport(request: LibraryBrowseRequest) {
    private val transport = KtorLibraryEndpointTransport(
        request,
        saltSource = null,
        logSink = null,
        hostResolver = systemHostResolver(),
    )

    suspend fun setAlbumStarred(rawId: String, starred: Boolean) {
        transport.checkedRequest(
            endpoint = if (starred) "star" else "unstar",
            parameters = mapOf("albumId" to rawId),
        )
    }

    fun close() = transport.close()
}

private class AlternatingAlbumPaginationSource(
    private val delegate: LibrarySyncSource,
) : LibrarySyncSource by delegate {
    var completedWalks: Int = 0
        private set
    var paginationMutations: Int = 0
        private set
    var lastCompletedIds: Set<String> = emptySet()
        private set

    private var shifted = false
    private val currentIds = mutableSetOf<String>()

    override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> {
        check(size == 1) { "CONF-33 requires single-row pages to force offset movement" }
        val effectiveOffset = offset + if (shifted) 1 else 0
        val page = delegate.albumPage(effectiveOffset, size)
        currentIds += page.map { it.id.rawId }
        if (offset == 0L) {
            // Model an insertion/removal ahead of the offset immediately after page zero. The next
            // offset can now omit a real row, which deduplication alone cannot recover.
            shifted = !shifted
            paginationMutations += 1
        }
        if (page.size < size) {
            completedWalks += 1
            lastCompletedIds = currentIds.toSet()
            currentIds.clear()
        }
        return page
    }
}

private object AtomicControlInterruption : RuntimeException()

private class LibraryControlFailure(val domainError: DomainError) : RuntimeException()
