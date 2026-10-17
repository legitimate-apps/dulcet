package com.legitimateapps.dulcet.server

/** Bounded, explainable recommendations over this user's imported and live annotations. */
class HomeRecommendations(private val ctx: ServerContext) {
    data class Shelf(val id: String, val title: String, val reason: String, val type: String, val ids: List<String>)

    fun shelves(user: String, limit: Int = 20, now: Long = System.currentTimeMillis(), only: String? = null): List<Shelf> {
        require(limit in 1..50)
        val day = now / 86_400_000L
        val visible = ctx.access.catalogPredicate(user, "track", "t")
        // MAX avoids counting the same scrobble both on its album and its songs. Imported song-only
        // history still contributes, including its original last-played timestamp.
        val albums = """SELECT al.id, al.created, al.rowid AS rank_seed,
            MAX(COALESCE(a.play_count,0), COALESCE(SUM(s.play_count),0)) AS plays,
            MAX(COALESCE(a.played,0), COALESCE(MAX(s.played),0)) AS played,
            MAX(COALESCE(a.rating,0), COALESCE(MAX(s.rating),0)) AS rating,
            MAX(COALESCE(a.starred,0), COALESCE(MAX(s.starred),0)) AS starred
            FROM album al JOIN track t ON t.album_id=al.id
            LEFT JOIN annotation a ON a.user_id=? AND a.item_type='album' AND a.item_id=al.id
            LEFT JOIN annotation s ON s.user_id=? AND s.item_type='song' AND s.item_id=t.id
            WHERE $visible GROUP BY al.id"""
        fun albumIds(where: String, order: String, vararg args: Any?) = ctx.db.read {
            query("SELECT id FROM ($albums) WHERE $where ORDER BY $order, id LIMIT ?", user, user, *args, limit) { it.string("id") }
        }
        val affinity = """(COALESCE(a.play_count,0) + 4*MAX(COALESCE(a.rating,0), COALESCE(aa.rating,0), COALESCE(ar.rating,0)) +
            CASE WHEN a.starred IS NOT NULL OR aa.starred IS NOT NULL OR ar.starred IS NOT NULL THEN 20 ELSE 0 END)"""
        val signals = """LEFT JOIN annotation a ON a.user_id=a_user.id AND a.item_type='song' AND a.item_id=t.id
            LEFT JOIN annotation aa ON aa.user_id=a_user.id AND aa.item_type='album' AND aa.item_id=t.album_id
            LEFT JOIN annotation ar ON ar.user_id=a_user.id AND ar.item_type='artist' AND ar.item_id=t.artist_id"""
        // Bind the user once for every annotation join, including album/artist-only favourites.
        val trackSource = "track t JOIN user a_user ON a_user.id=? $signals"
        fun tracks(where: String, order: String, vararg args: Any?) = ctx.db.read {
            query("SELECT t.id FROM $trackSource WHERE ($where) AND $visible ORDER BY $order, t.id LIMIT ?", user, *args, limit) { it.string("id") }
        }
        // A deterministic rotation changes daily without trusting a random candidate order or
        // manufacturing affinity for an account that has never listened or liked anything.
        val rotate = "((t.rowid + $day) % (SELECT MAX(rowid) FROM track))"
        val topArtist by lazy { ctx.db.read {
            string("SELECT t.artist_id FROM $trackSource WHERE $visible AND $affinity>0 GROUP BY t.artist_id ORDER BY SUM($affinity) DESC,t.artist_id LIMIT 1", user)
        } }
        val topGenre by lazy { ctx.db.read {
            string("SELECT g.genre FROM $trackSource JOIN track_genre g ON g.track_id=t.id WHERE $visible AND $affinity>0 GROUP BY g.genre ORDER BY SUM($affinity) DESC,g.genre LIMIT 1", user)
        } }
        return buildList {
            fun pick(id: String, title: String, reason: String, type: String, ids: () -> List<String>) {
                if (only == null || only == id) add(Shelf(id, title, reason, type, ids()))
            }
            pick("recent", "Recently Played", "Albums you played most recently", "album") { albumIds("played>0", "played DESC") }
            pick("recentPlaylists", "Recently Played Playlists", "Playlists named in your listening history", "playlist") {
                ctx.db.read {
                    query("""SELECT p.id FROM api_play h JOIN playlist p ON p.id=json_extract(h.payload,'$.context.id')
                        WHERE h.user_id=? AND h.payload IS NOT NULL AND json_extract(h.payload,'$.context.type')='playlist'
                        AND (p.owner_id=? OR p.public=1)
                        AND EXISTS (SELECT 1 FROM playlist_track pt JOIN track t ON t.id=pt.track_id WHERE pt.playlist_id=p.id AND $visible)
                        GROUP BY p.id ORDER BY MAX(h.started_at) DESC,p.id LIMIT ?""", user,user,limit) { it.string("id") }
                }
            }
            pick("frequent", "Heavy Rotation", "Albums with the most plays", "album") { albumIds("plays>0", "plays DESC, played DESC") }
            pick("newest", "New on Your Server", "Recently added to your server", "album") { albumIds("1=1", "created DESC") }
            pick("rediscover", "Rediscover", "Loved, highly rated or played often; unheard for 30 days", "album") {
                albumIds("(starred>0 OR rating>=4 OR plays>=3) AND played<?", "played ASC, plays DESC", now-30L*86_400_000)
            }
            pick("dailyMix", "Daily Mix", "A daily rotation of your plays, stars and ratings", "track") { tracks("$affinity>0", "$rotate, $affinity DESC") }
            pick("favourites", "Favourites Mix", "Tracks you starred or rated four stars and above", "track") { tracks("a.starred IS NOT NULL OR aa.starred IS NOT NULL OR ar.starred IS NOT NULL OR MAX(COALESCE(a.rating,0),COALESCE(aa.rating,0),COALESCE(ar.rating,0))>=4", "$rotate") }
            pick("artistStation", "From Your Artists", "More from an artist you play, star or rate", "track") {
                topArtist?.let { tracks("t.artist_id=?", "$rotate", it) } ?: emptyList()
            }
            pick("genreStation", "From Your Genres", "More from a genre you play, star or rate", "track") {
                topGenre?.let { tracks("t.id IN (SELECT track_id FROM track_genre WHERE genre=?)", "$rotate", it) } ?: emptyList()
            }
            pick("random", "Explore Your Server", "A selection from your server", "album") { albumIds("1=1", "((rank_seed + $day) % (SELECT MAX(rowid) FROM album))") }
        }
    }
}
