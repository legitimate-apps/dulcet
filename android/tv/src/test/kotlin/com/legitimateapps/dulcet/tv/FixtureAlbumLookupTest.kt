package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.emulator.FixtureAlbum
import com.legitimateapps.dulcet.emulator.fixtureAlbumId
import org.junit.Test
import kotlin.test.*

class FixtureAlbumLookupTest {
    @Test fun readsThePageContainingTheAlbumInsteadOfRepeatingTheFirstPage() {
        val albums = List(63) { FixtureAlbum("id:$it", if (it == 61) "Dulcet Conformance" else "Album $it") }
        val reads = mutableListOf<Pair<Int, Int>>()
        val found = fixtureAlbumId("Dulcet Conformance") { offset, size ->
            reads += offset to size
            albums.drop(offset).take(size)
        }
        assertEquals("id:61", found)
        assertEquals(listOf(0 to 50, 50 to 50), reads)
    }
    @Test fun anAbsentFixtureNamesTheMissingAlbumAndStopsAtTheEnd() {
        val reads = mutableListOf<Int>()
        val failure = assertFailsWith<IllegalStateException> {
            fixtureAlbumId("Dulcet Conformance") { offset, size ->
                reads += offset
                if (offset == 0) List(size) { FixtureAlbum("id:$it", "Album $it") } else emptyList()
            }
        }
        assertEquals(listOf(0, 50), reads)
        assertTrue(failure.message.orEmpty().contains("Dulcet Conformance"), failure.message)
    }
}
