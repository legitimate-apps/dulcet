package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.library.artistAlbumCount
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ArtistReleaseCountTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test fun anAlbumsOnlyListUsesAlbumCountCopy() {
        assertEquals("1 album", resources.artistAlbumCount(listOf("albums")))
        assertEquals("2 albums", resources.artistAlbumCount(listOf("albums", "albums")))
    }

    @Test fun aMixedDiscographyCountsOnlyAlbums() {
        assertEquals("1 album", resources.artistAlbumCount(listOf("albums", "epsSingles", "compilations", "appearsOn")))
        assertNull(resources.artistAlbumCount(listOf("epsSingles", "compilations", "appearsOn")))
    }
}
