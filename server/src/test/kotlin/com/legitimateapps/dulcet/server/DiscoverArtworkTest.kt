package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.URI
import kotlin.test.*

class DiscoverArtworkTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private val path="/release-group/$id/front-250"
    private val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,1,2,3)
    private class Clock:DiscoverClock {
        var now=1_000_000L
        override fun wallMillis()=now
        override fun monotonicNanos()=now*1_000_000
        override suspend fun sleepMillis(millis:Long){now+=millis}
    }
    @Test fun redirectImageStaysOnServerAndCachesThirtyDays() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0); val clock=Clock()
            val c=CoverArtClient(h,DiscoverCache(db,clock),DiscoverSettings(coverArtArchiveUrl=f.baseUrl))
            f.json(path,"",307,mapOf("Location" to "/storage")); f.respond("/storage",body=png,headers=mapOf("Content-Type" to "text/plain"))
            val first=c.thumbnail(id); assertEquals("image/png",first.contentType); assertFalse(first.placeholder)
            assertContentEquals(png,first.bytes)
            clock.now+=30*DiscoverCache.DAY-1; assertContentEquals(png,c.thumbnail(id).bytes); assertEquals(2,f.requests.size)
            clock.now++; c.thumbnail(id); assertEquals(4,f.requests.size)
            assertTrue(f.requests.all { it.headers["authorization"]==null && it.headers["cookie"]==null })
        } } }
    }
    @Test fun fiveRedirectHopsWorkButSixAndPrivateTargetsFail() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0)
            val c=CoverArtClient(h,DiscoverCache(db),DiscoverSettings(coverArtArchiveUrl=f.baseUrl))
            f.json(path,"",307,mapOf("Location" to "/hop1"))
            for(n in 1..4) f.json("/hop$n","",307,mapOf("Location" to "/hop${n+1}"))
            f.respond("/hop5",body=png)
            assertContentEquals(png,c.thumbnail(id).bytes); assertEquals(6,f.requests.size)
            val other="22222222-2222-4222-8222-222222222222"
            f.json("/release-group/$other/front-250","",307,mapOf("Location" to "/hop1"))
            f.json("/hop5","",307,mapOf("Location" to "/hop6"))
            assertFailsWith<UpstreamUnavailable> { c.thumbnail(other) }
            assertEquals(12,f.requests.size)
            val third="33333333-3333-4333-8333-333333333333"
            f.json("/release-group/$third/front-250","",307,mapOf("Location" to "http://169.254.169.254/latest/meta-data/"))
            assertFailsWith<UpstreamUnavailable> { c.thumbnail(third) }
            assertEquals(13,f.requests.size)
            assertFalse(CoverArtClient.publicAddress(InetAddress.getByName("127.0.0.1")))
            assertFalse(CoverArtClient.publicAddress(InetAddress.getByName("169.254.169.254")))
            assertFailsWith<UpstreamUnavailable> { c.validateRedirect(URI("https://coverartarchive.org/start"),"http://archive.org/image") }
        } } }
    }
    @Test fun missingArtGetsPlaceholderButOutageRetainsCachedBytes() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0); val clock=Clock()
            val c=CoverArtClient(h,DiscoverCache(db,clock),DiscoverSettings(coverArtArchiveUrl=f.baseUrl))
            f.respond(path,body=png); val first=c.thumbnail(id)
            clock.now+=30*DiscoverCache.DAY; f.json(path,"{bad image}")
            val stale=c.thumbnail(id); assertTrue(stale.age.stale); assertContentEquals(first.bytes,stale.bytes)
            f.json("/release-group/22222222-2222-4222-8222-222222222222/front-250","",404)
            assertTrue(c.thumbnail("22222222-2222-4222-8222-222222222222").placeholder)
        } } }
    }
    @Test fun signatureAndByteCapAreRequired() = runBlocking<Unit> {
        FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db -> PoliteHttp().use { h ->
            h.configureOrigin(f.baseUrl,0)
            val c=CoverArtClient(h,DiscoverCache(db),DiscoverSettings(coverArtArchiveUrl=f.baseUrl))
            for(body in listOf("{}".toByteArray(),byteArrayOf(0),png+ByteArray(5*1024*1024))) {
                f.respond(path,body=body); assertFailsWith<UpstreamUnavailable> { c.thumbnail(id) }
            }
            assertEquals("image/jpeg",CoverArtClient.imageType(byteArrayOf(255.toByte(),216.toByte(),255.toByte(),0)))
            assertEquals("image/gif",CoverArtClient.imageType("GIF89a".toByteArray()))
            assertEquals("image/webp",CoverArtClient.imageType("RIFF0000WEBP".toByteArray()))
        } } }
    }
}
