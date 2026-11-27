package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.discover.PopularRecording
import com.legitimateapps.dulcet.server.discover.SongTitles
import com.legitimateapps.dulcet.server.discover.mbidOrNull
import com.legitimateapps.dulcet.server.fold

/**
 * Ranks one artist's songs for `getTopSongs`, whose first rows are what an artist's Play button
 * plays first, strongest signal first:
 *
 * 1. **The artist's popular songs and the caller's own listening, together.** A song among the
 *    artist's most-listened recordings worldwide (an outside source such as ListenBrainz, matched
 *    to this library by recording MBID, then by title) scores [POPULAR_TOP] for the most popular
 *    one held here, two less for each place down, at least 1. The caller's own listening adds 3
 *    per play, 20 for a star, and 4 or 8 for a rating of 4 or 5, so heavy personal listening can
 *    lift a song past the hits, as Music personalises. Other accounts' listening is never used: in
 *    a small household any aggregate of it would let one listener infer another's.
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
    const val POPULAR_TOP = 40L

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
        val recordingMbid: String? = null,
        /** Place among the artist's popular songs held here, 0 for the most popular; null if not among them. */
        val popularity: Int? = null,
    ) {
        val ownScore: Long get() = 3L * ownPlays + (if (ownStarred) 20 else 0) + ratingBonus(ownRating)
        val score: Long get() = ownScore + popularityPoints(popularity)
        val vetoed: Boolean get() = ownRating in 1..2
        val short: Boolean get() = seconds < SHORT_SECONDS
    }

    fun ratingBonus(rating: Int): Long = if (rating >= 4) 4L * (rating - 3) else 0L
    fun popularityPoints(place: Int?): Long = if (place == null) 0L else maxOf(1L, POPULAR_TOP - 2L * place)

    /**
     * Each candidate's place among [popular] (most popular first), counting only recordings this
     * library holds, so the best-known song held here is place 0. A recording matches the tracks
     * tagged with its MBID; failing that, tracks of the same title and version (live, remix, demo,
     * acoustic, instrumental). A recording with no track here is skipped.
     */
    fun popularityPlaces(candidates: List<Candidate>, popular: List<PopularRecording>): Map<String, Int> {
        val places = HashMap<String, Int>()
        var place = 0
        for (recording in popular) {
            val wanted = recording.recordingMbid?.let(::mbidOrNull)
            val open = candidates.filter { it.id !in places }
            val byMbid = if (wanted == null) emptyList() else open.filter { mbidOrNull(it.recordingMbid) == wanted && SongTitles.sameVersion(it.title, recording.title) }
            val name = SongTitles.normalizeName(recording.title)
            val matched = byMbid.ifEmpty { open.filter { name.isNotEmpty() && SongTitles.normalizeName(it.title) == name && SongTitles.sameVersion(it.title, recording.title) } }
            if (matched.isEmpty()) continue
            matched.forEach { places[it.id] = place }
            place++
        }
        return places
    }

    /** A held song as search sees it: enough to match it to an outside popularity answer. */
    class SongRef(val id: String, val title: String, val recordingMbid: String?)

    /**
     * Worldwide listens for each of [songs] (one artist's), from that artist's [popular] recordings: a recording matches
     * the songs tagged with its MBID, failing that the songs of the same title and version (the rule of
     * [popularityPlaces]). A song several recordings could match takes the best-listened one, and a song none matches is
     * left out, so "no figure" is never confused with "few listens". Search uses this to lead with the version of a title
     * people actually play: the original has a figure, a live session or a cover does not.
     */
    fun listensFor(songs: List<SongRef>, popular: List<PopularRecording>): Map<String, Long> {
        val listens = HashMap<String, Long>()
        for (recording in popular.sortedByDescending { it.listens }) {
            val wanted = recording.recordingMbid?.let(::mbidOrNull)
            val open = songs.filter { it.id !in listens }
            val byMbid = if (wanted == null) emptyList() else open.filter { mbidOrNull(it.recordingMbid) == wanted && SongTitles.sameVersion(it.title, recording.title) }
            val name = SongTitles.normalizeName(recording.title)
            val matched = byMbid.ifEmpty { open.filter { name.isNotEmpty() && SongTitles.normalizeName(it.title) == name && SongTitles.sameVersion(it.title, recording.title) } }
            matched.forEach { listens[it.id] = recording.listens }
        }
        return listens
    }

    fun withPopularity(candidates: List<Candidate>, popular: List<PopularRecording>?): List<Candidate> {
        if (popular.isNullOrEmpty()) return candidates
        val places = popularityPlaces(candidates, popular)
        return candidates.map { c -> places[c.id]?.let { c.copyWithPopularity(it) } ?: c }
    }

    private fun Candidate.copyWithPopularity(place: Int) = Candidate(
        id, title, albumId, albumName, albumYear, albumSongCount, ownAlbum, disc, track, seconds, ownPlays, ownStarred, ownRating, recordingMbid, place,
    )

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
            // Own listening adds up over versions; popularity counts once, the best version's.
            lead to versions.sumOf { it.ownScore } + versions.maxOf { popularityPoints(it.popularity) }
        }

        val heard = best.filter { (song, score) -> score > 0 && !song.vetoed }
            .sortedWith(compareByDescending<Pair<Candidate, Long>> { it.second }.thenBy { it.first.popularity ?: Int.MAX_VALUE }.thenByDescending { it.first.ownAlbum }.thenComparator { a, b -> inRecord.compare(a.first, b.first) })
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
