package com.legitimateapps.dulcet.emulator

/** Public synthetic metadata, shared by the phone/TV tours and tools/design-tour-server. */
object DesignTourFixtures {
    const val LONG_ALBUM = "An album with an intentionally long title — live recordings, alternate takes, and late-night favorites"
    const val LONG_TRACK = "Extremely long design tour song title — a live performance, an alternate take, and a night of music that keeps going"
    const val LONG_PLAYLIST = "A deliberately long playlist title — live recordings, alternate takes, and late-night favorites"

    fun seedLongPlaylist(probe: DisposableServerProbe) {
        val existing = probe.call("getPlaylists").getJSONObject("playlists").optJSONArray("playlist")
        if (existing != null && (0 until existing.length()).any { existing.getJSONObject(it).getString("name") == LONG_PLAYLIST }) return
        // The same 100 songs in the same order in every variant, so light, dark and large type photograph one list.
        val library = probe.call("search3", mapOf("query" to "", "songCount" to "500", "artistCount" to "0", "albumCount" to "0"))
            .getJSONObject("searchResult3").getJSONArray("song")
        val songs = (0 until library.length()).map(library::getJSONObject)
            .sortedWith(compareBy({ it.getString("title") }, { it.optString("album") }, { it.optInt("track") }, { it.getString("id") }))
            .take(100)
        check(songs.size == 100) { "Long-list capture needs 100 fixture songs" }
        val created = probe.callPairs("createPlaylist", listOf("name" to LONG_PLAYLIST) +
            songs.map { "songId" to it.getString("id") }).getJSONObject("playlist")
        check(probe.call("getPlaylist", mapOf("id" to created.getString("id"))).getJSONObject("playlist").getJSONArray("entry").length() == 100)
        println("DESIGN TOUR long playlist wire witness entries=100")
    }
}
