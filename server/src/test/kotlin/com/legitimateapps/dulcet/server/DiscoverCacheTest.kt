package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DiscoverCacheTest {
    private class Clock : DiscoverClock {
        var now=1_000_000L
        override fun wallMillis()=now
        override fun monotonicNanos()=now*1_000_000
        override suspend fun sleepMillis(millis:Long) { now+=millis }
    }
    private val key=CacheKey("http://127.0.0.1:1234/prefix","/search",mapOf("q" to "Björk"))
    private fun decode(bytes:ByteArray)=bytes.decodeToString().also { require(it.startsWith("[")) }
    @Test fun freshHitAndTwentyConcurrentMissesSendOnce() = runBlocking {
        Db.inMemory().use { db ->
            val c=DiscoverCache(db,Clock()); val count=AtomicInteger()
            val values=coroutineScope { (1..20).map { async { c.read(key,3600000,::decode) { count.incrementAndGet(); delay(10); UpstreamResponse(200,emptyMap(),"[]".toByteArray()) } } }.awaitAll() }
            assertEquals(1,count.get()); assertTrue(values.all { it.value=="[]" && !it.age.stale })
            assertEquals(1,values.map { it.generation }.distinct().size)
        }
    }
    @Test fun restartRetainsAgeAndBadRefreshNeverOverwritesGood() = runBlocking {
        val file=Files.createTempDirectory("discover-cache").resolve("cache.db"); val clock=Clock()
        val original=Db.open(file).use { db -> DiscoverCache(db,clock).read(key,100,::decode) { UpstreamResponse(200,emptyMap(),"[]".toByteArray()) } }
        clock.now+=101
        Db.open(file).use { db ->
            val c=DiscoverCache(db,clock)
            for (body in listOf("malformed", "503")) {
                val value=c.read(key,100,::decode) { if(body=="503") throw UpstreamUnavailable() else UpstreamResponse(200,emptyMap(),body.toByteArray()) }
                assertEquals(original.age.fetchedAt,value.age.fetchedAt); assertEquals(original.generation,value.generation); assertTrue(value.age.stale)
            }
        }
    }
    @Test fun noCacheFailureIsNotEmptyAnd404ExpiresAfter24Hours() = runBlocking {
        Db.inMemory().use { db ->
            val clock=Clock(); val c=DiscoverCache(db,clock); var calls=0
            assertFailsWith<UpstreamUnavailable> { c.read(key,100,::decode) { throw UpstreamUnavailable() } }
            suspend fun missing()=c.read(key,100,::decode) { calls++; UpstreamResponse(404,emptyMap(),byteArrayOf()) }
            assertFailsWith<UpstreamNotFound> { missing() }; clock.now+=86_399_999
            assertFailsWith<UpstreamNotFound> { missing() }; assertEquals(1,calls)
            clock.now++; assertFailsWith<UpstreamNotFound> { missing() }; assertEquals(2,calls)
        }
    }
    @Test fun baseParserAndNinetyDayRetentionSeparateEntries() = runBlocking {
        Db.inMemory().use { db ->
            val clock=Clock(); val c=DiscoverCache(db,clock,byteBudget=4); var calls=0
            for(k in listOf(key,key.copy(parserVersion=2),key.copy(baseUrl="http://localhost:1234"))) c.read(k,100,::decode) { calls++; UpstreamResponse(200,emptyMap(),"[]".toByteArray()) }
            assertEquals(3,calls); assertTrue(db.read { long("SELECT sum(bytes) FROM discover_cache") }<=4)
            clock.now+=90L*86400000+1; c.prune()
            assertEquals(0,db.read { long("SELECT count(*) FROM discover_cache") })
        }
    }
}
