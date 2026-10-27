package com.legitimateapps.dulcet.search.conformance

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small OpenSubsonic server on this machine's loopback for host tests of the library screens over
 * the PRODUCTION session and reader: only the server is a fixture. It answers the reader's epoch reads
 * with one unchanging scan stamp, an album list per `getAlbumList2` type (each type its own albums, so
 * a screen shows which order it asked for), each album with one song from `getAlbum`, [genres] from
 * `getGenres`, and [songsByGenre] from `getSongsByGenre`. Every request is recorded, credentials and all, and never leaves the test.
 * Given [playlists], it also keeps playlists owned by the signed-in user `u`: listed, read, created
 * and appended to; [loseCreateAnswers] makes a create land without its songs and lose its answer.
 */
class LoopbackLibraryServer : AutoCloseable {
    private val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"

    /** Optional envelope implementation identity for saved-account reconnect proofs. */
    @Volatile var serverType: String? = null

    /** The genres `getGenres` lists, in the server's order. */
    @Volatile var genres: List<String> = listOf("Jazz", "Ambient")

    /** Each genre's songs: id to title. */
    @Volatile var songsByGenre: Map<String, List<Pair<String, String>>> = mapOf(
        "Jazz" to listOf("jazz-song-1" to "Blue Hour", "jazz-song-2" to "Late Set"),
        "Ambient" to listOf("ambient-song-1" to "Drift"),
    )

    /** Titles returned by getAlbum, including absent and blank metadata. */
    @Volatile var albumSongTitles: List<String?> = listOf("Opening")

    @Volatile var albumSongDurationSeconds: Int? = 120
    @Volatile var albumSongDurationsSeconds: List<Int?>? = null
    @Volatile var albumPlaybackAudio: ByteArray? = null

    /** Optional complete song catalog for library-depth UI proofs. */
    @Volatile var librarySongs: List<Pair<String, String>>? = null

    /** An epoch-read refusal for saved-launch connection error presentation proofs. */
    @Volatile var scanFailureCode: Int? = null

    /** When set, `getGenres` answers the server's own failure. */
    @Volatile var failGenres = false

    /** When set, `getGenres` is not answered until [releaseGenres]. */
    @Volatile var holdGenres = false
    private val genresReleased = CountDownLatch(1)

    fun releaseGenres() = genresReleased.countDown()

    /** When set, `getSong` is not answered until [releaseSongs]. */
    @Volatile var holdSongs = false
    private val songsReleased = CountDownLatch(1)

    fun releaseSongs() = songsReleased.countDown()

    /** Holds album continuation reads so remote header proofs can keep Open coverage visible. */
    @Volatile var holdAlbumPages = false
    private val albumPagesReleased = CountDownLatch(1)

    fun releaseAlbumPages() = albumPagesReleased.countDown()

    /**
     * When set, the server advertises `dulcetLibrary` v1 and answers its endpoints: the membership
     * below, `addToDulcetLibrary`/`removeFromDulcetLibrary` (an album's remove takes its member
     * songs, as the contract says), and `dulcetInLibrary` on every ordinary element it returns.
     */
    @Volatile var dulcetLibrary = false
    /** Optional Home feed, with mutable recent playlists for offscreen refresh proofs. */
    @Volatile var dulcetHome = false
    /** Advertise phone extensions to detect accidental eager feature composition on TV. */
    @Volatile var dulcetDiscoverWishlist = false
    @Volatile var homePlaylists: List<Pair<String, String>> = emptyList()

    private val libraryAlbumIds = LinkedHashMap<String, Long>()
    private val libraryArtistIds = LinkedHashMap<String, Long>()
    private val librarySongIds = LinkedHashMap<String, Long>()
    private var libraryClock = 0L

    /** When set, `stream` is not answered until the server closes: a play stays on the song it started. */
    @Volatile var holdStreams = false
    private val streamsReleased = CountDownLatch(1)

    /**
     * Playlists by id: name and song ids, in order. Null, the default, leaves every playlist endpoint
     * answering the empty envelope it always did.
     */
    @Volatile var playlists: MutableMap<String, Pair<String, List<String>>>? = null
    private var creates = 0

    /**
     * When set, a `createPlaylist` makes its playlist WITHOUT the songs it sent and is answered 502,
     * as a proxy answers when the server behind it committed and then failed: a 5xx does not prove a
     * write unapplied (spec §18.6), so the client cannot know it landed, and what landed is not what
     * it sent.
     */
    @Volatile var loseCreateAnswers = false

    /** The playlists named [name], by id. */
    @Synchronized fun playlistsNamed(name: String): Map<String, List<String>> =
        playlists.orEmpty().filterValues { it.first == name }.mapValues { it.value.second }

    private val log = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val connections = Executors.newCachedThreadPool()

    init {
        connections.submit {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                connections.submit { answer(client) }
            }
        }
    }

    fun requests(endpoint: String): List<Map<String, String>> = log.filter { it.first == endpoint }.map { it.second }

    fun endpoints(): List<String> = log.map { it.first }

    /** The album ids each type lists: `<type>-1` and `<type>-2`, titled after the type. */
    fun albumTitle(type: String, index: Int) = "Album $index by $type"

    private fun answer(client: Socket) = client.use {
        client.soTimeout = 10_000
        val input = client.getInputStream().bufferedReader()
        val line = input.readLine()?.split(' ') ?: return@use
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
        val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
        val uri = URI(line[1])
        val endpoint = uri.path.substringAfterLast('/').removeSuffix(".view")
        val pairs = listOfNotNull(uri.rawQuery, String(body).takeIf { it.isNotEmpty() })
            .flatMap { it.split('&') }.filter { it.isNotEmpty() }.map {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
        val parameters = pairs.toMap()
        log += endpoint to parameters
        if (endpoint == "createPlaylist" && loseCreateAnswers && playlists != null) {
            synchronized(this) { playlists!!["created-${++creates}"] = parameters["name"].orEmpty() to emptyList() }
            respond(client, 502, "<html>Bad Gateway</html>")
            return@use
        }
        playlistWrite(endpoint, pairs)?.let { respond(client, 200, it); return@use }
        if (endpoint == "getCoverArt") {
            respond(client, 404, "")
            return@use
        }
        if (endpoint == "addToDulcetLibrary" || endpoint == "removeFromDulcetLibrary") {
            libraryWrite(endpoint, parameters)
            respond(client, 200, envelope(endpoint, parameters))
            return@use
        }
        if (endpoint == "getAlbumList2" && (parameters["offset"]?.toIntOrNull() ?: 0) > 0 && holdAlbumPages) {
            albumPagesReleased.await(30, TimeUnit.SECONDS)
        }
        if (endpoint == "getGenres" && holdGenres) genresReleased.await(30, TimeUnit.SECONDS)
        if (endpoint == "getSong" && holdSongs) songsReleased.await(30, TimeUnit.SECONDS)
        if (endpoint == "stream" && holdStreams) streamsReleased.await(30, TimeUnit.SECONDS)
        if (endpoint == "stream" && albumPlaybackAudio != null) {
            val audio = checkNotNull(albumPlaybackAudio)
            client.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${audio.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(audio)
                flush()
            }
            return@use
        }
        respond(client, 200, envelope(endpoint, parameters))
    }

    private fun envelope(endpoint: String, parameters: Map<String, String>): String {
        if (endpoint == "getScanStatus") scanFailureCode?.let { code ->
            return """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"Refused"}}}"""
        }
        val payload = when (endpoint) {
            "getScanStatus" -> """"scanStatus":{"scanning":false,"count":6,"lastScan":"2026-09-01T00:00:00Z"}"""
            "getMusicFolders" -> """"musicFolders":{"musicFolder":[{"id":1,"name":"Music"}]}"""
            "getStarred2" -> """"starred2":{}"""
            "getOpenSubsonicExtensions" -> listOfNotNull(
                "dulcetLibrary".takeIf { dulcetLibrary }, "dulcetHome".takeIf { dulcetHome },
                "dulcetDiscover".takeIf { dulcetDiscoverWishlist }, "dulcetWishlist".takeIf { dulcetDiscoverWishlist },
            ).takeIf { it.isNotEmpty() }?.joinToString(",", "\"openSubsonicExtensions\":[", "]") {
                """{"name":${quote(it)},"versions":[1]}"""
            }
            "getDulcetHome" -> if (!dulcetHome) null else {
                val shelf = parameters["shelf"].orEmpty()
                val content = if (shelf == "recentPlaylists") homePlaylists.joinToString(",", "\"playlist\":[", "]") { (id, name) ->
                    """{"id":${quote(id)},"name":${quote(name)},"owner":"u","songCount":1}"""
                } else (1..2).joinToString(",", "\"album\":[", "]") { index ->
                    """{"id":"$shelf-$index","name":${quote(albumTitle(shelf, index))},"artist":"Fixture Artist","songCount":1}"""
                }
                "\"dulcetHome\":{$content}"
            }
            "getDulcetLibrary" -> dulcetLibraryRead()
            "getAlbumList2" -> {
                val type = parameters["type"].orEmpty()
                val offset = parameters["offset"]?.toIntOrNull() ?: 0
                val albums = if (offset > 0) "" else (1..2).joinToString(",") { index ->
                    """{"id":"$type-$index","name":${quote(albumTitle(type, index))},"artist":"Fixture Artist","artistId":"artist-1","songCount":1${flagAlbum("$type-$index")}}"""
                }
                """"albumList2":{"album":[$albums]}"""
            }
            "getGenres" -> {
                if (failGenres) return """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"Genres failed"}}}"""
                val list = genres.joinToString(",") { name ->
                    """{"value":${quote(name)},"songCount":${songsByGenre[name].orEmpty().size},"albumCount":1}"""
                }
                """"genres":{"genre":[$list]}"""
            }
            "search3" -> librarySongs?.let { all ->
                val offset = parameters["songOffset"]?.toIntOrNull() ?: 0
                val count = parameters["songCount"]?.toIntOrNull() ?: 100
                "\"searchResult3\":{\"song\":[" + all.drop(offset).take(count).joinToString(",") { (id, title) -> genreSong(id, title) } + "]}"
            }
            "getRandomSongs" -> librarySongs?.let { all ->
                "\"randomSongs\":{\"song\":[" + all.reversed().take(500).joinToString(",") { (id, title) -> genreSong(id, title) } + "]}"
            }
            "getTopSongs" -> librarySongs?.let { all ->
                "\"topSongs\":{\"song\":[" + all.take(5).joinToString(",") { (id, title) -> genreSong(id, title) } + "]}"
            }
            "getArtists" -> """"artists":{"index":[{"name":"F","artist":[{"id":"artist-1","name":"Fixture Artist","albumCount":2${flagArtist("artist-1")}}]}]}"""
            "getArtist" -> """"artist":{"id":"artist-1","name":"Fixture Artist","albumCount":2${flagArtist("artist-1")},""" +
                """"album":[{"id":"artistAlbum-1","name":"Artist Album One","artist":"Fixture Artist","artistId":"artist-1","songCount":1${flagAlbum("artistAlbum-1")}}]}"""
            "getAlbum" -> {
                val id = parameters["id"].orEmpty()
                val title = quote(albumTitle(id.substringBeforeLast('-'), id.substringAfterLast('-').toIntOrNull() ?: 1))
                val songs = albumSongTitles.mapIndexed { index, songTitle ->
                    val songId = "$id-song-${index + 1}"
                    val titleField = songTitle?.let { "\"title\":${quote(it)}," }.orEmpty()
                    val durationField = (if (albumSongDurationsSeconds != null) albumSongDurationsSeconds?.getOrNull(index) else albumSongDurationSeconds)?.let { "\"duration\":$it," }.orEmpty()
                    """{"id":${quote(songId)},${titleField}"album":$title,"albumId":${quote(id)},""" +
                        """"artist":"Fixture Artist","track":${index + 1},${durationField}"suffix":"mp3","contentType":"audio/mpeg"${flagSong(songId)}}"""
                }
                """"album":{"id":${quote(id)},"name":$title,"artist":"Fixture Artist","artistId":"artist-1","songCount":${songs.size}${flagAlbum(id)},"song":[${songs.joinToString(",")}] }"""
            }
            "getSongsByGenre" -> {
                val offset = parameters["offset"]?.toIntOrNull() ?: 0
                val songs = if (offset > 0) "" else songsByGenre[parameters["genre"]].orEmpty().joinToString(",") { (id, title) -> genreSong(id, title) }
                """"songsByGenre":{"song":[$songs]}"""
            }
            // A genre's songs, as a play re-reads each before queueing it; any other id stays the empty
            // envelope it always was.
            "getSong" -> if (albumPlaybackAudio != null && parameters["id"].orEmpty().contains("-song-")) {
                val id = parameters["id"].orEmpty()
                val index = id.substringAfterLast("-song-").toInt() - 1
                val titleField = albumSongTitles[index]?.let { "\"title\":${quote(it)}," }.orEmpty()
                val durationField = (if (albumSongDurationsSeconds != null) albumSongDurationsSeconds?.getOrNull(index) else albumSongDurationSeconds)?.let { "\"duration\":$it," }.orEmpty()
                """"song":{"id":${quote(id)},${titleField}${durationField}"suffix":"wav","contentType":"audio/wav"}"""
            } else songsByGenre.values.flatten().firstOrNull { it.first == parameters["id"] }
                ?.let { (id, title) -> """"song":${genreSong(id, title)}""" }
            else -> null
        }
        return """{"subsonic-response":{"status":"ok","version":"1.16.1"${serverType?.let { ",\"type\":${quote(it)}" }.orEmpty()}${payload?.let { ",$it" }.orEmpty()}}}"""
    }

    /** A playlist read or write, answered from [playlists]; null for any other endpoint, or without them. */
    @Synchronized
    private fun playlistWrite(endpoint: String, pairs: List<Pair<String, String>>): String? {
        val lists = playlists ?: return null
        fun one(name: String) = pairs.firstOrNull { it.first == name }?.second
        fun all(name: String) = pairs.filter { it.first == name }.map { it.second }
        fun playlist(id: String): String {
            val (name, songs) = lists.getValue(id)
            return """{"id":${quote(id)},"name":${quote(name)},"owner":"u","public":false,"readonly":false,""" +
                """"songCount":${songs.size},"duration":${songs.size * 120},"entry":[${songs.joinToString(",") { genreSong(it, it) }}]}"""
        }
        fun ok(payload: String?) = """{"subsonic-response":{"status":"ok","version":"1.16.1"${payload?.let { ",$it" }.orEmpty()}}}"""
        return when (endpoint) {
            "getPlaylists" -> ok(""""playlists":{"playlist":[${lists.keys.joinToString(",", transform = ::playlist)}]}""")
            "getPlaylist" -> one("id")?.takeIf { it in lists }?.let { ok(""""playlist":${playlist(it)}""") }
                ?: """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Playlist not found"}}}"""
            "createPlaylist" -> {
                val id = "created-${++creates}"
                lists[id] = one("name").orEmpty() to all("songId")
                ok(""""playlist":${playlist(id)}""")
            }
            "updatePlaylist" -> {
                val id = one("playlistId").orEmpty()
                val (name, songs) = lists[id] ?: return ok(null)
                lists[id] = (one("name") ?: name) to songs + all("songIdToAdd")
                ok(null)
            }
            else -> null
        }
    }

    private fun genreSong(id: String, title: String) =
        """{"id":"$id","title":${quote(title)},"album":"Genre Album","albumId":"genre-album","artist":"Fixture Artist","duration":120,"suffix":"mp3","contentType":"audio/mpeg"${flagSong(id)}}"""

    // ---- dulcetLibrary (the extension this fixture optionally advertises) ----------------------------------

    private fun flagAlbum(id: String): String = if (dulcetLibrary && synchronized(this) { libraryAlbumIds.containsKey(id) }) """,""dulcetInLibrary":true""" else ""
    private fun flagArtist(id: String): String = if (dulcetLibrary && synchronized(this) { libraryArtistIds.containsKey(id) }) """,""dulcetInLibrary":true""" else ""
    private fun flagSong(id: String): String = if (dulcetLibrary && synchronized(this) { librarySongIds.containsKey(id) }) """,""dulcetInLibrary":true""" else ""

    @Synchronized
    private fun libraryWrite(endpoint: String, parameters: Map<String, String>) {
        if (!dulcetLibrary) return
        val add = endpoint == "addToDulcetLibrary"
        val ids = listOfNotNull(parameters["id"], parameters["albumId"], parameters["artistId"])
        for (id in ids) {
            // The server works out which kind an id is; this fixture knows its own ids.
            val set = when {
                parameters["artistId"] != null -> libraryArtistIds
                parameters["albumId"] != null -> libraryAlbumIds
                id == "artist-1" -> libraryArtistIds
                id.contains("-song-") || songsByGenre.values.flatten().any { it.first == id } -> librarySongIds
                else -> libraryAlbumIds
            }
            if (add) {
                if (!set.containsKey(id)) set[id] = ++libraryClock
            } else if (set === libraryAlbumIds) {
                // Removing an album takes its member songs with it (the extension's semantics).
                libraryAlbumIds.remove(id)
                librarySongIds.entries.removeIf { it.key.startsWith("$id-song-") }
            } else {
                set.remove(id)
            }
            libraryClock += 1
        }
    }

    /** `getDulcetLibrary`: every member, newest addition first, with whole-library totals and the clock. */
    @Synchronized
    private fun dulcetLibraryRead(): String? {
        if (!dulcetLibrary) return null
        val albums = libraryAlbumIds.entries.sortedByDescending { it.value }.joinToString(",") { (id) ->
            """{"id":${quote(id)},"name":${quote(albumTitle(id.substringBeforeLast('-'), id.substringAfterLast('-').toIntOrNull() ?: 1))},""" +
                """"artist":"Fixture Artist","artistId":"artist-1","songCount":1,"dulcetInLibrary":true}"""
        }
        val artists = libraryArtistIds.entries.sortedByDescending { it.value }.joinToString(",") { (id) ->
            """{"id":${quote(id)},"name":"Fixture Artist","dulcetInLibrary":true}"""
        }
        val songs = librarySongIds.entries.sortedByDescending { it.value }.joinToString(",") { (id) ->
            val albumId = id.substringBefore("-song-")
            """{"id":${quote(id)},"title":"Opening","albumId":${quote(albumId)},"album":${quote(albumTitle(albumId.substringBeforeLast('-'), 1))},""" +
                """"artist":"Fixture Artist","duration":120,"suffix":"mp3","contentType":"audio/mpeg","dulcetInLibrary":true}"""
        }
        val clock = if (libraryClock == 0L) "" else """"changed":"2026-10-12T00:00:00.${(libraryClock % 1000).toString().padStart(3, '0')}Z","""
        return """"dulcetLibrary":{$clock"artistCount":${libraryArtistIds.size},"albumCount":${libraryAlbumIds.size},""" +
            """"songCount":${librarySongIds.size},"artist":[$artists],"album":[$albums],"song":[$songs]}"""
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray()
        client.getOutputStream().apply {
            write(("HTTP/1.1 $status ${when (status) { 200 -> "OK"; 502 -> "Bad Gateway"; else -> "Not Found" }}\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes)
            flush()
        }
    }

    override fun close() {
        releaseAlbumPages()
        genresReleased.countDown()
        streamsReleased.countDown()
        socket.close()
        connections.shutdownNow()
    }
}
