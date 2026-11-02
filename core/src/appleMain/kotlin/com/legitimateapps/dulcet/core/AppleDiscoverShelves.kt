package com.legitimateapps.dulcet.core

/** Copied shelf and playlist values at the Objective-C boundary. */
public class AppleDiscoverPlaylistSummary internal constructor(
    public val id: String,
    public val title: String,
    public val creator: String?,
    public val trackCount: Int?
) {
}
internal fun DiscoverPlaylistSummary.appleDiscover(): AppleDiscoverPlaylistSummary = AppleDiscoverPlaylistSummary(
    id, title, creator, trackCount
)

public class AppleDiscoverPlaylistEntry internal constructor(
    public val position: Int,
    public val item: AppleDiscoverItem
) {
}
internal fun DiscoverPlaylistEntry.appleDiscover(): AppleDiscoverPlaylistEntry = AppleDiscoverPlaylistEntry(
    position, item.appleDiscover()
)

public class AppleDiscoverPlaylistList internal constructor(
    public val nextCursor: String?,
    public val age: AppleDiscoverAge,
    public val refreshError: AppleDiscoverFailure?,
    items: List<AppleDiscoverPlaylistSummary>
) {
    private val items = items.toList()
    public val itemCount: Int get() = items.size
    public fun itemAt(index: Int): AppleDiscoverPlaylistSummary? = items.getOrNull(index)
}
internal fun DiscoverPlaylistList.appleDiscover(): AppleDiscoverPlaylistList = AppleDiscoverPlaylistList(
    nextCursor, age.appleDiscover(), refreshError?.appleDiscover(), items.map {it.appleDiscover()}
)

public class AppleDiscoverPlaylist internal constructor(
    public val summary: AppleDiscoverPlaylistSummary,
    public val nextCursor: String?,
    public val age: AppleDiscoverAge,
    public val refreshError: AppleDiscoverFailure?,
    entries: List<AppleDiscoverPlaylistEntry>
) {
    private val entries = entries.toList()
    public val entryCount: Int get() = entries.size
    public fun entryAt(index: Int): AppleDiscoverPlaylistEntry? = entries.getOrNull(index)
}
internal fun DiscoverPlaylist.appleDiscover(): AppleDiscoverPlaylist = AppleDiscoverPlaylist(
    summary.appleDiscover(), nextCursor, age.appleDiscover(), refreshError?.appleDiscover(), entries.map {it.appleDiscover()}
)

public class AppleDiscoverShelfPage internal constructor(
    public val id: String,
    public val nextCursor: String?,
    public val age: AppleDiscoverAge,
    public val refreshError: AppleDiscoverFailure?,
    items: List<AppleDiscoverItem>,
    playlists: List<AppleDiscoverPlaylistSummary>
) {
    private val items = items.toList()
    public val itemCount: Int get() = items.size
    public fun itemAt(index: Int): AppleDiscoverItem? = items.getOrNull(index)
    private val playlists = playlists.toList()
    public val playlistCount: Int get() = playlists.size
    public fun playlistAt(index: Int): AppleDiscoverPlaylistSummary? = playlists.getOrNull(index)
}
internal fun DiscoverShelfPage.appleDiscover(): AppleDiscoverShelfPage = AppleDiscoverShelfPage(
    id, nextCursor, age.appleDiscover(), refreshError?.appleDiscover(), items.map {it.appleDiscover()}, playlists.map {it.appleDiscover()}
)

public class AppleDiscoverShelfState internal constructor(
    public val id: String,
    public val page: AppleDiscoverShelfPage?,
    public val loading: Boolean,
    public val error: AppleDiscoverFailure?
) {
}
internal fun DiscoverShelfState.appleDiscover(): AppleDiscoverShelfState = AppleDiscoverShelfState(
    id, page?.appleDiscover(), loading, error?.appleDiscover()
)

public class AppleDiscoverHome internal constructor(
    public val wishesLoading: Boolean,
    public val wishesSettled: Boolean,
    public val wishesError: AppleDiscoverFailure?,
    shelves: List<AppleDiscoverShelfState>,
    recentWishes: List<AppleWishRow>
) {
    private val shelves = shelves.toList()
    public val shelfCount: Int get() = shelves.size
    public fun shelfAt(index: Int): AppleDiscoverShelfState? = shelves.getOrNull(index)
    private val recentWishes = recentWishes.toList()
    public val recentWishCount: Int get() = recentWishes.size
    public fun recentWishAt(index: Int): AppleWishRow? = recentWishes.getOrNull(index)
}
internal fun DiscoverHome.appleDiscover(): AppleDiscoverHome = AppleDiscoverHome(
    wishesLoading, wishesSettled, wishesError?.appleDiscover(), shelves.map {it.appleDiscover()}, recentWishes.map {it.appleDiscover()}
)

public class AppleDiscoverShelfPageResult internal constructor(
    public val value: AppleDiscoverShelfPage?,
    public val error: AppleDiscoverFailure?,
)
internal fun DiscoverResult<DiscoverShelfPage>.appleDiscoverShelfPageResult(): AppleDiscoverShelfPageResult = when(this) {
    is DiscoverResult.Value -> AppleDiscoverShelfPageResult(value.appleDiscover(),null)
    is DiscoverResult.Failed -> AppleDiscoverShelfPageResult(null,error.appleDiscover())
}

public class AppleDiscoverPlaylistListResult internal constructor(
    public val value: AppleDiscoverPlaylistList?,
    public val error: AppleDiscoverFailure?,
)
internal fun DiscoverResult<DiscoverPlaylistList>.appleDiscoverPlaylistListResult(): AppleDiscoverPlaylistListResult = when(this) {
    is DiscoverResult.Value -> AppleDiscoverPlaylistListResult(value.appleDiscover(),null)
    is DiscoverResult.Failed -> AppleDiscoverPlaylistListResult(null,error.appleDiscover())
}

public class AppleDiscoverPlaylistResult internal constructor(
    public val value: AppleDiscoverPlaylist?,
    public val error: AppleDiscoverFailure?,
)
internal fun DiscoverResult<DiscoverPlaylist>.appleDiscoverPlaylistResult(): AppleDiscoverPlaylistResult = when(this) {
    is DiscoverResult.Value -> AppleDiscoverPlaylistResult(value.appleDiscover(),null)
    is DiscoverResult.Failed -> AppleDiscoverPlaylistResult(null,error.appleDiscover())
}

