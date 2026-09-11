package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What one library open costs the person's server, decomposed by the component that issued each
 * request and by the walk it belonged to.
 *
 * 🚨 **Counted per endpoint, per component, and per WALK -- never as a total.** A total is
 * ambiguous by construction: the same figure is produced by one preview plus a two-pass sync and
 * by three previews, and those are different defects with different fixes. Under the previous
 * transport the discriminator was `getAlbum` (4,996 against 2,498 albums is one sync; three would
 * have been 14,988). That transport is gone -- #125 replaced the per-album fill with three
 * whole-library `search3` walks -- so the discriminator is now the thing it was always standing in
 * for: [CountingSyncSource.walkStarts], which counts how many times each walk was begun from
 * offset 0. It does not need a corpus to be unambiguous.
 *
 * A library open starts exactly ONE `browse`, and `browse` runs two things at once:
 *
 *  - the PREVIEW ([LibraryBrowser.browse]) -- one walk, and the only thing that can paint, because
 *    the sync publishes nothing until it commits;
 *  - the SYNC ([LibrarySyncEngine.synchronize]) -- a probe, then every stage filled and then
 *    re-walked, because the stability witness compares a second read against the first.
 *
 * ⚠️ **This file measures REQUESTS, not the paint boundary.** The 2026-09-11 proxy run reported
 * "21 requests before first paint", which was the preview plus however much of the sync had run by
 * the time the preview painted -- a wall-clock split, not a property of the code. #125 changed the
 * sync's shape and cost, so that split cannot be re-derived here and is deliberately not asserted.
 * What is asserted is the total cost of one open, per component. Re-running the proxy is the only
 * thing that can restate the paint boundary.
 */
class LibraryFirstPaintCostTest {

    /**
     * The preview walk, at the corpus the proxy measurement was taken against. #125 did not touch
     * it, and this is unchanged from the measurement it originally pinned.
     *
     * 7 requests: `getMusicFolders` + `getArtists` + page(0) + one look-ahead window of 4. The
     * fifth page comes back short (498 of 500) and ends the walk -- the preview ends on a SHORT
     * page, where the sync's walks end on an EMPTY one, which is why the two disagree about how
     * many pages the same 2,498 albums take.
     */
    @Test
    fun thePreviewIsOneWalkOfSevenRequestsAndReadsNoAlbum() = runTest {
        val transport = CountingLibraryTransport(albumCount = MEASURED_ALBUM_COUNT)

        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(
                transport,
                albumPageSize = LibraryBrowser.DEFAULT_ALBUM_PAGE_SIZE,
                albumConcurrency = LibraryBrowser.DEFAULT_ALBUM_CONCURRENCY,
            ).browse(fixtureRequest()),
        ).snapshot

        assertEquals(MEASURED_ALBUM_COUNT, loaded.albums.size)
        assertEquals(1, transport.requestsTo("getMusicFolders"))
        assertEquals(1, transport.requestsTo("getArtists"))
        assertEquals(5, transport.requestsTo("getAlbumList2"))
        assertEquals(0, transport.requestsTo("getAlbum"))
        assertEquals(0, transport.requestsTo("search3"))
        assertEquals(7, transport.totalRequests)
    }

    /**
     * The sync, and the whole-open histogram assembled from both components.
     *
     * The two are measured in one test on purpose: they are one `browse`, and running the sync
     * twice to assert the same numbers from two angles costs real minutes on Kotlin/Native inside
     * `apple-ci`, where `:core:macosArm64Test` is a required check.
     */
    @Test
    fun theSyncWalksEveryStageExactlyTwiceAndThePreviewAddsOneMore() = runTest {
        val previewTransport = CountingLibraryTransport(albumCount = MEASURED_ALBUM_COUNT)
        assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(
                previewTransport,
                albumPageSize = LibraryBrowser.DEFAULT_ALBUM_PAGE_SIZE,
                albumConcurrency = LibraryBrowser.DEFAULT_ALBUM_CONCURRENCY,
            ).browse(fixtureRequest()),
        )

        val driver = createTestDriver()
        val sync = CountingSyncSource(
            artistCount = STATED_ARTIST_COUNT,
            albumCount = MEASURED_ALBUM_COUNT,
            trackCount = MEASURED_TRACK_COUNT,
        )
        assertIs<LibrarySyncResult.Completed>(
            LibrarySyncEngine(LibrarySyncRepository(DulcetDatabaseStore.open(driver)))
                .synchronize(SERVER, sync),
        )

        // 🚨 THE DISCRIMINATOR. Each paged stage is begun from offset 0 exactly twice: the fill
        // that writes the generation, and the one stability witness that proves it was a snapshot.
        // A third start is an unaccounted walk, and this says so without reference to any corpus.
        assertEquals(
            mapOf("artistPage" to 2, "albumPage" to 2, "trackPage" to 2),
            sync.walkStarts,
            "the pinned read plus the witness, and nothing else",
        )
        // The unpaged stages are the same shape: read, then re-read and compare.
        assertEquals(2, sync.reads("musicFolders"), "pinned read plus stability witness")
        assertEquals(2, sync.reads("starred"), "pinned read plus stability witness")
        assertEquals(2, sync.reads("genres"), "pinned read plus stability witness")
        // The enumeration probe runs once per sync, before any stage.
        assertEquals(1, sync.reads("probeEnumeration"))
        assertEquals(2, sync.reads("playlists"), "pinned read plus stability witness")

        // Pages per walk. These walks end on an EMPTY page, so a corpus that is an exact multiple
        // of the page size costs one more request than its rows alone suggest.
        assertEquals(2 * 3, sync.reads("artistPage"), "1,000 artists: 2 data pages + the empty one")
        assertEquals(2 * 6, sync.reads("albumPage"), "2,498 albums: 5 data pages + the empty one")
        assertEquals(2 * 11, sync.reads("trackPage"), "4,996 tracks: 10 data pages + the empty one")

        // One request per source read, mapped through `HttpLibrarySyncSource` (LibrarySync.kt,
        // ref 4004969): musicFolders -> getMusicFolders; probeEnumeration -> getAlbumList2 AND
        // search3, because it asks a known positive before believing an enumeration; artistPage,
        // albumPage and trackPage -> search3; playlists/starred/genres -> getPlaylists/
        // getStarred2/getGenres.
        val combined = mapOf(
            "getMusicFolders" to previewTransport.requestsTo("getMusicFolders") +
                sync.reads("musicFolders"),
            "getArtists" to previewTransport.requestsTo("getArtists"),
            "getAlbumList2" to previewTransport.requestsTo("getAlbumList2") +
                sync.reads("probeEnumeration"),
            "search3" to sync.reads("probeEnumeration") +
                sync.reads("artistPage") + sync.reads("albumPage") + sync.reads("trackPage"),
            "getPlaylists" to sync.reads("playlists"),
            "getStarred2" to sync.reads("starred"),
            "getGenres" to sync.reads("genres"),
            "getAlbum" to previewTransport.requestsTo("getAlbum"),
        )

        assertEquals(
            mapOf(
                "getMusicFolders" to 3,
                "getArtists" to 1,
                "getAlbumList2" to 6,
                "search3" to 41,
                "getPlaylists" to 2,
                "getStarred2" to 2,
                "getGenres" to 2,
                // 🚨 Zero, and that is the headline of #125. The previous transport read every
                // album individually, twice: 4,996 requests against this corpus.
                "getAlbum" to 0,
            ),
            combined,
        )
        // One whole library open, both components, at the measured corpus. Stated as a derived
        // consequence of the map above -- never as the thing being checked, because a total is
        // exactly what cannot tell one mechanism from another.
        assertEquals(57, combined.values.sum(), "one open, preview and sync together")
    }

    /**
     * Counts the engine's reads, and separately how many times each paged walk was BEGUN.
     *
     * A page count alone cannot distinguish "one walk of six pages" from "two walks of three", and
     * the question this file exists to answer is how many walks there are.
     */
    private class CountingSyncSource(
        private val artistCount: Int,
        private val albumCount: Int,
        private val trackCount: Int,
    ) : LibrarySyncSource {
        private val counts = mutableMapOf<String, Int>()
        private val starts = mutableMapOf<String, Int>()

        val walkStarts: Map<String, Int> get() = starts.toMap()

        fun reads(name: String): Int = counts[name] ?: 0

        private fun record(name: String, offset: Long? = null) {
            counts[name] = (counts[name] ?: 0) + 1
            if (offset == 0L) starts[name] = (starts[name] ?: 0) + 1
        }

        override suspend fun musicFolders(): List<LibraryMusicFolder> {
            record("musicFolders")
            return listOf(LibraryMusicFolder(ProviderItemId(SERVER, "folder"), "Music"))
        }

        override suspend fun probeEnumeration(): LibraryEnumerationProbe {
            record("probeEnumeration")
            return LibraryEnumerationProbe(knownPositiveAlbumCount = 1, enumeratedAlbumCount = 1)
        }

        override suspend fun artistPage(offset: Long, size: Int): List<LibraryArtist> {
            record("artistPage", offset)
            return window(offset, size, artistCount).map { index ->
                LibraryArtist(ProviderItemId(SERVER, "artist-$index"), "Artist $index", null)
            }
        }

        override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> {
            record("albumPage", offset)
            return window(offset, size, albumCount).map { index ->
                AlbumSummary(
                    id = ProviderItemId(SERVER, albumRawId(index)),
                    title = "Album $index",
                    credits = emptyList(),
                    year = null,
                    duration = kotlin.time.Duration.ZERO,
                    mediaSourceId = null,
                    artworkKey = null,
                    trackCount = trackCount / albumCount,
                )
            }
        }

        override suspend fun trackPage(offset: Long, size: Int): List<LibraryTrackRow> {
            record("trackPage", offset)
            return window(offset, size, trackCount).map { index ->
                // Every track belongs to an album this generation's albums walk has seen;
                // a row filed under an unknown album is dropped and marks the sync unverified.
                val albumRawId = albumRawId(index % albumCount)
                LibraryTrackRow(
                    albumRawId = albumRawId,
                    track = LibraryTrack(
                        id = ProviderItemId(SERVER, "track-$index"),
                        title = "Track $index",
                        credits = emptyList(),
                        albumTitle = albumRawId,
                        discNumber = null,
                        trackNumber = 1,
                        duration = kotlin.time.Duration.ZERO,
                        sourceContainer = null,
                        mediaSourceId = null,
                        artworkKey = null,
                    ),
                )
            }
        }

        override suspend fun playlists(): List<LibraryPlaylistSummary> {
            record("playlists")
            return emptyList()
        }

        override suspend fun playlist(summary: LibraryPlaylistSummary): LibraryPlaylist {
            record("playlist")
            return LibraryPlaylist(summary, emptyList())
        }

        override suspend fun starred(): List<LibraryStarredItem> {
            record("starred")
            return emptyList()
        }

        override suspend fun genres(): List<LibraryGenre> {
            record("genres")
            return emptyList()
        }

        private fun albumRawId(index: Int) = "album-$index"

        private fun window(offset: Long, size: Int, total: Int): IntRange {
            val start = offset.toInt()
            if (start >= total) return IntRange.EMPTY
            return start until minOf(start + size, total)
        }
    }

    private class CountingLibraryTransport(
        private val albumCount: Int,
    ) : LibraryEndpointTransport {
        private val counts = mutableMapOf<String, Int>()

        val totalRequests: Int get() = counts.values.sum()

        fun requestsTo(endpoint: String): Int = counts[endpoint] ?: 0

        override suspend fun request(
            endpoint: String,
            parameters: Map<String, String>,
        ): LibraryEndpointResponse {
            counts[endpoint] = (counts[endpoint] ?: 0) + 1
            val body = when (endpoint) {
                "getMusicFolders" -> envelope(
                    "\"musicFolders\":{\"musicFolder\":[{\"id\":\"folder\",\"name\":\"Music\"}]}",
                )
                "getArtists" -> envelope(
                    "\"artists\":{\"index\":[{\"name\":\"A\",\"artist\":" +
                        "[{\"id\":\"artist\",\"name\":\"Artist\"}]}]}",
                )
                "getAlbumList2" -> {
                    val size = parameters.getValue("size").toInt()
                    val offset = parameters.getValue("offset").toInt()
                    val albums = (offset until minOf(offset + size, albumCount)).joinToString(",") {
                        "{\"id\":\"album-$it\",\"name\":\"Album $it\",\"songCount\":1}"
                    }
                    envelope("\"albumList2\":{\"album\":[$albums]}")
                }
                else -> error("unexpected endpoint $endpoint")
            }
            return LibraryEndpointResponse(200, body, "https://music.example.invalid/rest/$endpoint")
        }

        private fun envelope(payload: String) =
            "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",$payload}}"
    }

    private companion object {
        /** The corpus the 2026-09-11 proxy measurement was taken against. */
        const val MEASURED_ALBUM_COUNT = 2_498
        const val MEASURED_TRACK_COUNT = 4_996

        /**
         * Declared, not measured. That proxy run recorded albums and tracks; it did not record how
         * many artists the library held, so this dimension is a parameter of the test rather than
         * a property of the corpus, and is labelled as one instead of being dressed up as one.
         */
        const val STATED_ARTIST_COUNT = 1_000

        const val SERVER = "provider-instance-fixture"

        fun fixtureRequest() = LibraryBrowseRequest(
            providerInstanceId = SERVER,
            normalizedBaseUrl = "https://music.example.invalid",
            username = "listener",
            password = "fixture-password",
            allowLocalHttp = false,
        )
    }
}
