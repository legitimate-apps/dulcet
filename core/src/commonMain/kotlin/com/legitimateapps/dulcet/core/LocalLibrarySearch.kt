package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.time.Duration.Companion.milliseconds

/** Shared local half of §18.1. No wire request and no dependency on previous query strings. */
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
