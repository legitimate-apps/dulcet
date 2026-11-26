package com.legitimateapps.dulcet.server.integrations

import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Jellyfin and Emby share this API. What the server receives: one request an hour for its album-artist
 * list with provider ids, then the Primary image of an artist being shown, with the API key in the
 * `X-Emby-Token` header. Artists are matched by the server's `MusicBrainzArtist` provider id.
 */
internal class MediaBrowserConnection(override val kind: String, private val endpoint: ServiceEndpoint, private val http: PoliteHttp, private val cache: DiscoverCache) : Connection, ArtistImageSource {
    override val artistImages: Map<String, ArtistImageSource> get() = mapOf(kind to this)
    private val auth get() = mapOf("X-Emby-Token" to endpoint.key())
    private data class Entry(val id: String, val tag: String)
    private val lock = Mutex()
    private var memo: Pair<Long, Map<String, Entry>>? = null
    private var failedUntil: Long? = null

    private suspend fun index(): Map<String, Entry> = lock.withLock {
        // Re-checked under the lock: callers queued behind a failed read must not repeat it.
        failedUntil?.let { if (it - System.nanoTime() > 0) throw UpstreamUnavailable(reason = UpstreamFailure.Backoff) }
        memo?.let { (at, value) -> if (System.nanoTime() - at < MEMO_NANOS) return@withLock value }
        val value = try {
            cache.read(CacheKey(endpoint.url, LIST, LIST_QUERY), DiscoverCache.HOUR, ::parse) {
                http.get(endpoint.url, LIST, LIST_QUERY, 32 * 1024 * 1024, auth)
            }.value
        } catch (e: UpstreamUnavailable) { failedUntil = System.nanoTime() + Integrations.OUTAGE_SKIP_NANOS; throw e }
        memo = System.nanoTime() to value
        value
    }

    private fun parse(bytes: ByteArray): Map<String, Entry> {
        val items = (Json.parseToJsonElement(bytes.decodeToString()).jsonObject["Items"] as? kotlinx.serialization.json.JsonArray) ?: throw UpstreamUnavailable(reason = UpstreamFailure.Parse)
        val result = HashMap<String, Entry>()
        for (item in items.mapNotNull { it as? JsonObject }) {
            val id = item.text("Id")?.takeIf(ITEM_ID::matches) ?: continue
            val tag = (item["ImageTags"] as? JsonObject)?.text("Primary")?.takeIf(TAG::matches) ?: continue
            val mbids = (item["ProviderIds"] as? JsonObject)?.text("MusicBrainzArtist") ?: continue
            for (raw in mbids.split('/', ';', ',')) runCatching { canonicalMbid(raw.trim().lowercase()) }.getOrNull()?.let { result.putIfAbsent(it, Entry(id, tag)) }
        }
        return result
    }

    override suspend fun artistImage(artist: ArtistRef, reach: Reach): RemoteImage? {
        val mbid = canonicalMbid(artist.musicBrainzId!!).lowercase()
        val entry = index()[mbid] ?: return null
        val path = "/Items/${entry.id}/Images/Primary"
        val query = mapOf("maxWidth" to "1200", "quality" to "90", "tag" to entry.tag)
        return cachedImage(cache, CacheKey(endpoint.url, path, query), "mediabrowser:${entry.id}:${entry.tag}") {
            http.get(endpoint.url, path, query, CoverArtClient.MAX_BYTES, auth)
        }
    }

    companion object {
        const val LIST = "/Artists/AlbumArtists"
        val LIST_QUERY = mapOf("Fields" to "ProviderIds", "EnableImageTypes" to "Primary", "ImageTypeLimit" to "1", "EnableUserData" to "false", "EnableTotalRecordCount" to "false")
        private const val MEMO_NANOS = 5 * 60_000_000_000L
        private val ITEM_ID = Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val TAG = Regex("[0-9A-Za-z]{1,128}")
    }
}
