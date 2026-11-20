package com.legitimateapps.dulcet

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ArtistReleaseCountTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test fun anAlbumsOnlyListUsesAlbumCountCopy() {
        assertEquals("1 album", resources.artistReleaseCount(listOf("albums")))
        assertEquals("2 albums", resources.artistReleaseCount(listOf("albums", "albums")))
    }

    @Test fun aMixedDiscographyAlsoUsesAlbumCountCopy() {
        assertEquals("3 albums", resources.artistReleaseCount(listOf("albums", "epsSingles", "compilations")))
        assertEquals("1 album", resources.artistReleaseCount(listOf("epsSingles")))
    }
}
