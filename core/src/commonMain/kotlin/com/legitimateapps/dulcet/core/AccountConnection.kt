package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Inputs required to establish and negotiate one Subsonic account. */
public data class AccountConnectionRequest(
    val serverUrl: String,
    val username: String,
    val password: String,
)

/** Authentication placement observed at the transport boundary, without retaining credential values. */
public enum class AuthenticationLocation {
    None,
    FormBody,
    Query,
}

/** Redacted request evidence retained for diagnostics and conformance assertions. */
public data class RequestTrace(
    val endpoint: String,
    val method: String,
    val redactedUrl: String,
    val authenticationLocation: AuthenticationLocation,
    val requestedProtocolVersion: String?,
    val saltFingerprint: String?,
)

public data class UserPermissions(
    val download: Boolean,
    val playlist: Boolean,
    val share: Boolean,
    val jukebox: Boolean,
    val admin: Boolean,
)

public data class CapabilitySet(
    val extensions: Map<String, Set<Int>>,
    val permissions: UserPermissions,
    val legacySubsonic: Boolean,
)

public data class ConnectedAccount(
    val normalizedBaseUrl: String,
    val protocolVersion: String,
    val openSubsonic: Boolean,
    val serverType: String,
    val serverVersion: String,
    val capabilities: CapabilitySet,
    val requests: List<RequestTrace>,
)

public sealed interface AccountConnectionResult {
    public data class Connected(val account: ConnectedAccount) : AccountConnectionResult
    public data class Failed(val error: DomainError) : AccountConnectionResult
}

/** User-presentable failures. URL-bearing variants accept and retain redacted URLs only. */
public sealed interface DomainError {
    public sealed interface Input : DomainError {
        public data class InvalidServerUrl(val reason: String) : Input
    }

    public sealed interface Transport : DomainError {
        public data object Unreachable : Transport
        public data object Timeout : Transport
        public data object Cancelled : Transport
    }

    public sealed interface Security : DomainError {
        public data class TlsUntrusted(val reason: String) : Security
        public data object LocalExceptionViolated : Security
    }

    public sealed interface Protocol : DomainError {
        public data object MalformedEnvelope : Protocol
        public data class Incompatible(
            val clientVersion: String,
            val serverVersion: String?,
        ) : Protocol
        public data object NotASubsonicServer : Protocol
    }

    public sealed interface Server : DomainError {
        public data class Known(
            val code: Int,
            val message: String,
            val redactedUrl: String,
        ) : Server

        public data class Unknown(
            val code: Int,
            val message: String,
            val redactedUrl: String,
        ) : Server
    }

    public sealed interface Auth : DomainError {
        public data object InvalidCredentials : Auth
        public data object TokenAuthUnsupported : Auth
        public data object Forbidden : Auth
        public data object RedirectCredentialLoss : Auth
    }

    public data class CapabilityUnsupported(val featureId: String) : DomainError
}

public fun interface LogSink {
    public fun write(message: String)
}

public fun interface SaltSource {
    public fun nextSalt(): String
}

/** Account-connect entry point shared by every platform shell. */
public class AccountConnector(
    private val saltSource: SaltSource? = null,
    private val logSink: LogSink? = null,
) {
    public suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult {
        val normalized = normalizeServerUrl(request.serverUrl)
        if (normalized is NormalizedServerUrl.Invalid) {
            return AccountConnectionResult.Failed(normalized.error)
        }
        normalized as NormalizedServerUrl.Valid

        val client = HttpClient {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            }
        }
        return try {
            var lastResult: AccountConnectionResult =
                AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
            normalized.candidates.forEachIndexed { index, baseUrl ->
                val result = try {
                    connectNormalized(client, baseUrl, request)
                } catch (_: HttpRequestTimeoutException) {
                    AccountConnectionResult.Failed(DomainError.Transport.Timeout)
                } catch (_: CancellationException) {
                    return AccountConnectionResult.Failed(DomainError.Transport.Cancelled)
                } catch (_: Throwable) {
                    AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
                }
                lastResult = result
                val mayTryLocalHttp = index < normalized.candidates.lastIndex &&
                    result is AccountConnectionResult.Failed &&
                    (result.error is DomainError.Transport.Unreachable ||
                        result.error is DomainError.Transport.Timeout ||
                        result.error is DomainError.Security.TlsUntrusted)
                if (!mayTryLocalHttp) return result
            }
            lastResult
        } finally {
            client.close()
        }
    }

    private suspend fun connectNormalized(
        client: HttpClient,
        baseUrl: String,
        request: AccountConnectionRequest,
    ): AccountConnectionResult {
        val traces = mutableListOf<RequestTrace>()
        val extensionResponse = request(
            client = client,
            baseUrl = baseUrl,
            endpoint = "getOpenSubsonicExtensions",
            endpointParameters = emptyMap(),
            credentials = null,
            formPost = false,
            traces = traces,
        )
        val extensionEnvelope = parseEnvelope(extensionResponse.body)
        val extensions = extensionEnvelope?.takeIf { it.status == "ok" }
            ?.payload
            ?.get("openSubsonicExtensions")
            .toExtensionMap()
        val extensionListUnavailable = extensionResponse.statusCode == 404 || extensionEnvelope == null
        val formPost = extensions["formPost"]?.contains(1) == true

        val ping = authenticatedRequest(
            client = client,
            baseUrl = baseUrl,
            endpoint = "ping",
            endpointParameters = emptyMap(),
            credentials = request,
            formPost = formPost,
            traces = traces,
        )
        val pingEnvelope = parseEnvelope(ping.body)
            ?: return AccountConnectionResult.Failed(
                if (extensionListUnavailable) {
                    DomainError.Protocol.NotASubsonicServer
                } else {
                    DomainError.Protocol.MalformedEnvelope
                },
            )
        if (pingEnvelope.status != "ok") {
            return AccountConnectionResult.Failed(
                mapEnvelopeError(pingEnvelope, ping.logicalRequestUrl),
            )
        }
        val serverProtocolVersion = pingEnvelope.string("version")
            ?: return AccountConnectionResult.Failed(DomainError.Protocol.MalformedEnvelope)
        if (!isCompatibleVersion(AccountConnectionContract.protocolVersion, serverProtocolVersion)) {
            return AccountConnectionResult.Failed(
                DomainError.Protocol.Incompatible(
                    clientVersion = AccountConnectionContract.protocolVersion,
                    serverVersion = serverProtocolVersion,
                ),
            )
        }

        val userResponse = authenticatedRequest(
            client = client,
            baseUrl = baseUrl,
            endpoint = "getUser",
            endpointParameters = mapOf("username" to request.username),
            credentials = request,
            formPost = formPost,
            traces = traces,
        )
        val userEnvelope = parseEnvelope(userResponse.body)
            ?: return AccountConnectionResult.Failed(DomainError.Protocol.MalformedEnvelope)
        if (userEnvelope.status != "ok") {
            return AccountConnectionResult.Failed(
                mapEnvelopeError(userEnvelope, userResponse.logicalRequestUrl),
            )
        }
        val user = userEnvelope.payload["user"] as? JsonObject
            ?: return AccountConnectionResult.Failed(DomainError.Protocol.MalformedEnvelope)

        val effectiveExtensions = if (extensionListUnavailable) emptyMap() else extensions
        return AccountConnectionResult.Connected(
            ConnectedAccount(
                normalizedBaseUrl = baseUrl,
                protocolVersion = serverProtocolVersion,
                openSubsonic = pingEnvelope.boolean("openSubsonic") ?: false,
                serverType = pingEnvelope.string("type").orEmpty(),
                serverVersion = pingEnvelope.string("serverVersion").orEmpty(),
                capabilities = CapabilitySet(
                    extensions = effectiveExtensions,
                    permissions = UserPermissions(
                        download = user.boolean("downloadRole") ?: false,
                        playlist = user.boolean("playlistRole") ?: false,
                        share = user.boolean("shareRole") ?: false,
                        jukebox = user.boolean("jukeboxRole") ?: false,
                        admin = user.boolean("adminRole") ?: false,
                    ),
                    legacySubsonic = extensionListUnavailable,
                ),
                requests = traces.toList(),
            ),
        )
    }

    private suspend fun authenticatedRequest(
        client: HttpClient,
        baseUrl: String,
        endpoint: String,
        endpointParameters: Map<String, String>,
        credentials: AccountConnectionRequest,
        formPost: Boolean,
        traces: MutableList<RequestTrace>,
    ): WireResponse {
        val source = saltSource ?: AccountConnectionContract.secureSaltSource()
        val salt = source.nextSalt()
        require(SALT_PATTERN.matches(salt)) { "SaltSource must return exactly 16 bytes as lowercase hex" }
        val token = AccountConnectionContract.saltedToken(credentials.password, salt)
        return request(
            client = client,
            baseUrl = baseUrl,
            endpoint = endpoint,
            endpointParameters = endpointParameters,
            credentials = AuthenticationParameters(
                username = credentials.username,
                token = token,
                salt = salt,
            ),
            formPost = formPost,
            traces = traces,
        )
    }

    private suspend fun request(
        client: HttpClient,
        baseUrl: String,
        endpoint: String,
        endpointParameters: Map<String, String>,
        credentials: AuthenticationParameters?,
        formPost: Boolean,
        traces: MutableList<RequestTrace>,
    ): WireResponse {
        val endpointUrl = "$baseUrl/rest/$endpoint.view"
        val commonParameters = linkedMapOf(
            "v" to AccountConnectionContract.protocolVersion,
            "c" to CLIENT_NAME,
            "f" to "json",
        )
        commonParameters.putAll(endpointParameters)
        val authentication = credentials?.let {
            linkedMapOf("u" to it.username, "t" to it.token, "s" to it.salt)
        }.orEmpty()
        val useForm = credentials != null && formPost
        val method = if (useForm) "POST" else "GET"
        val response: HttpResponse = if (useForm) {
            client.submitForm(
                url = endpointUrl,
                formParameters = Parameters.build {
                    commonParameters.forEach { (key, value) -> append(key, value) }
                    authentication.forEach { (key, value) -> append(key, value) }
                },
                encodeInQuery = false,
            )
        } else {
            client.get(endpointUrl) {
                commonParameters.forEach { (key, value) -> parameter(key, value) }
                authentication.forEach { (key, value) -> parameter(key, value) }
            }
        }
        val diagnosticUrl = if (credentials == null) {
            Redactor.redactUrl("$endpointUrl?v=${AccountConnectionContract.protocolVersion}")
        } else {
            Redactor.redactUrl("$endpointUrl?authentication=present")
        }
        val trace = RequestTrace(
            endpoint = endpoint,
            method = method,
            redactedUrl = diagnosticUrl,
            authenticationLocation = when {
                credentials == null -> AuthenticationLocation.None
                useForm -> AuthenticationLocation.FormBody
                else -> AuthenticationLocation.Query
            },
            requestedProtocolVersion = AccountConnectionContract.protocolVersion,
            saltFingerprint = credentials?.salt?.let { md5Hex("salt:$it") },
        )
        traces += trace
        logSink?.write(
            "account.connect endpoint=${trace.endpoint} method=${trace.method} url=${trace.redactedUrl}",
        )
        return WireResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            logicalRequestUrl = diagnosticUrl,
        )
    }

    private fun mapEnvelopeError(envelope: SubsonicEnvelope, requestUrl: String): DomainError {
        val error = envelope.payload["error"] as? JsonObject
        val code = error?.int("code") ?: -1
        val message = error?.string("message").orEmpty()
        return when (code) {
            30 -> DomainError.Protocol.Incompatible(
                clientVersion = AccountConnectionContract.protocolVersion,
                serverVersion = envelope.string("version"),
            )
            else -> AccountConnectionContract.mapSubsonicError(code, message, requestUrl)
        }
    }

    private data class AuthenticationParameters(
        val username: String,
        val token: String,
        val salt: String,
    )

    private data class WireResponse(
        val statusCode: Int,
        val body: String,
        val logicalRequestUrl: String,
    )

    private companion object {
        const val CLIENT_NAME = "Dulcet"
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        val SALT_PATTERN = Regex("[0-9a-f]{32}")
    }
}

/** Stable protocol functions shared by transport, tests, and future account refreshes. */
public object AccountConnectionContract {
    public const val protocolVersion: String = "1.16.1"

    public fun secureSaltSource(): SaltSource = SecureSaltSource

    public fun saltedToken(password: String, salt: String): String =
        md5Hex(password + salt)

    public fun mapSubsonicError(
        code: Int,
        message: String,
        requestUrl: String,
    ): DomainError {
        val redactedUrl = Redactor.redactUrl(requestUrl)
        return when (code) {
            40 -> DomainError.Auth.InvalidCredentials
            41 -> DomainError.Auth.TokenAuthUnsupported
            50 -> DomainError.Auth.Forbidden
            in KNOWN_SUBSONIC_ERROR_CODES -> DomainError.Server.Known(code, message, redactedUrl)
            else -> DomainError.Server.Unknown(code, message, redactedUrl)
        }
    }

    private val KNOWN_SUBSONIC_ERROR_CODES = setOf(0, 10, 20, 30, 40, 41, 50, 60, 70)
}

/** The only URL representation allowed into logs and URL-bearing domain errors. */
public object Redactor {
    public fun redactUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val fragmentIndex = url.indexOf('#')
        val end = listOf(queryIndex, fragmentIndex).filter { it >= 0 }.minOrNull()
            ?: return url
        val prefix = url.substring(0, end)
        return if (queryIndex >= 0 && queryIndex == end) "$prefix?<redacted>" else prefix
    }
}

private object SecureSaltSource : SaltSource {
    override fun nextSalt(): String = secureRandomBytes(16).toLowerHex()
}

private sealed interface NormalizedServerUrl {
    data class Valid(val candidates: List<String>) : NormalizedServerUrl
    data class Invalid(val error: DomainError) : NormalizedServerUrl
}

private fun normalizeServerUrl(input: String): NormalizedServerUrl {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return NormalizedServerUrl.Invalid(DomainError.Input.InvalidServerUrl("Server URL is empty"))
    }
    val suppliedScheme = SCHEME_PATTERN.containsMatchIn(trimmed)
    val withScheme = if (suppliedScheme) trimmed else "https://$trimmed"
    val match = URL_PATTERN.matchEntire(withScheme)
        ?: return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl("Server URL must contain a valid host"),
        )
    val scheme = match.groupValues[1].lowercase()
    if (scheme != "https" && scheme != "http") {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl("Only HTTP and HTTPS server URLs are supported"),
        )
    }
    val authority = match.groupValues[2]
    if (authority.isBlank() || '@' in authority) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl("Server URL must contain a host and no user info"),
        )
    }
    val host = authority.hostWithoutPort()
    if (host.isBlank()) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl("Server URL must contain a host"),
        )
    }
    if (scheme == "http" && !host.isLocalHttpHost()) {
        return NormalizedServerUrl.Invalid(DomainError.Security.LocalExceptionViolated)
    }

    var path = match.groupValues[3].substringBefore('?').substringBefore('#').trimEnd('/')
    if (path.substringAfterLast('/').endsWith(".view", ignoreCase = true)) {
        path = path.substringBeforeLast('/', missingDelimiterValue = "")
    }
    path = path.trimEnd('/')
    if (path.endsWith("/rest", ignoreCase = true)) {
        path = path.dropLast(5).trimEnd('/')
    }
    val secureBaseUrl = "$scheme://$authority$path"
    val candidates = if (!suppliedScheme && host.isLocalHttpHost()) {
        listOf(secureBaseUrl, "http://$authority$path")
    } else {
        listOf(secureBaseUrl)
    }
    return NormalizedServerUrl.Valid(candidates)
}

private fun String.hostWithoutPort(): String = when {
    startsWith("[") -> substringAfter('[').substringBefore(']')
    else -> substringBefore(':')
}.lowercase()

private fun String.isLocalHttpHost(): Boolean {
    if (this == "localhost" || this == "::1") return true
    val octets = split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127 ||
        octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168)
}

private fun isCompatibleVersion(clientVersion: String, serverVersion: String): Boolean {
    val client = clientVersion.parseProtocolVersion() ?: return false
    val server = serverVersion.parseProtocolVersion() ?: return false
    return client.first == server.first && client.second <= server.second
}

private fun String.parseProtocolVersion(): Pair<Int, Int>? {
    val parts = split('.')
    if (parts.size < 2) return null
    return (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
}

private data class SubsonicEnvelope(
    val status: String,
    val payload: JsonObject,
) {
    fun string(name: String): String? = payload.string(name)
    fun boolean(name: String): Boolean? = payload.boolean(name)
}

private val ENVELOPE_JSON = Json { ignoreUnknownKeys = true }

private fun parseEnvelope(body: String): SubsonicEnvelope? {
    return try {
        val root = ENVELOPE_JSON.parseToJsonElement(body) as? JsonObject ?: return null
        val payload = root["subsonic-response"] as? JsonObject ?: return null
        SubsonicEnvelope(payload.string("status") ?: return null, payload)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun JsonElement?.toExtensionMap(): Map<String, Set<Int>> {
    val list = this as? JsonArray ?: return emptyMap()
    return buildMap {
        list.forEach { element ->
            val extension = element as? JsonObject ?: return@forEach
            val name = extension.string("name") ?: return@forEach
            val versions = (extension["versions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                ?.toSet()
                .orEmpty()
            put(name, versions)
        }
    }
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(name: String): Int? =
    (get(name) as? JsonPrimitive)?.intOrNull

private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val URL_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/]+)(/.*)?$")
