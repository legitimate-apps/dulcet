package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Opaque server identity scoped to the locally-created provider instance. */
public data class ProviderItemId(
    val providerInstanceId: String,
    val rawId: String,
) {
    init {
        require(providerInstanceId.isNotBlank())
        require(rawId.isNotBlank())
    }
}

public enum class CreditRole {
    Artist,
    AlbumArtist,
}

public data class Credit(
    val role: CreditRole,
    val name: String,
    val id: ProviderItemId?,
)

internal data class LibraryMusicFolder(
    val id: ProviderItemId,
    val name: String,
)

internal data class LibraryArtist(
    val id: ProviderItemId,
    val name: String,
    val mediaSourceId: String?,
)

internal data class LibraryTrack(
    val id: ProviderItemId,
    val title: String,
    val credits: List<Credit>,
    val albumTitle: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val duration: Duration,
    val sourceContainer: AudioContainer?,
    val mediaSourceId: String?,
    val artworkKey: String?,
)

/**
 * [trackCount] is the count the server declares for the album and is independent of [tracks]:
 * the album grid draws it before any track list has been read. [tracksLoaded] is the only thing
 * that separates "this album genuinely has no tracks" from "nobody has asked the server yet",
 * which are otherwise the same empty list.
 */
internal data class LibraryAlbum(
    val id: ProviderItemId,
    val title: String,
    val credits: List<Credit>,
    val year: Int?,
    val duration: Duration,
    val mediaSourceId: String?,
    val artworkKey: String?,
    val tracks: List<LibraryTrack>,
    val trackCount: Int = tracks.size,
    val tracksLoaded: Boolean = true,
) {
    init {
        require(trackCount >= 0)
        require(tracksLoaded || tracks.isEmpty()) {
            "an album that has not been read cannot already carry tracks"
        }
    }
}

internal data class LibraryBrowseSnapshot(
    val musicFolders: List<LibraryMusicFolder>,
    val artists: List<LibraryArtist>,
    val albums: List<LibraryAlbum>,
)

internal data class LibraryBrowseRequest(
    val providerInstanceId: String,
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "LibraryBrowseRequest(<redacted>)"
}

internal sealed interface LibraryBrowseResult {
    data class Loaded(val snapshot: LibraryBrowseSnapshot) : LibraryBrowseResult
    data class Failed(val error: DomainError) : LibraryBrowseResult
}

/** One album's track list, read only when someone opens that album. */
internal sealed interface LibraryAlbumTracksResult {
    data class Loaded(val album: LibraryAlbum) : LibraryAlbumTracksResult
    data class Failed(val error: DomainError) : LibraryAlbumTracksResult
}

internal data class LibraryEndpointResponse(
    val statusCode: Int,
    val body: String,
    val redactedUrl: String,
)

internal fun interface LibraryEndpointTransport {
    suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse
}

/**
 * One uncached, read-through library read.
 *
 * [browse] reads only what the album grid draws. It never issues `getAlbum`, so its request count
 * is a small constant plus one request per album *page* — it does not grow per album. Track lists
 * are read one album at a time by [albumTracks], when somebody opens that album.
 */
internal class LibraryBrowser private constructor(
    private val transportFactory: (LibraryBrowseRequest) -> LibraryEndpointTransport,
    private val albumPageSize: Int,
    private val albumConcurrency: Int,
    private val firstPaintBudget: Duration,
) {
    constructor(
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        transportFactory = { request ->
            KtorLibraryEndpointTransport(request, saltSource, logSink, hostResolver)
        },
        albumPageSize = DEFAULT_ALBUM_PAGE_SIZE,
        albumConcurrency = DEFAULT_ALBUM_CONCURRENCY,
        firstPaintBudget = DEFAULT_FIRST_PAINT_BUDGET,
    )

    internal constructor(
        transport: LibraryEndpointTransport,
        albumPageSize: Int = DEFAULT_ALBUM_PAGE_SIZE,
        albumConcurrency: Int = DEFAULT_ALBUM_CONCURRENCY,
        firstPaintBudget: Duration = DEFAULT_FIRST_PAINT_BUDGET,
    ) : this({ transport }, albumPageSize, albumConcurrency, firstPaintBudget)

    init {
        require(albumPageSize > 0)
        require(albumConcurrency > 0)
        require(firstPaintBudget.isPositive() && firstPaintBudget.isFinite())
    }

    suspend fun browse(request: LibraryBrowseRequest): LibraryBrowseResult {
        val transport = transportFactory(request)
        return try {
            LibraryBrowseResult.Loaded(
                withTimeout(firstPaintBudget) { readFirstPaint(request, transport) },
            )
        } catch (failure: Throwable) {
            LibraryBrowseResult.Failed(failure.asLibraryError())
        } finally {
            (transport as? AutoCloseableLibraryTransport)?.close()
        }
    }

    /** Reads one album's tracks. Exactly one `getAlbum` request, under the same deadline. */
    suspend fun albumTracks(
        request: LibraryBrowseRequest,
        albumRawId: String,
    ): LibraryAlbumTracksResult {
        require(albumRawId.isNotBlank())
        val transport = transportFactory(request)
        return try {
            LibraryAlbumTracksResult.Loaded(
                withTimeout(firstPaintBudget) {
                    parseAlbum(
                        request.providerInstanceId,
                        AlbumSummary(
                            id = ProviderItemId(request.providerInstanceId, albumRawId),
                            title = albumRawId,
                            credits = emptyList(),
                            year = null,
                            duration = Duration.ZERO,
                            mediaSourceId = null,
                            artworkKey = null,
                        ),
                        transport.checkedRequest("getAlbum", mapOf("id" to albumRawId)),
                    )
                },
            )
        } catch (failure: Throwable) {
            LibraryAlbumTracksResult.Failed(failure.asLibraryError())
        } finally {
            (transport as? AutoCloseableLibraryTransport)?.close()
        }
    }

    private suspend fun readFirstPaint(
        request: LibraryBrowseRequest,
        transport: LibraryEndpointTransport,
    ): LibraryBrowseSnapshot = coroutineScope {
        val folders = async {
            parseMusicFolders(
                request.providerInstanceId,
                transport.checkedRequest("getMusicFolders"),
            )
        }
        val artists = async {
            parseArtists(request.providerInstanceId, transport.checkedRequest("getArtists"))
        }
        LibraryBrowseSnapshot(
            musicFolders = folders.await(),
            artists = artists.await(),
            albums = readAlbumSummaries(request.providerInstanceId, transport)
                .map(AlbumSummary::asUnreadAlbum),
        )
    }

    /**
     * Pages `getAlbumList2` until a short page, exactly as a one-page-at-a-time walk would, but
     * reads a look-ahead window concurrently once the server has proved there is more than one
     * page. Pages are merged in offset order, so the album order and the dedupe are identical to
     * the sequential walk; the only difference is round trips. The first page is read alone so a
     * library that fits in one page still costs one request.
     *
     * Two independent stops, because "page until a short page" alone trusts the server to end:
     * a short page, and a whole window that contributes no album id we have not already seen.
     * Neither is a tolerance — both mean the server has stopped offering distinct albums. A server
     * that keeps offering new ids forever is stopped by the caller's deadline, as a reported
     * timeout.
     */
    private suspend fun readAlbumSummaries(
        providerInstanceId: String,
        transport: LibraryEndpointTransport,
    ): List<AlbumSummary> {
        val summaries = mutableListOf<AlbumSummary>()
        val seenAlbumIds = mutableSetOf<String>()
        var offset = 0
        var windowSize = 1
        while (true) {
            val pages = coroutineScope {
                List(windowSize) { index -> offset + index * albumPageSize }
                    .map { pageOffset ->
                        async {
                            parseAlbumList(
                                providerInstanceId,
                                transport.checkedRequest(
                                    "getAlbumList2",
                                    mapOf(
                                        "type" to "alphabeticalByName",
                                        "size" to albumPageSize.toString(),
                                        "offset" to pageOffset.toString(),
                                    ),
                                ),
                            )
                        }
                    }.awaitAll()
            }
            var addedInWindow = 0
            var reachedShortPage = false
            for (page in pages) {
                page.forEach { album ->
                    if (seenAlbumIds.add(album.id.rawId)) {
                        summaries += album
                        addedInWindow += 1
                    }
                }
                if (page.size < albumPageSize) {
                    reachedShortPage = true
                    break
                }
            }
            if (reachedShortPage || addedInWindow == 0) break
            offset += windowSize * albumPageSize
            windowSize = albumConcurrency
        }
        return summaries
    }

    private companion object {
        const val DEFAULT_ALBUM_PAGE_SIZE = 500
        const val DEFAULT_ALBUM_CONCURRENCY = 4

        /**
         * One first paint gets the same budget as one HTTP request, and for the same reason.
         *
         * A first paint is now a constant number of round trips — `getMusicFolders` and
         * `getArtists` together, then one `getAlbumList2` page, then windows of
         * [DEFAULT_ALBUM_CONCURRENCY] pages — so its wall time is a few server responses, not a
         * response per album. MEASUREMENT_PLACEHOLDER
         *
         * The per-request budget in [AuthenticatedEndpointClient] is unchanged, so a single hung
         * request still fails on its own deadline. This one bounds the walk as a whole, and it is
         * a reporting deadline: exceeding it surfaces [DomainError.Transport.Timeout], never a
         * partial library presented as complete.
         */
        val DEFAULT_FIRST_PAINT_BUDGET: Duration = 30.seconds
    }
}

private fun Throwable.asLibraryError(): DomainError = when (this) {
    // TimeoutCancellationException IS a CancellationException, so it has to be answered first or
    // a deadline that fired reads as "the user cancelled".
    is TimeoutCancellationException -> DomainError.Transport.Timeout
    is CancellationException -> DomainError.Transport.Cancelled
    is LibraryRequestFailure -> error
    is AuthenticatedEndpointFailure -> error
    else -> mapAccountConnectionFailure(this)
}

private fun AlbumSummary.asUnreadAlbum(): LibraryAlbum = LibraryAlbum(
    id = id,
    title = title,
    credits = credits,
    year = year,
    duration = duration,
    mediaSourceId = mediaSourceId,
    artworkKey = artworkKey,
    tracks = emptyList(),
    trackCount = trackCount ?: 0,
    tracksLoaded = false,
)

internal interface AutoCloseableLibraryTransport {
    fun close()
}

internal class KtorLibraryEndpointTransport(
    request: LibraryBrowseRequest,
    saltSource: SaltSource?,
    logSink: LogSink?,
    hostResolver: HostResolver,
) : LibraryEndpointTransport, AutoCloseableLibraryTransport {
    private val client = AuthenticatedEndpointClient(
        credentials = request.endpointCredentials(),
        operationName = "library.browse",
        saltSource = saltSource,
        logSink = logSink,
        hostResolver = hostResolver,
    )

    override suspend fun request(
        endpoint: String,
        parameters: Map<String, String>,
    ): LibraryEndpointResponse {
        val response = client.request(endpoint, parameters)
        return LibraryEndpointResponse(
            response.statusCode,
            response.body.decodeToString(),
            response.redactedUrl,
        )
    }

    override fun close() {
        client.close()
    }
}

private fun LibraryBrowseRequest.endpointCredentials() = AuthenticatedEndpointCredentials(
    normalizedBaseUrl = normalizedBaseUrl,
    username = username,
    password = password,
    allowLocalHttp = allowLocalHttp,
)

internal class LibraryRequestFailure(val error: DomainError) : Exception()

internal suspend fun LibraryEndpointTransport.checkedRequest(
    endpoint: String,
    parameters: Map<String, String> = emptyMap(),
): String {
    val response = request(endpoint, parameters)
    val envelope = parseLibraryEnvelope(response.body)
        ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
    if (envelope.status != "ok") {
        val error = envelope.payload["error"] as? JsonObject
        val code = error.int("code") ?: -1
        val message = error?.string("message").orEmpty()
        throw LibraryRequestFailure(
            AccountConnectionContract.mapSubsonicError(code, message, response.redactedUrl),
        )
    }
    return response.body
}

internal data class LibraryEnvelope(val status: String, val payload: JsonObject)

internal val LIBRARY_JSON = Json { ignoreUnknownKeys = true }

internal fun parseLibraryEnvelope(body: String): LibraryEnvelope? = try {
    val root = LIBRARY_JSON.parseToJsonElement(body) as? JsonObject ?: return null
    val payload = root["subsonic-response"] as? JsonObject ?: return null
    LibraryEnvelope(payload.string("status") ?: return null, payload)
} catch (_: IllegalArgumentException) {
    null
}

internal fun parseMusicFolders(providerInstanceId: String, body: String): List<LibraryMusicFolder> {
    val payload = parseLibraryEnvelope(body)?.payload
        ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
    val container = payload["musicFolders"] as? JsonObject
        ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
    return container.arrayOrEmpty("musicFolder").map { element ->
        val folder = element as? JsonObject ?: malformed()
        LibraryMusicFolder(
            id = ProviderItemId(providerInstanceId, folder.requiredOpaqueId("id")),
            name = folder.requiredString("name"),
        )
    }
}

internal fun parseArtists(providerInstanceId: String, body: String): List<LibraryArtist> {
    val payload = parseLibraryEnvelope(body)?.payload
        ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
    val container = payload["artists"] as? JsonObject ?: malformed()
    return container.arrayOrEmpty("index").flatMap { indexElement ->
        val index = indexElement as? JsonObject ?: malformed()
        index.arrayOrEmpty("artist").map { artistElement ->
            val artist = artistElement as? JsonObject ?: malformed()
            LibraryArtist(
                id = ProviderItemId(providerInstanceId, artist.requiredOpaqueId("id")),
                name = artist.requiredString("name"),
                mediaSourceId = null,
            )
        }
    }.distinctBy { it.id.rawId }
}

/** [trackCount] is the server's declared `songCount`, or null when the server omitted it. */
internal data class AlbumSummary(
    val id: ProviderItemId,
    val title: String,
    val credits: List<Credit>,
    val year: Int?,
    val duration: Duration,
    val mediaSourceId: String?,
    val artworkKey: String?,
    val trackCount: Int? = null,
)

internal fun parseAlbumList(providerInstanceId: String, body: String): List<AlbumSummary> {
    val payload = parseLibraryEnvelope(body)?.payload ?: malformed()
    val list = payload["albumList2"] as? JsonObject ?: malformed()
    return list.arrayOrEmpty("album").map { element ->
        val album = element as? JsonObject ?: malformed()
        AlbumSummary(
            id = ProviderItemId(providerInstanceId, album.requiredOpaqueId("id")),
            title = album.requiredString("name"),
            credits = album.credit(providerInstanceId, CreditRole.AlbumArtist),
            year = album.int("year"),
            duration = album.duration(),
            mediaSourceId = null,
            artworkKey = album.optionalOpaqueId("coverArt"),
            // getAlbumList2 already carries the album's track count, so the grid's "N tracks"
            // never needs the track list itself.
            trackCount = album.int("songCount")?.takeIf { it >= 0 },
        )
    }
}

internal fun parseAlbum(
    providerInstanceId: String,
    summary: AlbumSummary,
    body: String,
): LibraryAlbum {
    val payload = parseLibraryEnvelope(body)?.payload ?: malformed()
    val album = payload["album"] as? JsonObject ?: malformed()
    val rawId = album.requiredOpaqueId("id")
    if (rawId != summary.id.rawId) malformed()
    val tracks = album.arrayOrEmpty("song").map { element ->
        val track = element as? JsonObject ?: malformed()
        LibraryTrack(
            id = ProviderItemId(providerInstanceId, track.requiredOpaqueId("id")),
            title = track.requiredString("title"),
            credits = track.credit(providerInstanceId, CreditRole.Artist),
            albumTitle = track.string("album"),
            discNumber = track.int("discNumber"),
            trackNumber = track.int("track"),
            duration = track.duration(),
            sourceContainer = track.libraryAudioContainer(),
            mediaSourceId = null,
            artworkKey = track.optionalOpaqueId("coverArt"),
        )
    }
    return LibraryAlbum(
        id = summary.id,
        title = album.string("name") ?: summary.title,
        credits = album.credit(providerInstanceId, CreditRole.AlbumArtist)
            .ifEmpty { summary.credits },
        year = album.int("year") ?: summary.year,
        duration = album.optionalDuration() ?: tracks.fold(Duration.ZERO) { total, track ->
            total + track.duration
        },
        mediaSourceId = null,
        artworkKey = album.optionalOpaqueId("coverArt") ?: summary.artworkKey,
        tracks = tracks,
        trackCount = tracks.size,
        tracksLoaded = true,
    )
}

private fun JsonObject.libraryAudioContainer(): AudioContainer? {
    string("suffix")?.lowercase()?.let { suffix ->
        when (suffix) {
            "mp3" -> return AudioContainer.Mp3
            "mp4", "m4a" -> return AudioContainer.Mp4
            "wav", "wave" -> return AudioContainer.Wav
            "flac" -> return AudioContainer.Flac
            "ogg", "oga", "opus" -> return AudioContainer.Ogg
            "aac", "adts" -> return AudioContainer.AdtsAac
        }
    }
    return when (string("contentType")?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> AudioContainer.Mp3
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> AudioContainer.Mp4
        "audio/wav", "audio/wave", "audio/x-wav" -> AudioContainer.Wav
        "audio/flac", "audio/x-flac" -> AudioContainer.Flac
        "audio/ogg", "application/ogg" -> AudioContainer.Ogg
        "audio/aac", "audio/aacp" -> AudioContainer.AdtsAac
        else -> null
    }
}

private fun JsonObject.credit(providerInstanceId: String, role: CreditRole): List<Credit> {
    val name = string("artist")?.takeIf(String::isNotBlank) ?: return emptyList()
    return listOf(
        Credit(
            role = role,
            name = name,
            id = optionalOpaqueId("artistId")?.let {
                ProviderItemId(providerInstanceId, it)
            },
        ),
    )
}

private fun JsonObject.duration(): Duration = optionalDuration() ?: Duration.ZERO

private fun JsonObject.optionalDuration(): Duration? = when (val seconds = int("duration")) {
    null -> null
    in 0..Int.MAX_VALUE -> seconds.seconds
    else -> malformed()
}

private fun JsonObject.arrayOrEmpty(name: String): JsonArray = when (val value = get(name)) {
    null -> JsonArray(emptyList())
    is JsonArray -> value
    else -> malformed()
}

private fun JsonObject.requiredString(name: String): String =
    string(name)?.takeIf(String::isNotBlank) ?: malformed()

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.requiredOpaqueId(name: String): String =
    optionalOpaqueId(name) ?: malformed()

private fun JsonObject.optionalOpaqueId(name: String): String? {
    val value = get(name) ?: return null
    val primitive = value as? JsonPrimitive ?: malformed()
    return primitive.contentOrNull?.takeIf(String::isNotBlank) ?: malformed()
}

private fun JsonObject?.int(name: String): Int? =
    (this?.get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun malformed(): Nothing =
    throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
