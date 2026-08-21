package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

internal actual fun createAccountHttpClient(
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(CIO) {
    configure()
}
