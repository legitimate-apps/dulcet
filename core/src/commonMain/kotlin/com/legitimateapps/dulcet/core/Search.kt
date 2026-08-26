package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class SearchResultType {
    Track,
    Album,
    Artist,
}

internal data class SearchResultItem(
    val id: ProviderItemId,
    val type: SearchResultType,
    val title: String,
    val credits: List<Credit>,
    val albumTitle: String?,
    val year: Int?,
    val duration: Duration?,
    val mediaSourceId: String?,
    val artworkKey: String?,
)

internal data class SearchPageRequest(
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

internal data class SearchPage(
    val results: List<SearchResultItem>,
    val artistResultCount: Int,
    val albumResultCount: Int,
    val trackResultCount: Int,
    val artistHasMore: Boolean,
    val albumHasMore: Boolean,
    val trackHasMore: Boolean,
)

internal sealed interface SearchPageResult {
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

internal class ServerSearch private constructor(
    private val transportFactory: (SearchPageRequest) -> SearchEndpointTransport,
) {
    constructor(
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        transportFactory = { request ->
            KtorSearchEndpointTransport(request, saltSource, logSink, hostResolver)
        },
    )

    internal constructor(transport: SearchEndpointTransport) : this({ transport })

    suspend fun search(request: SearchPageRequest): SearchPageResult {
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

private fun rankResults(query: String, results: List<SearchResultItem>): List<SearchResultItem> {
    val normalizedQuery = normalizeSearchText(query)
    return results.withIndex().sortedWith(
        compareBy<IndexedValue<SearchResultItem>>(
            { matchRank(normalizedQuery, it.value) },
            { typeRank(it.value.type) },
            { it.index },
        ),
    ).map(IndexedValue<SearchResultItem>::value)
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
