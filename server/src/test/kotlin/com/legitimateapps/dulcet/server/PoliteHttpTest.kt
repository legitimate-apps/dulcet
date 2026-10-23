package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.*

class PoliteHttpTest {
    private class Clock : DiscoverClock {
        var nanos=0L; var wall=1_000_000L
        override fun wallMillis()=wall
        override fun monotonicNanos()=nanos
        override suspend fun sleepMillis(millis:Long) { nanos+=millis*1_000_000; wall+=millis }
    }
    @Test fun serializesClientsAndSeparatesOrigins() = runBlocking {
        FixtureDiscoverUpstream().use { f ->
            f.json("/one", "{}")
            val clock=Clock()
            PoliteHttp(clock).use { http ->
                val starts=mutableListOf<Long>()
                http.onRequest={ starts+=clock.nanos }
                coroutineScope { (1..5).map { async(Dispatchers.Default) { http.get(f.baseUrl,"/one",emptyMap()) } }.awaitAll() }
                assertEquals(5, f.requests.size); assertEquals(1, f.requests.maxOf { it.active })
                assertTrue(starts.zipWithNext().all { (a,b) -> b-a>=1_000_000_000 })
                assertTrue(f.requests.all { it.headers["user-agent"] == USER_AGENT && it.headers["authorization"] == null })
            }
        }
    }
    @Test fun deltaDateAnd503BackOffEntireOriginWithoutRetry() = runBlocking {
        FixtureDiscoverUpstream().use { f ->
            val clock=Clock()
            PoliteHttp(clock).use { h ->
                f.json("/limited","{}",503,mapOf("Retry-After" to "120"))
                assertEquals(120_000L,assertFailsWith<UpstreamUnavailable> { h.get(f.baseUrl,"/limited",emptyMap()) }.retryAfterMillis)
                clock.wall+=9_000_000
                assertFailsWith<UpstreamUnavailable> { h.get(f.baseUrl,"/other",emptyMap()) }
                assertEquals(1,f.requests.size)
                clock.nanos+=120_000_000_000
                f.json("/limited","{}",429,mapOf("Retry-After" to DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(clock.wall+120_000).atZone(ZoneOffset.UTC))))
                assertEquals(120_000L,assertFailsWith<UpstreamUnavailable> { h.get(f.baseUrl,"/limited",emptyMap()) }.retryAfterMillis)
            }
        }
    }
    @Test fun remainingZeroAndMalformedRetryUseWholeOriginDeadlines() = runBlocking {
        FixtureDiscoverUpstream().use { f ->
            val clock=Clock()
            PoliteHttp(clock).use { h ->
                f.json("/a","{}",headers=mapOf("X-RateLimit-Remaining" to "0","X-RateLimit-Reset-In" to "120"))
                h.get(f.baseUrl,"/a",emptyMap())
                assertEquals(120_000L,assertFailsWith<UpstreamUnavailable> { h.get(f.baseUrl,"/a",emptyMap()) }.retryAfterMillis)
                clock.nanos+=120_000_000_000
                f.json("/a","{}",429,mapOf("Retry-After" to "bad"))
                assertEquals(60_000L,assertFailsWith<UpstreamUnavailable> { h.get(f.baseUrl,"/a",emptyMap()) }.retryAfterMillis)
            }
        }
    }
    @Test fun cancelledQueueEntryNeverSendsAndOtherOriginProgresses() = runBlocking {
        FixtureDiscoverUpstream().use { first -> FixtureDiscoverUpstream().use { second ->
            first.json("/one","{}"); second.json("/two","{}")
            first.entered=java.util.concurrent.CountDownLatch(1)
            first.release=java.util.concurrent.CountDownLatch(1)
            PoliteHttp().use { h ->
                val active=async(Dispatchers.IO) { h.get(first.baseUrl,"/one",emptyMap()) }
                assertTrue(withContext(Dispatchers.IO) { first.entered!!.await(3,java.util.concurrent.TimeUnit.SECONDS) })
                val cancelled=launch { h.get(first.baseUrl,"/cancelled",emptyMap()) }
                yield(); cancelled.cancelAndJoin()
                assertEquals(200,withTimeout(3000) { h.get(second.baseUrl,"/two",emptyMap()) }.status)
                first.release!!.countDown(); active.await()
                assertEquals(listOf("/one"),first.requests.map { it.path })
            }
        } }
    }
    @Test fun boundedBodyAndNoAutomaticRedirect() = runBlocking {
        FixtureDiscoverUpstream().use { f ->
            f.json("/large","abcdefgh")
            PoliteHttp(Clock()).use { h ->
                assertFailsWith<UpstreamUnavailable> { h.get(f.baseUrl,"/large",emptyMap(),4) }
                f.json("/redirect","",307,mapOf("Location" to "https://example.invalid/"))
                assertEquals(307,h.get(f.baseUrl,"/redirect",emptyMap()).status)
                assertEquals(2,f.requests.size)
            }
        }
    }
}
