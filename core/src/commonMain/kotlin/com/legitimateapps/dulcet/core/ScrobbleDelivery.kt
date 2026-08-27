package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

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
}

/** Low-level sender shared by ephemeral now-playing and the later durable outbox worker. */
public class ScrobbleEndpointSender private constructor(
    private val transport: ScrobbleEndpointTransport,
    private val closeTransport: (() -> Unit)?,
) {
    constructor(
        account: PlaybackEndpointAccount,
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        transport = KtorScrobbleEndpointTransport(account, saltSource, logSink, hostResolver),
        closeTransport = null,
    )

    internal constructor(transport: ScrobbleEndpointTransport) : this(transport, null)

    suspend fun send(request: ScrobbleEndpointRequest): ScrobbleSendResult = try {
        val parameters = when (val event = request.event) {
            is RecordedPlaybackEvent.NowPlaying -> linkedMapOf(
                "id" to event.itemId.rawId,
                "submission" to "false",
            )
            is RecordedPlaybackEvent.SubmittedPlay -> linkedMapOf(
                "id" to event.itemId.rawId,
                "time" to event.sessionStartWallClock.epochMilliseconds.toString(),
                "submission" to "true",
            )
        }
        val response = transport.request(parameters)
        response.toScrobbleSendResult()
    } catch (_: CancellationException) {
        ScrobbleSendResult.Failed(DomainError.Transport.Cancelled)
    } catch (failure: AuthenticatedEndpointFailure) {
        ScrobbleSendResult.Failed(failure.error)
    } catch (failure: Throwable) {
        ScrobbleSendResult.Failed(mapAccountConnectionFailure(failure))
    }

    fun close() {
        closeTransport?.invoke()
        (transport as? KtorScrobbleEndpointTransport)?.close()
    }
}

/** The single hand-off for submitted plays; production uses [PersistentScrobbleOutbox]. */
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
