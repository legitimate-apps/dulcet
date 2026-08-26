package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtworkFetchTest {
    @Test
    fun sendsOpaqueArtworkKeyUnchangedAndUsesOnlyFixedSizeBuckets() = runTest {
        val requests = mutableListOf<Map<String, String>>()
        val transport = ArtworkEndpointTransport { parameters ->
            requests += parameters
            ArtworkEndpointResponse(
                statusCode = 200,
                body = byteArrayOf(0x01, 0x23, 0x45),
                redactedUrl = REDACTED_URL,
            )
        }
        val key = "001.0/song-scoped:opaque/key"

        ArtworkSizeBucket.entries.forEach { bucket ->
            val loaded = assertIs<ArtworkFetchResult.Loaded>(
                ArtworkFetcher(transport).fetch(request(key, bucket)),
            )
            assertContentEquals(byteArrayOf(0x01, 0x23, 0x45), loaded.bytes)
        }

        assertEquals(
            listOf("96", "256", "512", "1024"),
            requests.map { it.getValue("size") },
        )
        assertTrue(requests.all { it.getValue("id") == key })
    }

    @Test
    fun mapsAbsentAndMalformedArtworkToClosedFallbackOutcomes() = runTest {
        val unavailable = ArtworkFetcher(ArtworkEndpointTransport {
            ArtworkEndpointResponse(404, byteArrayOf(), REDACTED_URL)
        }).fetch(request("missing", ArtworkSizeBucket.Px96))
        assertEquals(ArtworkFetchResult.Unavailable, unavailable)

        val malformed = assertIs<ArtworkFetchResult.Failed>(
            ArtworkFetcher(ArtworkEndpointTransport {
                ArtworkEndpointResponse(200, byteArrayOf(), REDACTED_URL)
            }).fetch(request("empty", ArtworkSizeBucket.Px256)),
        )
        assertEquals(DomainError.Protocol.MalformedEnvelope, malformed.error)
    }

    @Test
    fun cancellationStopsTheArtworkRequest() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val operation = async {
            ArtworkFetcher(ArtworkEndpointTransport {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }).fetch(request("artwork:blocked", ArtworkSizeBucket.Px512))
        }

        entered.await()
        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertTrue(cancelled)
    }

    @Test
    fun everyArtworkErrorPathDiscardsRawQueryCredentialsAndServerText() = runTest {
        val canaries = listOf(
            "artwork-user-canary",
            "artwork-token-canary",
            "artwork-salt-canary",
            "artwork-server-message-canary",
        )
        val request = request("artwork:opaque", ArtworkSizeBucket.Px1024).copy(
            username = canaries[0],
            password = canaries[1],
        )
        val failures = listOf<ArtworkEndpointTransport>(
            ArtworkEndpointTransport {
                ArtworkEndpointResponse(
                    500,
                    errorBody(70, canaries[3]).encodeToByteArray(),
                    REDACTED_URL,
                )
            },
            ArtworkEndpointTransport {
                throw IllegalStateException(
                    "https://music.example.invalid/rest/getCoverArt.view" +
                        "?u=${canaries[0]}&t=${canaries[1]}&s=${canaries[2]}",
                )
            },
        ).map { transport ->
            assertIs<ArtworkFetchResult.Failed>(ArtworkFetcher(transport).fetch(request)).error
        }

        val rendered = failures.flatMap { error ->
            listOf(error.toString(), error.toDiagnosticJson())
        } + request.toString()
        canaries.forEach { canary ->
            assertTrue(rendered.none { it.contains(canary) }, canary)
        }
        assertTrue(rendered.none { it.contains("?u=") || it.contains("&t=") || it.contains("&s=") })
    }

    private companion object {
        const val REDACTED_URL =
            "https://music.example.invalid/rest/getCoverArt.view?<redacted>"

        fun request(key: String, bucket: ArtworkSizeBucket) = ArtworkFetchRequest(
            providerInstanceId = "provider-instance-fixture",
            artworkKey = key,
            sizeBucket = bucket,
            normalizedBaseUrl = "https://music.example.invalid",
            username = "listener",
            password = "fixture-password",
            allowLocalHttp = false,
        )

        fun errorBody(code: Int, message: String) =
            "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\"," +
                "\"error\":{\"code\":$code,\"message\":\"$message\"}}}"
    }
}
