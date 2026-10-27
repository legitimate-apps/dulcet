@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.legitimateapps.dulcet.core

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.posix.memcpy

/** Fixed Objective-C values. Lists are copied and exposed through safe count/index methods only. */
public class AppleDiscoverTarget(
    public val kind: String,
    public val mbid: String,
) {
    internal fun common(): DiscoverTarget? = DiscoverKind.entries.firstOrNull { it.wire == kind }?.let { DiscoverTarget(it, mbid) }
}

public class AppleDiscoverLibraryRef(
    public val providerInstanceId: String,
    public val type: String,
    public val id: String,
) {
    internal fun common(): DiscoverLibraryRef = DiscoverLibraryRef(providerInstanceId, type, id)
}

public class AppleDiscoverLink(
    public val relation: String,
    public val url: String,
) {
    internal fun common(): DiscoverLink = DiscoverLink(relation, url)
}

public class AppleDiscoverAge internal constructor(
    public val fetchedAtMillis: Long?,
    public val sourceAgeSeconds: Long?,
    public val receivedAtMillis: Long,
    public val stale: Boolean,
    public val fromDeviceCache: Boolean,
) {
}

public class AppleDiscoverAvailability internal constructor(
    public val status: String,
    public val completeness: String,
    public val matchedBy: String?,
    public val matchedTrackCount: Int?,
    public val totalTrackCount: Int?,
    public val editionReleaseId: String?,
    libraryItems: List<AppleDiscoverLibraryRef>,
) {
    private val libraryItems = libraryItems.toList()
    public val libraryItemCount: Int get() = libraryItems.size
    public fun libraryItemAt(index: Int): AppleDiscoverLibraryRef? = libraryItems.getOrNull(index)
    internal fun common(): DiscoverAvailability = DiscoverAvailability(status, completeness, matchedBy, matchedTrackCount, totalTrackCount, editionReleaseId, libraryItems.map { it.common() })
}

public class AppleDiscoverItem internal constructor(
    public val id: String,
    public val kind: String,
    public val target: AppleDiscoverTarget?,
    public val title: String,
    public val artist: String?,
    public val year: Int?,
    public val releaseDate: String?,
    public val artworkId: String?,
    public val disc: Int?,
    public val position: Int?,
    public val listenCount: Long?,
    public val primaryType: String?,
    links: List<AppleDiscoverLink>,
    public val wishable: Boolean,
    public val availability: AppleDiscoverAvailability,
) {
    private val links = links.toList()
    public val linkCount: Int get() = links.size
    public fun linkAt(index: Int): AppleDiscoverLink? = links.getOrNull(index)
    internal fun common(): DiscoverItem = DiscoverItem(id, kind, target?.common(), title, artist, year, releaseDate, artworkId, disc, position, listenCount, primaryType, links.map { it.common() }, wishable, availability.common())
}

public class AppleDiscoverPage internal constructor(
    items: List<AppleDiscoverItem>,
    public val nextCursor: String?,
    public val age: AppleDiscoverAge,
) {
    private val items = items.toList()
    public val itemCount: Int get() = items.size
    public fun itemAt(index: Int): AppleDiscoverItem? = items.getOrNull(index)
}

public class AppleDiscoverEdition internal constructor(
    public val releaseId: String,
    public val releaseDate: String?,
    public val trackCount: Int?,
    links: List<AppleDiscoverLink>,
) {
    private val links = links.toList()
    public val linkCount: Int get() = links.size
    public fun linkAt(index: Int): AppleDiscoverLink? = links.getOrNull(index)
}

public class AppleDiscoverArtist internal constructor(
    public val item: AppleDiscoverItem,
    public val albums: AppleDiscoverPage,
) {
}

public class AppleDiscoverAlbum internal constructor(
    public val item: AppleDiscoverItem,
    editions: List<AppleDiscoverEdition>,
    public val selectedReleaseId: String?,
    public val tracks: AppleDiscoverPage,
) {
    private val editions = editions.toList()
    public val editionCount: Int get() = editions.size
    public fun editionAt(index: Int): AppleDiscoverEdition? = editions.getOrNull(index)
}

public class AppleDiscoverFailure internal constructor(
    public val code: String,
    public val retryAtMillis: Long?,
    public val retryable: Boolean,
) {
}

public class AppleWishSnapshot(
    public val title: String,
    public val artist: String?,
    public val year: Int?,
    public val artworkId: String?,
) {
    internal fun common(): WishSnapshot = WishSnapshot(title, artist, year, artworkId)
}

public class AppleWishSource(
    public val type: String,
    public val id: String?,
) {
    internal fun common(): WishSource = WishSource(type, id)
}

public class AppleDiscoverWish internal constructor(
    public val id: String,
    public val userId: String,
    public val target: AppleDiscoverTarget?,
    public val kind: String,
    public val snapshot: AppleWishSnapshot,
    public val source: AppleWishSource,
    public val addedAtMillis: Long,
    public val updatedAtMillis: Long,
    public val state: String,
    public val note: String?,
    public val noteAtMillis: Long?,
    public val fulfilledAtMillis: Long?,
    public val libraryItem: AppleDiscoverLibraryRef?,
    public val availability: AppleDiscoverAvailability,
    public val revision: String,
) {
}

public class AppleWishFilter(
    public val kind: String?,
    public val state: String?,
    public val includeFulfilledHistory: Boolean,
) {
    internal fun common(): WishFilter = WishFilter(kind, state, includeFulfilledHistory)
}

public class AppleDiscoverFeatureAccess internal constructor(
    public val detection: String,
    public val error: AppleDiscoverFailure?,
    public val discoverAvailable: Boolean,
    public val wishlistAvailable: Boolean,
    public val canSearch: Boolean,
    public val canReadWishlist: Boolean,
    public val canAddWish: Boolean,
    public val canRemoveWish: Boolean,
    public val canRejectMatch: Boolean,
) {
}

public class AppleDiscoverGroup internal constructor(
    public val kind: String,
    public val page: AppleDiscoverPage?,
    public val loading: Boolean,
    public val error: AppleDiscoverFailure?,
) {
}

public class ApplePendingWishAdd internal constructor(
    public val operationId: String,
    public val actionTimeMillis: Long,
    public val target: AppleDiscoverTarget,
    public val snapshot: AppleWishSnapshot,
    public val source: AppleWishSource,
) {
}

public class AppleWishRow internal constructor(
    public val key: String,
    public val wish: AppleDiscoverWish?,
    public val pendingAdd: ApplePendingWishAdd?,
    public val pending: Boolean,
    public val error: AppleDiscoverFailure?,
) {
}

public class AppleDiscoverPublication internal constructor(
    public val providerInstanceId: String,
    public val generation: Long,
    public val access: AppleDiscoverFeatureAccess,
    public val connection: String,
    public val query: String,
    groups: List<AppleDiscoverGroup>,
    wishes: List<AppleWishRow>,
    public val wishlistLoading: Boolean,
    public val wishlistError: AppleDiscoverFailure?,
    public val wishlistSettled: Boolean,
    public val pendingChangeCount: Long?,
    public val home: AppleDiscoverHome? = null,
) {
    private val groups = groups.toList()
    public val groupCount: Int get() = groups.size
    public fun groupAt(index: Int): AppleDiscoverGroup? = groups.getOrNull(index)
    private val wishes = wishes.toList()
    public val wishCount: Int get() = wishes.size
    public fun wishAt(index: Int): AppleWishRow? = wishes.getOrNull(index)
}

public class AppleWishEnqueued internal constructor(
    public val operationId: String,
    public val rowKey: String,
) {
}

internal fun DiscoverTarget.appleDiscover(): AppleDiscoverTarget = AppleDiscoverTarget(
    kind.wire, mbid
)

internal fun DiscoverLibraryRef.appleDiscover(): AppleDiscoverLibraryRef = AppleDiscoverLibraryRef(
    providerInstanceId, type, id
)

internal fun DiscoverLink.appleDiscover(): AppleDiscoverLink = AppleDiscoverLink(
    relation, url
)

internal fun DiscoverAge.appleDiscover(): AppleDiscoverAge = AppleDiscoverAge(
    fetchedAtMillis, sourceAgeSeconds, receivedAtMillis, stale, fromDeviceCache
)

internal fun DiscoverAvailability.appleDiscover(): AppleDiscoverAvailability = AppleDiscoverAvailability(
    status, completeness, matchedBy, matchedTrackCount, totalTrackCount, editionReleaseId, libraryItems.map { it.appleDiscover() }
)

internal fun DiscoverItem.appleDiscover(): AppleDiscoverItem = AppleDiscoverItem(
    id, kind, target?.appleDiscover(), title, artist, year, releaseDate, artworkId, disc, position, listenCount, primaryType, links.map { it.appleDiscover() }, wishable, availability.appleDiscover()
)

internal fun DiscoverPage.appleDiscover(): AppleDiscoverPage = AppleDiscoverPage(
    items.map { it.appleDiscover() }, nextCursor, age.appleDiscover()
)

internal fun DiscoverEdition.appleDiscover(): AppleDiscoverEdition = AppleDiscoverEdition(
    releaseId, releaseDate, trackCount, links.map { it.appleDiscover() }
)

internal fun DiscoverArtist.appleDiscover(): AppleDiscoverArtist = AppleDiscoverArtist(
    item.appleDiscover(), albums.appleDiscover()
)

internal fun DiscoverAlbum.appleDiscover(): AppleDiscoverAlbum = AppleDiscoverAlbum(
    item.appleDiscover(), editions.map { it.appleDiscover() }, selectedReleaseId, tracks.appleDiscover()
)

internal fun DiscoverFailure.appleDiscover(): AppleDiscoverFailure = AppleDiscoverFailure(
    code, retryAtMillis, retryable
)

internal fun WishSnapshot.appleDiscover(): AppleWishSnapshot = AppleWishSnapshot(
    title, artist, year, artworkId
)

internal fun WishSource.appleDiscover(): AppleWishSource = AppleWishSource(
    type, id
)

internal fun DiscoverWish.appleDiscover(): AppleDiscoverWish = AppleDiscoverWish(
    id, userId, target?.appleDiscover(), kind, snapshot.appleDiscover(), source.appleDiscover(), addedAtMillis, updatedAtMillis, state, note, noteAtMillis, fulfilledAtMillis, libraryItem?.appleDiscover(), availability.appleDiscover(), revision
)

internal fun WishFilter.appleDiscover(): AppleWishFilter = AppleWishFilter(
    kind, state, includeFulfilledHistory
)

internal fun DiscoverFeatureAccess.appleDiscover(): AppleDiscoverFeatureAccess = AppleDiscoverFeatureAccess(
    detection, error?.appleDiscover(), discoverAvailable, wishlistAvailable, canSearch, canReadWishlist, canAddWish, canRemoveWish, canRejectMatch
)

internal fun DiscoverGroup.appleDiscover(): AppleDiscoverGroup = AppleDiscoverGroup(
    kind, page?.appleDiscover(), loading, error?.appleDiscover()
)

internal fun WishMutation.Add.appleDiscover(): ApplePendingWishAdd = ApplePendingWishAdd(
    operationId, actionTimeMillis, target.appleDiscover(), snapshot.appleDiscover(), source.appleDiscover()
)

internal fun WishRow.appleDiscover(): AppleWishRow = AppleWishRow(
    key, wish?.appleDiscover(), pendingAdd?.appleDiscover(), pending, error?.appleDiscover()
)

internal fun DiscoverPublication.appleDiscover(): AppleDiscoverPublication = AppleDiscoverPublication(
    providerInstanceId, generation, access.appleDiscover(), connection, query, groups.map { it.appleDiscover() }, wishes.map { it.appleDiscover() }, wishlistLoading, wishlistError?.appleDiscover(), wishlistSettled, pendingChangeCount, home?.appleDiscover()
)

internal fun WishEnqueued.appleDiscover(): AppleWishEnqueued = AppleWishEnqueued(
    operationId, rowKey
)

public class AppleDiscoverArtistResult internal constructor(
    public val value: AppleDiscoverArtist?,
    public val error: AppleDiscoverFailure?,
)

internal fun DiscoverResult<DiscoverArtist>.appleDiscoverArtistResult(): AppleDiscoverArtistResult = when (this) {
    is DiscoverResult.Value -> AppleDiscoverArtistResult(value.appleDiscover(), null)
    is DiscoverResult.Failed -> AppleDiscoverArtistResult(null, error.appleDiscover())
}

public class AppleDiscoverAlbumResult internal constructor(
    public val value: AppleDiscoverAlbum?,
    public val error: AppleDiscoverFailure?,
)

internal fun DiscoverResult<DiscoverAlbum>.appleDiscoverAlbumResult(): AppleDiscoverAlbumResult = when (this) {
    is DiscoverResult.Value -> AppleDiscoverAlbumResult(value.appleDiscover(), null)
    is DiscoverResult.Failed -> AppleDiscoverAlbumResult(null, error.appleDiscover())
}

public class AppleDiscoverPageResult internal constructor(
    public val value: AppleDiscoverPage?,
    public val error: AppleDiscoverFailure?,
)

internal fun DiscoverResult<DiscoverPage>.appleDiscoverPageResult(): AppleDiscoverPageResult = when (this) {
    is DiscoverResult.Value -> AppleDiscoverPageResult(value.appleDiscover(), null)
    is DiscoverResult.Failed -> AppleDiscoverPageResult(null, error.appleDiscover())
}

public class AppleWishEnqueueResult internal constructor(
    public val value: AppleWishEnqueued?,
    public val error: AppleDiscoverFailure?,
)

internal fun DiscoverResult<WishEnqueued>.appleWishEnqueueResult(): AppleWishEnqueueResult = when (this) {
    is DiscoverResult.Value -> AppleWishEnqueueResult(value.appleDiscover(), null)
    is DiscoverResult.Failed -> AppleWishEnqueueResult(null, error.appleDiscover())
}

public class AppleDiscoverArtworkResult internal constructor(
    public val bytes: NSData?,
    public val error: AppleDiscoverFailure?,
)

internal fun DiscoverResult<ByteArray>.appleDiscoverArtworkResult(): AppleDiscoverArtworkResult = when (this) {
    is DiscoverResult.Value -> AppleDiscoverArtworkResult(value.discoverNSData(), null)
    is DiscoverResult.Failed -> AppleDiscoverArtworkResult(null, error.appleDiscover())
}

private fun ByteArray.discoverNSData(): NSData {
    val data = NSMutableData()
    data.setLength(size.toULong())
    if (isNotEmpty()) usePinned { memcpy(data.mutableBytes, it.addressOf(0), size.toULong()) }
    return data
}
