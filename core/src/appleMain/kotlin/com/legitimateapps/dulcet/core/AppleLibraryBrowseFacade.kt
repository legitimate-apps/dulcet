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

/**
 * [trackCount] is what the server declares for the album, so the grid can draw "N tracks" without
 * a track list. [tracksLoaded] separates "this album has no tracks" from "nobody has read this
 * album's tracks yet" — [tracks] is empty in both cases.
 */
public class AppleLibraryAlbumDto internal constructor(
    public val providerInstanceId: String,
    public val rawId: String,
    public val title: String,
    public val credits: List<AppleLibraryCreditDto>,
    public val year: Int?,
    public val durationMilliseconds: Long,
    public val mediaSourceId: String?,
    public val artworkKey: String?,
    public val trackCount: Int,
    public val tracksLoaded: Boolean,
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

/** Exactly one of [album] and [error] is populated. */
public class AppleLibraryAlbumTracksOutcome internal constructor(
    public val album: AppleLibraryAlbumDto?,
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
        val operation = AppleLibraryOperationImpl(scope, completion) {
            browser.browse(request.toCoreRequest()).toAppleOutcome()
        }
        operation.start()
        return operation
    }

    /** Reads one album's track list. One request, under the same deadline as a first paint. */
    public fun startAlbumTracks(
        request: AppleLibraryBrowseRequest,
        albumRawId: String,
        completion: (AppleLibraryAlbumTracksOutcome) -> Unit,
    ): AppleLibraryBrowseOperation {
        val operation = AppleLibraryOperationImpl(scope, completion) {
            browser.albumTracks(request.toCoreRequest(), albumRawId).toAppleOutcome()
        }
        operation.start()
        return operation
    }
}

private class AppleLibraryOperationImpl<T>(
    private val scope: CoroutineScope,
    private val completion: (T) -> Unit,
    private val cancelledOutcome: () -> T,
    private val failureOutcome: (Throwable) -> T,
    private val work: suspend () -> T,
) : AppleLibraryBrowseOperation {
    private var delivered = false
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
        val outcome = try {
            work()
        } catch (_: CancellationException) {
            cancelledOutcome()
        } catch (failure: Throwable) {
            failureOutcome(failure)
        }
        deliver(outcome)
    }.also { operationJob ->
        operationJob.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                scope.launch { deliver(cancelledOutcome()) }
            }
        }
    }

    fun start() {
        scope.launch { job.start() }
    }

    override fun cancel() {
        job.cancel()
    }

    private fun deliver(outcome: T) {
        if (delivered) return
        delivered = true
        completion(outcome)
    }
}

private fun AppleLibraryOperationImpl(
    scope: CoroutineScope,
    completion: (AppleLibraryBrowseOutcome) -> Unit,
    work: suspend () -> AppleLibraryBrowseOutcome,
) = AppleLibraryOperationImpl(
    scope = scope,
    completion = completion,
    cancelledOutcome = {
        LibraryBrowseResult.Failed(DomainError.Transport.Cancelled).toAppleOutcome()
    },
    failureOutcome = { failure ->
        LibraryBrowseResult.Failed(mapAccountConnectionFailure(failure)).toAppleOutcome()
    },
    work = work,
)

private fun AppleLibraryOperationImpl(
    scope: CoroutineScope,
    completion: (AppleLibraryAlbumTracksOutcome) -> Unit,
    work: suspend () -> AppleLibraryAlbumTracksOutcome,
) = AppleLibraryOperationImpl(
    scope = scope,
    completion = completion,
    cancelledOutcome = {
        LibraryAlbumTracksResult.Failed(DomainError.Transport.Cancelled).toAppleOutcome()
    },
    failureOutcome = { failure ->
        LibraryAlbumTracksResult.Failed(mapAccountConnectionFailure(failure)).toAppleOutcome()
    },
    work = work,
)

private fun AppleLibraryBrowseRequest.toCoreRequest(): LibraryBrowseRequest = LibraryBrowseRequest(
    providerInstanceId = providerInstanceId,
    normalizedBaseUrl = normalizedBaseUrl,
    username = username,
    password = password,
    allowLocalHttp = allowLocalHttp,
)

private fun LibraryAlbumTracksResult.toAppleOutcome(): AppleLibraryAlbumTracksOutcome = when (this) {
    is LibraryAlbumTracksResult.Loaded -> AppleLibraryAlbumTracksOutcome(
        album = album.toAppleDto(),
        error = null,
    )
    is LibraryAlbumTracksResult.Failed -> AppleLibraryAlbumTracksOutcome(
        album = null,
        error = AppleLibraryBrowseErrorDto(error.appleLibraryKind()),
    )
}

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

internal fun LibraryBrowseSnapshot.toAppleDto(): AppleLibraryBrowseSnapshotDto =
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
    trackCount = trackCount,
    tracksLoaded = tracksLoaded,
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
