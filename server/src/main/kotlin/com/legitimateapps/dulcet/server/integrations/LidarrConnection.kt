package com.legitimateapps.dulcet.server.integrations

import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetAddress
import java.net.URI

/**
 * Lidarr's v1 API. What Lidarr receives: the MusicBrainz artist id of an artist being shown, and the
 * API key in the `X-Api-Key` header. [library] reads Lidarr's own library (`/api/v1/artist?mbId=`) and
 * the poster Lidarr already stores (`/MediaCover/{id}/poster.jpg`). [lookup] asks Lidarr's artist lookup,
 * which Lidarr answers from its metadata service, and fetches the poster through Lidarr's own image proxy
 * first; only when that fails is the poster's remote URL fetched directly, and only from an allowlisted
 * image host.
 */
internal class LidarrConnection(
    private val endpoint: ServiceEndpoint, imageHosts: Set<String>, private val http: PoliteHttp, private val cache: DiscoverCache,
    testingOverride: () -> Boolean, resolver: (String) -> Array<InetAddress>,
) : Connection {
    override val kind = "lidarr"
    override val artistImages: Map<String, ArtistImageSource> get() = mapOf("lidarr" to library, "lidarr-feed" to lookup)
    private val auth get() = mapOf("X-Api-Key" to endpoint.key())
    private val remote = AllowlistedImageFetch(http, imageHosts, testingOverride, resolver)

    val library = object : ArtistImageSource {
        override suspend fun artistImage(artist: ArtistRef, reach: Reach) = libraryImage(canonicalMbid(artist.musicBrainzId!!))
    }
    val lookup = object : ArtistImageSource {
        override suspend fun artistImage(artist: ArtistRef, reach: Reach) = if (reach == Reach.Catalogue) lookupImage(canonicalMbid(artist.musicBrainzId!!)) else null
    }

    private suspend fun artists(path: String, query: Map<String, String>, ttl: Long): List<JsonObject> =
        cache.read(CacheKey(endpoint.url, path, query), ttl, { bytes -> Json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject } }) {
            http.get(endpoint.url, path, query, credentials = auth)
        }.value
    private fun poster(artist: JsonObject) = (artist["images"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.text("coverType") == "poster" }

    private suspend fun libraryImage(mbid: String): RemoteImage? {
        val artist = (try { artists("/api/v1/artist", mapOf("mbId" to mbid), DiscoverCache.HOUR) } catch (_: UpstreamNotFound) { return null })
            .firstOrNull { it.text("foreignArtistId").equals(mbid, true) } ?: return null
        val id = artist.number("id")?.takeIf { it > 0 } ?: return null
        val url = poster(artist)?.text("url") ?: return null
        // Paths are built here from the id; nothing from the answer is used as a URL.
        val match = MEDIA_COVER.find(url)?.takeIf { it.groupValues[1] == id.toString() } ?: return null
        val extension = match.groupValues[2]
        val query = match.groupValues[3].takeIf(String::isNotEmpty)?.let { mapOf("lastWrite" to it) } ?: emptyMap()
        suspend fun variant(suffix: String): RemoteImage? {
            val path = "/MediaCover/$id/poster$suffix$extension"
            return cachedImage(cache, CacheKey(endpoint.url, path, query), "lidarr:$mbid:${query["lastWrite"]}$suffix") {
                http.get(endpoint.url, path, query, CoverArtClient.MAX_BYTES, auth)
            }
        }
        // The full poster serves every size through the resize cache; Lidarr's 500 px copy covers an oversized original.
        return try { variant("") } catch (e: UpstreamUnavailable) {
            if (e.reason != UpstreamFailure.Body && e.reason != UpstreamFailure.Parse) throw e
            variant("-500")
        }
    }

    private suspend fun lookupImage(mbid: String): RemoteImage? {
        val hit = (try { artists("/api/v1/artist/lookup", mapOf("term" to "lidarr:$mbid"), DiscoverCache.DAY) } catch (_: UpstreamNotFound) { return null })
            .firstOrNull { it.text("foreignArtistId").equals(mbid, true) } ?: return null
        val poster = poster(hit) ?: return null
        val proxy = poster.text("url")?.let(PROXY::find)
        val direct = (poster.text("remoteUrl") ?: hit.text("remotePoster"))?.let { runCatching { URI(it) }.getOrNull() }
        val identity = direct?.toString() ?: proxy?.groupValues?.get(1) ?: return null
        return cachedImage(cache, CacheKey(endpoint.url, "/artist-lookup-image", mapOf("mbid" to mbid, "source" to identity)), "lidarr-lookup:$identity") {
            proxy?.let { p ->
                try {
                    val response = http.get(endpoint.url, "/MediaCoverProxy/${p.groupValues[1]}/${p.groupValues[2]}", emptyMap(), CoverArtClient.MAX_BYTES, auth)
                    if (response.status == 200 && isImage(response.body)) return@cachedImage response
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            if (direct != null && remote.allowed(direct)) remote.fetch(direct) else NOT_FOUND
        }
    }

    companion object {
        private val MEDIA_COVER = Regex("""/MediaCover/(\d{1,10})/poster(\.(?:jpg|jpeg|png))(?:\?lastWrite=(\d{1,20}))?$""")
        private val PROXY = Regex("""/MediaCoverProxy/([0-9a-f]{64})/([A-Za-z0-9._-]{1,200})$""")
    }
}
