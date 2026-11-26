package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which row leads a search: the best match, then artist over album over song, then the person's own taste. */
class TopResultTest {
    private fun row(
        id: String,
        type: SearchResultType,
        title: String,
        credit: String? = null,
        favourite: Boolean? = null,
        playCount: Long? = null,
    ) = LibrarySearchRow(
        item = SearchResultItem(
            id = ProviderItemId("provider-top-result-fixture", id),
            type = type,
            title = title,
            credits = listOfNotNull(credit?.let { Credit(CreditRole.Artist, it, null) }),
            albumTitle = null, year = null, duration = null, discNumber = null, trackNumber = null,
            sourceContainer = null, mediaSourceId = null, artworkKey = null,
        ),
        source = SearchResultSource.Server,
        favourite = favourite,
        rating = null,
        playCount = playCount,
    )

    @Test
    fun theArtistNamedByTheQueryLeadsItsOwnSongsAndAlbums() {
        val rows = listOf(
            row("t1", SearchResultType.Track, "Gold Soundz", credit = "Pavement"),
            row("al1", SearchResultType.Album, "Pavement", credit = "Someone Else"),
            row("ar1", SearchResultType.Artist, "Pavement"),
        )
        assertEquals(2, topResultIndex("pavement", rows), "an exact artist beats an exact album beats songs that carry the name")
    }

    @Test
    fun aRowNamedByTheQueryBeatsOneThatOnlyCarriesTheName() {
        val rows = listOf(
            row("t1", SearchResultType.Track, "Cut Your Hair", credit = "Pavement"),
            row("al1", SearchResultType.Album, "Pavement", credit = "Someone Else"),
        )
        assertEquals(1, topResultIndex("pavement", rows))
    }

    @Test
    fun equalSongsGoToTheOneThePersonFavouritedThenTheOneTheyPlayedMost() {
        val plain = row("t1", SearchResultType.Track, "Gold Soundz", credit = "Soccer Mommy")
        val played = row("t2", SearchResultType.Track, "Gold Soundz", credit = "Pavement", playCount = 7)
        val playedMore = row("t3", SearchResultType.Track, "Gold Soundz", credit = "Cover Band", playCount = 9)
        val starred = row("t4", SearchResultType.Track, "Gold Soundz", credit = "Liked One", favourite = true)
        assertEquals(1, topResultIndex("gold soundz", listOf(plain, played)))
        assertEquals(1, topResultIndex("gold soundz", listOf(played, playedMore)), "most played wins")
        assertEquals(2, topResultIndex("gold soundz", listOf(played, playedMore, starred)), "a favourite beats plays")
    }

    @Test
    fun aTieOnEverythingKeepsTheListsOrder() {
        val rows = listOf(
            row("t1", SearchResultType.Track, "Gold Soundz", credit = "A"),
            row("t2", SearchResultType.Track, "Gold Soundz", credit = "B"),
        )
        assertEquals(0, topResultIndex("gold soundz", rows))
    }

    @Test
    fun aMatchInsideAWordLeadsNothing() {
        val rows = listOf(row("t1", SearchResultType.Track, "Rainbow", credit = "X"))
        assertNull(topResultIndex("ainb", rows), "substring only")
        assertEquals(0, topResultIndex("rain", rows), "a prefix leads")
    }

    @Test
    fun noRowsOrAnEmptyQueryLeadNothing() {
        assertNull(topResultIndex("anything", emptyList()))
        assertNull(topResultIndex("  ", listOf(row("t1", SearchResultType.Track, "A"))))
    }
}
