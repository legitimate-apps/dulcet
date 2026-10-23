package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.concurrent.Volatile

public data class ScrobbleEndpointRequest(
    val event: RecordedPlaybackEvent,
) {
    override fun toString(): String = "ScrobbleEndpointRequest(<redacted>)"
}

public sealed interface ScrobbleSendResult {
    data class Sent(val requestTrace: RequestTrace) : ScrobbleSendResult
    data class Failed(
        val error: DomainError,
        val presentationError: DomainError = error,
    ) : ScrobbleSendResult
}

internal fun interface ScrobbleEndpointTransport {
    suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse

    /** Null is the ordinary-server seam for transports without extension negotiation. */
    suspend fun extensions(): AuthenticatedEndpointResponse? = null
}

/** Low-level sender shared by ephemeral now-playing and the later durable outbox worker. */
public class ScrobbleEndpointSender private constructor(
    private val transport: ScrobbleEndpointTransport,
    private val closeTransport: (() -> Unit)?,
    private val authorizeRequest: () -> DomainError?,
    private val identityAuthorization: () -> Long?,
) {
    constructor(
        account: PlaybackEndpointAccount,
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        transport = KtorScrobbleEndpointTransport(account, saltSource, logSink, hostResolver),
        closeTransport = null,
        authorizeRequest = { null },
        identityAuthorization = { 0L },
    )

    internal constructor(
        account: PlaybackEndpointAccount,
        identityAuthorization: () -> Long?,
    ) : this(
        KtorScrobbleEndpointTransport(account, null, null, systemHostResolver()),
        null,
        { null },
        identityAuthorization,
    )

    internal constructor(
        transport: ScrobbleEndpointTransport,
        authorizeRequest: () -> DomainError? = { null },
        identityAuthorization: () -> Long? = { 0L },
    ) : this(transport, null, authorizeRequest, identityAuthorization)

    @Volatile private var closed = false
    private val discovery = Mutex()
    private var capabilityGeneration = 0L
    private var offered: Pair<Long, Boolean>? = null

    /** A refreshed connection must authenticate its own advertisement, including valid absence. */
    internal fun invalidateCapabilities() {
        capabilityGeneration += 1
        offered = null
    }

    private suspend fun playlistContextOffered(authorization: Long): Boolean = discovery.withLock {
        while (true) {
            requireAuthorizedRequest(authorization)
            val generation = capabilityGeneration
            offered?.takeIf { it.first == generation }?.let { return@withLock it.second }
            val response = transport.extensions()
            requireAuthorizedRequest(authorization)
            val available = when {
                response == null -> false
                response.statusCode == 404 || response.statusCode == 501 -> false
                else -> {
                    val parsed = parseScrobbleEnvelope(response.body)
                    if (response.statusCode in 200..299 && parsed?.status == "failed" && parsed.errorCode == 70) {
                        false
                    } else {
                        val result = response.toScrobbleSendResult()
                        if (result is ScrobbleSendResult.Failed) throw AuthenticatedEndpointFailure(result.error)
                        1 in parseOpenSubsonicExtensionVersions(response.body.decodeToString(), "dulcetHome")
                    }
                }
            }
            // A reconnect during discovery cannot seed the new connection with an old answer.
            if (generation != capabilityGeneration) continue
            offered = generation to available
            return@withLock available
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    suspend fun send(request: ScrobbleEndpointRequest): ScrobbleSendResult = try {
        val authorization = identityAuthorization()
            ?: throw AuthenticatedEndpointFailure(DomainError.Protocol.ServerIdentityChanged)
        requireAuthorizedRequest(authorization)
        val parameters = when (val event = request.event) {
            is RecordedPlaybackEvent.NowPlaying -> linkedMapOf(
                "id" to event.itemId.rawId,
                "submission" to "false",
            )
            is RecordedPlaybackEvent.SubmittedPlay -> linkedMapOf(
                "id" to event.itemId.rawId,
                "time" to event.sessionStartWallClock.epochMilliseconds.toString(),
                "submission" to "true",
            ).apply {
                if (event.playlistContextId != null && playlistContextOffered(authorization)) {
                    put("dulcetContextType", "playlist")
                    put("dulcetContextId", event.playlistContextId.rawId)
                }
            }
        }
        // Discovery may suspend while this account is rejected or its sender is retired.
        requireAuthorizedRequest(authorization)
        val response = transport.request(parameters)
        // A successful response is not an acknowledgement once this account has been rejected
        // or this sender retired while the request was suspended. Keep the durable play pending.
        requireAuthorizedRequest(authorization)
        response.toScrobbleSendResult()
    } catch (_: CancellationException) {
        ScrobbleSendResult.Failed(DomainError.Transport.Cancelled)
    } catch (failure: AuthenticatedEndpointFailure) {
        ScrobbleSendResult.Failed(failure.error)
    } catch (failure: LibraryRequestFailure) {
        ScrobbleSendResult.Failed(failure.error)
    } catch (failure: Throwable) {
        ScrobbleSendResult.Failed(mapAccountConnectionFailure(failure))
    }

    fun close() {
        if (closed) return
        closed = true
        closeTransport?.invoke()
        (transport as? KtorScrobbleEndpointTransport)?.close()
    }

    private fun requireAuthorizedRequest(authorization: Long) {
        if (closed) throw AuthenticatedEndpointFailure(DomainError.Transport.Cancelled)
        authorizeRequest()?.let { throw AuthenticatedEndpointFailure(it) }
        if (identityAuthorization() != authorization) {
            throw AuthenticatedEndpointFailure(DomainError.Protocol.ServerIdentityChanged)
        }
    }
}

/** Submitted-play persistence seam; no production composition root is wired yet. */
internal fun interface SubmittedPlayOutboxSink {
    suspend fun persistForAtLeastOnceDelivery(event: RecordedPlaybackEvent.SubmittedPlay)
}

internal data class ScrobbleDeliveryDiagnostics(
    val nowPlayingSentCount: Long = 0,
    val nowPlayingDroppedFailureCount: Long = 0,
    val submittedPlayHandOffCount: Long = 0,
)

/** Applies Slice 1 policy effects to their deliberately different delivery paths. */
internal class SubsonicPlaybackEventRecorder(
    private val accountProviderInstanceId: String,
    private val sender: ScrobbleEndpointSender,
    private val submittedPlayOutbox: SubmittedPlayOutboxSink,
) : PlaybackEventRecorder {
    var diagnostics: ScrobbleDeliveryDiagnostics = ScrobbleDeliveryDiagnostics()
        private set

    override suspend fun recordPlaybackEvent(event: RecordedPlaybackEvent) {
        require(event.itemId.providerInstanceId == accountProviderInstanceId)
        when (event) {
            is RecordedPlaybackEvent.NowPlaying -> {
                when (sender.send(ScrobbleEndpointRequest(event))) {
                    is ScrobbleSendResult.Sent -> diagnostics = diagnostics.copy(
                        nowPlayingSentCount = diagnostics.nowPlayingSentCount + 1,
                    )
                    is ScrobbleSendResult.Failed -> diagnostics = diagnostics.copy(
                        nowPlayingDroppedFailureCount =
                            diagnostics.nowPlayingDroppedFailureCount + 1,
                    )
                }
            }
            is RecordedPlaybackEvent.SubmittedPlay -> {
                submittedPlayOutbox.persistForAtLeastOnceDelivery(event)
                diagnostics = diagnostics.copy(
                    submittedPlayHandOffCount = diagnostics.submittedPlayHandOffCount + 1,
                )
            }
        }
    }
}

private class KtorScrobbleEndpointTransport(
    account: PlaybackEndpointAccount,
    saltSource: SaltSource?,
    logSink: LogSink?,
    hostResolver: HostResolver,
) : ScrobbleEndpointTransport {
    private val client = AuthenticatedEndpointClient(
        credentials = AuthenticatedEndpointCredentials(
            normalizedBaseUrl = account.normalizedBaseUrl,
            username = account.username,
            password = account.password,
            allowLocalHttp = account.allowLocalHttp,
        ),
        operationName = "playback.scrobble",
        saltSource = saltSource,
        logSink = logSink,
        hostResolver = hostResolver,
    )

    override suspend fun request(
        parameters: Map<String, String>,
    ): AuthenticatedEndpointResponse = client.request("scrobble", parameters)

    override suspend fun extensions(): AuthenticatedEndpointResponse =
        client.request("getOpenSubsonicExtensions", emptyMap())

    fun close() {
        client.close()
    }
}

private fun AuthenticatedEndpointResponse.toScrobbleSendResult(): ScrobbleSendResult {
    val parsed = parseScrobbleEnvelope(body)
    if (statusCode == 429) {
        val busy = DomainError.Server.Busy(parseRetryAfterSeconds(headers.retryAfter))
        val presentation = parsed?.errorCode?.let {
            AccountConnectionContract.mapSubsonicError(it, "", redactedUrl)
        } ?: busy
        return ScrobbleSendResult.Failed(busy, presentation)
    }
    if (parsed == null) {
        return ScrobbleSendResult.Failed(DomainError.Protocol.MalformedEnvelope)
    }
    if (parsed.status == "ok" && statusCode in 200..299) {
        return ScrobbleSendResult.Sent(requestTrace)
    }
    val mapped = parsed.errorCode?.let {
        AccountConnectionContract.mapSubsonicError(it, "", redactedUrl)
    } ?: if (statusCode !in 200..299) {
        DomainError.Server.Unknown(statusCode)
    } else {
        DomainError.Protocol.MalformedEnvelope
    }
    return ScrobbleSendResult.Failed(mapped)
}

private data class ParsedScrobbleEnvelope(
    val status: String,
    val errorCode: Int?,
)

private fun parseScrobbleEnvelope(body: ByteArray): ParsedScrobbleEnvelope? = try {
    val start = body.binaryPayloadContentStartIndex()
    val root = SCROBBLE_JSON.parseToJsonElement(
        body.copyOfRange(start, body.size).decodeToString(),
    ) as? JsonObject ?: return null
    val payload = root["subsonic-response"] as? JsonObject ?: return null
    val status = (payload["status"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?: return null
    val error = payload["error"] as? JsonObject
    val errorCode = (error?.get("code") as? JsonPrimitive)?.intOrNull
    if (status != "ok" && errorCode == null) return null
    ParsedScrobbleEnvelope(status, errorCode)
} catch (_: IllegalArgumentException) {
    null
}

private val SCROBBLE_JSON = Json { ignoreUnknownKeys = true }
