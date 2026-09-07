package com.legitimateapps.dulcet.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** No platform exception/cause or signed URI may cross into Media3's own diagnostics. */
internal class AndroidPlaybackIOException(val error: DomainError) : IOException("Playback resource rejected")

internal class AndroidPlaybackResponse(
    val status: Int,
    val headers: AuthenticatedEndpointResponseHeaders,
    val input: InputStream,
    val close: () -> Unit,
)

internal fun interface AndroidPlaybackResource {
    fun open(position: Long, length: Long): AndroidPlaybackResponse
}

/** The factory belongs to an immutable attempt, including its signature witness. */
internal class AndroidPlaybackDataSourceFactory(
    private val plan: RemotePlaybackWirePlan,
    private val resource: AndroidPlaybackResource,
    private val observed: (Long) -> Unit = {},
) : DataSource.Factory {
    @Volatile private var signatureValidated = false

    override fun createDataSource(): DataSource = object : BaseDataSource(true) {
        private var response: AndroidPlaybackResponse? = null
        private var spec: DataSpec? = null
        private var prefix = byteArrayOf()
        private var prefixOffset = 0
        private var remaining = C.LENGTH_UNSET.toLong()
        private var exactRemaining: Long? = null
        private var started = false

        override fun open(dataSpec: DataSpec): Long = closed {
            check(response == null)
            // Reject raw URLs even when accidentally supplied by a caller.
            if (dataSpec.uri.scheme != "dulcet" || dataSpec.uri.query != null)
                throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
            transferInitializing(dataSpec)
            val loaded = resource.open(dataSpec.position, dataSpec.length)
            response = loaded
            spec = dataSpec
            val buffer = ByteArrayOutputStream()
            val scratch = ByteArray(8192)
            // Read the actual engine response, never a separate preflight. Large ID3 tags are
            // bounded; unsupported enormous metadata fails closed before any bytes escape.
            var validated = false
            while (buffer.size() < MAX_PREFIX && !validated) {
                // InputStream may return a short read at any byte boundary, including inside RIFF.
                // Accumulate a prefix before classifying it; a socket read is not a payload boundary.
                var count = 0
                val target = minOf(buffer.size() + scratch.size, MAX_PREFIX)
                while (buffer.size() < target) {
                    count = loaded.input.read(scratch, 0, minOf(scratch.size, target - buffer.size()))
                    if (count < 0) break
                    if (count == 0) throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
                    buffer.write(scratch, 0, count)
                }
                prefix = buffer.toByteArray()
                val needsSignature = dataSpec.position == 0L
                if (!needsSignature && !signatureValidated)
                    throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
                val validation = validateAndroidPlaybackPrefix(plan, loaded, prefix, needsSignature)
                when (validation) {
                    is PlaybackStreamValidationResult.Audio -> validated = true
                    is PlaybackStreamValidationResult.Failure -> {
                        if (validation.error != DomainError.Protocol.UnexpectedBinary || count < 0 ||
                            !needsSignature || plan.expectedContainer != AudioContainer.Flac ||
                            !prefix.take(3).toByteArray().contentEquals("ID3".toByteArray()))
                            throw AndroidPlaybackIOException(validation.error)
                    }
                }
                if (count < 0) break
            }
            if (!validated) throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
            val total = validateRange(loaded, dataSpec.position, dataSpec.length)
            if (dataSpec.position == 0L) signatureValidated = true
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong())
                minOf(dataSpec.length, total?.minus(dataSpec.position) ?: dataSpec.length)
                else total?.minus(dataSpec.position) ?: C.LENGTH_UNSET.toLong()
            exactRemaining = if (loaded.headers.contentLength is PlaybackContentLength.Exact)
                loaded.headers.contentLength.byteCount else null
            if (exactRemaining != null && prefix.size > exactRemaining!!)
                throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
            prefixOffset = 0
            transferStarted(dataSpec)
            started = true
            remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = closed {
            if (length == 0) return@closed 0
            if (remaining == 0L) return@closed C.RESULT_END_OF_INPUT
            val maximum = if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length
            val count = if (prefixOffset < prefix.size) {
                minOf(maximum, prefix.size - prefixOffset).also {
                    prefix.copyInto(buffer, offset, prefixOffset, prefixOffset + it)
                    prefixOffset += it
                }
            } else checkNotNull(response).input.read(buffer, offset, maximum)
            if (count < 0) {
                if (exactRemaining?.let { it > 0 } == true)
                    throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
                return@closed C.RESULT_END_OF_INPUT
            }
            if (remaining >= 0) remaining -= count
            exactRemaining = exactRemaining?.minus(count)
            bytesTransferred(count)
            observed(count.toLong())
            count
        }

        override fun getUri(): Uri? = spec?.uri
        override fun close() {
            try { response?.close?.invoke() } catch (_: Exception) { /* nothing diagnostic escapes */ }
            response = null
            spec = null
            prefix = byteArrayOf()
            if (started) transferEnded()
            started = false
        }

        private inline fun <T> closed(block: () -> T): T = try { block() }
        catch (error: AndroidPlaybackIOException) { close(); throw error }
        catch (_: Exception) { close(); throw AndroidPlaybackIOException(DomainError.Transport.Unreachable) }
    }

    private companion object { const val MAX_PREFIX = 16 * 1024 * 1024 }
}

internal fun validateAndroidPlaybackPrefix(
    plan: RemotePlaybackWirePlan,
    response: AndroidPlaybackResponse,
    bytes: ByteArray,
    requiresSignature: Boolean,
): PlaybackStreamValidationResult = PlaybackStreamValidator.validate(
    AuthenticatedEndpointResponse(
        response.status, bytes, "<redacted-url>",
        // Full-body integrity is checked as reads finish, not against this prefix.
        response.headers.copy(contentLength = null),
        RequestTrace.observed(
            endpoint = "playback-resource", method = "GET", redactedUrl = "<redacted-url>",
            authenticationLocation = AuthenticationLocation.Query,
            queryAuthenticationParameters = setOf(AuthenticationParameter.Username,
                AuthenticationParameter.SaltedToken, AuthenticationParameter.Salt),
            formAuthenticationParameters = emptySet(), channels = emptySet(),
            requestedProtocolVersion = AccountConnectionContract.protocolVersion, saltFingerprint = null,
        ),
    ), plan.expectedContainer, requiresSignature,
)

private fun validateRange(response: AndroidPlaybackResponse, position: Long, length: Long): Long? {
    if (response.status == 206) {
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
            .matchEntire(response.headers.contentRange.orEmpty())
            ?: throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
        val (start, end, total) = match.destructured.toList().map { it.toLongOrNull()
            ?: throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary) }
        if (start != position || end < start || total <= end ||
            (length >= 0 && end - start + 1 > length) ||
            (response.headers.contentLength as? PlaybackContentLength.Exact)?.byteCount != end - start + 1)
            throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
        return total
    }
    if (response.status != 200 || position != 0L)
        throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
    return (response.headers.contentLength as? PlaybackContentLength.Exact)?.byteCount
}

/** Signed requests stay wholly in this loader; ExoPlayer only sees dulcet://resource. */
internal class AndroidHttpPlaybackResource(
    private val account: PlaybackEndpointAccount,
    private val plan: RemotePlaybackWirePlan,
    private val authorizer: AuthenticatedEndpointClient,
) : AndroidPlaybackResource {
    override fun open(position: Long, length: Long): AndroidPlaybackResponse = try {
        val range = if (position > 0 || length >= 0) "bytes=$position-" +
            if (length >= 0) Math.addExact(position, length - 1).toString() else "" else null
        val prepared = runBlocking { authorizer.prepareGetRequest(plan.endpoint, plan.parameters,
            AuthenticatedEndpointRequestOptions(range = range)) }
        var url = prepared.url
        var hostHeader = prepared.hostHeader
        var redirects = 0
        while (true) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                hostHeader?.let { connection.setRequestProperty("Host", it) }
                range?.let { connection.setRequestProperty("Range", it) }
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
                    var proposed = URL(URL(url), location).toString()
                    when (val decision = AccountConnectionContract.redirectDecision(url, proposed, redirects)) {
                        RedirectPolicyDecision.PreserveCredentials -> Unit
                        is RedirectPolicyDecision.Reject -> {
                            if (decision.reason != RedirectRejectionReason.CrossOrigin)
                                throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
                            val parsed = Uri.parse(proposed)
                            val rebuilt = parsed.buildUpon().clearQuery()
                            parsed.queryParameterNames.filterNot { it.lowercase() in setOf("u", "t", "s", "p") }
                                .forEach { name -> parsed.getQueryParameters(name).forEach { rebuilt.appendQueryParameter(name, it) } }
                            proposed = rebuilt.build().toString()
                        }
                    }
                    val target = runBlocking { LocalHttpConnectionPolicy(systemHostResolver())
                        .targetFor(proposed, account.allowLocalHttp) }
                    url = target.url + Uri.parse(proposed).encodedQuery?.let { "?$it" }.orEmpty()
                    hostHeader = target.hostHeader
                    redirects++
                    connection.disconnect()
                    continue
                }
                val declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                val headers = AuthenticatedEndpointResponseHeaders(
                    connection.contentType,
                    declaredLength?.let { if (status == 200 && plan.usesEstimatedLegacyContentLength())
                        PlaybackContentLength.Estimated(it) else PlaybackContentLength.Exact(it) },
                    connection.getHeaderField("Retry-After"), connection.getHeaderField("Accept-Ranges"),
                    connection.getHeaderField("Content-Range"),
                )
                val input = if (status >= 400) connection.errorStream ?: java.io.ByteArrayInputStream(byteArrayOf())
                    else connection.inputStream
                return AndroidPlaybackResponse(status, headers, input) {
                    try { input.close() } finally { connection.disconnect() }
                }
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
    } catch (error: AndroidPlaybackIOException) { throw error }
    catch (_: Exception) { throw AndroidPlaybackIOException(DomainError.Transport.Unreachable) }
}
