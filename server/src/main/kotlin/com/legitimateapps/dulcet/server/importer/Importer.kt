package com.legitimateapps.dulcet.server.importer

import com.legitimateapps.dulcet.server.Ids
import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.USER_AGENT
import com.legitimateapps.dulcet.server.api.ensureAnnotation
import com.legitimateapps.dulcet.server.auth.Users
import com.legitimateapps.dulcet.server.fold
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/** A song as another server describes it, with the importing user's state on it. */
data class RemoteSong(
    val id: String, val path: String?, val mbzRecordingId: String?, val title: String, val artist: String?, val album: String?,
    val track: Int?, val disc: Int?, val durationSeconds: Int?, val starred: Long?, val rating: Int, val playCount: Int, val played: Long?,
    val albumId: String?, val artistId: String?,
)

data class RemoteItem(val id: String, val name: String, val mbzId: String?, val starred: Long?, val rating: Int, val playCount: Int, val played: Long?, val artist: String?)

data class RemotePlaylist(val name: String, val comment: String?, val songIds: List<String>)

/**
 * The seam every importer implements: read one user's songs, albums, artists and playlists from a
 * source server. Navidrome (any OpenSubsonic server) is implemented below; a Jellyfin source would
 * read `/Users/{id}/Items` with `UserData`, and a Plex source `/library/sections/{id}/all` with
 * `viewCount`/`userRating` — both map onto these same records.
 */
interface ImportSource {
    val name: String
    fun songs(): List<RemoteSong>
    fun albums(): List<RemoteItem>
    fun artists(): List<RemoteItem>
    fun playlists(): List<RemotePlaylist>
}

data class ImportOptions(val url: String, val username: String, val password: String, val intoUserId: String, val dryRun: Boolean = false)

data class ImportReport(
    var songsSeen: Int = 0, var matchedByPath: Int = 0, var matchedByMbz: Int = 0, var matchedByTags: Int = 0, var unmatched: Int = 0,
    var stars: Int = 0, var ratings: Int = 0, var playCounts: Int = 0, var albumsMatched: Int = 0, var artistsMatched: Int = 0,
    var playlists: Int = 0, var playlistEntries: Int = 0, var playlistEntriesMissing: Int = 0, val unmatchedExamples: MutableList<String> = ArrayList(),
) {
    fun summary(): String = buildString {
        appendLine("songs seen $songsSeen: path $matchedByPath, musicbrainz $matchedByMbz, tags $matchedByTags, unmatched $unmatched")
        appendLine("albums matched $albumsMatched, artists matched $artistsMatched")
        appendLine("applied: stars $stars, ratings $ratings, play counts $playCounts")
        appendLine("playlists $playlists ($playlistEntries entries, $playlistEntriesMissing not in this library)")
        if (unmatchedExamples.isNotEmpty()) appendLine("unmatched examples: " + unmatchedExamples.joinToString("; "))
    }
}

/** Reads any OpenSubsonic server (Navidrome, and others) as the importing user. */
class OpenSubsonicSource(private val baseUrl: String, private val username: String, private val password: String, override val name: String = "Navidrome") : ImportSource {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val random = SecureRandom()

    private fun call(method: String, params: Map<String, String> = emptyMap()): JsonObject {
        val salt = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val token = Users.md5Hex(password + salt)
        fun q(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8)
        val query = (mapOf("u" to username, "t" to token, "s" to salt, "v" to "1.16.1", "c" to "dulcet-import", "f" to "json") + params)
            .entries.joinToString("&") { "${q(it.key)}=${q(it.value)}" }
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/rest/" + method + "?" + query))
            .header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(60)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val envelope = Json.parseToJsonElement(response.body()).jsonObject["subsonic-response"]?.jsonObject
            ?: error("$method: not an OpenSubsonic response (HTTP ${response.statusCode()})")
        if ((envelope["status"] as? JsonPrimitive)?.contentOrNull != "ok") {
            val error = envelope["error"] as? JsonObject
            error("$method failed: ${(error?.get("code") as? JsonPrimitive)?.contentOrNull} ${(error?.get("message") as? JsonPrimitive)?.contentOrNull}")
        }
        return envelope
    }

    override fun songs(): List<RemoteSong> {
        val result = ArrayList<RemoteSong>()
        var offset = 0
        while (true) {
            val page = call("search3", mapOf("query" to "", "songCount" to "500", "songOffset" to offset.toString(), "artistCount" to "0", "albumCount" to "0"))
            val songs = (page["searchResult3"] as? JsonObject)?.get("song") as? JsonArray ?: break
            if (songs.isEmpty()) break
            songs.mapTo(result) { song(it.jsonObject) }
            if (songs.size < 500) break
            offset += songs.size
        }
        return result
    }

    override fun albums(): List<RemoteItem> {
        val result = ArrayList<RemoteItem>()
        var offset = 0
        while (true) {
            val page = call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500", "offset" to offset.toString()))
            val albums = (page["albumList2"] as? JsonObject)?.get("album") as? JsonArray ?: break
            if (albums.isEmpty()) break
            albums.mapTo(result) { item(it.jsonObject, "name") }
            if (albums.size < 500) break
            offset += albums.size
        }
        return result
    }

    override fun artists(): List<RemoteItem> {
        val indexes = (call("getArtists")["artists"] as? JsonObject)?.get("index") as? JsonArray ?: return emptyList()
        return indexes.flatMap { index -> ((index.jsonObject["artist"] as? JsonArray) ?: JsonArray(emptyList())).map { item(it.jsonObject, "name") } }
    }

    override fun playlists(): List<RemotePlaylist> {
        val lists = (call("getPlaylists")["playlists"] as? JsonObject)?.get("playlist") as? JsonArray ?: return emptyList()
        return lists.mapNotNull { summary ->
            val obj = summary.jsonObject
            if (obj.text("owner")?.equals(username, true) == false) return@mapNotNull null
            val full = call("getPlaylist", mapOf("id" to obj.text("id")!!))["playlist"]?.jsonObject ?: return@mapNotNull null
            RemotePlaylist(full.text("name") ?: "Playlist", full.text("comment"), (full["entry"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.text("id") })
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)
    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.time(key: String): Long? = text(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun song(o: JsonObject) = RemoteSong(
        id = o.text("id")!!, path = o.text("path"), mbzRecordingId = o.text("musicBrainzId"), title = o.text("title") ?: "",
        artist = o.text("artist"), album = o.text("album"), track = o.number("track"), disc = o.number("discNumber"),
        durationSeconds = o.number("duration"), starred = o.time("starred"), rating = o.number("userRating") ?: 0,
        playCount = o.number("playCount") ?: 0, played = o.time("played"), albumId = o.text("albumId"), artistId = o.text("artistId"),
    )

    private fun item(o: JsonObject, nameKey: String) = RemoteItem(
        id = o.text("id")!!, name = o.text(nameKey) ?: "", mbzId = o.text("musicBrainzId"), starred = o.time("starred"),
        rating = o.number("userRating") ?: 0, playCount = o.number("playCount") ?: 0, played = o.time("played"), artist = o.text("artist"),
    )
}

/**
 * Applies a source's user state to one Dulcet user. Songs match by path (the longest common path
 * suffix, since each server roots paths differently), then MusicBrainz recording id, then tags
 * (title, artist, album, track and duration within two seconds). Albums and artists follow their
 * matched songs, then MusicBrainz id, then folded names. Re-running is safe: stars keep the earlier
 * timestamp, play counts take the larger value, playlists replace an earlier import of the same name.
 */
class Importer(private val ctx: ServerContext) {
    private data class LocalSong(val id: String, val path: String, val mbz: String?, val title: String, val artist: String, val album: String, val track: Int?, val duration: Double, val albumId: String, val artistIds: List<String>)

    fun run(source: ImportSource, options: ImportOptions): ImportReport {
        val report = ImportReport()
        val local = ctx.db.read {
            val artists = HashMap<String, MutableList<String>>()
            query("SELECT track_id, artist_id FROM track_artist WHERE role IN ('artist','albumartist')") { artists.getOrPut(it.string("track_id")) { ArrayList() }.add(it.string("artist_id")) }
            query("SELECT id, path, mbz_recording_id, title, artist, album, track_no, duration, album_id FROM track") {
                LocalSong(it.string("id"), it.string("path"), it.stringOrNull("mbz_recording_id"), it.string("title"), it.string("artist"), it.string("album"), it.intOrNull("track_no"), it.double("duration"), it.string("album_id"), artists[it.string("id")].orEmpty())
            }
        }
        val byPathSuffix = HashMap<String, MutableList<LocalSong>>()
        local.forEach { song -> suffixes(song.path).forEach { byPathSuffix.getOrPut(it) { ArrayList() }.add(song) } }
        val byMbz = local.filter { it.mbz != null }.groupBy { it.mbz!! }
        val byTitle = local.groupBy { fold(it.title) }

        val songMatch = HashMap<String, LocalSong>()
        val remoteSongs = source.songs()
        report.songsSeen = remoteSongs.size
        for (remote in remoteSongs) {
            val path = remote.path?.let { p -> suffixes(p).firstNotNullOfOrNull { key -> byPathSuffix[key]?.singleOrNull() } }
            val match = path?.also { report.matchedByPath++ }
                ?: remote.mbzRecordingId?.let { byMbz[it]?.singleOrNull() }?.also { report.matchedByMbz++ }
                ?: byTitle[fold(remote.title)].orEmpty().filter { candidate ->
                    (remote.artist == null || fold(candidate.artist).contains(fold(remote.artist)) || fold(remote.artist).contains(fold(candidate.artist))) &&
                        (remote.album == null || fold(candidate.album) == fold(remote.album)) &&
                        (remote.track == null || candidate.track == null || remote.track == candidate.track) &&
                        (remote.durationSeconds == null || kotlin.math.abs(candidate.duration - remote.durationSeconds) <= 2.0)
                }.singleOrNull()?.also { report.matchedByTags++ }
            if (match == null) {
                report.unmatched++
                if (report.unmatchedExamples.size < 5) report.unmatchedExamples.add("${remote.artist} - ${remote.title}")
            } else {
                songMatch[remote.id] = match
            }
        }

        // Albums and artists: through matched songs first, then MusicBrainz, then names.
        val albumVotes = HashMap<String, String>()
        val artistVotes = HashMap<String, String>()
        for (remote in remoteSongs) {
            val match = songMatch[remote.id] ?: continue
            remote.albumId?.let { albumVotes.putIfAbsent(it, match.albumId) }
        }
        val localAlbums = ctx.db.read { query("SELECT id, name, album_artist, mbz_album_id FROM album") { listOf(it.string("id"), it.string("name"), it.string("album_artist"), it.stringOrNull("mbz_album_id")) } }
        val localArtists = ctx.db.read { query("SELECT id, search, mbz_artist_id FROM artist") { Triple(it.string("id"), it.string("search"), it.stringOrNull("mbz_artist_id")) } }
        val albums = source.albums().mapNotNull { remote ->
            val id = albumVotes[remote.id]
                ?: localAlbums.firstOrNull { remote.mbzId != null && it[3] == remote.mbzId }?.get(0)
                ?: localAlbums.filter { fold(it[1]!!) == fold(remote.name) && (remote.artist == null || fold(it[2]!!) == fold(remote.artist)) }.singleOrNull()?.get(0)
            id?.let { remote to it }
        }
        report.albumsMatched = albums.size
        val artists = source.artists().mapNotNull { remote ->
            val id = artistVotes[remote.id]
                ?: localArtists.firstOrNull { remote.mbzId != null && it.third == remote.mbzId }?.first
                ?: localArtists.firstOrNull { it.second == fold(remote.name) }?.first
            id?.let { remote to it }
        }
        report.artistsMatched = artists.size

        val playlists = source.playlists()
        if (options.dryRun) {
            report.playlists = playlists.size
            playlists.forEach { pl -> pl.songIds.forEach { if (songMatch[it] != null) report.playlistEntries++ else report.playlistEntriesMissing++ } }
            return report
        }
        val user = options.intoUserId
        ctx.db.write {
            fun apply(type: String, id: String, starred: Long?, rating: Int, playCount: Int, played: Long?) {
                ensureAnnotation(user, type, id)
                if (starred != null) { execute("UPDATE annotation SET starred = COALESCE(starred, ?) WHERE user_id = ? AND item_type = ? AND item_id = ?", starred, user, type, id); report.stars++ }
                if (rating > 0) { execute("UPDATE annotation SET rating = ? WHERE user_id = ? AND item_type = ? AND item_id = ?", rating, user, type, id); report.ratings++ }
                if (playCount > 0) {
                    execute("UPDATE annotation SET play_count = MAX(play_count, ?), played = MAX(COALESCE(played, 0), ?) WHERE user_id = ? AND item_type = ? AND item_id = ?", playCount, played ?: 0, user, type, id)
                    report.playCounts++
                }
            }
            for (remote in remoteSongs) {
                val match = songMatch[remote.id] ?: continue
                if (remote.starred != null || remote.rating > 0 || remote.playCount > 0) apply("song", match.id, remote.starred, remote.rating, remote.playCount, remote.played)
            }
            for ((remote, id) in albums) if (remote.starred != null || remote.rating > 0 || remote.playCount > 0) apply("album", id, remote.starred, remote.rating, remote.playCount, remote.played)
            for ((remote, id) in artists) if (remote.starred != null || remote.rating > 0) apply("artist", id, remote.starred, remote.rating, 0, null)
            val now = System.currentTimeMillis()
            for (playlist in playlists) {
                val comment = "Imported from ${source.name}"
                val existing = single("SELECT id FROM playlist WHERE owner_id = ? AND name = ? AND comment = ?", user, playlist.name, comment) { it.string("id") }
                val id = existing ?: Ids.of("playlist", user, playlist.name, "import", now.toString())
                if (existing == null) execute("INSERT INTO playlist(id, owner_id, name, comment, public, created, changed) VALUES (?,?,?,?,0,?,?)", id, user, playlist.name, comment, now, now)
                execute("DELETE FROM playlist_track WHERE playlist_id = ?", id)
                var position = 0
                for (remoteId in playlist.songIds) {
                    val match = songMatch[remoteId]
                    if (match == null) { report.playlistEntriesMissing++; continue }
                    execute("INSERT INTO playlist_track VALUES (?,?,?)", id, position++, match.id)
                    report.playlistEntries++
                }
                execute("UPDATE playlist SET changed = ? WHERE id = ?", now, id)
                report.playlists++
            }
        }
        return report
    }

    /** Path suffix keys from the longest (artist/album/file) to the file name alone, folded. */
    private fun suffixes(path: String): List<String> {
        val parts = path.replace('\\', '/').split('/').filter(String::isNotEmpty).map(::fold)
        return (minOf(parts.size, 4) downTo 2).map { n -> parts.takeLast(n).joinToString("/") }
    }
}

class NavidromeImporter(private val ctx: ServerContext) {
    fun run(options: ImportOptions): ImportReport =
        Importer(ctx).run(OpenSubsonicSource(options.url, options.username, options.password), options)
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
