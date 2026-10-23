@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.legitimateapps.dulcet.core

import kotlin.native.HiddenFromObjC
import kotlinx.serialization.Serializable

@HiddenFromObjC
@Serializable
enum class DiscoverKind { Artist, Album, Song } // wire artist, album, song
@HiddenFromObjC
@Serializable
data class DiscoverTarget(val kind: DiscoverKind, val mbid: String)
@HiddenFromObjC
@Serializable
data class DiscoverLibraryRef(val providerInstanceId: String, val type: String, val id: String)
@HiddenFromObjC
@Serializable
data class DiscoverLink(val relation: String, val url: String)
@HiddenFromObjC
@Serializable
data class DiscoverAge(val fetchedAtMillis: Long?, val sourceAgeSeconds: Long?,
    val receivedAtMillis: Long, val stale: Boolean, val fromDeviceCache: Boolean)
@HiddenFromObjC
@Serializable
data class DiscoverAvailability(val status: String, val completeness: String,
    val matchedBy: String?, val matchedTrackCount: Int?, val totalTrackCount: Int?,
    val editionReleaseId: String?, val libraryItems: List<DiscoverLibraryRef>)
@HiddenFromObjC
@Serializable
data class DiscoverItem(val id: String, val kind: String, val target: DiscoverTarget?,
    val title: String, val artist: String?, val year: Int?, val releaseDate: String?,
    val artworkId: String?, val disc: Int?, val position: Int?, val listenCount: Long?,
    val primaryType: String?, val links: List<DiscoverLink>, val wishable: Boolean,
    val availability: DiscoverAvailability)
@HiddenFromObjC
@Serializable
data class DiscoverPage(val items: List<DiscoverItem>, val nextCursor: String?, val age: DiscoverAge)
@HiddenFromObjC
@Serializable
data class DiscoverEdition(val releaseId: String, val releaseDate: String?,
    val trackCount: Int?, val links: List<DiscoverLink>)
@HiddenFromObjC
@Serializable
data class DiscoverArtist(val item: DiscoverItem, val albums: DiscoverPage)
@HiddenFromObjC
@Serializable
data class DiscoverAlbum(val item: DiscoverItem, val editions: List<DiscoverEdition>,
    val selectedReleaseId: String?, val tracks: DiscoverPage)
@HiddenFromObjC
@Serializable
data class DiscoverFailure(val code: String, val retryAtMillis: Long?, val retryable: Boolean)
// code: offline, cancelled, unauthorized, forbidden, unsupported, identity-changed,
// rate-limited, upstream-unavailable, cursor-expired, invalid-response, missing-art, conflict, storage.
// Preserve unknown wire state separately; never put raw URLs or server exception text in code.
@HiddenFromObjC
@Serializable
sealed interface DiscoverResult<out T> {
    @Serializable
    data class Value<T>(val value: T) : DiscoverResult<T>
    @Serializable
    data class Failed(val error: DiscoverFailure) : DiscoverResult<Nothing>
}
@HiddenFromObjC
@Serializable
data class WishSnapshot(val title: String, val artist: String?, val year: Int?, val artworkId: String?)
@HiddenFromObjC
@Serializable
data class WishSource(val type: String, val id: String?) // search/artist/album now; shelf/playlist later
@HiddenFromObjC
@Serializable
data class DiscoverWish(val id: String, val userId: String, val target: DiscoverTarget?,
    val kind: String, val snapshot: WishSnapshot, val source: WishSource,
    val addedAtMillis: Long, val updatedAtMillis: Long, val state: String,
    val note: String?, val noteAtMillis: Long?, val fulfilledAtMillis: Long?,
    val libraryItem: DiscoverLibraryRef?, val availability: DiscoverAvailability, val revision: String)
@HiddenFromObjC
@Serializable
data class WishFilter(val kind: String? = null, val state: String? = null,
    val includeFulfilledHistory: Boolean = false)
internal data class WishListSnapshot(val items: List<DiscoverWish>, val stamp: String?)
@HiddenFromObjC
@Serializable
sealed interface WishMutation {
    val operationId: String
    @Serializable
    data class Add(override val operationId: String, val actionTimeMillis: Long,
        val target: DiscoverTarget, val snapshot: WishSnapshot, val source: WishSource) : WishMutation
    @Serializable
    data class Remove(override val operationId: String, val actionTimeMillis: Long,
        val wishId: String?, val addOperationId: String?) : WishMutation // exactly one target
    @Serializable
    data class Reject(override val operationId: String, val target: DiscoverTarget,
        val libraryItem: DiscoverLibraryRef, val wishId: String?) : WishMutation
}
internal data class WishReceipt(val applied: Boolean, val wish: DiscoverWish?, val removed: Boolean)
internal interface DiscoverTransport {
    suspend fun search(kind: DiscoverKind, query: String, cursor: String?, limit: Int = 20): DiscoverResult<DiscoverPage>
    suspend fun artist(mbid: String): DiscoverResult<DiscoverArtist>
    suspend fun artistAlbums(mbid: String, cursor: String?, limit: Int = 20): DiscoverResult<DiscoverPage>
    suspend fun album(mbid: String, releaseId: String?): DiscoverResult<DiscoverAlbum>
    suspend fun albumTracks(mbid: String, releaseId: String, cursor: String?, limit: Int = 20): DiscoverResult<DiscoverPage>
    suspend fun artwork(artworkId: String): DiscoverResult<ByteArray>
}
internal interface WishlistTransport {
    suspend fun snapshot(filter: WishFilter): DiscoverResult<WishListSnapshot>
    suspend fun wish(id: String): DiscoverResult<DiscoverWish>
    suspend fun apply(mutation: WishMutation): DiscoverResult<WishReceipt>
}
@HiddenFromObjC
@Serializable
data class DiscoverFeatureAccess(val detection: String,
    val error: DiscoverFailure?, val discoverAvailable: Boolean,
    val wishlistAvailable: Boolean, val canSearch: Boolean,
    val canReadWishlist: Boolean, val canAddWish: Boolean, val canRemoveWish: Boolean,
    val canRejectMatch: Boolean)
// detection: known/unresolved. A failed probe is not a confirmed absence.
@HiddenFromObjC
@Serializable
data class DiscoverGroup(val kind: String, val page: DiscoverPage?, val loading: Boolean,
    val error: DiscoverFailure?)
@HiddenFromObjC
@Serializable
data class WishRow(val key: String, val wish: DiscoverWish?, val pendingAdd: WishMutation.Add?,
    val pending: Boolean, val error: DiscoverFailure?) // key stable across acknowledgement
@HiddenFromObjC
@Serializable
data class DiscoverPublication(val providerInstanceId: String, val generation: Long,
    val access: DiscoverFeatureAccess, val connection: String, val query: String,
    val groups: List<DiscoverGroup>, // connection: online/offline/validating
    val wishes: List<WishRow>, val wishlistLoading: Boolean, val wishlistError: DiscoverFailure?,
    val wishlistSettled: Boolean, val pendingChangeCount: Long?)
@HiddenFromObjC
@Serializable
data class WishEnqueued(val operationId: String, val rowKey: String)
@HiddenFromObjC
interface DiscoverRegistration { fun close() }
@HiddenFromObjC
interface DiscoverOperation { fun cancel() }
@HiddenFromObjC
interface DiscoverClient {
    fun observe(listener: (DiscoverPublication) -> Unit): DiscoverRegistration
    fun setQuery(query: String)
    fun loadMore(kind: String)
    fun artist(mbid: String, completion: (DiscoverResult<DiscoverArtist>) -> Unit): DiscoverOperation
    fun artistAlbums(mbid: String, cursor: String?, completion: (DiscoverResult<DiscoverPage>) -> Unit): DiscoverOperation
    fun album(mbid: String, releaseId: String?, completion: (DiscoverResult<DiscoverAlbum>) -> Unit): DiscoverOperation
    fun albumTracks(mbid: String, releaseId: String, cursor: String?, completion: (DiscoverResult<DiscoverPage>) -> Unit): DiscoverOperation
    fun artwork(artworkId: String, completion: (DiscoverResult<ByteArray>) -> Unit): DiscoverOperation
    fun refreshWishlist(filter: WishFilter)
    fun add(item: DiscoverItem, source: WishSource, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation
    fun remove(rowKey: String, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation
    fun reject(item: DiscoverItem, libraryItem: DiscoverLibraryRef, wishId: String?, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation
    fun close()
}
