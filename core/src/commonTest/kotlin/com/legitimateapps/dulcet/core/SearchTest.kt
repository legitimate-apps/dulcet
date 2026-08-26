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
    fun sendsIndependentCountsOffsetsAndCopiesOpaqueStructuredResults() = runTest {
        var sent = emptyMap<String, String>()
        val result = ServerSearch(
            SearchEndpointTransport { parameters ->
                sent = parameters
                success(
                    """{"subsonic-response":{"status":"ok","searchResult3":{
                        "artist":[{"id":7,"name":"Atlas Artist","coverArt":70}],
                        "album":[{"id":"album:8","name":"Atlas Album","artist":"Atlas Artist","artistId":7,"year":2024,"duration":121,"coverArt":"art:8"}],
                        "song":[{"id":9,"title":"Atlas Track","artist":"Atlas Artist","artistId":7,"album":"Atlas Album","year":2024,"duration":61,"coverArt":90}]
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
    }
}
