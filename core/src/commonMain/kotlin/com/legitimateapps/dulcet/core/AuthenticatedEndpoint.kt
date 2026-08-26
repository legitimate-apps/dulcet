package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders

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
)

/**
 * The single authenticated GET implementation used by read-through OpenSubsonic endpoints.
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
    ): AuthenticatedEndpointResponse {
        val salt = saltSource.nextSalt()
        require(SALT_PATTERN.matches(salt)) {
            "SaltSource must return exactly 16 bytes as lowercase hex"
        }
        val common = linkedMapOf(
            "v" to AccountConnectionContract.protocolVersion,
            "c" to CLIENT_NAME,
            "f" to "json",
        ).apply {
            putAll(parameters)
            put("u", credentials.username)
            put("t", AccountConnectionContract.saltedToken(credentials.password, salt))
            put("s", salt)
        }
        var currentUrl = "${credentials.normalizedBaseUrl}/rest/$endpoint.view"
        var redirects = 0
        while (true) {
            val target = localHttpPolicy.targetFor(currentUrl, credentials.allowLocalHttp)
            val response = client.get(target.url) {
                target.hostHeader?.let { header(HttpHeaders.Host, it) }
                common.forEach { (key, value) -> parameter(key, value) }
            }
            val body = response.bodyAsBytes()
            val redactedUrl = traceRecorder.latestRedactedUrl()
            if (response.status.value !in REDIRECT_STATUS_CODES) {
                return AuthenticatedEndpointResponse(
                    statusCode = response.status.value,
                    body = body,
                    redactedUrl = redactedUrl,
                )
            }
            val location = response.headers[HttpHeaders.Location]
                ?: return AuthenticatedEndpointResponse(
                    statusCode = response.status.value,
                    body = body,
                    redactedUrl = redactedUrl,
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
