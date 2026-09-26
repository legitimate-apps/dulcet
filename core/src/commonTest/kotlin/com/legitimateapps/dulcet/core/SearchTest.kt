package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SearchTest {
    @Test
    fun serverRowsReplaceLocalRowsInPlaceWhileNewRowsAppendWithoutDuplicates() {
        val overlapId = ProviderItemId(PROVIDER_INSTANCE_ID, "track:opaque/overlap:not-an-integer")
        val localOnlyId = ProviderItemId(PROVIDER_INSTANCE_ID, "album:opaque/local:not-an-integer")
        val serverOnlyId = ProviderItemId(PROVIDER_INSTANCE_ID, "artist:opaque/server:not-an-integer")
        val staleLocal = item(overlapId, SearchResultType.Track, "Stale cached title")
        val localOnly = item(localOnlyId, SearchResultType.Album, "Local only")
        val freshServer = item(overlapId, SearchResultType.Track, "Fresh server title")
        val serverOnly = item(serverOnlyId, SearchResultType.Artist, "Server only")

        val merged = mergeSearchResults(
            localResults = listOf(staleLocal, localOnly, staleLocal),
            serverResults = listOf(freshServer, serverOnly, freshServer),
        )

        assertEquals(listOf(overlapId, localOnlyId, serverOnlyId), merged.map(SearchResultItem::id))
        assertEquals(freshServer, merged[0], "the server row must refresh the local row in place")
        assertEquals(localOnly, merged[1], "a local-only row must not be dropped")
        assertEquals(serverOnly, merged[2], "a server-only row must append")
    }

    @Test
    fun sendsIndependentCountsOffsetsAndCopiesOpaqueStructuredResults() = runTest {
        var sent = emptyMap<String, String>()
        val result = ServerSearch(
            SearchEndpointTransport { parameters ->
                sent = parameters
                success(
                    """{"subsonic-response":{"status":"ok","searchResult3":{
                        "artist":[{"id":7,"name":"Atlas Artist","coverArt":70}],
                        "album":[{"id":"album:8","name":"Atlas Album","artist":"Atlas Artist","artistId":7,"year":2024,"duration":121,"coverArt":"art:8"}],
                        "song":[{"id":9,"title":"Atlas Track","artist":"Atlas Artist","artistId":7,"album":"Atlas Album","year":2024,"duration":61,"discNumber":2,"track":4,"suffix":"flac","coverArt":90}]
                    }}}""".trimIndent(),
                )
            },
        ).search(request(artistCount = 1, artistOffset = 2, albumCount = 3, albumOffset = 4, trackCount = 5, trackOffset = 6))

        val page = assertIs<SearchPageResult.Loaded>(result).page
        assertEquals(
            mapOf(
                "query" to "atlas",
                "artistCount" to "1",
                "artistOffset" to "2",
                "albumCount" to "3",
                "albumOffset" to "4",
                "songCount" to "5",
                "songOffset" to "6",
            ),
            sent,
        )
        assertEquals(listOf("9", "album:8", "7"), page.results.map { it.id.rawId })
        assertTrue(page.results.all { it.id.providerInstanceId == PROVIDER_INSTANCE_ID })
        val track = page.results.first()
        assertEquals(SearchResultType.Track, track.type)
        assertEquals(61.seconds, track.duration)
        assertEquals(2, track.discNumber)
        assertEquals(4, track.trackNumber)
        assertEquals(AudioContainer.Flac, track.sourceContainer)
        assertNull(track.mediaSourceId)
        assertEquals("90", track.artworkKey)
        assertEquals(
            Credit(CreditRole.Artist, "Atlas Artist", ProviderItemId(PROVIDER_INSTANCE_ID, "7")),
            track.credits.single(),
        )
        assertEquals(1, page.artistResultCount)
        assertEquals(1, page.albumResultCount)
        assertEquals(1, page.trackResultCount)
        assertTrue(page.artistHasMore)
        assertFalse(page.albumHasMore)
        assertFalse(page.trackHasMore)
    }

    @Test
    fun aFullPageContainingADuplicateStillReportsMorePages() = runTest {
        // `hasMore` asks whether the server FILLED the page we requested. De-duplication answers a
        // different question -- what is worth displaying -- and deriving one from the other made a
        // full page containing a repeat look short, and a short page means "last page".
        //
        // Synthetic: a server repeating a row inside one response is ASSUMED (spec 18.1), which is
        // why the parser de-duplicates at all. When it happens it must not silently truncate the
        // results; the person would just stop being offered pages that exist.
        val duplicatedArtist = """{"id":"artist:opaque/7","name":"Atlas Artist"}"""
        val result = ServerSearch(
            SearchEndpointTransport {
                success(
                    """{"subsonic-response":{"status":"ok","searchResult3":{
                        "artist":[$duplicatedArtist,$duplicatedArtist],
                        "album":[],
                        "song":[]
                    }}}""".trimIndent(),
                )
            },
        ).search(request(artistCount = 2, albumCount = 0, trackCount = 0))

        val page = assertIs<SearchPageResult.Loaded>(result).page
        assertEquals(
            1,
            page.artistResultCount,
            "the duplicate must still be collapsed for display",
        )
        assertTrue(
            page.artistHasMore,
            "the server returned the 2 rows it was asked for, so a further page must be offered " +
                "even though the two collapse to one displayable result",
        )
    }

    @Test
    fun consumedRowsReachLaterUniqueResultsForAllKinds() = runTest {
        // Defensive synthetic intra-page duplication, not evidence of real server behavior.
        // Cross-page overlap alone does not reduce this parser's per-response distinct count.
        //
        // The three kinds are deliberately unlike one another: a different page size, and for every
        // pair of kinds at least one page where their raw row count, their de-duplicated count and
        // their hasMore each differ. A mapping that crossed two kinds' values would otherwise read
        // back the same numbers and pass. hasMore runs artist T,F,F / album T,T,F / song F,F,F.
        val rows = mapOf(
            // 20 a page: raw 20, 3, 0 -- distinct 1, 1, 0. The first page is full of one artist.
            "artist" to List(20) { "A" } + List(3) { "B" },
            // 15 a page: raw 15, 15, 2 -- distinct 2, 3, 1.
            "album" to List(8) { "A" } + List(7) { "B" } +
                List(5) { "C" } + List(5) { "D" } + List(5) { "E" } + List(2) { "F" },
            // 10 a page: raw 7, 0, 0 -- distinct 4, 0, 0. The first page is already short.
            "song" to List(4) { "A" } + listOf("B", "C", "D"),
        )
        val offsets = mutableListOf<List<Int>>()
        val search = ServerSearch(SearchEndpointTransport { parameters ->
            offsets += listOf("artistOffset", "albumOffset", "songOffset").map {
                parameters.getValue(it).toInt()
            }
            fun page(kind: String, titleKey: String): String = rows.getValue(kind)
                .drop(parameters.getValue("${kind}Offset").toInt())
                .take(parameters.getValue("${kind}Count").toInt())
                .joinToString(",") { """{"id":"$kind-$it","$titleKey":"$it"}""" }
            success(envelope("""{
                "artist":[${page("artist", "name")}],
                "album":[${page("album", "name")}],
                "song":[${page("song", "title")}]
            }"""))
        })
        val consumed = listOf(listOf(20, 15, 7), listOf(3, 15, 0), listOf(0, 2, 0))
        val distinct = listOf(listOf(1, 2, 4), listOf(1, 3, 0), listOf(0, 1, 0))
        val hasMore = listOf(listOf(true, true, false), listOf(false, true, false), listOf(false, false, false))
        var next = request(artistCount = 20, albumCount = 15, trackCount = 10)
        var visible = emptyList<SearchResultItem>()
        repeat(3) { index ->
            val page = assertIs<SearchPageResult.Loaded>(search.search(next)).page
            visible = mergeSearchResults(visible, page.results)
            assertEquals(consumed[index], listOf(
                page.artistConsumedRowCount, page.albumConsumedRowCount, page.trackConsumedRowCount,
            ), "consumed rows, page ${index + 1}")
            assertEquals(distinct[index], listOf(
                page.artistResultCount, page.albumResultCount, page.trackResultCount,
            ), "displayable results, page ${index + 1}")
            assertEquals(hasMore[index], listOf(
                page.artistHasMore, page.albumHasMore, page.trackHasMore,
            ), "hasMore, page ${index + 1}")
            next = next.copy(
                artistOffset = next.artistOffset + page.artistConsumedRowCount,
                albumOffset = next.albumOffset + page.albumConsumedRowCount,
                trackOffset = next.trackOffset + page.trackConsumedRowCount,
            )
        }
        assertEquals(listOf(listOf(0, 0, 0), listOf(20, 15, 7), listOf(23, 30, 7)), offsets)
        val expected = mapOf(
            SearchResultType.Artist to listOf("A", "B"),
            SearchResultType.Album to listOf("A", "B", "C", "D", "E", "F"),
            SearchResultType.Track to listOf("A", "B", "C", "D"),
        )
        assertEquals(SearchResultType.entries.toSet(), expected.keys, "every kind is checked")
        for ((kind, titles) in expected) {
            assertEquals(titles, visible.filter { it.type == kind }.map { it.title }, "visible $kind")
        }
    }

    @Test
    fun ranksExactPrefixWordStartSubstringThenTypeWithCompatibilityNormalization() = runTest {
        val body = """{"subsonic-response":{"status":"ok","searchResult3":{
            "artist":[
                {"id":"artist-substring","name":"The Café Atlas"},
                {"id":"artist-exact","name":"ATLAS"}
            ],
            "album":[
                {"id":"album-word","name":"An Atlas Record"},
                {"id":"album-exact","name":"Ａｔｌａｓ"}
            ],
            "song":[
                {"id":"track-prefix","title":"Atlasology"},
                {"id":"track-exact","title":"átlas"}
            ]
        }}}""".trimIndent()
        val page = assertIs<SearchPageResult.Loaded>(
            ServerSearch(SearchEndpointTransport { success(body) }).search(request()),
        ).page

        assertEquals(
            listOf(
                "track-exact",
                "album-exact",
                "artist-exact",
                "track-prefix",
                "album-word",
                "artist-substring",
            ),
            page.results.map { it.id.rawId },
        )
    }

    @Test
    fun cancellationStopsTheInFlightTransport() = runTest {
        val entered = CompletableDeferred<Unit>()
        var transportCancelled = false
        val operation = async {
            ServerSearch(
                SearchEndpointTransport {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        transportCancelled = true
                    }
                },
            ).search(request())
        }
        entered.await()
        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertTrue(transportCancelled)
    }

    @Test
    fun rejectsObjectArrayNullMissingAndBlankIdsAnywhere() = runTest {
        val malformed = listOf("{}", "[]", "null", "\"\"", "\"   \"")
        malformed.forEach { id ->
            val bodies = listOf(
                """{"artist":[{"id":$id,"name":"Artist"}]}""",
                """{"album":[{"id":$id,"name":"Album"}]}""",
                """{"song":[{"id":$id,"title":"Track"}]}""",
            )
            bodies.forEach { resultBody ->
                val result = ServerSearch(
                    SearchEndpointTransport { success(envelope(resultBody)) },
                ).search(request())
                assertEquals(
                    DomainError.Protocol.MalformedEnvelope,
                    assertIs<SearchPageResult.Failed>(result).error,
                )
            }
        }
        val missing = ServerSearch(
            SearchEndpointTransport {
                success(envelope("""{"album":[{"name":"Album"}]}"""))
            },
        ).search(request())
        assertEquals(DomainError.Protocol.MalformedEnvelope, assertIs<SearchPageResult.Failed>(missing).error)
    }

    @Test
    fun everyFailurePathDiscardsRawQueriesCredentialsAndServerText() = runTest {
        val canaries = listOf(
            "search-user-canary",
            "search-token-canary",
            "search-salt-canary",
            "search-server-message-canary",
        )
        val request = request().copy(username = canaries[0], password = canaries[1])
        val failures = listOf<SearchEndpointTransport>(
            SearchEndpointTransport {
                SearchEndpointResponse(200, "not-json-${canaries[3]}", REDACTED_URL)
            },
            SearchEndpointTransport {
                SearchEndpointResponse(
                    200,
                    """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"${canaries[3]}"}}}""",
                    REDACTED_URL,
                )
            },
            SearchEndpointTransport {
                throw IllegalStateException(
                    "https://music.example.invalid/rest/search3.view?u=${canaries[0]}&t=${canaries[1]}&s=${canaries[2]}",
                )
            },
        ).map { transport ->
            assertIs<SearchPageResult.Failed>(ServerSearch(transport).search(request)).error
        }

        val rendered = failures.flatMap { listOf(it.toString(), it.toDiagnosticJson()) } + request.toString()
        canaries.forEach { canary -> assertTrue(rendered.none { it.contains(canary) }, canary) }
        assertFalse(rendered.any { it.contains("?u=") || it.contains("&t=") || it.contains("&s=") })
    }

    private companion object {
        const val PROVIDER_INSTANCE_ID = "provider-search-fixture"
        const val REDACTED_URL = "https://music.example.invalid/rest/search3.view?<redacted>"

        fun request(
            artistCount: Int = 20,
            artistOffset: Int = 0,
            albumCount: Int = 20,
            albumOffset: Int = 0,
            trackCount: Int = 20,
            trackOffset: Int = 0,
        ) = SearchPageRequest(
            providerInstanceId = PROVIDER_INSTANCE_ID,
            normalizedBaseUrl = "https://music.example.invalid",
            username = "listener",
            password = "credential",
            allowLocalHttp = false,
            query = "atlas",
            artistCount = artistCount,
            artistOffset = artistOffset,
            albumCount = albumCount,
            albumOffset = albumOffset,
            trackCount = trackCount,
            trackOffset = trackOffset,
        )

        fun success(body: String) = SearchEndpointResponse(200, body, REDACTED_URL)

        fun envelope(searchResult: String) =
            """{"subsonic-response":{"status":"ok","searchResult3":$searchResult}}"""

        fun item(id: ProviderItemId, type: SearchResultType, title: String) = SearchResultItem(
            id = id,
            type = type,
            title = title,
            credits = emptyList(),
            albumTitle = null,
            year = null,
            duration = null,
            discNumber = null,
            trackNumber = null,
            sourceContainer = null,
            mediaSourceId = null,
            artworkKey = null,
        )
    }
}
