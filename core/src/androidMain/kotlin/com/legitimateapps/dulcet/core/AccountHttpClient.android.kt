package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.http.Url
import io.ktor.http.HttpStatusCode
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
}.also { client ->
    if (transport is AccountClientTransport.ForwardProxy) {
        // An HTTPS request through the proxy starts with a CONNECT, and CIO turns a 407 to that
        // CONNECT into `IOException("Can not establish tunnel connection")` with no status, so the
        // response observer above never sees it — and the same message covers a proxy that is down.
        // After such a failure, ask the proxy the same CONNECT once, with no credentials, and read its
        // status line: only a proxy that answers 407 is marked, and a dead one stays unreachable.
        client.plugin(HttpSend).intercept { request ->
            try {
                execute(request)
            } catch (failure: Throwable) {
                if (failure !is CancellationException && request.url.protocol == URLProtocol.HTTPS &&
                    proxyDemandsAuthenticationForTunnel(transport.proxy, request.url.host, request.url.port)
                ) {
                    transport.challengeTracker.markUnsupported()
                }
                throw failure
            }
        }
    }
}

/**
 * Whether [proxy] answers a credential-free `CONNECT host:port` with 407. Any failure to ask — a proxy
 * that is down, slow or speaks something else — is false: only an observed 407 is a challenge.
 */
internal suspend fun proxyDemandsAuthenticationForTunnel(proxy: AccountForwardProxy, host: String, port: Int): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.connect(InetSocketAddress(proxy.host, proxy.port), PROXY_PROBE_TIMEOUT_MILLIS)
                socket.soTimeout = PROXY_PROBE_TIMEOUT_MILLIS
                val authority = "$host:$port"
                socket.getOutputStream().apply {
                    write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val statusLine = StringBuilder()
                val input = socket.getInputStream()
                while (statusLine.length < MAX_STATUS_LINE) {
                    val byte = input.read()
                    if (byte < 0 || byte == '\n'.code) break
                    statusLine.append(byte.toChar())
                }
                statusLine.split(' ').getOrNull(1)?.trim() == "407"
            }
        } catch (_: IOException) {
            false
        }
    }

private const val PROXY_PROBE_TIMEOUT_MILLIS = 5_000
private const val MAX_STATUS_LINE = 256

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
