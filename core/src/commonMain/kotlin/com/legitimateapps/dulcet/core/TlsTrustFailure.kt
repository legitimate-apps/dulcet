package com.legitimateapps.dulcet.core

/** Maps only typed platform trust failures; exception messages are never a classification input. */
internal expect fun tlsTrustFailureOrNull(failure: Throwable): TlsTrustFailure?
