package com.legitimateapps.dulcet.server.integrations

import com.legitimateapps.dulcet.server.discover.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal const val IMAGE_TTL = 30 * DiscoverCache.DAY
internal val NOT_FOUND = UpstreamResponse(404, emptyMap(), ByteArray(0))

/** Signature, structure and size checks shared with Discover covers; anything else is never cached or served. */
internal fun validImage(bytes: ByteArray): ByteArray {
    if (bytes.size > CoverArtClient.MAX_BYTES) throw UpstreamUnavailable(reason = UpstreamFailure.Body)
    CoverArtClient.imageType(bytes)
    return bytes
}
internal fun isImage(bytes: ByteArray) = runCatching { validImage(bytes) }.isSuccess

/** 30 days for an image, a day for a 404 (the shared cache's negative lifetime); null when there is none. */
internal suspend fun cachedImage(cache: DiscoverCache, key: CacheKey, stamp: String, fetch: suspend () -> UpstreamResponse): RemoteImage? = try {
    val bytes = cache.read(key, IMAGE_TTL, ::validImage, fetch).value
    RemoteImage(bytes, CoverArtClient.imageType(bytes), stamp)
} catch (_: UpstreamNotFound) { null }

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.number(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

/**
 * Fetches an image from an outside host the operator allowlisted (exact host names). HTTPS only, every
 * DNS answer must be a public address and the checked address is pinned; redirects stay on allowlisted
 * HTTPS hosts. No credential is ever sent. Only the explicit Testing-profile override admits a loopback
 * fixture over HTTP.
 */
internal class AllowlistedImageFetch(
    private val http: PoliteHttp, private val hosts: Set<String>, private val testingOverride: () -> Boolean,
    private val resolver: (String) -> Array<InetAddress>,
) {
    fun allowed(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return uri.host != null && uri.host.lowercase() in hosts && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.port != 0 && uri.port <= 65535 && uri.toString().length <= 8192 && (scheme == "https" || (scheme == "http" && testingOverride()))
    }
    suspend fun fetch(start: URI): UpstreamResponse {
        var uri = start
        repeat(4) { hop ->
            if (!allowed(uri)) return NOT_FOUND
            val target = uri
            val addresses = withContext(Dispatchers.IO) {
                try { CompletableFuture.supplyAsync { resolver(target.host) }.get(5, TimeUnit.SECONDS) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { throw UpstreamUnavailable(reason = UpstreamFailure.DnsTransport) }
            }
            if (addresses.isEmpty() || addresses.any { !CoverArtClient.publicAddress(it) && !(testingOverride() && it.isLoopbackAddress) }) return NOT_FOUND
            val response = http.request(uri, CoverArtClient.MAX_BYTES, addresses.first())
            if (response.status !in listOf(301, 302, 303, 307, 308)) return response
            if (hop == 3) return NOT_FOUND
            val next = response.headers["location"]?.singleOrNull()?.let { runCatching { uri.resolve(it) }.getOrNull() } ?: return NOT_FOUND
            if (uri.scheme.equals("https", true) && !next.scheme.equals("https", true)) return NOT_FOUND
            uri = next
        }
        return NOT_FOUND
    }
}
