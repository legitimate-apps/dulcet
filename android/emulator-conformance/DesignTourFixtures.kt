package com.legitimateapps.dulcet.emulator

/** Public synthetic metadata, shared by the phone/TV tours and tools/design-tour-server. */
object DesignTourFixtures {
    const val LONG_ALBUM = "An album with an intentionally long title — live recordings, alternate takes, and late-night favorites"
    const val LONG_TRACK = "Extremely long design tour song title — a live performance, an alternate take, and a night of music that keeps going"
    const val LONG_PLAYLIST = "A deliberately long playlist title — live recordings, alternate takes, and late-night favorites"
    /** Short enough for the collapsed bar to hold its dash, and its hero's ordinary wrap would strand the last word at large type. */
    const val DASH_WRAP_PLAYLIST = "Quiet hours — live takes"
    /** The corpus's non-Latin title, first in view, then rows whose names do not change between runs. */
    const val PINNED_QUEUE_UNICODE_TITLE = "Étude 東京"
    val PINNED_QUEUE_TITLES = listOf(PINNED_QUEUE_UNICODE_TITLE) + (1..11).map { "Paging Track %03d".format(it) }

    /**
     * Holds the tour until the album shows what the populated frames show, so a frame's download count follows the fixture
     * and not how long the section ran before its server went away.
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

    /**
     * [awaitSavedAlbumDownloads] for a section with nothing to start them: a person's own download of the album's first tracks.
     * The TV's saved section alone never had the count its frames showed in a full run, which it inherited from earlier sections.
     */
    fun downloadSavedAlbumTracks(context: android.content.Context, probe: DisposableServerProbe, album: String, count: Int = 2) {
        val controller = checkNotNull(com.legitimateapps.dulcet.downloads.AndroidDownloads.controller(context)) { "No download controller for the saved account" }
        val songs = probe.call("getAlbum", mapOf("id" to probe.albumId(album))).getJSONObject("album").getJSONArray("song")
        val items = (0 until minOf(count, songs.length())).map(songs::getJSONObject).map { song ->
            val container = when (song.getString("suffix").lowercase()) {
                "flac" -> com.legitimateapps.dulcet.core.AudioContainer.Flac
                "mp3" -> com.legitimateapps.dulcet.core.AudioContainer.Mp3
                "wav" -> com.legitimateapps.dulcet.core.AudioContainer.Wav
                "ogg", "opus" -> com.legitimateapps.dulcet.core.AudioContainer.Ogg
                "m4a", "mp4" -> com.legitimateapps.dulcet.core.AudioContainer.Mp4
                else -> error("The fixture song's format is not one this tour downloads: ${song.getString("suffix")}")
            }
            com.legitimateapps.dulcet.core.AndroidDownloadItem(song.getString("id"), container, song.optLong("duration") * 1000)
        }
        kotlinx.coroutines.runBlocking { controller.download(items) }
        awaitSavedAlbumDownloads(context, probe, album, count)
    }

    /** One scrobble at a stated time, so a section's Recently Played order is the one it says. */
    fun scrobbleAlbumFirstTrack(probe: DisposableServerProbe, album: String, wallClockMillis: Long) {
        val songs = probe.call("getAlbum", mapOf("id" to probe.albumId(album))).getJSONObject("album").getJSONArray("song")
        probe.call("scrobble", mapOf("id" to songs.getJSONObject(0).getString("id"), "submission" to "true", "time" to wallClockMillis.toString()))
    }

    /**
     * A person's own additions to the playing queue. They stay ahead of the Autoplay section, whose rows come from the
     * server's random songs and differ every run, so what a frame shows of the queue is the same each time.
     */
    fun pinQueue(context: android.content.Context, probe: DisposableServerProbe, titles: List<String>) {
        val provider = checkNotNull(com.legitimateapps.dulcet.AndroidAccountCredentialStore(context).load()) { "No saved account to queue for" }.id
        val tracks = titles.map { title ->
            val song = probe.call("getSong", mapOf("id" to probe.songId(title))).getJSONObject("song")
            com.legitimateapps.dulcet.core.AndroidTrack(
                providerInstanceId = provider,
                rawId = song.getString("id"),
                title = song.getString("title"),
                artist = song.optString("artist").ifEmpty { null },
                album = song.optString("album").ifEmpty { null },
                durationMilliseconds = song.optLong("duration") * 1000,
                artworkKey = song.optString("coverArt").ifEmpty { null },
                albumRawId = song.optString("albumId").ifEmpty { null },
            )
        }
        // Not close(): that stops playback and removes the account's data, and the tour goes on.
        val observer = PlaybackObserver(context, removeAccountDataOnClose = false)
        observer.bind()
        try {
            observer.onController { controller ->
                check(controller.addToQueue(tracks, com.legitimateapps.dulcet.core.AndroidQueueInsertion.AddToQueue,
                    com.legitimateapps.dulcet.core.AndroidQueueSource.Library, "Design tour")) { "The queue refused the tour's additions" }
            }
        } finally {
            observer.unbind()
        }
    }

    /** Twelve songs: enough to scroll this playlist's hero away at large type, and no list frame's fixture changes for it. */
    fun seedDashWrapPlaylist(probe: DisposableServerProbe): String {
        val existing = probe.call("getPlaylists").getJSONObject("playlists").optJSONArray("playlist")
        (0 until (existing?.length() ?: 0)).map { existing!!.getJSONObject(it) }.firstOrNull { it.getString("name") == DASH_WRAP_PLAYLIST }
            ?.let { return it.getString("id") }
        val library = probe.call("search3", mapOf("query" to "", "songCount" to "100", "artistCount" to "0", "albumCount" to "0"))
            .getJSONObject("searchResult3").getJSONArray("song")
        val songs = (0 until library.length()).map(library::getJSONObject).sortedBy { it.getString("title") }.take(12)
        check(songs.size == 12) { "The dash-wrap playlist needs 12 fixture songs" }
        return probe.callPairs("createPlaylist", listOf("name" to DASH_WRAP_PLAYLIST) + songs.map { "songId" to it.getString("id") })
            .getJSONObject("playlist").getString("id")
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
