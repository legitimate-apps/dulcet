package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.fold

/**
 * Ranks one artist's songs for `getTopSongs`, whose first rows are what an artist's Play button
 * plays first. The server has no external popularity source, so the order uses only signals it
 * holds, strongest first:
 *
 * 1. **Listening on this server.** Score = 3 per play by the caller, plus every play by anyone
 *    else; a star is worth 20 to the caller and 10 to each other listener; a rating of 4 or 5 adds
 *    4 or 8. Only the sum is used, so another account's activity is never identified.
 * 2. **A spread across records for the rest.** Songs nobody has played go round the artist's
 *    records, one song from each per round, newest record first, each record in track order. So an
 *    artist nobody has listened to still opens with one song from each of their records, never
 *    with the first title alphabetically.
 *
 * Also: the same title on several records counts once (its listening adds up); records the artist
 * is credited on but does not own (compilations, features) follow their own records; clips under
 * [SHORT_SECONDS] (intros, skits) follow the full songs; a song the caller rated one or two stars
 * comes last.
 */
object TopSongs {
    const val SHORT_SECONDS = 75.0

    class Candidate(
        val id: String,
        val title: String,
        val albumId: String,
        val albumName: String,
        val albumYear: Int?,
        val albumSongCount: Int,
        val ownAlbum: Boolean,
        val disc: Int,
        val track: Int,
        val seconds: Double,
        val ownPlays: Int,
        val ownStarred: Boolean,
        val ownRating: Int,
        val otherPlays: Long,
        val otherStars: Int,
        val otherRatingBonus: Long,
    ) {
        val score: Long get() = 3L * ownPlays + (if (ownStarred) 20 else 0) + ratingBonus(ownRating) + otherPlays + 10L * otherStars + otherRatingBonus
        val vetoed: Boolean get() = ownRating in 1..2
        val short: Boolean get() = seconds < SHORT_SECONDS
    }

    /** Applied per listener, in SQL for other listeners and here for the caller. */
    fun ratingBonus(rating: Int): Long = if (rating >= 4) 4L * (rating - 3) else 0L

    /** Candidate ids in the order the artist's Play button should use. */
    fun rank(candidates: List<Candidate>): List<String> {
        val records = candidates.groupBy { it.albumId }.values
            .sortedWith(
                compareByDescending<List<Candidate>> { it.first().albumYear ?: Int.MIN_VALUE }
                    .thenByDescending { it.first().albumSongCount }
                    .thenBy { it.first().albumName.lowercase() }.thenBy { it.first().albumId },
            ).mapIndexed { rank, songs -> songs.first().albumId to rank }.toMap()
        val inRecord = compareBy<Candidate>({ records.getValue(it.albumId) }, { it.disc }, { it.track }, { it.id })

        // One entry per title: the best-listened version, ties to the better-placed record.
        val best = candidates.groupBy { fold(it.title) }.values.map { versions ->
            val lead = versions.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.ownAlbum }.then(inRecord)).first()
            lead to versions.sumOf { it.score }
        }

        val heard = best.filter { (song, score) -> score > 0 && !song.vetoed }
            .sortedWith(compareByDescending<Pair<Candidate, Long>> { it.second }.thenByDescending { it.first.ownAlbum }.thenComparator { a, b -> inRecord.compare(a.first, b.first) })
            .map { it.first }
        val heardIds = heard.mapTo(HashSet()) { it.id }

        val unheard = best.map { it.first }.filter { it.id !in heardIds && !it.vetoed }
        // Round number within a record counts only the full songs, so a skit on track 1 does not
        // push the record's real opener to round two.
        val round = HashMap<String, Int>()
        unheard.groupBy { it.albumId }.values.forEach { songs ->
            songs.filter { !it.short }.sortedWith(inRecord).forEachIndexed { i, song -> round[song.id] = i }
        }
        val spread = unheard.sortedWith(
            compareBy<Candidate>({ !it.ownAlbum }, { it.short }, { round[it.id] ?: Int.MAX_VALUE }).then(inRecord),
        )
        val vetoed = best.map { it.first }.filter { it.vetoed }.sortedWith(inRecord)
        return (heard + spread + vetoed).map { it.id }
    }
}
