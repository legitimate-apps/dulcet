package com.legitimateapps.dulcet.server.integrations

import com.legitimateapps.dulcet.server.discover.DiscoverCache
import com.legitimateapps.dulcet.server.discover.PoliteHttp
import com.legitimateapps.dulcet.server.discover.UpstreamFailure
import com.legitimateapps.dulcet.server.discover.UpstreamNotFound
import com.legitimateapps.dulcet.server.discover.UpstreamUnavailable
import com.legitimateapps.dulcet.server.discover.CacheKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * The Connections layer (package `integrations`, since `Connections.kt` already names client routes): the
 * tools a person runs beside Dulcet (Lidarr, Jellyfin, Emby), each optional and off until configured. It
 * sits beside the Library (everything on the server) and My Library (each person's picks and playlists).
 * Later capabilities (a wish sent to Lidarr, import from Jellyfin or Plex, popularity) join as further
 * interfaces here. The library and catalog code see only the capability
 * interfaces here, never which tools exist. Files in the library itself (an `artist.jpg` beside the
 * albums) are the library layer's, not an integration.
 */

/** Who an image is for. Remote tools are matched by MusicBrainz artist id only; a name never picks a photo. */
data class ArtistRef(val musicBrainzId: String?, val name: String)

/**
 * [Library] asks only what the operator's own tools hold (fast, local; used while catalog pages
 * render). [Catalogue] may also ask a tool's own outside feed, for Discover artists nobody holds.
 */
enum class Reach { Library, Catalogue }

/** Validated image bytes; [key] is a stable identity for downstream resize caches, and [cacheDigest] names the
 * shared-cache entry that holds the bytes, so a later cache-only read can find them again. */
class RemoteImage(val bytes: ByteArray, val contentType: String, val key: String, internal val cacheDigest: String? = null)

/**
 * A configured connection (one Lidarr, Jellyfin or Emby instance) and the capabilities it offers, each keyed by
 * a source name an admin orders per capability. Connections are server-wide and admin-configured, because their
 * answers are shared and cached for every user. A later capability (album art, metadata, popularity, requests,
 * import) is one more property here with an empty default; an artwork-only kind (fanart.tv with the operator's
 * own key) is one more implementation offering only [artistImages].
 */
interface Connection {
    val kind: String
    val artistImages: Map<String, ArtistImageSource> get() = emptyMap()
}

/** One capability. Adding a source (Plex, a Subsonic server's getArtistInfo2, fanart.tv with a user key)
 * is one implementation of this interface and one entry in [Integrations.from]. */
interface ArtistImageSource {
    /** Whether this source is asked at [reach] at all; one that is not never counts as having answered. */
    fun serves(reach: Reach): Boolean = true
    /** null when this source has no image for the artist. Throws [UpstreamUnavailable] when the source failed. */
    suspend fun artistImage(artist: ArtistRef, reach: Reach): RemoteImage?
}

/**
 * The configured sources in order. A source that fails with an outage is skipped for a minute, so an
 * unreachable tool costs one timeout a minute rather than one per artist on a page.
 *
 * Each answer is remembered as a small pointer (per artist and [Reach]) to the cache entry holding the bytes, so
 * [cachedArtistImage] can answer without the network while the caller holds the database lock; a pointer that is
 * missing or over an hour old is refreshed in the background. [onArtistImageChanged] is told the MusicBrainz id
 * whenever the Library-reach answer changes, so the catalog can tell clients to re-read that artist.
 */
class Integrations(
    private val sources: List<ArtistImageSource>,
    private val cache: DiscoverCache? = null,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : ArtistImageSource, AutoCloseable {
    val configured: Boolean get() = sources.isNotEmpty()
    @Volatile var onArtistImageChanged: (String) -> Unit = {}
    private val downUntil = ConcurrentHashMap<ArtistImageSource, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = ConcurrentHashMap<Pair<String, Reach>, Job>()
    private val refreshPermits = Semaphore(2)

    internal fun isDown(source: ArtistImageSource): Boolean = downUntil[source]?.let { it - monotonicNanos() > 0 } == true

    /**
     * null when no source has an image. Throws [UpstreamUnavailable] when every source that could answer was in an
     * outage, so a caller can tell "no photo" from "could not ask".
     */
    override suspend fun artistImage(artist: ArtistRef, reach: Reach): RemoteImage? {
        val mbid = canonical(artist) ?: return null
        val ref = artist.copy(musicBrainzId = mbid)
        var answered = false
        var skipped = false
        for (source in sources) {
            if (!source.serves(reach)) continue
            if (isDown(source)) { skipped = true; continue }
            try {
                val image = source.artistImage(ref, reach)
                answered = true
                if (image != null) return image.also { remember(mbid, reach, it) }
            } catch (e: CancellationException) { throw e }
              catch (e: UpstreamUnavailable) { if (e.reason in OUTAGES) { downUntil[source] = monotonicNanos() + OUTAGE_SKIP_NANOS; skipped = true } else answered = true }
              catch (_: UpstreamNotFound) { answered = true }
              catch (_: Exception) { answered = true }
        }
        if (!answered && configured) throw UpstreamUnavailable(reason = UpstreamFailure.Unavailable)
        // "No photo" is remembered only when every source answered; an outage leaves the earlier answer standing.
        if (!skipped) remember(mbid, reach, null)
        return null
    }

    /**
     * The last answer for [artist] from the shared cache only: never the network and never a refresh lock, so it is
     * safe inside a database transaction. Queues a background refresh when the answer is missing or stale.
     */
    fun cachedArtistImage(artist: ArtistRef, reach: Reach): RemoteImage? {
        val cache = cache ?: return null
        val mbid = canonical(artist) ?: return null
        val pointer = cache.peek(pointerKey(mbid, reach))
        if (pointer == null || (pointer.status == 200 && pointer.ageMillis > POINTER_REFRESH_MILLIS)) refresh(artist.copy(musicBrainzId = mbid), reach)
        if (pointer?.status != 200) return null
        val (digest, key) = pointer.body.decodeToString().split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
        val stored = cache.peek(digest)?.takeIf { it.status == 200 } ?: return null.also { refresh(artist.copy(musicBrainzId = mbid), reach) }
        val type = runCatching { com.legitimateapps.dulcet.server.discover.CoverArtClient.imageType(stored.body) }.getOrNull() ?: return null
        return RemoteImage(stored.body, type, key, digest)
    }

    /**
     * A short token naming the Library-reach photo [cachedArtistImage] would answer for [artist], or null when none is
     * known yet. It goes into the artist's `coverArt` id, so a client that keeps an image under its id for good (Dulcet's
     * own apps, other OpenSubsonic clients) sees a new id when a photo arrives or changes, rather than keeping the album
     * cover that stood in before. Cache only, never the network, so it is safe inside a database transaction; a missing
     * or stale answer queues the same background refresh as [cachedArtistImage].
     */
    fun artistImageVersion(artist: ArtistRef): String? {
        val cache = cache ?: return null
        val mbid = canonical(artist) ?: return null
        val pointer = cache.peek(pointerKey(mbid, Reach.Library))
        if (pointer == null || (pointer.status == 200 && pointer.ageMillis > POINTER_REFRESH_MILLIS)) refresh(artist.copy(musicBrainzId = mbid), Reach.Library)
        if (pointer?.status != 200) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pointer.body)
        return digest.take(5).joinToString("") { "%02x".format(it) }
    }

    @Volatile private var closed = false

    private fun refresh(artist: ArtistRef, reach: Reach) {
        val id = artist.musicBrainzId!! to reach
        // A bounded queue: past it a stale answer waits for a later render rather than piling up fetches.
        if (closed || (refreshing.size >= MAX_PENDING_REFRESHES && !refreshing.containsKey(id))) return
        refreshing.computeIfAbsent(id) {
            scope.launch(start = CoroutineStart.LAZY) {
                try { refreshPermits.withPermit { runCatching { artistImage(artist, reach) } } } finally { refreshing.remove(id) }
            }
        }.start()
    }

    /** Waits for queued background refreshes (tests and shutdown). */
    suspend fun awaitRefreshes() { while (true) { val jobs = refreshing.values.toList(); if (jobs.isEmpty()) return; jobs.joinAll() } }

    private fun remember(mbid: String, reach: Reach, image: RemoteImage?) {
        val cache = cache ?: return
        if (closed) return
        val key = pointerKey(mbid, reach)
        val previous = cache.peek(key)
        val digest = image?.cacheDigest
        if (image != null && digest == null) return
        val body = if (image == null) ByteArray(0) else "$digest\n${image.key}".toByteArray()
        cache.put(key, if (image == null) 404 else 200, body)
        val before = previous?.takeIf { it.status == 200 }?.body?.decodeToString()?.substringBefore('\n')
        if (!closed && reach == Reach.Library && before != digest && !(previous == null && image == null)) runCatching { onArtistImageChanged(mbid) }
    }

    private fun canonical(artist: ArtistRef): String? =
        artist.musicBrainzId?.let { runCatching { com.legitimateapps.dulcet.server.discover.canonicalMbid(it.trim().lowercase()) }.getOrNull() }
    private fun pointerKey(mbid: String, reach: Reach) = CacheKey("dulcet:integrations", "/artist-image", mapOf("mbid" to mbid, "reach" to reach.name))

    /** Stops refreshing; waits briefly for one already writing, so nothing writes after the database closes. */
    override fun close() {
        closed = true
        val jobs = refreshing.values.toList()
        scope.cancel()
        runBlocking { withTimeoutOrNull(CLOSE_WAIT_MILLIS) { jobs.joinAll() } }
    }

    companion object {
        const val MAX_PENDING_REFRESHES = 256
        const val CLOSE_WAIT_MILLIS = 2_000L
        const val OUTAGE_SKIP_NANOS = 60_000_000_000L
        const val POINTER_REFRESH_MILLIS = DiscoverCache.HOUR
        private val OUTAGES = setOf(UpstreamFailure.Timeout, UpstreamFailure.Transport, UpstreamFailure.DnsTimeout, UpstreamFailure.DnsTransport,
            UpstreamFailure.HttpStatus, UpstreamFailure.Backoff, UpstreamFailure.WaitingCap)
        val NONE = Integrations(emptyList())

        /** Configured connections, each declaring what it offers. */
        fun connections(settings: IntegrationSettings, http: PoliteHttp, cache: DiscoverCache, testingOverride: () -> Boolean = { false },
                        resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName): List<Connection> =
            listOfNotNull(
                settings.lidarr?.let { LidarrConnection(it, settings.lidarrImageHosts, http, cache, testingOverride, resolver) },
                settings.jellyfin?.let { MediaBrowserConnection("jellyfin", it, http, cache) },
                settings.emby?.let { MediaBrowserConnection("emby", it, http, cache) },
            )

        /**
         * The artist-image capability: [IntegrationSettings.artistImageOrder] names the offered sources in the order they are
         * asked; a configured connection left out of it is not asked for images. The default is Lidarr's library,
         * Jellyfin, Emby (all on the operator's network), then Lidarr's outside feed.
         */
        fun from(settings: IntegrationSettings, http: PoliteHttp, cache: DiscoverCache, testingOverride: () -> Boolean = { false },
                 resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName): Integrations {
            val offered = connections(settings, http, cache, testingOverride, resolver).flatMap { it.artistImages.entries }.associate { it.key to it.value }
            val order = settings.artistImageOrder
            if (order != null) order.forEach { require(it in offered) { "integration-artist-images names $it, which no configured connection offers" } }
            // Operator services are spaced by their own queue only; the outside image host keeps the default gap.
            listOfNotNull(settings.lidarr, settings.jellyfin, settings.emby).forEach { http.configureOrigin(it.url, 0) }
            return Integrations((order ?: DEFAULT_ARTIST_IMAGE_ORDER).mapNotNull { offered[it] }, cache)
        }
        val DEFAULT_ARTIST_IMAGE_ORDER = listOf("lidarr", "jellyfin", "emby", "lidarr-feed")
    }
}
