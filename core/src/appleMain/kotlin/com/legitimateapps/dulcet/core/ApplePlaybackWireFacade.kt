package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.posix.memcpy

/** Synchronous cancellation handle for an exported playback wire operation. */
public interface ApplePlaybackWireOperation {
    public fun cancel()
}

/** Opaque wrapper around a core-owned resolved plan. */
public class AppleRemotePlaybackPlanDto internal constructor(
    internal val corePlan: RemotePlaybackWirePlan,
) {
    public val playbackSessionId: String get() = corePlan.playbackSessionId.value
    public val attemptId: String get() = corePlan.attemptId.value
    public val deliveryProtocol: String get() = corePlan.deliveryProtocol.name
    public val expectedContainer: String get() = corePlan.expectedContainer.name

    override fun toString(): String = "AppleRemotePlaybackPlanDto(<redacted>)"
}

public class ApplePlaybackResolutionOutcomeDto internal constructor(
    public val plan: AppleRemotePlaybackPlanDto?,
    public val errorKind: String?,
)

/** Signed request material. Its renderer never includes the credential-bearing URL. */
public class ApplePlaybackPreparedRequestDto internal constructor(
    public val url: String,
    public val hostHeader: String?,
    public val rangeHeader: String?,
) {
    override fun toString(): String = "ApplePlaybackPreparedRequestDto(<redacted>)"
}

public class ApplePlaybackRequestPreparationOutcomeDto internal constructor(
    public val request: ApplePlaybackPreparedRequestDto?,
    public val errorKind: String?,
)

public class ApplePlaybackValidationOutcomeDto internal constructor(
    public val accepted: Boolean,
    /** Total resource length, or -1 when the response does not establish one. */
    public val contentLength: Long,
    public val supportsByteRanges: Boolean,
    public val errorKind: String?,
    public val refreshReason: String?,
    public val responseShape: String?,
    /** Core-derived Retry-After duration, or -1 when absent/not applicable. */
    public val retryAfterMilliseconds: Long,
)

public class ApplePlaybackRedirectDecisionDto internal constructor(
    /** `preserve`, `strip`, or `reject`. */
    public val kind: String,
    public val queryItemNamesToStrip: List<String>,
)

/**
 * Objective-C-compatible access to core-owned request construction and validation.
 * Public methods convert every failure to closed DTOs; no Kotlin exception crosses into Swift.
 */
public class ApplePlaybackWireClient(
    private val account: PlaybackEndpointAccount,
) {
    private val scope: CoroutineScope = MainScope()
    private val wireClient = PlaybackWireClient(account)
    private val requestClient = AuthenticatedEndpointClient(
        credentials = AuthenticatedEndpointCredentials(
            normalizedBaseUrl = account.normalizedBaseUrl,
            username = account.username,
            password = account.password,
            allowLocalHttp = account.allowLocalHttp,
        ),
        operationName = "playback.apple-resource",
    )

    public fun startResolve(
        request: PlaybackResolveRequest,
        completion: (ApplePlaybackResolutionOutcomeDto) -> Unit,
    ): ApplePlaybackWireOperation = ApplePlaybackWireOperationImpl(scope) {
        val outcome = try {
            when (val result = wireClient.resolve(request)) {
                is PlaybackResolutionResult.Resolved -> ApplePlaybackResolutionOutcomeDto(
                    plan = AppleRemotePlaybackPlanDto(result.plan),
                    errorKind = null,
                )
                is PlaybackResolutionResult.Failed -> ApplePlaybackResolutionOutcomeDto(
                    plan = null,
                    errorKind = result.error.applePlaybackErrorKind(),
                )
            }
        } catch (_: CancellationException) {
            ApplePlaybackResolutionOutcomeDto(null, "cancelled")
        } catch (failure: Throwable) {
            ApplePlaybackResolutionOutcomeDto(
                null,
                mapAccountConnectionFailure(failure).applePlaybackErrorKind(),
            )
        }
        completion(outcome)
    }

    public fun startPrepareRequest(
        plan: AppleRemotePlaybackPlanDto,
        rangeStart: Long,
        rangeEndInclusive: Long,
        completion: (ApplePlaybackRequestPreparationOutcomeDto) -> Unit,
    ): ApplePlaybackWireOperation = ApplePlaybackWireOperationImpl(scope) {
        val outcome = try {
            val range = PlaybackByteRange(rangeStart, rangeEndInclusive)
            val prepared = requestClient.prepareGetRequest(
                endpoint = plan.corePlan.endpoint,
                parameters = plan.corePlan.parameters,
                options = AuthenticatedEndpointRequestOptions(range.render()),
            )
            ApplePlaybackRequestPreparationOutcomeDto(
                request = ApplePlaybackPreparedRequestDto(
                    prepared.url,
                    prepared.hostHeader,
                    prepared.rangeHeader,
                ),
                errorKind = null,
            )
        } catch (_: CancellationException) {
            ApplePlaybackRequestPreparationOutcomeDto(null, "cancelled")
        } catch (failure: AuthenticatedEndpointFailure) {
            ApplePlaybackRequestPreparationOutcomeDto(
                null,
                failure.error.applePlaybackErrorKind(),
            )
        } catch (failure: LocalHttpPolicyFailure) {
            ApplePlaybackRequestPreparationOutcomeDto(
                null,
                failure.error.applePlaybackErrorKind(),
            )
        } catch (failure: Throwable) {
            ApplePlaybackRequestPreparationOutcomeDto(
                null,
                mapAccountConnectionFailure(failure).applePlaybackErrorKind(),
            )
        }
        completion(outcome)
    }

    @OptIn(ExperimentalForeignApi::class)
    public fun validateResponse(
        plan: AppleRemotePlaybackPlanDto,
        statusCode: Int,
        contentType: String?,
        contentLength: Long,
        retryAfter: String?,
        acceptRanges: String?,
        contentRange: String?,
        body: NSData,
        requestedRangeStart: Long,
        requestedRangeEndInclusive: Long,
        requiresAudioSignature: Boolean,
    ): ApplePlaybackValidationOutcomeDto = try {
        val bytes = body.toByteArray()
        val headers = AuthenticatedEndpointResponseHeaders(
            contentType = contentType,
            contentLength = contentLength.takeIf { it >= 0 }?.let { byteCount ->
                if (
                    statusCode == 200 &&
                    contentRange == null &&
                    plan.corePlan.usesEstimatedLegacyContentLength()
                ) {
                    PlaybackContentLength.Estimated(byteCount)
                } else {
                    PlaybackContentLength.Exact(byteCount)
                }
            },
            retryAfter = retryAfter,
            acceptRanges = acceptRanges,
            contentRange = contentRange,
        )
        val response = AuthenticatedEndpointResponse(
            statusCode = statusCode,
            body = bytes,
            redactedUrl = "<redacted-url>",
            headers = headers,
            requestTrace = syntheticApplePlaybackTrace(),
        )
        val validation = PlaybackStreamValidator.validate(
            response,
            plan.corePlan.expectedContainer,
            requiresAudioSignature,
        )
        if (validation is PlaybackStreamValidationResult.Failure) {
            val expired = statusCode == 400 &&
                plan.corePlan.path == PlaybackDeliveryPath.ExtensionTranscode
            return ApplePlaybackValidationOutcomeDto(
                accepted = false,
                contentLength = -1,
                supportsByteRanges = false,
                errorKind = validation.error.applePlaybackErrorKind(),
                refreshReason = when {
                    statusCode == 401 -> "Unauthorized"
                    expired -> "Expired"
                    else -> "ValidationFailed"
                },
                responseShape = validation.shape.name,
                retryAfterMilliseconds = (validation.error as? DomainError.Server.Busy)
                    ?.retryAfter
                    ?.inWholeMilliseconds
                    ?: -1,
            )
        }
        validation as PlaybackStreamValidationResult.Audio
        val range = PlaybackByteRange(requestedRangeStart, requestedRangeEndInclusive)
        val totalLength = validateAppleRangeAndTotalLength(
            statusCode = statusCode,
            contentRange = contentRange,
            declaredContentLength = validation.contentLength,
            bodyLength = bytes.size.toLong(),
            requestedRange = range,
        ) ?: return ApplePlaybackValidationOutcomeDto(
            accepted = false,
            contentLength = -1,
            supportsByteRanges = false,
            errorKind = "protocol",
            refreshReason = "ValidationFailed",
            responseShape = PlaybackErrorResponseShape.UnexpectedSuccessfulPayload.name,
            retryAfterMilliseconds = -1,
        )
        ApplePlaybackValidationOutcomeDto(
            accepted = true,
            contentLength = totalLength,
            supportsByteRanges = validation.supportsByteRanges,
            errorKind = null,
            refreshReason = null,
            responseShape = null,
            retryAfterMilliseconds = -1,
        )
    } catch (_: Throwable) {
        ApplePlaybackValidationOutcomeDto(
            accepted = false,
            contentLength = -1,
            supportsByteRanges = false,
            errorKind = "protocol",
            refreshReason = "ValidationFailed",
            responseShape = PlaybackErrorResponseShape.UnexpectedSuccessfulPayload.name,
            retryAfterMilliseconds = -1,
        )
    }

    public fun evaluateRedirect(
        sourceUrl: String,
        proposedUrl: String,
        redirectsAlreadyFollowed: Int,
    ): ApplePlaybackRedirectDecisionDto = try {
        when (
            val decision = AccountConnectionContract.redirectDecision(
                sourceUrl,
                proposedUrl,
                redirectsAlreadyFollowed,
            )
        ) {
            RedirectPolicyDecision.PreserveCredentials ->
                ApplePlaybackRedirectDecisionDto("preserve", emptyList())
            is RedirectPolicyDecision.Reject -> if (
                decision.reason == RedirectRejectionReason.CrossOrigin
            ) {
                ApplePlaybackRedirectDecisionDto("strip", listOf("u", "t", "s"))
            } else {
                ApplePlaybackRedirectDecisionDto("reject", emptyList())
            }
        }
    } catch (_: Throwable) {
        ApplePlaybackRedirectDecisionDto("reject", emptyList())
    }

    public fun close() {
        requestClient.close()
        wireClient.close()
    }
}

private class ApplePlaybackWireOperationImpl(
    scope: CoroutineScope,
    block: suspend () -> Unit,
) : ApplePlaybackWireOperation {
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) { block() }

    init {
        scope.launch { job.start() }
    }

    override fun cancel() {
        job.cancel()
    }
}

internal fun validateAppleRangeAndTotalLength(
    statusCode: Int,
    contentRange: String?,
    declaredContentLength: PlaybackContentLength?,
    bodyLength: Long,
    requestedRange: PlaybackByteRange,
): Long? {
    if (bodyLength <= 0) return null
    val requestedEnd = requestedRange.endInclusive ?: return null
    if (statusCode == 206) {
        val match = CONTENT_RANGE_PATTERN.matchEntire(contentRange?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (
            start != requestedRange.start ||
            end < start ||
            end > requestedEnd ||
            end - start + 1 != bodyLength ||
            total <= end
        ) {
            return null
        }
        return total
    }
    if (statusCode in 200..299 && requestedRange.start == 0L) {
        return when (declaredContentLength) {
            null -> bodyLength
            is PlaybackContentLength.Exact ->
                declaredContentLength.byteCount.takeIf { it == bodyLength }
            is PlaybackContentLength.Estimated -> bodyLength
        }
    }
    return null
}

private fun syntheticApplePlaybackTrace(): RequestTrace = RequestTrace.observed(
    endpoint = "playback-resource",
    method = "GET",
    redactedUrl = "<redacted-url>",
    authenticationLocation = AuthenticationLocation.Query,
    queryAuthenticationParameters = setOf(
        AuthenticationParameter.Username,
        AuthenticationParameter.SaltedToken,
        AuthenticationParameter.Salt,
    ),
    formAuthenticationParameters = emptySet(),
    channels = emptySet(),
    requestedProtocolVersion = AccountConnectionContract.protocolVersion,
    saltFingerprint = null,
)

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    if (length == 0UL) return ByteArray(0)
    val result = ByteArray(length.toInt())
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

private fun DomainError.applePlaybackErrorKind(): String = when (this) {
    DomainError.Transport.Cancelled -> "cancelled"
    DomainError.Transport.Timeout,
    DomainError.Transport.Unreachable,
    -> "transport"
    is DomainError.Security.TlsUntrusted -> "tlsUntrusted"
    is DomainError.Security -> "security"
    DomainError.Auth.InvalidCredentials -> "authentication"
    DomainError.Auth.Forbidden -> "forbidden"
    is DomainError.Auth -> "authentication"
    is DomainError.Server.Busy -> "serverBusy"
    is DomainError.Server -> "sourceUnavailable"
    is DomainError.Protocol -> "protocol"
    DomainError.Playback.NoPlayableSource -> "unsupportedPlan"
    is DomainError.Input.InvalidServerUrl -> "protocol"
    is DomainError.CapabilityUnsupported -> "unsupportedPlan"
}

private val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
