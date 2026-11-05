package com.legitimateapps.dulcet.emulator

/** Exact fixture metadata, independent of server-assigned IDs or the size of its catalog. */
data class FixtureAlbum(val id: String, val name: String)

/**
 * Every album with this exact title, in server order: a server may split one title into several albums,
 * and a scan-complete server may still have more albums than one REST page.
 */
fun fixtureAlbumIds(title: String, readPage: (offset: Int, size: Int) -> List<FixtureAlbum>): List<String> {
    val size = 50
    var offset = 0
    val found = mutableListOf<String>()
    while (true) {
        val page = readPage(offset, size)
        found += page.filter { it.name == title }.map { it.id }
        if (page.size < size) break
        offset += page.size
    }
    check(found.isNotEmpty()) { "Fixture album $title absent after reading ${offset + size} albums at most" }
    return found
}
