package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.time.Duration.Companion.milliseconds

/**
 * The local half of §18.1 over the MIRROR's committed generation. The shells still call this until
 * they move to the reader (R2a, R3); it is deleted with the mirror (§16.18 R5). The reader's local
 * half is [SeenCacheSearch], below.
 */
public class LocalLibrarySearch internal constructor(private val store: DulcetDatabaseStore) {
    public fun search(providerInstanceId: String, query: String): List<SearchResultItem> {
        val normalized = normalizeSearchText(query.trim())
        if (normalized.isEmpty()) return emptyList()
        return store.database.transactionWithResult {
            val generation = store.metadata().committedGeneration
            val queries = store.database.libraryQueries
            fun credits(kind: String, rawId: String): List<Credit> =
                queries.selectSearchCredits(providerInstanceId, kind, rawId, generation) { role, name, id ->
                    Credit(if (role == "artist") CreditRole.Artist else CreditRole.AlbumArtist,
                        name, id?.let { ProviderItemId(providerInstanceId, it) })
                }.executeAsList()
            val artists = queries.searchArtistsAtGeneration(providerInstanceId, generation, normalized) { id, name, media ->
                SearchResultItem(ProviderItemId(providerInstanceId, id), SearchResultType.Artist,
                    name, emptyList(), null, null, null, null, null, null, media, null)
            }.executeAsList()
            val albums = queries.searchAlbumsAtGeneration(providerInstanceId, generation, normalized) {
                    id, title, _, _, year, duration, media, artwork ->
                SearchResultItem(ProviderItemId(providerInstanceId, id), SearchResultType.Album,
                    title, credits("album", id), null, year?.toInt(), duration.milliseconds,
                    null, null, null, media, artwork)
            }.executeAsList()
            val tracks = queries.searchTracksAtGeneration(providerInstanceId, generation, normalized) {
                    id, _, title, _, _, album, disc, track, duration, container, media, artwork ->
                SearchResultItem(ProviderItemId(providerInstanceId, id), SearchResultType.Track,
                    title, credits("track", id), album, null, duration.milliseconds,
                    disc?.toInt(), track?.toInt(), container?.let(::audioContainerFromWireName), media, artwork)
            }.executeAsList()
            rankResults(query.trim(), artists + albums + tracks)
        }
    }
}

/** SQLite cannot NFKD/case-fold text. Run once for each unindexed row, including old generations. */
internal fun backfillSearchIndex(database: DulcetDatabase) = database.transaction {
    val queries = database.libraryQueries
    queries.selectArtistSearchBackfill().executeAsList().forEach {
        queries.updateArtistSearchIndex(normalizeSearchText(it.name), it.server_id, it.raw_id, it.valid_from_generation)
    }
    queries.selectAlbumSearchBackfill().executeAsList().forEach {
        queries.updateAlbumSearchIndex(normalizeSearchText(it.title), it.server_id, it.raw_id, it.valid_from_generation)
    }
    queries.selectTrackSearchBackfill().executeAsList().forEach {
        queries.updateTrackSearchIndex(normalizeSearchText(it.title), normalizeSearchText(it.album_title.orEmpty()),
            it.server_id, it.raw_id, it.valid_from_generation)
    }
    queries.selectCreditSearchBackfill().executeAsList().forEach {
        queries.updateCreditSearchIndex(normalizeSearchText(it.name), it.server_id, it.seen_key, it.valid_from_generation)
    }
}

/**
 * The local half of §16.15: the seen-cache, searched with the same normalization and the same
 * ranker as the server's results. No wire request. `gone` rows are never returned (the queries
 * exclude them), and neither are identity-only tracks, which have no title to match.
 */
internal class SeenCacheSearch(private val cache: BoundSeenCache) {
    fun search(query: String): List<SearchResultItem> {
        val trimmed = query.trim()
        val normalized = normalizeSearchText(trimmed)
        if (normalized.isEmpty()) return emptyList()
        val provider = cache.serverId
        val artists = cache.searchArtists(normalized).map { it.record.toSearchResult(provider) }
        val albums = cache.searchAlbums(normalized).map { it.record.toSearchResult(provider) }
        val tracks = cache.searchTracks(normalized).mapNotNull { it.record?.toSearchResult(provider) }
        return rankResultsStably(trimmed, artists + albums + tracks)
    }

    /** What this device has seen, for the offline label: "1,204 albums and 9,870 tracks". */
    fun counts(): SeenCacheCounts = cache.counts()
}

// One mapping from a cached record to a search row, used for BOTH halves: a server row and the
// local row it replaces are built by the same code from the same fields, so replacing one with the
// other changes a row's content only where the server's data actually differs.

internal fun CacheArtistRecord.toSearchResult(providerInstanceId: String) = SearchResultItem(
    id = ProviderItemId(providerInstanceId, rawId),
    type = SearchResultType.Artist,
    title = name,
    credits = emptyList(),
    albumTitle = null,
    year = null,
    duration = null,
    discNumber = null,
    trackNumber = null,
    sourceContainer = null,
    mediaSourceId = null,
    artworkKey = artworkKey,
)

internal fun CacheAlbumRecord.toSearchResult(providerInstanceId: String) = SearchResultItem(
    id = ProviderItemId(providerInstanceId, rawId),
    type = SearchResultType.Album,
    title = title,
    credits = credits.toSearchCredits(providerInstanceId),
    albumTitle = null,
    year = year,
    duration = durationMilliseconds?.milliseconds,
    discNumber = null,
    trackNumber = null,
    sourceContainer = null,
    mediaSourceId = null,
    artworkKey = artworkKey,
)

internal fun CacheTrackRecord.toSearchResult(providerInstanceId: String) = SearchResultItem(
    id = ProviderItemId(providerInstanceId, rawId),
    type = SearchResultType.Track,
    title = title,
    credits = credits.toSearchCredits(providerInstanceId),
    albumTitle = albumTitle,
    year = null,
    duration = durationMilliseconds?.milliseconds,
    discNumber = discNumber,
    trackNumber = trackNumber,
    sourceContainer = sourceContainer,
    mediaSourceId = null,
    artworkKey = artworkKey,
)

private fun List<CacheCredit>.toSearchCredits(providerInstanceId: String): List<Credit> = map { credit ->
    Credit(credit.role, credit.name, credit.artistRawId?.let { ProviderItemId(providerInstanceId, it) })
}
