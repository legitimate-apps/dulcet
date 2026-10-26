package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Uses the pre-diagnostics API too, so the same regression executes against the previous build. */
class DiscoverDiagnosticsRegressionTest {
    private class Clock: DiscoverClock {
        var millis=1_000_000L
        override fun wallMillis()=millis
        override fun monotonicNanos()=millis*1_000_000
        override suspend fun sleepMillis(millis:Long) { this.millis+=millis }
    }
    private suspend fun logs(block:suspend ()->Unit):String {
        val output=ByteArrayOutputStream(); val previous=System.err
        try { System.setErr(PrintStream(output,true,Charsets.UTF_8)); block() }
        finally { System.setErr(previous) }
        return output.toString(Charsets.UTF_8)
    }
    @Test fun statusBackoffParseAndStaleAreObservableWithoutQueryValues() = runBlocking {
        val canary="private-query-canary"
        val output=logs {
            FixtureDiscoverUpstream().use { f -> Db.inMemory().use { db ->
                val clock=Clock(); val cache=DiscoverCache(db,clock)
                PoliteHttp(clock).use { http ->
                    val key=CacheKey(f.baseUrl,"/private-path-canary",mapOf("query" to canary))
                    fun decode(body:ByteArray)=body.decodeToString().also { require(it=="[]") { canary } }
                    val statuses=mutableListOf<Int>(); val retryDelays=mutableListOf<Long?>()
                    suspend fun read()=cache.read(key,1,::decode) {
                        try { http.get(key.baseUrl,key.path,key.query).also { statuses+=it.status } }
                        catch(e:UpstreamUnavailable) { retryDelays+=e.retryAfterMillis; throw e }
                    }
                    f.json(key.path,"[]")
                    assertFalse(read().age.stale)
                    clock.millis+=2
                    f.json(key.path,"{}",503,mapOf("Retry-After" to "120"))
                    assertTrue(read().age.stale,"actual 503 must fall back to the retained response")
                    assertEquals(listOf(200),statuses,"503 throws before the response is returned to the cache")
                    assertEquals(listOf<Long?>(120_000L),retryDelays,"actual 503 Retry-After must be observed")
                    assertEquals(2,f.requests.size)
                    assertTrue(read().age.stale,"the next read must be refused by active origin backoff")
                    assertEquals(2,f.requests.size,"backoff must prevent an upstream request")
                    clock.millis+=120_001
                    f.json(key.path,"bad-json-$canary")
                    assertTrue(read().age.stale,"a received but undecodable 200 must retain the old response")
                    assertEquals(3,f.requests.size,"malformed response must actually have been requested")
                }
            } }
        }
        assertTrue(output.contains("status=5xx"),output)
        assertTrue(output.contains("event=stale_serve outcome=http_status"),output)
        assertTrue(output.contains("event=stale_serve outcome=backoff"),output)
        assertTrue(output.contains("event=stale_serve outcome=parse_failure"),output)
        assertTrue(Regex("backoff_applied=[1-9][0-9]*").containsMatchIn(output),output)
        assertTrue(Regex("parse_failures=[1-9][0-9]*").containsMatchIn(output),output)
        assertFalse(output.contains(canary)); assertFalse(output.contains("private-path-canary"))
        assertFalse(output.contains("127.0.0.1")); assertFalse(output.contains("query="))
    }
    @Test fun actualWaitingCapRefusalIsObservable() = runBlocking {
        val output=logs {
            FixtureDiscoverUpstream().use { f ->
                f.json("/blocked","[]"); f.entered=CountDownLatch(1); f.release=CountDownLatch(1)
                PoliteHttp().use { http ->
                    val active=async(Dispatchers.IO) { http.get(f.baseUrl,"/blocked",emptyMap()) }
                    assertTrue(withContext(Dispatchers.IO) { f.entered!!.await(3,TimeUnit.SECONDS) })
                    // UNDISPATCHED runs each request through incrementAndGet to its suspended mutex wait.
                    val waiting=(1..64).map { async(start=CoroutineStart.UNDISPATCHED) { http.get(f.baseUrl,"/queued",emptyMap()) } }
                    try {
                        assertFailsWith<UpstreamUnavailable> { http.get(f.baseUrl,"/overflow",emptyMap()) }
                        assertEquals(1,f.requests.size,"65 admitted calls must leave only the active call on the wire")
                    } finally { waiting.forEach { it.cancel() }; waiting.joinAll(); f.release!!.countDown(); active.await() }
                }
            }
        }
        assertTrue(output.contains("outcome=waiting_cap"),output)
        assertTrue(Regex("waiting_cap=[1-9][0-9]*").containsMatchIn(output),output)
    }
    @Test fun actualNetworkTimeoutIsObservable() = runBlocking {
        val output=logs {
            java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val entered=CountDownLatch(1); val release=CountDownLatch(1)
                val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
                val handling=executor.submit {
                    server.accept().use { socket ->
                        val reader=socket.getInputStream().bufferedReader()
                        while(reader.readLine()?.isNotEmpty()==true) { }
                        entered.countDown(); release.await(30,TimeUnit.SECONDS)
                    }
                }
                try {
                    PoliteHttp().use { http ->
                        val start=System.nanoTime()
                        assertFailsWith<UpstreamUnavailable> { http.get("http://127.0.0.1:${server.localPort}","/slow",emptyMap()) }
                        assertEquals(0,entered.count,"request must really have reached the blocked local server")
                        assertTrue(System.nanoTime()-start>=9_000_000_000,"must reach the client's real ten-second deadline")
                    }
                } finally { release.countDown(); handling.get(3,TimeUnit.SECONDS); executor.shutdownNow() }
            }
        }
        assertTrue(output.contains("outcome=timeout"),output)
        assertTrue(Regex("timeouts=[1-9][0-9]*").containsMatchIn(output),output)
        assertTrue(Regex("duration_ms=([0-9]+)").findAll(output).any { it.groupValues[1].toLong()>=9_000 },output)
    }
}
