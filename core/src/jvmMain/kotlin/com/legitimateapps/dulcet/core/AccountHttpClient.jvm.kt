package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Authenticator
import okhttp3.CookieJar
import java.net.Proxy

internal actual fun createAccountHttpClient(
    transport: AccountClientTransport,
    strictMediaFraming: Boolean,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = run {
    require(transport is AccountClientTransport.Default) {
        "The injected forward-proxy transport is not supported on JVM"
    }
    HttpClient(OkHttp) {
        engine {
            // Every HTTPS request needs hostname validation, including numeric IP SANs.
            // Keep strict framing, no recovery retries, ambient authentication, cookies or redirects.
            config {
                retryOnConnectionFailure(false)
                followRedirects(false)
                followSslRedirects(false)
                authenticator(Authenticator.NONE)
                proxyAuthenticator(Authenticator.NONE)
                cookieJar(CookieJar.NO_COOKIES)
            }
            proxy = Proxy.NO_PROXY
        }
        configure()
    }
}
