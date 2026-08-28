package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Synchronous cancellation handle for one Apple library read. */
public interface AppleLibraryBrowseOperation {
    public fun cancel()
}

/** Credential-bearing input copied from Swift and never rendered or logged. */
public class AppleLibraryBrowseRequest(
    public val providerInstanceId: String,
    public val normalizedBaseUrl: String,
    public val username: String,
    public val password: String,
    public val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "AppleLibraryBrowseRequest(<redacted>)"
}

public class AppleLibraryCreditDto internal constructor(
    public val role: String,
    public val name: String,
    public val providerInstanceId: String?,
    public val rawId: String?,
)

public class AppleLibraryMusicFolderDto internal constructor(
    public val providerInstanceId: String,
    public val rawId: String,
    public val name: String,
)

public class AppleLibraryArtistDto internal constructor(
    public val providerInstanceId: String,
    public val rawId: String,
    public val name: String,
    public val mediaSourceId: String?,
)

public class AppleLibraryTrackDto internal constructor(
    public val providerInstanceId: String,
    public val rawId: String,
    public val title: String,
    public val credits: List<AppleLibraryCreditDto>,
    public val albumTitle: String?,
    public val discNumber: Int?,
    public val trackNumber: Int?,
    public val durationMilliseconds: Long,
    public val sourceContainer: String?,
    public val mediaSourceId: String?,
    public val artworkKey: String?,
)

public class AppleLibraryAlbumDto internal constructor(
    public val providerInstanceId: String,
    public val rawId: String,
    public val title: String,
    public val credits: List<AppleLibraryCreditDto>,
    public val year: Int?,
    public val durationMilliseconds: Long,
    public val mediaSourceId: String?,
    public val artworkKey: String?,
    public val tracks: List<AppleLibraryTrackDto>,
)

public class AppleLibraryBrowseSnapshotDto internal constructor(
    public val musicFolders: List<AppleLibraryMusicFolderDto>,
    public val artists: List<AppleLibraryArtistDto>,
    public val albums: List<AppleLibraryAlbumDto>,
)

/** A closed presentation discriminator containing no server text or URL. */
public class AppleLibraryBrowseErrorDto internal constructor(public val kind: String)

/** Exactly one of [snapshot] and [error] is populated. */
public class AppleLibraryBrowseOutcome internal constructor(
    public val snapshot: AppleLibraryBrowseSnapshotDto?,
    public val error: AppleLibraryBrowseErrorDto?,
)

/** Objective-C-compatible completion-handler facade for the read-through library walk. */
public class AppleLibraryBrowseClient internal constructor(
    private val browser: LibraryBrowser,
) {
    public constructor() : this(LibraryBrowser())

    private val scope: CoroutineScope = MainScope()

    public fun startBrowse(
        request: AppleLibraryBrowseRequest,
        completion: (AppleLibraryBrowseOutcome) -> Unit,
    ): AppleLibraryBrowseOperation {
        val operation = AppleLibraryBrowseOperationImpl(scope, browser, request, completion)
        operation.start()
        return operation
    }
}

private class AppleLibraryBrowseOperationImpl(
    private val scope: CoroutineScope,
    private val browser: LibraryBrowser,
    private val request: AppleLibraryBrowseRequest,
    private val completion: (AppleLibraryBrowseOutcome) -> Unit,
) : AppleLibraryBrowseOperation {
    private var delivered = false
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            browser.browse(request.toCoreRequest())
        } catch (_: CancellationException) {
            LibraryBrowseResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: Throwable) {
            LibraryBrowseResult.Failed(mapAccountConnectionFailure(failure))
        }
        deliver(result.toAppleOutcome())
    }.also { operationJob ->
        operationJob.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                scope.launch {
                    deliver(
                        LibraryBrowseResult.Failed(DomainError.Transport.Cancelled).toAppleOutcome(),
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

    private fun deliver(outcome: AppleLibraryBrowseOutcome) {
        if (delivered) return
        delivered = true
        completion(outcome)
    }
}

private fun AppleLibraryBrowseRequest.toCoreRequest(): LibraryBrowseRequest = LibraryBrowseRequest(
    providerInstanceId = providerInstanceId,
    normalizedBaseUrl = normalizedBaseUrl,
    username = username,
    password = password,
    allowLocalHttp = allowLocalHttp,
)

private fun LibraryBrowseResult.toAppleOutcome(): AppleLibraryBrowseOutcome = when (this) {
    is LibraryBrowseResult.Loaded -> AppleLibraryBrowseOutcome(
        snapshot = snapshot.toAppleDto(),
        error = null,
    )
    is LibraryBrowseResult.Failed -> AppleLibraryBrowseOutcome(
        snapshot = null,
        error = AppleLibraryBrowseErrorDto(error.appleLibraryKind()),
    )
}

private fun LibraryBrowseSnapshot.toAppleDto(): AppleLibraryBrowseSnapshotDto =
    AppleLibraryBrowseSnapshotDto(
        musicFolders = musicFolders.map { folder ->
            AppleLibraryMusicFolderDto(
                folder.id.providerInstanceId,
                folder.id.rawId,
                folder.name,
            )
        },
        artists = artists.map { artist ->
            AppleLibraryArtistDto(
                artist.id.providerInstanceId,
                artist.id.rawId,
                artist.name,
                artist.mediaSourceId,
            )
        },
        albums = albums.map(LibraryAlbum::toAppleDto),
    )

private fun LibraryAlbum.toAppleDto(): AppleLibraryAlbumDto = AppleLibraryAlbumDto(
    providerInstanceId = id.providerInstanceId,
    rawId = id.rawId,
    title = title,
    credits = credits.map(Credit::toAppleDto),
    year = year,
    durationMilliseconds = duration.inWholeMilliseconds,
    mediaSourceId = mediaSourceId,
    artworkKey = artworkKey,
    tracks = tracks.map { track ->
        AppleLibraryTrackDto(
            providerInstanceId = track.id.providerInstanceId,
            rawId = track.id.rawId,
            title = track.title,
            credits = track.credits.map(Credit::toAppleDto),
            albumTitle = track.albumTitle,
            discNumber = track.discNumber,
            trackNumber = track.trackNumber,
            durationMilliseconds = track.duration.inWholeMilliseconds,
            sourceContainer = track.sourceContainer?.name,
            mediaSourceId = track.mediaSourceId,
            artworkKey = track.artworkKey,
        )
    },
)

private fun Credit.toAppleDto(): AppleLibraryCreditDto = AppleLibraryCreditDto(
    role = role.name,
    name = name,
    providerInstanceId = id?.providerInstanceId,
    rawId = id?.rawId,
)

private fun DomainError.appleLibraryKind(): String = when (this) {
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
