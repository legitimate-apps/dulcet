package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/** Exactly one of [tracks] and [error] is populated. */
public class AppleAutoplayOutcome internal constructor(
    public val tracks: List<AppleLibraryTrackDto>?,
    public val error: AppleLibraryBrowseErrorDto?,
)

/** Objective-C-compatible completion-handler facade for AutoPlay's track search (§14.8). */
public class AppleAutoplayClient(private val databaseName: String) {
    public constructor() : this("dulcet.db")
    private val scope: CoroutineScope = MainScope()

    public fun startCandidates(
        request: AppleLibraryBrowseRequest,
        seedRawId: String,
        excludedRawIds: List<String>,
        count: Int,
        completion: (AppleAutoplayOutcome) -> Unit,
    ): AppleLibraryBrowseOperation {
        val authorization = AppleLibraryReaderIdentityAuthorization.capture(databaseName, request.providerInstanceId)
        val operation = AppleLibraryOperationImpl(
            scope = scope,
            completion = completion,
            cancelledOutcome = { failed(DomainError.Transport.Cancelled) },
            failureOutcome = { failure -> failed(failure.asLibraryError()) },
        ) {
            val coreRequest = request.toCoreRequest()
            val transport = KtorLibraryEndpointTransport(
                coreRequest,
                saltSource = null,
                logSink = null,
                hostResolver = systemHostResolver(),
                operationName = "playback.autoplay",
            )
            try {
                val tracks = AutoplayCandidateSource(coreRequest.providerInstanceId, transport) {
                    if (authorization.currentToken() == null) throw AuthenticatedEndpointFailure(DomainError.Protocol.ServerIdentityChanged)
                }
                    .candidates(seedRawId, excludedRawIds.toSet(), count)
                AppleAutoplayOutcome(tracks.map(LibraryTrack::toAppleDto), null)
            } finally {
                transport.close()
            }
        }
        operation.start()
        return operation
    }

    private fun failed(error: DomainError) = AppleAutoplayOutcome(null, AppleLibraryBrowseErrorDto(error.appleLibraryKind()))
}
