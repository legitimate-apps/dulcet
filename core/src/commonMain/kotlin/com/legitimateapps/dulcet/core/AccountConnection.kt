package com.legitimateapps.dulcet.core

/** Inputs required to establish and negotiate one Subsonic account. */
public data class AccountConnectionRequest(
    val serverUrl: String,
    val username: String,
    val password: String,
)

/** Authentication placement observed at the transport boundary, without retaining credential values. */
public enum class AuthenticationLocation {
    None,
    FormBody,
    Query,
}

/** Redacted request evidence retained for diagnostics and conformance assertions. */
public data class RequestTrace(
    val endpoint: String,
    val method: String,
    val redactedUrl: String,
    val authenticationLocation: AuthenticationLocation,
    val requestedProtocolVersion: String?,
    val saltFingerprint: String?,
)

public data class UserPermissions(
    val download: Boolean,
    val playlist: Boolean,
    val share: Boolean,
    val jukebox: Boolean,
    val admin: Boolean,
)

public data class CapabilitySet(
    val extensions: Map<String, Set<Int>>,
    val permissions: UserPermissions,
    val legacySubsonic: Boolean,
)

public data class ConnectedAccount(
    val normalizedBaseUrl: String,
    val protocolVersion: String,
    val openSubsonic: Boolean,
    val serverType: String,
    val serverVersion: String,
    val capabilities: CapabilitySet,
    val requests: List<RequestTrace>,
)

public sealed interface AccountConnectionResult {
    public data class Connected(val account: ConnectedAccount) : AccountConnectionResult
    public data class Failed(val error: DomainError) : AccountConnectionResult
}

/** User-presentable failures. URL-bearing variants accept and retain redacted URLs only. */
public sealed interface DomainError {
    public sealed interface Transport : DomainError {
        public data object Unreachable : Transport
        public data object Timeout : Transport
        public data object Cancelled : Transport
    }

    public sealed interface Security : DomainError {
        public data class TlsUntrusted(val reason: String) : Security
        public data object LocalExceptionViolated : Security
    }

    public sealed interface Protocol : DomainError {
        public data object MalformedEnvelope : Protocol
        public data class Incompatible(
            val clientVersion: String,
            val serverVersion: String?,
        ) : Protocol
        public data object NotASubsonicServer : Protocol
    }

    public sealed interface Server : DomainError {
        public data class Known(
            val code: Int,
            val message: String,
            val redactedUrl: String,
        ) : Server

        public data class Unknown(
            val code: Int,
            val message: String,
            val redactedUrl: String,
        ) : Server
    }

    public sealed interface Auth : DomainError {
        public data object InvalidCredentials : Auth
        public data object Forbidden : Auth
        public data object RedirectCredentialLoss : Auth
    }

    public data class CapabilityUnsupported(val featureId: String) : DomainError
}

public fun interface LogSink {
    public fun write(message: String)
}

public fun interface SaltSource {
    public fun nextSalt(): String
}

/** Account-connect entry point. The red conformance commit deliberately has no behavior yet. */
public class AccountConnector(
    private val saltSource: SaltSource? = null,
    private val logSink: LogSink? = null,
) {
    public suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult {
        @Suppress("UNUSED_VARIABLE")
        val contractInputs = arrayOf(request, saltSource, logSink)
        return AccountConnectionResult.Failed(DomainError.CapabilityUnsupported("account.connect"))
    }
}

/** Contract declarations used by the red suite; production behavior lands only after red evidence. */
public object AccountConnectionContract {
    public const val protocolVersion: String = "1.16.1"

    public fun secureSaltSource(): SaltSource = SaltSource {
        error("account.connect secure salt generation is not implemented")
    }

    public fun saltedToken(password: String, salt: String): String {
        @Suppress("UNUSED_VARIABLE")
        val inputs = password to salt
        error("account.connect salted-token authentication is not implemented")
    }

    public fun mapSubsonicError(
        code: Int,
        message: String,
        requestUrl: String,
    ): DomainError = DomainError.CapabilityUnsupported("account.connect")
}
