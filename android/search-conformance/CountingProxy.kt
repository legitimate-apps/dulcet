package com.legitimateapps.dulcet.search.conformance

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * A loopback forwarder between the production app and the disposable server: every request the
 * reader issues passes through it unchanged, and it records WHICH endpoint was asked, in arrival
 * order, so tests can assert request counts (never wall time). It is not a fixture: every answer is
 * the disposable server's own, except where a test explicitly holds, fails, drops or rewrites an
 * endpoint's answer.
 *
 * Credentials never reach its log: only the endpoint name and the non-credential parameters are kept.
 */
class CountingProxy(private val target: String) : AutoCloseable {
    data class Seen(
        val sequence: Int,
        val endpoint: String,
        val parameters: Map<String, String>,
        @Volatile var answered: Boolean = false,
        /** The HTTP status the app received; 0 until answered, and for a dropped connection. */
        @Volatile var status: Int = 0,
        /** The server's answer reached the app changed by a [rewrite] rule. */
        @Volatile var rewritten: Boolean = false,
    ) {
        override fun toString(): String = "$endpoint${parameters["type"]?.let { "[$it]" } ?: ""}:$status"
    }

    private val lock = Any()
    private val seen = mutableListOf<Seen>()
    private var holdRule: ((Seen) -> Boolean)? = null
    private var failRule: ((Seen) -> Boolean)? = null
    private var dropRule: ((Seen) -> Boolean)? = null
    private var rewriteRule: Pair<(Seen) -> Boolean, (ByteArray) -> ByteArray>? = null
    private var gate = CountDownLatch(1)
    private val executor = Executors.newFixedThreadPool(16)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64).apply {
        createContext("/") { exchange -> forward(exchange) }
        executor = this@CountingProxy.executor
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** Everything seen so far, in arrival order. */
    fun log(): List<Seen> = synchronized(lock) { seen.map { it.copy() } }

    fun size(): Int = synchronized(lock) { seen.size }

    fun since(mark: Int): List<Seen> = log().drop(mark)

    fun inFlight(): Int = synchronized(lock) { seen.count { !it.answered } }

    fun answered(endpoint: String): Int = synchronized(lock) { seen.count { it.endpoint == endpoint && it.answered } }

    /** Holds every matching request unanswered until [release]. */
    fun hold(rule: (Seen) -> Boolean) = synchronized(lock) {
        holdRule = rule
        gate = CountDownLatch(1)
    }

    fun holdAll() = hold { true }

    fun release() {
        val open = synchronized(lock) {
            holdRule = null
            gate
        }
        open.countDown()
    }

    /** Answers every matching request with HTTP 500 and no body, without asking the server. */
    fun fail(rule: ((Seen) -> Boolean)?) = synchronized(lock) { failRule = rule }

    /**
     * Closes the connection of every matching request without any answer: the connection is
     * accepted and then ends before a status line. The client sees a transport failure, not an HTTP
     * status, which the app classifies as unreachable. It is not a refused or timed-out connection.
     */
    fun drop(rule: ((Seen) -> Boolean)?) = synchronized(lock) { dropRule = rule }

    /**
     * Passes every matching request to the server as usual and hands the app [transform] of the
     * server's answer body instead of the body itself: a server presented as one that answers
     * differently, the rest of its answer (status, headers) unchanged. Null stops rewriting.
     */
    fun rewrite(rule: ((Seen) -> Boolean)?, transform: (ByteArray) -> ByteArray = { it }) = synchronized(lock) {
        rewriteRule = rule?.let { it to transform }
    }

    private fun forward(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val body = exchange.requestBody.readBytes()
        val form = exchange.requestHeaders.getFirst("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded")
        val parameters = parse(uri.rawQuery) + (if (form) parse(String(body, Charsets.UTF_8)) else emptyMap())
        val entry = synchronized(lock) {
            Seen(seen.size, uri.path.substringAfterLast('/').removeSuffix(".view"), parameters.filterKeys { it !in CREDENTIAL_KEYS })
                .also { seen += it }
        }
        try {
            val (held, latch, failing) = synchronized(lock) {
                Triple(holdRule?.invoke(entry) == true, gate, failRule?.invoke(entry) == true)
            }
            if (held) latch.await(HOLD_CEILING_SECONDS, TimeUnit.SECONDS)
            if (synchronized(lock) { dropRule?.invoke(entry) == true }) {
                // No status: closing an exchange before its headers are sent closes the connection.
                entry.status = 0
                return
            }
            if (failing) {
                entry.status = 500
                exchange.sendResponseHeaders(500, -1)
                return
            }
            val connection = URI(target + uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")).toURL()
                .openConnection() as HttpURLConnection
            connection.requestMethod = exchange.requestMethod
            connection.instanceFollowRedirects = false
            exchange.requestHeaders.forEach { (name, values) ->
                if (name != null && name.lowercase() !in HOP_HEADERS) values.forEach { connection.addRequestProperty(name, it) }
            }
            if (body.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val served = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            val rewriting = synchronized(lock) { rewriteRule?.takeIf { (rule, _) -> rule(entry) } }
            // A rewrite reads the body as the server meant it, so a compressed answer is passed on decoded.
            val gzipped = rewriting != null && connection.contentEncoding.equals("gzip", ignoreCase = true)
            val answer = rewriting?.second?.invoke(if (gzipped) GZIPInputStream(served.inputStream()).readBytes() else served)
                ?.also { entry.rewritten = true } ?: served
            connection.headerFields.forEach { (name, values) ->
                if (name != null && name.lowercase() !in HOP_HEADERS && !(gzipped && name.equals("content-encoding", true))) {
                    values.forEach { exchange.responseHeaders.add(name, it) }
                }
            }
            entry.status = status
            exchange.sendResponseHeaders(status, if (answer.isEmpty()) -1 else answer.size.toLong())
            if (answer.isNotEmpty()) exchange.responseBody.use { it.write(answer) }
        } catch (_: Throwable) {
            entry.status = 502
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            entry.answered = true
            exchange.close()
        }
    }

    override fun close() {
        release()
        server.stop(0)
        executor.shutdownNow()
    }

    private companion object {
        /** The only parameters that can carry a secret; they are never recorded. */
        val CREDENTIAL_KEYS = setOf("u", "p", "t", "s", "apiKey")
        val HOP_HEADERS = setOf("connection", "keep-alive", "transfer-encoding", "content-length", "host")
        const val HOLD_CEILING_SECONDS = 120L

        fun parse(query: String?): Map<String, String> = query.orEmpty().split('&').filter { it.isNotEmpty() }
            .associate { part ->
                val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                name to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
    }
}
