package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.Credit
import com.legitimateapps.dulcet.core.CreditRole
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.library.LibraryIndex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class LibraryIndexTest {
    @Test fun eachAlbumOwnsTheTracksThatFollowItAndArtistsMatchByIdBeforeName() {
        val byId = item("artist-1", SearchResultType.Artist, "Shared Name")
        val byName = item("artist-2", SearchResultType.Artist, "Named Only")
        val rows = listOf(byId, byName,
            item("album-a", SearchResultType.Album, "A", credit("Shared Name", "artist-1"), art = "cover-a"),
            item("t1", SearchResultType.Track, "One"), item("t2", SearchResultType.Track, "Two"),
            item("album-b", SearchResultType.Album, "B", credit("Named Only", null)),
            item("t3", SearchResultType.Track, "Three"),
            // Same display name, different identity: must not be attributed to artist-1.
            item("album-c", SearchResultType.Album, "C", credit("Shared Name", "artist-9")))

        val index = LibraryIndex.from(rows)

        assertEquals(listOf("album-a", "album-b", "album-c"), index.albums.map { it.item.id.rawId })
        assertEquals(listOf("t1", "t2"), index.album("album-a")!!.tracks.map { it.id.rawId })
        assertEquals(listOf("t3"), index.album("album-b")!!.tracks.map { it.id.rawId })
        assertEquals(emptyList(), index.album("album-c")!!.tracks)
        assertEquals(listOf("album-a"), index.albumsBy(index.artist("artist-1")!!).map { it.item.id.rawId })
        assertEquals(listOf("album-b"), index.albumsBy(index.artist("artist-2")!!).map { it.item.id.rawId })

        val playable = index.album("album-a")!!.playable()
        assertEquals(listOf("t1", "t2"), playable.map { it.rawId })
        assertEquals("cover-a", playable.first().artworkKey, "A track without its own cover uses its album's")
        assertEquals("A", playable.first().album)
        assertEquals("Shared Name", playable.first().artist)
    }

    private fun credit(name: String, id: String?) =
        listOf(Credit(CreditRole.entries.first(), name, id?.let { ProviderItemId(PROVIDER, it) }))

    private fun item(id: String, type: SearchResultType, title: String, credits: List<Credit> = emptyList(), art: String? = null) =
        SearchResultItem(ProviderItemId(PROVIDER, id), type, title, credits, null, null,
            if (type == SearchResultType.Track) 30.seconds else null, null, null, null, null, art)

    private companion object { const val PROVIDER = "provider:index" }
}
