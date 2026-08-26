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
import platform.Foundation.NSMutableData
import platform.posix.memcpy

/** Synchronous cancellation handle for one Apple artwork request. */
public interface AppleArtworkFetchOperation {
    public fun cancel()
}

/** Credential-bearing input copied from Swift and never rendered or logged. */
public class AppleArtworkFetchRequest(
    public val providerInstanceId: String,
    public val artworkKey: String,
    public val sizeBucketPixels: Int,
    public val normalizedBaseUrl: String,
    public val username: String,
    public val password: String,
    public val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "AppleArtworkFetchRequest(<redacted>)"
}

/** A closed presentation discriminator containing no server text or URL. */
public class AppleArtworkFetchErrorDto internal constructor(public val kind: String)

/** Exactly one of [data] and [error] is populated; both null means no artwork is available. */
public class AppleArtworkFetchOutcome internal constructor(
    public val data: NSData?,
    public val error: AppleArtworkFetchErrorDto?,
)

/** Objective-C-compatible completion-handler facade for one cover-art request. */
public class AppleArtworkFetchClient internal constructor(
    private val fetcher: ArtworkFetcher,
) {
    public constructor() : this(ArtworkFetcher())

    private val scope: CoroutineScope = MainScope()

    public fun startFetch(
        request: AppleArtworkFetchRequest,
        completion: (AppleArtworkFetchOutcome) -> Unit,
    ): AppleArtworkFetchOperation {
        val operation = AppleArtworkFetchOperationImpl(scope, fetcher, request, completion)
        operation.start()
        return operation
    }
}

private class AppleArtworkFetchOperationImpl(
    private val scope: CoroutineScope,
    private val fetcher: ArtworkFetcher,
    private val request: AppleArtworkFetchRequest,
    private val completion: (AppleArtworkFetchOutcome) -> Unit,
) : AppleArtworkFetchOperation {
    private var delivered = false
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            request.toCoreRequest()?.let { fetcher.fetch(it) }
                ?: ArtworkFetchResult.Failed(DomainError.Protocol.MalformedEnvelope)
        } catch (_: CancellationException) {
            ArtworkFetchResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: Throwable) {
            ArtworkFetchResult.Failed(mapAccountConnectionFailure(failure))
        }
        deliver(result.toAppleOutcome())
    }.also { operationJob ->
        operationJob.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                scope.launch {
                    deliver(
                        ArtworkFetchResult.Failed(DomainError.Transport.Cancelled).toAppleOutcome(),
                    )
                }
            }
        }
    }

    fun start() {
        scope.launch { job.start() }
    }

    override fun cancel() {
        job.cancel()
    }

    private fun deliver(outcome: AppleArtworkFetchOutcome) {
        if (delivered) return
        delivered = true
        completion(outcome)
    }
}

private fun AppleArtworkFetchRequest.toCoreRequest(): ArtworkFetchRequest? {
    val bucket = ArtworkSizeBucket.entries.singleOrNull { it.pixels == sizeBucketPixels }
        ?: return null
    return ArtworkFetchRequest(
        providerInstanceId = providerInstanceId,
        artworkKey = artworkKey,
        sizeBucket = bucket,
        normalizedBaseUrl = normalizedBaseUrl,
        username = username,
        password = password,
        allowLocalHttp = allowLocalHttp,
    )
}

private fun ArtworkFetchResult.toAppleOutcome(): AppleArtworkFetchOutcome = when (this) {
    is ArtworkFetchResult.Loaded -> AppleArtworkFetchOutcome(
        data = bytes.toNSData(),
        error = null,
    )
    ArtworkFetchResult.Unavailable -> AppleArtworkFetchOutcome(data = null, error = null)
    is ArtworkFetchResult.Failed -> AppleArtworkFetchOutcome(
        data = null,
        error = AppleArtworkFetchErrorDto(error.appleArtworkKind()),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    val data = NSMutableData()
    data.setLength(size.toULong())
    memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong())
    data
}

private fun DomainError.appleArtworkKind(): String = when (this) {
    DomainError.Transport.Cancelled -> "cancelled"
    DomainError.Transport.Timeout -> "timeout"
    DomainError.Transport.Unreachable -> "unreachable"
    is DomainError.Security.TlsUntrusted -> "tlsUntrusted"
    is DomainError.Security -> "security"
    is DomainError.Auth -> "authentication"
    is DomainError.Protocol -> "protocol"
    is DomainError.Server -> "server"
    is DomainError.Input.InvalidServerUrl -> "input"
    is DomainError.CapabilityUnsupported -> "capability"
}
