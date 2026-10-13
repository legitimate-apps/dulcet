package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.lyrics.Lrc
import com.legitimateapps.dulcet.server.lyrics.LrcLibProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in for LRCLIB's /api/get. Tests never call the real service. */
class FakeLrcLib : AutoCloseable {
    val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(ArrayList())
    @Volatile var status = 200
    @Volatile var body = """{"id":1,"trackName":"Song","artistName":"Band","instrumental":false,"plainLyrics":"la la","syncedLyrics":"[00:01.00]la\n[00:03.50]la la"}"""
    @Volatile var entered: java.util.concurrent.CountDownLatch? = null
    @Volatile var release: java.util.concurrent.CountDownLatch? = null
    @Volatile var retryAfter: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/get") { exchange ->
            entered?.countDown()
            release?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            requests.add(exchange.requestURI.rawQuery to exchange.requestHeaders.getFirst("User-Agent"))
            retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
            val bytes = if (status == 200) body.toByteArray() else """{"code":$status}""".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

class LyricsTest {
    private val fake = FakeLrcLib()
    private var now = 1_000_000L
    private val provider = LrcLibProvider(fake.url, USER_AGENT, minGapMillis = 0, clock = { now })
    private val ctx = Fixtures.context(createTempDirectory("dulcet-lyrics"), provider)

    @AfterTest fun stop() = fake.close()

    private fun track(name: String, tags: Map<String, String>): String {
        Fixtures.track(ctx.config.musicFolder.resolve("L/$name.mp3"), tags = tags)
        ctx.scanner.scan()
        return ctx.db.read { string("SELECT id FROM track WHERE path = ?", "L/$name.mp3")!! }
    }

    @Test
    fun parsesLrcWithOffsetMultipleStampsAndMetadata() {
        val parsed = Lrc.parse("﻿[ar:Band]\n[offset:-300]\n[00:01.5]one\n[00:10.00][00:20.00]chorus\n[01:02.345]three\n", "eng", "t")!!
        assertTrue(parsed.synced)
        assertEquals(-300, parsed.offsetMillis)
        assertEquals(listOf(1500L, 10000L, 20000L, 62345L), parsed.lines.map { it.startMillis })
        assertEquals("chorus", parsed.lines[2].text)
        val plain = Lrc.parse("\nfirst\nsecond\n\n", "xxx", "t")!!
        assertEquals(false, plain.synced)
        assertEquals(listOf("first", "second"), plain.lines.map { it.text })
        assertNull(Lrc.parse("[ar:Only metadata]\n", "xxx", "t"))
    }

    @Test
    fun sidecarAndEmbeddedLyricsWinWithoutAskingLrcLib() {
        val id = track("01 - Local", mapOf("title" to "Local", "artist" to "Band", "album" to "Rec"))
        Files.writeString(ctx.config.musicFolder.resolve("L/01 - Local.lrc"), "[00:02.00]from the sidecar")
        ctx.scanner.scan()
        val lyrics = ctx.lyrics.forTrack(id)!!
        assertEquals("from the sidecar", lyrics.single().lines.single().text)
        assertEquals(0, fake.requests.size, "local lyrics never reach LRCLIB")
    }

    @Test
    fun fetchesOnceFromLrcLibAndCachesTheAnswer() {
        val id = track("02 - Song", mapOf("title" to "Song", "artist" to "Band", "album" to "Rec"))
        val first = ctx.lyrics.forTrack(id)!!.single()
        assertTrue(first.synced)
        assertEquals(listOf(1000L, 3500L), first.lines.map { it.startMillis })
        val (query, agent) = fake.requests.single()
        assertTrue("artist_name=Band" in query && "track_name=Song" in query && "album_name=Rec" in query && "duration=1" in query, query)
        assertTrue(agent!!.startsWith("DulcetServer/"), "the app identifies itself: $agent")
        ctx.lyrics.forTrack(id)
        assertEquals(1, fake.requests.size, "the answer is cached")
    }

    @Test fun busyRemoteLookupReturnsWithoutWaitingForAnotherNetworkCall() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fake.entered = entered
        fake.release = release
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<com.legitimateapps.dulcet.server.lyrics.RemoteLyrics> { provider.lookup("Band", "Slow", null, 1) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit<com.legitimateapps.dulcet.server.lyrics.RemoteLyrics> { provider.lookup("Band", "Other", null, 1) }
            assertTrue(second.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) is com.legitimateapps.dulcet.server.lyrics.RemoteLyrics.Unavailable)
            release.countDown()
            assertTrue(first.get(2, java.util.concurrent.TimeUnit.SECONDS) is com.legitimateapps.dulcet.server.lyrics.RemoteLyrics.Found)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test
    fun cachesNoneAndHonoursRetryAfter() {
        val none = track("03 - Nothing", mapOf("title" to "Nothing", "artist" to "Band"))
        fake.status = 404
        assertEquals(emptyList(), ctx.lyrics.forTrack(none))
        assertEquals(emptyList(), ctx.lyrics.forTrack(none))
        assertEquals(1, fake.requests.size, "a definite none is cached")

        val limited = track("04 - Busy", mapOf("title" to "Busy", "artist" to "Band"))
        fake.status = 429
        fake.retryAfter = "120"
        assertEquals(emptyList(), ctx.lyrics.forTrack(limited))
        assertEquals(2, fake.requests.size)
        fake.status = 200
        fake.retryAfter = null
        now += 60_000
        assertEquals(emptyList(), ctx.lyrics.forTrack(limited), "inside Retry-After the server does not ask")
        assertEquals(2, fake.requests.size)
        now += 61_000
        assertEquals(1, ctx.lyrics.forTrack(limited)!!.size, "a 429 is not cached as none; after Retry-After it asks again")
        assertEquals(3, fake.requests.size)
    }
}
