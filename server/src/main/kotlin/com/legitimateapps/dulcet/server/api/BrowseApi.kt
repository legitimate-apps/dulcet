package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.IGNORED_ARTICLES
import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.fold
import com.legitimateapps.dulcet.server.indexLetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.DelicateCoroutinesApi

/** Server information, folders, browsing by ID3 tags and by directory, search and album lists. */
fun browseHandlers(ctx: ServerContext): Map<String, Handler> {
    val catalog = ctx.catalog
    val db = ctx.db

    fun lastModified(): Long = ctx.lastScanMillis() ?: 0L

    fun indexed(call: Call, container: String, legacy: Boolean): Reply {
        val artists = catalog.artists(call.user.id)
        return ok {
            child(container) {
                this["ignoredArticles"] = IGNORED_ARTICLES
                if (legacy) this["lastModified"] = lastModified() else this["lastModified"] = lastModified()
                artists.groupBy { indexLetter(it["name"] as String) }.toSortedMap(compareBy<String> { if (it == "#") "~" else it })
                    .forEach { (letter, members) ->
                        add("index") {
                            this["name"] = letter
                            members.forEach { add(it) }
                        }
                    }
            }
        }
    }

    fun requireSong(call: Call, id: String) = catalog.song(call.user.id, id) ?: throw SubsonicError.notFound("Song")

    return mapOf(
        "ping" to { _ -> ok() },
        "getLicense" to { _ -> ok { child("license") { this["valid"] = true } } },
        "getOpenSubsonicExtensions" to { _ ->
            ok {
                ctx.extensions.forEach { (name, versions) ->
                    add("openSubsonicExtensions") {
                        this["name"] = name
                        numbers("versions", versions)
                    }
                }
            }
        },
        "getMusicFolders" to { _ ->
            ok { child("musicFolders") { add("musicFolder") { this["id"] = 1; this["name"] = ctx.config.musicFolderName } } }
        },
        "getArtists" to { call -> indexed(call, "artists", legacy = false) },
        "getIndexes" to { call -> indexed(call, "indexes", legacy = true) },
        "getArtist" to { call ->
            val id = call.required("id")
            val artist = catalog.artist(call.user.id, id) ?: throw SubsonicError.notFound("Artist")
            catalog.albums(call.user.id, "al.id IN (SELECT album_id FROM album_artist WHERE artist_id = ?)", listOf(id), order = "al.year, al.sort_name COLLATE NOCASE, al.id")
                .forEach { artist.add(it) }
            ok { put(artist) }
        },
        "getAlbum" to { call ->
            val id = call.required("id")
            val album = catalog.album(call.user.id, id) ?: throw SubsonicError.notFound("Album")
            catalog.songs(call.user.id, "t.album_id = ?", listOf(id), order = "COALESCE(t.disc_no, 0), COALESCE(t.track_no, 0), t.path").forEach { album.add(it) }
            ok { put(album) }
        },
        "getSong" to { call -> ok { put(requireSong(call, call.required("id"))) } },
        "getMusicDirectory" to { call ->
            val id = call.required("id")
            val userId = call.user.id
            when (catalog.kindOf(id)) {
                "artist" -> {
                    val artist = catalog.artist(userId, id)!!
                    ok {
                        child("directory") {
                            this["id"] = id
                            this["name"] = artist["name"]
                            catalog.albums(userId, "al.id IN (SELECT album_id FROM album_artist WHERE artist_id = ?)", listOf(id), elementName = "child", legacyChild = true).forEach { add(it) }
                        }
                    }
                }
                "album" -> {
                    val album = catalog.album(userId, id)!!
                    ok {
                        child("directory") {
                            this["id"] = id
                            this["name"] = album["name"]
                            this["parent"] = album["artistId"]
                            catalog.songs(userId, "t.album_id = ?", listOf(id), order = "COALESCE(t.disc_no, 0), COALESCE(t.track_no, 0), t.path", elementName = "child").forEach { add(it) }
                        }
                    }
                }
                else -> throw SubsonicError.notFound("Directory")
            }
        },
        "getGenres" to { _ ->
            val genres = db.read {
                query("SELECT g.genre, COUNT(DISTINCT g.track_id) AS songs, COUNT(DISTINCT t.album_id) AS albums FROM track_genre g JOIN track t ON t.id = g.track_id GROUP BY g.genre ORDER BY g.genre COLLATE NOCASE") {
                    Triple(it.string("genre"), it.int("songs"), it.int("albums"))
                }
            }
            ok {
                child("genres") {
                    genres.forEach { (name, songs, albums) -> add("genre") { this["songCount"] = songs; this["albumCount"] = albums; value = name } }
                }
            }
        },
        "getSongsByGenre" to { call ->
            val genre = call.required("genre")
            val songs = catalog.songs(call.user.id, "t.id IN (SELECT track_id FROM track_genre WHERE genre = ?)", listOf(genre), limit = call.size("count", 10), offset = call.int("offset", 0))
            ok { child("songsByGenre") { songs.forEach { add(it) } } }
        },
        "getRandomSongs" to { call ->
            val where = StringBuilder("1=1")
            val args = ArrayList<Any?>()
            call.param("genre")?.let { where.append(" AND t.id IN (SELECT track_id FROM track_genre WHERE genre = ?)"); args.add(it) }
            call.intOrNull("fromYear")?.let { where.append(" AND t.year >= ?"); args.add(it) }
            call.intOrNull("toYear")?.let { where.append(" AND t.year <= ?"); args.add(it) }
            val songs = catalog.songs(call.user.id, where.toString(), args, order = "RANDOM()", limit = call.size("size", 10))
            ok { child("randomSongs") { songs.forEach { add(it) } } }
        },
        "getAlbumList2" to { call -> albumList(ctx, call, "albumList2", legacy = false) },
        "getAlbumList" to { call -> albumList(ctx, call, "albumList", legacy = true) },
        "getStarred2" to { call -> starred(ctx, call, "starred2") },
        "getStarred" to { call -> starred(ctx, call, "starred") },
        "search3" to { call -> search(ctx, call, "searchResult3") },
        "search2" to { call -> search(ctx, call, "searchResult2") },
        "getScanStatus" to { _ -> ok { put(scanStatus(ctx)) } },
        "startScan" to { call ->
            if (!call.user.isAdmin) throw SubsonicError.notAuthorized()
            ctx.requestScan()
            ok { put(scanStatus(ctx).also { it["scanning"] = true }) }
        },
        "getUser" to { call ->
            val name = call.param("username") ?: call.user.name
            if (!name.equals(call.user.name, ignoreCase = true) && !call.user.isAdmin) throw SubsonicError.notAuthorized()
            val user = ctx.users.find(name) ?: throw SubsonicError.notFound("User")
            ok { put(userNode(user.name, user.isAdmin)) }
        },
        "getUsers" to { call ->
            if (!call.user.isAdmin) throw SubsonicError.notAuthorized()
            ok { child("users") { ctx.users.all().forEach { add(userNode(it.name, it.isAdmin)) } } }
        },
        "getNowPlaying" to { call ->
            val now = System.currentTimeMillis()
            val entries = db.read {
                query("SELECT n.track_id, n.client, n.at, u.name FROM now_playing n JOIN user u ON u.id = n.user_id WHERE n.at > ? ORDER BY n.at DESC", now - 30 * 60_000) {
                    listOf(it.string("track_id"), it.string("client"), it.long("at").toString(), it.string("name"))
                }
            }
            ok {
                child("nowPlaying") {
                    entries.forEach { (trackId, client, at, name) ->
                        catalog.song(call.user.id, trackId, "entry")?.let { entry ->
                            entry["username"] = name
                            entry["minutesAgo"] = ((now - at.toLong()) / 60_000).toInt()
                            entry["playerId"] = 0
                            entry["playerName"] = client
                            add(entry)
                        }
                    }
                }
            }
        },
        "getArtistInfo2" to { call -> call.required("id"); ok { child("artistInfo2") } },
        "getArtistInfo" to { call -> call.required("id"); ok { child("artistInfo") } },
        "getAlbumInfo2" to { call -> call.required("id"); ok { child("albumInfo") } },
        "getAlbumInfo" to { call -> call.required("id"); ok { child("albumInfo") } },
        "getTopSongs" to { call ->
            val artist = call.required("artist")
            val songs = catalog.songs(
                call.user.id, "t.id IN (SELECT track_id FROM track_artist WHERE role = 'artist' AND artist_id IN (SELECT id FROM artist WHERE search = ?))",
                listOf(fold(artist)), order = "COALESCE(a.play_count, 0) DESC, t.title COLLATE NOCASE", limit = call.size("count", 50),
            )
            ok { child("topSongs") { songs.forEach { add(it) } } }
        },
        "getSimilarSongs2" to { call -> call.required("id"); ok { child("similarSongs2") } },
        "getSimilarSongs" to { call -> call.required("id"); ok { child("similarSongs") } },
        "getBookmarks" to { _ -> ok { child("bookmarks") } },
        "getInternetRadioStations" to { _ -> ok { child("internetRadioStations") } },
        "getPodcasts" to { _ -> ok { child("podcasts") } },
        "getNewestPodcasts" to { _ -> ok { child("newestPodcasts") } },
        "getShares" to { _ -> ok { child("shares") } },
    )
}

fun userNode(name: String, admin: Boolean): Node = Node("user").apply {
    this["username"] = name
    this["scrobblingEnabled"] = true
    this["adminRole"] = admin
    this["settingsRole"] = true
    this["downloadRole"] = true
    this["uploadRole"] = false
    this["playlistRole"] = true
    this["coverArtRole"] = true
    this["commentRole"] = false
    this["podcastRole"] = false
    this["streamRole"] = true
    this["jukeboxRole"] = false
    this["shareRole"] = false
    this["videoConversionRole"] = false
    add(Node("folder").apply { value = "1" })
}

fun scanStatus(ctx: ServerContext): Node = Node("scanStatus").apply {
    this["scanning"] = ctx.scanner.isScanning
    this["count"] = if (ctx.scanner.isScanning) ctx.scanner.scannedCount.toLong() else ctx.db.read { long("SELECT COUNT(*) FROM track") }
    this["folderCount"] = ctx.db.read { string("SELECT value FROM meta WHERE key = 'folder_count'") }?.toLongOrNull() ?: 0L
    ctx.db.read { string("SELECT value FROM meta WHERE key = 'last_scan'") }?.let { this["lastScan"] = it }
}

private fun albumList(ctx: ServerContext, call: Call, container: String, legacy: Boolean): Reply {
    val type = call.required("type")
    val size = call.size("size", 10)
    val offset = call.int("offset", 0)
    val args = ArrayList<Any?>()
    var where = "1=1"
    val order = when (type) {
        "random" -> "RANDOM()"
        "newest" -> "al.created DESC, al.id"
        "highest" -> { where = "a.rating > 0"; "a.rating DESC, al.sort_name COLLATE NOCASE, al.id" }
        "frequent" -> { where = "a.play_count > 0"; "a.play_count DESC, al.id" }
        "recent" -> { where = "a.played IS NOT NULL"; "a.played DESC, al.id" }
        "alphabeticalByName" -> "al.sort_name COLLATE NOCASE, al.id"
        "alphabeticalByArtist" -> "al.album_artist COLLATE NOCASE, al.year, al.sort_name COLLATE NOCASE, al.id"
        "starred" -> { where = "a.starred IS NOT NULL"; "al.sort_name COLLATE NOCASE, al.id" }
        "byYear" -> {
            val from = call.intOrNull("fromYear") ?: throw SubsonicError.missing("fromYear")
            val to = call.intOrNull("toYear") ?: throw SubsonicError.missing("toYear")
            where = "al.year BETWEEN ? AND ?"
            args.add(minOf(from, to)); args.add(maxOf(from, to))
            if (from <= to) "al.year, al.sort_name COLLATE NOCASE, al.id" else "al.year DESC, al.sort_name COLLATE NOCASE, al.id"
        }
        "byGenre" -> {
            val genre = call.param("genre") ?: throw SubsonicError.missing("genre")
            where = "al.id IN (SELECT t.album_id FROM track t JOIN track_genre g ON g.track_id = t.id WHERE g.genre = ?)"
            args.add(genre)
            "al.sort_name COLLATE NOCASE, al.id"
        }
        else -> throw SubsonicError(0, "Invalid list type: $type")
    }
    val albums = ctx.catalog.albums(call.user.id, where, args, order, size, offset, elementName = if (legacy) "album" else "album", legacyChild = legacy)
    return ok { child(container) { albums.forEach { add(it) } } }
}

private fun starred(ctx: ServerContext, call: Call, container: String): Reply {
    val userId = call.user.id
    val artists = ctx.catalog.artists(userId, "a.starred IS NOT NULL", order = "a.starred DESC, ar.id")
    val albums = ctx.catalog.albums(userId, "a.starred IS NOT NULL", order = "a.starred DESC, al.id", legacyChild = container == "starred")
    val songs = ctx.catalog.songs(userId, "a.starred IS NOT NULL", order = "a.starred DESC, t.id")
    return ok {
        child(container) {
            artists.forEach { add(it) }
            albums.forEach { add(it) }
            songs.forEach { add(it) }
        }
    }
}

private fun search(ctx: ServerContext, call: Call, container: String): Reply {
    val raw = (call.param("query") ?: "").trim().removeSurrounding("\"").trim()
    val words = fold(raw).split(' ').filter(String::isNotEmpty)
    fun clause(column: String): Pair<String, List<Any?>> =
        if (words.isEmpty()) "1=1" to emptyList()
        else words.joinToString(" AND ") { "$column LIKE ? ESCAPE '\\'" } to words.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
    val userId = call.user.id
    val (artistWhere, artistArgs) = clause("ar.search")
    val (albumWhere, albumArgs) = clause("al.search")
    val (songWhere, songArgs) = clause("t.search")
    val artistCount = call.size("artistCount", 20)
    val albumCount = call.size("albumCount", 20)
    val songCount = call.size("songCount", 20)
    val artists = if (artistCount == 0) emptyList() else ctx.catalog.artists(userId, "ar.album_count > 0 AND $artistWhere", artistArgs, limit = artistCount, offset = call.int("artistOffset", 0))
    val albums = if (albumCount == 0) emptyList() else ctx.catalog.albums(userId, albumWhere, albumArgs, limit = albumCount, offset = call.int("albumOffset", 0))
    val songs = if (songCount == 0) emptyList() else ctx.catalog.songs(userId, songWhere, songArgs, order = "t.title COLLATE NOCASE, t.id", limit = songCount, offset = call.int("songOffset", 0))
    return ok {
        child(container) {
            artists.forEach { add(it) }
            albums.forEach { add(it) }
            songs.forEach { add(it) }
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
internal fun launchBackground(block: () -> Unit) {
    GlobalScope.launch(Dispatchers.IO) { block() }
}
