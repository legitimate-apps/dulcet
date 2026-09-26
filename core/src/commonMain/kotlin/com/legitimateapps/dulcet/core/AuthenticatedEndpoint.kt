package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable

internal data class AuthenticatedEndpointCredentials(
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "AuthenticatedEndpointCredentials(<redacted>)"
}

internal data class AuthenticatedEndpointResponse(
    val statusCode: Int,
    val body: ByteArray,
    val redactedUrl: String,
    val headers: AuthenticatedEndpointResponseHeaders,
    val requestTrace: RequestTrace,
)

internal data class AuthenticatedEndpointResponseHeaders(
    val contentType: String?,
    val contentLength: PlaybackContentLength?,
    val retryAfter: String?,
    val acceptRanges: String?,
    val contentRange: String?,
    /** `X-Total-Count`, which `getAlbumList2` carries as a header and never in the body (spec §16.9). */
    val totalCount: String? = null,
)

private data class AuthenticatedEndpointHttpSnapshot(
    val statusCode: Int,
    val body: ByteArray,
    val headers: AuthenticatedEndpointResponseHeaders,
    val location: String?,
)

internal data class AuthenticatedEndpointRequestOptions(
    /** An already-rendered HTTP byte range, for example `bytes=0-65535`. */
    val range: String? = null,
    /** How a successful full response's Content-Length must be interpreted. */
    val contentLengthKind: AuthenticatedEndpointContentLengthKind =
        AuthenticatedEndpointContentLengthKind.Exact,
    /**
     * The most body bytes this request may read, or null for the whole body. Beyond it the request
     * fails with [bodyBeyondLimit] instead of reading on. For a whole GET only. How early it stops
     * depends on the engine (§18.4 residual 1). On the JVM engine a declared Content-Length beyond
     * the limit is refused before a byte of the body is read, and a body without one at the first
     * read past the limit. The Darwin engine hands over the headers only with the first body
     * bytes: a declared length is refused once some of the body has arrived, and a server that
     * declares a length and then sends nothing ends as [DomainError.Transport.Timeout], never as a
     * refusal.
     */
    val maxBodyBytes: Int? = null,
) {
    init {
        require(range == null || BYTE_RANGE_PATTERN.matches(range))
        require(maxBodyBytes == null || maxBodyBytes > 0)
        require(
            maxBodyBytes == null ||
                (range == null && contentLengthKind == AuthenticatedEndpointContentLengthKind.Exact),
        )
    }

    private companion object {
        val BYTE_RANGE_PATTERN = Regex("bytes=[0-9]+-(?:[0-9]+)?")
    }
}

internal enum class AuthenticatedEndpointContentLengthKind {
    Exact,
    Estimated,
}

/** Signed request material for a platform loader. Rendering is deliberately redacted. */
internal data class AuthenticatedEndpointPreparedRequest(
    val url: String,
    val hostHeader: String?,
    val rangeHeader: String?,
) {
    override fun toString(): String = "AuthenticatedEndpointPreparedRequest(<redacted>)"
}

private fun Throwable.isPrematureEndOfHttpBody(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current::class.simpleName == "EOFException") return true
        current = current.cause
    }
    return false
}

/**
 * The single authenticated request implementation used by OpenSubsonic endpoints.
 * Authentication, redirect handling, local-HTTP policy, and query redaction therefore cannot drift
 * between browse, artwork, and later endpoint-specific clients.
 */
internal class AuthenticatedEndpointClient(
    private val credentials: AuthenticatedEndpointCredentials,
    operationName: String,
    saltSource: SaltSource? = null,
    logSink: LogSink? = null,
    hostResolver: HostResolver = systemHostResolver(),
) {
    private val saltSource = saltSource ?: AccountConnectionContract.secureSaltSource()
    private val localHttpPolicy = LocalHttpConnectionPolicy(hostResolver)
    private val traceRecorder = RequestTraceRecorder(logSink, operationName)
    private val clientTransport = AccountClientTransport.Default()
    private val client: HttpClient = createAccountHttpClient(clientTransport) {
        expectSuccess = false
        followRedirects = false
        install(RequestTracePlugin) { observe = traceRecorder::observe }
        install(HttpTimeout) {
            connectTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

    suspend fun request(
        endpoint: String,
        parameters: Map<String, String>,
        options: AuthenticatedEndpointRequestOptions = AuthenticatedEndpointRequestOptions(),
    ): AuthenticatedEndpointResponse = execute(
        endpoint = endpoint,
        parameters = parameters,
        options = options,
        jsonBody = null,
    )

    /**
     * A request whose parameters repeat a name, in order (`songId`, `songIdToAdd`,
     * `songIndexToRemove` — spec §18.6). [formPost] sends every parameter, credentials included, as
     * an `application/x-www-form-urlencoded` body — the OpenSubsonic `formPost` extension — so a
     * long list never meets a proxy's URL-length limit and nothing rides in the URL at all.
     */
    suspend fun requestRepeated(
        endpoint: String,
        parameters: List<Pair<String, String>>,
        formPost: Boolean,
    ): AuthenticatedEndpointResponse = execute(
        endpoint = endpoint,
        parameters = emptyMap(),
        options = AuthenticatedEndpointRequestOptions(),
        jsonBody = null,
        repeated = parameters,
        formPost = formPost,
    )

    suspend fun postJson(
        endpoint: String,
        parameters: Map<String, String>,
        jsonBody: String,
        options: AuthenticatedEndpointRequestOptions = AuthenticatedEndpointRequestOptions(),
    ): AuthenticatedEndpointResponse {
        require(jsonBody.isNotBlank())
        require(options.maxBodyBytes == null)
        return execute(endpoint, parameters, options, jsonBody)
    }

    suspend fun prepareGetRequest(
        endpoint: String,
        parameters: Map<String, String>,
        options: AuthenticatedEndpointRequestOptions = AuthenticatedEndpointRequestOptions(),
    ): AuthenticatedEndpointPreparedRequest {
        val common = authenticatedParameters(parameters)
        val logicalUrl = "${credentials.normalizedBaseUrl}/rest/$endpoint.view"
        val target = localHttpPolicy.targetFor(logicalUrl, credentials.allowLocalHttp)
        val url = URLBuilder(target.url).apply {
            common.forEach { (name, value) -> this.parameters.append(name, value) }
        }.buildString()
        return AuthenticatedEndpointPreparedRequest(url, target.hostHeader, options.range)
    }

    private suspend fun execute(
        endpoint: String,
        parameters: Map<String, String>,
        options: AuthenticatedEndpointRequestOptions,
        jsonBody: String?,
        repeated: List<Pair<String, String>> = emptyList(),
        formPost: Boolean = false,
    ): AuthenticatedEndpointResponse {
        require(!formPost || jsonBody == null)
        val common = authenticatedParameters(parameters)
        var currentUrl = "${credentials.normalizedBaseUrl}/rest/$endpoint.view"
        var redirects = 0
        while (true) {
            val target = localHttpPolicy.targetFor(currentUrl, credentials.allowLocalHttp)
            val snapshot = try {
                val maxBodyBytes = options.maxBodyBytes
                if (jsonBody == null && !formPost && maxBodyBytes != null) {
                    client.prepareGet(target.url) {
                        applyRequestParts(target.hostHeader, common, options, repeated)
                    }.execute { response ->
                        response.toSnapshot(response.bodyAsBytesWithin(maxBodyBytes), options)
                    }
                } else if (
                    jsonBody == null && !formPost &&
                    options.contentLengthKind == AuthenticatedEndpointContentLengthKind.Estimated &&
                    options.range == null
                ) {
                    var completedSnapshot: AuthenticatedEndpointHttpSnapshot? = null
                    try {
                        client.prepareGet(target.url) {
                            applyRequestParts(target.hostHeader, common, options, repeated)
                        }.execute { response ->
                            response.toSnapshot(
                                body = if (response.status.value in 200..299) {
                                    response.bodyAsBytesAllowingEstimatedEnd()
                                } else {
                                    response.bodyAsBytes()
                                },
                                options = options,
                            ).also { completedSnapshot = it }
                        }
                    } catch (failure: Throwable) {
                        completedSnapshot?.takeIf {
                            it.body.isNotEmpty() && isPlatformEstimatedLengthCompletion(failure)
                        } ?: throw failure
                    }
                } else {
                    val response = if (formPost) {
                        client.post(target.url) {
                            target.hostHeader?.let { header(HttpHeaders.Host, it) }
                            setBody(
                                FormDataContent(
                                    Parameters.build {
                                        common.forEach { (name, value) -> append(name, value) }
                                        repeated.forEach { (name, value) -> append(name, value) }
                                    },
                                ),
                            )
                        }
                    } else if (jsonBody == null) {
                        client.get(target.url) {
                            applyRequestParts(target.hostHeader, common, options, repeated)
                        }
                    } else {
                        client.post(target.url) {
                            applyRequestParts(target.hostHeader, common, options)
                            contentType(ContentType.Application.Json)
                            setBody(jsonBody)
                        }
                    }
                    response.toSnapshot(response.bodyAsBytes(), options)
                }
            } catch (failure: Throwable) {
                if (clientTransport.challengeTracker.consumeUnsupported()) {
                    throw AuthenticatedEndpointFailure(
                        DomainError.Auth.UnsupportedAuthenticationChallenge,
                    )
                }
                throw failure
            }
            val redactedUrl = traceRecorder.latestRedactedUrl()
            if (snapshot.statusCode !in REDIRECT_STATUS_CODES) {
                return AuthenticatedEndpointResponse(
                    statusCode = snapshot.statusCode,
                    body = snapshot.body,
                    redactedUrl = redactedUrl,
                    headers = snapshot.headers,
                    requestTrace = traceRecorder.latestTrace(),
                )
            }
            val location = snapshot.location
                ?: return AuthenticatedEndpointResponse(
                    statusCode = snapshot.statusCode,
                    body = snapshot.body,
                    redactedUrl = redactedUrl,
                    headers = snapshot.headers,
                    requestTrace = traceRecorder.latestTrace(),
                )
            val nextUrl = resolveRedirectUrl(currentUrl, location)
                ?: throw AuthenticatedEndpointFailure(
                    DomainError.Security.RedirectRejected(RedirectRejectionReason.InvalidLocation),
                )
            if (localHttpPolicy.leavesLocalNetwork(currentUrl, nextUrl)) {
                throw AuthenticatedEndpointFailure(
                    DomainError.Auth.CrossOriginRedirectRejected(nextUrl.redirectTargetHost()),
                )
            }
            when (
                val decision = AccountConnectionContract.redirectDecision(
                    currentUrl,
                    nextUrl,
                    redirects,
                )
            ) {
                RedirectPolicyDecision.PreserveCredentials -> Unit
                is RedirectPolicyDecision.Reject -> throw AuthenticatedEndpointFailure(
                    if (decision.reason == RedirectRejectionReason.CrossOrigin) {
                        DomainError.Auth.CrossOriginRedirectRejected(nextUrl.redirectTargetHost())
                    } else {
                        DomainError.Security.RedirectRejected(decision.reason)
                    },
                )
            }
            currentUrl = nextUrl.withoutQuery()
            redirects += 1
        }
    }

    private fun io.ktor.client.statement.HttpResponse.toSnapshot(
        body: ByteArray,
        options: AuthenticatedEndpointRequestOptions,
    ) = AuthenticatedEndpointHttpSnapshot(
        statusCode = status.value,
        body = body,
        headers = AuthenticatedEndpointResponseHeaders(
            contentType = headers[HttpHeaders.ContentType],
            contentLength = playbackContentLength(options),
            retryAfter = headers[HttpHeaders.RetryAfter],
            acceptRanges = headers[HttpHeaders.AcceptRanges],
            contentRange = headers[HttpHeaders.ContentRange],
            totalCount = headers["X-Total-Count"],
        ),
        location = headers[HttpHeaders.Location],
    )

    private fun io.ktor.client.statement.HttpResponse.playbackContentLength(
        options: AuthenticatedEndpointRequestOptions,
    ): PlaybackContentLength? = headers[HttpHeaders.ContentLength]
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let { byteCount ->
            if (
                options.contentLengthKind == AuthenticatedEndpointContentLengthKind.Estimated &&
                options.range == null &&
                status.value in 200..299
            ) {
                PlaybackContentLength.Estimated(byteCount)
            } else {
                PlaybackContentLength.Exact(byteCount)
            }
        }

    /**
     * Ktor correctly reports an exact Content-Length mismatch as a terminal read failure.
     * Navidrome's estimateContentLength contract is different: all bytes already received are the
     * complete representation even when the estimate overshoots. Read incrementally so those bytes
     * survive CIO's EOF or Darwin's specifically typed completion; every other transport failure
     * still propagates.
     */
    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsBytesAllowingEstimatedEnd(): ByteArray {
        val channel = bodyAsChannel()
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        val scratch = ByteArray(16 * 1024)
        while (true) {
            val count = try {
                channel.readAvailable(scratch, 0, scratch.size)
            } catch (failure: Throwable) {
                if (
                    total > 0 &&
                    (failure.isPrematureEndOfHttpBody() ||
                        isPlatformEstimatedLengthCompletion(failure))
                ) {
                    break
                }
                throw failure
            }
            if (count < 0) break
            if (count == 0) continue
            chunks += scratch.copyOf(count)
            total += count
        }
        return concatenate(chunks, total)
    }

    /**
     * The body, read no further than [limit] bytes (see [AuthenticatedEndpointRequestOptions.maxBodyBytes]).
     * Leaving the enclosing `execute` block by throwing cancels the response, so the connection is
     * closed rather than drained. On the JVM engine the rest of an oversized body is then never
     * downloaded, beyond what the sockets had already buffered. The Darwin engine keeps receiving
     * into its own buffer until the cancellation reaches the task, so several megabytes more cross
     * the network before the refusal (§18.4 residual 1 has the measurements).
     */
    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsBytesWithin(limit: Int): ByteArray {
        val refusal = bodyBeyondLimit(status.value)
        val declared = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > limit) throw AuthenticatedEndpointFailure(refusal)
        val channel = bodyAsChannel()
        val chunks = mutableListOf<ByteArray>()
        var total = 0L
        val scratch = ByteArray(16 * 1024)
        while (true) {
            val count = channel.readAvailable(scratch, 0, scratch.size)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) throw AuthenticatedEndpointFailure(refusal)
            chunks += scratch.copyOf(count)
        }
        return concatenate(chunks, total.toInt())
    }

    private fun concatenate(chunks: List<ByteArray>, total: Int): ByteArray = ByteArray(total).also { result ->
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
    }

    private fun authenticatedParameters(parameters: Map<String, String>): Map<String, String> {
        val salt = saltSource.nextSalt()
        require(SALT_PATTERN.matches(salt)) {
            "SaltSource must return exactly 16 bytes as lowercase hex"
        }
        return linkedMapOf(
            "v" to AccountConnectionContract.protocolVersion,
            "c" to CLIENT_NAME,
            "f" to "json",
        ).apply {
            putAll(parameters)
            put("u", credentials.username)
            put("t", AccountConnectionContract.saltedToken(credentials.password, salt))
            put("s", salt)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyRequestParts(
        hostHeader: String?,
        parameters: Map<String, String>,
        options: AuthenticatedEndpointRequestOptions,
        repeated: List<Pair<String, String>> = emptyList(),
    ) {
        hostHeader?.let { header(HttpHeaders.Host, it) }
        parameters.forEach { (key, value) -> parameter(key, value) }
        repeated.forEach { (key, value) -> parameter(key, value) }
        options.range?.let { header(HttpHeaders.Range, it) }
    }

    fun close() {
        client.close()
    }

    private companion object {
        const val CLIENT_NAME = "Dulcet"
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val SALT_PATTERN = Regex("[0-9a-f]{32}")
    }
}

internal class AuthenticatedEndpointFailure(val error: DomainError) : Exception()
