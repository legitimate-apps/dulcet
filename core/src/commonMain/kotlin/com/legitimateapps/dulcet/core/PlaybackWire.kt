package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration

internal data class PlaybackEndpointAccount(
    val providerInstanceId: String,
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    init {
        require(providerInstanceId.isNotBlank())
    }

    override fun toString(): String = "PlaybackEndpointAccount(<redacted>)"
}

internal data class DirectPlayAudioProfile(
    val containers: List<AudioContainer>,
    val audioCodecs: List<String>,
    val protocols: List<String> = listOf("http"),
    val maxAudioChannels: Int,
) {
    init {
        require(containers.isNotEmpty())
        require(audioCodecs.isNotEmpty() && audioCodecs.none(String::isBlank))
        require(protocols.isNotEmpty() && protocols.none(String::isBlank))
        require(maxAudioChannels > 0)
    }
}

internal data class TranscodingAudioProfile(
    val container: AudioContainer,
    val audioCodec: String,
    val protocol: String = "http",
    val maxAudioChannels: Int,
) {
    init {
        require(audioCodec.isNotBlank())
        require(protocol.isNotBlank())
        require(maxAudioChannels > 0)
    }
}

internal data class PlaybackDeviceProfile(
    val name: String,
    val platform: String,
    val maxAudioBitrate: Int,
    val maxTranscodingAudioBitrate: Int,
    val directPlayProfiles: List<DirectPlayAudioProfile>,
    val transcodingProfiles: List<TranscodingAudioProfile>,
) {
    init {
        require(name.isNotBlank() && platform.isNotBlank())
        require(maxAudioBitrate > 0 && maxTranscodingAudioBitrate > 0)
        require(directPlayProfiles.isNotEmpty())
        require(transcodingProfiles.isNotEmpty())
    }

    /** The server silently rejects an unrecognised wrapper, so this shape is intentionally flat. */
    internal fun toFlatClientInfoJson(): String = buildJsonObject {
        put("name", name)
        put("platform", platform)
        put("maxAudioBitrate", maxAudioBitrate)
        put("maxTranscodingAudioBitrate", maxTranscodingAudioBitrate)
        putJsonArray("directPlayProfiles") {
            directPlayProfiles.forEach { profile ->
                addJsonObject {
                    putStringArray("containers", profile.containers.map(AudioContainer::wireName))
                    putStringArray("audioCodecs", profile.audioCodecs)
                    putStringArray("protocols", profile.protocols)
                    put("maxAudioChannels", profile.maxAudioChannels)
                }
            }
        }
        putJsonArray("transcodingProfiles") {
            transcodingProfiles.forEach { profile ->
                addJsonObject {
                    put("container", profile.container.wireName())
                    put("audioCodec", profile.audioCodec)
                    put("protocol", profile.protocol)
                    put("maxAudioChannels", profile.maxAudioChannels)
                }
            }
        }
        put("codecProfiles", JsonArray(emptyList()))
    }.toString()
}

internal data class LegacyPlaybackPreference(
    val format: AudioContainer?,
    val maxBitRateKbps: Int?,
) {
    init {
        require(maxBitRateKbps == null || maxBitRateKbps > 0)
    }

    val requestsTranscode: Boolean get() = format != null || maxBitRateKbps != null
}

internal data class PlaybackResolveRequest(
    val playbackSessionId: PlaybackSessionId,
    val attemptId: AttemptId,
    val itemId: ProviderItemId,
    val sourceContainer: AudioContainer,
    val supportsTranscodingExtension: Boolean,
    val deviceProfile: PlaybackDeviceProfile,
    val legacyPreference: LegacyPlaybackPreference,
    val legacyTimeOffset: Duration? = null,
) {
    init {
        require(
            legacyTimeOffset == null ||
                (!legacyTimeOffset.isNegative() && legacyTimeOffset.isFinite()),
        )
    }

    override fun toString(): String = "PlaybackResolveRequest(<redacted>)"
}

internal enum class PlaybackDeliveryPath {
    ExtensionDirect,
    ExtensionTranscode,
    Legacy,
}

internal enum class PlaybackDeliveryProtocol {
    HttpProgressive,
    Hls,
}

internal sealed interface PlaybackWireTranscodeDecision {
    data object DirectPlay : PlaybackWireTranscodeDecision

    class Transcoded internal constructor(
        internal val opaqueParams: String,
        val reasons: List<String>,
    ) : PlaybackWireTranscodeDecision {
        init {
            require(opaqueParams.isNotBlank())
        }

        override fun toString(): String = "Transcoded(<opaque>)"
    }

    data class LegacyHint(
        val format: AudioContainer?,
        val maxBitRateKbps: Int?,
    ) : PlaybackWireTranscodeDecision
}

internal data class RemotePlaybackWirePlan(
    val playbackSessionId: PlaybackSessionId,
    val attemptId: AttemptId,
    val itemId: ProviderItemId,
    val path: PlaybackDeliveryPath,
    val deliveryProtocol: PlaybackDeliveryProtocol,
    val expectedContainer: AudioContainer,
    val transcode: PlaybackWireTranscodeDecision,
    internal val endpoint: String,
    internal val parameters: Map<String, String>,
    internal val resolutionRequest: PlaybackResolveRequest,
) : PlaybackPlan {
    override fun toString(): String = "RemotePlaybackWirePlan(<redacted>)"
}

internal sealed interface PlaybackResolutionResult {
    data class Resolved(val plan: RemotePlaybackWirePlan) : PlaybackResolutionResult
    data class Failed(val error: DomainError) : PlaybackResolutionResult
}

internal interface PlaybackEndpointTransport {
    suspend fun get(
        endpoint: String,
        parameters: Map<String, String>,
        options: AuthenticatedEndpointRequestOptions = AuthenticatedEndpointRequestOptions(),
    ): AuthenticatedEndpointResponse

    suspend fun postJson(
        endpoint: String,
        parameters: Map<String, String>,
        jsonBody: String,
    ): AuthenticatedEndpointResponse

    fun close()
}

internal class PlaybackWireClient private constructor(
    private val account: PlaybackEndpointAccount,
    private val transport: PlaybackEndpointTransport,
) {
    constructor(
        account: PlaybackEndpointAccount,
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        account = account,
        transport = KtorPlaybackEndpointTransport(account, saltSource, logSink, hostResolver),
    )

    internal constructor(
        account: PlaybackEndpointAccount,
        transport: PlaybackEndpointTransport,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(account, transport)

    suspend fun resolve(request: PlaybackResolveRequest): PlaybackResolutionResult {
        require(request.itemId.providerInstanceId == account.providerInstanceId)
        return try {
            if (request.supportsTranscodingExtension) {
                resolveExtension(request)
            } else {
                PlaybackResolutionResult.Resolved(resolveLegacy(request))
            }
        } catch (_: CancellationException) {
            PlaybackResolutionResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: AuthenticatedEndpointFailure) {
            PlaybackResolutionResult.Failed(failure.error)
        } catch (failure: Throwable) {
            PlaybackResolutionResult.Failed(mapAccountConnectionFailure(failure))
        }
    }

    fun close() {
        transport.close()
    }

    private suspend fun resolveExtension(
        request: PlaybackResolveRequest,
    ): PlaybackResolutionResult {
        val response = transport.postJson(
            endpoint = TRANSCODE_DECISION_ENDPOINT,
            parameters = linkedMapOf(
                "mediaId" to request.itemId.rawId,
                "mediaType" to MEDIA_TYPE_SONG,
            ),
            jsonBody = request.deviceProfile.toFlatClientInfoJson(),
        )
        responseError(response)?.let { return PlaybackResolutionResult.Failed(it) }
        val decision = parseTranscodeDecision(response.body)
            ?: return PlaybackResolutionResult.Failed(DomainError.Protocol.MalformedEnvelope)
        if (decision.canDirectPlay) {
            val container = decision.sourceContainer
                ?: return PlaybackResolutionResult.Failed(DomainError.Protocol.MalformedEnvelope)
            return PlaybackResolutionResult.Resolved(
                RemotePlaybackWirePlan(
                    playbackSessionId = request.playbackSessionId,
                    attemptId = request.attemptId,
                    itemId = request.itemId,
                    path = PlaybackDeliveryPath.ExtensionDirect,
                    deliveryProtocol = PlaybackDeliveryProtocol.HttpProgressive,
                    expectedContainer = container,
                    transcode = PlaybackWireTranscodeDecision.DirectPlay,
                    endpoint = LEGACY_STREAM_ENDPOINT,
                    parameters = mapOf("id" to request.itemId.rawId),
                    resolutionRequest = request,
                ),
            )
        }
        if (decision.canTranscode) {
            val opaqueParams = decision.transcodeParams
                ?: return PlaybackResolutionResult.Failed(DomainError.Protocol.MalformedEnvelope)
            val container = decision.transcodeContainer
                ?: return PlaybackResolutionResult.Failed(DomainError.Protocol.MalformedEnvelope)
            val protocol = decision.transcodeProtocol
                ?: return PlaybackResolutionResult.Failed(DomainError.Protocol.MalformedEnvelope)
            return PlaybackResolutionResult.Resolved(
                RemotePlaybackWirePlan(
                    playbackSessionId = request.playbackSessionId,
                    attemptId = request.attemptId,
                    itemId = request.itemId,
                    path = PlaybackDeliveryPath.ExtensionTranscode,
                    deliveryProtocol = protocol,
                    expectedContainer = container,
                    transcode = PlaybackWireTranscodeDecision.Transcoded(
                        opaqueParams = opaqueParams,
                        reasons = decision.reasons,
                    ),
                    endpoint = TRANSCODE_STREAM_ENDPOINT,
                    parameters = linkedMapOf(
                        "transcodeParams" to opaqueParams,
                        "mediaId" to request.itemId.rawId,
                        "mediaType" to MEDIA_TYPE_SONG,
                    ),
                    resolutionRequest = request,
                ),
            )
        }
        return PlaybackResolutionResult.Failed(DomainError.Playback.NoPlayableSource)
    }

    private fun resolveLegacy(request: PlaybackResolveRequest): RemotePlaybackWirePlan {
        val preference = request.legacyPreference
        val parameters = linkedMapOf("id" to request.itemId.rawId).apply {
            preference.format?.let { put("format", it.wireName()) }
            preference.maxBitRateKbps?.let { put("maxBitRate", it.toString()) }
            if (preference.requestsTranscode) put("estimateContentLength", "true")
            request.legacyTimeOffset?.let { put("timeOffset", it.inWholeSeconds.toString()) }
        }
        return RemotePlaybackWirePlan(
            playbackSessionId = request.playbackSessionId,
            attemptId = request.attemptId,
            itemId = request.itemId,
            path = PlaybackDeliveryPath.Legacy,
            deliveryProtocol = PlaybackDeliveryProtocol.HttpProgressive,
            expectedContainer = preference.format ?: request.sourceContainer,
            transcode = PlaybackWireTranscodeDecision.LegacyHint(
                format = preference.format,
                maxBitRateKbps = preference.maxBitRateKbps,
            ),
            endpoint = LEGACY_STREAM_ENDPOINT,
            parameters = parameters,
            resolutionRequest = request,
        )
    }

    private fun responseError(response: AuthenticatedEndpointResponse): DomainError? {
        val envelope = response.body.inspectSubsonicBinaryEnvelope()
        if (response.statusCode == 429) {
            return DomainError.Server.Busy(parseRetryAfterSeconds(response.headers.retryAfter))
        }
        if (envelope is SubsonicBinaryEnvelopeInspection.Error) {
            return AccountConnectionContract.mapSubsonicError(
                envelope.code,
                message = "",
                requestUrl = response.redactedUrl,
            )
        }
        if (response.statusCode !in 200..299) return when (response.statusCode) {
            401 -> DomainError.Auth.InvalidCredentials
            403 -> DomainError.Auth.Forbidden
            else -> DomainError.Server.Unknown(response.statusCode)
        }
        return null
    }

    private companion object {
        const val LEGACY_STREAM_ENDPOINT = "stream"
        const val TRANSCODE_DECISION_ENDPOINT = "getTranscodeDecision"
        const val TRANSCODE_STREAM_ENDPOINT = "getTranscodeStream"
        const val MEDIA_TYPE_SONG = "song"
    }
}

private class KtorPlaybackEndpointTransport(
    account: PlaybackEndpointAccount,
    saltSource: SaltSource?,
    logSink: LogSink?,
    hostResolver: HostResolver,
) : PlaybackEndpointTransport {
    private val client = AuthenticatedEndpointClient(
        credentials = AuthenticatedEndpointCredentials(
            normalizedBaseUrl = account.normalizedBaseUrl,
            username = account.username,
            password = account.password,
            allowLocalHttp = account.allowLocalHttp,
        ),
        operationName = "playback.wire",
        saltSource = saltSource,
        logSink = logSink,
        hostResolver = hostResolver,
    )

    override suspend fun get(
        endpoint: String,
        parameters: Map<String, String>,
        options: AuthenticatedEndpointRequestOptions,
    ): AuthenticatedEndpointResponse = client.request(endpoint, parameters, options)

    override suspend fun postJson(
        endpoint: String,
        parameters: Map<String, String>,
        jsonBody: String,
    ): AuthenticatedEndpointResponse = client.postJson(endpoint, parameters, jsonBody)

    override fun close() {
        client.close()
    }
}

private data class ParsedTranscodeDecision(
    val canDirectPlay: Boolean,
    val canTranscode: Boolean,
    val reasons: List<String>,
    val transcodeParams: String?,
    val sourceContainer: AudioContainer?,
    val transcodeContainer: AudioContainer?,
    val transcodeProtocol: PlaybackDeliveryProtocol?,
)

private fun parseTranscodeDecision(body: ByteArray): ParsedTranscodeDecision? = try {
    val start = body.binaryPayloadContentStartIndex()
    val root = PLAYBACK_JSON.parseToJsonElement(
        body.copyOfRange(start, body.size).decodeToString(),
    ) as? JsonObject ?: return null
    val envelope = root["subsonic-response"] as? JsonObject ?: return null
    if (envelope.requiredString("status") != "ok") return null
    val decision = envelope["transcodeDecision"] as? JsonObject ?: return null
    val canDirectPlay = decision.requiredBoolean("canDirectPlay") ?: return null
    val canTranscode = decision.requiredBoolean("canTranscode") ?: return null
    val reasons = (decision["transcodeReason"] as? JsonArray)?.map { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    } ?: emptyList()
    ParsedTranscodeDecision(
        canDirectPlay = canDirectPlay,
        canTranscode = canTranscode,
        reasons = reasons,
        transcodeParams = decision.optionalString("transcodeParams"),
        sourceContainer = (decision["sourceStream"] as? JsonObject)
            ?.optionalString("container")
            ?.toAudioContainerOrNull(),
        transcodeContainer = (decision["transcodeStream"] as? JsonObject)
            ?.optionalString("container")
            ?.toAudioContainerOrNull(),
        transcodeProtocol = (decision["transcodeStream"] as? JsonObject)
            ?.optionalString("protocol")
            ?.toPlaybackDeliveryProtocolOrNull(),
    )
} catch (_: IllegalArgumentException) {
    null
}

private fun AudioContainer.wireName(): String = when (this) {
    AudioContainer.Mp3 -> "mp3"
    AudioContainer.Mp4 -> "mp4"
    AudioContainer.Wav -> "wav"
    AudioContainer.Flac -> "flac"
    AudioContainer.Ogg -> "ogg"
    AudioContainer.AdtsAac -> "aac"
}

private fun String.toAudioContainerOrNull(): AudioContainer? = when (lowercase()) {
    "mp3" -> AudioContainer.Mp3
    "mp4", "m4a" -> AudioContainer.Mp4
    "wav", "wave" -> AudioContainer.Wav
    "flac" -> AudioContainer.Flac
    "ogg", "oga", "opus" -> AudioContainer.Ogg
    "aac", "adts" -> AudioContainer.AdtsAac
    else -> null
}

private fun String.toPlaybackDeliveryProtocolOrNull(): PlaybackDeliveryProtocol? = when (lowercase()) {
    "http", "https" -> PlaybackDeliveryProtocol.HttpProgressive
    "hls" -> PlaybackDeliveryProtocol.Hls
    else -> null
}

private fun JsonObject.requiredString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.optionalString(name: String): String? {
    if (name !in this) return null
    return requiredString(name)
}

private fun JsonObject.requiredBoolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

private fun JsonArrayBuilder.addJsonObject(block: JsonObjectBuilder.() -> Unit) {
    add(buildJsonObject(block))
}

private fun JsonObjectBuilder.putStringArray(name: String, values: List<String>) {
    put(name, buildJsonArray { values.forEach { add(it) } })
}

private val PLAYBACK_JSON = Json { ignoreUnknownKeys = true }
