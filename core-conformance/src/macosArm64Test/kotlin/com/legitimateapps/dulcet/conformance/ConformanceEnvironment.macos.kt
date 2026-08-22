package com.legitimateapps.dulcet.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun requiredEnvironment(name: String): String =
    getenv(name)?.toKString()?.takeIf(String::isNotBlank)
        ?: error("required conformance environment variable is absent: $name")
