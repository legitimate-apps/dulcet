package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public enum class SearchResultType {
    Track,
    Album,
    Artist,
}

public data class SearchResultItem(
    val id: ProviderItemId,
    val type: SearchResultType,
    val title: String,
    val credits: List<Credit>,
    val albumTitle: String?,
    val year: Int?,
    val duration: Duration?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val sourceContainer: AudioContainer?,
    val mediaSourceId: String?,
    val artworkKey: String?,
)

public data class SearchPageRequest(
    val providerInstanceId: String,
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
    val query: String,
    val artistCount: Int,
    val artistOffset: Int,
    val albumCount: Int,
    val albumOffset: Int,
    val trackCount: Int,
    val trackOffset: Int,
) {
    init {
        require(providerInstanceId.isNotBlank())
        require(query.isNotBlank())
        require(
            listOf(
                artistCount,
                artistOffset,
                albumCount,
                albumOffset,
                trackCount,
                trackOffset,
            ).all { it >= 0 },
        )
    }

    override fun toString(): String = "SearchPageRequest(<redacted>)"
}

public data class SearchPage(
    val results: List<SearchResultItem>,
    val artistResultCount: Int,
    val albumResultCount: Int,
    val trackResultCount: Int,
    val artistHasMore: Boolean,
    val albumHasMore: Boolean,
    val trackHasMore: Boolean,
)

public sealed interface SearchPageResult {
    data class Loaded(val page: SearchPage) : SearchPageResult
    data class Failed(val error: DomainError) : SearchPageResult
}

internal data class SearchEndpointResponse(
    val statusCode: Int,
    val body: String,
    val redactedUrl: String,
)

internal fun interface SearchEndpointTransport {
    suspend fun request(parameters: Map<String, String>): SearchEndpointResponse
}

public class ServerSearch private constructor(
    private val transportFactory: (SearchPageRequest) -> SearchEndpointTransport,
) {
    public constructor(
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        transportFactory = { request ->
            KtorSearchEndpointTransport(request, saltSource, logSink, hostResolver)
        },
    )

    internal constructor(transport: SearchEndpointTransport) : this({ transport })

    public suspend fun search(request: SearchPageRequest): SearchPageResult {
        val transport = transportFactory(request)
        return try {
            val response = transport.request(request.parameters())
            val envelope = parseSearchEnvelope(response.body)
                ?: throw SearchRequestFailure(DomainError.Protocol.MalformedEnvelope)
            if (envelope.status != "ok") {
                val error = envelope.payload["error"] as? JsonObject
                val code = error.searchInt("code") ?: -1
                val message = error?.searchString("message").orEmpty()
                throw SearchRequestFailure(
                    AccountConnectionContract.mapSubsonicError(
                        code,
                        message,
                        response.redactedUrl,
                    ),
                )
            }
            val parsed = parseResults(request.providerInstanceId, envelope.payload)
            SearchPageResult.Loaded(
                SearchPage(
                    results = rankResults(request.query, parsed.all),
                    artistResultCount = parsed.artists.size,
                    albumResultCount = parsed.albums.size,
                    trackResultCount = parsed.tracks.size,
                    artistHasMore = request.artistCount > 0 &&
                        parsed.artists.size == request.artistCount,
                    albumHasMore = request.albumCount > 0 &&
                        parsed.albums.size == request.albumCount,
                    trackHasMore = request.trackCount > 0 &&
                        parsed.tracks.size == request.trackCount,
                ),
            )
        } catch (_: CancellationException) {
            SearchPageResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: SearchRequestFailure) {
            SearchPageResult.Failed(failure.error)
        } catch (failure: AuthenticatedEndpointFailure) {
            SearchPageResult.Failed(failure.error)
        } catch (failure: Throwable) {
            SearchPageResult.Failed(mapAccountConnectionFailure(failure))
        } finally {
            (transport as? AutoCloseableSearchTransport)?.close()
        }
    }
}

/**
 * Merges an already-visible local result set with a later server result set.
 *
 * A server row replaces the local row with the same provider-scoped opaque id in place. Local-only
 * rows retain their position, and server-only rows append in server order. IDs are compared as
 * strings through [ProviderItemId]; they are never parsed as numbers.
 */
public fun mergeSearchResults(
    localResults: List<SearchResultItem>,
    serverResults: List<SearchResultItem>,
): List<SearchResultItem> {
    val serverById = serverResults.associateBy(SearchResultItem::id)
    val emittedIds = mutableSetOf<ProviderItemId>()
    return buildList {
        localResults.forEach { local ->
            if (emittedIds.add(local.id)) add(serverById[local.id] ?: local)
        }
        serverById.values.forEach { server ->
            if (emittedIds.add(server.id)) add(server)
        }
    }
}

// ---- Search in a reader (spec §16.15) ----------------------------------------------------------------

/** §18.1's timing and paging. The debounce and the two-character minimum are core policy. */
internal data class LibrarySearchConfig(
    val debounceMillis: Long = 250,
    val minimumServerQueryLength: Int = 2,
    /** Rows asked of `search3` per type. */
    val serverPageSize: Int = 30,
) {
    init {
        require(debounceMillis >= 0)
        require(minimumServerQueryLength >= 1)
        require(serverPageSize in 1..500)
    }
}

/** Where one result row came from. A row the server returned is [Server] even if it was also local. */
internal enum class SearchResultSource {
    /** The server's search returned it in this query. */
    Server,

    /** Found in what this device has seen, and not (or not yet) returned by the server. */
    Device,
}

/**
 * The honest scope of a whole result list (§16.15). The counts are the seen-cache's own, so "no
 * match" can be told from "no match among what this device has seen".
 */
internal sealed interface SearchScope {
    /** The server search completed and the device's rows were merged into it. */
    data object ServerAndDevice : SearchScope

    /** Fewer than two characters, or the server request is still pending: "On this device". */
    data object DeviceWhileServerPending : SearchScope

    /** The server is unreachable: "Searching what's available offline — N albums and M tracks". */
    data class DeviceOffline(val seen: SeenCacheCounts) : SearchScope

    /** The server search failed with [error]; the device's rows stand, with the device label. */
    data class DeviceServerFailed(val error: DomainError, val seen: SeenCacheCounts) : SearchScope
}

/** One row as shells draw it. [favourite] and [rating] carry any pending local change (§16.20). */
internal data class LibrarySearchRow(
    val item: SearchResultItem,
    val source: SearchResultSource,
    val favourite: Boolean?,
    val rating: Int?,
)

internal data class LibrarySearchPublication(
    /** The text as typed, for matching a publication to the field it answers. */
    val query: String,
    /** 1-based, in delivery order. */
    val sequence: Int,
    val scope: SearchScope,
    val rows: List<LibrarySearchRow>,
)

/**
 * Search as you type, over one [LibraryReader] (§16.15, §18.1).
 *
 * - **Local first, synchronously.** Every keystroke publishes the seen-cache's ranked matches
 *   before [updateQuery] returns — from the first character, and with no request issued.
 * - **Server after a pause.** `search3` is issued only for two or more characters, and only after
 *   [LibrarySearchConfig.debounceMillis] without another keystroke; a keystroke cancels the pending
 *   or in-flight server request, and an answer to an older query is never published.
 * - **No flicker when the server answers.** The server's rows replace the device's rows of the same
 *   opaque id in place, device-only rows keep their positions, and server-only rows append
 *   ([mergeSearchResults], unchanged from §18.1). Both halves are built from seen-cache records by
 *   one mapping, so a replaced row changes only where the server's data does. No empty or loading
 *   list is ever published between the device's rows and the merged ones.
 * - **Stable order.** The ranker is total ([rankResultsStably]): match tier, then type, then
 *   normalized title, then id — never the order rows arrived in.
 * - **Write-through.** The server's entities are written into the seen-cache as a search read
 *   ([CacheEntitySource.Search]), so the next keystroke finds them locally. Result lists are not
 *   cached (§16.15).
 *
 * Threading follows the reader's contract: every call and every publication is on the reader's
 * thread.
 */
internal class LibrarySearchSession(
    private val reader: LibraryReader,
    private val config: LibrarySearchConfig = LibrarySearchConfig(),
    private val listener: (LibrarySearchPublication) -> Unit,
) {
    private val cache: BoundSeenCache get() = reader.cache
    private val local = SeenCacheSearch(reader.cache)
    private var text = ""
    private var generation = 0L
    private var serverJob: Job? = null
    private var sequence = 0
    private var closed = false
    private var scope: SearchScope = SearchScope.DeviceWhileServerPending
    private var items: List<SearchResultItem> = emptyList()
    private var serverIds: Set<ProviderItemId> = emptySet()

    val query: String get() = text

    val isClosed: Boolean get() = closed

    /** One keystroke: publishes the device's rows at once and schedules the server's. */
    fun updateQuery(value: String) {
        if (closed) return
        generation += 1
        serverJob?.cancel()
        serverJob = null
        text = value
        val trimmed = value.trim()
        val device = local.search(trimmed)
        serverIds = emptySet()
        items = device
        if (!reader.online) {
            scope = SearchScope.DeviceOffline(local.counts())
            publish()
            return
        }
        scope = SearchScope.DeviceWhileServerPending
        publish()
        if (normalizeSearchText(trimmed).isEmpty() || trimmed.length < config.minimumServerQueryLength) return
        val submitted = generation
        serverJob = reader.scope.launch {
            delay(config.debounceMillis)
            if (submitted != generation || closed) return@launch
            val outcome = readServer(trimmed)
            if (submitted != generation || closed) return@launch
            when (outcome) {
                is ServerOutcome.Read -> {
                    serverIds = outcome.items.mapTo(mutableSetOf()) { it.id }
                    items = mergeSearchResults(device, outcome.items)
                    scope = SearchScope.ServerAndDevice
                }
                is ServerOutcome.Failed -> {
                    scope = if (outcome.error == DomainError.Transport.Unreachable || !reader.online) {
                        SearchScope.DeviceOffline(local.counts())
                    } else {
                        SearchScope.DeviceServerFailed(outcome.error, local.counts())
                    }
                }
            }
            publish()
        }
    }

    /** Runs the current query again, as if retyped: after reachability changes, or on request. */
    fun refresh() = updateQuery(text)

    /** A pending favourite or rating changed: republish if any row shows one of [rawIds]. */
    fun republishPendingChanges(rawIds: Set<String>) {
        if (!closed && items.any { it.id.rawId in rawIds }) publish()
    }

    /** Idempotent; cancels the server request. Nothing is published after it. */
    fun close() {
        closed = true
        serverJob?.cancel()
        serverJob = null
    }

    private sealed interface ServerOutcome {
        data class Read(val items: List<SearchResultItem>) : ServerOutcome
        data class Failed(val error: DomainError) : ServerOutcome
    }

    private suspend fun readServer(query: String): ServerOutcome {
        // Issued before the request is SENT, so a slower answer never overwrites a newer read.
        val seq = cache.issue()
        val epochKey = reader.sessionEpoch?.key
        return try {
            val size = config.serverPageSize.toString()
            val response = reader.checked(
                "search3",
                linkedMapOf(
                    "query" to query,
                    "artistCount" to size, "artistOffset" to "0",
                    "albumCount" to size, "albumOffset" to "0",
                    "songCount" to size, "songOffset" to "0",
                ),
            )
            val entities = parseReaderSearch3(response.body)
            cache.writeEntities(CacheWriteStamp(seq, cache.now(), epochKey), CacheEntitySource.Search, entities)
            cache.evictIfNeeded()
            val provider = cache.serverId
            ServerOutcome.Read(
                rankResultsStably(
                    query,
                    entities.artists.map { it.toSearchResult(provider) } +
                        entities.albums.map { it.toSearchResult(provider) } +
                        entities.tracks.map { it.toSearchResult(provider) },
                ),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            ServerOutcome.Failed(failure.asReaderError())
        }
    }

    private fun publish() {
        if (closed) return
        val pending = reader.overlay.pending(cache.serverId, items.mapTo(mutableSetOf()) { it.id.rawId })
        val rows = items.map { item ->
            val state = userState(item)
            val overlay = pending[item.id.rawId]
            LibrarySearchRow(
                item = item,
                source = if (item.id in serverIds) SearchResultSource.Server else SearchResultSource.Device,
                favourite = overlay?.starred ?: state?.starred,
                rating = overlay?.userRating ?: state?.userRating,
            )
        }
        sequence += 1
        listener(LibrarySearchPublication(text, sequence, scope, rows))
    }

    private fun userState(item: SearchResultItem): CacheUserState? = when (item.type) {
        SearchResultType.Artist -> cache.artist(item.id.rawId)?.record?.userState
        SearchResultType.Album -> cache.album(item.id.rawId)?.record?.userState
        SearchResultType.Track -> cache.track(item.id.rawId)?.record?.userState
    }
}

private interface AutoCloseableSearchTransport {
    fun close()
}

private class KtorSearchEndpointTransport(
    request: SearchPageRequest,
    saltSource: SaltSource?,
    logSink: LogSink?,
    hostResolver: HostResolver,
) : SearchEndpointTransport, AutoCloseableSearchTransport {
    private val client = AuthenticatedEndpointClient(
        credentials = AuthenticatedEndpointCredentials(
            normalizedBaseUrl = request.normalizedBaseUrl,
            username = request.username,
            password = request.password,
            allowLocalHttp = request.allowLocalHttp,
        ),
        operationName = "search.query",
        saltSource = saltSource,
        logSink = logSink,
        hostResolver = hostResolver,
    )

    override suspend fun request(parameters: Map<String, String>): SearchEndpointResponse {
        val response = client.request("search3", parameters)
        return SearchEndpointResponse(
            statusCode = response.statusCode,
            body = response.body.decodeToString(),
            redactedUrl = response.redactedUrl,
        )
    }

    override fun close() {
        client.close()
    }
}

private fun SearchPageRequest.parameters(): Map<String, String> = linkedMapOf(
    "query" to query,
    "artistCount" to artistCount.toString(),
    "artistOffset" to artistOffset.toString(),
    "albumCount" to albumCount.toString(),
    "albumOffset" to albumOffset.toString(),
    "songCount" to trackCount.toString(),
    "songOffset" to trackOffset.toString(),
)

private data class SearchEnvelope(val status: String, val payload: JsonObject)

private data class ParsedSearchResults(
    val artists: List<SearchResultItem>,
    val albums: List<SearchResultItem>,
    val tracks: List<SearchResultItem>,
) {
    val all: List<SearchResultItem> get() = artists + albums + tracks
}

private val SEARCH_JSON = Json { ignoreUnknownKeys = true }

private fun parseSearchEnvelope(body: String): SearchEnvelope? = try {
    val root = SEARCH_JSON.parseToJsonElement(body) as? JsonObject ?: return null
    val payload = root["subsonic-response"] as? JsonObject ?: return null
    SearchEnvelope(payload.searchString("status") ?: return null, payload)
} catch (_: IllegalArgumentException) {
    null
}

private fun parseResults(
    providerInstanceId: String,
    payload: JsonObject,
): ParsedSearchResults {
    val container = payload["searchResult3"] as? JsonObject ?: searchMalformed()
    val artists = container.searchArrayOrEmpty("artist").map { element ->
        val artist = element as? JsonObject ?: searchMalformed()
        SearchResultItem(
            id = ProviderItemId(providerInstanceId, artist.searchRequiredOpaqueId("id")),
            type = SearchResultType.Artist,
            title = artist.searchRequiredString("name"),
            credits = emptyList(),
            albumTitle = null,
            year = null,
            duration = null,
            discNumber = null,
            trackNumber = null,
            sourceContainer = null,
            mediaSourceId = null,
            artworkKey = artist.searchOptionalOpaqueId("coverArt"),
        )
    }
    val albums = container.searchArrayOrEmpty("album").map { element ->
        val album = element as? JsonObject ?: searchMalformed()
        SearchResultItem(
            id = ProviderItemId(providerInstanceId, album.searchRequiredOpaqueId("id")),
            type = SearchResultType.Album,
            title = album.searchRequiredString("name"),
            credits = album.searchCredit(providerInstanceId, CreditRole.AlbumArtist),
            albumTitle = null,
            year = album.searchInt("year"),
            duration = album.searchDuration(),
            discNumber = null,
            trackNumber = null,
            sourceContainer = null,
            mediaSourceId = null,
            artworkKey = album.searchOptionalOpaqueId("coverArt"),
        )
    }
    val tracks = container.searchArrayOrEmpty("song").map { element ->
        val track = element as? JsonObject ?: searchMalformed()
        SearchResultItem(
            id = ProviderItemId(providerInstanceId, track.searchRequiredOpaqueId("id")),
            type = SearchResultType.Track,
            title = track.searchRequiredString("title"),
            credits = track.searchCredit(providerInstanceId, CreditRole.Artist),
            albumTitle = track.searchString("album"),
            year = track.searchInt("year"),
            duration = track.searchDuration(),
            discNumber = track.searchInt("discNumber"),
            trackNumber = track.searchInt("track"),
            sourceContainer = track.searchAudioContainer(),
            mediaSourceId = null,
            artworkKey = track.searchOptionalOpaqueId("coverArt"),
        )
    }
    return ParsedSearchResults(
        artists = artists.distinctBy { it.id.rawId },
        albums = albums.distinctBy { it.id.rawId },
        tracks = tracks.distinctBy { it.id.rawId },
    )
}

internal fun rankResults(query: String, results: List<SearchResultItem>): List<SearchResultItem> {
    val normalizedQuery = normalizeSearchText(query)
    return results.withIndex().sortedWith(
        compareBy<IndexedValue<SearchResultItem>>(
            { matchRank(normalizedQuery, it.value) },
            { typeRank(it.value.type) },
            { it.index },
        ),
    ).map(IndexedValue<SearchResultItem>::value)
}

/**
 * [rankResults] with a total order: ties within a match tier and type break on the normalized
 * title and then the opaque id, never on input order. The reader's search uses it for both halves,
 * so the same rows rank the same way whichever order the database or the server returned them in,
 * and a keystroke that keeps a row keeps it in the same place relative to the rows it kept too.
 */
internal fun rankResultsStably(query: String, results: List<SearchResultItem>): List<SearchResultItem> {
    val normalizedQuery = normalizeSearchText(query)
    return results.sortedWith(
        compareBy<SearchResultItem>(
            { matchRank(normalizedQuery, it) },
            { typeRank(it.type) },
            { normalizeSearchText(it.title) },
            { it.id.rawId },
        ),
    )
}

private fun matchRank(query: String, result: SearchResultItem): Int = buildList {
    add(result.title)
    result.albumTitle?.let(::add)
    addAll(result.credits.map(Credit::name))
}.minOfOrNull { candidate ->
    val value = normalizeSearchText(candidate)
    when {
        value == query -> 0
        value.startsWith(query) -> 1
        value.hasWordStartingWith(query) -> 2
        value.contains(query) -> 3
        else -> 4
    }
} ?: 4

private fun String.hasWordStartingWith(query: String): Boolean {
    var atWordStart = true
    indices.forEach { index ->
        val character = this[index]
        if (character.isLetterOrDigit()) {
            if (atWordStart && regionMatches(index, query, 0, query.length)) return true
            atWordStart = false
        } else {
            atWordStart = true
        }
    }
    return false
}

private fun typeRank(type: SearchResultType): Int = when (type) {
    SearchResultType.Track -> 0
    SearchResultType.Album -> 1
    SearchResultType.Artist -> 2
}

private fun JsonObject.searchCredit(
    providerInstanceId: String,
    role: CreditRole,
): List<Credit> {
    val name = searchString("artist")?.takeIf(String::isNotBlank) ?: return emptyList()
    return listOf(
        Credit(
            role = role,
            name = name,
            id = searchOptionalOpaqueId("artistId")?.let {
                ProviderItemId(providerInstanceId, it)
            },
        ),
    )
}

private fun JsonObject.searchDuration(): Duration? = when (val seconds = searchInt("duration")) {
    null -> null
    in 0..Int.MAX_VALUE -> seconds.seconds
    else -> searchMalformed()
}

private fun JsonObject.searchAudioContainer(): AudioContainer? {
    searchString("suffix")?.lowercase()?.let { suffix ->
        when (suffix) {
            "mp3" -> return AudioContainer.Mp3
            "mp4", "m4a" -> return AudioContainer.Mp4
            "wav", "wave" -> return AudioContainer.Wav
            "flac" -> return AudioContainer.Flac
            "ogg", "oga", "opus" -> return AudioContainer.Ogg
            "aac", "adts" -> return AudioContainer.AdtsAac
        }
    }
    return when (searchString("contentType")?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> AudioContainer.Mp3
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> AudioContainer.Mp4
        "audio/wav", "audio/wave", "audio/x-wav" -> AudioContainer.Wav
        "audio/flac", "audio/x-flac" -> AudioContainer.Flac
        "audio/ogg", "application/ogg" -> AudioContainer.Ogg
        "audio/aac", "audio/aacp" -> AudioContainer.AdtsAac
        else -> null
    }
}

private fun JsonObject.searchArrayOrEmpty(name: String): JsonArray = when (val value = get(name)) {
    null -> JsonArray(emptyList())
    is JsonArray -> value
    else -> searchMalformed()
}

private fun JsonObject.searchRequiredString(name: String): String =
    searchString(name)?.takeIf(String::isNotBlank) ?: searchMalformed()

private fun JsonObject.searchString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.searchRequiredOpaqueId(name: String): String =
    searchOptionalOpaqueId(name) ?: searchMalformed()

private fun JsonObject.searchOptionalOpaqueId(name: String): String? {
    val value = get(name) ?: return null
    val primitive = value as? JsonPrimitive ?: searchMalformed()
    return primitive.contentOrNull?.takeIf(String::isNotBlank) ?: searchMalformed()
}

private fun JsonObject?.searchInt(name: String): Int? =
    (this?.get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private class SearchRequestFailure(val error: DomainError) : Exception()

private fun searchMalformed(): Nothing =
    throw SearchRequestFailure(DomainError.Protocol.MalformedEnvelope)
