package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Synchronous cancellation handle for one durable Apple library import. */
public interface AppleLibrarySyncOperation {
    public fun cancel()
}

/** Credential-bearing input copied from Swift and never rendered or logged. */
public class AppleLibrarySyncRequest(
    public val providerInstanceId: String,
    public val normalizedBaseUrl: String,
    public val username: String,
    public val password: String,
    public val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "AppleLibrarySyncRequest(<redacted>)"
}

public class AppleLibrarySyncProgressDto internal constructor(
    public val stage: String,
    public val completedStageCount: Int,
    public val totalStageCount: Int,
    public val isFirstSync: Boolean,
)

public class AppleLibrarySyncSuccessDto internal constructor(
    public val generation: Long,
    public val stability: String,
    public val deletionNotices: LibraryDeletionNoticeList,
    public val libraryChangedDuringScan: Boolean,
)

public class AppleLibrarySyncErrorDto internal constructor(public val kind: String)

/** Exactly one of [success] and [error] is populated. */
public class AppleLibrarySyncOutcome internal constructor(
    public val success: AppleLibrarySyncSuccessDto?,
    public val error: AppleLibrarySyncErrorDto?,
)

/** Objective-C-compatible, constructible facade for `library.sync`. */
public class AppleLibrarySyncClient(
    private val databaseName: String = "dulcet.db",
    private val maximumInFlightPerServer: Int = 4,
) {
    private val scope: CoroutineScope = MainScope()

    public fun startSync(
        request: AppleLibrarySyncRequest,
        restart: Boolean,
        progress: (AppleLibrarySyncProgressDto) -> Unit,
        completion: (AppleLibrarySyncOutcome) -> Unit,
    ): AppleLibrarySyncOperation {
        val operation = AppleLibrarySyncOperationImpl(
            scope, databaseName, maximumInFlightPerServer, request, restart, progress, completion,
        )
        operation.start()
        return operation
    }
}

private class AppleLibrarySyncOperationImpl(
    private val scope: CoroutineScope,
    private val databaseName: String,
    private val maximumInFlightPerServer: Int,
    private val request: AppleLibrarySyncRequest,
    private val restart: Boolean,
    private val progress: (AppleLibrarySyncProgressDto) -> Unit,
    private val completion: (AppleLibrarySyncOutcome) -> Unit,
) : AppleLibrarySyncOperation {
    private var delivered = false
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
        var store: DulcetDatabaseStore? = null
        val response = try {
            require(databaseName.isNotBlank())
            store = DulcetDriverFactory(databaseName = databaseName).openDulcetDatabase()
            val repository = LibrarySyncRepository(store)
            LibrarySyncManager(
                LibrarySyncEngine(repository, maxInFlight = maximumInFlightPerServer),
                repository,
            ).synchronize(
                request = request.toCoreRequest(),
                restart = restart,
                progress = { update ->
                    try {
                        progress(update.toAppleDto())
                    } catch (_: Throwable) {
                        // Presentation callback failures do not escape into or abort core sync.
                    }
                },
            )
        } catch (_: CancellationException) {
            LibrarySyncResponse.Failed(DomainError.Transport.Cancelled)
        } catch (failure: Throwable) {
            LibrarySyncResponse.Failed(mapAccountConnectionFailure(failure))
        } finally {
            try {
                store?.close()
            } catch (_: Throwable) {
            }
        }
        deliver(response.toAppleOutcome())
    }.also { operationJob ->
        operationJob.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                scope.launch {
                    deliver(
                        LibrarySyncResponse.Failed(DomainError.Transport.Cancelled).toAppleOutcome(),
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

    private fun deliver(outcome: AppleLibrarySyncOutcome) {
        if (delivered) return
        delivered = true
        completion(outcome)
    }
}

private fun AppleLibrarySyncRequest.toCoreRequest() = LibrarySyncRequest(
    providerInstanceId,
    normalizedBaseUrl,
    username,
    password,
    allowLocalHttp,
)

private fun LibrarySyncProgress.toAppleDto() = AppleLibrarySyncProgressDto(
    stage, completedStageCount, totalStageCount, isFirstSync,
)

private fun LibrarySyncResponse.toAppleOutcome(): AppleLibrarySyncOutcome = when (this) {
    is LibrarySyncResponse.Completed -> AppleLibrarySyncOutcome(
        success = AppleLibrarySyncSuccessDto(
            generation,
            stability.name.lowercase(),
            deletionNotices,
            libraryChangedDuringScan,
        ),
        error = null,
    )
    is LibrarySyncResponse.Failed -> AppleLibrarySyncOutcome(
        success = null,
        error = AppleLibrarySyncErrorDto(error.appleSyncKind()),
    )
}

private fun DomainError.appleSyncKind(): String = when (this) {
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
    is DomainError.CapabilityUnsupported -> "unsupported"
}
