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
        listens: Long? = null,
        album: String? = null,
    ) = LibrarySearchRow(
        item = SearchResultItem(
            id = ProviderItemId("provider-top-result-fixture", id),
            type = type,
            title = title,
            credits = listOfNotNull(credit?.let { Credit(CreditRole.Artist, it, null) }),
            albumTitle = album, year = null, duration = null, discNumber = null, trackNumber = null,
            sourceContainer = null, mediaSourceId = null, artworkKey = null,
        ),
        source = SearchResultSource.Server,
        favourite = favourite,
        rating = null,
        playCount = playCount,
        listens = listens,
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

    private fun LibrarySearchRow.inAlbum(title: String) = copy(item = item.copy(albumTitle = title))

    /** The shape the household search had: Soccer Mommy's session cover listed ahead of Pavement's original, same title. */
    private val cover = row("t-cover", SearchResultType.Track, "Gold Soundz", credit = "Soccer Mommy", album = "Gold Soundz (triple j Like a Version)")
    private val original = row("t-original", SearchResultType.Track, "Gold Soundz", credit = "Pavement", album = "Crooked Rain, Crooked Rain")

    @Test
    fun anOriginalThatMorePeoplePlayLeadsItsCoverWhateverTheListsOrder() {
        val coverFirst = listOf(cover.copy(listens = 3_000), original.copy(listens = 2_400_000))
        assertEquals(1, topResultIndex("gold soundz", coverFirst))
        assertEquals(0, topResultIndex("gold soundz", coverFirst.reversed()), "the same pick when the server lists them the other way round")
        // Control: with no figure for either, the pick is not the original by luck of order; the cover stays first here.
        val noFigures = listOf(cover.inAlbum("Singles"), original.inAlbum("Crooked Rain, Crooked Rain"))
        assertEquals(0, topResultIndex("gold soundz", noFigures))
    }

    @Test
    fun aFewStrayPlaysOfTheCoverDoNotOutvoteWhatEveryonePlaysButARealHabitDoes() {
        // The test account had played the cover twice and the original once: that is not a choice.
        val stray = listOf(cover.copy(playCount = 2, listens = 3_000), original.copy(playCount = 1, listens = 2_400_000))
        assertEquals(1, topResultIndex("gold soundz", stray))
        val habit = listOf(cover.copy(playCount = 9, listens = 3_000), original.copy(playCount = 1, listens = 2_400_000))
        assertEquals(0, topResultIndex("gold soundz", habit), "nine plays is the person's own pick")
        val starred = listOf(cover.copy(favourite = true), original.copy(listens = 2_400_000))
        assertEquals(0, topResultIndex("gold soundz", starred), "a favourite still beats what is popular")
    }

    @Test
    fun withoutAnyListenFigureTheSongNotMarkedAsAnotherVersionLeads() {
        // Plain OpenSubsonic: nothing global is known, but the cover's album names its session.
        assertEquals(1, topResultIndex("gold soundz", listOf(cover, original)))
        assertEquals(0, topResultIndex("gold soundz", listOf(original, cover)))
        val live = row("t-live", SearchResultType.Track, "Gold Soundz", credit = "Pavement", album = "Live at the Barrowlands")
        assertEquals(1, topResultIndex("gold soundz", listOf(live, original)))
        // Control: when neither is marked, the list's order stands.
        val other = row("t-other", SearchResultType.Track, "Gold Soundz", credit = "Cover Band", album = "Tributes")
        assertEquals(0, topResultIndex("gold soundz", listOf(other.inAlbum("Hits"), original)))
    }

    @Test
    fun theListenFigureComesOffTheSearchAnswerAndOnlyWhenTheServerStatesIt() {
        val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{"song":[
            {"id":"s1","title":"Gold Soundz","dulcetListens":2400000},
            {"id":"s2","title":"Gold Soundz","dulcetListens":0},
            {"id":"s3","title":"Gold Soundz","dulcetListens":"many"},
            {"id":"s4","title":"Gold Soundz"}]}}}"""
        assertEquals(mapOf("s1" to 2_400_000L), parseReaderSearch3Listens(body), "a missing, zero or malformed figure is absent, never a number")
        assertEquals(emptyMap(), parseReaderSearch3Listens("""{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{}}}"""))
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
