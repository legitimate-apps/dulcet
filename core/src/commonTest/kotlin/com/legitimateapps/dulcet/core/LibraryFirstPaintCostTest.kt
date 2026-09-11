package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What one library open costs the person's server, decomposed by the component that issued each
 * request.
 *
 * OBSERVED 2026-09-11 through the shipping build against a disposable Navidrome (2,498 albums /
 * 4,996 tracks) behind a logging proxy: first paint is 21 requests -- 3x `getMusicFolders`,
 * 3x `getArtists`, 15x `getAlbumList2` -- and the sync that follows is 4,996 `getAlbum`. That
 * histogram looks exactly like three identical browse walks, and the obvious reading is that one
 * library open started three of them.
 *
 * It did not. One open starts exactly one `browse`, and `browse` runs two things at once:
 *
 *  - the PREVIEW ([LibraryBrowser.browse]) -- ONE walk, and the only thing that paints inside
 *    half a second;
 *  - the SYNC ([LibrarySyncEngine.synchronize]) -- which reads every stage TWICE, because the
 *    stability witness re-reads each stage and compares it against the first read. That is two
 *    walks, and both are required: a stage that is read once cannot be known to be a snapshot.
 *
 * 1 + 2 = 3, and every request has an owner.
 *
 * 🚨 **The counts are asserted per endpoint and per component, never as a total.** A total of 21
 * is produced equally by one preview plus a two-pass sync and by three previews, so a total
 * cannot tell the two apart -- and they are different defects with different fixes. The
 * discriminator is `getAlbum`: one sync reads every album exactly twice, so 4,996 against a
 * 2,498-album server is one sync. Three would have been 14,988.
 */
class LibraryFirstPaintCostTest {

    /**
     * The preview walk, at the corpus the proxy measurement was taken against.
     *
     * 7 requests: `getMusicFolders` + `getArtists` + page(0) + one look-ahead window of 4. The
     * fifth page comes back short (498 of 500) and ends the walk.
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
        assertEquals(7, transport.totalRequests)
    }

    /**
     * The sync, at the same corpus. Every stage is read twice -- the pinned read, then the
     * stability witness that proves it was a snapshot.
     *
     * This is where the OTHER two walks come from, and it is the reason the measured histogram is
     * an odd multiple: 1 preview + 2 sync passes.
     */
    @Test
    fun theSyncReadsEveryStageTwiceAndEveryAlbumTwice() = runTest {
        val driver = createTestDriver()
        val source = CountingSyncSource(albumCount = MEASURED_ALBUM_COUNT)

        val result = LibrarySyncEngine(LibrarySyncRepository(DulcetDatabaseStore.open(driver)))
            .synchronize(SERVER, source)

        assertIs<LibrarySyncResult.Completed>(result)
        assertEquals(2, source.reads("musicFolders"), "pinned read plus stability witness")
        assertEquals(2, source.reads("artists"), "pinned read plus stability witness")
        assertEquals(10, source.reads("albumPage"), "two full five-page walks")
        assertEquals(
            2 * MEASURED_ALBUM_COUNT,
            source.reads("album"),
            "exactly two getAlbum passes -- a third would be an unaccounted walk",
        )
        driver.close()
    }

    /**
     * The whole first-paint histogram, assembled from the two components, against the numbers the
     * proxy actually recorded.
     *
     * `getAlbum` is deliberately compared too: it is the only column that separates "one sync"
     * from "three browses", and leaving it out is what makes 21 look like three previews.
     */
    @Test
    fun theTwoComponentsTogetherReproduceTheMeasuredHistogram() = runTest {
        val previewTransport = CountingLibraryTransport(albumCount = MEASURED_ALBUM_COUNT)
        assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(
                previewTransport,
                albumPageSize = LibraryBrowser.DEFAULT_ALBUM_PAGE_SIZE,
                albumConcurrency = LibraryBrowser.DEFAULT_ALBUM_CONCURRENCY,
            ).browse(fixtureRequest()),
        )

        val driver = createTestDriver()
        val syncSource = CountingSyncSource(albumCount = MEASURED_ALBUM_COUNT)
        assertIs<LibrarySyncResult.Completed>(
            LibrarySyncEngine(LibrarySyncRepository(DulcetDatabaseStore.open(driver)))
                .synchronize(SERVER, syncSource),
        )

        // The sync source counts the engine's reads. Each one is exactly one request:
        // `HttpLibrarySyncSource` (LibrarySync.kt, ref b599cdb) maps musicFolders/artists/
        // albumPage/album onto getMusicFolders/getArtists/getAlbumList2/getAlbum one for one.
        val combined = mapOf(
            "getMusicFolders" to previewTransport.requestsTo("getMusicFolders") +
                syncSource.reads("musicFolders"),
            "getArtists" to previewTransport.requestsTo("getArtists") +
                syncSource.reads("artists"),
            "getAlbumList2" to previewTransport.requestsTo("getAlbumList2") +
                syncSource.reads("albumPage"),
            "getAlbum" to previewTransport.requestsTo("getAlbum") + syncSource.reads("album"),
        )

        assertEquals(
            mapOf(
                "getMusicFolders" to 3,
                "getArtists" to 3,
                "getAlbumList2" to 15,
                "getAlbum" to 2 * MEASURED_ALBUM_COUNT,
            ),
            combined,
        )
        // The three browse-shaped endpoints are what the proxy saw before first paint.
        assertEquals(
            21,
            combined.getValue("getMusicFolders") +
                combined.getValue("getArtists") +
                combined.getValue("getAlbumList2"),
        )
        driver.close()
    }

    /** Counts the engine's reads. One read is one request -- see the note above. */
    private class CountingSyncSource(private val albumCount: Int) : LibrarySyncSource {
        private val counts = mutableMapOf<String, Int>()

        fun reads(name: String): Int = counts[name] ?: 0

        private fun record(name: String) {
            counts[name] = (counts[name] ?: 0) + 1
        }

        private fun rawId(index: Int) = "album-$index"

        override suspend fun musicFolders(): List<LibraryMusicFolder> {
            record("musicFolders")
            return listOf(LibraryMusicFolder(ProviderItemId(SERVER, "folder"), "Music"))
        }

        override suspend fun artists(): List<LibraryArtist> {
            record("artists")
            return listOf(LibraryArtist(ProviderItemId(SERVER, "artist"), "Artist", null))
        }

        override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> {
            record("albumPage")
            return (offset.toInt() until minOf(offset.toInt() + size, albumCount)).map { index ->
                AlbumSummary(
                    id = ProviderItemId(SERVER, rawId(index)),
                    title = "Album $index",
                    credits = emptyList(),
                    year = null,
                    duration = kotlin.time.Duration.ZERO,
                    mediaSourceId = null,
                    artworkKey = null,
                    trackCount = 1,
                )
            }
        }

        override suspend fun album(rawId: String): LibraryAlbum {
            record("album")
            return LibraryAlbum(
                id = ProviderItemId(SERVER, rawId),
                title = rawId,
                credits = emptyList(),
                year = null,
                duration = kotlin.time.Duration.ZERO,
                mediaSourceId = null,
                artworkKey = null,
                tracks = listOf(
                    LibraryTrack(
                        id = ProviderItemId(SERVER, "track-$rawId"),
                        title = "Track",
                        credits = emptyList(),
                        albumTitle = rawId,
                        discNumber = null,
                        trackNumber = 1,
                        duration = kotlin.time.Duration.ZERO,
                        sourceContainer = null,
                        mediaSourceId = null,
                        artworkKey = null,
                    ),
                ),
            )
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
