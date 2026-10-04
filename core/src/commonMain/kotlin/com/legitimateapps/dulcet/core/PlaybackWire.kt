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

public data class PlaybackEndpointAccount(
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

public data class DirectPlayAudioProfile(
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

public data class TranscodingAudioProfile(
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

public data class PlaybackDeviceProfile(
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

public data class LegacyPlaybackPreference(
    val format: AudioContainer?,
    val maxBitRateKbps: Int?,
    /**
     * True for a streaming-quality cap (spec §12.5): the resolve plays the original file instead
     * when the device plays its container and its own bitrate is at or below [maxBitRateKbps]. An
     * adapter's explicit hint is false and always asks for the transcode.
     */
    val originalWhenItFits: Boolean,
) {
    public constructor(format: AudioContainer?, maxBitRateKbps: Int?) : this(format, maxBitRateKbps, false)

    init {
        require(maxBitRateKbps == null || maxBitRateKbps > 0)
    }

    val requestsTranscode: Boolean get() = format != null || maxBitRateKbps != null
}

public data class PlaybackResolveRequest(
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

    /**
     * This request with the person's streaming quality applied — one preference, two encodings
     * (spec §12.5). On the transcoding extension's path the cap lowers the `ClientInfo` limits for
     * both direct play and transcoding, in bits per second, so a source above it is transcoded. On
     * the legacy path it is the `maxBitRate` hint, and a format is always named with it: without
     * one the server picks its own downsampling format, and the validator and the engine would not
     * know which container to expect. The named format is the profile's own transcoding target.
     * The server may ignore the hint and send the original file; the plan accepts either
     * ([acceptedContainers]). Where the request carried no hint of its own, a source this device
     * plays at or below the cap is streamed as the original instead, read before the stream
     * ([LegacyPlaybackPreference.originalWhenItFits]).
     * [StreamingQuality.Original] returns this request unchanged. Never applied to a download.
     */
    public fun withStreamingQuality(quality: StreamingQuality): PlaybackResolveRequest {
        val capKbps = quality.maxBitRateKbps ?: return this
        val capBitsPerSecond = capKbps * 1_000
        return copy(
            deviceProfile = deviceProfile.copy(
                maxAudioBitrate = minOf(deviceProfile.maxAudioBitrate, capBitsPerSecond),
                maxTranscodingAudioBitrate = minOf(deviceProfile.maxTranscodingAudioBitrate, capBitsPerSecond),
            ),
            legacyPreference = LegacyPlaybackPreference(
                format = legacyPreference.format ?: deviceProfile.transcodingProfiles.first().container,
                maxBitRateKbps = minOf(legacyPreference.maxBitRateKbps ?: capKbps, capKbps),
                // Only a cap on a request with no hint of its own may yield to the original.
                originalWhenItFits = !legacyPreference.requestsTranscode,
            ),
        )
    }

    override fun toString(): String = "PlaybackResolveRequest(<redacted>)"
}

public enum class PlaybackDeliveryPath {
    ExtensionDirect,
    ExtensionTranscode,
    Legacy,
}

public enum class PlaybackDeliveryProtocol {
    HttpProgressive,
    Hls,
}

public sealed interface PlaybackWireTranscodeDecision {
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

public class RemotePlaybackWirePlan internal constructor(
    val playbackSessionId: PlaybackSessionId,
    val attemptId: AttemptId,
    val itemId: ProviderItemId,
    val path: PlaybackDeliveryPath,
    val deliveryProtocol: PlaybackDeliveryProtocol,
    val expectedContainer: AudioContainer,
    val transcode: PlaybackWireTranscodeDecision,
    /** Length observed while loading this immutable plan; null until a full response is loaded. */
    val contentLength: PlaybackContentLength? = null,
    internal val endpoint: String,
    internal val parameters: Map<String, String>,
    internal val resolutionRequest: PlaybackResolveRequest,
) : PlaybackPlan {
    override fun toString(): String = "RemotePlaybackWirePlan(<redacted>)"

    internal fun recording(contentLength: PlaybackContentLength?): RemotePlaybackWirePlan =
        RemotePlaybackWirePlan(
            playbackSessionId = playbackSessionId,
            attemptId = attemptId,
            itemId = itemId,
            path = path,
            deliveryProtocol = deliveryProtocol,
            expectedContainer = expectedContainer,
            transcode = transcode,
            contentLength = contentLength,
            endpoint = endpoint,
            parameters = parameters,
            resolutionRequest = resolutionRequest,
        )
}

public sealed interface PlaybackResolutionResult {
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

internal fun interface PlaybackAttemptIdSource {
    fun nextAttemptId(): AttemptId
}

public data class PlaybackByteRange(
    val start: Long,
    val endInclusive: Long? = null,
) {
    init {
        require(start >= 0)
        require(endInclusive == null || endInclusive >= start)
    }

    internal fun render(): String = "bytes=$start-${endInclusive ?: ""}"
}

public enum class PlaybackWireRequestPurpose {
    CurrentPlayback,
    Preload,
    Download,
}

public data class ActiveTranscodeCounts(
    val currentPlayback: Int = 0,
    val preload: Int = 0,
    val downloads: Int = 0,
) {
    init {
        require(currentPlayback >= 0 && preload >= 0 && downloads >= 0)
    }
}

/** Learned account-server budget. Current playback may preempt every lower-priority consumer. */
public class PlaybackTranscodeBudget {
    var maximumConcurrentTranscodes: Int = OPTIMISTIC_BUDGET
        private set

    fun maySchedule(
        purpose: PlaybackWireRequestPurpose,
        isTranscoded: Boolean,
        active: ActiveTranscodeCounts,
    ): Boolean {
        if (!isTranscoded) return true
        return when (purpose) {
            PlaybackWireRequestPurpose.CurrentPlayback -> true
            PlaybackWireRequestPurpose.Preload ->
                active.currentPlayback + active.preload < maximumConcurrentTranscodes
            PlaybackWireRequestPurpose.Download ->
                active.currentPlayback + active.preload + active.downloads <
                    maximumConcurrentTranscodes
        }
    }

    fun observeFailure(
        purpose: PlaybackWireRequestPurpose,
        isTranscoded: Boolean,
        error: DomainError,
    ) {
        if (
            purpose == PlaybackWireRequestPurpose.Preload &&
            isTranscoded &&
            error is DomainError.Server.Busy
        ) {
            maximumConcurrentTranscodes = 1
        }
    }

    fun reset() {
        maximumConcurrentTranscodes = OPTIMISTIC_BUDGET
    }

    private companion object {
        const val OPTIMISTIC_BUDGET = 2
    }
}

public sealed interface PlaybackLoadResult {
    data class Audio(
        val bytes: ByteArray,
        val plan: RemotePlaybackWirePlan,
        val validation: PlaybackStreamValidationResult.Audio,
        val requestTrace: RequestTrace,
        val didReresolveAfterBadRequest: Boolean,
    ) : PlaybackLoadResult

    data class Failed(
        val error: DomainError,
        val presentationError: DomainError,
        val plan: RemotePlaybackWirePlan,
        val shape: PlaybackErrorResponseShape?,
        val statusCode: Int?,
        val didReresolveAfterBadRequest: Boolean,
    ) : PlaybackLoadResult
}

public class PlaybackWireClient private constructor(
    private val account: PlaybackEndpointAccount,
    private val transport: PlaybackEndpointTransport,
    private val attemptIdSource: PlaybackAttemptIdSource,
    val transcodeBudget: PlaybackTranscodeBudget,
    @Suppress("UNUSED_PARAMETER") ownsTransportLifetime: Boolean,
) {
    constructor(
        account: PlaybackEndpointAccount,
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        account = account,
        transport = KtorPlaybackEndpointTransport(account, saltSource, logSink, hostResolver),
        attemptIdSource = SecurePlaybackAttemptIdSource,
        transcodeBudget = PlaybackTranscodeBudget(),
        ownsTransportLifetime = true,
    )

    internal constructor(
        account: PlaybackEndpointAccount,
        transport: PlaybackEndpointTransport,
        attemptIdSource: PlaybackAttemptIdSource = SecurePlaybackAttemptIdSource,
        transcodeBudget: PlaybackTranscodeBudget = PlaybackTranscodeBudget(),
    ) : this(account, transport, attemptIdSource, transcodeBudget, true)

    suspend fun resolve(request: PlaybackResolveRequest): PlaybackResolutionResult {
        require(request.itemId.providerInstanceId == account.providerInstanceId)
        return try {
            if (request.supportsTranscodingExtension) {
                resolveExtension(request)
            } else {
                PlaybackResolutionResult.Resolved(resolveLegacy(withoutAHintTheSourceMeets(request)))
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

    suspend fun load(
        plan: RemotePlaybackWirePlan,
        purpose: PlaybackWireRequestPurpose = PlaybackWireRequestPurpose.CurrentPlayback,
        range: PlaybackByteRange? = null,
    ): PlaybackLoadResult = load(plan, purpose, range, allowBadRequestReresolution = true)

    private suspend fun load(
        plan: RemotePlaybackWirePlan,
        purpose: PlaybackWireRequestPurpose,
        range: PlaybackByteRange?,
        allowBadRequestReresolution: Boolean,
    ): PlaybackLoadResult {
        require(plan.itemId.providerInstanceId == account.providerInstanceId)
        return try {
            val response = transport.get(
                endpoint = plan.endpoint,
                parameters = plan.parameters,
                // Every declared length is exact: no plan asks for an estimate (resolveLegacy), so
                // a body short of its Content-Length is truncation, never the end of the song.
                options = AuthenticatedEndpointRequestOptions(range = range?.render()),
            )
            val validation = PlaybackStreamValidator.validate(response, plan.acceptedContainers())
            if (validation is PlaybackStreamValidationResult.Audio) {
                return PlaybackLoadResult.Audio(
                    bytes = response.body,
                    plan = if (range == null) plan.recording(validation.contentLength) else plan,
                    validation = validation,
                    requestTrace = response.requestTrace,
                    didReresolveAfterBadRequest = !allowBadRequestReresolution,
                )
            }
            validation as PlaybackStreamValidationResult.Failure
            if (
                allowBadRequestReresolution &&
                plan.path == PlaybackDeliveryPath.ExtensionTranscode &&
                validation.statusCode == BAD_REQUEST
            ) {
                return reresolveAfterBadRequest(plan, purpose, range)
            }
            val isTranscoded = plan.isTranscoded()
            transcodeBudget.observeFailure(purpose, isTranscoded, validation.error)
            PlaybackLoadResult.Failed(
                error = validation.error,
                presentationError = validation.presentationError,
                plan = plan,
                shape = validation.shape,
                statusCode = validation.statusCode,
                didReresolveAfterBadRequest = !allowBadRequestReresolution,
            )
        } catch (_: CancellationException) {
            loadFailure(plan, DomainError.Transport.Cancelled, !allowBadRequestReresolution)
        } catch (failure: AuthenticatedEndpointFailure) {
            loadFailure(plan, failure.error, !allowBadRequestReresolution)
        } catch (failure: Throwable) {
            loadFailure(
                plan,
                mapAccountConnectionFailure(failure),
                !allowBadRequestReresolution,
            )
        }
    }

    private suspend fun reresolveAfterBadRequest(
        stalePlan: RemotePlaybackWirePlan,
        purpose: PlaybackWireRequestPurpose,
        range: PlaybackByteRange?,
    ): PlaybackLoadResult {
        val nextAttemptId = attemptIdSource.nextAttemptId()
        check(nextAttemptId != stalePlan.attemptId) {
            "PlaybackAttemptIdSource returned the current attempt identity"
        }
        val refreshedRequest = stalePlan.resolutionRequest.copy(attemptId = nextAttemptId)
        val resolution = resolve(refreshedRequest)
        if (resolution is PlaybackResolutionResult.Failed) {
            return loadFailure(
                stalePlan,
                resolution.error,
                didReresolveAfterBadRequest = true,
            )
        }
        val refreshedPlan = (resolution as PlaybackResolutionResult.Resolved).plan
        check(refreshedPlan.playbackSessionId == stalePlan.playbackSessionId)
        check(refreshedPlan.attemptId != stalePlan.attemptId)
        return load(
            plan = refreshedPlan,
            purpose = purpose,
            range = range,
            allowBadRequestReresolution = false,
        )
    }

    private fun loadFailure(
        plan: RemotePlaybackWirePlan,
        error: DomainError,
        didReresolveAfterBadRequest: Boolean,
    ) = PlaybackLoadResult.Failed(
        error = error,
        presentationError = error,
        plan = plan,
        shape = null,
        statusCode = null,
        didReresolveAfterBadRequest = didReresolveAfterBadRequest,
    )

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

    /**
     * A streaming-quality cap the original file already meets is dropped, so the original plays:
     * seekable, resumable and never re-encoded (spec §12.5). It applies only where this device
     * plays the source's container directly and the source's own bitrate, read with `getSong`, is
     * at or below the hint. An unreadable bitrate keeps the hint — the cap is honoured when in
     * doubt. A server-offset request always keeps it: `timeOffset` needs a transcode.
     */
    private suspend fun withoutAHintTheSourceMeets(request: PlaybackResolveRequest): PlaybackResolveRequest {
        if (!request.legacyPreference.originalWhenItFits) return request
        val capKbps = request.legacyPreference.maxBitRateKbps ?: return request
        if (request.legacyTimeOffset != null) return request
        if (request.deviceProfile.directPlayProfiles.none { request.sourceContainer in it.containers }) {
            return request
        }
        val sourceKbps = sourceBitRateKbps(request.itemId.rawId) ?: return request
        if (sourceKbps > capKbps) return request
        return request.copy(legacyPreference = LegacyPlaybackPreference(format = null, maxBitRateKbps = null))
    }

    private suspend fun sourceBitRateKbps(rawId: String): Int? = try {
        val response = transport.get(SONG_ENDPOINT, linkedMapOf("id" to rawId))
        val envelope = parseLibraryEnvelope(response.body.decodeToString())
        val song = envelope?.takeIf { response.statusCode in 200..299 && it.status == "ok" }
            ?.payload?.get("song") as? JsonObject
        (song?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it == rawId }?.let {
            (song["bitRate"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { kbps -> kbps > 0 }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun resolveLegacy(request: PlaybackResolveRequest): RemotePlaybackWirePlan {
        val preference = request.legacyPreference
        val parameters = linkedMapOf("id" to request.itemId.rawId).apply {
            preference.format?.let { put("format", it.wireName()) }
            preference.maxBitRateKbps?.let { put("maxBitRate", it.toString()) }
            // Never `estimateContentLength` (spec §12.5, §28 2026-10-04): a cold transcode's
            // estimate can be short of the real body, and the server then cuts the body off at an
            // earlier write and closes. That ends exactly as a complete body does, so no client can
            // tell it from the whole song. Without the flag a cold transcode arrives chunked and
            // complete, and a cached one with an exact length.
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
        const val BAD_REQUEST = 400
        const val LEGACY_STREAM_ENDPOINT = "stream"
        const val SONG_ENDPOINT = "getSong"
        const val TRANSCODE_DECISION_ENDPOINT = "getTranscodeDecision"
        const val TRANSCODE_STREAM_ENDPOINT = "getTranscodeStream"
        const val MEDIA_TYPE_SONG = "song"
    }
}

/**
 * The containers a response to this plan may validly be. A legacy `format` hint is a hint: a server
 * without a working transcoder answers it with the original file (trap 28), which played before a
 * cap was chosen and must still play. So a hinted plan accepts the hinted format first and the
 * source's own container second, each under its full signature check. Every other plan expects
 * exactly one container.
 */
internal fun RemotePlaybackWirePlan.acceptedContainers(): List<AudioContainer> {
    val hint = transcode as? PlaybackWireTranscodeDecision.LegacyHint
    val source = resolutionRequest.sourceContainer
    return if (path == PlaybackDeliveryPath.Legacy && hint?.format != null && source != expectedContainer) {
        listOf(expectedContainer, source)
    } else {
        listOf(expectedContainer)
    }
}

internal fun RemotePlaybackWirePlan.isTranscoded(): Boolean = when (val decision = transcode) {
    PlaybackWireTranscodeDecision.DirectPlay -> false
    is PlaybackWireTranscodeDecision.Transcoded -> true
    is PlaybackWireTranscodeDecision.LegacyHint ->
        decision.format != null || decision.maxBitRateKbps != null
}

/**
 * The plan a download sends: the legacy `stream` request with `format=raw`, the Subsonic API's
 * explicit "no transcoding" (spec §14.5). A request with no format lets the server apply a
 * transcoding configured for this player, and the reference server's `download` endpoint does the
 * same when it transcodes downloads automatically; `raw` returns before either (OBSERVED in the
 * reference server's source, v0.63.2 `ResolveRequest`). Only an original-file legacy plan qualifies.
 */
internal fun RemotePlaybackWirePlan.asOriginalFileDownload(): RemotePlaybackWirePlan {
    require(path == PlaybackDeliveryPath.Legacy && endpoint == "stream" && !isTranscoded()) {
        "a download is the original file over legacy stream"
    }
    return RemotePlaybackWirePlan(
        playbackSessionId = playbackSessionId,
        attemptId = attemptId,
        itemId = itemId,
        path = path,
        deliveryProtocol = deliveryProtocol,
        expectedContainer = expectedContainer,
        transcode = transcode,
        contentLength = contentLength,
        endpoint = endpoint,
        parameters = LinkedHashMap(parameters).apply { put("format", ORIGINAL_FILE_FORMAT) },
        resolutionRequest = resolutionRequest,
    )
}

/** The Subsonic API's `format` value that disables transcoding (API 1.9.0). */
internal const val ORIGINAL_FILE_FORMAT = "raw"

private object SecurePlaybackAttemptIdSource : PlaybackAttemptIdSource {
    override fun nextAttemptId(): AttemptId = AttemptId(
        "attempt:${secureRandomBytes(16).toLowerHex()}",
    )
}

internal class KtorPlaybackEndpointTransport(
    account: PlaybackEndpointAccount,
    saltSource: SaltSource?,
    logSink: LogSink?,
    hostResolver: HostResolver,
    clientTransport: AccountClientTransport = AccountClientTransport.Default(),
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
        clientTransport = clientTransport,
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
