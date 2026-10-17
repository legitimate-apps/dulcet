package com.legitimateapps.dulcet.search.conformance

import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.search.*

/** Enough rows per kind to exercise section expansion without replacing the presenter. */
class LibraryDepthSearchFixture : SearchSource {
    val committed = mutableListOf<String>()
    var cleared = false
    override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle = object : SearchSourceHandle {
        override fun updateQuery(text: String) {
            val rows = if (text.isBlank()) emptyList() else listOf(SearchResultType.Track, SearchResultType.Album, SearchResultType.Artist).flatMap { type ->
                (0..4).map { index -> AndroidLibrarySearchRow(SearchResultItem(
                    id = ProviderItemId("depth-search", "${type.name}-$index"), type = type, title = "${type.name} $index",
                    credits = emptyList(), albumTitle = null, year = null, duration = null, discNumber = null,
                    trackNumber = null, sourceContainer = null, mediaSourceId = null, artworkKey = null,
                ), AndroidLibrarySearchRowSource.Server, null, null, null) }
            }
            listener(AndroidLibrarySearchPublication(text, 1, AndroidLibrarySearchScope.ServerAndDevice, rows))
        }
        override fun refresh() = Unit
        override fun close() = Unit
        override fun recentSearches(completion: (List<String>) -> Unit) { completion(if (cleared) emptyList() else listOf("Previous search") + committed.asReversed()) }
        override fun recordSearch(text: String) { committed += text }
        override fun clearRecentSearches() { cleared = true }
    }
}
