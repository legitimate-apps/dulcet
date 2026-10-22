package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.USER_AGENT
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface DiscoverClock {
    fun wallMillis(): Long
    fun monotonicNanos(): Long
    suspend fun sleepMillis(millis: Long)
}
object SystemDiscoverClock : DiscoverClock {
    override fun wallMillis() = System.currentTimeMillis()
    override fun monotonicNanos() = System.nanoTime()
    override suspend fun sleepMillis(millis: Long) = delay(millis)
}
data class UpstreamResponse(val status: Int, val headers: Map<String,List<String>>, val body: ByteArray)
class UpstreamUnavailable(val retryAfterMillis: Long? = null) : RuntimeException("Discovery source unavailable")

/** A single shared instance owns every client's origin queue. No caller headers are accepted. */
class PoliteHttp(private val clock: DiscoverClock = SystemDiscoverClock, private val userAgent: String = USER_AGENT) : AutoCloseable {
    private class Gate {
        val mutex = Mutex(); val waiting = AtomicInteger()
        var lastStart: Long? = null; var blockedUntil = 0L; var gapMillis = 1000L
    }
    private val gates = ConcurrentHashMap<String,Gate>()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    @Volatile private var closed = false
    var onRequest: (() -> Unit)? = null
    private fun gate(uri: URI) = gates.computeIfAbsent("${uri.scheme.lowercase()}://${uri.host.lowercase()}:${if(uri.port<0) if(uri.scheme=="https") 443 else 80 else uri.port}") { Gate() }
    fun configureOrigin(baseUrl: String, gapMillis: Long) {
        DiscoverSettings.validateBase(baseUrl)
        require(gapMillis >= 0)
        val uri=URI(baseUrl)
        require(!uri.host.lowercase().endsWith("musicbrainz.org") || gapMillis>=1000)
        gate(uri).gapMillis=gapMillis
    }
    suspend fun get(baseUrl: String, path: String, query: Map<String,String>, maxBytes: Int = 8*1024*1024): UpstreamResponse {
        DiscoverSettings.validateBase(baseUrl)
        require(path.startsWith("/") && !path.startsWith("//") && '?' !in path && '#' !in path)
        require(maxBytes>0)
        val encoded=query.toSortedMap().entries.joinToString("&") { (k,v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val uri=URI(baseUrl.trimEnd('/')+path+if(encoded.isEmpty()) "" else "?$encoded")
        return request(uri,maxBytes)
    }
    internal suspend fun request(uri: URI, maxBytes: Int): UpstreamResponse {
        val g=gate(uri)
        if(g.waiting.incrementAndGet()>65) { g.waiting.decrementAndGet(); throw UpstreamUnavailable() }
        try {
            return g.mutex.withLock {
                if(closed) throw UpstreamUnavailable()
                fun checkBackoff() {
                    val remaining=g.blockedUntil-clock.monotonicNanos()
                    if(remaining>0) throw UpstreamUnavailable((remaining+999_999)/1_000_000)
                }
                checkBackoff()
                g.lastStart?.let { start ->
                    val remaining=g.gapMillis*1_000_000-(clock.monotonicNanos()-start)
                    if(remaining>0) clock.sleepMillis((remaining+999_999)/1_000_000)
                }
                currentCoroutineContext().ensureActive(); checkBackoff()
                g.lastStart=clock.monotonicNanos(); onRequest?.invoke()
                try {
                    withContext(Dispatchers.IO) {
                        val req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("User-Agent",userAgent).header("Accept","application/json, image/*").GET().build()
                        val future=client.sendAsync(req,HttpResponse.BodyHandler { BoundedBody(maxBytes) }).orTimeout(10,java.util.concurrent.TimeUnit.SECONDS)
                        val response=suspendCancellableCoroutine<HttpResponse<ByteArray>> { continuation ->
                            continuation.invokeOnCancellation { future.cancel(true) }
                            future.whenComplete { value,error ->
                                if(error!=null) continuation.resumeWith(Result.failure(error))
                                else continuation.resumeWith(Result.success(value))
                            }
                        }
                        val headers=response.headers().map().mapKeys { it.key.lowercase() }
                        val status=response.statusCode()
                        val delay=backoff(status,headers)
                        if(delay!=null) g.blockedUntil=maxOf(g.blockedUntil,clock.monotonicNanos()+delay*1_000_000)
                        response.body().let { bytes ->
                            if(status==429 || status==503) throw UpstreamUnavailable(delay)
                            val length=headers["content-length"]?.firstOrNull()?.toLongOrNull()
                            if(length!=null && length>maxBytes) throw UpstreamUnavailable()
                            
                            if(bytes.size>maxBytes || (length!=null && length!=bytes.size.toLong())) throw UpstreamUnavailable()
                            UpstreamResponse(status,headers,bytes)
                        }
                    }
                } catch(e: CancellationException) { throw e }
                  catch(e: UpstreamUnavailable) { throw e }
                  catch(_: Exception) { throw UpstreamUnavailable() }
            }
        } finally { g.waiting.decrementAndGet() }
    }
    private fun backoff(status: Int, headers: Map<String,List<String>>): Long? {
        fun seconds(name:String)=headers[name]?.firstOrNull()?.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(7*86400)?.times(1000)
        val retry=headers["retry-after"]?.firstOrNull()?.let { value ->
            value.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(7*86400)?.times(1000)
                ?: runCatching { (ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-clock.wallMillis()).coerceIn(0,7*86400_000L) }.getOrNull()
        }
        val exhausted=headers["x-ratelimit-remaining"]?.firstOrNull()=="0"
        val reset=if(exhausted) seconds("x-ratelimit-reset-in") ?: seconds("x-ratelimit-reset")?.let { (it-clock.wallMillis()).coerceAtLeast(0) } ?: 60_000L else null
        val limit=if(status==429 || status==503) retry ?: 60_000L else retry
        return listOfNotNull(limit,reset).maxOrNull()
    }
    private class BoundedBody(private val maxBytes:Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result=java.util.concurrent.CompletableFuture<ByteArray>()
        private val output=java.io.ByteArrayOutputStream()
        private lateinit var subscription: java.util.concurrent.Flow.Subscription
        override fun getBody(): java.util.concurrent.CompletionStage<ByteArray> = result
        override fun onSubscribe(value:java.util.concurrent.Flow.Subscription) { subscription=value; value.request(1) }
        override fun onNext(chunks:List<java.nio.ByteBuffer>) {
            if(chunks.sumOf { it.remaining().toLong() }+output.size()>maxBytes) {
                subscription.cancel(); result.completeExceptionally(UpstreamUnavailable()); return
            }
            chunks.forEach { chunk -> val bytes=ByteArray(chunk.remaining()); chunk.get(bytes); output.write(bytes) }
            subscription.request(1)
        }
        override fun onError(error:Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(output.toByteArray()) }
    }
    override fun close() { closed=true }
}
