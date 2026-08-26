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
        val recordedJpegPrefix = RecordedArtworkResponses.VALID_JPEG_PREFIX_HEX.recordedHexBytes()
        val transport = ArtworkEndpointTransport { parameters ->
            requests += parameters
            ArtworkEndpointResponse(
                statusCode = 200,
                body = recordedJpegPrefix,
                redactedUrl = REDACTED_URL,
            )
        }
        val key = "001.0/song-scoped:opaque/key"

        ArtworkSizeBucket.entries.forEach { bucket ->
            val loaded = assertIs<ArtworkFetchResult.Loaded>(
                ArtworkFetcher(transport).fetch(request(key, bucket)),
            )
            assertContentEquals(recordedJpegPrefix, loaded.bytes)
        }

        assertEquals(
            listOf("96", "256", "512", "1024"),
            requests.map { it.getValue("size") },
        )
        assertTrue(requests.all { it.getValue("id") == key })
        assertEquals(64, recordedJpegPrefix.size)
        assertEquals(3_294, RecordedArtworkResponses.VALID_JPEG_BODY_BYTE_COUNT)
    }

    @Test
    fun mapsRecordedJsonAndXmlCode70EnvelopesToUnavailableAtHttp200() = runTest {
        val recordedBodies = listOf(
            RecordedArtworkResponses.MISSING_JSON.encodeToByteArray(),
            RecordedArtworkResponses.MISSING_XML.encodeToByteArray(),
        )

        assertEquals(185, recordedBodies[0].size)
        assertEquals(232, recordedBodies[1].size)
        listOf(200, 500).forEach { statusCode ->
            recordedBodies.forEach { body ->
                val outcome = ArtworkFetcher(ArtworkEndpointTransport {
                    ArtworkEndpointResponse(statusCode, body, REDACTED_URL)
                }).fetch(request("missing:opaque", ArtworkSizeBucket.Px96))
                assertEquals(ArtworkFetchResult.Unavailable, outcome)
            }
        }

        val bomAndWhitespace = byteArrayOf(0x20, 0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x0A) +
            recordedBodies[1]
        val adornedOutcome = ArtworkFetcher(ArtworkEndpointTransport {
            ArtworkEndpointResponse(200, bomAndWhitespace, REDACTED_URL)
        }).fetch(request("missing:opaque", ArtworkSizeBucket.Px96))
        assertEquals(ArtworkFetchResult.Unavailable, adornedOutcome)
    }

    @Test
    fun rejectsEverythingThatIsNeitherAnErrorEnvelopeNorARecognizedImage() = runTest {
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

        listOf(
            "not an image".encodeToByteArray(),
            "<html>proxy error</html>".encodeToByteArray(),
            byteArrayOf(0x01, 0x23, 0x45),
        ).forEach { body ->
            val rejected = assertIs<ArtworkFetchResult.Failed>(
                ArtworkFetcher(ArtworkEndpointTransport {
                    ArtworkEndpointResponse(200, body, REDACTED_URL)
                }).fetch(request("invalid:opaque", ArtworkSizeBucket.Px256)),
            )
            assertEquals(DomainError.Protocol.MalformedEnvelope, rejected.error)
        }
    }

    @Test
    fun positiveArtworkSignatureTableRejectsSimilarNonImages() = runTest {
        val recognized = listOf(
            "ffd8ff".recordedHexBytes(),
            "89504e470d0a1a0a".recordedHexBytes(),
            "474946383761".recordedHexBytes(),
            "474946383961".recordedHexBytes(),
            "524946460000000057454250".recordedHexBytes(),
            "49492a0008000000".recordedHexBytes(),
            "4d4d002a00000008".recordedHexBytes(),
            "424d000000000000000000000000".recordedHexBytes(),
            "000001000100".recordedHexBytes(),
            "000000186674797061766966".recordedHexBytes(),
        )
        recognized.forEachIndexed { index, body ->
            val loaded = assertIs<ArtworkFetchResult.Loaded>(
                ArtworkFetcher(ArtworkEndpointTransport {
                    ArtworkEndpointResponse(200, body, REDACTED_URL)
                }).fetch(request("signature:$index", ArtworkSizeBucket.Px256)),
            )
            assertContentEquals(body, loaded.bytes)
        }

        listOf(
            "524946460000000057415645".recordedHexBytes(),
            "00000018667479706d703432".recordedHexBytes(),
            "49492a00".recordedHexBytes(),
            "424d".recordedHexBytes(),
            "00000100".recordedHexBytes(),
        ).forEachIndexed { index, body ->
            val rejected = assertIs<ArtworkFetchResult.Failed>(
                ArtworkFetcher(ArtworkEndpointTransport {
                    ArtworkEndpointResponse(200, body, REDACTED_URL)
                }).fetch(request("not-image:$index", ArtworkSizeBucket.Px256)),
            )
            assertEquals(DomainError.Protocol.MalformedEnvelope, rejected.error)
        }
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
                    errorBody(0, canaries[3]).encodeToByteArray(),
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
