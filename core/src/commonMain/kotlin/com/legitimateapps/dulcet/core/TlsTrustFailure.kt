package com.legitimateapps.dulcet.core

/** Maps only typed platform trust failures; exception messages are never a classification input. */
internal expect fun tlsTrustFailureOrNull(failure: Throwable): TlsTrustFailure?

/** True only for a typed platform failure produced by rejecting an unsupported auth challenge. */
internal expect fun isUnsupportedAuthenticationChallenge(failure: Throwable): Boolean
