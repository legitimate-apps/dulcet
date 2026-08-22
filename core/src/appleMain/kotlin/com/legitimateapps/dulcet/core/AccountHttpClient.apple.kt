package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun createAccountHttpClient(
    transport: AccountClientTransport,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            setURLCredentialStorage(null)
            if (transport is AccountClientTransport.ForwardProxy) {
                val proxy = transport.proxy
                setConnectionProxyDictionary(
                    mapOf(
                        "HTTPEnable" to 1,
                        "HTTPProxy" to proxy.host,
                        "HTTPPort" to proxy.port,
                        "HTTPSEnable" to 1,
                        "HTTPSProxy" to proxy.host,
                        "HTTPSPort" to proxy.port,
                    ),
                )
            }
        }
        handleChallenge { _, _, challenge, completionHandler ->
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                completionHandler(
                    NSURLSessionAuthChallengePerformDefaultHandling,
                    challenge.proposedCredential,
                )
            } else {
                throw UnsupportedAuthenticationChallengeFailure()
            }
        }
    }
    configure()
}

/** Content-free marker retained by Ktor's Darwin task handler when it cancels the challenge. */
internal class UnsupportedAuthenticationChallengeFailure : Exception()

/**
 * Explicit forward-proxy connector used by the hosted Darwin wire conformance control.
 *
 * Production account setup continues to use the system URL-session proxy configuration. This seam
 * selects one deterministic loopback proxy without adding proxy credentials or changing challenge
 * handling, so the test exercises the same fail-closed delegate as production.
 */
public class DarwinForwardProxyAccountConnector(
    proxyHost: String,
    proxyPort: Int,
    saltSource: SaltSource? = null,
) {
    private val connector: AccountConnector

    init {
        require(proxyHost.isNotBlank() && proxyHost.none(Char::isWhitespace)) {
            "proxyHost must be a nonblank host without whitespace"
        }
        require(proxyPort in 1..65535) { "proxyPort must be in 1..65535" }
        connector = AccountConnector(
            forwardProxy = AccountForwardProxy(proxyHost, proxyPort),
            saltSource = saltSource,
        )
    }

    public suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult =
        connector.connect(request)
}
