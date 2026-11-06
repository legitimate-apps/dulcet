package com.legitimateapps.dulcet.emulator

/** Public synthetic metadata, shared by the phone/TV tours and tools/design-tour-server. */
object DesignTourFixtures {
    const val LONG_ALBUM = "An album with an intentionally long title — live recordings, alternate takes, and late-night favorites"
    const val LONG_TRACK = "Extremely long design tour song title — a live performance, an alternate take, and a night of music that keeps going"
    const val LONG_PLAYLIST = "A deliberately long playlist title — live recordings, alternate takes, and late-night favorites"

    /**
     * Holds the tour until the album shows what the populated frames show, so a frame's download count follows the fixture
     * and not how long the section ran before its server went away. Starring [album] is what downloads it.
     */
    fun awaitSavedAlbumDownloads(context: android.content.Context, probe: DisposableServerProbe, album: String, count: Int = 2) {
        val controller = checkNotNull(com.legitimateapps.dulcet.downloads.AndroidDownloads.controller(context)) { "No download controller for the saved account" }
        val songs = probe.call("getAlbum", mapOf("id" to probe.albumId(album))).getJSONObject("album").getJSONArray("song")
        val ids = (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }.toSet()
        val deadline = System.currentTimeMillis() + 180_000
        fun held() = controller.downloadedRawIds().count { it in ids }
        while (held() < count && System.currentTimeMillis() < deadline) Thread.sleep(250)
        check(held() >= count) { "The tour's album holds ${held()} of its ${ids.size} tracks, not the $count its frames show" }
    }

    /** One scrobble at a stated time, so a section's Recently Played order is the one it says. */
    fun scrobbleAlbumFirstTrack(probe: DisposableServerProbe, album: String, wallClockMillis: Long) {
        val songs = probe.call("getAlbum", mapOf("id" to probe.albumId(album))).getJSONObject("album").getJSONArray("song")
        probe.call("scrobble", mapOf("id" to songs.getJSONObject(0).getString("id"), "submission" to "true", "time" to wallClockMillis.toString()))
    }

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
