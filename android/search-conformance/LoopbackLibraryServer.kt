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
 */
class LoopbackLibraryServer : AutoCloseable {
    private val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"

    /** The genres `getGenres` lists, in the server's order. */
    @Volatile var genres: List<String> = listOf("Jazz", "Ambient")

    /** Each genre's songs: id to title. */
    @Volatile var songsByGenre: Map<String, List<Pair<String, String>>> = mapOf(
        "Jazz" to listOf("jazz-song-1" to "Blue Hour", "jazz-song-2" to "Late Set"),
        "Ambient" to listOf("ambient-song-1" to "Drift"),
    )

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

    /** When set, `stream` is not answered until the server closes: a play stays on the song it started. */
    @Volatile var holdStreams = false
    private val streamsReleased = CountDownLatch(1)

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
        val parameters = listOfNotNull(uri.rawQuery, String(body).takeIf { it.isNotEmpty() })
            .flatMap { it.split('&') }.filter { it.isNotEmpty() }.associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
        log += endpoint to parameters
        if (endpoint == "getCoverArt") {
            respond(client, 404, "")
            return@use
        }
        if (endpoint == "getGenres" && holdGenres) genresReleased.await(30, TimeUnit.SECONDS)
        if (endpoint == "getSong" && holdSongs) songsReleased.await(30, TimeUnit.SECONDS)
        if (endpoint == "stream" && holdStreams) streamsReleased.await(30, TimeUnit.SECONDS)
        respond(client, 200, envelope(endpoint, parameters))
    }

    private fun envelope(endpoint: String, parameters: Map<String, String>): String {
        val payload = when (endpoint) {
            "getScanStatus" -> """"scanStatus":{"scanning":false,"count":6,"lastScan":"2026-09-01T00:00:00Z"}"""
            "getMusicFolders" -> """"musicFolders":{"musicFolder":[{"id":1,"name":"Music"}]}"""
            "getAlbumList2" -> {
                val type = parameters["type"].orEmpty()
                val offset = parameters["offset"]?.toIntOrNull() ?: 0
                val albums = if (offset > 0) "" else (1..2).joinToString(",") { index ->
                    """{"id":"$type-$index","name":${quote(albumTitle(type, index))},"artist":"Fixture Artist","artistId":"artist-1","songCount":1}"""
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
            "getAlbum" -> {
                val id = parameters["id"].orEmpty()
                val title = quote(albumTitle(id.substringBeforeLast('-'), id.substringAfterLast('-').toIntOrNull() ?: 1))
                """"album":{"id":${quote(id)},"name":$title,"artist":"Fixture Artist","artistId":"artist-1","songCount":1,""" +
                    """"song":[{"id":${quote("$id-song-1")},"title":"Opening","album":$title,"albumId":${quote(id)},""" +
                    """"artist":"Fixture Artist","track":1,"duration":120,"suffix":"mp3","contentType":"audio/mpeg"}]}"""
            }
            "getSongsByGenre" -> {
                val offset = parameters["offset"]?.toIntOrNull() ?: 0
                val songs = if (offset > 0) "" else songsByGenre[parameters["genre"]].orEmpty().joinToString(",") { (id, title) -> genreSong(id, title) }
                """"songsByGenre":{"song":[$songs]}"""
            }
            // A genre's songs, as a play re-reads each before queueing it; any other id stays the empty
            // envelope it always was.
            "getSong" -> songsByGenre.values.flatten().firstOrNull { it.first == parameters["id"] }
                ?.let { (id, title) -> """"song":${genreSong(id, title)}""" }
            else -> null
        }
        return """{"subsonic-response":{"status":"ok","version":"1.16.1"${payload?.let { ",$it" }.orEmpty()}}}"""
    }

    private fun genreSong(id: String, title: String) =
        """{"id":"$id","title":${quote(title)},"album":"Genre Album","albumId":"genre-album","artist":"Fixture Artist","duration":120,"suffix":"mp3","contentType":"audio/mpeg"}"""

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray()
        client.getOutputStream().apply {
            write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes)
            flush()
        }
    }

    override fun close() {
        genresReleased.countDown()
        streamsReleased.countDown()
        socket.close()
        connections.shutdownNow()
    }
}
