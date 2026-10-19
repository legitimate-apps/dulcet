package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.ServerContext

/** Local neighbours only: alternate artist matches and other artists sharing a seed genre. */
internal fun similarSongs(ctx: ServerContext, call: Call, container: String): Reply {
    val id = call.required("id")
    val count = call.size("count", 50)
    // Seed resolution, metadata and candidates share one catalog/policy snapshot. In particular,
    // a visible album or artist must not inherit genres or credits from its hidden tracks.
    val songs = ctx.db.read {
        val kind = ctx.catalog.kindOf(id) ?: throw SubsonicError.notFound()
        if (!ctx.access.canReadCatalog(call.user.id, kind, id)) throw SubsonicError.notFound()
        if (count == 0) return@read emptyList<Node>()

        val seedWhere = when (kind) {
            "song" -> "t.id = ?"
            "album" -> "t.album_id = ?"
            else -> "t.id IN (SELECT track_id FROM track_artist WHERE artist_id = ? AND role IN ('artist', 'albumartist'))"
        }
        // Artist ids name exactly that artist, rather than all of their collaborators. Song and
        // album ids use all performing and album-artist credits (never composer-only credits).
        val seedArtists = if (kind == "artist") "SELECT ? AS artist_id" else """
            SELECT DISTINCT artist_id FROM track_artist
            WHERE track_id IN (SELECT id FROM seed_tracks) AND role IN ('artist', 'albumartist')
        """.trimIndent()
        val seedArgs = if (kind == "artist") listOf(id, id) else listOf(id)
        val seeds = """
            WITH seed_tracks AS (
                SELECT t.id, t.album_id FROM track t
                WHERE ($seedWhere) AND ${ctx.access.catalogPredicate(call.user.id, "track", "t")}
            ), seed_artists AS ($seedArtists), seed_genres AS (
                SELECT DISTINCT genre FROM track_genre WHERE track_id IN (SELECT id FROM seed_tracks)
            )
        """.trimIndent()
        val sameArtist = """
            EXISTS (SELECT 1 FROM track_artist credit WHERE credit.track_id = t.id
                AND credit.role IN ('artist', 'albumartist')
                AND credit.artist_id IN (SELECT artist_id FROM seed_artists))
        """.trimIndent()
        val otherAlbum = if (kind == "artist") "1=1" else "t.album_id NOT IN (SELECT album_id FROM seed_tracks)"
        val notSeed = if (kind == "artist") "1=1" else "t.id NOT IN (SELECT id FROM seed_tracks)"
        fun candidates(condition: String) = ctx.catalog.songs(
            call.user.id,
            "t.id IN ($seeds SELECT t.id FROM track t WHERE $condition)",
            seedArgs, order = "RANDOM()", limit = count,
        )
        val artist = candidates("($sameArtist) AND $otherAlbum AND $notSeed")
        val genre = candidates("""
            NOT ($sameArtist) AND $notSeed AND EXISTS (
                SELECT 1 FROM track_genre g WHERE g.track_id = t.id
                    AND g.genre COLLATE NOCASE IN (SELECT genre FROM seed_genres)
            )
        """.trimIndent())
        // The buckets are disjoint. Take enough from each to fill the request if the other runs
        // short; requests of at least two songs contain both kinds when both buckets have results.
        buildList {
            for (index in 0 until maxOf(artist.size, genre.size)) {
                artist.getOrNull(index)?.let { add(it) }
                genre.getOrNull(index)?.let { add(it) }
            }
        }.take(count)
    }
    return ok { child(container) { songs.forEach { add(it) } } }
}
