package com.legitimateapps.dulcet.server.integrations

import com.legitimateapps.dulcet.server.discover.DiscoverCache
import com.legitimateapps.dulcet.server.discover.PoliteHttp
import com.legitimateapps.dulcet.server.discover.UpstreamFailure
import com.legitimateapps.dulcet.server.discover.UpstreamNotFound
import com.legitimateapps.dulcet.server.discover.UpstreamUnavailable
import kotlinx.coroutines.CancellationException
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

/** Validated image bytes; [key] is a stable identity for downstream resize caches. */
class RemoteImage(val bytes: ByteArray, val contentType: String, val key: String)

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
    /** null when this source has no image for the artist. Throws [UpstreamUnavailable] when the source failed. */
    suspend fun artistImage(artist: ArtistRef, reach: Reach): RemoteImage?
}

/**
 * The configured sources in order. A source that fails with an outage is skipped for a minute, so an
 * unreachable tool costs one timeout a minute rather than one per artist on a page.
 */
class Integrations(private val sources: List<ArtistImageSource>, private val monotonicNanos: () -> Long = System::nanoTime) : ArtistImageSource {
    val configured: Boolean get() = sources.isNotEmpty()
    private val downUntil = ConcurrentHashMap<ArtistImageSource, Long>()
    override suspend fun artistImage(artist: ArtistRef, reach: Reach): RemoteImage? {
        val mbid = artist.musicBrainzId?.let { runCatching { com.legitimateapps.dulcet.server.discover.canonicalMbid(it.trim().lowercase()) }.getOrNull() } ?: return null
        val ref = artist.copy(musicBrainzId = mbid)
        for (source in sources) {
            val until = downUntil[source]
            if (until != null && until - monotonicNanos() > 0) continue
            try {
                source.artistImage(ref, reach)?.let { return it }
            } catch (e: CancellationException) { throw e }
              catch (e: UpstreamUnavailable) { if (e.reason in OUTAGES) downUntil[source] = monotonicNanos() + OUTAGE_SKIP_NANOS }
              catch (_: UpstreamNotFound) { }
              catch (_: Exception) { }
        }
        return null
    }
    companion object {
        const val OUTAGE_SKIP_NANOS = 60_000_000_000L
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
            return Integrations((order ?: DEFAULT_ARTIST_IMAGE_ORDER).mapNotNull { offered[it] })
        }
        val DEFAULT_ARTIST_IMAGE_ORDER = listOf("lidarr", "jellyfin", "emby", "lidarr-feed")
    }
}
