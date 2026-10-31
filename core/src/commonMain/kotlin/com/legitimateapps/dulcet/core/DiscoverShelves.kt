@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
package com.legitimateapps.dulcet.core

import kotlin.native.HiddenFromObjC
import kotlinx.serialization.Serializable

@HiddenFromObjC
@Serializable
data class DiscoverPlaylistSummary(val id: String, val title: String, val creator: String?, val trackCount: Int?)
@HiddenFromObjC
@Serializable
data class DiscoverPlaylistEntry(val position: Int, val item: DiscoverItem)
@HiddenFromObjC
@Serializable
data class DiscoverPlaylistList(val items: List<DiscoverPlaylistSummary>, val nextCursor: String?, val age: DiscoverAge,
    val refreshError: DiscoverFailure? = null)
@HiddenFromObjC
@Serializable
data class DiscoverPlaylist(val summary: DiscoverPlaylistSummary, val entries: List<DiscoverPlaylistEntry>,
    val nextCursor: String?, val age: DiscoverAge, val refreshError: DiscoverFailure? = null)
@HiddenFromObjC
@Serializable
data class DiscoverShelfPage(val id: String, val items: List<DiscoverItem>, val playlists: List<DiscoverPlaylistSummary>,
    val nextCursor: String?, val age: DiscoverAge, val refreshError: DiscoverFailure? = null)
@HiddenFromObjC
@Serializable
data class DiscoverShelfState(val id: String, val page: DiscoverShelfPage?, val loading: Boolean, val error: DiscoverFailure?)
@HiddenFromObjC
@Serializable
data class DiscoverHome(val shelves: List<DiscoverShelfState>, val recentWishes: List<WishRow>,
    val wishesLoading: Boolean, val wishesSettled: Boolean, val wishesError: DiscoverFailure?)

/** Ordering is shared by all shells. Server ordering within each shelf remains intact. */
internal val discoverShelfIds = listOf("trendingArtists", "trendingAlbums", "trendingSongs", "newReleases", "similarArtists", "publicPlaylists")
internal fun recentDiscoverWishes(rows: List<WishRow>): List<WishRow> = rows.sortedWith(
    compareByDescending<WishRow> { it.pendingAdd?.actionTimeMillis ?: it.wish?.addedAtMillis ?: Long.MIN_VALUE }.thenBy { it.key }
).take(6)
