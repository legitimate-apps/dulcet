package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.emulator.FixtureAlbum
import com.legitimateapps.dulcet.emulator.fixtureAlbumIds
import org.junit.Test
import kotlin.test.*

class FixtureAlbumLookupTest {
    @Test fun readsThePageContainingTheAlbumInsteadOfRepeatingTheFirstPage() {
        val albums = List(63) { FixtureAlbum("id:$it", if (it == 61) "Dulcet Conformance" else "Album $it") }
        val reads = mutableListOf<Pair<Int, Int>>()
        val found = fixtureAlbumIds("Dulcet Conformance") { offset, size ->
            reads += offset to size
            albums.drop(offset).take(size)
        }
        assertEquals(listOf("id:61"), found)
        assertEquals(listOf(0 to 50, 50 to 50), reads)
    }
    @Test fun aTitleSplitIntoSeveralAlbumsReturnsEveryOne() {
        val albums = listOf(FixtureAlbum("a", "Dulcet Conformance"), FixtureAlbum("b", "Other"), FixtureAlbum("c", "Dulcet Conformance"))
        assertEquals(listOf("a", "c"), fixtureAlbumIds("Dulcet Conformance") { offset, _ -> albums.drop(offset) })
    }
    @Test fun anAbsentFixtureNamesTheMissingAlbumAndStopsAtTheEnd() {
        val reads = mutableListOf<Int>()
        val failure = assertFailsWith<IllegalStateException> {
            fixtureAlbumIds("Dulcet Conformance") { offset, size ->
                reads += offset
                if (offset == 0) List(size) { FixtureAlbum("id:$it", "Album $it") } else emptyList()
            }
        }
        assertEquals(listOf(0, 50), reads)
        assertTrue(failure.message.orEmpty().contains("Dulcet Conformance"), failure.message)
    }
}
