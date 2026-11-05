package com.legitimateapps.dulcet.emulator

/** Exact fixture metadata, independent of server-assigned IDs or the size of its catalog. */
data class FixtureAlbum(val id: String, val name: String)

/** A scan-complete server may still have more albums than one REST page. */
fun fixtureAlbumId(title: String, readPage: (offset: Int, size: Int) -> List<FixtureAlbum>): String {
    val size = 50
    var offset = 0
    while (true) {
        val page = readPage(offset, size)
        val matches = page.filter { it.name == title }
        check(matches.size <= 1) { "Multiple fixture albums titled $title at offset $offset" }
        matches.singleOrNull()?.let { return it.id }
        check(page.size == size) { "Fixture album $title absent after reading ${offset + page.size} albums" }
        offset += page.size
    }
}
