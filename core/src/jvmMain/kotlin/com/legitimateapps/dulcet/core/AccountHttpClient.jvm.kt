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
                // OkHttp otherwise retries 503 + Retry-After: 0 even with connection retries
                // disabled, and consumes a direct 407 as an impossible proxy challenge. Shared
                // status/retry policy owns both. The typed per-call tag keeps the original status
                // out of headers and restores it before Ktor can observe the response.
                addInterceptor { chain ->
                    val status = ExplicitStatusPolicy()
                    val request = chain.request().newBuilder().tag(ExplicitStatusPolicy::class.java, status).build()
                    val response = chain.proceed(request)
                    status.original?.let { response.newBuilder().code(it).build() } ?: response
                }
                addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code == 503 || response.code == 407) {
                        val status = requireNotNull(chain.request().tag(ExplicitStatusPolicy::class.java))
                        status.original = response.code
                        response.newBuilder().code(599).build()
                    } else response
                }
            }
            proxy = Proxy.NO_PROXY
        }
        configure()
    }
}

private class ExplicitStatusPolicy(var original: Int? = null)
