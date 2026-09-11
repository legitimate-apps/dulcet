package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The two properties the fill transport exists for, and one hazard that would silently undo both.
 *
 * 1. **The request count does not grow with the album count.** [fullSyncRequestCountDoesNotGrow...]
 *    runs the same import over 8 albums and over 400 and requires the *identical* call counts at
 *    the transport seam. Any per-album read has to come through that seam, so reintroducing one
 *    breaks the equality by exactly the number of albums.
 * 2. **The library the walks produce is the library `getAlbum` produced.**
 *    [theWalkProducesTheSameLibraryAsGetAlbum] drives both shapes from one set of server responses:
 *    the expected side goes through the production `getAlbumList2`/`getAlbum` parsers, the actual
 *    side through the production walk parsers, the real engine and the real repository, and the
 *    committed snapshots are compared field for field.
 *
 * What that second control can and cannot say is worth stating, because the difference is where
 * this kind of test usually lies. It proves *our* two parse paths agree and that grouping songs by
 * `albumId` reconstructs the same albums. It cannot prove the *server* sends the same values to
 * both endpoints — that is a measurement, made against Navidrome 0.63.2 on 2026-09-11 over 2,500
 * albums (60,000 field values, zero differences) and recorded in the spec.
 */
class LibrarySyncTransportTest {
    @Test
    fun fullSyncRequestCountDoesNotGrowWithTheNumberOfAlbums() = runTest {
        val small = syncCallCounts(albumCount = 8)
        val large = syncCallCounts(albumCount = 400)

        assertEquals(
            mapOf(
                // One probe call, two requests: the empty query and the known positive it is
                // validated against.
                "probeEnumeration" to 1,
                "musicFolders" to 2,
                // Two walks each — one fill pass, one stable witness — and two requests per walk.
                // The second request is not waste: both libraries here fit inside one page, and a
                // first page that is short against the request is exactly what a silently capping
                // server also returns. Asking once more is the only way to tell those apart, and
                // it is why the walk in `aServerThatSilentlyServesShorterPagesStillImportsEveryRow`
                // cannot be fooled. A library larger than one page pays it only when its size is
                // an exact multiple of the page size.
                "artistPage" to 4,
                "albumPage" to 4,
                "trackPage" to 4,
                "playlists" to 2,
                "starred" to 2,
                "genres" to 2,
            ),
            small.counts,
            "a full import is a fixed number of walks: one fill pass and one stable witness each",
        )
        assertEquals(
            small.counts,
            large.counts,
            "fifty times the albums must cost the same requests, or the transport reads per album",
        )
        assertTrue(
            large.total < 400,
            "an import of 400 albums that issues ${large.total} requests is reading per album",
        )
        assertEquals(400, large.committedAlbumIds.size)
        assertEquals(400, large.committedTrackIds.size)
    }

    @Test
    fun theWalkProducesTheSameLibraryAsGetAlbum() = runTest {
        val fixture = libraryFixture()

        // Expected: the transport this change replaced. `getAlbumList2` for the album row, then
        // `getAlbum` for that album's songs, both through the production browse parsers.
        val summaries = parseAlbumList(SERVER, fixture.albumListResponse())
        val expected = summaries.map { summary ->
            parseAlbum(SERVER, summary, fixture.albumResponse(summary.id.rawId))
        }

        withRepository { repository ->
            assertIs<LibrarySyncResult.Completed>(
                LibrarySyncEngine(repository, enumerationPageSize = 3)
                    .synchronize(SERVER, WalkSource(fixture)),
            )
            val committed = repository.readCommittedLibrary(SERVER).library

            assertEquals(
                expected.map { it.id.rawId }.sorted(),
                committed.albums.map { it.id.rawId }.sorted(),
            )
            expected.forEach { album ->
                val actual = committed.albums.single { it.id.rawId == album.id.rawId }
                assertEquals(album.title, actual.title, "title of ${album.id.rawId}")
                assertEquals(album.credits, actual.credits, "credits of ${album.id.rawId}")
                assertEquals(album.year, actual.year, "year of ${album.id.rawId}")
                assertEquals(album.artworkKey, actual.artworkKey, "artwork of ${album.id.rawId}")
                assertEquals(album.mediaSourceId, actual.mediaSourceId, "source of ${album.id.rawId}")
                assertEquals(
                    album.tracks.sortedBy { it.id.rawId },
                    actual.tracks.sortedBy { it.id.rawId },
                    "tracks of ${album.id.rawId}",
                )
        }
        // The album-level duration is the one field where the two transports can disagree, and
        // only when the album row omits `duration`: `getAlbum` then falls back to summing its
        // songs, while the walk keeps the album row's own value. The fixture exercises that case
        // deliberately. It does not arise on the reference server — OBSERVED 2026-09-11, every
        // album row on both a 8-album and a 2,500-album Navidrome 0.63.2 library carried a
        // `duration`, and all 15,048 album-level field values matched `getAlbum` exactly.
        expected.forEach { album ->
            val actual = committed.albums.single { it.id.rawId == album.id.rawId }
            val summary = summaries.single { it.id.rawId == album.id.rawId }
            assertEquals(summary.duration, actual.duration, "duration of ${album.id.rawId}")
        }
        assertEquals(
            fixture.artistIds.sorted(),
            committed.artists.map { it.id.rawId }.sorted(),
        )
        assertEquals(0, repository.visibleDanglingReferenceCount(SERVER))
        }
    }

    @Test
    fun aServerThatSilentlyServesShorterPagesStillImportsEveryRow() = runTest {
        withRepository { repository ->
            // The reference server caps `getAlbumList2` at 500 rows without saying so (OBSERVED
            // 2026-09-11). A walk that ended on "shorter than I asked for" would stop at the cap and
            // commit a library missing everything past it, with no error anywhere.
            val source = CappedSource(GeneratedSource(albumCount = 250), serverPageCap = 100)

            assertIs<LibrarySyncResult.Completed>(
                LibrarySyncEngine(repository, enumerationPageSize = 500).synchronize(SERVER, source),
            )

            val committed = repository.readCommitted(SERVER)
            assertEquals(250, committed.albumIds.size, "the walk stopped at the server's silent cap")
            assertEquals(250, committed.trackIds.size)
            assertTrue(
                source.observedShortPages > 0,
                "the control proved nothing: the fake server never served a short page",
            )
        }
    }

    @Test
    fun anEmptyEnumerationIsRejectedUnlessTheLibraryIsAlsoEmptyToAKnownPositive() = runTest {
        withRepository { repository ->
            val cannotEnumerate = object : LibrarySyncSource by GeneratedSource(albumCount = 4) {
                override suspend fun probeEnumeration() =
                    LibraryEnumerationProbe(knownPositiveAlbumCount = 1, enumeratedAlbumCount = 0)

                override suspend fun albumPage(offset: Long, size: Int) = emptyList<AlbumSummary>()
                override suspend fun trackPage(offset: Long, size: Int) = emptyList<LibraryTrackRow>()
        }

        val failed = assertIs<LibrarySyncResult.Failed>(
            LibrarySyncEngine(repository).synchronize(SERVER, cannotEnumerate),
        )

        assertEquals(
            DomainError.CapabilityUnsupported(CapabilityFeature.LibrarySync),
            failed.error,
        )
        assertEquals(
            0,
            repository.committedGeneration(),
            "a server that cannot enumerate must not commit an empty library over a full one",
        )
        }
    }

    @Test
    fun anEmptyLibraryEnumeratesToAnEmptyLibraryWithoutFailing() = runTest {
        withRepository { repository ->
            val completed = assertIs<LibrarySyncResult.Completed>(
                LibrarySyncEngine(repository).synchronize(SERVER, GeneratedSource(albumCount = 0)),
            )

            assertEquals(LibrarySyncStability.Verified, completed.stability)
            assertEquals(emptyList(), repository.readCommitted(SERVER).albumIds)
        }
    }

    @Test
    fun everyWalkAsksForOneEntityAndZeroesTheOtherTwo() {
        assertEquals(
            mapOf(
                "query" to "\"\"",
                "artistCount" to "0",
                "albumCount" to "500",
                "songCount" to "0",
                "albumOffset" to "1000",
            ),
            libraryEnumerationParameters(LibraryEnumerationKind.Album, offset = 1000, size = 500),
        )
        assertEquals(
            mapOf(
                "query" to "\"\"",
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to "7",
                "songOffset" to "0",
            ),
            libraryEnumerationParameters(LibraryEnumerationKind.Song, offset = 0, size = 7),
        )
    }

    @Test
    fun aSongThatCannotBeFiledUnderAnAlbumFailsInsteadOfVanishing() {
        val body = searchResponse(
            "song",
            listOf(
                jsonObject(
                    "id" to JsonPrimitive("track"),
                    "title" to JsonPrimitive("Orphan"),
                    "suffix" to JsonPrimitive("flac"),
                ),
            ),
        )

        val failure = kotlin.runCatching { parseEnumeratedTracks(SERVER, body) }.exceptionOrNull()

        assertIs<LibraryRequestFailure>(failure)
        assertEquals(DomainError.Protocol.MalformedEnvelope, failure.error)
    }

    private suspend fun syncCallCounts(albumCount: Int): SyncCallCounts =
        withRepository { repository ->
                val source = CountingSource(GeneratedSource(albumCount = albumCount))

                assertIs<LibrarySyncResult.Completed>(
                    LibrarySyncEngine(repository, enumerationPageSize = 500).synchronize(SERVER, source),
                )

                val committed = repository.readCommitted(SERVER)
                SyncCallCounts(source.counts.toMap(), committed.albumIds, committed.trackIds)
        }

    /**
     * Opens a database for one test and always closes it.
     *
     * 🚨 Not tidiness. On Kotlin/Native the test driver opens an in-memory database **by name**, so
     * every test in the binary shares one database for as long as any connection to it is open. A
     * single test that leaks its driver leaves its committed generation visible to every test that
     * runs after it: OBSERVED 2026-09-11, one missing `close()` here turned 11 unrelated tests red
     * on macosArm64 while the whole suite stayed green on the JVM.
     */
    private suspend fun <T> withRepository(body: suspend (LibrarySyncRepository) -> T): T {
        val driver = createTestDriver()
        return try {
            body(LibrarySyncRepository(DulcetDatabaseStore.open(driver)))
        } finally {
            driver.close()
        }
    }

    private class SyncCallCounts(
        val counts: Map<String, Int>,
        val committedAlbumIds: List<String>,
        val committedTrackIds: List<String>,
    ) {
        val total: Int get() = counts.values.sum()
    }

    /**
     * Counts every call that reaches the transport seam.
     *
     * Written as explicit overrides rather than a reflective proxy so that a new source method
     * cannot be added without deciding, here, whether it costs a request per album.
     */
    private class CountingSource(private val delegate: LibrarySyncSource) : LibrarySyncSource {
        val counts = mutableMapOf<String, Int>()

        private fun <T> record(name: String, value: T): T {
            counts[name] = counts.getOrElse(name) { 0 } + 1
            return value
        }

        override suspend fun musicFolders() = record("musicFolders", delegate.musicFolders())
        override suspend fun probeEnumeration() =
            record("probeEnumeration", delegate.probeEnumeration())

        override suspend fun artistPage(offset: Long, size: Int) =
            record("artistPage", delegate.artistPage(offset, size))

        override suspend fun albumPage(offset: Long, size: Int) =
            record("albumPage", delegate.albumPage(offset, size))

        override suspend fun trackPage(offset: Long, size: Int) =
            record("trackPage", delegate.trackPage(offset, size))

        override suspend fun playlists() = record("playlists", delegate.playlists())
        override suspend fun playlist(summary: LibraryPlaylistSummary) =
            record("playlist", delegate.playlist(summary))

        override suspend fun starred() = record("starred", delegate.starred())
        override suspend fun genres() = record("genres", delegate.genres())
    }

    /** A server that silently serves fewer rows than the page asked for. */
    private class CappedSource(
        private val delegate: LibrarySyncSource,
        private val serverPageCap: Int,
    ) : LibrarySyncSource by delegate {
        var observedShortPages = 0
            private set

        override suspend fun albumPage(offset: Long, size: Int): List<AlbumSummary> =
            delegate.albumPage(offset, minOf(size, serverPageCap))
                .also { if (it.size < size) observedShortPages += 1 }

        override suspend fun trackPage(offset: Long, size: Int): List<LibraryTrackRow> =
            delegate.trackPage(offset, minOf(size, serverPageCap))
                .also { if (it.size < size) observedShortPages += 1 }
    }

    /** A library of [albumCount] albums with one track each, served as three walks. */
    private class GeneratedSource(private val albumCount: Int) : LibrarySyncSource {
        private val albums = List(albumCount) { index ->
            AlbumSummary(
                id = ProviderItemId(SERVER, "album-$index"),
                title = "Album $index",
                credits = emptyList(),
                year = null,
                duration = kotlin.time.Duration.ZERO,
                mediaSourceId = null,
                artworkKey = null,
            )
        }
        private val tracks = albums.map { album ->
            LibraryTrackRow(
                albumRawId = album.id.rawId,
                track = LibraryTrack(
                    id = ProviderItemId(SERVER, "track-${album.id.rawId}"),
                    title = "Track ${album.id.rawId}",
                    credits = emptyList(),
                    albumTitle = album.title,
                    discNumber = null,
                    trackNumber = 1,
                    duration = kotlin.time.Duration.ZERO,
                    sourceContainer = AudioContainer.Flac,
                    mediaSourceId = null,
                    artworkKey = null,
                ),
            )
        }

        override suspend fun musicFolders() =
            listOf(LibraryMusicFolder(ProviderItemId(SERVER, "folder"), "Music"))

        override suspend fun probeEnumeration() = LibraryEnumerationProbe(
            knownPositiveAlbumCount = minOf(albumCount, 1),
            enumeratedAlbumCount = minOf(albumCount, 1),
        )

        override suspend fun artistPage(offset: Long, size: Int): List<LibraryArtist> =
            listOf(LibraryArtist(ProviderItemId(SERVER, "artist"), "Artist", null))
                .drop(offset.toInt()).take(size)

        override suspend fun albumPage(offset: Long, size: Int) =
            albums.drop(offset.toInt()).take(size)

        override suspend fun trackPage(offset: Long, size: Int) =
            tracks.drop(offset.toInt()).take(size)

        override suspend fun playlists() = emptyList<LibraryPlaylistSummary>()
        override suspend fun playlist(summary: LibraryPlaylistSummary) =
            LibraryPlaylist(summary, emptyList())

        override suspend fun starred() = emptyList<LibraryStarredItem>()
        override suspend fun genres() = emptyList<LibraryGenre>()
    }

    /** Serves [LibraryFixture]'s `search3` pages through the production walk parsers. */
    private class WalkSource(private val fixture: LibraryFixture) : LibrarySyncSource {
        override suspend fun musicFolders() =
            listOf(LibraryMusicFolder(ProviderItemId(SERVER, "folder"), "Music"))

        override suspend fun probeEnumeration() = LibraryEnumerationProbe(1, 1)

        override suspend fun artistPage(offset: Long, size: Int) =
            parseEnumeratedArtists(SERVER, fixture.artistPageResponse(offset, size))

        override suspend fun albumPage(offset: Long, size: Int) =
            parseEnumeratedAlbums(SERVER, fixture.albumPageResponse(offset, size))

        override suspend fun trackPage(offset: Long, size: Int) =
            parseEnumeratedTracks(SERVER, fixture.songPageResponse(offset, size))

        override suspend fun playlists() = emptyList<LibraryPlaylistSummary>()
        override suspend fun playlist(summary: LibraryPlaylistSummary) =
            LibraryPlaylist(summary, emptyList())

        override suspend fun starred() = emptyList<LibraryStarredItem>()
        override suspend fun genres() = emptyList<LibraryGenre>()
    }

    /**
     * One library rendered in both wire shapes from one set of facts.
     *
     * The variety is deliberate: a multi-disc release, a release with several album artists, a
     * track with no artist credit, a track with no artwork, a missing duration, a missing year and
     * four container suffixes. A comparison over fields that are null on both sides proves nothing.
     */
    private class LibraryFixture(private val albums: List<JsonObject>) {
        val artistIds: List<String> = albums.mapNotNull { album ->
            (album["artistId"] as? JsonPrimitive)?.content
        }.distinct()

        private fun songs(album: JsonObject): List<JsonObject> =
            (album["song"] as? JsonArray).orEmpty().map { it as JsonObject }

        private fun albumRow(album: JsonObject): JsonObject =
            JsonObject(album.filterKeys { it != "song" })

        fun albumListResponse(): String = envelope("albumList2", "album", albums.map(::albumRow))

        fun albumResponse(rawId: String): String {
            val album = albums.single { (it["id"] as JsonPrimitive).content == rawId }
            return "{\"subsonic-response\":{\"status\":\"ok\",\"album\":$album}}"
        }

        fun albumPageResponse(offset: Long, size: Int): String = searchResponse(
            "album",
            albums.map(::albumRow).drop(offset.toInt()).take(size),
        )

        fun songPageResponse(offset: Long, size: Int): String = searchResponse(
            "song",
            albums.flatMap { album ->
                songs(album).map { song ->
                    JsonObject(song + ("albumId" to album.getValue("id")))
                }
            }.drop(offset.toInt()).take(size),
        )

        fun artistPageResponse(offset: Long, size: Int): String = searchResponse(
            "artist",
            albums.mapNotNull { album ->
                val id = album["artistId"] as? JsonPrimitive ?: return@mapNotNull null
                jsonObject("id" to id, "name" to album.getValue("artist"))
            }.distinctBy { (it.getValue("id") as JsonPrimitive).content }
                .drop(offset.toInt()).take(size),
        )

        private fun envelope(container: String, field: String, rows: List<JsonObject>): String =
            "{\"subsonic-response\":{\"status\":\"ok\",\"$container\":" +
                "{\"$field\":${JsonArray(rows)}}}}"
    }

    private companion object {
        const val SERVER = "server:transport"

        fun jsonObject(vararg fields: Pair<String, JsonElement>): JsonObject =
            JsonObject(fields.toMap().filterValues { it != JsonNull })

        fun searchResponse(field: String, rows: List<JsonObject>): String =
            "{\"subsonic-response\":{\"status\":\"ok\",\"searchResult3\":" +
                "{\"$field\":${JsonArray(rows)}}}}"

        fun song(
            id: String,
            title: String,
            albumTitle: String,
            artist: String?,
            artistId: String?,
            disc: Int?,
            track: Int?,
            duration: Int?,
            suffix: String?,
            contentType: String?,
            coverArt: String?,
        ): JsonObject = jsonObject(
            "id" to JsonPrimitive(id),
            "title" to JsonPrimitive(title),
            "album" to JsonPrimitive(albumTitle),
            "artist" to (artist?.let(::JsonPrimitive) ?: JsonNull),
            "artistId" to (artistId?.let(::JsonPrimitive) ?: JsonNull),
            "discNumber" to (disc?.let(::JsonPrimitive) ?: JsonNull),
            "track" to (track?.let(::JsonPrimitive) ?: JsonNull),
            "duration" to (duration?.let(::JsonPrimitive) ?: JsonNull),
            "suffix" to (suffix?.let(::JsonPrimitive) ?: JsonNull),
            "contentType" to (contentType?.let(::JsonPrimitive) ?: JsonNull),
            "coverArt" to (coverArt?.let(::JsonPrimitive) ?: JsonNull),
        )

        fun album(
            id: String,
            name: String,
            artist: String?,
            artistId: String?,
            year: Int?,
            duration: Int?,
            coverArt: String?,
            songs: List<JsonObject>,
        ): JsonObject = jsonObject(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "artist" to (artist?.let(::JsonPrimitive) ?: JsonNull),
            "artistId" to (artistId?.let(::JsonPrimitive) ?: JsonNull),
            "year" to (year?.let(::JsonPrimitive) ?: JsonNull),
            "duration" to (duration?.let(::JsonPrimitive) ?: JsonNull),
            "coverArt" to (coverArt?.let(::JsonPrimitive) ?: JsonNull),
            "song" to JsonArray(songs),
        )

        fun libraryFixture() = LibraryFixture(
            listOf(
                album(
                    id = "al-1", name = "Двойные линии", artist = "Хор", artistId = "ar-1",
                    year = 1998, duration = 244, coverArt = "al-1_6aa3",
                    songs = listOf(
                        song(
                            "tr-1", "Первая", "Двойные линии", "Хор", "ar-1",
                            disc = 1, track = 1, duration = 121, suffix = "flac",
                            contentType = "audio/flac", coverArt = "mf-tr-1_1",
                        ),
                        song(
                            "tr-2", "Вторая", "Двойные линии", "Хор", "ar-1",
                            disc = 2, track = 1, duration = 123, suffix = "mp3",
                            contentType = "audio/mpeg", coverArt = "mf-tr-2_1",
                        ),
                    ),
                ),
                album(
                    id = "al-2", name = "Several Album Artists",
                    artist = "Alpha • Beta", artistId = "ar-2", year = null, duration = 60,
                    coverArt = "al-2_1",
                    songs = listOf(
                        song(
                            "tr-3", "No credit at all", "Several Album Artists", null, null,
                            disc = null, track = 4, duration = 60, suffix = "m4a",
                            contentType = "audio/mp4", coverArt = null,
                        ),
                    ),
                ),
                album(
                    id = "al-3", name = "Unknown duration", artist = "Solo", artistId = "ar-3",
                    year = 2026, duration = null, coverArt = null,
                    songs = listOf(
                        song(
                            "tr-4", "No duration", "Unknown duration", "Solo", "ar-3",
                            disc = null, track = null, duration = null, suffix = null,
                            contentType = "application/ogg", coverArt = "mf-tr-4_1",
                        ),
                        song(
                            "tr-5", "Aac by suffix", "Unknown duration", "Solo", "ar-3",
                            disc = null, track = 2, duration = 3, suffix = "aac",
                            contentType = null, coverArt = null,
                        ),
                    ),
                ),
                album(
                    id = "al-4", name = "Fourth, to force a second page", artist = "Solo",
                    artistId = "ar-3", year = 2001, duration = 1, coverArt = "al-4_2",
                    songs = listOf(
                        song(
                            "tr-6", "Last", "Fourth, to force a second page", "Solo", "ar-3",
                            disc = null, track = 1, duration = 1, suffix = "wav",
                            contentType = "audio/wav", coverArt = "mf-tr-6_1",
                        ),
                    ),
                ),
            ),
        )
    }
}
