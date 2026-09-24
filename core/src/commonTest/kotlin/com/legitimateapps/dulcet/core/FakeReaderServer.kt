package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * An in-process OpenSubsonic server for driving the production [LibraryReader]: albums in
 * alphabetical order, per-album songs, a scan stamp, music folders, artists, playlists and stars.
 *
 * Every request is recorded in [log] in the order it was ISSUED, so tests assert request counts and
 * order — never wall time. [beforeRespond] lets a test mutate the library between a request's
 * arrival and its response (a scan racing a page read); [holdMatching] parks responses so a test can
 * observe what is in flight.
 */
internal class FakeReaderServer(albumCount: Int = 250, tracksPerAlbum: Int = 2) : LibraryEndpointTransport {
    data class Request(val endpoint: String, val parameters: Map<String, String>) {
        override fun toString(): String =
            endpoint + parameters.filterKeys { it in setOf("offset", "id", "type") }.entries.sortedBy { it.key }.joinToString("") { "[${it.key}=${it.value}]" }
    }

    data class Album(val id: String, val name: String, val songs: MutableList<String>, var starred: Boolean = false)

    val albums: MutableList<Album> = MutableList(albumCount) { index ->
        val id = "album-${index.toString().padStart(4, '0')}"
        Album(id, "Album ${index.toString().padStart(4, '0')}", MutableList(tracksPerAlbum) { "$id-track-$it" })
    }
    var lastScan: String? = "2026-09-22T10:00:00.123+02:00"
    var scanning = false
    var folders = listOf("folder-1")
    var sendTotalCount = true
    var artists = listOf("artist-1" to "Artist One", "artist-2" to "Artist Two")
    var playlists = listOf("playlist-1" to listOf("album-0000-track-0", "album-0000-track-0"))

    /** How `getAlbum` answers for an album whose files were removed (spec §16.11's table). */
    var purgeMissingAlways = true
    val removedAlbums = mutableSetOf<String>()

    val log = mutableListOf<Request>()
    val events: MutableList<String> get() = eventLog
    private val eventLog = mutableListOf<String>()

    /** Called after a request is recorded and before it is answered. */
    var beforeRespond: (Request) -> Unit = {}

    /** Endpoints (or `endpoint[type=x]`) that answer with a failure envelope. */
    val failing = mutableSetOf<String>()

    private val held = mutableListOf<Pair<Request, CompletableDeferred<Unit>>>()
    var holdMatching: ((Request) -> Boolean)? = null
    var inFlight = 0
        private set
    var maxInFlight = 0
        private set
    var cancelled = 0
        private set

    fun count(endpoint: String): Int = log.count { it.endpoint == endpoint }

    fun release(count: Int = Int.MAX_VALUE) {
        repeat(minOf(count, held.size)) { held.removeAt(0).second.complete(Unit) }
    }

    val heldCount: Int get() = held.size

    fun markEvent(event: String) {
        eventLog += event
    }

    override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
        val request = Request(endpoint, parameters)
        log += request
        eventLog += request.toString()
        inFlight += 1
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            if (holdMatching?.invoke(request) == true) {
                val gate = CompletableDeferred<Unit>()
                held += request to gate
                gate.await()
            }
            beforeRespond(request)
            return respond(request)
        } catch (failure: CancellationException) {
            cancelled += 1
            throw failure
        } finally {
            inFlight -= 1
        }
    }

    private fun respond(request: Request): LibraryEndpointResponse {
        val p = request.parameters
        val typeKey = "${request.endpoint}[type=${p["type"]}]"
        if (request.endpoint in failing || typeKey in failing) return envelope(failure(0, "busy"))
        return when (request.endpoint) {
            "getScanStatus" -> envelope(
                """"scanStatus":{"scanning":$scanning,"count":${albums.size}${lastScan?.let { ",\"lastScan\":\"$it\"" } ?: ""}}""",
            )
            "getMusicFolders" -> envelope(
                """"musicFolders":{"musicFolder":[${folders.joinToString(",") { """{"id":"$it","name":"$it"}""" }}]}""",
            )
            "getAlbumList2" -> albumList(p)
            "getAlbum" -> album(p.getValue("id"))
            "getArtists" -> envelope(
                """"artists":{"index":[{"name":"A","artist":[${artists.joinToString(",") { """{"id":"${it.first}","name":"${it.second}","albumCount":1}""" }}]}]}""",
            )
            "getPlaylists" -> envelope(
                """"playlists":{"playlist":[${playlists.joinToString(",") { """{"id":"${it.first}","name":"${it.first}","songCount":${it.second.size}}""" }}]}""",
            )
            "getPlaylist" -> {
                val playlist = playlists.firstOrNull { it.first == p["id"] } ?: return envelope(failure(70, "not found"))
                envelope(""""playlist":{"id":"${playlist.first}","name":"${playlist.first}","entry":[${playlist.second.joinToString(",") { song(it) }}]}""")
            }
            "getStarred2" -> envelope(
                """"starred2":{"album":[${albums.filter { it.starred }.joinToString(",") { albumJson(it) }}]}""",
            )
            else -> error("unexpected endpoint ${request.endpoint}")
        }
    }

    private fun albumList(p: Map<String, String>): LibraryEndpointResponse {
        val type = p.getValue("type")
        val listed = when (type) {
            "alphabeticalByName", "newest" -> albums
            "recent", "frequent", "random", "highest" -> albums.asReversed()
            "starred" -> albums.filter { it.starred }
            else -> albums
        }
        val size = p.getValue("size").toInt()
        val offset = p["offset"]?.toInt() ?: 0
        val page = listed.drop(offset).take(size)
        return envelope(
            """"albumList2":{"album":[${page.joinToString(",") { albumJson(it) }}]}""",
            total = if (sendTotalCount) listed.size else null,
        )
    }

    private fun album(id: String): LibraryEndpointResponse {
        val album = albums.firstOrNull { it.id == id }
        if (album == null || id in removedAlbums) {
            if (purgeMissingAlways || album == null) return envelope(failure(70, "Album not found"))
            // The server's default keeps a missing album: ok, songCount unchanged, zero songs.
            return envelope(""""album":${albumJson(album).dropLast(1)},"song":[]}""")
        }
        return envelope(""""album":${albumJson(album).dropLast(1)},"song":[${album.songs.joinToString(",") { song(it, album) }}]}""")
    }

    private fun albumJson(album: Album) =
        """{"id":"${album.id}","name":"${album.name}","artist":"Artist One","artistId":"artist-1","songCount":${album.songs.size},"duration":120${if (album.starred) ",\"starred\":\"2026-09-22T10:00:00Z\"" else ""}}"""

    private fun song(id: String, album: Album? = albums.firstOrNull { a -> a.songs.contains(id) }) =
        """{"id":"$id","title":"Song $id","album":"${album?.name ?: ""}","albumId":"${album?.id ?: ""}","artist":"Artist One","artistId":"artist-1","duration":60,"suffix":"flac"}"""

    private fun failure(code: Int, message: String) = """"error":{"code":$code,"message":"$message"}"""

    private fun envelope(body: String, total: Int? = null): LibraryEndpointResponse {
        val status = if (body.startsWith("\"error\"")) "failed" else "ok"
        return LibraryEndpointResponse(
            statusCode = 200,
            body = """{"subsonic-response":{"status":"$status","version":"1.16.1",$body}}""",
            redactedUrl = "http://fixture.invalid/rest",
            totalCount = total,
        )
    }
}
