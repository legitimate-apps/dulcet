package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO

internal actual fun createAccountHttpClient(
    transport: AccountClientTransport,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(CIO) {
    engine {
        if (transport is AccountClientTransport.ForwardProxy) {
            proxy = ProxyBuilder.http(
                "http://${transport.proxy.host}:${transport.proxy.port}/",
            )
        }
    }
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
