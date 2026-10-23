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
data class DiscoverItem(val id: String = "", val kind: DiscoverKind, val title: String, val artist: String? = null, val year: Int? = null, val releaseDate: String? = null, val externalIds: ExternalIds = emptyMap(), val artworkId: String? = null, val links: List<ExternalLink> = emptyList(), val wishable: Boolean = true, val availability: Availability = Availability(), val disc: Int? = null, val position: Int? = null, val listenCount: Long? = null, val artistMbids: List<String> = emptyList(), val primaryType: String? = null)
data class CacheAge(val fetchedAt: Long, val ageSeconds: Long, val stale: Boolean)
data class DiscoverPage<T>(val items: List<T>, val nextCursor: String?, val age: CacheAge)
data class PageRequest(val limit: Int = 20, val cursor: String? = null) { init { require(limit in 1..100) { "Limit must be 1..100" } } }
data class UpstreamPage<T>(val items: List<T>, val total: Int, val offset: Int)
data class Edition(val releaseMbid:String,val releaseGroupMbid:String,val tracks:List<EditionTrack>,val links:List<ExternalLink> = emptyList(),val releaseDate:String? = null)
data class EditionTrack(val disc:Int,val position:Int,val recordingMbid:String?,val title:String,val artist:String,val durationMillis:Long?)
fun <T,R> Cached<T>.map(transform:(T)->R)=Cached(transform(value),age,generation,dependencies)
fun <T> combineCached(parts:List<Cached<*>>,value:T):Cached<T> {
    require(parts.isNotEmpty())
    val age=parts.minBy { it.age.fetchedAt }.age.copy(stale=parts.any { it.age.stale })
    val generation=java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString(":") { it.generation }.toByteArray()).joinToString("") { "%02x".format(it) }
    return Cached(value,age,generation,parts.flatMap { it.dependencies.entries }.associate { it.key to it.value })
}
data class PlaylistSummary(val id:String,val title:String,val creator:String?,val trackCount:Int?)
data class PlaylistEntry(val position:Int,val item:DiscoverItem)
data class DiscoverPlaylist(val summary:PlaylistSummary,val entries:List<PlaylistEntry>)
data class DiscoverArtistPage(val item:DiscoverItem,val albums:DiscoverPage<DiscoverItem>)
data class DiscoverAlbumPage(val item:DiscoverItem,val editions:List<Edition>,val selectedReleaseId:String?,val tracks:DiscoverPage<DiscoverItem>)
data class DiscoverPlaylistPage(val summary:PlaylistSummary,val entries:DiscoverPage<PlaylistEntry>)
