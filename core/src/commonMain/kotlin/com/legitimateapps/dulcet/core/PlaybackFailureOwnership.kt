package com.legitimateapps.dulcet.core

/**
 * Whose failure a playback failure is (spec §12.12). The queue moves past a failure that belongs to
 * the track and stops on one that belongs to the connection, the server or the account.
 */
public enum class PlaybackFailureOwner {
    /** This item cannot be played. The queue skips past it, within the §12.12 guard. */
    Track,

    /** The connection, the server or the account failed. The queue stops and presents it. */
    Connection,

    /** The request was withdrawn. It is neither presented nor skipped past (§12.12). */
    NotAFailure,
}

/**
 * The §12.12 classification: the error alone decides it, and the phase never does -- a failure
 * before start and a failure after partial playback of the same error are classified alike.
 *
 * Closed and exhaustive, with no `else`: a new [DomainError] case does not compile until someone
 * decides whose failure it is.
 */
public fun playbackFailureOwner(error: DomainError): PlaybackFailureOwner = when (error) {
    is DomainError.Input.InvalidServerUrl -> PlaybackFailureOwner.Connection

    DomainError.Transport.Unreachable -> PlaybackFailureOwner.Connection
    DomainError.Transport.Timeout -> PlaybackFailureOwner.Connection
    DomainError.Transport.Cancelled -> PlaybackFailureOwner.NotAFailure

    is DomainError.Security.TlsUntrusted -> PlaybackFailureOwner.Connection
    DomainError.Security.LocalExceptionViolated -> PlaybackFailureOwner.Connection
    is DomainError.Security.RedirectRejected -> PlaybackFailureOwner.Connection

    DomainError.Protocol.MalformedEnvelope -> PlaybackFailureOwner.Connection
    // The server answered for this item with the wrong kind of content.
    is DomainError.Protocol.UnexpectedContentType -> PlaybackFailureOwner.Track
    // The bytes served for this item are not the audio its container promises. A proxy page
    // served under an audio content type lands here too; the guard bounds that sweep (§12.12).
    DomainError.Protocol.UnexpectedBinary -> PlaybackFailureOwner.Track
    is DomainError.Protocol.Incompatible -> PlaybackFailureOwner.Connection
    DomainError.Protocol.NotASubsonicServer -> PlaybackFailureOwner.Connection

    // Already retried by PlaybackRetryPolicy; a capacity limit is the server's, not the item's.
    is DomainError.Server.Busy -> PlaybackFailureOwner.Connection
    is DomainError.Server.Known -> when (error.code) {
        // 70: the item was not found. 0: the reference server's answer to `stream` for an item
        // whose file is gone (spec §28, Revision 104 item 6). A server-wide generic failure also
        // answers 0; the guard is what bounds that sweep (§12.12).
        SUBSONIC_DATA_NOT_FOUND, SUBSONIC_GENERIC_ERROR -> PlaybackFailureOwner.Track
        else -> PlaybackFailureOwner.Connection
    }
    // A code this client does not know, or a bare HTTP status from whatever answered: nothing
    // ties it to this item, so it is presented rather than guessed at.
    is DomainError.Server.Unknown -> PlaybackFailureOwner.Connection
    // A bare HTTP status from the library's checked request path. Playback never produces it (it
    // names a bare status Unknown), and like Unknown nothing ties it to this item.
    is DomainError.Server.HttpStatus -> PlaybackFailureOwner.Connection

    DomainError.Auth.InvalidCredentials -> PlaybackFailureOwner.Connection
    DomainError.Auth.TokenAuthUnsupported -> PlaybackFailureOwner.Connection
    DomainError.Auth.Forbidden -> PlaybackFailureOwner.Connection
    DomainError.Auth.UnsupportedAuthenticationChallenge -> PlaybackFailureOwner.Connection
    is DomainError.Auth.CrossOriginRedirectRejected -> PlaybackFailureOwner.Connection

    DomainError.Playback.NoPlayableSource -> PlaybackFailureOwner.Track

    is DomainError.CapabilityUnsupported -> PlaybackFailureOwner.Connection
}

private const val SUBSONIC_GENERIC_ERROR = 0
private const val SUBSONIC_DATA_NOT_FOUND = 70
