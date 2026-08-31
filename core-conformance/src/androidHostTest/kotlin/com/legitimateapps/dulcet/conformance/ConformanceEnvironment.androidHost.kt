package com.legitimateapps.dulcet.conformance

internal actual fun environmentOrNull(name: String): String? =
    System.getenv(name)?.takeIf(String::isNotBlank)
