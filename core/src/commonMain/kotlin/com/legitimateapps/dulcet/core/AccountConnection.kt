package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
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
    val allowLocalHttp: Boolean = false,
)

/** Authentication placement observed at the transport boundary, without retaining credential values. */
public enum class AuthenticationLocation {
    None,
    FormBody,
    Query,
}

/** Closed credential-key identities; request traces never retain credential values. */
public enum class AuthenticationParameter {
    Username,
    SaltedToken,
    Salt,
    LegacyPassword,
}

/** Structural request locations observed before the engine sends a request. */
public enum class RequestChannelLocation {
    Header,
    Query,
    FormBody,
}

/** A value-free request channel identity; names are normalized to lowercase. */
public data class RequestChannel(
    val location: RequestChannelLocation,
    val name: String,
)

public enum class RequestObservationBoundary {
    KtorSendingRequest,
}

public enum class RedirectRejectionReason {
    HttpsDowngrade,
    LocalToPublic,
    CrossOrigin,
    TooManyRedirects,
    InvalidLocation,
}

public sealed interface RedirectPolicyDecision {
    public data object PreserveCredentials : RedirectPolicyDecision
    public data class Reject(val reason: RedirectRejectionReason) : RedirectPolicyDecision
}

/** Redacted request evidence retained for diagnostics and conformance assertions. */
public class RequestTrace private constructor(
    public val endpoint: String,
    public val method: String,
    public val redactedUrl: String,
    public val authenticationLocation: AuthenticationLocation,
    public val queryAuthenticationParameters: Set<AuthenticationParameter>,
    public val formAuthenticationParameters: Set<AuthenticationParameter>,
    public val channels: Set<RequestChannel>,
    public val requestedProtocolVersion: String?,
    public val saltFingerprint: String?,
    public val observationBoundary: RequestObservationBoundary,
) {
    override fun toString(): String =
        "RequestTrace(endpoint=$endpoint, method=$method, redactedUrl=$redactedUrl, " +
            "authenticationLocation=$authenticationLocation, " +
            "queryAuthenticationParameters=$queryAuthenticationParameters, " +
            "formAuthenticationParameters=$formAuthenticationParameters, " +
            "channels=$channels, " +
            "requestedProtocolVersion=$requestedProtocolVersion, " +
            "saltFingerprint=$saltFingerprint, observationBoundary=$observationBoundary)"

    public companion object {
        internal fun observed(
            endpoint: String,
            method: String,
            redactedUrl: String,
            authenticationLocation: AuthenticationLocation,
            queryAuthenticationParameters: Set<AuthenticationParameter>,
            formAuthenticationParameters: Set<AuthenticationParameter>,
            channels: Set<RequestChannel>,
            requestedProtocolVersion: String?,
            saltFingerprint: String?,
        ): RequestTrace = RequestTrace(
            endpoint = endpoint,
            method = method,
            redactedUrl = redactedUrl,
            authenticationLocation = authenticationLocation,
            queryAuthenticationParameters = queryAuthenticationParameters,
            formAuthenticationParameters = formAuthenticationParameters,
            channels = channels,
            requestedProtocolVersion = requestedProtocolVersion,
            saltFingerprint = saltFingerprint,
            observationBoundary = RequestObservationBoundary.KtorSendingRequest,
        )
    }
}

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
    val allowsLocalHttp: Boolean,
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

public enum class InvalidServerUrlReason {
    Empty,
    MalformedHost,
    UnsupportedInternationalizedHost,
    UnsupportedScheme,
    EmbeddedUserInfo,
}

public enum class TlsTrustFailure {
    CertificateChain,
    Hostname,
    ValidityPeriod,
    Other,
}

public enum class CapabilityFeature {
    AccountConnect,
    LibrarySync,
    SearchQuery,
    PlaybackStream,
    PlaybackScrobble,
    DownloadsOffline,
}

/** Parsed protocol components retain no server-controlled source text. */
public data class ProtocolVersionLevel(
    val major: Int,
    val minor: Int,
)

/** Content-free marker used where the UI may say that server text was deliberately discarded. */
public object SuppressedServerText {
    public const val value: String = "<server-text-suppressed>"
    override fun toString(): String = value
}

/** Content-free marker used where the UI may say that a server URL was deliberately discarded. */
public object SuppressedServerUrl {
    public const val value: String = "?<redacted>"
    override fun toString(): String = value
}

/** Content-free redirect marker with no query syntax. */
public object SuppressedRedirectUrl {
    public const val value: String = "<redirect-url-suppressed>"
    override fun toString(): String = value
}

/**
 * Semantic failures safe for rendering, logging, exception wrapping, and diagnostic serialization.
 * Server-controlled messages and URLs are discarded before construction; fields contain only closed
 * enums, numeric values, or content-free markers.
 */
public sealed interface DomainError {
    public sealed interface Input : DomainError {
        public data class InvalidServerUrl(val reason: InvalidServerUrlReason) : Input
    }

    public sealed interface Transport : DomainError {
        public data object Unreachable : Transport
        public data object Timeout : Transport
        public data object Cancelled : Transport
    }

    public sealed interface Security : DomainError {
        public data class TlsUntrusted(val reason: TlsTrustFailure) : Security
        public data object LocalExceptionViolated : Security
        public data class RedirectRejected(
            val reason: RedirectRejectionReason,
            val redactedUrl: SuppressedRedirectUrl = SuppressedRedirectUrl,
        ) : Security
    }

    public sealed interface Protocol : DomainError {
        public data object MalformedEnvelope : Protocol
        public data class Incompatible(
            val clientVersion: ProtocolVersionLevel,
            val serverVersion: ProtocolVersionLevel?,
        ) : Protocol
        public data object NotASubsonicServer : Protocol
    }

    public sealed interface Server : DomainError {
        public data class Known(
            val code: Int,
            val message: SuppressedServerText = SuppressedServerText,
            val redactedUrl: SuppressedServerUrl = SuppressedServerUrl,
        ) : Server

        public data class Unknown(
            val code: Int,
            val message: SuppressedServerText = SuppressedServerText,
            val redactedUrl: SuppressedServerUrl = SuppressedServerUrl,
        ) : Server
    }

    public sealed interface Auth : DomainError {
        public data object InvalidCredentials : Auth
        public data object TokenAuthUnsupported : Auth
        public data object Forbidden : Auth
        /** The server or an intermediary requested an authentication mechanism Phase 1 does not support. */
        public data object UnsupportedAuthenticationChallenge : Auth
        /** Account connect refused to send a request across an origin boundary. */
        public data class CrossOriginRedirectRejected(
            val redactedUrl: SuppressedRedirectUrl = SuppressedRedirectUrl,
        ) : Auth
        public data class RedirectCredentialLoss(
            val redactedUrl: SuppressedRedirectUrl = SuppressedRedirectUrl,
        ) : Auth
    }

    public data class CapabilityUnsupported(val featureId: CapabilityFeature) : DomainError
}

private val DomainError.diagnosticKind: String
    get() = when (this) {
        is DomainError.Input.InvalidServerUrl -> "Input.InvalidServerUrl"
        DomainError.Transport.Unreachable -> "Transport.Unreachable"
        DomainError.Transport.Timeout -> "Transport.Timeout"
        DomainError.Transport.Cancelled -> "Transport.Cancelled"
        is DomainError.Security.TlsUntrusted -> "Security.TlsUntrusted"
        DomainError.Security.LocalExceptionViolated -> "Security.LocalExceptionViolated"
        is DomainError.Security.RedirectRejected -> "Security.RedirectRejected"
        DomainError.Protocol.MalformedEnvelope -> "Protocol.MalformedEnvelope"
        is DomainError.Protocol.Incompatible -> "Protocol.Incompatible"
        DomainError.Protocol.NotASubsonicServer -> "Protocol.NotASubsonicServer"
        is DomainError.Server.Known -> "Server.Known"
        is DomainError.Server.Unknown -> "Server.Unknown"
        DomainError.Auth.InvalidCredentials -> "Auth.InvalidCredentials"
        DomainError.Auth.TokenAuthUnsupported -> "Auth.TokenAuthUnsupported"
        DomainError.Auth.Forbidden -> "Auth.Forbidden"
        DomainError.Auth.UnsupportedAuthenticationChallenge -> "Auth.UnsupportedAuthenticationChallenge"
        is DomainError.Auth.CrossOriginRedirectRejected -> "Auth.CrossOriginRedirectRejected"
        is DomainError.Auth.RedirectCredentialLoss -> "Auth.RedirectCredentialLoss"
        is DomainError.CapabilityUnsupported -> "Capability.Unsupported"
    }

/** Explicit safe serialization path; no server-controlled source text is available to this mapper. */
public fun DomainError.toDiagnosticJson(): String {
    val fields = buildMap<String, JsonElement> {
        put("kind", JsonPrimitive(this@toDiagnosticJson.diagnosticKind))
        when (val error = this@toDiagnosticJson) {
            is DomainError.Input.InvalidServerUrl -> put("reason", JsonPrimitive(error.reason.name))
            is DomainError.Security.TlsUntrusted -> put("reason", JsonPrimitive(error.reason.name))
            is DomainError.Security.RedirectRejected -> put("reason", JsonPrimitive(error.reason.name))
            is DomainError.Protocol.Incompatible -> {
                put("clientMajor", JsonPrimitive(error.clientVersion.major))
                put("clientMinor", JsonPrimitive(error.clientVersion.minor))
                error.serverVersion?.let {
                    put("serverMajor", JsonPrimitive(it.major))
                    put("serverMinor", JsonPrimitive(it.minor))
                }
            }
            is DomainError.Server.Known -> put("code", JsonPrimitive(error.code))
            is DomainError.Server.Unknown -> put("code", JsonPrimitive(error.code))
            DomainError.Transport.Unreachable,
            DomainError.Transport.Timeout,
            DomainError.Transport.Cancelled,
            DomainError.Security.LocalExceptionViolated,
            DomainError.Protocol.MalformedEnvelope,
            DomainError.Protocol.NotASubsonicServer,
            DomainError.Auth.InvalidCredentials,
            DomainError.Auth.TokenAuthUnsupported,
            DomainError.Auth.Forbidden,
            DomainError.Auth.UnsupportedAuthenticationChallenge,
            is DomainError.Auth.CrossOriginRedirectRejected,
            is DomainError.Auth.RedirectCredentialLoss,
            -> Unit
            is DomainError.CapabilityUnsupported -> put(
                "featureId",
                JsonPrimitive(error.featureId.name),
            )
        }
    }
    return JsonObject(fields).toString()
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
    hostResolver: HostResolver = systemHostResolver(),
) {
    private val localHttpPolicy = LocalHttpConnectionPolicy(hostResolver)

    public suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult {
        val normalized = normalizeServerUrl(request.serverUrl, request.allowLocalHttp)
        if (normalized is NormalizedServerUrl.Invalid) {
            return AccountConnectionResult.Failed(normalized.error)
        }
        normalized as NormalizedServerUrl.Valid

        val traceRecorder = RequestTraceRecorder(logSink)
        val client = createAccountHttpClient {
            expectSuccess = false
            followRedirects = false
            install(RequestTracePlugin) {
                observe = traceRecorder::observe
            }
            install(HttpTimeout) {
                connectTimeoutMillis = ACCOUNT_REQUEST_TIMEOUT_MILLIS
                requestTimeoutMillis = ACCOUNT_REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = ACCOUNT_REQUEST_TIMEOUT_MILLIS
            }
        }
        return try {
            var lastResult: AccountConnectionResult =
                AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
            normalized.candidates.forEachIndexed { index, baseUrl ->
                traceRecorder.clear()
                val result = try {
                    connectNormalized(client, baseUrl, request, traceRecorder)
                } catch (failure: RedirectPolicyFailure) {
                    AccountConnectionResult.Failed(failure.error)
                } catch (failure: LocalHttpPolicyFailure) {
                    AccountConnectionResult.Failed(failure.error)
                } catch (_: CancellationException) {
                    return AccountConnectionResult.Failed(DomainError.Transport.Cancelled)
                } catch (failure: Throwable) {
                    AccountConnectionResult.Failed(mapAccountConnectionFailure(failure))
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
        traceRecorder: RequestTraceRecorder,
    ): AccountConnectionResult {
        val extensionResponse = request(
            client = client,
            baseUrl = baseUrl,
            endpoint = "getOpenSubsonicExtensions",
            endpointParameters = emptyMap(),
            credentials = null,
            formPost = false,
            allowLocalHttp = request.allowLocalHttp,
            traceRecorder = traceRecorder,
        )
        val extensionEnvelope = parseEnvelope(extensionResponse.body)
        val extensionListUnavailable = extensionResponse.statusCode == 404 || extensionEnvelope == null
        val extensions = if (extensionEnvelope?.status == "ok") {
            extensionEnvelope.payload["openSubsonicExtensions"].toExtensionMapOrNull()
                ?: return AccountConnectionResult.Failed(DomainError.Protocol.MalformedEnvelope)
        } else {
            emptyMap()
        }
        val formPost = extensions["formPost"]?.contains(1) == true

        val ping = authenticatedRequest(
            client = client,
            baseUrl = baseUrl,
            endpoint = "ping",
            endpointParameters = emptyMap(),
            credentials = request,
            formPost = formPost,
            traceRecorder = traceRecorder,
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
        val protocolCompatible = AccountConnectionContract.isCompatibleVersion(
            AccountConnectionContract.protocolVersion,
            serverProtocolVersion,
        )
        if (!protocolCompatible) {
            return AccountConnectionResult.Failed(
                DomainError.Protocol.Incompatible(
                    clientVersion = AccountConnectionContract.protocolVersionLevel,
                    serverVersion = serverProtocolVersion.parseProtocolVersion(),
                ),
            )
        }
        if (
            !pingEnvelope.payload.hasOptionalBoolean("openSubsonic") ||
            !pingEnvelope.payload.hasOptionalString("type") ||
            !pingEnvelope.payload.hasOptionalString("serverVersion")
        ) {
            return AccountConnectionResult.Failed(DomainError.Protocol.MalformedEnvelope)
        }

        val userResponse = authenticatedRequest(
            client = client,
            baseUrl = baseUrl,
            endpoint = "getUser",
            endpointParameters = mapOf("username" to request.username),
            credentials = request,
            formPost = formPost,
            traceRecorder = traceRecorder,
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
        if (
            listOf("downloadRole", "playlistRole", "shareRole", "jukeboxRole", "adminRole")
                .any { !user.hasOptionalBoolean(it) }
        ) {
            return AccountConnectionResult.Failed(DomainError.Protocol.MalformedEnvelope)
        }

        val effectiveExtensions = if (extensionListUnavailable) emptyMap() else extensions
        return AccountConnectionResult.Connected(
            ConnectedAccount(
                normalizedBaseUrl = baseUrl,
                allowsLocalHttp = request.allowLocalHttp,
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
                requests = traceRecorder.snapshot(),
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
        traceRecorder: RequestTraceRecorder,
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
            allowLocalHttp = credentials.allowLocalHttp,
            traceRecorder = traceRecorder,
        )
    }

    private suspend fun request(
        client: HttpClient,
        baseUrl: String,
        endpoint: String,
        endpointParameters: Map<String, String>,
        credentials: AuthenticationParameters?,
        formPost: Boolean,
        allowLocalHttp: Boolean,
        traceRecorder: RequestTraceRecorder,
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
        var currentUrl = endpointUrl
        var queryParameters = Parameters.build {
            if (!useForm) {
                commonParameters.forEach { (key, value) -> append(key, value) }
                authentication.forEach { (key, value) -> append(key, value) }
            }
        }
        var formParameters = Parameters.build {
            if (useForm) {
                commonParameters.forEach { (key, value) -> append(key, value) }
                authentication.forEach { (key, value) -> append(key, value) }
            }
        }
        var followedRedirects = 0
        while (true) {
            val response = sendRequest(
                client = client,
                url = currentUrl,
                queryParameters = queryParameters,
                formParameters = formParameters,
                useForm = useForm,
                allowLocalHttp = allowLocalHttp,
            )
            if (response.status.value !in REDIRECT_STATUS_CODES) {
                return WireResponse(
                    statusCode = response.status.value,
                    body = response.bodyAsText(),
                    logicalRequestUrl = traceRecorder.latestRedactedUrl(),
                )
            }

            val location = response.headers[HttpHeaders.Location]
                ?: return WireResponse(
                    statusCode = response.status.value,
                    body = response.bodyAsText(),
                    logicalRequestUrl = traceRecorder.latestRedactedUrl(),
                )
            response.bodyAsText()
            val nextUrl = resolveRedirectUrl(currentUrl, location)
                ?: throw RedirectPolicyFailure(
                    DomainError.Security.RedirectRejected(
                        RedirectRejectionReason.InvalidLocation,
                        SuppressedRedirectUrl,
                    ),
                )
            if (localHttpPolicy.leavesLocalNetwork(currentUrl, nextUrl)) {
                throw RedirectPolicyFailure(
                    DomainError.Auth.CrossOriginRedirectRejected(SuppressedRedirectUrl),
                )
            }
            when (
                val decision = AccountConnectionContract.redirectDecision(
                    currentUrl = currentUrl,
                    targetUrl = nextUrl,
                    redirectsAlreadyFollowed = followedRedirects,
                )
            ) {
                RedirectPolicyDecision.PreserveCredentials -> Unit
                is RedirectPolicyDecision.Reject -> {
                    val error = if (decision.reason == RedirectRejectionReason.CrossOrigin) {
                        DomainError.Auth.CrossOriginRedirectRejected(SuppressedRedirectUrl)
                    } else {
                        DomainError.Security.RedirectRejected(
                            decision.reason,
                            SuppressedRedirectUrl,
                        )
                    }
                    throw RedirectPolicyFailure(error)
                }
            }
            currentUrl = nextUrl.withoutQuery()
            followedRedirects += 1
        }
    }

    private suspend fun sendRequest(
        client: HttpClient,
        url: String,
        queryParameters: Parameters,
        formParameters: Parameters,
        useForm: Boolean,
        allowLocalHttp: Boolean,
    ): HttpResponse {
        val target = localHttpPolicy.targetFor(url, allowLocalHttp)
        return if (useForm) {
            client.submitForm(
                url = target.url,
                formParameters = formParameters,
                encodeInQuery = false,
            ) {
                target.hostHeader?.let { header(HttpHeaders.Host, it) }
                queryParameters.entries().forEach { (key, values) ->
                    values.forEach { value -> parameter(key, value) }
                }
            }
        } else {
            client.get(target.url) {
                target.hostHeader?.let { header(HttpHeaders.Host, it) }
                queryParameters.entries().forEach { (key, values) ->
                    values.forEach { value -> parameter(key, value) }
                }
            }
        }
    }

    private fun mapEnvelopeError(envelope: SubsonicEnvelope, requestUrl: String): DomainError {
        val error = envelope.payload["error"] as? JsonObject
        val code = error?.int("code") ?: -1
        val message = error?.string("message").orEmpty()
        return when (code) {
            30 -> DomainError.Protocol.Incompatible(
                clientVersion = AccountConnectionContract.protocolVersionLevel,
                serverVersion = envelope.string("version")?.parseProtocolVersion(),
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
        const val ACCOUNT_REQUEST_TIMEOUT_MILLIS = 30_000L
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val SALT_PATTERN = Regex("[0-9a-f]{32}")
    }
}

internal fun mapAccountConnectionFailure(failure: Throwable): DomainError = when {
    failure is HttpRequestTimeoutException -> DomainError.Transport.Timeout
    isUnsupportedAuthenticationChallenge(failure) ->
        DomainError.Auth.UnsupportedAuthenticationChallenge
    else -> tlsTrustFailureOrNull(failure)?.let {
        DomainError.Security.TlsUntrusted(it)
    } ?: DomainError.Transport.Unreachable
}

private class RequestTracePluginConfig {
    lateinit var observe: (HttpRequestBuilder, OutgoingContent) -> Unit
}

internal expect fun createAccountHttpClient(
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient

private val RequestTracePlugin = createClientPlugin("DulcetRequestTrace", ::RequestTracePluginConfig) {
    val observe = pluginConfig.observe
    on(SendingRequest) { request, content -> observe(request, content) }
}

private class RequestTraceRecorder(private val logSink: LogSink?) {
    private val traces = mutableListOf<RequestTrace>()

    fun clear() {
        traces.clear()
    }

    fun observe(request: HttpRequestBuilder, content: OutgoingContent) {
        val query = request.url.parameters
        val form = (content as? FormDataContent)?.formData ?: Parameters.Empty
        val queryAuthenticationParameters = authenticationParameters(
            username = query["u"],
            saltedToken = query["t"],
            salt = query["s"],
            legacyPassword = query["p"],
        )
        val formAuthenticationParameters = authenticationParameters(
            username = form["u"],
            saltedToken = form["t"],
            salt = form["s"],
            legacyPassword = form["p"],
        )
        val channels = buildSet {
            request.headers.names().forEach { name ->
                add(RequestChannel(RequestChannelLocation.Header, name.lowercase()))
            }
            query.names().forEach { name ->
                add(RequestChannel(RequestChannelLocation.Query, name.lowercase()))
            }
            form.names().forEach { name ->
                add(RequestChannel(RequestChannelLocation.FormBody, name.lowercase()))
            }
        }
        val authenticationLocation = when {
            queryAuthenticationParameters.isNotEmpty() -> AuthenticationLocation.Query
            formAuthenticationParameters.isNotEmpty() -> AuthenticationLocation.FormBody
            else -> AuthenticationLocation.None
        }
        val salt = query["s"] ?: form["s"]
        val trace = RequestTrace.observed(
            endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view"),
            method = request.method.value,
            redactedUrl = Redactor.redactUrl(request.url.buildString()),
            authenticationLocation = authenticationLocation,
            queryAuthenticationParameters = queryAuthenticationParameters,
            formAuthenticationParameters = formAuthenticationParameters,
            channels = channels,
            requestedProtocolVersion = query["v"] ?: form["v"],
            saltFingerprint = salt?.let { md5Hex("salt:$it") },
        )
        traces += trace
        logSink?.write(
            "account.connect endpoint=${trace.endpoint} method=${trace.method} url=${trace.redactedUrl}",
        )
    }

    fun latestRedactedUrl(): String = traces.lastOrNull()?.redactedUrl
        ?: error("Ktor send boundary did not observe the completed request")

    fun snapshot(): List<RequestTrace> = traces.toList()
}

private fun authenticationParameters(
    username: String?,
    saltedToken: String?,
    salt: String?,
    legacyPassword: String?,
): Set<AuthenticationParameter> = buildSet {
    if (username != null) add(AuthenticationParameter.Username)
    if (saltedToken != null) add(AuthenticationParameter.SaltedToken)
    if (salt != null) add(AuthenticationParameter.Salt)
    if (legacyPassword != null) add(AuthenticationParameter.LegacyPassword)
}

private class RedirectPolicyFailure(val error: DomainError) : Exception()

private fun resolveRedirectUrl(currentUrl: String, location: String): String? = try {
    URLBuilder().apply {
        takeFrom(currentUrl)
        parameters.clear()
        fragment = ""
        takeFrom(location)
        parameters.clear()
        fragment = ""
    }.buildString()
} catch (_: IllegalArgumentException) {
    null
}

private fun String.withoutQuery(): String = URLBuilder().apply {
    takeFrom(this@withoutQuery)
    parameters.clear()
    fragment = ""
}.buildString()

private fun Url.normalizedRedirectPort(): Int? = when {
    protocol.name == "http" && port == 80 -> null
    protocol.name == "https" && port == 443 -> null
    else -> port
}

/** Stable protocol functions shared by transport, tests, and future account refreshes. */
public object AccountConnectionContract {
    public const val protocolVersion: String = "1.16.1"
    public val protocolVersionLevel: ProtocolVersionLevel = ProtocolVersionLevel(1, 16)

    public fun secureSaltSource(): SaltSource = SecureSaltSource

    public fun saltedToken(password: String, salt: String): String =
        md5Hex(password + salt)

    public fun isCompatibleVersion(clientVersion: String, serverVersion: String): Boolean {
        val client = clientVersion.parseProtocolVersion() ?: return false
        val server = serverVersion.parseProtocolVersion() ?: return false
        return client.major == server.major && client.minor <= server.minor
    }

    public fun redirectDecision(
        currentUrl: String,
        targetUrl: String,
        redirectsAlreadyFollowed: Int,
    ): RedirectPolicyDecision {
        if (redirectsAlreadyFollowed >= MAX_REDIRECTS) {
            return RedirectPolicyDecision.Reject(RedirectRejectionReason.TooManyRedirects)
        }
        val current = try {
            Url(currentUrl)
        } catch (_: IllegalArgumentException) {
            return RedirectPolicyDecision.Reject(RedirectRejectionReason.InvalidLocation)
        }
        val target = try {
            Url(targetUrl)
        } catch (_: IllegalArgumentException) {
            return RedirectPolicyDecision.Reject(RedirectRejectionReason.InvalidLocation)
        }
        if (current.protocol.name == "https" && target.protocol.name == "http") {
            return RedirectPolicyDecision.Reject(RedirectRejectionReason.HttpsDowngrade)
        }
        val sameAuthority = current.host.equals(target.host, ignoreCase = true) &&
            current.normalizedRedirectPort() == target.normalizedRedirectPort()
        val sameOrigin = current.protocol == target.protocol && sameAuthority
        val sameAuthorityUpgrade = current.protocol.name == "http" &&
            target.protocol.name == "https" &&
            sameAuthority
        return if (sameOrigin || sameAuthorityUpgrade) {
            RedirectPolicyDecision.PreserveCredentials
        } else {
            RedirectPolicyDecision.Reject(RedirectRejectionReason.CrossOrigin)
        }
    }

    public fun mapSubsonicError(
        code: Int,
        message: String,
        requestUrl: String,
    ): DomainError {
        // These attacker-controlled values are accepted at the mapping boundary only. They are
        // intentionally not retained by any DomainError subtype.
        return when (code) {
            40 -> DomainError.Auth.InvalidCredentials
            41 -> DomainError.Auth.TokenAuthUnsupported
            50 -> DomainError.Auth.Forbidden
            in KNOWN_SUBSONIC_ERROR_CODES -> DomainError.Server.Known(code)
            else -> DomainError.Server.Unknown(code)
        }
    }

    public fun isPermittedLocalHttpAddress(address: String): Boolean =
        address.isPermittedLocalHttpAddress()

    private const val MAX_REDIRECTS = 5
    private val KNOWN_SUBSONIC_ERROR_CODES = setOf(0, 10, 20, 30, 40, 41, 50, 60, 70)
}

/** The only URL representation allowed into logs. Domain errors retain no URL content. */
public object Redactor {
    public fun redactUrl(url: String): String {
        val schemeDelimiter = url.indexOf("://")
        if (schemeDelimiter <= 0) return UNRENDERABLE_URL
        val authorityStart = schemeDelimiter + 3
        val authorityEnd = url.indexOfFirstFrom(authorityStart) { character ->
            character == '/' || character == '?' || character == '#'
        }.let { if (it < 0) url.length else it }
        if (authorityEnd == authorityStart) return UNRENDERABLE_URL
        val authority = url.substring(authorityStart, authorityEnd)
        if (!authority.isStructurallyValidUrlAuthority()) return UNRENDERABLE_URL
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            return UNRENDERABLE_URL
        }
        if (parsed.protocol.name.isBlank() || parsed.host.isBlank()) return UNRENDERABLE_URL
        val renderedHost = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
        val queryMarker = if ('?' in url.substringBefore('#')) "?<redacted>" else ""
        return "${parsed.protocol.name}://$renderedHost:${parsed.port}${parsed.encodedPath}$queryMarker"
    }

    private const val UNRENDERABLE_URL = "<unrenderable-url>"
}

private fun String.isStructurallyValidUrlAuthority(): Boolean {
    if (
        isBlank() || any { it.isWhitespace() || it.isISOControl() } ||
        '@' in this || '\\' in this
    ) {
        return false
    }
    if (startsWith('[')) {
        val closingBracket = indexOf(']')
        if (closingBracket <= 1 || indexOf('[', startIndex = 1) >= 0) return false
        if (indexOf(']', startIndex = closingBracket + 1) >= 0) return false
        val literal = substring(1, closingBracket)
        val address = literal.substringBefore('%').lowercase()
        if (!address.isValidIpv6Literal()) return false
        if ('%' in literal && !literal.substringAfter('%').isValidIpv6ZoneSuffix()) return false
        val suffix = substring(closingBracket + 1)
        return suffix.isEmpty() || suffix.isValidExplicitUrlPort()
    }
    if ('[' in this || ']' in this || count { it == ':' } > 1) return false
    val host = substringBefore(':')
    if (host.isBlank()) return false
    if (!host.isValidUrlRegName()) return false
    if ('.' in host && host.all { it == '.' || it in '0'..'9' } && host.parseIpv4() == null) {
        return false
    }
    val suffix = removePrefix(host)
    return suffix.isEmpty() || suffix.isValidExplicitUrlPort()
}

private fun String.isStructurallyValidInternationalizedHostAuthority(): Boolean {
    if (
        isBlank() || any { it.isWhitespace() || it.isISOControl() } ||
        '@' in this || '\\' in this || startsWith('[') || '[' in this || ']' in this ||
        count { it == ':' } > 1
    ) {
        return false
    }
    val host = substringBefore(':')
    if (host.isBlank()) return false
    val decodedHost = host.decodePercentEncodedAsciiRegName() ?: return false
    if (decodedHost.none { it.code > 0x7f }) return false
    if (!decodedHost.isValidUrlRegName(allowNonAscii = true)) return false
    val suffix = removePrefix(host)
    return suffix.isEmpty() || suffix.isValidExplicitUrlPort()
}

private fun String.decodePercentEncodedAsciiRegName(): String? {
    if (any { it.code > 0x7f }) return this
    val bytes = ByteArray(length)
    var byteCount = 0
    var index = 0
    while (index < length) {
        val character = this[index]
        if (character == '%') {
            if (
                index + 2 >= length ||
                !this[index + 1].isAsciiHexDigit() ||
                !this[index + 2].isAsciiHexDigit()
            ) {
                return null
            }
            bytes[byteCount] = (
                this[index + 1].asciiHexValue() * 16 + this[index + 2].asciiHexValue()
                ).toByte()
            byteCount += 1
            index += 3
        } else {
            bytes[byteCount] = character.code.toByte()
            byteCount += 1
            index += 1
        }
    }
    return try {
        bytes.decodeToString(endIndex = byteCount, throwOnInvalidSequence = true)
    } catch (_: Exception) {
        null
    }
}

private fun String.isValidIpv6ZoneSuffix(): Boolean {
    val zoneIdentifier = if (startsWith("25")) drop(2) else this
    if (zoneIdentifier.isEmpty()) return false
    var index = 0
    while (index < zoneIdentifier.length) {
        val character = zoneIdentifier[index]
        when {
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character in "-._~" -> index += 1

            character == '%' -> {
                if (
                    index + 2 >= zoneIdentifier.length ||
                    !zoneIdentifier[index + 1].isAsciiHexDigit() ||
                    !zoneIdentifier[index + 2].isAsciiHexDigit()
                ) {
                    return false
                }
                if (zoneIdentifier.substring(index + 1, index + 3).equals("25", ignoreCase = true)) {
                    return false
                }
                index += 3
            }

            else -> return false
        }
    }
    return true
}

private fun String.isValidUrlRegName(allowNonAscii: Boolean = false): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character in "-._~!$&'()*+,;=" -> index += 1

            allowNonAscii && character.code > 0x7f -> index += 1

            character == '%' -> {
                if (
                    index + 2 >= length ||
                    !this[index + 1].isAsciiHexDigit() ||
                    !this[index + 2].isAsciiHexDigit()
                ) {
                    return false
                }
                index += 3
            }

            else -> return false
        }
    }
    return true
}

private fun Char.isAsciiHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.asciiHexValue(): Int = when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    else -> code - 'A'.code + 10
}

private fun String.isValidExplicitUrlPort(): Boolean {
    if (!startsWith(':')) return false
    val digits = drop(1)
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return false
    val port = digits.toIntOrNull() ?: return false
    return port in 0..65_535
}

private inline fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return -1
}

private object SecureSaltSource : SaltSource {
    override fun nextSalt(): String = secureRandomBytes(16).toLowerHex()
}

private sealed interface NormalizedServerUrl {
    data class Valid(val candidates: List<String>) : NormalizedServerUrl
    data class Invalid(val error: DomainError) : NormalizedServerUrl
}

private fun normalizeServerUrl(input: String, allowLocalHttp: Boolean): NormalizedServerUrl {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.Empty),
        )
    }
    val suppliedScheme = SCHEME_PATTERN.containsMatchIn(trimmed)
    val withScheme = if (suppliedScheme) trimmed else "https://$trimmed"
    val match = URL_PATTERN.matchEntire(withScheme)
        ?: return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(
                InvalidServerUrlReason.MalformedHost,
            ),
        )
    val scheme = match.groupValues[1].lowercase()
    if (scheme != "https" && scheme != "http") {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(
                InvalidServerUrlReason.UnsupportedScheme,
            ),
        )
    }
    val authority = match.groupValues[2]
    if ('@' in authority) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(
                InvalidServerUrlReason.EmbeddedUserInfo,
            ),
        )
    }
    if (authority.isStructurallyValidInternationalizedHostAuthority()) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(
                InvalidServerUrlReason.UnsupportedInternationalizedHost,
            ),
        )
    }
    if (!authority.isStructurallyValidUrlAuthority()) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.MalformedHost),
        )
    }
    val parsed = try {
        Url(withScheme)
    } catch (_: Exception) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.MalformedHost),
        )
    }
    if (parsed.host.isBlank()) {
        return NormalizedServerUrl.Invalid(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.MalformedHost),
        )
    }
    if (scheme == "http" && !allowLocalHttp) {
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
    val candidates = if (!suppliedScheme && allowLocalHttp) {
        listOf(secureBaseUrl, "http://$authority$path")
    } else {
        listOf(secureBaseUrl)
    }
    return NormalizedServerUrl.Valid(candidates)
}

private fun String.parseProtocolVersion(): ProtocolVersionLevel? {
    val parts = split('.')
    if (parts.size < 2) return null
    return ProtocolVersionLevel(
        major = parts[0].toIntOrNull() ?: return null,
        minor = parts[1].toIntOrNull() ?: return null,
    )
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

private fun JsonElement?.toExtensionMapOrNull(): Map<String, Set<Int>>? {
    val list = this as? JsonArray ?: return null
    val result = mutableMapOf<String, Set<Int>>()
    list.forEach { element ->
        val extension = element as? JsonObject ?: return null
        val name = extension.string("name")?.takeIf(String::isNotBlank) ?: return null
        val versionElements = extension["versions"] as? JsonArray ?: return null
        val versions = mutableSetOf<Int>()
        versionElements.forEach { versionElement ->
            val version = (versionElement as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.intOrNull
                ?: return null
            versions += version
        }
        if (result.put(name, versions) != null) return null
    }
    return result
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? {
    val value = get(name) as? JsonPrimitive ?: return null
    if (!value.isString) return value.booleanOrNull
    return when {
        value.content.equals("true", ignoreCase = true) -> true
        value.content.equals("false", ignoreCase = true) -> false
        else -> null
    }
}

private fun JsonObject.hasOptionalString(name: String): Boolean =
    name !in this || string(name) != null

private fun JsonObject.hasOptionalBoolean(name: String): Boolean =
    name !in this || boolean(name) != null

private fun JsonObject.int(name: String): Int? =
    (get(name) as? JsonPrimitive)?.intOrNull

private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val URL_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]+)([/?#].*)?$")
