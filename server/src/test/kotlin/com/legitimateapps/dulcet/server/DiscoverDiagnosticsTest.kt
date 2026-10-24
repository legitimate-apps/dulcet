package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DiscoverDiagnosticsTest {
    private class Clock:DiscoverClock {
        var millis=1_000_000L
        override fun wallMillis()=millis
        override fun monotonicNanos()=millis*1_000_000
        override suspend fun sleepMillis(millis:Long) { this.millis+=millis }
    }
    @Test fun originCardinalityAndLabelsAreBoundedAndPrivate() {
        val lines=mutableListOf<String>(); val diagnostics=DiscoverDiagnostics { lines+=it }
        repeat(100) { index -> diagnostics.http(URI("http://private-host-$index.invalid/private-path?query=private-query"),2_000_000,200,null,false,true) }
        val counts=diagnostics.snapshot()
        assertEquals(65,counts.size)
        assertEquals(100L,counts.sumOf { it.requests })
        assertEquals(36L,counts.single { it.origin=="other-origins" }.requests)
        assertEquals(200L,counts.sumOf { it.durationMillis })
        assertTrue(lines.all { "private-host" !in it && "private-path" !in it && "private-query" !in it })
    }
    @Test fun waitingCapAndCancelledQueueEntriesDoNotCountAsWireRequests() = runBlocking {
        FixtureDiscoverUpstream().use { f ->
            f.json("/active","[]"); f.entered=CountDownLatch(1); f.release=CountDownLatch(1)
            val diagnostics=DiscoverDiagnostics { }
            PoliteHttp(diagnostics=diagnostics).use { http ->
                val active=async(Dispatchers.IO) { http.get(f.baseUrl,"/active",emptyMap()) }
                assertTrue(withContext(Dispatchers.IO) { f.entered!!.await(3,TimeUnit.SECONDS) })
                val waiting=(1..64).map { async(start=CoroutineStart.UNDISPATCHED) { http.get(f.baseUrl,"/waiting",emptyMap()) } }
                try {
                    val failure=assertFailsWith<UpstreamUnavailable> { http.get(f.baseUrl,"/overflow",emptyMap()) }
                    assertEquals(UpstreamFailure.WaitingCap,failure.reason)
                } finally { waiting.forEach { it.cancel() }; waiting.joinAll(); f.release!!.countDown(); active.await() }
                val counts=diagnostics.snapshot().single()
                assertEquals(1,f.requests.size); assertEquals(1L,counts.requests)
                assertEquals(66L,counts.attempts); assertEquals(1L,counts.waitingCap)
                assertEquals(1L,counts.statusClasses[2])
            }
        }
    }
    @Test fun backoffRefusalDoesNotCountAsWireRequest() = runBlocking {
        FixtureDiscoverUpstream().use { f ->
            f.json("/limited","{}",503,mapOf("Retry-After" to "120"))
            val diagnostics=DiscoverDiagnostics { }
            PoliteHttp(Clock(),diagnostics=diagnostics).use { http ->
                assertEquals(UpstreamFailure.HttpStatus,assertFailsWith<UpstreamUnavailable> { http.get(f.baseUrl,"/limited",emptyMap()) }.reason)
                assertEquals(UpstreamFailure.Backoff,assertFailsWith<UpstreamUnavailable> { http.get(f.baseUrl,"/next",emptyMap()) }.reason)
                val counts=diagnostics.snapshot().single()
                assertEquals(1,f.requests.size); assertEquals(1L,counts.requests); assertEquals(2L,counts.attempts)
                assertEquals(1L,counts.backoffApplied); assertEquals(1L,counts.backoffRefused)
                assertEquals(1L,counts.statusClasses[5])
            }
        }
    }
    @Test fun cancelledRefreshAndCachedDecodeNeverServeStaleOrLogFailure() = runBlocking {
        Db.inMemory().use { db ->
            val clock=Clock(); val diagnostics=DiscoverDiagnostics { error("Cancellation must not be logged as an upstream failure") }
            val cache=DiscoverCache(db,clock,diagnostics=diagnostics)
            val key=CacheKey("http://localhost:1234","/search",emptyMap())
            cache.read(key,1,{it.decodeToString()}) { UpstreamResponse(200,emptyMap(),"[]".toByteArray()) }
            clock.millis+=2
            assertFailsWith<CancellationException> { cache.read(key,1,{it.decodeToString()}) { throw CancellationException() } }
            assertFailsWith<CancellationException> { cache.read<String>(key,1,{throw CancellationException()}) { error("must not fetch after cached decode cancellation") } }
            assertTrue(diagnostics.snapshot().isEmpty())
        }
    }
    @Test fun intentionalCacheOnlyMissAndStaleReadAreNotUpstreamRefreshFailures() = runBlocking {
        FixtureDiscoverUpstream().use { f ->
            Db.inMemory().use { db ->
                val clock=Clock(); val lines=mutableListOf<String>(); val diagnostics=DiscoverDiagnostics { lines+=it }
                val cache=DiscoverCache(db,clock,diagnostics=diagnostics)
                PoliteHttp(clock,diagnostics=diagnostics).use { http ->
                    val settings=DiscoverSettings(enabled=false,musicBrainzUrl=f.baseUrl)
                    val client=MusicBrainzClient(http,cache,settings)
                    val id="11111111-1111-4111-8111-111111111111"
                    assertFailsWith<UpstreamUnavailable> { client.album(id) }
                    assertTrue(lines.isEmpty(),"A deliberately disabled fetch with no cache is not an upstream failure")
                    val key=CacheKey(f.baseUrl,"/ws/2/release-group/$id",mapOf("inc" to "artist-credits+url-rels","fmt" to "json"))
                    val body="""{"id":"$id","title":"Cached album","artist-credit":[]}""".toByteArray()
                    cache.read(key,DiscoverCache.DAY,{it}) { UpstreamResponse(200,emptyMap(),body) }
                    clock.millis+=DiscoverCache.DAY+1
                    val result=client.album(id)
                    assertTrue(result.age.stale,"The test must actually exercise expired-cache fallback")
                    assertEquals(0,f.requests.size,"Cache-only reads must never send HTTP")
                    val counts=diagnostics.snapshot().single()
                    assertEquals(0L,counts.requests); assertEquals(0L,counts.refreshFailures)
                    assertEquals(1L,counts.staleServes)
                    assertTrue(lines.single().contains("event=stale_serve outcome=cache_only"))
                }
            }
        }
    }
    @Test fun disabledArtworkAndListenBrainzDoNotInventUpstreamFailures() = runBlocking {
        Db.inMemory().use { db ->
            val lines=mutableListOf<String>(); val diagnostics=DiscoverDiagnostics { lines+=it }
            val cache=DiscoverCache(db,diagnostics=diagnostics)
            PoliteHttp(diagnostics=diagnostics).use { http ->
                val settings=DiscoverSettings(enabled=false)
                var resolved=false
                val art=CoverArtClient(http,cache,settings,resolver={ resolved=true; error("disabled artwork must not resolve upstream") })
                val image=art.thumbnail("11111111-1111-4111-8111-111111111111")
                assertEquals("image/svg+xml",image.contentType,"A disabled missing artwork lookup must retain the existing placeholder behavior")
                assertFalse(resolved,"The cache-only condition must actually avoid DNS and HTTP")
                val failure=assertFailsWith<UpstreamUnavailable> { ListenBrainzClient(http,cache,settings).freshReleases() }
                assertEquals(UpstreamFailure.CacheOnly,failure.reason)
                assertTrue(lines.isEmpty(),"Neither deliberately disabled provider is an upstream refresh failure")
                assertTrue(diagnostics.snapshot().isEmpty())
            }
        }
    }
    @Test fun resolverTimeoutWithoutCachedValueIsObservable() = runBlocking {
        Db.inMemory().use { db ->
            val lines=mutableListOf<String>(); val diagnostics=DiscoverDiagnostics { lines+=it }
            val cache=DiscoverCache(db,diagnostics=diagnostics); var attempted=false
            PoliteHttp(diagnostics=diagnostics).use { http ->
                val settings=DiscoverSettings(coverArtArchiveUrl="http://127.0.0.1:1234")
                val art=CoverArtClient(http,cache,settings,resolver={ attempted=true; throw java.util.concurrent.TimeoutException("private-resolver-error") })
                assertEquals(UpstreamFailure.DnsTimeout,assertFailsWith<UpstreamUnavailable> { art.thumbnail("11111111-1111-4111-8111-111111111111") }.reason)
                assertTrue(attempted,"the resolver must really report a timeout before a wire request")
                val counts=diagnostics.snapshot().single()
                assertEquals(0L,counts.requests); assertEquals(1L,counts.refreshFailures); assertEquals(1L,counts.refreshTimeouts)
                assertTrue(lines.any { "event=refresh_failure outcome=dns_timeout" in it })
                assertTrue(lines.none { "private-resolver-error" in it || "127.0.0.1" in it })
            }
        }
    }
}
