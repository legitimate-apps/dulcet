package com.legitimateapps.dulcet.core

import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLPeerUnverifiedException

internal actual fun tlsTrustFailureOrNull(failure: Throwable): TlsTrustFailure? {
    var current: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return null
        when (candidate) {
            is CertificateExpiredException,
            is CertificateNotYetValidException -> return TlsTrustFailure.ValidityPeriod
            is SSLPeerUnverifiedException -> return TlsTrustFailure.Other
            is CertificateException -> return TlsTrustFailure.CertificateChain
        }
        current = candidate.cause
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 16
