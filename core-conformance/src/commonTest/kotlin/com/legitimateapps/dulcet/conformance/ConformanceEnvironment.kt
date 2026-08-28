package com.legitimateapps.dulcet.conformance

internal expect fun environmentOrNull(name: String): String?

internal fun requiredEnvironment(name: String): String =
    environmentOrNull(name)
        ?: error("required conformance environment variable is absent: $name")

internal fun disposableConformanceBaseUrl(): String {
    check(environmentOrNull("DULCET_CONFORMANCE_DISPOSABLE") == "true") {
        "conformance suite requires an explicitly declared disposable instance"
    }
    val baseUrl = requiredEnvironment("DULCET_CONFORMANCE_BASE_URL")
    val match = Regex("http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})").matchEntire(baseUrl)
        ?: error("conformance suite is restricted to a loopback HTTP server")
    val port = match.groupValues[1].toInt()
    check(port in 1..65535) { "conformance server port is outside the valid range" }
    return baseUrl
}
