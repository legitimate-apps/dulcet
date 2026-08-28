package com.legitimateapps.dulcet.conformance

internal expect fun environmentOrNull(name: String): String?

internal fun requiredEnvironment(name: String): String =
    environmentOrNull(name)
        ?: error("required conformance environment variable is absent: $name")
