package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Synchronous cancellation handle for one Apple server-search page. */
public interface AppleSearchOperation {
    public fun cancel()
}

/** Credential-bearing search input copied from Swift and never rendered or logged. */
public class AppleSearchPageRequest(
    public val providerInstanceId: String,
    public val normalizedBaseUrl: String,
    public val username: String,
    public val password: String,
    public val allowLocalHttp: Boolean,
    public val query: String,
    public val artistCount: Int,
    public val artistOffset: Int,
    public val albumCount: Int,
    public val albumOffset: Int,
    public val trackCount: Int,
    public val trackOffset: Int,
) {
    override fun toString(): String = "AppleSearchPageRequest(<redacted>)"
}

public class AppleSearchCreditDto internal constructor(
    public val role: String,
    public val name: String,
    public val providerInstanceId: String?,
    public val rawId: String?,
)

public class AppleSearchResultItemDto internal constructor(
    public val providerInstanceId: String,
    public val rawId: String,
    public val type: String,
    public val title: String,
    public val credits: List<AppleSearchCreditDto>,
    public val albumTitle: String?,
    public val year: Int?,
    public val durationMilliseconds: Long?,
    public val mediaSourceId: String?,
    public val artworkKey: String?,
)

public class AppleSearchPageDto internal constructor(
    public val results: List<AppleSearchResultItemDto>,
    public val artistResultCount: Int,
    public val albumResultCount: Int,
    public val trackResultCount: Int,
    public val artistHasMore: Boolean,
    public val albumHasMore: Boolean,
    public val trackHasMore: Boolean,
)

/** A closed presentation discriminator containing no server text or URL. */
public class AppleSearchErrorDto internal constructor(public val kind: String)

/** Exactly one of [page] and [error] is populated. */
public class AppleSearchOutcome internal constructor(
    public val page: AppleSearchPageDto?,
    public val error: AppleSearchErrorDto?,
)

/** Objective-C-compatible completion-handler facade for one read-through search page. */
public class AppleSearchClient internal constructor(
    private val search: ServerSearch,
) {
    public constructor() : this(ServerSearch())

    private val scope: CoroutineScope = MainScope()

    public fun startSearch(
        request: AppleSearchPageRequest,
        completion: (AppleSearchOutcome) -> Unit,
    ): AppleSearchOperation {
        val operation = AppleSearchOperationImpl(scope, search, request, completion)
        operation.start()
        return operation
    }
}

private class AppleSearchOperationImpl(
    private val scope: CoroutineScope,
    private val search: ServerSearch,
    private val request: AppleSearchPageRequest,
    private val completion: (AppleSearchOutcome) -> Unit,
) : AppleSearchOperation {
    private var delivered = false
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            search.search(request.toCoreRequest())
        } catch (_: CancellationException) {
            SearchPageResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: Throwable) {
            SearchPageResult.Failed(mapAccountConnectionFailure(failure))
        }
        deliver(result.toAppleOutcome())
    }.also { operationJob ->
        operationJob.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                scope.launch {
                    deliver(SearchPageResult.Failed(DomainError.Transport.Cancelled).toAppleOutcome())
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

    private fun deliver(outcome: AppleSearchOutcome) {
        if (delivered) return
        delivered = true
        completion(outcome)
    }
}

private fun AppleSearchPageRequest.toCoreRequest(): SearchPageRequest = SearchPageRequest(
    providerInstanceId = providerInstanceId,
    normalizedBaseUrl = normalizedBaseUrl,
    username = username,
    password = password,
    allowLocalHttp = allowLocalHttp,
    query = query,
    artistCount = artistCount,
    artistOffset = artistOffset,
    albumCount = albumCount,
    albumOffset = albumOffset,
    trackCount = trackCount,
    trackOffset = trackOffset,
)

private fun SearchPageResult.toAppleOutcome(): AppleSearchOutcome = when (this) {
    is SearchPageResult.Loaded -> AppleSearchOutcome(page.toAppleDto(), null)
    is SearchPageResult.Failed -> AppleSearchOutcome(null, AppleSearchErrorDto(error.appleSearchKind()))
}

private fun SearchPage.toAppleDto(): AppleSearchPageDto = AppleSearchPageDto(
    results = results.map { result ->
        AppleSearchResultItemDto(
            providerInstanceId = result.id.providerInstanceId,
            rawId = result.id.rawId,
            type = result.type.name,
            title = result.title,
            credits = result.credits.map { credit ->
                AppleSearchCreditDto(
                    role = credit.role.name,
                    name = credit.name,
                    providerInstanceId = credit.id?.providerInstanceId,
                    rawId = credit.id?.rawId,
                )
            },
            albumTitle = result.albumTitle,
            year = result.year,
            durationMilliseconds = result.duration?.inWholeMilliseconds,
            mediaSourceId = result.mediaSourceId,
            artworkKey = result.artworkKey,
        )
    },
    artistResultCount = artistResultCount,
    albumResultCount = albumResultCount,
    trackResultCount = trackResultCount,
    artistHasMore = artistHasMore,
    albumHasMore = albumHasMore,
    trackHasMore = trackHasMore,
)

private fun DomainError.appleSearchKind(): String = when (this) {
    DomainError.Transport.Cancelled -> "cancelled"
    DomainError.Transport.Timeout -> "timeout"
    DomainError.Transport.Unreachable -> "unreachable"
    is DomainError.Security.TlsUntrusted -> "tlsUntrusted"
    is DomainError.Security -> "security"
    is DomainError.Auth -> "authentication"
    is DomainError.Protocol -> "protocol"
    is DomainError.Server -> "server"
    is DomainError.Playback -> "playback"
    is DomainError.Input.InvalidServerUrl -> "input"
    is DomainError.CapabilityUnsupported -> "capability"
}
