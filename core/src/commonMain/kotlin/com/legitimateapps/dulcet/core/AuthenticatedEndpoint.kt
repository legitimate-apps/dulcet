package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
) {
    init {
        require(range == null || BYTE_RANGE_PATTERN.matches(range))
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
    private val client: HttpClient = createAccountHttpClient(AccountClientTransport.Default) {
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

    suspend fun postJson(
        endpoint: String,
        parameters: Map<String, String>,
        jsonBody: String,
        options: AuthenticatedEndpointRequestOptions = AuthenticatedEndpointRequestOptions(),
    ): AuthenticatedEndpointResponse {
        require(jsonBody.isNotBlank())
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
    ): AuthenticatedEndpointResponse {
        val common = authenticatedParameters(parameters)
        var currentUrl = "${credentials.normalizedBaseUrl}/rest/$endpoint.view"
        var redirects = 0
        while (true) {
            val target = localHttpPolicy.targetFor(currentUrl, credentials.allowLocalHttp)
            val snapshot = if (
                jsonBody == null &&
                options.contentLengthKind == AuthenticatedEndpointContentLengthKind.Estimated &&
                options.range == null
            ) {
                client.prepareGet(target.url) {
                    applyRequestParts(target.hostHeader, common, options)
                }.execute { response ->
                    response.toSnapshot(
                        body = if (response.status.value in 200..299) {
                            response.bodyAsBytesAllowingEstimatedEnd()
                        } else {
                            response.bodyAsBytes()
                        },
                        options = options,
                    )
                }
            } else {
                val response = if (jsonBody == null) {
                    client.get(target.url) {
                        applyRequestParts(target.hostHeader, common, options)
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
     * Ktor correctly reports an exact Content-Length mismatch as EOF. Navidrome's
     * estimateContentLength contract is different: all bytes already received are the complete
     * representation even when the estimate overshoots. Read incrementally so those bytes survive
     * the terminal EOF; every other transport failure still propagates.
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
                if (total > 0 && failure.isPrematureEndOfHttpBody()) break
                throw failure
            }
            if (count < 0) break
            if (count == 0) continue
            chunks += scratch.copyOf(count)
            total += count
        }
        return ByteArray(total).also { result ->
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(result, destinationOffset = offset)
                offset += chunk.size
            }
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
    ) {
        hostHeader?.let { header(HttpHeaders.Host, it) }
        parameters.forEach { (key, value) -> parameter(key, value) }
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
