package com.legitimateapps.dulcet.core

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.CFNetwork.CFNetworkCopySystemProxySettings
import platform.darwin.*
import kotlin.time.TimeSource

/**
 * The macOS loopback test shows URLSession accepts EOF between chunks without a terminating zero chunk.
 * Media requests therefore keep the raw HTTP/1.1 framing until it has been checked. Foundation's
 * socket streams retain platform TLS certificate and hostname validation; no trust override is set.
 * Redirects remain ordinary responses for the account client's existing redirect policy.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class StrictMediaProtocol : NSURLProtocol {
    @OverrideInit
    constructor(request: NSURLRequest, cachedResponse: NSCachedURLResponse?, client: NSURLProtocolClientProtocol?) :
        super(request, cachedResponse, client)

    companion object : NSURLProtocolMeta() {
        override fun canInitWithRequest(request: NSURLRequest): Boolean {
            val url = request.URL ?: return false
            if (url.scheme !in listOf("http", "https")) return false
            // Installed only on the media client, including any same-origin redirect target.
            return request.HTTPMethod == "GET"
        }
        override fun canonicalRequestForRequest(request: NSURLRequest): NSURLRequest = request
    }

    private val lock = NSLock()
    private var stopped = false
    private var input: NSInputStream? = null
    private var output: NSOutputStream? = null

    override fun stopLoading() {
        lock.lock()
        stopped = true
        input?.close()
        output?.close()
        lock.unlock()
    }

    override fun startLoading() {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            try {
                load()
            } catch (failure: Throwable) {
                if (!isStopped()) {
                    // Raw stream and parser errors can contain request targets. Publish only a code.
                    val code = when (failure) {
                        is MediaTimeout -> NSURLErrorTimedOut
                        is MediaTlsFailure -> failure.urlErrorCode
                        else -> NSURLErrorNetworkConnectionLost
                    }
                    client?.URLProtocol(this, didFailWithError = NSError.errorWithDomain(NSURLErrorDomain, code, null))
                }
            } finally {
                lock.lock()
                input?.close()
                output?.close()
                input = null
                output = null
                lock.unlock()
            }
        }
    }

    private fun isStopped(): Boolean {
        lock.lock()
        return try { stopped } finally { lock.unlock() }
    }

    private fun load() {
        check(NSURLProtocol.propertyForKey(STRICT_MEDIA_PROXY_KEY, request) != true)
        check(!systemProxyConfigured())
        val url = requireNotNull(request.URL)
        val host = requireNotNull(url.host).removeSurrounding("[", "]")
        val secure = url.scheme == "https"
        val port = url.port?.intValue ?: if (secure) 443 else 80
        val streams = memScoped {
            val read = alloc<ObjCObjectVar<NSInputStream?>>()
            val write = alloc<ObjCObjectVar<NSOutputStream?>>()
            NSStream.getStreamsToHostWithName(host, port.toLong(), read.ptr, write.ptr)
            requireNotNull(read.value) to requireNotNull(write.value)
        }
        lock.lock()
        try {
            if (stopped) return
            input = streams.first
            output = streams.second
        } finally { lock.unlock() }
        val read = streams.first
        val write = streams.second
        if (secure) {
            check(read.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, NSStreamSocketSecurityLevelKey))
            check(write.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, NSStreamSocketSecurityLevelKey))
        }
        read.open()
        write.open()
        val timeout = request.timeoutInterval.takeIf { it > 0 }?.coerceAtMost(30.0) ?: 30.0
        val deadline = TimeSource.Monotonic.markNow()
        fun streamFailure(): Nothing {
            val code = (read.streamError ?: write.streamError)?.code
            if (code in setOf(-9807L, -9808L, -9812L, -9813L, -9814L, -9815L, -9843L)) {
                throw MediaTlsFailure(when (code) {
                    -9814L -> NSURLErrorServerCertificateHasBadDate
                    -9815L -> NSURLErrorServerCertificateNotYetValid
                    else -> NSURLErrorServerCertificateUntrusted
                })
            }
            error("HTTP media stream failed")
        }
        fun waitUntil(ready: () -> Boolean) {
            while (!ready()) {
                check(!isStopped())
                if (read.streamStatus == NSStreamStatusError || write.streamStatus == NSStreamStatusError) streamFailure()
                if (deadline.elapsedNow().inWholeMilliseconds > (timeout * 1000).toLong()) throw MediaTimeout()
                NSThread.sleepForTimeInterval(0.005)
            }
        }
        val components = requireNotNull(NSURLComponents.componentsWithURL(url, resolvingAgainstBaseURL = false))
        val target = (components.percentEncodedPath?.takeIf { it.isNotEmpty() } ?: "/") +
            (components.percentEncodedQuery?.let { "?$it" } ?: "")
        val authority = if (':' in host) "[$host]" else host
        val hostHeader = request.valueForHTTPHeaderField("Host") ?: "$authority:$port"
        check(hostHeader.isNotEmpty() && '\r' !in hostHeader && '\n' !in hostHeader)
        val headers = (request.allHTTPHeaderFields ?: emptyMap<Any?, Any?>()).entries
            .filter { (name, _) -> name.toString().lowercase() !in setOf("connection", "accept-encoding", "host") }
        val head = buildString {
            append("GET $target HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Connection: close\r\nAccept-Encoding: identity\r\n")
            headers.forEach { (name, value) ->
                check('\r' !in name.toString() && '\n' !in name.toString())
                check('\r' !in value.toString() && '\n' !in value.toString())
                append("$name: $value\r\n")
            }
            append("\r\n")
        }.encodeToByteArray()
        var sent = 0
        head.usePinned { pinned ->
            while (sent < head.size) {
                waitUntil { write.hasSpaceAvailable }
                val count = write.write(pinned.addressOf(sent).reinterpret(), (head.size - sent).toULong()).toInt()
                if (count <= 0) streamFailure()
                sent += count
            }
        }
        val source = MediaReader { buffer ->
            waitUntil { read.hasBytesAvailable || read.streamStatus == NSStreamStatusAtEnd || read.streamStatus == NSStreamStatusClosed }
            if (read.streamStatus == NSStreamStatusAtEnd || read.streamStatus == NSStreamStatusClosed) -1 else
                buffer.usePinned { read.read(it.addressOf(0).reinterpret(), buffer.size.toULong()).toInt() }
                    .also { if (it < 0) streamFailure() }
        }
        var status: Int
        var responseHeaders: LinkedHashMap<String, String>
        var informationalResponses = 0
        do {
            val statusLine = source.line()
            check(statusLine.startsWith("HTTP/1.0 ") || statusLine.startsWith("HTTP/1.1 "))
            status = requireNotNull(statusLine.split(' ').getOrNull(1)?.toIntOrNull())
            check(status in 100..599 && status != 101)
            responseHeaders = linkedMapOf()
            var headerBytes = statusLine.length
            while (true) {
                val line = source.line()
                headerBytes += line.length
                check(headerBytes <= 64 * 1024)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                check(colon > 0 && !line[0].isWhitespace())
                val name = line.substring(0, colon)
                check(name.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it in "!#$%&'*+-.^_`|~" })
                val existing = responseHeaders.keys.firstOrNull { it.equals(name, true) }
                val value = line.substring(colon + 1).trim()
                if (existing != null) {
                    check(!name.equals("Content-Length", true) && !name.equals("Transfer-Encoding", true))
                    responseHeaders[existing] = responseHeaders.getValue(existing) + ", " + value
                } else responseHeaders[name] = value
            }
            check(++informationalResponses <= 16)
        } while (status < 200)
        if (status == 407 || (status == 401 && responseHeaders.keys.any { it.equals("WWW-Authenticate", true) })) {
            (NSURLProtocol.propertyForKey(STRICT_MEDIA_CHALLENGE_KEY, request) as? UnsupportedAuthenticationChallengeTracker)
                ?.markUnsupported()
            error("Unsupported media authentication challenge")
        }
        val response = NSHTTPURLResponse(url, status.toLong(), "HTTP/1.1", responseHeaders.mapKeys { it.key as Any? })
        client?.URLProtocol(this, didReceiveResponse = response, cacheStoragePolicy = NSURLCacheStoragePolicy.NSURLCacheStorageNotAllowed)
        fun emit(bytes: ByteArray) {
            check(!isStopped())
            bytes.usePinned {
                client?.URLProtocol(this, didLoadData = NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()))
            }
        }
        val encoding = responseHeaders.entries.firstOrNull { it.key.equals("Content-Encoding", true) }?.value
        check(encoding == null || encoding.equals("identity", true))
        val transfer = responseHeaders.entries.firstOrNull { it.key.equals("Transfer-Encoding", true) }?.value
        val length = responseHeaders.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value
        if (status == 204 || status == 304) {
            check(transfer == null)
        } else if (transfer != null) {
            check(length == null && transfer.equals("chunked", true))
            while (true) {
                val line = source.line()
                val sizeText = line.substringBefore(';')
                check(sizeText.isNotEmpty() && sizeText.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
                val count = requireNotNull(sizeText.toLongOrNull(16))
                if (count == 0L) {
                    var trailerBytes = 0
                    while (true) {
                        val trailer = source.line()
                        trailerBytes += trailer.length
                        check(trailerBytes <= 64 * 1024)
                        if (trailer.isEmpty()) break
                        val colon = trailer.indexOf(':')
                        check(colon > 0 && trailer.substring(0, colon).all {
                            it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it in "!#$%&'*+-.^_`|~"
                        })
                    }
                    break
                }
                source.copy(count, ::emit)
                check(source.byte() == 13 && source.byte() == 10)
            }
        } else if (length != null) {
            check(length.isNotEmpty() && length.all { it in '0'..'9' })
            source.copy(requireNotNull(length.toLongOrNull()), ::emit)
        } else {
            source.copyToEnd(::emit)
        }
        check(!isStopped())
        client?.URLProtocolDidFinishLoading(this)
    }
}

internal const val STRICT_MEDIA_PROXY_KEY = "DulcetStrictMediaProxy"
internal const val STRICT_MEDIA_CHALLENGE_KEY = "DulcetStrictMediaChallenge"

private class MediaTimeout : Exception()
private class MediaTlsFailure(val urlErrorCode: Long) : Exception()

@OptIn(ExperimentalForeignApi::class)
private fun systemProxyConfigured(): Boolean {
    val settings = CFBridgingRelease(CFNetworkCopySystemProxySettings()) as? Map<*, *> ?: return false
    fun enabled(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.any { (key, item) ->
            (key in setOf("HTTPEnable", "HTTPSEnable", "SOCKSEnable", "ProxyAutoConfigEnable", "ProxyAutoDiscoveryEnable") &&
                ((item as? Number)?.toInt() ?: (item as? NSNumber)?.intValue ?: 0) != 0) || enabled(item)
        }
        else -> false
    }
    return enabled(settings)
}

private class MediaReader(private val read: (ByteArray) -> Int) {
    private val buffer = ByteArray(16 * 1024)
    private var offset = 0
    private var available = 0
    private fun fill(): Boolean {
        if (offset < available) return true
        available = read(buffer)
        offset = 0
        return available > 0
    }
    fun byte(): Int {
        check(fill()) { "Incomplete HTTP media body" }
        return buffer[offset++].toInt() and 255
    }
    fun line(): String {
        val result = StringBuilder()
        while (true) {
            val next = byte()
            if (next == 13) {
                check(byte() == 10)
                return result.toString()
            }
            check(next != 10 && result.length < 8192)
            result.append(next.toChar())
        }
    }
    fun copy(count: Long, emit: (ByteArray) -> Unit) {
        var remaining = count
        while (remaining > 0) {
            check(fill()) { "Incomplete HTTP media body" }
            val take = minOf(remaining, (available - offset).toLong()).toInt()
            emit(buffer.copyOfRange(offset, offset + take))
            offset += take
            remaining -= take
        }
    }
    fun copyToEnd(emit: (ByteArray) -> Unit) {
        while (fill()) {
            emit(buffer.copyOfRange(offset, available))
            offset = available
        }
    }
}
