package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.Ids
import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.db.Db

/** Per-user state: stars, ratings, plays, now playing, playlists and the `dulcetLibrary` extension. */
fun userDataHandlers(ctx: ServerContext): Map<String, Handler> {
    val db = ctx.db
    val catalog = ctx.catalog

    /** (type, id) targets of star/unstar/library calls: `id` is resolved; `albumId`/`artistId` are typed. */
    fun targets(call: Call): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        for (id in call.params("id").filter(String::isNotEmpty)) {
            result.add((catalog.kindOf(id) ?: throw SubsonicError.notFound("Item")) to id)
        }
        for (id in call.params("albumId").filter(String::isNotEmpty)) {
            if (catalog.kindOf(id) != "album") throw SubsonicError.notFound("Album")
            result.add("album" to id)
        }
        for (id in call.params("artistId").filter(String::isNotEmpty)) {
            if (catalog.kindOf(id) != "artist") throw SubsonicError.notFound("Artist")
            result.add("artist" to id)
        }
        if (result.isEmpty()) throw SubsonicError.missing("id")
        return result
    }

    fun star(call: Call, starred: Boolean): Reply {
        val items = targets(call)
        val now = System.currentTimeMillis()
        db.write {
            for ((type, id) in items) {
                ensureAnnotation(call.user.id, type, id)
                execute("UPDATE annotation SET starred = ? WHERE user_id = ? AND item_type = ? AND item_id = ?", if (starred) now else null, call.user.id, type, id)
            }
        }
        return ok()
    }

    return mapOf(
        "star" to { call -> star(call, true) },
        "unstar" to { call -> star(call, false) },
        "setRating" to { call ->
            val id = call.required("id")
            val rating = call.required("rating").toIntOrNull()?.takeIf { it in 0..5 } ?: throw SubsonicError(0, "Invalid rating")
            val type = catalog.kindOf(id) ?: throw SubsonicError.notFound("Item")
            db.write {
                ensureAnnotation(call.user.id, type, id)
                execute("UPDATE annotation SET rating = ? WHERE user_id = ? AND item_type = ? AND item_id = ?", rating, call.user.id, type, id)
            }
            ok()
        },
        "scrobble" to { call ->
            val ids = call.params("id").filter(String::isNotEmpty).ifEmpty { throw SubsonicError.missing("id") }
            val times = call.params("time").map { it.toLongOrNull() }
            val submission = call.bool("submission", true)
            val now = System.currentTimeMillis()
            val client = call.param("c") ?: ""
            db.write {
                ids.forEachIndexed { index, id ->
                    // An id this server does not hold is accepted and dropped, as other servers do.
                    val track = single("SELECT album_id, artist_id FROM track WHERE id = ?", id) { it.string("album_id") to it.string("artist_id") }
                        ?: return@forEachIndexed
                    if (submission) {
                        val at = times.getOrNull(index) ?: now
                        for ((type, itemId) in listOf("song" to id, "album" to track.first, "artist" to track.second)) {
                            ensureAnnotation(call.user.id, type, itemId)
                            execute(
                                "UPDATE annotation SET play_count = play_count + 1, played = MAX(COALESCE(played, 0), ?) WHERE user_id = ? AND item_type = ? AND item_id = ?",
                                at, call.user.id, type, itemId,
                            )
                        }
                    } else {
                        execute(
                            "INSERT INTO now_playing(user_id, client, track_id, at) VALUES (?,?,?,?) ON CONFLICT(user_id, client) DO UPDATE SET track_id = excluded.track_id, at = excluded.at",
                            call.user.id, client, id, now,
                        )
                    }
                }
            }
            ok()
        },
        "getPlaylists" to { call ->
            val owner = call.param("username")?.let { name ->
                if (!name.equals(call.user.name, true) && !call.user.isAdmin) throw SubsonicError.notAuthorized()
                ctx.users.find(name) ?: throw SubsonicError.notFound("User")
            }
            val rows = db.read {
                if (owner != null) playlistRows("p.owner_id = ?", owner.id)
                // An admin sees every user's playlists, read-only unless they own them.
                else if (call.user.isAdmin) playlistRows("1 = 1")
                else playlistRows("p.owner_id = ? OR p.public = 1", call.user.id)
            }
            ok { child("playlists") { rows.forEach { add(playlistNode(it, call.user.id)) } } }
        },
        "getPlaylist" to { call ->
            val id = call.required("id")
            val row = db.read { playlistRows("p.id = ?", id).firstOrNull() } ?: throw SubsonicError.notFound("Playlist")
            if (row.ownerId != call.user.id && !row.public && !call.user.isAdmin) throw SubsonicError.notFound("Playlist")
            ok { put(playlistWithEntries(ctx, call, row)) }
        },
        "createPlaylist" to { call ->
            val songIds = call.params("songId").filter(String::isNotEmpty)
            val existingId = call.param("playlistId")?.takeIf(String::isNotEmpty)
            val now = System.currentTimeMillis()
            val id = if (existingId != null) {
                val row = db.read { playlistRows("p.id = ?", existingId).firstOrNull() } ?: throw SubsonicError.notFound("Playlist")
                if (row.ownerId != call.user.id) throw SubsonicError.notAuthorized()
                db.write {
                    call.param("name")?.takeIf(String::isNotEmpty)?.let { execute("UPDATE playlist SET name = ? WHERE id = ?", it, existingId) }
                    // A replace with no songId leaves the entries alone.
                    if (songIds.isNotEmpty()) {
                        execute("DELETE FROM playlist_track WHERE playlist_id = ?", existingId)
                        songIds.forEachIndexed { index, songId -> execute("INSERT INTO playlist_track VALUES (?,?,?)", existingId, index, songId) }
                    }
                    execute("UPDATE playlist SET changed = ? WHERE id = ?", now, existingId)
                }
                existingId
            } else {
                val name = call.required("name")
                val newId = Ids.of("playlist", call.user.id, name, now.toString(), System.nanoTime().toString())
                db.write {
                    execute("INSERT INTO playlist(id, owner_id, name, comment, public, created, changed) VALUES (?,?,?,?,?,?,?)", newId, call.user.id, name, "", false, now, now)
                    songIds.forEachIndexed { index, songId -> execute("INSERT INTO playlist_track VALUES (?,?,?)", newId, index, songId) }
                }
                newId
            }
            val row = db.read { playlistRows("p.id = ?", id).first() }
            ok { put(playlistWithEntries(ctx, call, row)) }
        },
        "updatePlaylist" to { call ->
            val id = call.required("playlistId")
            val row = db.read { playlistRows("p.id = ?", id).firstOrNull() } ?: throw SubsonicError.notFound("Playlist")
            if (row.ownerId != call.user.id && !call.user.isAdmin) throw SubsonicError.notAuthorized()
            val add = call.params("songIdToAdd").filter(String::isNotEmpty)
            val remove = call.params("songIndexToRemove").mapNotNull { it.toIntOrNull() }.toSet()
            db.write {
                call.param("name")?.takeIf(String::isNotEmpty)?.let { execute("UPDATE playlist SET name = ? WHERE id = ?", it, id) }
                call.param("comment")?.let { execute("UPDATE playlist SET comment = ? WHERE id = ?", it, id) }
                call.param("public")?.let { execute("UPDATE playlist SET public = ? WHERE id = ?", it.equals("true", true), id) }
                if (add.isNotEmpty() || remove.isNotEmpty()) {
                    val current = query("SELECT track_id FROM playlist_track WHERE playlist_id = ? ORDER BY position", id) { it.string("track_id") }
                    val next = current.filterIndexed { index, _ -> index !in remove } + add
                    execute("DELETE FROM playlist_track WHERE playlist_id = ?", id)
                    next.forEachIndexed { index, songId -> execute("INSERT INTO playlist_track VALUES (?,?,?)", id, index, songId) }
                }
                execute("UPDATE playlist SET changed = ? WHERE id = ?", System.currentTimeMillis(), id)
            }
            ok()
        },
        "deletePlaylist" to { call ->
            val id = call.required("id")
            val row = db.read { playlistRows("p.id = ?", id).firstOrNull() } ?: throw SubsonicError.notFound("Playlist")
            if (row.ownerId != call.user.id && !call.user.isAdmin) throw SubsonicError.notAuthorized()
            db.write { execute("DELETE FROM playlist WHERE id = ?", id) }
            ok()
        },
        // ---- dulcetLibrary v1 (docs/dulcet-server/dulcet-library-extension.md) ----
        "getDulcetLibrary" to { call ->
            val type = call.param("type")
            if (type != null && type !in setOf("artist", "album", "song")) throw SubsonicError(0, "Invalid type: $type")
            val size = call.size("size", 500, max = 5000)
            val offset = call.int("offset", 0)
            val userId = call.user.id
            val since = call.long("since")
            val sinceClause = if (since != null) " AND li.added > ?" else ""
            val sinceArgs = listOfNotNull(since)
            val counts = db.read {
                query("SELECT item_type, COUNT(*) AS n FROM library_item WHERE user_id = ? GROUP BY item_type", userId) { it.string("item_type") to it.int("n") }.toMap()
            }
            ok {
                child("dulcetLibrary") {
                    this["artistCount"] = counts["artist"] ?: 0
                    this["albumCount"] = counts["album"] ?: 0
                    this["songCount"] = counts["song"] ?: 0
                    db.read { string("SELECT value FROM meta WHERE key = ?", "library_changed:$userId") }?.let { this["changed"] = Catalog.iso(it.toLong()) }
                    if (type == null || type == "artist") catalog.artists(userId, "li.added IS NOT NULL$sinceClause", sinceArgs, order = "li.added DESC, ar.id", limit = size, offset = offset).forEach { add(it) }
                    if (type == null || type == "album") catalog.albums(userId, "li.added IS NOT NULL$sinceClause", sinceArgs, order = "li.added DESC, al.id", limit = size, offset = offset).forEach { add(it) }
                    if (type == null || type == "song") catalog.songs(userId, "li.added IS NOT NULL$sinceClause", sinceArgs, order = "li.added DESC, t.id", limit = size, offset = offset).forEach { add(it) }
                }
            }
        },
        "addToDulcetLibrary" to { call ->
            val items = targets(call)
            val now = System.currentTimeMillis()
            db.write {
                items.forEach { (type, id) -> execute("INSERT OR IGNORE INTO library_item(user_id, item_type, item_id, added) VALUES (?,?,?,?)", call.user.id, type, id, now) }
                markLibraryChanged(call.user.id, now)
            }
            ok()
        },
        "removeFromDulcetLibrary" to { call ->
            // Removal accepts ids the catalog no longer has, so a member whose file vanished can still go.
            val items = call.params("id").map { (catalog.kindOf(it) ?: "") to it } +
                call.params("albumId").map { "album" to it } + call.params("artistId").map { "artist" to it }
            if (items.isEmpty()) throw SubsonicError.missing("id")
            db.write {
                items.forEach { (type, id) ->
                    if (type.isEmpty()) execute("DELETE FROM library_item WHERE user_id = ? AND item_id = ?", call.user.id, id)
                    else execute("DELETE FROM library_item WHERE user_id = ? AND item_type = ? AND item_id = ?", call.user.id, type, id)
                }
                markLibraryChanged(call.user.id, System.currentTimeMillis())
            }
            ok()
        },
    )
}

internal fun Db.Session.markLibraryChanged(userId: String, now: Long) {
    execute("INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = CAST(MAX(CAST(value AS INTEGER) + 1, CAST(excluded.value AS INTEGER)) AS TEXT)", "library_changed:$userId", now.toString())
}

internal fun Db.Session.ensureAnnotation(userId: String, type: String, id: String) {
    execute("INSERT OR IGNORE INTO annotation(user_id, item_type, item_id) VALUES (?,?,?)", userId, type, id)
}

data class PlaylistRow(
    val id: String, val ownerId: String, val owner: String, val name: String, val comment: String?, val public: Boolean,
    val created: Long, val changed: Long, val songCount: Int, val duration: Double,
)

private fun Db.Session.playlistRows(where: String, vararg args: Any?): List<PlaylistRow> = query(
    """SELECT p.*, u.name AS owner_name,
         (SELECT COUNT(*) FROM playlist_track pt JOIN track t ON t.id = pt.track_id WHERE pt.playlist_id = p.id) AS song_count,
         (SELECT COALESCE(SUM(t.duration), 0) FROM playlist_track pt JOIN track t ON t.id = pt.track_id WHERE pt.playlist_id = p.id) AS total_duration
       FROM playlist p JOIN user u ON u.id = p.owner_id WHERE $where ORDER BY p.name COLLATE NOCASE, p.id""",
    *args,
) {
    PlaylistRow(
        it.string("id"), it.string("owner_id"), it.string("owner_name"), it.string("name"), it.stringOrNull("comment"), it.bool("public"),
        it.long("created"), it.long("changed"), it.int("song_count"), it.double("total_duration"),
    )
}

private fun playlistNode(row: PlaylistRow, viewerId: String): Node = Node("playlist").apply {
    this["id"] = row.id
    this["name"] = row.name
    row.comment?.takeIf(String::isNotEmpty)?.let { this["comment"] = it }
    this["songCount"] = row.songCount
    this["duration"] = row.duration.toInt()
    this["public"] = row.public
    this["owner"] = row.owner
    this["created"] = Catalog.iso(row.created)
    this["changed"] = Catalog.iso(row.changed)
    this["coverArt"] = "pl-${row.id}"
    this["readonly"] = row.ownerId != viewerId
}

private fun playlistWithEntries(ctx: ServerContext, call: Call, row: PlaylistRow): Node {
    val ids = ctx.db.read { query("SELECT track_id FROM playlist_track WHERE playlist_id = ? ORDER BY position", row.id) { it.string("track_id") } }
    return playlistNode(row, call.user.id).apply { ctx.catalog.songsById(call.user.id, ids, "entry").forEach { add(it) } }
}
