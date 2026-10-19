package com.legitimateapps.dulcet.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Shared home identifiers. Shells and system surfaces open these through the ordinary reader. */
public enum class ListenNowShelf(public val wireName: String, public val title: String) {
    Recent("recent", "Recently Played"),
    RecentPlaylists("recentPlaylists", "Recently Played Playlists"),
    Frequent("frequent", "Heavy Rotation"),
    Newest("newest", "New on Your Server"),
    Rediscover("rediscover", "Rediscover"),
    DailyMix("dailyMix", "Daily Mix"),
    Favourites("favourites", "Your Favourites"),
    ArtistStation("artistStation", "From Your Artists"),
    GenreStation("genreStation", "From Your Genres"),
    Random("random", "Explore Your Server"),
}

/**
 * Uses only an advertised dulcetHome v1. A valid absence uses the public REST equivalents;
 * discovery/auth/transport failures remain failures and never replace the shelf's cached answer.
 * All I/O still passes through the reader's offline rule, bounded requests and issue ordering.
 */
internal class ListenNowTransport(private val reader: LibraryReader) {
    private val discovery = Mutex()
    private var available: Boolean? = null

    fun invalidate() { available = null }

    private suspend fun offered(): Boolean = discovery.withLock {
        available?.let { return@withLock it }
        val answer = try {
            val sent = reader.sendChecked("getOpenSubsonicExtensions")
            1 in parseOpenSubsonicExtensionVersions(sent.response.body, "dulcetHome")
        } catch (failure: LibraryRequestFailure) {
            val absent = when (val error = failure.error) {
                is DomainError.Server.Known -> error.code == 70
                is DomainError.Server.HttpStatus -> error.status == 404 || error.status == 501
                else -> false
            }
            if (!absent || failure is ReaderSendRefused) throw failure
            false
        }
        available = answer
        answer
    }

    suspend fun read(parameters: Map<String, String>): SentResponse {
        val shelf = ListenNowShelf.entries.first { it.wireName == parameters["shelf"] }
        val size = parameters["size"] ?: "20"
        if (offered()) return reader.sendChecked("getDulcetHome", mapOf("shelf" to shelf.wireName, "size" to size))
        val sent = when (shelf) {
            ListenNowShelf.Recent, ListenNowShelf.Frequent, ListenNowShelf.Newest, ListenNowShelf.Random ->
                reader.sendChecked("getAlbumList2", mapOf("type" to shelf.wireName, "size" to size))
            ListenNowShelf.Favourites -> reader.sendChecked("getStarred2")
            ListenNowShelf.ArtistStation -> similar(size)
            // Plain REST has no timestamped loved-but-unheard query, affinity mix or genre profile.
            // An empty, hidden shelf is honest; random songs are never called personal picks.
            else -> reader.sendChecked("ping")
        }
        val payload = sent.response.okPayload()
        val raw = (payload["albumList2"] ?: payload["starred2"] ?: payload["similarSongs2"]) as? JsonObject
        val max = size.toInt().coerceIn(1, 50)
        val albums = (raw?.get("album") as? JsonArray).orEmpty().take(max)
        val songs = (raw?.get("song") as? JsonArray).orEmpty().take(max - albums.size)
        val content = buildJsonObject { put("album", JsonArray(albums)); put("song", JsonArray(songs)) }
        return SentResponse(sent.issueSeq, sent.before, sent.response.copy(body = buildJsonObject {
            put("subsonic-response", buildJsonObject { put("status", "ok"); put("dulcetHome", content) })
        }.toString()))
    }
    /** Similar songs use a representative song from a recently played album; no song-level recency is inferred. */
    private suspend fun similar(size: String): SentResponse {
        val recent = reader.sendChecked("getAlbumList2", mapOf("type" to "recent", "size" to "1"))
        val album = parseReaderAlbumList(recent.response.body).firstOrNull()
            ?: return reader.sendChecked("ping")
        val detail = reader.sendChecked("getAlbum", mapOf("id" to album.rawId))
        val song = parseReaderAlbum(detail.response.body, album.rawId).second.firstOrNull()
            ?: return reader.sendChecked("ping")
        return try {
            reader.sendChecked("getSimilarSongs2", mapOf("id" to song.rawId, "count" to size))
        } catch (failure: LibraryRequestFailure) {
            val absent = when(val error = failure.error) {
                is DomainError.Server.Known -> error.code == 70
                is DomainError.Server.HttpStatus -> error.status == 404 || error.status == 501
                else -> false
            }
            if(!absent || failure is ReaderSendRefused) throw failure
            reader.sendChecked("ping")
        }
    }

}

internal fun listenNowSpec(shelf: ListenNowShelf): ListRequestSpec = ListRequestSpec(
    endpoint = "listenNow",
    parameters = mapOf("shelf" to shelf.wireName),
    listKey = "listenNow:v1:${shelf.wireName}",
    paged = false,
    sizeParameter = "size",
    singlePageSize = 20,
) { body ->
    val content = parseLibraryEnvelope(body)?.payload?.get("dulcetHome") as? JsonObject
        ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
    fun envelope(key: String) = buildJsonObject {
        put("subsonic-response", buildJsonObject { put("status", "ok"); put(key, content) })
    }.toString()
    val playlists = parseReaderPlaylists(envelope("playlists"))
    val entities = parseReaderStarred(envelope("starred2")).copy(playlists=playlists)
    ParsedList(
        entities.playlists.map { CacheListMember(CacheItemKind.Playlist, it.rawId) } +
            entities.albums.map { CacheListMember(CacheItemKind.Album, it.rawId) } +
            entities.tracks.map { CacheListMember(CacheItemKind.Track, it.rawId) },
        entities,
    )
}
