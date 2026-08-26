package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        assertEquals(listOf("album:opaque-A", "album:opaque-B", "album:opaque-C"), loaded.albums.map { it.id.rawId })
        assertEquals(listOf("0", "2"), transport.albumListOffsets)
        assertTrue(loaded.albums.all { it.id.providerInstanceId == PROVIDER_INSTANCE_ID })

        val first = loaded.albums.first()
        assertEquals(121.seconds, first.duration)
        assertNull(first.mediaSourceId)
        assertEquals(
            Credit(CreditRole.AlbumArtist, "Opaque Artist", ProviderItemId(PROVIDER_INSTANCE_ID, "artist:opaque-A")),
            first.credits.single(),
        )
        val track = first.tracks.single()
        assertEquals("track:000000000000000000000000000001", track.id.rawId)
        assertEquals(61.seconds, track.duration)
        assertEquals(
            Credit(CreditRole.Artist, "Opaque Artist", ProviderItemId(PROVIDER_INSTANCE_ID, "artist:opaque-A")),
            track.credits.single(),
        )
        assertNull(track.mediaSourceId)
    }

    @Test
    fun albumDetailFanOutNeverExceedsConfiguredBound() = runTest {
        val transport = ConcurrencyMeasuringTransport(albumCount = 9)
        val result = LibraryBrowser(
            transport = transport,
            albumPageSize = 20,
            albumConcurrency = 4,
        ).browse(fixtureRequest())

        assertIs<LibraryBrowseResult.Loaded>(result)
        assertEquals(4, transport.maximumInFlight)
        assertEquals(9, transport.albumRequests)
    }

    @Test
    fun cancellationStopsChildRequests() = runTest {
        val enteredAlbumRequest = CompletableDeferred<Unit>()
        var childRequestCancelled = false
        val transport = object : LibraryEndpointTransport {
            override suspend fun request(
                endpoint: String,
                parameters: Map<String, String>,
            ): LibraryEndpointResponse = when (endpoint) {
                "getMusicFolders" -> success(musicFoldersBody())
                "getArtists" -> success(artistsBody())
                "getAlbumList2" -> success(albumListBody(listOf("album:blocked")))
                "getAlbum" -> {
                    enteredAlbumRequest.complete(Unit)
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
        enteredAlbumRequest.await()
        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertTrue(childRequestCancelled)
    }

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
        ).map { transport ->
            assertIs<LibraryBrowseResult.Failed>(LibraryBrowser(transport).browse(request)).error
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

        val loaded = assertIs<LibraryBrowseResult.Loaded>(
            LibraryBrowser(transport, albumPageSize = 20).browse(fixtureRequest()),
        ).snapshot

        assertEquals("7", loaded.albums.single().id.rawId)
        assertEquals("9", loaded.albums.single().tracks.single().id.rawId)
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
                check(endpoint == "getMusicFolders")
                success(musicFoldersBody(folderObject))
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

    private class ConcurrencyMeasuringTransport(private val albumCount: Int) : LibraryEndpointTransport {
        var albumRequests = 0
            private set
        var maximumInFlight = 0
            private set
        private var inFlight = 0

        override suspend fun request(
            endpoint: String,
            parameters: Map<String, String>,
        ): LibraryEndpointResponse = when (endpoint) {
            "getMusicFolders" -> success(musicFoldersBody())
            "getArtists" -> success(artistsBody())
            "getAlbumList2" -> success(
                albumListBody((0 until albumCount).map { "album:bounded-$it" }),
            )
            "getAlbum" -> {
                albumRequests += 1
                inFlight += 1
                maximumInFlight = maxOf(maximumInFlight, inFlight)
                delay(10)
                inFlight -= 1
                success(albumBody(parameters.getValue("id")))
            }
            else -> error("unexpected endpoint")
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

        fun albumListBody(ids: List<String>): String {
            val albums = ids.joinToString(",") { id ->
                "{\"id\":\"$id\",\"name\":\"Album $id\",\"artist\":\"Opaque Artist\"," +
                    "\"artistId\":\"artist:opaque-A\",\"duration\":121,\"songCount\":1}"
            }
            return envelope("\"albumList2\":{\"album\":[$albums]}")
        }

        fun albumBody(id: String) = envelope(
            "\"album\":{" +
                "\"id\":\"$id\",\"name\":\"Album $id\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"duration\":121,\"song\":[{" +
                "\"id\":\"track:000000000000000000000000000001\"," +
                "\"title\":\"Opaque Track\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"album\":\"Album $id\"," +
                "\"duration\":61,\"discNumber\":1,\"track\":1}]}"
        )

        fun numericAlbumListBody() = envelope(
            "\"albumList2\":{\"album\":[{" +
                "\"id\":7,\"name\":\"Numeric Album\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"duration\":121,\"songCount\":1}]}"
        )

        fun numericAlbumBody() = envelope(
            "\"album\":{" +
                "\"id\":7,\"name\":\"Numeric Album\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"duration\":121,\"song\":[{" +
                "\"id\":9,\"title\":\"Numeric Track\",\"artist\":\"Opaque Artist\"," +
                "\"artistId\":\"artist:opaque-A\",\"album\":\"Numeric Album\"," +
                "\"duration\":61,\"discNumber\":1,\"track\":1}]}"
        )

        fun errorBody(code: Int, message: String) =
            "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\"," +
                "\"error\":{\"code\":$code,\"message\":\"$message\"}}}"

        fun envelope(payload: String) =
            "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",$payload}}"
    }
}
