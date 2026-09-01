package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.http.Url
import io.ktor.http.HttpStatusCode
import io.ktor.client.plugins.api.createClientPlugin

internal actual fun createAccountHttpClient(
    transport: AccountClientTransport,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(CIO) {
    engine {
        if (transport is AccountClientTransport.ForwardProxy) {
            // ProxyBuilder.http takes a Url on this Ktor version, not a String.
            proxy = ProxyBuilder.http(
                Url("http://${transport.proxy.host}:${transport.proxy.port}/"),
            )
        }
    }
    // Mark a proxy challenge the way the Darwin delegate does. Without this the tracker is never
    // set on Android, `consumeUnsupported()` stays false, and a 407 falls through as a transport
    // failure -- OBSERVED via CONF-10c, which saw Transport.Unreachable where Darwin reports
    // Auth.UnsupportedAuthenticationChallenge. That mistranslation is user-visible and wrong: it
    // tells someone their server is unreachable when a proxy actually demanded credentials they
    // were never asked for.
    //
    // Ktor's CIO engine surfaces the challenge as an ordinary 407 response rather than a delegate
    // callback, so the hook is a response observer instead. Nothing here answers the challenge:
    // marking it is what makes the request fail CLOSED with a typed error.
    install(createClientPlugin("DulcetProxyChallengeMarker") {
        onResponse { response ->
            if (response.status == HttpStatusCode.ProxyAuthenticationRequired) {
                transport.challengeTracker.markUnsupported()
            }
        }
    })
    configure()
}

/**
 * Explicit forward-proxy connector used by the hosted Android wire conformance control.
 *
 * Production account setup does not discover or attach proxy credentials. This seam selects one
 * deterministic loopback proxy without installing Ktor Auth or a Proxy-Authorization header, so
 * the control exercises the same CIO account client while a JVM ambient Authenticator is present.
 */
public class AndroidForwardProxyAccountConnector(
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
