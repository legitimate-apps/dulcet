package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.db.Db
import java.time.Instant

/**
 * Reads catalog rows joined with one user's annotations and My Library membership and maps them to
 * OpenSubsonic `Child`, `AlbumID3` and `ArtistID3` elements. Field names follow the OpenSubsonic
 * spec; the extra `dulcetInLibrary` flag is the `dulcetLibrary` extension's membership marker.
 */
class Catalog(private val db: Db) {

    // ---- songs -------------------------------------------------------------------------------

    private val songSelect = """
        SELECT t.*, a.starred AS u_starred, a.rating AS u_rating, a.play_count AS u_play_count, a.played AS u_played,
               li.added AS u_added, al.art_track_id AS al_art_track, al.folder_art AS al_folder_art
        FROM track t
        LEFT JOIN annotation a ON a.user_id = ? AND a.item_type = 'song' AND a.item_id = t.id
        LEFT JOIN library_item li ON li.user_id = ? AND li.item_type = 'song' AND li.item_id = t.id
        LEFT JOIN album al ON al.id = t.album_id
    """.trimIndent()

    fun songs(userId: String, where: String = "1=1", args: List<Any?> = emptyList(), order: String = "t.album, t.disc_no, t.track_no, t.path", limit: Int = -1, offset: Int = 0, elementName: String = "song"): List<Node> {
        val rows = db.read {
            query("$songSelect WHERE $where ORDER BY $order LIMIT ? OFFSET ?", userId, userId, *args.toTypedArray(), limit, offset) { SongRow.from(it) }
        }
        return songNodes(rows, elementName)
    }

    fun song(userId: String, id: String, elementName: String = "song"): Node? = songs(userId, "t.id = ?", listOf(id), elementName = elementName).firstOrNull()

    /** Songs in a given id order (playlists, queues), skipping ids that no longer exist. */
    fun songsById(userId: String, ids: List<String>, elementName: String = "entry"): List<Node> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.distinct().chunked(500).flatMap { chunk ->
            songs(userId, "t.id IN (${chunk.joinToString(",") { "?" }})", chunk, elementName = elementName)
        }.associateBy { it["id"] as String }
        return ids.mapNotNull { id -> byId[id] }
    }


    class SongRow(val values: Map<String, Any?>) {
        operator fun get(key: String): Any? = values[key]
        val id: String get() = values["id"] as String

        companion object {
            private val COLUMNS = listOf(
                "id", "path", "title", "sort_title", "album", "album_id", "artist", "artist_id", "album_artist", "album_artist_id",
                "track_no", "disc_no", "year", "release_date", "genre", "duration", "bitrate", "sample_rate", "channels", "bit_depth",
                "suffix", "content_type", "size", "created", "mbz_recording_id", "mbz_track_id", "has_embedded_art", "comment", "bpm",
                "rg_track_gain", "rg_track_peak", "rg_album_gain", "rg_album_peak", "u_starred", "u_rating", "u_play_count", "u_played",
                "u_added", "al_art_track", "al_folder_art", "codec",
            )
            private val INTS = setOf("track_no", "disc_no", "year", "bitrate", "sample_rate", "channels", "bit_depth", "bpm", "u_rating", "u_play_count")
            private val LONGS = setOf("size", "created", "u_starred", "u_played", "u_added")
            private val DOUBLES = setOf("duration", "rg_track_gain", "rg_track_peak", "rg_album_gain", "rg_album_peak")

            fun from(row: Db.Row): SongRow = SongRow(
                COLUMNS.associateWith { column ->
                    when (column) {
                        in INTS -> row.intOrNull(column)
                        in LONGS -> row.longOrNull(column)
                        in DOUBLES -> row.doubleOrNull(column)
                        "has_embedded_art" -> row.bool(column)
                        else -> row.stringOrNull(column)
                    }
                },
            )
        }
    }

    private fun songNodes(rows: List<SongRow>, elementName: String): List<Node> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val credits = HashMap<String, MutableList<Triple<String, String, String>>>()
        val genres = HashMap<String, MutableList<String>>()
        db.read {
            ids.chunked(500).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                query("SELECT track_id, role, artist_id, name FROM track_artist WHERE track_id IN ($marks) ORDER BY position", *chunk.toTypedArray()) {
                    credits.getOrPut(it.string("track_id")) { ArrayList() }.add(Triple(it.string("role"), it.string("artist_id"), it.string("name")))
                }
                query("SELECT track_id, genre FROM track_genre WHERE track_id IN ($marks)", *chunk.toTypedArray()) {
                    genres.getOrPut(it.string("track_id")) { ArrayList() }.add(it.string("genre"))
                }
            }
        }
        return rows.map { row -> songNode(row, elementName, credits[row.id].orEmpty(), genres[row.id].orEmpty()) }
    }

    private fun songNode(r: SongRow, elementName: String, credits: List<Triple<String, String, String>>, genres: List<String>): Node = Node(elementName).apply {
        this["id"] = r.id
        this["parent"] = r["album_id"]
        this["isDir"] = false
        this["title"] = r["title"]
        this["album"] = r["album"]
        this["artist"] = r["artist"]
        this["track"] = r["track_no"]
        this["year"] = r["year"]
        this["genre"] = r["genre"]
        this["coverArt"] = songCoverArt(r)
        this["size"] = r["size"]
        this["contentType"] = r["content_type"]
        this["suffix"] = r["suffix"]
        this["duration"] = ((r["duration"] as Double?) ?: 0.0).toInt()
        this["bitRate"] = r["bitrate"]
        this["path"] = r["path"]
        this["discNumber"] = r["disc_no"]
        this["created"] = iso(r["created"] as Long?)
        this["albumId"] = r["album_id"]
        this["artistId"] = r["artist_id"]
        this["type"] = "music"
        this["isVideo"] = false
        (r["u_played"] as Long?)?.let { this["played"] = iso(it) }
        this["bpm"] = r["bpm"] ?: 0
        this["comment"] = r["comment"] ?: ""
        this["sortName"] = r["sort_title"] ?: ""
        this["mediaType"] = "song"
        this["musicBrainzId"] = r["mbz_recording_id"] ?: ""
        genres.forEach { genre -> add("genres") { this["name"] = genre } }
        child("replayGain") {
            r["rg_track_gain"]?.let { this["trackGain"] = it }
            r["rg_album_gain"]?.let { this["albumGain"] = it }
            r["rg_track_peak"]?.let { this["trackPeak"] = it }
            r["rg_album_peak"]?.let { this["albumPeak"] = it }
        }
        this["channelCount"] = r["channels"]
        this["samplingRate"] = r["sample_rate"]
        this["bitDepth"] = r["bit_depth"] ?: 0
        credits.filter { it.first == "artist" }.forEach { (_, id, name) -> add("artists") { this["id"] = id; this["name"] = name } }
        this["displayArtist"] = r["artist"]
        credits.filter { it.first == "albumartist" }.forEach { (_, id, name) -> add("albumArtists") { this["id"] = id; this["name"] = name } }
        this["displayAlbumArtist"] = r["album_artist"]
        credits.filter { it.first == "composer" }.forEach { (_, id, name) ->
            add("contributors") { this["role"] = "composer"; child("artist") { this["id"] = id; this["name"] = name } }
        }
        this["displayComposer"] = credits.filter { it.first == "composer" }.joinToString(" • ") { it.third }
        this["explicitStatus"] = ""
        (r["u_starred"] as Long?)?.let { this["starred"] = iso(it) }
        (r["u_rating"] as Int?)?.takeIf { it > 0 }?.let { this["userRating"] = it }
        this["playCount"] = (r["u_play_count"] as Int?) ?: 0
        if (r["u_added"] != null) this["dulcetInLibrary"] = true
    }

    private fun songCoverArt(r: SongRow): String =
        if (r["has_embedded_art"] == true) "mf-${r.id}" else "al-${r["album_id"]}"

    // ---- albums ------------------------------------------------------------------------------

    private val albumSelect = """
        SELECT al.*, a.starred AS u_starred, a.rating AS u_rating, a.play_count AS u_play_count, a.played AS u_played, li.added AS u_added
        FROM album al
        LEFT JOIN annotation a ON a.user_id = ? AND a.item_type = 'album' AND a.item_id = al.id
        LEFT JOIN library_item li ON li.user_id = ? AND li.item_type = 'album' AND li.item_id = al.id
    """.trimIndent()

    fun albums(userId: String, where: String = "1=1", args: List<Any?> = emptyList(), order: String = "al.sort_name COLLATE NOCASE, al.id", limit: Int = -1, offset: Int = 0, elementName: String = "album", legacyChild: Boolean = false): List<Node> {
        data class AlbumRow(val v: Map<String, Any?>)
        val rows = db.read {
            query("$albumSelect WHERE $where ORDER BY $order LIMIT ? OFFSET ?", userId, userId, *args.toTypedArray(), limit, offset) { row ->
                AlbumRow(
                    mapOf(
                        "id" to row.string("id"), "name" to row.string("name"), "sort_name" to row.string("sort_name"),
                        "album_artist" to row.string("album_artist"), "artist_id" to row.string("artist_id"), "year" to row.intOrNull("year"),
                        "genre" to row.stringOrNull("genre"), "compilation" to row.bool("compilation"), "song_count" to row.int("song_count"),
                        "duration" to row.double("duration"), "created" to row.long("created"), "mbz" to row.stringOrNull("mbz_album_id"),
                        "u_starred" to row.longOrNull("u_starred"), "u_rating" to row.intOrNull("u_rating"), "u_play_count" to row.intOrNull("u_play_count"),
                        "u_played" to row.longOrNull("u_played"), "u_added" to row.longOrNull("u_added"),
                    ),
                )
            }
        }
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.v["id"] as String }
        val artists = HashMap<String, MutableList<Pair<String, String>>>()
        val genres = HashMap<String, MutableSet<String>>()
        val discs = HashMap<String, MutableMap<Int, String?>>()
        db.read {
            ids.chunked(500).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                query("SELECT album_id, artist_id, name FROM album_artist WHERE album_id IN ($marks) ORDER BY position", *chunk.toTypedArray()) {
                    artists.getOrPut(it.string("album_id")) { ArrayList() }.add(it.string("artist_id") to it.string("name"))
                }
                query("SELECT DISTINCT t.album_id, g.genre FROM track t JOIN track_genre g ON g.track_id = t.id WHERE t.album_id IN ($marks)", *chunk.toTypedArray()) {
                    genres.getOrPut(it.string("album_id")) { LinkedHashSet() }.add(it.string("genre"))
                }
                query("SELECT album_id, disc_no, MAX(disc_subtitle) AS sub FROM track WHERE album_id IN ($marks) AND disc_no IS NOT NULL GROUP BY album_id, disc_no", *chunk.toTypedArray()) {
                    discs.getOrPut(it.string("album_id")) { sortedMapOf() }[it.int("disc_no")] = it.stringOrNull("sub")
                }
            }
        }
        return rows.map { row ->
            val v = row.v
            val id = v["id"] as String
            Node(elementName).apply {
                this["id"] = id
                if (legacyChild) {
                    this["parent"] = v["artist_id"]
                    this["isDir"] = true
                    this["title"] = v["name"]
                    this["album"] = v["name"]
                } else {
                    this["name"] = v["name"]
                }
                this["artist"] = v["album_artist"]
                this["artistId"] = v["artist_id"]
                this["coverArt"] = "al-$id"
                this["songCount"] = v["song_count"]
                this["duration"] = (v["duration"] as Double).toInt()
                this["playCount"] = (v["u_play_count"] as Int?) ?: 0
                (v["u_played"] as Long?)?.let { this["played"] = iso(it) }
                this["created"] = iso(v["created"] as Long)
                this["year"] = v["year"]
                this["genre"] = v["genre"]
                (v["u_starred"] as Long?)?.let { this["starred"] = iso(it) }
                (v["u_rating"] as Int?)?.takeIf { it > 0 }?.let { this["userRating"] = it }
                genres[id].orEmpty().forEach { genre -> add("genres") { this["name"] = genre } }
                this["musicBrainzId"] = v["mbz"] ?: ""
                this["isCompilation"] = v["compilation"]
                this["sortName"] = v["sort_name"]
                discs[id].orEmpty().filter { it.key > 0 }.takeIf { it.size > 1 || it.values.any { sub -> sub != null } }?.forEach { (disc, title) ->
                    add("discTitles") { this["disc"] = disc; this["title"] = title ?: "" }
                }
                artists[id].orEmpty().forEach { (artistId, name) -> add("artists") { this["id"] = artistId; this["name"] = name } }
                this["displayArtist"] = v["album_artist"]
                this["explicitStatus"] = ""
                if (v["u_added"] != null) this["dulcetInLibrary"] = true
            }
        }
    }

    fun album(userId: String, id: String): Node? = albums(userId, "al.id = ?", listOf(id)).firstOrNull()

    // ---- artists -----------------------------------------------------------------------------

    fun artists(userId: String, where: String = "ar.album_count > 0", args: List<Any?> = emptyList(), order: String = "ar.sort_name COLLATE NOCASE, ar.id", limit: Int = -1, offset: Int = 0, elementName: String = "artist"): List<Node> = db.read {
        query(
            """SELECT ar.*, a.starred AS u_starred, a.rating AS u_rating, li.added AS u_added FROM artist ar
               LEFT JOIN annotation a ON a.user_id = ? AND a.item_type = 'artist' AND a.item_id = ar.id
               LEFT JOIN library_item li ON li.user_id = ? AND li.item_type = 'artist' AND li.item_id = ar.id
               WHERE $where ORDER BY $order LIMIT ? OFFSET ?""",
            userId, userId, *args.toTypedArray(), limit, offset,
        ) { row ->
            Node(elementName).apply {
                this["id"] = row.string("id")
                this["name"] = row.string("name")
                this["coverArt"] = "ar-${row.string("id")}"
                this["albumCount"] = row.int("album_count")
                row.longOrNull("u_starred")?.let { this["starred"] = iso(it) }
                row.intOrNull("u_rating")?.takeIf { it > 0 }?.let { this["userRating"] = it }
                this["musicBrainzId"] = row.stringOrNull("mbz_artist_id") ?: ""
                this["sortName"] = row.string("sort_name")
                strings("roles", buildList { if (row.int("album_count") > 0) add("albumartist"); if (row.int("song_count") > 0) add("artist") })
                if (row.longOrNull("u_added") != null) this["dulcetInLibrary"] = true
            }
        }
    }

    fun artist(userId: String, id: String): Node? = artists(userId, "ar.id = ?", listOf(id)).firstOrNull()

    // ---- lookups -----------------------------------------------------------------------------

    /** What kind of catalog item an opaque id names, or null when it names nothing. */
    fun kindOf(id: String): String? = db.read {
        when {
            long("SELECT COUNT(*) FROM track WHERE id = ?", id) > 0 -> "song"
            long("SELECT COUNT(*) FROM album WHERE id = ?", id) > 0 -> "album"
            long("SELECT COUNT(*) FROM artist WHERE id = ?", id) > 0 -> "artist"
            else -> null
        }
    }

    companion object {
        fun iso(epochMillis: Long?): String? = epochMillis?.let { Instant.ofEpochMilli(it).toString() }
    }
}
