package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

internal const val DISCOVER_MBID = "11111111-1111-4111-8111-111111111111"
internal fun discoverEnvelope(child: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1",$child}}"""
internal val discoverItemJson = """{"id":"opaque:%/id","kind":"song","title":"Studio","wishable":true,"externalIds":{"musicbrainzRecordingId":"$DISCOVER_MBID"},"availability":{"status":"partlyHeld","completeness":"unknown","libraryItems":{"item":[{"id":"local:%/id","type":"song"}]}},"links":{"link":[]}}"""
internal fun discoverPageJson(rows: String = discoverItemJson, extra: String = "") = """{"item":[$rows],"ageSeconds":120,"stale":true$extra}"""
internal fun binaryDiscoverResponse(bytes: ByteArray, status: Int = 200, retry: String? = null) = AuthenticatedEndpointResponse(
    status, bytes, "https://music.invalid/rest/art?<redacted>",
    AuthenticatedEndpointResponseHeaders("image/jpeg", null, retry, null, null),
    RequestTrace.observed("getDulcetDiscoverCoverArt", "GET", "https://music.invalid/rest/art?<redacted>",
        AuthenticationLocation.Query, emptySet(), emptySet(), emptySet(), "1.16.1", null))

internal class DiscoverWireFixture : LibraryEndpointTransport {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    var json: (String, Map<String, String>) -> String = { _, _ -> discoverEnvelope("\"dulcetDiscover\":" + discoverPageJson()) }
    var image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)
    var cap = 0
    var imageStatus = 200
    var retryAfter: String? = null
    override suspend fun request(endpoint: String, parameters: Map<String, String>): LibraryEndpointResponse {
        requests += endpoint to parameters
        return LibraryEndpointResponse(200, json(endpoint, parameters), "https://music.invalid?<redacted>")
    }
    override suspend fun requestBinary(endpoint: String, parameters: Map<String, String>, maxBodyBytes: Int): AuthenticatedEndpointResponse {
        requests += endpoint to parameters
        cap = maxBodyBytes
        return binaryDiscoverResponse(image, imageStatus, retryAfter)
    }
}

class RestDiscoverTransportTest {
    @Test fun binaryRateLimitUsesStatusEvenWithFailureEnvelope() = readerTest { env ->
        val wire = DiscoverWireFixture().apply {
            imageStatus = 429; retryAfter = "120"
            image = "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":50}}}".encodeToByteArray()
        }
        val failure = assertIs<DiscoverResult.Failed>(RestDiscoverTransport(env.reader(transport=wire), {1000L}).artwork("id")).error
        assertEquals("rate-limited", failure.code)
        assertEquals(121000L, failure.retryAtMillis)
    }
    @Test fun retryAfterAcceptsDateDeltaAndFallback() {
        val now = kotlin.time.Instant.parse("2026-10-22T12:00:00Z").toEpochMilliseconds()
        assertEquals(now+120000, discoverRetryAt("120", now))
        assertEquals(now+120000, discoverRetryAt("Thu, 22 Oct 2026 12:02:00 GMT", now))
        assertEquals(now+60000, discoverRetryAt("invalid", now))
    }

    @Test fun mapsAllRestMethodsAndOpaqueIds() = readerTest { env ->
        val wire = DiscoverWireFixture()
        val transport = RestDiscoverTransport(env.reader(transport = wire), { 1000L })
        val value = assertIs<DiscoverResult.Value<DiscoverPage>>(transport.search(DiscoverKind.Song, "A & B", "opaque:+/?", 20)).value
        assertEquals(DISCOVER_MBID, value.items.single().target?.mbid)
        assertEquals("opaque:%/id", value.items.single().id)
        assertEquals("server:reader", value.items.single().availability.libraryItems.single().providerInstanceId)
        assertEquals(mapOf("kind" to "song", "query" to "A & B", "cursor" to "opaque:+/?", "size" to "20"), wire.requests.single().second)
        wire.json = { _, _ -> discoverEnvelope("\"dulcetDiscoverArtist\":{\"item\":$discoverItemJson,\"albums\":{\"album\":[]}}") }
        assertIs<DiscoverResult.Value<DiscoverArtist>>(transport.artist(DISCOVER_MBID))
        wire.json = { _, _ -> discoverEnvelope("\"dulcetDiscover\":{\"album\":[]}") }
        assertIs<DiscoverResult.Value<DiscoverPage>>(transport.artistAlbums(DISCOVER_MBID, null))
        wire.json = { _, _ -> discoverEnvelope("\"dulcetDiscoverAlbum\":{\"item\":$discoverItemJson,\"editions\":{\"edition\":[]},\"tracks\":{\"song\":[]}}") }
        assertIs<DiscoverResult.Value<DiscoverAlbum>>(transport.album(DISCOVER_MBID, "edition"))
        wire.json = { _, _ -> discoverEnvelope("\"dulcetDiscover\":{\"song\":[]}") }
        assertIs<DiscoverResult.Value<DiscoverPage>>(transport.albumTracks(DISCOVER_MBID, "edition", null))
        assertEquals(listOf("searchDulcetDiscover", "getDulcetDiscoverArtist", "getDulcetDiscoverArtistAlbums", "getDulcetDiscoverAlbum", "getDulcetDiscoverAlbumTracks"), wire.requests.map { it.first })
    }
    @Test fun knownUnknownCompletenessIsNotHoldingStatus() = readerTest { env ->
        val item = assertIs<DiscoverResult.Value<DiscoverPage>>(RestDiscoverTransport(env.reader(transport = DiscoverWireFixture())).search(DiscoverKind.Song, "x", null)).value.items.single()
        assertEquals("partlyHeld", item.availability.status)
        assertEquals("unknown", item.availability.completeness)
    }
    @Test fun http200FailureAndCursorReasonNeverBecomeEmpty() = readerTest { env ->
        val wire = DiscoverWireFixture()
        val transport = RestDiscoverTransport(env.reader(transport = wire))
        for (code in listOf(0, 70)) {
            wire.json = { _, _ -> """{"subsonic-response":{"status":"failed","error":{"code":$code,"reason":"cursor-expired","message":"canary"}}}""" }
            assertEquals("cursor-expired", assertIs<DiscoverResult.Failed>(transport.search(DiscoverKind.Song, "x", "cursor")).error.code)
        }
        wire.json = { _, _ -> discoverEnvelope("\"dulcetDiscover\":{}") }
        assertEquals("invalid-response", assertIs<DiscoverResult.Failed>(transport.search(DiscoverKind.Song, "x", null)).error.code)
    }
    @Test fun binaryErrorsAndWrongMagicAreNotArtwork() = readerTest { env ->
        val wire = DiscoverWireFixture()
        val transport = RestDiscoverTransport(env.reader(transport = wire))
        for (bad in listOf(byteArrayOf(1,2,3), "\uFEFF  <subsonic-response status=\"failed\"><error code=\"50\"/></subsonic-response>".encodeToByteArray(),
            "\uFEFF {\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":70}}}".encodeToByteArray(), ByteArray(5_242_881))) {
            wire.image = bad
            assertIs<DiscoverResult.Failed>(transport.artwork("opaque"))
        }
        for (good in listOf(byteArrayOf(-1,-40,-1), byteArrayOf(-119,80,78,71,13,10,26,10), "GIF89a".encodeToByteArray(), "RIFF0000WEBP".encodeToByteArray())) {
            wire.image = good
            assertContentEquals(good, assertIs<DiscoverResult.Value<ByteArray>>(transport.artwork("opaque")).value)
        }
        wire.image = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()
        assertEquals("missing-art", assertIs<DiscoverResult.Failed>(transport.artwork("opaque")).error.code)
    }
    @Test fun binaryRequestSharesReaderAdmissionAndPreservesBytes() = readerTest { env ->
        val wire = DiscoverWireFixture()
        val reader = env.reader(config = LibraryReaderConfig(serverConcurrency = 1, lookAheadInFlight = 1), transport = wire)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { reader.withOneSlot { held.complete(Unit); release.await() } }
        held.await()
        val waiting = async { runCatching { reader.sendBinary("getDulcetDiscoverCoverArt", mapOf("id" to "opaque"), 5_242_880) } }
        runCurrent()
        reader.setOnline(false)
        release.complete(Unit)
        holder.join()
        assertIs<ReaderSendRefused>(waiting.await().exceptionOrNull())
        assertTrue(wire.requests.isEmpty())
        val fresh = env.reader(transport = wire)
        assertContentEquals(wire.image, fresh.sendBinary("getDulcetDiscoverCoverArt", mapOf("id" to "opaque"), 5_242_880).body)
        assertEquals(1, wire.requests.size)
        assertEquals(5_242_880, wire.cap)
    }
}
