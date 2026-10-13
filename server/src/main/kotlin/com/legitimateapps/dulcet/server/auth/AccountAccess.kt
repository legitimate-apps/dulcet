package com.legitimateapps.dulcet.server.auth

import com.legitimateapps.dulcet.server.db.Db
import kotlinx.serialization.json.*

/** Live account policy for both HTTP protocols. A protocol's scopes never widen this policy. */
class AccountAccess(private val db: Db) {
    fun sourceIds(userId: String): List<String> = db.read {
        val configured = string("SELECT source_ids FROM api_user_access WHERE user_id=?", userId)
        if (configured == null) query("SELECT CAST(id AS TEXT) AS id FROM music_folder ORDER BY id") { it.string("id") }
        else Json.parseToJsonElement(configured).jsonArray.map { it.jsonPrimitive.content }
    }

    fun permissions(userId: String): JsonObject = db.read {
        val configured = string("SELECT permissions FROM api_user_access WHERE user_id=?", userId)
            ?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        JsonObject(mapOf("download" to JsonPrimitive(true), "share" to JsonPrimitive(true)) + configured)
    }
    fun download(userId: String): Boolean = permissions(userId)["download"]!!.jsonPrimitive.boolean
    fun share(userId: String): Boolean = permissions(userId)["share"]!!.jsonPrimitive.boolean
    fun canReadSource(userId: String, sourceId: String): Boolean = sourceId in sourceIds(userId)

    /** SQL for filtering before pagination. Aliases and types come only from server code. */
    fun catalogPredicate(userId: String, type: String, alias: String): String {
        val sources = sourceIds(userId).joinToString(",") { "'${it.replace("'", "''")}'" }.ifEmpty { "NULL" }
        fun tracks(condition: String) = "EXISTS(SELECT 1 FROM track visible_track WHERE ($condition) AND CAST(visible_track.folder_id AS TEXT) IN ($sources))"
        return when(type) {
            "song", "track" -> "CAST($alias.folder_id AS TEXT) IN ($sources)"
            "album" -> tracks("visible_track.album_id=$alias.id")
            "artist" -> tracks("visible_track.id IN (SELECT track_id FROM track_artist WHERE artist_id=$alias.id) OR visible_track.album_id IN (SELECT album_id FROM album_artist WHERE artist_id=$alias.id)")
            "source" -> "CAST($alias.id AS TEXT) IN ($sources)"
            "genre" -> tracks("visible_track.id IN (SELECT track_id FROM track_genre WHERE genre=$alias.key)")
            else -> "0"
        }
    }

    fun canReadCatalog(userId: String, type: String, id: String): Boolean = db.read {
        val table = when(type) { "song", "track" -> "track"; "album", "artist" -> type; "source" -> "music_folder"; "genre" -> "api_group_identity"; else -> return@read false }
        long("SELECT COUNT(*) FROM $table item WHERE item.id=? AND ${catalogPredicate(userId,type,"item")}", id) > 0
    }

    fun canReadArtwork(user: User, id: String): Boolean = db.read {
        val typed = listOf("al-" to "album", "mf-" to "track", "ar-" to "artist", "pl-" to "playlist").firstOrNull { id.startsWith(it.first) }
        val bare = typed?.let { id.removePrefix(it.first) } ?: id
        val type = typed?.second ?: listOf("album", "track", "artist", "playlist").firstOrNull { long("SELECT COUNT(*) FROM $it WHERE id=?", bare)>0 } ?: return@read false
        if(type=="playlist") long("SELECT COUNT(*) FROM playlist WHERE id=? AND (owner_id=? OR public=1 OR ?=1)", bare,user.id,user.isAdmin)>0
        else canReadCatalog(user.id,type,bare)
    }
}
