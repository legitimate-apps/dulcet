package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Synchronous cancellation handle exported to Apple platform shells. */
public interface AppleAccountConnectOperation {
    /** Best-effort, idempotent cancellation of the in-flight account negotiation. */
    public fun cancel()
}

/** Flat completion payload; exactly one of [account] and [error] is non-null. */
public class AppleAccountConnectOutcome internal constructor(
    public val account: ConnectedAccount?,
    public val error: DomainError?,
    public val errorKind: AppleAccountErrorKind?,
    public val errorPresentation: AppleAccountErrorPresentation?,
)

/** Localizable presentation inputs derived exhaustively from the closed domain-error hierarchy. */
public class AppleAccountErrorPresentation internal constructor(
    public val kind: String,
    public val targetHost: String?,
    public val invalidServerURLIsInternationalized: Boolean,
)

/** Flat, closed discriminator for exhaustive Apple presentation mapping. */
public enum class AppleAccountErrorKind {
    InputInvalidServerUrl,
    TransportUnreachable,
    TransportTimeout,
    TransportCancelled,
    SecurityTlsUntrusted,
    SecurityLocalExceptionViolated,
    SecurityRedirectRejected,
    ProtocolMalformedEnvelope,
    ProtocolIncompatible,
    ProtocolNotASubsonicServer,
    ServerKnown,
    ServerUnknown,
    AuthInvalidCredentials,
    AuthTokenAuthUnsupported,
    AuthForbidden,
    AuthUnsupportedAuthenticationChallenge,
    AuthCrossOriginRedirectRejected,
    CapabilityUnsupported,
}

/**
 * Objective-C-interoperable account-connect facade.
 *
 * It returns the operation handle synchronously, delivers exactly one completion on the Apple main
 * dispatcher, and never lets a Kotlin exception cross the framework boundary.
 */
public class AppleAccountConnectionClient internal constructor(
    private val connector: AccountConnector,
) {
    /** Objective-C/Swift-visible production constructor. */
    public constructor() : this(AccountConnector())

    private val scope: CoroutineScope = MainScope()

    public fun startConnect(
        request: AccountConnectionRequest,
        completion: (AppleAccountConnectOutcome) -> Unit,
    ): AppleAccountConnectOperation {
        val operation = AppleAccountConnectOperationImpl(
            scope = scope,
            connector = connector,
            request = request,
            completion = completion,
        )
        operation.start()
        return operation
    }
}

private class AppleAccountConnectOperationImpl(
    private val scope: CoroutineScope,
    private val connector: AccountConnector,
    private val request: AccountConnectionRequest,
    private val completion: (AppleAccountConnectOutcome) -> Unit,
) : AppleAccountConnectOperation {
    private var delivered = false
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            connector.connect(request)
        } catch (_: CancellationException) {
            AccountConnectionResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: Throwable) {
            AccountConnectionResult.Failed(mapAccountConnectionFailure(failure))
        }
        deliver(result.toAppleOutcome())
    }.also { operationJob ->
        operationJob.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                scope.launch {
                    deliver(
                        AccountConnectionResult.Failed(DomainError.Transport.Cancelled)
                            .toAppleOutcome(),
                    )
                }
            }
        }
    }

    fun start() {
        // Schedule rather than entering the lazy coroutine inline: the caller receives this handle
        // before even an input-validation completion can be delivered.
        scope.launch { job.start() }
    }

    override fun cancel() {
        job.cancel()
    }

    private fun deliver(outcome: AppleAccountConnectOutcome) {
        if (delivered) return
        delivered = true
        completion(outcome)
    }
}

private fun AccountConnectionResult.toAppleOutcome(): AppleAccountConnectOutcome = when (this) {
    is AccountConnectionResult.Connected -> AppleAccountConnectOutcome(
        account = account,
        error = null,
        errorKind = null,
        errorPresentation = null,
    )
    is AccountConnectionResult.Failed -> AppleAccountConnectOutcome(
        account = null,
        error = error,
        errorKind = error.toAppleErrorKind(),
        errorPresentation = error.toAppleErrorPresentation(),
    )
}

private fun DomainError.toAppleErrorPresentation(): AppleAccountErrorPresentation = when (this) {
    is DomainError.Input.InvalidServerUrl -> AppleAccountErrorPresentation(
        kind = "invalidServerURL",
        targetHost = null,
        invalidServerURLIsInternationalized =
            reason == InvalidServerUrlReason.UnsupportedInternationalizedHost,
    )
    DomainError.Transport.Unreachable -> applePresentation("transportUnreachable")
    DomainError.Transport.Timeout -> applePresentation("transportTimeout")
    DomainError.Transport.Cancelled -> applePresentation("transportCancelled")
    is DomainError.Security.TlsUntrusted -> applePresentation("tlsUntrusted")
    DomainError.Security.LocalExceptionViolated -> applePresentation("localNetworkPolicyRejected")
    is DomainError.Security.RedirectRejected -> applePresentation("redirectRejected")
    DomainError.Protocol.MalformedEnvelope -> applePresentation("malformedEnvelope")
    is DomainError.Protocol.Incompatible -> applePresentation("incompatibleProtocol")
    DomainError.Protocol.NotASubsonicServer -> applePresentation("notASubsonicServer")
    is DomainError.Server.Known -> applePresentation("knownServerError")
    is DomainError.Server.Unknown -> applePresentation("unknownServerError")
    DomainError.Auth.InvalidCredentials -> applePresentation("invalidCredentials")
    DomainError.Auth.TokenAuthUnsupported -> applePresentation("tokenAuthenticationUnsupported")
    DomainError.Auth.Forbidden -> applePresentation("forbidden")
    DomainError.Auth.UnsupportedAuthenticationChallenge ->
        applePresentation("unsupportedAuthenticationChallenge")
    is DomainError.Auth.CrossOriginRedirectRejected -> AppleAccountErrorPresentation(
        kind = "crossOriginRedirectRejected",
        targetHost = targetHost.value,
        invalidServerURLIsInternationalized = false,
    )
    is DomainError.CapabilityUnsupported -> applePresentation("capabilityUnsupported")
}

private fun applePresentation(kind: String): AppleAccountErrorPresentation =
    AppleAccountErrorPresentation(
        kind = kind,
        targetHost = null,
        invalidServerURLIsInternationalized = false,
    )

private fun DomainError.toAppleErrorKind(): AppleAccountErrorKind = when (this) {
    is DomainError.Input.InvalidServerUrl -> AppleAccountErrorKind.InputInvalidServerUrl
    DomainError.Transport.Unreachable -> AppleAccountErrorKind.TransportUnreachable
    DomainError.Transport.Timeout -> AppleAccountErrorKind.TransportTimeout
    DomainError.Transport.Cancelled -> AppleAccountErrorKind.TransportCancelled
    is DomainError.Security.TlsUntrusted -> AppleAccountErrorKind.SecurityTlsUntrusted
    DomainError.Security.LocalExceptionViolated -> AppleAccountErrorKind.SecurityLocalExceptionViolated
    is DomainError.Security.RedirectRejected -> AppleAccountErrorKind.SecurityRedirectRejected
    DomainError.Protocol.MalformedEnvelope -> AppleAccountErrorKind.ProtocolMalformedEnvelope
    is DomainError.Protocol.Incompatible -> AppleAccountErrorKind.ProtocolIncompatible
    DomainError.Protocol.NotASubsonicServer -> AppleAccountErrorKind.ProtocolNotASubsonicServer
    is DomainError.Server.Known -> AppleAccountErrorKind.ServerKnown
    is DomainError.Server.Unknown -> AppleAccountErrorKind.ServerUnknown
    DomainError.Auth.InvalidCredentials -> AppleAccountErrorKind.AuthInvalidCredentials
    DomainError.Auth.TokenAuthUnsupported -> AppleAccountErrorKind.AuthTokenAuthUnsupported
    DomainError.Auth.Forbidden -> AppleAccountErrorKind.AuthForbidden
    DomainError.Auth.UnsupportedAuthenticationChallenge ->
        AppleAccountErrorKind.AuthUnsupportedAuthenticationChallenge
    is DomainError.Auth.CrossOriginRedirectRejected ->
        AppleAccountErrorKind.AuthCrossOriginRedirectRejected
    is DomainError.CapabilityUnsupported -> AppleAccountErrorKind.CapabilityUnsupported
}
