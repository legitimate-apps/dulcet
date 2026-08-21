package com.legitimateapps.dulcet.core

import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLPeerUnverifiedException

internal actual fun tlsTrustFailureOrNull(failure: Throwable): TlsTrustFailure? {
    var current: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return null
        when (candidate) {
            is CertificateExpiredException,
            is CertificateNotYetValidException -> return TlsTrustFailure.ValidityPeriod
            is CertPathBuilderException -> return TlsTrustFailure.CertificateChain
            is CertPathValidatorException -> return when (candidate.reason) {
                CertPathValidatorException.BasicReason.EXPIRED,
                CertPathValidatorException.BasicReason.NOT_YET_VALID -> TlsTrustFailure.ValidityPeriod
                else -> TlsTrustFailure.CertificateChain
            }
            is SSLPeerUnverifiedException -> return TlsTrustFailure.Other
            is CertificateException -> return TlsTrustFailure.CertificateChain
        }
        current = candidate.cause
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 16
