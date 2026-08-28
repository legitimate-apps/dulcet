package com.legitimateapps.dulcet.core

internal actual fun createLibrarySyncControlDatabase(): LibrarySyncControlDatabase =
    error("Library sync conformance controls run only on the declared JVM and Apple targets")
