package com.legitimateapps.dulcet

import android.content.res.Resources
import androidx.annotation.StringRes
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.InvalidServerUrlReason
import com.legitimateapps.dulcet.shared.R

/**
 * What every Android shell says for an account-connect failure (CONF-09c): a title, what happened,
 * and what to do. The TLS, internationalized-host and cross-origin-redirect remedies keep their
 * decided specifics. One closed mapping, so the phone and the TV cannot say different things.
 */
public data class AccountFailurePresentation(
    @param:StringRes public val title: Int,
    @param:StringRes public val message: Int,
    @param:StringRes public val recovery: Int,
    public val messageArgument: String? = null,
)

/** The presentation as one statement: the title, then what happened and what to do. */
public fun AccountFailurePresentation.statement(resources: Resources): String {
    val said = messageArgument?.let { resources.getString(message, it) } ?: resources.getString(message)
    return resources.getString(title) + "\n" + said + " " + resources.getString(recovery)
}

public fun DomainError.accountFailurePresentation(): AccountFailurePresentation = when (this) {
    is DomainError.Input.InvalidServerUrl -> if (
        reason == InvalidServerUrlReason.UnsupportedInternationalizedHost
    ) {
        AccountFailurePresentation(
            R.string.error_internationalized_url_title,
            R.string.error_internationalized_url_message,
            R.string.error_internationalized_url_recovery,
        )
    } else AccountFailurePresentation(
        R.string.error_invalid_url_title,
        R.string.error_invalid_url_message,
        R.string.error_invalid_url_recovery,
    )
    DomainError.Transport.Unreachable -> AccountFailurePresentation(
        R.string.error_unreachable_title,
        R.string.error_unreachable_message,
        R.string.error_unreachable_recovery,
    )
    DomainError.Transport.Timeout -> AccountFailurePresentation(
        R.string.error_timeout_title,
        R.string.error_timeout_message,
        R.string.error_timeout_recovery,
    )
    DomainError.Transport.Cancelled -> AccountFailurePresentation(
        R.string.error_cancelled_title,
        R.string.error_cancelled_message,
        R.string.error_cancelled_recovery,
    )
    is DomainError.Security.TlsUntrusted -> AccountFailurePresentation(
        R.string.error_tls_title,
        R.string.error_tls_message,
        R.string.error_tls_recovery,
    )
    DomainError.Security.LocalExceptionViolated,
    is DomainError.Security.RedirectRejected,
    -> AccountFailurePresentation(
        R.string.error_security_title,
        R.string.error_security_message,
        R.string.error_security_recovery,
    )
    DomainError.Protocol.MalformedEnvelope,
    is DomainError.Protocol.UnexpectedContentType,
    DomainError.Protocol.UnexpectedBinary,
    is DomainError.Protocol.Incompatible,
    DomainError.Protocol.NotASubsonicServer,
    DomainError.Protocol.TooLarge,
    -> AccountFailurePresentation(
        R.string.error_protocol_title,
        R.string.error_protocol_message,
        R.string.error_protocol_recovery,
    )
    is DomainError.Server.Busy,
    is DomainError.Server.Known,
    is DomainError.Server.Unknown,
    is DomainError.Server.HttpStatus,
    DomainError.Playback.NoPlayableSource,
    -> AccountFailurePresentation(
        R.string.error_server_title,
        R.string.error_server_message,
        R.string.error_server_recovery,
    )
    DomainError.Auth.InvalidCredentials,
    DomainError.Auth.TokenAuthUnsupported,
    DomainError.Auth.Forbidden,
    DomainError.Auth.UnsupportedAuthenticationChallenge,
    -> AccountFailurePresentation(
        R.string.error_auth_title,
        R.string.error_auth_message,
        R.string.error_auth_recovery,
    )
    is DomainError.Auth.CrossOriginRedirectRejected -> AccountFailurePresentation(
        R.string.error_cross_origin_title,
        R.string.error_cross_origin_message,
        R.string.error_cross_origin_recovery,
        messageArgument = targetHost.value,
    )
    is DomainError.CapabilityUnsupported -> AccountFailurePresentation(
        R.string.error_capability_title,
        R.string.error_capability_message,
        R.string.error_capability_recovery,
    )
}
