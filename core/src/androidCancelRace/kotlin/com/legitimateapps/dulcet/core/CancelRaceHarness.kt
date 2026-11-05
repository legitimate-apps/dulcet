package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

/**
 * The one interleaving that closes a live response body on the thread cancelling the request.
 *
 * A writer coroutine reading the body keeps the call job open, so an ordinary cancel, including one
 * of a body stalled mid-chunk, finishes that job, and closes the body, on the writer's thread. The
 * call job only completes inside `cancel()` when the writer already finished without closing, which
 * means cancelled before its first dispatch and retired on a worker while the canceller was between
 * cancelling its children and looking for one to wait on. The race fixes that order:
 *  - [WriterGate] holds the writer's first dispatch, so it can never start;
 *  - a handler registered after the writer's child handle runs inside that gap. It releases the
 *    writer, which retires at once, and waits for it, so the canceller finds no child to wait on.
 *
 * Everything else is the production client from [createAccountHttpClient].
 */
internal class HeldWriterRace(framing: CancelRaceFraming, tls: CancelRaceTls? = null) : Closeable {
    val server = StalledBodyServer(framing, tls)
    val probe = ResponseBodyProbe()
    private val gate = WriterGate()
    val client = cancelRaceClient(probe, tls, gate)
    private val responded = CompletableDeferred<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var writerRetiredBeforeFinish = false
        private set

    /** The composition's effect: a job with a `withContext` under it holding the request open. */
    private lateinit var owner: Job

    /** Starts the request and returns once the response is out with its writer not yet started. */
    @OptIn(InternalCoroutinesApi::class)
    fun reachTheGap() {
        probe.onStalledResponse = gate::arm
        owner = scope.launch {
            withContext(Dispatchers.IO) {
                client.prepareGet(server.url("/stalled")).execute { response ->
                    responded.complete(response.coroutineContext.job)
                    awaitCancellation()
                }
            }
        }
        val callJob = runBlocking { withTimeout(HARNESS_TIMEOUT_MILLIS) { responded.await() } }
        // A race that never met its condition proves nothing: the writer must be held, must be the
        // call's only open child, and must never have touched the body.
        check(pollUntil { gate.isHolding && callJob.children.count { !it.isCompleted } == 1 }) {
            "the held writer was not the call's only open child"
        }
        check(probe.reads.get() == 0) { "the writer read the body before the cancel" }
        callJob.invokeOnCompletion(onCancelling = true, invokeImmediately = false) {
            gate.release()
            writerRetiredBeforeFinish = pollUntil { callJob.children.none { !it.isCompleted } }
        }
    }

    /** Runs on the thread under test. On Android's main thread, an uncaught failure kills the process. */
    fun cancelOwner() = owner.cancel()

    fun awaitBodyClosed(): Boolean = probe.closeFinished.await(HARNESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

    /** The call's connection is gone: a later request on the same client must still work. */
    fun nextRequestSucceeds(): Boolean = runBlocking {
        withTimeout(HARNESS_TIMEOUT_MILLIS) { client.prepareGet(server.url("/ok")).execute { it.bodyAsText() } == "ok" }
    }

    override fun close() {
        client.close()
        server.close()
    }
}

internal fun cancelRaceClient(
    probe: ResponseBodyProbe,
    tls: CancelRaceTls? = null,
    dispatcher: CoroutineDispatcher? = null,
): HttpClient = createAccountHttpClient(AccountClientTransport.Default(), strictMediaFraming = false) {
    @Suppress("UNCHECKED_CAST")
    (this as HttpClientConfig<OkHttpConfig>).engine {
        dispatcher?.let { this.dispatcher = it }
        config {
            addInterceptor(probe)
            tls?.let {
                sslSocketFactory(it.clientSocketFactory, it.trustManager)
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}

/** Holds the engine's first call-context dispatch made by the engine itself after the response. */
private class WriterGate : CoroutineDispatcher() {
    private val onWorker = ThreadLocal<Boolean>()
    private val held = AtomicReference<Pair<CoroutineContext, Runnable>?>()
    @Volatile private var armed = false

    val isHolding get() = held.get() != null

    fun arm() { armed = true }

    fun release() {
        armed = false
        held.getAndSet(null)?.let { (context, block) -> worker(context, block) }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (armed && onWorker.get() == true && context[CoroutineName]?.name == "call-context" &&
            held.compareAndSet(null, context to block)
        ) {
            armed = false
            return
        }
        worker(context, block)
    }

    private fun worker(context: CoroutineContext, block: Runnable) = Dispatchers.IO.dispatch(context, Runnable {
        onWorker.set(true)
        try { block.run() } finally { onWorker.set(false) }
    })
}

/** Records how the production client treats the stalled body, seen from just inside the engine. */
internal class ResponseBodyProbe : Interceptor {
    val reads = AtomicInteger()
    val closes = AtomicInteger()
    val closeThreads = CopyOnWriteArrayList<Thread>()
    val closeFinished = CountDownLatch(1)
    @Volatile var onStalledResponse: () -> Unit = {}

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.request().url.encodedPath != "/stalled") return response
        val body = response.body
        val observed = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads.incrementAndGet()
                return super.read(sink, byteCount)
            }
            override fun close() {
                closeThreads += Thread.currentThread()
                try { super.close() } finally {
                    closes.incrementAndGet()
                    closeFinished.countDown()
                }
            }
        }
        onStalledResponse()
        return response.newBuilder().body(observed.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }
}

internal enum class CancelRaceFraming { Chunked, FixedLength }

/** Sends part of `/stalled` and then nothing until the peer goes away. `/ok` is a whole keep-alive body. */
internal class StalledBodyServer(private val framing: CancelRaceFraming, tls: CancelRaceTls? = null) : Closeable {
    private val socket: ServerSocket =
        (tls?.serverContext?.serverSocketFactory?.createServerSocket(0, 64, InetAddress.getLoopbackAddress())
            ?: ServerSocket(0, 64, InetAddress.getLoopbackAddress()))
    private val scheme = if (tls == null) "http" else "https"
    private val peers = CopyOnWriteArrayList<Socket>()
    val accepted = AtomicInteger()

    fun url(path: String) = "$scheme://127.0.0.1:${socket.localPort}$path"

    init {
        thread(isDaemon = true, name = "stalled-body-accept") {
            try {
                while (true) {
                    val peer = socket.accept()
                    accepted.incrementAndGet()
                    peers += peer
                    thread(isDaemon = true, name = "stalled-body-peer") { serve(peer) }
                }
            } catch (_: IOException) {
                // The server closed.
            }
        }
    }

    private fun serve(peer: Socket) {
        try {
            val input = peer.getInputStream()
            val output = peer.getOutputStream()
            while (true) {
                val path = input.readRequestPath() ?: return
                if (path == "/stalled") {
                    output.write(STALLED_HEADERS.getValue(framing).toByteArray())
                    output.write(STALLED_PART.getValue(framing).toByteArray())
                    output.flush()
                    while (input.read() >= 0) { /* Stall until the peer goes away. */ }
                    return
                }
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                output.flush()
            }
        } catch (_: IOException) {
            // A cancelled client is what most of these tests are for.
        } finally {
            peer.runCatching { close() }
            peers -= peer
        }
    }

    private fun InputStream.readRequestPath(): String? {
        val head = ByteArrayOutputStream()
        while (true) {
            val next = read()
            if (next < 0) return null
            head.write(next)
            if (head.size() >= 4 && head.toString(Charsets.US_ASCII.name()).endsWith("\r\n\r\n")) break
        }
        return head.toString(Charsets.US_ASCII.name()).substringBefore('\r').split(' ')[1]
    }

    override fun close() {
        socket.close()
        peers.forEach { it.runCatching { close() } }
    }

    private companion object {
        val STALLED_HEADERS = mapOf(
            CancelRaceFraming.Chunked to "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n",
            CancelRaceFraming.FixedLength to "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n",
        )
        val STALLED_PART = mapOf(
            CancelRaceFraming.Chunked to "400\r\n" + "x".repeat(0x200),
            CancelRaceFraming.FixedLength to "x".repeat(0x200),
        )
    }
}

/** A throwaway self-signed TLS identity created per test: no key or root is committed or installed. */
internal class CancelRaceTls {
    private val certificate: X509Certificate
    val serverContext: SSLContext
    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw IOException("no client auth")
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.firstOrNull() != certificate) throw java.security.cert.CertificateException("unexpected peer")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(certificate)
    }
    val clientSocketFactory: SSLSocketFactory

    init {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = der(0x30, oid(1, 2, 840, 113549, 1, 1, 11), der(0x05))
        val name = der(0x30, der(0x31, der(0x30, oid(2, 5, 4, 3), der(0x0C, "localhost".toByteArray()))))
        val validity = der(0x30, der(0x17, "200101000000Z".toByteArray()), der(0x17, "490101000000Z".toByteArray()))
        val body = der(
            0x30, der(0xA0, der(0x02, byteArrayOf(2))), der(0x02, BigInteger.valueOf(System.nanoTime()).abs().toByteArray()),
            algorithm, name, validity, name, keys.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(body)
            sign()
        }
        certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            der(0x30, body, algorithm, der(0x03, byteArrayOf(0) + signature)).inputStream(),
        ) as X509Certificate
        val password = "fixture".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("fixture", keys.private, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        clientSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val content = parts.fold(ByteArray(0), ByteArray::plus)
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    private fun oid(first: Int, second: Int, vararg rest: Int): ByteArray {
        val encoded = ByteArrayOutputStream().apply { write(first * 40 + second) }
        rest.forEach { arc ->
            var shift = (31 - Integer.numberOfLeadingZeros(arc or 1)) / 7 * 7
            while (shift > 0) {
                encoded.write(((arc ushr shift) and 0x7F) or 0x80)
                shift -= 7
            }
            encoded.write(arc and 0x7F)
        }
        return der(0x06, encoded.toByteArray())
    }
}

internal fun pollUntil(timeoutMillis: Long = HARNESS_TIMEOUT_MILLIS, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(1)
    }
    return condition()
}

internal const val HARNESS_TIMEOUT_MILLIS = 15_000L
