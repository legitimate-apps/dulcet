package com.legitimateapps.dulcet.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Scripted loopback upstream. Unscripted requests fail; fixtures never fall through to the internet. */
class FixtureDiscoverUpstream : AutoCloseable {
    data class RecordedRequest(val path: String, val query: Map<String, String>, val headers: Map<String, String>, val startNanos: Long, val active: Int, val method:String, val body:ByteArray)
    data class Answer(val status: Int, val body: ByteArray, val headers: Map<String, String>)
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val answers = ConcurrentHashMap<String, Answer>()
    private val active = AtomicInteger()
    @Volatile var entered: CountDownLatch? = null
    @Volatile var release: CountDownLatch? = null
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@FixtureDiscoverUpstream.executor
        createContext("/") { x ->
            val query = x.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { val parts=it.split('=',limit=2); URLDecoder.decode(parts[0],Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1){""},Charsets.UTF_8) }
            val count=active.incrementAndGet()
            try {
                requests += RecordedRequest(x.requestURI.path, query, x.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }, System.nanoTime(), count, x.requestMethod, x.requestBody.readAllBytes())
                entered?.countDown(); release?.await(10, TimeUnit.SECONDS)
                val a=answers[key(x.requestURI.path,query)] ?: answers[key(x.requestURI.path,emptyMap())] ?: Answer(500,"unscripted fixture request".toByteArray(),emptyMap())
                a.headers.forEach { (k,v)->x.responseHeaders.add(k,v) }
                x.sendResponseHeaders(a.status,a.body.size.toLong()); x.responseBody.use { it.write(a.body) }
            } finally { active.decrementAndGet(); x.close() }
        }
        start()
    }
    val baseUrl="http://127.0.0.1:${server.address.port}"
    fun respond(path:String, query:Map<String,String> = emptyMap(), status:Int=200, body:ByteArray, headers:Map<String,String> = emptyMap()) { answers[key(path,query)] = Answer(status,body,headers) }
    fun json(path:String, body:String, status:Int=200, headers:Map<String,String> = emptyMap()) = respond(path,status=status,body=body.toByteArray(),headers=headers)
    private fun key(path:String,query:Map<String,String>)=path+"?"+query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
    override fun close() { release?.countDown(); server.stop(0); executor.shutdownNow() }
}
