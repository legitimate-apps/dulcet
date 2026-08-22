package com.legitimateapps.dulcet.conformance

internal actual fun requiredEnvironment(name: String): String =
    System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("required conformance environment variable is absent: $name")
