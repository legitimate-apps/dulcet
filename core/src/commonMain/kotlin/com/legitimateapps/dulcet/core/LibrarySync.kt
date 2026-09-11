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
import kotlin.time.Duration.Companion.seconds

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

/**
 * One track exactly as the songs walk returns it.
 *
 * The walk enumerates songs, not albums, so the album a track belongs to arrives as a field on the
 * row — `Child.albumId` — instead of being implied by the request that produced it.
 */
internal data class LibraryTrackRow(
    val albumRawId: String,
    val track: LibraryTrack,
)

/** The three entity walks a full import is made of. One request asks for exactly one of them. */
internal enum class LibraryEnumerationKind(
    val countParameter: String,
    val offsetParameter: String,
    val arrayName: String,
) {
    Artist("artistCount", "artistOffset", "artist"),
    Album("albumCount", "albumOffset", "album"),
    Song("songCount", "songOffset", "song"),
}

/**
 * What one whole-library enumeration probe measured.
 *
 * [enumeratedAlbumCount] cannot answer the question on its own: an empty library and a server that
 * does not implement empty-query enumeration both return zero rows with `status="ok"`.
 * [knownPositiveAlbumCount] comes from `getAlbumList2`, which every Subsonic server implements, and
 * is the only thing that separates the two.
 */
internal data class LibraryEnumerationProbe(
    val knownPositiveAlbumCount: Int,
    val enumeratedAlbumCount: Int,
) {
    init {
        require(knownPositiveAlbumCount >= 0 && enumeratedAlbumCount >= 0)
    }

    /**
     * False only when the server demonstrably has albums and enumerates none of them. Importing in
     * that state would replace a full library with an empty one and report success.
     */
    val enumeratesWholeLibrary: Boolean
        get() = knownPositiveAlbumCount == 0 || enumeratedAlbumCount > 0
}

/**
 * The largest page any whole-library walk asks for.
 *
 * **OBSERVED 2026-09-11, Navidrome 0.63.2, a 2,500-album library:** `search3` does *not* clamp its
 * counts — `albumCount=501` returns 501, `1000` returns 1,000, and `5000` returns every album the
 * library held.
 * `getAlbumList2` does, silently: `size=501` and `size=1000` both return exactly 500 rows with
 * `status="ok"` and nothing marking the truncation.
 *
 * 500 is also what the protocol promises: `getAlbumList2`'s `size` is documented "The number of
 * albums to return. Max 500." (OBSERVED 2026-09-11, subsonic.org/pages/api.jsp), while `search3`'s
 * counts document no maximum at all. A walk must never depend on being able to ask for more than
 * the documented limit — other servers are reported to clamp there too, and a clamp is invisible.
 */
internal const val MAX_LIBRARY_ENUMERATION_PAGE_SIZE: Int = 500

/**
 * The literal two characters `""`.
 *
 * OpenSubsonic requires a server to enumerate everything for an empty query, but it layers that on
 * a base specification where `query` is mandatory, so servers disagree about which spelling of
 * "empty" they accept. **OBSERVED 2026-09-11:** on the reference server `""`, the bare empty value,
 * `" "` and `"*"` all enumerate identically, so the choice costs nothing there; the quoted form is
 * chosen because it is the spelling other servers are REPORTED to accept, which is a claim about
 * them that this project has not measured.
 */
internal const val WHOLE_LIBRARY_QUERY: String = "\"\""

/**
 * `search3` parameters for one page of one whole-library walk.
 *
 * The other two counts are zeroed deliberately. **OBSERVED 2026-09-11, Navidrome 0.63.2:** a zero
 * count removes that entity from the response entirely — no `artist` or `song` key at all, not an
 * empty array — so a page carries only the entity it asked for and nothing is paid for the rest.
 */
internal fun libraryEnumerationParameters(
    kind: LibraryEnumerationKind,
    offset: Long,
    size: Int,
): Map<String, String> {
    require(offset >= 0) { "A whole-library walk never reads backwards" }
    require(size in 1..MAX_LIBRARY_ENUMERATION_PAGE_SIZE)
    return buildMap {
        put("query", WHOLE_LIBRARY_QUERY)
        LibraryEnumerationKind.entries.forEach { entry ->
            put(entry.countParameter, if (entry == kind) size.toString() else "0")
        }
        put(kind.offsetParameter, offset.toString())
    }
}

/**
 * The fill transport for a durable import.
 *
 * Artists, albums and tracks are read as three independent whole-library walks — one entity per
 * request — and never one request per album. `getAlbum` is not in this interface at all: an album's
 * track list is read when somebody opens that album, which is a browse concern, not a sync one.
 */
internal interface LibrarySyncSource {
    suspend fun musicFolders(): List<LibraryMusicFolder>

    /** Reads whether this server can enumerate its whole library, against a known positive. */
    suspend fun probeEnumeration(): LibraryEnumerationProbe
    suspend fun artistPage(offset: Long, size: Int): List<LibraryArtist>
    suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary>
    suspend fun trackPage(offset: Long, size: Int): List<LibraryTrackRow>
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

    /**
     * Asks the known positive first, so a server that answers neither is reported as unreachable or
     * unauthenticated by that request, rather than as a missing capability.
     */
    override suspend fun probeEnumeration(): LibraryEnumerationProbe {
        val knownPositive = parseAlbumList(
            request.providerInstanceId,
            transport.checkedRequest(
                "getAlbumList2",
                mapOf("type" to "alphabeticalByName", "size" to "1", "offset" to "0"),
            ),
        )
        val enumerated = enumeratedAlbums(
            transport.checkedRequest(
                "search3",
                libraryEnumerationParameters(LibraryEnumerationKind.Album, offset = 0, size = 1),
            ),
        )
        return LibraryEnumerationProbe(knownPositive.size, enumerated.size)
    }

    override suspend fun artistPage(offset: Long, size: Int): List<LibraryArtist> =
        parseEnumeratedArtists(
            request.providerInstanceId,
            transport.checkedRequest(
                "search3",
                libraryEnumerationParameters(LibraryEnumerationKind.Artist, offset, size),
            ),
        )

    override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> =
        enumeratedAlbums(
            transport.checkedRequest(
                "search3",
                libraryEnumerationParameters(LibraryEnumerationKind.Album, offset, size),
            ),
        )

    override suspend fun trackPage(offset: Long, size: Int): List<LibraryTrackRow> =
        parseEnumeratedTracks(
            request.providerInstanceId,
            transport.checkedRequest(
                "search3",
                libraryEnumerationParameters(LibraryEnumerationKind.Song, offset, size),
            ),
        )

    private fun enumeratedAlbums(body: String): List<AlbumSummary> =
        parseEnumeratedAlbums(request.providerInstanceId, body)

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

private fun String.searchResultContainer(): JsonObject =
    syncPayload(this)["searchResult3"] as? JsonObject ?: syncMalformed()

private fun String.enumeratedRows(kind: LibraryEnumerationKind): List<JsonObject> =
    searchResultContainer().syncArray(kind.arrayName).map { it as? JsonObject ?: syncMalformed() }

/**
 * Artists from one page of the artists walk.
 *
 * The fields are the two `getArtists` supplies through [parseArtists] — a stable opaque id and a
 * name. `search3` pages artists; `getArtists` cannot page at all, and returns every artist in one
 * indivisible response.
 */
internal fun parseEnumeratedArtists(
    providerInstanceId: String,
    body: String,
): List<LibraryArtist> = body.enumeratedRows(LibraryEnumerationKind.Artist).map { artist ->
    LibraryArtist(
        id = ProviderItemId(providerInstanceId, artist.syncOpaqueId("id")),
        name = artist.syncRequiredString("name"),
        mediaSourceId = null,
    )
}.distinctBy { it.id.rawId }

/**
 * Albums from one page of the albums walk.
 *
 * Every field is read by the same rule [parseAlbumList] applies to a `getAlbumList2` row, because
 * both endpoints build the same `AlbumID3` object on the reference server. Equivalence is not left
 * as an assumption: `LibrarySyncTransportTest.bothParsersReadOneServerRowToTheSameValues` runs one
 * server row through both functions and requires the whole objects to be equal, so a field added to
 * one parser and not the other fails at the moment it is introduced.
 */
internal fun parseEnumeratedAlbums(
    providerInstanceId: String,
    body: String,
): List<AlbumSummary> = body.enumeratedRows(LibraryEnumerationKind.Album).map { album ->
    AlbumSummary(
        id = ProviderItemId(providerInstanceId, album.syncOpaqueId("id")),
        title = album.syncRequiredString("name"),
        credits = album.syncCredit(providerInstanceId, CreditRole.AlbumArtist),
        year = album.syncInt("year"),
        duration = album.syncDuration(),
        mediaSourceId = null,
        artworkKey = album.syncOptionalOpaqueId("coverArt"),
    )
}

/**
 * Tracks from one page of the songs walk, each carrying the album it belongs to.
 *
 * A song without an `albumId` is rejected rather than dropped. Dropping it would silently shrink
 * the library by exactly the rows this transport cannot file, which is the failure this whole
 * change exists to make impossible; rejecting it fails the import with a named protocol error.
 */
internal fun parseEnumeratedTracks(
    providerInstanceId: String,
    body: String,
): List<LibraryTrackRow> = body.enumeratedRows(LibraryEnumerationKind.Song).map { track ->
    LibraryTrackRow(
        albumRawId = track.syncOpaqueId("albumId"),
        track = LibraryTrack(
            id = ProviderItemId(providerInstanceId, track.syncOpaqueId("id")),
            title = track.syncRequiredString("title"),
            credits = track.syncCredit(providerInstanceId, CreditRole.Artist),
            albumTitle = track.syncString("album"),
            discNumber = track.syncInt("discNumber"),
            trackNumber = track.syncInt("track"),
            duration = track.syncDuration(),
            sourceContainer = track.syncAudioContainer(),
            mediaSourceId = null,
            artworkKey = track.syncOptionalOpaqueId("coverArt"),
        ),
    )
}

private fun JsonObject.syncOptionalOpaqueId(name: String): String? {
    val value = get(name) ?: return null
    val primitive = value as? JsonPrimitive ?: syncMalformed()
    return primitive.contentOrNull?.takeIf(String::isNotBlank) ?: syncMalformed()
}

private fun JsonObject.syncInt(name: String): Int? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonObject.syncDuration(): kotlin.time.Duration = when (val seconds = syncInt("duration")) {
    null -> kotlin.time.Duration.ZERO
    in 0..Int.MAX_VALUE -> seconds.seconds
    else -> syncMalformed()
}

private fun JsonObject.syncCredit(providerInstanceId: String, role: CreditRole): List<Credit> {
    val name = syncString("artist")?.takeIf(String::isNotBlank) ?: return emptyList()
    return listOf(
        Credit(
            role = role,
            name = name,
            id = syncOptionalOpaqueId("artistId")?.let { ProviderItemId(providerInstanceId, it) },
        ),
    )
}

private fun JsonObject.syncAudioContainer(): AudioContainer? {
    syncString("suffix")?.lowercase()?.let { suffix ->
        when (suffix) {
            "mp3" -> return AudioContainer.Mp3
            "mp4", "m4a" -> return AudioContainer.Mp4
            "wav", "wave" -> return AudioContainer.Wav
            "flac" -> return AudioContainer.Flac
            "ogg", "oga", "opus" -> return AudioContainer.Ogg
            "aac", "adts" -> return AudioContainer.AdtsAac
        }
    }
    return when (syncString("contentType")?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> AudioContainer.Mp3
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> AudioContainer.Mp4
        "audio/wav", "audio/wave", "audio/x-wav" -> AudioContainer.Wav
        "audio/flac", "audio/x-flac" -> AudioContainer.Flac
        "audio/ogg", "application/ogg" -> AudioContainer.Ogg
        "audio/aac", "audio/aacp" -> AudioContainer.AdtsAac
        else -> null
    }
}

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

    /**
     * Albums this generation holds that no track in this generation names.
     *
     * [albumCount] is passed in rather than queried because the caller already has it, and because
     * counting from the track side keeps this O(tracks): every stored track's album is one of those
     * albums by construction, so the albums with tracks are exactly the distinct album ids the
     * track table carries at this generation.
     */
    fun albumsWithoutTracks(serverId: String, generation: Long, albumCount: Long): Long =
        albumCount - queries.countAlbumsWithTracksAtGeneration(serverId, generation).executeAsOne()

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
                normalizeSearchText(value.name),
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
                normalizeSearchText(value.title),
            )
            queries.markSeen(serverId, generation, LibrarySyncStage.Albums.wireName, value.id.rawId)
            putCredits(serverId, generation, "album", value.id.rawId, value.credits)
        }
    }

    fun putTracks(serverId: String, generation: Long, rows: List<LibraryTrackRow>) =
        database.transaction {
        rows.forEach { row ->
            requireProvider(serverId, row.track.id)
            row.track.credits.mapNotNull(Credit::id).forEach { requireProvider(serverId, it) }
        }
        rows.distinctBy { it.track.id.rawId }
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
                    normalizeSearchText(value.title), normalizeSearchText(value.albumTitle.orEmpty()),
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
                credit.name, credit.id?.rawId, key, generation, normalizeSearchText(credit.name),
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

internal fun audioContainerFromWireName(value: String): AudioContainer = when (value) {
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

/**
 * A durable, generation-pinned import.
 *
 * The fill transport is three whole-library walks — artists, then albums, then songs — and the
 * request count is therefore a function of the library's *page* count, never of its album count.
 * Reconstructing each album's track list from the songs walk replaces one `getAlbum` per album:
 * **OBSERVED 2026-09-11** against Navidrome 0.63.2 holding 2,500 albums / 5,000 songs: one full
 * import costs 48 requests through the walks against 5,022 through `getAlbum`, 1.97 s against
 * 9.20 s on loopback, and the two commit byte-identical libraries — every stored field of every
 * album, track and artist, on that library and on the conformance fixture corpus.
 */
internal class LibrarySyncEngine(
    private val repository: LibrarySyncRepository,
    private val enumerationPageSize: Int = MAX_LIBRARY_ENUMERATION_PAGE_SIZE,
    private val maxInFlight: Int = 4,
    private val maxEnumerationPages: Long = DEFAULT_MAX_ENUMERATION_PAGES,
    private val saltSource: SaltSource? = null,
    private val logSink: LogSink? = null,
    private val hostResolver: HostResolver = systemHostResolver(),
    private val syncMutex: Mutex = LibrarySyncCoordinator.syncMutex,
) {
    init {
        require(enumerationPageSize in 1..MAX_LIBRARY_ENUMERATION_PAGE_SIZE)
        require(maxInFlight in 1..MAX_IN_FLIGHT_PER_SERVER)
        require(maxEnumerationPages >= 1)
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
            requireWholeLibraryEnumeration(source)

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
                    LibrarySyncStage.Artists -> checkpoint = runPagedStage(
                        serverId, checkpoint, { artist: LibraryArtist -> artist.id.rawId },
                        repository::putArtists,
                    ) { offset, size -> SourcePage(source.artistPage(offset, size)) }
                    LibrarySyncStage.Albums -> checkpoint = runPagedStage(
                        serverId, checkpoint, { album: AlbumSummary -> album.id.rawId },
                        repository::putAlbums,
                    ) { offset, size -> SourcePage(source.albumPage(offset, size)) }
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
            // Persist the baseline before the attempt, so a resume compares against the same
            // witness this attempt is about to test. ⚠️ This stage has no pages: the paged version
            // of this comment in `runPagedStage` explains an empty page ending a fill walk, and
            // that reasoning does NOT apply here. It was copy-pasted; the line's purpose is the
            // one above.
            checkpoint = checkpoint.copy(attempt = attempt, witness = baseline)
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

    /**
     * Fails the import when the server demonstrably has albums and enumerates none of them.
     *
     * Two requests per import buy the one thing a walk cannot tell on its own: zero rows from the
     * empty query means "this library is empty" on a server that implements whole-library
     * enumeration and "I do not implement it" on a server that does not. Without the known positive
     * the second case commits an empty generation over a full one, closes every row, reports
     * success — and reads, in every instrument anyone would look at, exactly like an empty library.
     */
    private suspend fun requireWholeLibraryEnumeration(source: LibrarySyncSource) {
        if (!source.probeEnumeration().enumeratesWholeLibrary) {
            throw LibraryRequestFailure(
                DomainError.CapabilityUnsupported(CapabilityFeature.LibrarySync),
            )
        }
    }

    /** One page as the server returned it: [rowCount] is its size, whatever we chose to keep. */
    private data class SourcePage<T>(val values: List<T>, val rowCount: Int = values.size) {
        init {
            require(rowCount >= values.size)
        }
    }

    private data class PagedWalk<T>(val values: List<T>, val witness: LibrarySyncWitness)

    /**
     * One resumable whole-library walk, written page by page, then re-walked until its witness is
     * stable (§16.4).
     *
     * The re-walk is what makes the witness affordable: it is one more pass over the same pages —
     * seven requests for albums at the design's target scale, six data pages and the empty page
     * that ends the walk — where the previous transport re-read every album individually, two to
     * four times over.
     */
    private suspend fun <T> runPagedStage(
        serverId: String,
        original: LibrarySyncCheckpoint,
        identity: (T) -> String,
        put: (String, Long, List<T>) -> Unit,
        // Evaluated once, immediately before the stage advances, so a stage that learned something
        // during its walk records it in the SAME checkpoint write that moves the stage on. Written
        // afterwards it would be a second, separate write, and a process death between the two
        // would lose it and commit the generation as verified.
        unverifiedAfterWalk: () -> Boolean = { false },
        // 🚨 A stage's completeness gate must run BEFORE the stage advances, for the same reason
        // and with sharper consequences. [advance] durably writes a checkpoint naming the NEXT
        // stage; a gate that throws after that write leaves a checkpoint which resumes past its own
        // stage, so the next `synchronize` never enters it and the gate never runs again. A gate
        // disarmed by retrying is worse than no gate: it turns a loud failure into a silent
        // success on the second press of the button.
        requireWalkComplete: () -> Unit = {},
        fetch: suspend (Long, Int) -> SourcePage<T>,
    ): LibrarySyncCheckpoint {
        var checkpoint = original
        var baseline: LibrarySyncWitness
        if (checkpoint.attempt == 0) {
            if (checkpoint.cursor == 0L) {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
            }
            if (checkpoint.cursor > 0) {
                val prefix = walkPages(identity, fetch, pageLimit = checkpoint.witness.pageCount)
                if (prefix.witness != checkpoint.witness) {
                    repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                    checkpoint = checkpoint.copy(cursor = 0, witness = LibrarySyncWitness.Empty)
                }
            }
            var offset = checkpoint.cursor
            val ids = repository.seenIds(serverId, checkpoint.generation, checkpoint.stage).toMutableSet()
            var pages = checkpoint.witness.pageCount
            while (true) {
                val page = fetch(offset, enumerationPageSize)
                pages += 1
                put(serverId, checkpoint.generation, page.values)
                ids.addAll(page.values.map(identity))
                if (page.rowCount == 0) break
                requireTerminableWalk(pages)
                offset += page.rowCount
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
            // The witness written here is the COMPLETE baseline, including the empty page that
            // ended the fill walk — the checkpoints written during the fill deliberately count only
            // the pages that advanced the offset. A resumed witness compares against whatever this
            // row says, so storing the fill's undercount makes the first resumed attempt unable to
            // match a walk that has not changed, and spends one of three attempts proving it.
            checkpoint = checkpoint.copy(attempt = attempt, witness = baseline)
                .also { repository.saveCheckpoint(serverId, it) }
            val current = walkPages(identity, fetch)
            if (current.witness == baseline) {
                return advanceCompleteStage(
                    serverId, checkpoint, requireWalkComplete, unverifiedAfterWalk,
                )
            }
            baseline = current.witness
            checkpoint = checkpoint.copy(cursor = 0, witness = baseline)
            repository.writeAtomically {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                put(serverId, checkpoint.generation, current.values)
                repository.completeStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.saveCheckpoint(serverId, checkpoint)
            }
        }
        return advanceCompleteStage(
            serverId, checkpoint.copy(unverified = true), requireWalkComplete, unverifiedAfterWalk,
        )
    }

    /**
     * Runs the stage's completeness gate, then advances — in that order, and never the reverse.
     *
     * A rejected stage is rolled back to a state a later attempt can re-walk from scratch, in one
     * transaction, before the failure propagates. Rejecting without the rollback is not enough: the
     * durable checkpoint would keep the attempt count the rejected walk spent, and once that
     * reaches the bound the stage can never be re-filled — not even against a healthy server —
     * because the fill only runs at `attempt == 0`. No shipping caller passes `restart = true`, so
     * "the user can force a full rescan" is not an answer available to them.
     */
    private fun advanceCompleteStage(
        serverId: String,
        checkpoint: LibrarySyncCheckpoint,
        requireWalkComplete: () -> Unit,
        unverifiedAfterWalk: () -> Boolean,
    ): LibrarySyncCheckpoint {
        try {
            requireWalkComplete()
        } catch (rejection: LibraryRequestFailure) {
            repository.writeAtomically {
                repository.resetStage(serverId, checkpoint.generation, checkpoint.stage)
                repository.saveCheckpoint(
                    serverId,
                    LibrarySyncCheckpoint(
                        generation = checkpoint.generation,
                        stage = checkpoint.stage,
                        cursor = 0,
                        attempt = 0,
                        witness = LibrarySyncWitness.Empty,
                        unverified = checkpoint.unverified,
                    ),
                )
            }
            throw rejection
        }
        return advance(serverId, checkpoint.markUnverified(unverifiedAfterWalk))
    }

    private fun LibrarySyncCheckpoint.markUnverified(decide: () -> Boolean): LibrarySyncCheckpoint =
        if (unverified || !decide()) this else copy(unverified = true)

    /**
     * Walks every page of one entity from offset zero, obeying two rules that never trust the size
     * the request asked for.
     *
     * 1. **The offset advances by the number of rows the server returned**, never by the number
     *    requested. A server may serve fewer rows than asked: OBSERVED 2026-09-11 against the
     *    reference server, `getAlbumList2` silently caps `size` at 500, answering `status="ok"`
     *    with no marker of the truncation.
     * 2. **Only an empty page ends the walk.** A short page is a *candidate* end and is corroborated
     *    by one more request, because a page can be short for two unrelated reasons — the list
     *    ended, or the server served fewer rows than asked — and nothing in the response
     *    distinguishes them. Trusting the first reading truncates the library on the second: a
     *    single short page anywhere in the walk would silently drop every row after it, and the
     *    walk *becomes the library*.
     *
     * The corroborating request is not new cost in the common case — a walk whose row count is an
     * exact multiple of the page size has always paid it — and one extra request per walk is
     * nothing against the thing it makes impossible.
     *
     * There is deliberately no rule ending the walk on a page that contributes no new id, either:
     * a page of ids already seen is what an insertion during the pass looks like. A walk the server
     * will not let terminate fails instead (see [requireTerminableWalk]).
     */
    private suspend fun <T> walkPages(
        identity: (T) -> String,
        fetch: suspend (Long, Int) -> SourcePage<T>,
        pageLimit: Long? = null,
    ): PagedWalk<T> {
        val values = mutableListOf<T>()
        val ids = mutableSetOf<String>()
        var offset = 0L
        var pages = 0L
        while (pageLimit == null || pages < pageLimit) {
            val page = fetch(offset, enumerationPageSize)
            pages += 1
            page.values.forEach { if (ids.add(identity(it))) values += it }
            if (page.rowCount == 0) break
            requireTerminableWalk(pages)
            offset += page.rowCount
        }
        return PagedWalk(values, LibrarySyncWitness(ids, pages))
    }

    private fun requireTerminableWalk(pages: Long) {
        if (pages < maxEnumerationPages) return
        throw LibraryRequestFailure(DomainError.CapabilityUnsupported(CapabilityFeature.LibrarySync))
    }

    /**
     * The songs walk, filed under the albums this generation already holds.
     *
     * A song whose `albumId` is not in this generation's album set is a row the albums walk never
     * saw — the library gained an album between the two walks. Storing it would leave a track
     * pointing at no album, which §16.5's reconciliation rules count as a dangling reference;
     * dropping it quietly would make an incomplete import look complete. It is dropped **and** the
     * generation is marked `unverified`, which is the existing "the library changed during the
     * scan, here is a rescan" result rather than a new one.
     */
    private suspend fun runTrackStage(
        serverId: String,
        original: LibrarySyncCheckpoint,
        source: LibrarySyncSource,
    ): LibrarySyncCheckpoint {
        val albumIds = repository.albumIds(serverId, original.generation).toSet()
        var droppedRows = 0L
        var tracklessAlbums = 0L
        return runPagedStage(
            serverId,
            original,
            { row: LibraryTrackRow -> row.track.id.rawId },
            repository::putTracks,
            unverifiedAfterWalk = { droppedRows > 0L || tracklessAlbums > 0L },
            requireWalkComplete = {
                tracklessAlbums = repository.albumsWithoutTracks(
                    serverId, original.generation, albumIds.size.toLong(),
                )
                requireEnumeratedTracks(albumIds.size.toLong(), tracklessAlbums, droppedRows)
            },
        ) { offset, size ->
            val page = source.trackPage(offset, size)
            val kept = page.filter { it.albumRawId in albumIds }
            droppedRows += (page.size - kept.size).toLong()
            SourcePage(kept, rowCount = page.size)
        }
    }

    /**
     * The songs walk's known positive, and it costs nothing: the album set is already in hand.
     *
     * 🚨 The import-level probe validates the *albums* walk only. Without this, a server that
     * enumerates albums and returns nothing for songs closes every track row and commits the result
     * `Verified` — a library of empty albums, reported as a success, which is the same failure the
     * probe exists to prevent applied to the walk that carries every track.
     *
     * The invariant is exact rather than heuristic: a Subsonic album exists because songs exist, so
     * "this server has albums" and "this server enumerates no songs" cannot both be true of a real
     * library. `droppedRows` distinguishes the two ways to reach an empty track stage — nothing
     * returned (this failure) versus everything returned filed under albums this generation has not
     * seen (a mid-pass mutation, already marked `unverified`).
     *
     * **It is applied at two granularities because the invariant has two granularities.** Every
     * album trackless is impossible, and fails. *Some* albums trackless is the same impossibility
     * in miniature, but it is also what a legitimate race produces — an album deleted between the
     * two walks, or one whose songs arrived after the songs walk passed them — so it marks the
     * generation `unverified` rather than failing. Hard-failing there would reject a correct import
     * over one concurrent deletion; accepting it silently is how one spurious empty page mid-walk
     * committed six of ten albums with no tracks at all and called the result verified.
     * OBSERVED 2026-09-11: zero trackless albums on either live corpus, so this is silent in
     * practice on the reference server.
     *
     * Artists get no equivalent check on purpose. An album with no album-artist tag is ordinary, and
     * whether a server then emits an artist row for it is server-specific, so "albums but no
     * artists" is not impossible the way "albums but no songs" is.
     */
    private fun requireEnumeratedTracks(
        albumCount: Long,
        tracklessAlbums: Long,
        droppedRows: Long,
    ) {
        if (albumCount == 0L || droppedRows > 0L) return
        if (tracklessAlbums < albumCount) return
        throw LibraryRequestFailure(DomainError.CapabilityUnsupported(CapabilityFeature.LibrarySync))
    }

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
            // Persist the baseline before the attempt, so a resume compares against the same
            // witness this attempt is about to test.
            //
            // 🚨 NOT dead code, despite having no pages to count. A playlist stage that finds no
            // playlists still needs its baseline written, or the first resumed attempt cannot match
            // an unchanged walk and burns one of three attempts proving it. An earlier version of
            // this comment was copy-pasted from `runPagedStage` and described an empty page ending a
            // fill walk, which does not happen here — a reader who deleted the line as inert on the
            // strength of that wrong explanation would reintroduce the attempt-burn.
            checkpoint = checkpoint.copy(attempt = attempt, witness = baseline)
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

        /**
         * A ceiling on pages per walk, not a tolerance: at the largest page the protocol promises
         * it admits five million rows, roughly 160 times the design's target scale. It exists so a
         * server that answers full pages forever fails the import instead of walking without end.
         *
         * It is deliberately high, and the cost of that is explicit: a server that ignores `offset`
         * is charged this many sequential requests before the import gives up. A lower ceiling
         * would be politer to a broken server and would truncate a genuinely enormous real one,
         * which is the failure this transport exists to make unrepresentable.
         */
        const val DEFAULT_MAX_ENUMERATION_PAGES = 10_000L
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
        val completed = LibrarySyncEngine(repository, enumerationPageSize = 1)
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
    /**
     * The control mutates the ALBUM walk, and needs single-row album pages to force the offset to
     * move. The engine has one page size, so without this the songs walk would also run one row per
     * request — 315 requests per pass against the conformance corpus instead of three. The walk
     * advances by the rows it is given, never by the rows it asked for, so a larger page here is
     * simply a server answering more generously than the request.
     */
    override suspend fun trackPage(offset: Long, size: Int): List<LibraryTrackRow> =
        delegate.trackPage(offset, maxOf(size, TRACK_PAGE_SIZE))

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

    private companion object {
        const val TRACK_PAGE_SIZE = 200
    }
}

private object AtomicControlInterruption : RuntimeException()

private class LibraryControlFailure(val domainError: DomainError) : RuntimeException()
