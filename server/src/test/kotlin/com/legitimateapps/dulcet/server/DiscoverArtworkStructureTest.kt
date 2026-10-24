package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Generated 2x2 blue images, never downloaded. Tests serve them only from the loopback fixture. */
internal fun artworkFixture(extension: String): ByteArray =
    DiscoverArtworkStructureTest::class.java.getResource("/discover/artwork/whole.$extension")!!.readBytes()

class DiscoverArtworkStructureTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val path = "/release-group/$id/front-250"
    private val png = artworkFixture("png")

    private fun rejectedAndRetried(body: ByteArray) = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { upstream -> Db.inMemory().use { db -> PoliteHttp().use { http ->
            http.configureOrigin(upstream.baseUrl, 0)
            val client = CoverArtClient(http, DiscoverCache(db), DiscoverSettings(coverArtArchiveUrl = upstream.baseUrl), testingOverride = { true })
            upstream.respond(path, body = body)
            assertFailsWith<UpstreamUnavailable> { client.thumbnail(id) }
            assertEquals(0L, db.read { long("SELECT count(*) FROM discover_cache") }, "Malformed image must not be cached, including negatively")
            assertEquals(1, upstream.requests.size)
            upstream.respond(path, body = png)
            assertContentEquals(png, client.thumbnail(id).bytes, "Later request retries and recovers")
            assertEquals(2, upstream.requests.size)
            assertContentEquals(png, client.thumbnail(id).bytes)
            assertEquals(2, upstream.requests.size, "Recovered whole image is cached")
        } } }
    }

    private fun wholeImage(extension: String, type: String) = runBlocking<Unit> {
        val bytes = artworkFixture(extension)
        FixtureDiscoverUpstream().use { upstream -> Db.inMemory().use { db -> PoliteHttp().use { http ->
            http.configureOrigin(upstream.baseUrl, 0)
            val client = CoverArtClient(http, DiscoverCache(db), DiscoverSettings(coverArtArchiveUrl = upstream.baseUrl), testingOverride = { true })
            upstream.respond(path, body = bytes, headers = mapOf("Content-Type" to "text/plain"))
            val image = client.thumbnail(id)
            assertEquals(type, image.contentType)
            assertFalse(image.placeholder)
            assertContentEquals(bytes, image.bytes, "No decoding or re-encoding")
            assertContentEquals(bytes, client.thumbnail(id).bytes)
            assertEquals(1, upstream.requests.size)
        } } }
    }

    @Test fun wholeJpegPassesThroughAndCaches() = wholeImage("jpg", "image/jpeg")
    @Test fun wholePngPassesThroughAndCaches() = wholeImage("png", "image/png")
    @Test fun wholeGifPassesThroughAndCaches() = wholeImage("gif", "image/gif")
    @Test fun wholeWebpPassesThroughAndCaches() = wholeImage("webp", "image/webp")

    @Test fun jpegBarePrefixIsTemporaryAndRetried() = rejectedAndRetried(artworkFixture("jpg").copyOf(3))
    @Test fun jpegMissingEoiIsTemporaryAndRetried() = rejectedAndRetried(artworkFixture("jpg").dropLast(2).toByteArray())
    @Test fun jpegTrailingBytesAreTemporaryAndRetried() = rejectedAndRetried(artworkFixture("jpg") + byteArrayOf(0))
    @Test fun pngBareSignatureIsTemporaryAndRetried() = rejectedAndRetried(png.copyOf(8))
    @Test fun pngMissingIendIsTemporaryAndRetried() = rejectedAndRetried(png.dropLast(12).toByteArray())
    @Test fun pngIhdrMustBeFirst() = rejectedAndRetried(png.copyOf().apply { "IDAT".toByteArray().copyInto(this, 12) })
    @Test fun pngIendMustBeLast() = rejectedAndRetried(png + byteArrayOf(0))
    @Test fun pngTruncatedChunkCannotHideBehindIend() = rejectedAndRetried(png.copyOf().apply { this[8] = 127 })
    @Test fun pngIendMustHaveZeroLength() = rejectedAndRetried(png.copyOf().apply { this[size - 9] = 1 })
    @Test fun gifBareHeaderIsTemporaryAndRetried() = rejectedAndRetried("GIF89a".toByteArray())
    @Test fun gifMissingTrailerIsTemporaryAndRetried() = rejectedAndRetried(artworkFixture("gif").dropLast(1).toByteArray())
    @Test fun gifTrailerMustBeLast() = rejectedAndRetried(artworkFixture("gif") + byteArrayOf(0))
    @Test fun webpBareHeaderIsTemporaryAndRetried() = rejectedAndRetried("RIFF0000WEBP".toByteArray())
    @Test fun webpTruncatedBodyIsTemporaryAndRetried() = rejectedAndRetried(artworkFixture("webp").dropLast(2).toByteArray())
    @Test fun webpLengthMustMatchBody() = rejectedAndRetried(artworkFixture("webp") + byteArrayOf(0, 0))
    @Test fun webpUnsignedLengthCannotOverflow() = rejectedAndRetried(artworkFixture("webp").copyOf().apply { for (i in 4..7) this[i] = 255.toByte() })

    @Test fun webpEmptyContainerIsNotAnImage() = rejectedAndRetried(
        "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBP".toByteArray())
    @Test fun webpChunkLengthCannotExceedContainer() = rejectedAndRetried(artworkFixture("webp").copyOf().apply {
        for (i in 16..19) this[i] = 255.toByte()
    })
    @Test fun pngMustContainImageData() = rejectedAndRetried(png.copyOf(33) + png.takeLast(12).toByteArray())

    @Test fun oldPrefixOnlyCacheEntryIsRevalidatedBeforeServing() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { upstream -> Db.inMemory().use { db -> PoliteHttp().use { http ->
            val cache = DiscoverCache(db)
            // Simulate a fresh 30-day entry written by the old signature-only validator.
            cache.read(CacheKey(upstream.baseUrl, path, emptyMap()), 30 * DiscoverCache.DAY, { it }) {
                UpstreamResponse(200, emptyMap(), png.copyOf(8))
            }
            http.configureOrigin(upstream.baseUrl, 0)
            upstream.respond(path, body = png)
            val client = CoverArtClient(http, cache, DiscoverSettings(coverArtArchiveUrl = upstream.baseUrl), testingOverride = { true })
            assertContentEquals(png, client.thumbnail(id).bytes)
            assertEquals(1, upstream.requests.size, "Invalid old entry must not wait 30 days")
        } } }
    }
}
