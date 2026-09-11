package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LibraryBrowseTest {
    @Test
    fun pagesOpaqueIdsAndCopiesDurationsCreditsAndMediaSourceIdentity() = runTest {
        val transport = RecordedLibraryTransport()
        val result = LibraryBrowser(
            transport = transport,
            albumPageSize = 2,
            albumConcurrency = 2,
        ).browse(fixtureRequest())

        val loaded = assertIs<LibraryBrowseResult.Loaded>(result).snapshot
        assertEquals(listOf("1"), loaded.musicFolders.map { it.id.rawId })
        assertEquals(listOf("artist:opaque-A"), loaded.artists.map { it.id.rawId })
        assertEquals(
            listOf("album:opaque-A", "album:opaque-B", "album:opaque-C"),
            loaded.albums.map { it.id.rawId },
        )
        // The window beyond the short page is read concurrently, so assert the set of offsets
        // rather than an order two concurrent requests do not have.
        assertEquals(setOf("0", "2", "4"), transport.albumListOffsets.toSet())
        assertTrue(loaded.albums.all { it.id.providerInstanceId == PROVIDER_INSTANCE_ID })

        val first = loaded.albums.first()
        assertEquals(121.seconds, first.duration)
        assertNull(first.mediaSourceId)
        assertEquals("artwork:album:opaque-A", first.artworkKey)
        assertEquals(
            Credit(
                CreditRole.AlbumArtist,
                "Opaque Artist",
                ProviderItemId(PROVIDER_INSTANCE_ID, "artist:opaque-A"),
            ),
            first.credits.single(),
        )
    }

    @Test
    fun readsOneAlbumsTracksAndCopiesTheirIdentity() = runTest {
        val transport = RecordedLibraryTransport()
        val album = assertIs<LibraryAlbumTracksResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 2, albumConcurrency = 2)
                .albumTracks(fixtureRequest(), "album:opaque-A"),
        ).album

        assertTrue(album.tracksLoaded)
        assertEquals(1, album.trackCount)
        val track = album.tracks.single()
        assertEquals("track:000000000000000000000000000001", track.id.rawId)
        assertEquals(61.seconds, track.duration)
        assertEquals(AudioContainer.Flac, track.sourceContainer)
        assertEquals(
            Credit(
                CreditRole.Artist,
                "Opaque Artist",
                ProviderItemId(PROVIDER_INSTANCE_ID, "artist:opaque-A"),
            ),
            track.credits.single(),
        )
        assertNull(track.mediaSourceId)
        assertEquals("artwork:track:000000000000000000000000000001", track.artworkKey)
    }

    // ---- first-paint request cost -------------------------------------------------------------
    //
    // These are the controls for the defect this file exists to prevent coming back: a library
    // open that costs one request per album. Asserting "the library loaded" passes either way,
    // so these assert the REQUEST COUNT instead.

    @Test
    fun firstPaintIssuesNoRequestPerAlbum() = runTest {
        val transport = CountingLibraryTransport(albumCount = 1_200)
        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 500, albumConcurrency = 4)
                .browse(fixtureRequest()),
        ).snapshot

        assertEquals(1_200, loaded.albums.size)
        // getMusicFolders + getArtists + page(0) + one look-ahead window of 4 = 7.
        // The per-album walk this replaced would have been 7 + 1_200.
        assertEquals(7, transport.totalRequests)
        assertEquals(5, transport.requestsTo("getAlbumList2"))
        assertEquals(0, transport.requestsTo("getAlbum"))
    }

    /**
     * The honest shape of the cost, at the SHIPPED page size.
     *
     * It is not constant — it grows with PAGES. What it must never contain is a request per
     * album, which is the defect this file exists to prevent returning. A previous version of
     * this test claimed constancy and got it by setting the page size to 5,000, which is both
     * above the protocol maximum and the exact configuration that made the walk truncate.
     */
    @Test
    fun firstPaintCostsNoRequestPerAlbumAndOnlyOnePerPage() = runTest {
        val expected = mapOf(
            40 to 4,
            400 to 4,
            1_200 to 7,
            2_950 to 11,
            4_000 to 11,
            12_000 to 27,
        )
        val observed = expected.keys.sorted().associateWith { albumCount ->
            val transport = CountingLibraryTransport(albumCount = albumCount)
            val loaded = assertIs<LibraryBrowseResult.Loaded>(
                LibraryBrowser(
                    transport,
                    albumPageSize = LibraryBrowser.DEFAULT_ALBUM_PAGE_SIZE,
                    albumConcurrency = LibraryBrowser.DEFAULT_ALBUM_CONCURRENCY,
                ).browse(fixtureRequest()),
            ).snapshot
            assertEquals(albumCount, loaded.albums.size, "albums at $albumCount")
            assertEquals(0, transport.requestsTo("getAlbum"), "getAlbum at $albumCount")
            transport.totalRequests
        }

        // `toSortedMap` is a JVM-only stdlib extension. This file is commonTest, so it compiles for
        // Kotlin/Native too, where the reference does not resolve -- and `:core:jvmTest` passes
        // regardless, which is exactly why the break reached CI. Sort the keys explicitly instead.
        assertEquals(expected.entries.sortedBy { it.key }.associate { it.key to it.value }, observed)
        // 12,000 albums cost 27 requests. The walk this replaced cost 12,000 of them plus the
        // same paging.
        assertTrue(observed.getValue(12_000) < 30)
    }

    /**
     * 🚨 A server may return fewer albums than `size` asked for, without saying so. OBSERVED
     * 2026-09-11 against the pinned reference server (Navidrome 0.63.2, 1,208 albums): `size=501`
     * and `size=5000` both return 500 with `status="ok"`. A walk that reads "shorter than I asked
     * for" as "that was the end" reports a truncated library as a complete one — measured through
     * the production client at 500 of 1,208.
     */
    @Test
    fun aServerThatSilentlyCapsThePageSizeIsPagedToCompletionRatherThanTruncated() = runTest {
        val transport = CountingLibraryTransport(albumCount = 1_000, serverPageCap = 100)
        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 500, albumConcurrency = 4)
                .browse(fixtureRequest()),
        ).snapshot

        assertEquals(1_000, loaded.albums.size)
        assertEquals(
            (0 until 1_000).map { "album:counted-$it" },
            loaded.albums.map { it.id.rawId },
        )
        assertEquals(0, transport.requestsTo("getAlbum"))
    }

    /** A page size above what the protocol allows describes a request no server can answer. */
    @Test
    fun theAlbumPageSizeCannotExceedTheProtocolMaximum() {
        assertEquals(500, LibraryBrowser.MAX_ALBUM_PAGE_SIZE)
        assertFailsWith<IllegalArgumentException> {
            LibraryBrowser(RecordedLibraryTransport(), albumPageSize = 501)
        }
        assertFailsWith<IllegalArgumentException> {
            LibraryBrowser(RecordedLibraryTransport(), albumPageSize = 0)
        }
    }

    /**
     * The control above is only worth its assertion if the counter can actually see a per-album
     * request. Reading one album's tracks through the same counter must move `getAlbum` off zero.
     */
    @Test
    fun theRequestCounterObservesGetAlbumWhenATrackListIsRead() = runTest {
        val transport = CountingLibraryTransport(albumCount = 40)
        val browser = LibraryBrowser(transport, albumPageSize = 500, albumConcurrency = 4)

        assertIs<LibraryBrowseResult.Loaded>(browser.browse(fixtureRequest()))
        assertEquals(0, transport.requestsTo("getAlbum"))

        assertIs<LibraryAlbumTracksResult.Loaded>(
            browser.albumTracks(fixtureRequest(), "album:counted-0"),
        )
        assertEquals(1, transport.requestsTo("getAlbum"))
        // getMusicFolders + getArtists + the album page + the page that confirms the end of the
        // list, then one getAlbum. The confirming page is what stops a server that silently caps
        // its page size from reading as a finished library.
        assertEquals(5, transport.totalRequests)
    }

    @Test
    fun browseCarriesTheServerDeclaredTrackCountWithoutReadingTheTrackList() = runTest {
        val transport = CountingLibraryTransport(albumCount = 3, songCount = 7)
        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 500).browse(fixtureRequest()),
        ).snapshot

        assertTrue(loaded.albums.all { it.trackCount == 7 })
        assertTrue(loaded.albums.all { it.tracks.isEmpty() })
        assertTrue(loaded.albums.none { it.tracksLoaded })
        assertEquals(0, transport.requestsTo("getAlbum"))
    }

    @Test
    fun anAlbumWithoutADeclaredSongCountReportsZeroRatherThanGuessing() = runTest {
        val transport = LibraryEndpointTransport { endpoint, _ ->
            when (endpoint) {
                "getMusicFolders" -> success(musicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> success(
                    envelope(
                        "\"albumList2\":{\"album\":[{\"id\":\"album:no-count\"," +
                            "\"name\":\"No Count\",\"duration\":121}]}",
                    ),
                )
                else -> error("unexpected endpoint $endpoint")
            }
        }
        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 20).browse(fixtureRequest()),
        ).snapshot

        assertEquals(0, loaded.albums.single().trackCount)
        assertFalse(loaded.albums.single().tracksLoaded)
    }

    // ---- paging -------------------------------------------------------------------------------

    @Test
    fun concurrentPageWindowsPreserveTheSequentialAlbumOrder() = runTest {
        // Later offsets answer first, so a window that merged in completion order would scramble.
        val transport = LibraryEndpointTransport { endpoint, parameters ->
            when (endpoint) {
                "getMusicFolders" -> success(musicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> {
                    val offset = parameters.getValue("offset").toInt()
                    delay((40 - offset).coerceAtLeast(1).milliseconds)
                    success(
                        albumListBody(
                            (offset until minOf(offset + 2, 10)).map { "album:ordered-" + it.toString().padStart(2, '0') },
                        ),
                    )
                }
                else -> error("unexpected endpoint $endpoint")
            }
        }

        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 2, albumConcurrency = 4)
                .browse(fixtureRequest()),
        ).snapshot

        assertEquals((0 until 10).map { "album:ordered-" + it.toString().padStart(2, '0') }, loaded.albums.map { it.id.rawId })
    }

    @Test
    fun pageWindowsNeverExceedTheConfiguredConcurrency() = runTest {
        val transport = InFlightMeasuringTransport(albumCount = 20, albumPageSize = 2)
        assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 2, albumConcurrency = 4)
                .browse(fixtureRequest()),
        )

        assertEquals(4, transport.maximumInFlight)
    }

    /**
     * A server that keeps answering full pages of albums we have already seen must end the walk
     * rather than page forever. This is a termination condition, not a tolerance: the walk stops
     * because the server offered nothing new, and every distinct album it did offer is kept.
     */
    @Test
    fun aWindowThatOffersNoNewAlbumIdsEndsTheWalk() = runTest {
        val transport = CountingLibraryTransport(albumCount = 2, repeatEveryPage = true)
        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 2, albumConcurrency = 4)
                .browse(fixtureRequest()),
        ).snapshot

        assertEquals(listOf("album:counted-0", "album:counted-1"), loaded.albums.map { it.id.rawId })
        // page(0) plus one window of 4, then the walk stops because nothing was new.
        assertEquals(7, transport.totalRequests)
    }

    // ---- deadline -----------------------------------------------------------------------------

    @Test
    fun aRequestThatNeverAnswersBecomesATypedTimeoutNotACancellation() = runTest {
        val transport = LibraryEndpointTransport { endpoint, _ ->
            when (endpoint) {
                "getMusicFolders" -> awaitCancellation()
                else -> success(artistsBody())
            }
        }

        val failure = assertIs<LibraryBrowseResult.Failed>(
            LibraryBrowser(transport, firstPaintBudget = 30.seconds).browse(fixtureRequest()),
        )

        assertEquals(DomainError.Transport.Timeout, failure.error)
    }

    /**
     * A walk that runs out of budget part way through has albums in hand. Reporting those as the
     * library would present a truncated library as a complete one, which is worse than the stall
     * it came from: the deadline exists to REPORT, so the partial result is discarded in favour
     * of a typed failure.
     */
    @Test
    fun aWalkThatRunsOutOfBudgetReportsRatherThanPublishingAShortLibrary() = runTest {
        val transport = LibraryEndpointTransport { endpoint, parameters ->
            when (endpoint) {
                "getMusicFolders" -> success(musicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> if (parameters.getValue("offset") == "0") {
                    success(albumListBody(listOf("album:answered-0", "album:answered-1")))
                } else {
                    awaitCancellation()
                }
                else -> error("unexpected endpoint $endpoint")
            }
        }

        val result = LibraryBrowser(
            transport,
            albumPageSize = 2,
            albumConcurrency = 4,
            firstPaintBudget = 30.seconds,
        ).browse(fixtureRequest())

        assertEquals(
            DomainError.Transport.Timeout,
            assertIs<LibraryBrowseResult.Failed>(result).error,
        )
    }

    @Test
    fun anAlbumTrackReadThatNeverAnswersBecomesATypedTimeout() = runTest {
        val transport = LibraryEndpointTransport { _, _ -> awaitCancellation() }

        val failure = assertIs<LibraryAlbumTracksResult.Failed>(
            LibraryBrowser(transport, firstPaintBudget = 30.seconds)
                .albumTracks(fixtureRequest(), "album:hung"),
        )

        assertEquals(DomainError.Transport.Timeout, failure.error)
    }

    @Test
    fun callerCancellationIsStillReportedAsCancellationNotAsATimeout() = runTest {
        val entered = CompletableDeferred<Unit>()
        var childRequestCancelled = false
        val transport = LibraryEndpointTransport { endpoint, _ ->
            when (endpoint) {
                "getMusicFolders" -> success(musicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        childRequestCancelled = true
                    }
                }
                else -> error("unexpected endpoint")
            }
        }
        val operation = async {
            LibraryBrowser(transport, albumPageSize = 20, albumConcurrency = 4)
                .browse(fixtureRequest())
        }
        entered.await()
        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertTrue(childRequestCancelled)
    }

    // ---- redaction and malformed input ---------------------------------------------------------

    @Test
    fun everyFailurePathDiscardsRawQueriesCredentialsAndServerText() = runTest {
        val canaries = listOf(
            "redaction-user-canary",
            "redaction-token-canary",
            "redaction-salt-canary",
            "redaction-server-message-canary",
        )
        val request = fixtureRequest().copy(
            username = canaries[0],
            password = canaries[1],
        )
        val failures = listOf<LibraryEndpointTransport>(
            LibraryEndpointTransport { _, _ ->
                LibraryEndpointResponse(
                    200,
                    "not-json-${canaries[3]}",
                    "https://music.example.invalid/rest/getMusicFolders.view?<redacted>",
                )
            },
            LibraryEndpointTransport { _, _ ->
                LibraryEndpointResponse(
                    200,
                    errorBody(70, canaries[3]),
                    "https://music.example.invalid/rest/getMusicFolders.view?<redacted>",
                )
            },
            LibraryEndpointTransport { _, _ ->
                throw IllegalStateException(
                    "https://music.example.invalid/rest/getMusicFolders.view" +
                        "?u=${canaries[0]}&t=${canaries[1]}&s=${canaries[2]}",
                )
            },
        ).flatMap { transport ->
            listOf(
                assertIs<LibraryBrowseResult.Failed>(
                    LibraryBrowser(transport).browse(request),
                ).error,
                assertIs<LibraryAlbumTracksResult.Failed>(
                    LibraryBrowser(transport).albumTracks(request, "album:canary"),
                ).error,
            )
        }

        val rendered = failures.flatMap { error ->
            listOf(error.toString(), error.toDiagnosticJson())
        } + request.toString()
        canaries.forEach { canary ->
            assertTrue(rendered.none { it.contains(canary) }, canary)
        }
        assertFalse(rendered.any { it.contains("?u=") || it.contains("&t=") || it.contains("&s=") })
    }

    @Test
    fun acceptsNumericOpaqueIdsOutsideMusicFoldersWithoutNumericConversion() = runTest {
        val transport = LibraryEndpointTransport { endpoint, parameters ->
            when (endpoint) {
                "getMusicFolders" -> success(quotedMusicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> success(
                    if (parameters.getValue("offset") == "0") numericAlbumListBody()
                    else albumListBody(emptyList()),
                )
                "getAlbum" -> {
                    assertEquals("7", parameters.getValue("id"))
                    success(numericAlbumBody())
                }
                else -> error("unexpected endpoint $endpoint")
            }
        }
        val browser = LibraryBrowser(transport, albumPageSize = 20)

        val loaded = assertIs<LibraryBrowseResult.Loaded>(browser.browse(fixtureRequest())).snapshot
        assertEquals("7", loaded.albums.single().id.rawId)
        assertEquals("11", loaded.albums.single().artworkKey)

        val album = assertIs<LibraryAlbumTracksResult.Loaded>(
            browser.albumTracks(fixtureRequest(), "7"),
        ).album
        assertEquals("9", album.tracks.single().id.rawId)
        assertEquals("12", album.tracks.single().artworkKey)
    }

    @Test
    fun rejectsNonPrimitiveNullMissingAndBlankOpaqueIds() = runTest {
        val malformedFolderObjects = listOf(
            "{\"id\":{},\"name\":\"Primary\"}",
            "{\"id\":[],\"name\":\"Primary\"}",
            "{\"id\":null,\"name\":\"Primary\"}",
            "{\"name\":\"Primary\"}",
            "{\"id\":\"\",\"name\":\"Primary\"}",
            "{\"id\":\"   \" ,\"name\":\"Primary\"}",
        )

        malformedFolderObjects.forEach { folderObject ->
            val transport = LibraryEndpointTransport { endpoint, _ ->
                when (endpoint) {
                    "getMusicFolders" -> success(musicFoldersBody(folderObject))
                    "getArtists" -> success(artistsBody())
                    "getAlbumList2" -> success(albumListBody(emptyList()))
                    else -> error("unexpected endpoint $endpoint")
                }
            }

            val failure = assertIs<LibraryBrowseResult.Failed>(
                LibraryBrowser(transport).browse(fixtureRequest()),
            )
            assertEquals(DomainError.Protocol.MalformedEnvelope, failure.error)
        }
    }

    private class RecordedLibraryTransport : LibraryEndpointTransport {
        val albumListOffsets = mutableListOf<String>()

        override suspend fun request(
            endpoint: String,
            parameters: Map<String, String>,
        ): LibraryEndpointResponse = when (endpoint) {
            "getMusicFolders" -> success(musicFoldersBody())
            "getArtists" -> success(artistsBody())
            "getAlbumList2" -> {
                val offset = parameters.getValue("offset")
                albumListOffsets += offset
                success(
                    albumListBody(
                        when (offset) {
                            "0" -> listOf("album:opaque-A", "album:opaque-B")
                            "2" -> listOf("album:opaque-C")
                            else -> emptyList()
                        },
                    ),
                )
            }
            "getAlbum" -> success(albumBody(parameters.getValue("id")))
            else -> error("unexpected endpoint $endpoint")
        }
    }

    /** Counts every request by endpoint, so a per-album fan-out cannot reappear unnoticed. */
    private class CountingLibraryTransport(
        private val albumCount: Int,
        private val songCount: Int = 1,
        private val repeatEveryPage: Boolean = false,
        /** What the server will actually serve per page, however much is asked for. */
        private val serverPageCap: Int = Int.MAX_VALUE,
    ) : LibraryEndpointTransport {
        private val counts = mutableMapOf<String, Int>()

        val totalRequests: Int get() = counts.values.sum()

        fun requestsTo(endpoint: String): Int = counts[endpoint] ?: 0

        override suspend fun request(
            endpoint: String,
            parameters: Map<String, String>,
        ): LibraryEndpointResponse {
            counts[endpoint] = (counts[endpoint] ?: 0) + 1
            return when (endpoint) {
                "getMusicFolders" -> success(musicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> {
                    val size = minOf(parameters.getValue("size").toInt(), serverPageCap)
                    val offset = if (repeatEveryPage) 0 else parameters.getValue("offset").toInt()
                    success(
                        albumListBody(
                            (offset until minOf(offset + size, albumCount))
                                .map { "album:counted-$it" },
                            songCount = songCount,
                        ),
                    )
                }
                "getAlbum" -> success(albumBody(parameters.getValue("id")))
                else -> error("unexpected endpoint $endpoint")
            }
        }
    }

    private class InFlightMeasuringTransport(
        private val albumCount: Int,
        private val albumPageSize: Int,
    ) : LibraryEndpointTransport {
        var maximumInFlight = 0
            private set
        private var inFlight = 0

        override suspend fun request(
            endpoint: String,
            parameters: Map<String, String>,
        ): LibraryEndpointResponse {
            inFlight += 1
            maximumInFlight = maxOf(maximumInFlight, inFlight)
            delay(10)
            inFlight -= 1
            return when (endpoint) {
                "getMusicFolders" -> success(musicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> {
                    val offset = parameters.getValue("offset").toInt()
                    success(
                        albumListBody(
                            (offset until minOf(offset + albumPageSize, albumCount))
                                .map { "album:flight-$it" },
                        ),
                    )
                }
                else -> error("unexpected endpoint $endpoint")
            }
        }
    }

    private companion object {
        const val PROVIDER_INSTANCE_ID = "provider-instance-fixture"

        fun fixtureRequest() = LibraryBrowseRequest(
            providerInstanceId = PROVIDER_INSTANCE_ID,
            normalizedBaseUrl = "https://music.example.invalid",
            username = "listener",
            password = "fixture-password",
            allowLocalHttp = false,
        )

        fun success(body: String) = LibraryEndpointResponse(
            statusCode = 200,
            body = body,
            redactedUrl = "https://music.example.invalid/rest/fixture.view?<redacted>",
        )

        fun musicFoldersBody() = musicFoldersBody("{\"id\":1,\"name\":\"Primary\"}")

        fun quotedMusicFoldersBody() =
            musicFoldersBody("{\"id\":\"folder:opaque/primary\",\"name\":\"Primary\"}")

        fun musicFoldersBody(folderObject: String) =
            envelope("\"musicFolders\":{\"musicFolder\":[$folderObject]}")

        fun artistsBody() = envelope(
            "\"artists\":{\"index\":[{\"name\":\"O\",\"artist\":[{" +
                "\"id\":\"artist:opaque-A\",\"name\":\"Opaque Artist\"}]}]}"
        )

        fun albumListBody(ids: List<String>, songCount: Int = 1): String {
            val albums = ids.joinToString(",") { id ->
                "{\"id\":\"$id\",\"name\":\"Album $id\",\"artist\":\"Opaque Artist\"," +
                    "\"artistId\":\"artist:opaque-A\",\"coverArt\":\"artwork:$id\"," +
                    "\"duration\":121,\"songCount\":$songCount}"
            }
            return envelope("\"albumList2\":{\"album\":[$albums]}")
        }

        fun albumBody(id: String) = envelope(
            "\"album\":{" +
                "\"id\":\"$id\",\"name\":\"Album $id\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"coverArt\":\"artwork:$id\"," +
                "\"duration\":121,\"song\":[{" +
                "\"id\":\"track:000000000000000000000000000001\"," +
                "\"title\":\"Opaque Track\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"album\":\"Album $id\"," +
                "\"coverArt\":\"artwork:track:000000000000000000000000000001\"," +
                "\"duration\":61,\"discNumber\":1,\"track\":1,\"suffix\":\"flac\"}]}"
        )

        fun numericAlbumListBody() = envelope(
            "\"albumList2\":{\"album\":[{" +
                "\"id\":7,\"name\":\"Numeric Album\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"coverArt\":11," +
                "\"duration\":121,\"songCount\":1}]}"
        )

        fun numericAlbumBody() = envelope(
            "\"album\":{" +
                "\"id\":7,\"name\":\"Numeric Album\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"coverArt\":11,\"duration\":121,\"song\":[{" +
                "\"id\":9,\"title\":\"Numeric Track\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"album\":\"Numeric Album\"," +
                "\"coverArt\":12," +
                "\"duration\":61,\"discNumber\":1,\"track\":1,\"contentType\":\"audio/mpeg\"}]}"
        )

        fun errorBody(code: Int, message: String) =
            "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\"," +
                "\"error\":{\"code\":$code,\"message\":\"$message\"}}}"

        fun envelope(payload: String) =
            "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",$payload}}"
    }
}
