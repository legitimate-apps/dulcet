package com.legitimateapps.dulcet.server.discover

import java.util.UUID

typealias ExternalIds = Map<String, String>
enum class DiscoverKind(val wire: String, val registryKey: String, val catalogType: String) {
    Artist("artist", "musicbrainzArtistId", "artist"), Album("album", "musicbrainzReleaseGroupId", "album"), Song("song", "musicbrainzRecordingId", "track");
    companion object { fun parse(value: String): DiscoverKind = entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("Invalid discover kind") }
}
fun canonicalMbid(value: String): String {
    val id = UUID.fromString(value).toString()
    require(id.equals(value, true)) { "Invalid MusicBrainz id" }
    return id
}
data class LibraryRef(val id: String, val type: String)
data class ExternalLink(val relation: String, val url: String)
data class Availability(val status: String = "notHeld", val libraryItems: List<LibraryRef> = emptyList(), val matchedBy: String? = null, val matchedTrackCount: Int? = null, val totalTrackCount: Int? = null, val editionReleaseId: String? = null, val completeness: String = "unknown")
data class DiscoverItem(val id: String = "", val kind: DiscoverKind, val title: String, val artist: String? = null, val year: Int? = null, val releaseDate: String? = null, val externalIds: ExternalIds = emptyMap(), val artworkId: String? = null, val links: List<ExternalLink> = emptyList(), val wishable: Boolean = true, val availability: Availability = Availability(), val disc: Int? = null, val position: Int? = null, val listenCount: Long? = null)
data class CacheAge(val fetchedAt: Long, val ageSeconds: Long, val stale: Boolean)
data class DiscoverPage<T>(val items: List<T>, val nextCursor: String?, val age: CacheAge)
data class PageRequest(val limit: Int = 20, val cursor: String? = null) { init { require(limit in 1..100) { "Limit must be 1..100" } } }
data class UpstreamPage<T>(val items: List<T>, val total: Int, val offset: Int)
