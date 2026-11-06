package com.legitimateapps.dulcet

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

/** A14: "13 albums" over albums, EPs and compilations said something the page below it did not. */
@RunWith(RobolectricTestRunner::class)
class ArtistReleaseCountTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test fun onlyAlbumsAreCountedAsAlbums() {
        assertEquals("1 album", resources.artistReleaseCount(listOf("albums")))
        assertEquals("2 albums", resources.artistReleaseCount(listOf("albums", "albums")))
    }

    @Test fun aMixedListIsCountedAsReleases() {
        assertEquals("3 releases", resources.artistReleaseCount(listOf("albums", "epsSingles", "compilations")))
        assertEquals("1 release", resources.artistReleaseCount(listOf("epsSingles")))
    }
}
