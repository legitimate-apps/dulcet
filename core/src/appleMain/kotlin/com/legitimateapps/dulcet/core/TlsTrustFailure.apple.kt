package com.legitimateapps.dulcet.core

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLErrorDomain

@OptIn(ExperimentalForeignApi::class)
internal actual fun tlsTrustFailureOrNull(failure: Throwable): TlsTrustFailure? {
    var current: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return null
        val origin = (candidate as? DarwinHttpRequestException)?.origin
        if (origin?.domain == NSURLErrorDomain) {
            when (origin?.code) {
                SERVER_CERTIFICATE_HAS_BAD_DATE,
                SERVER_CERTIFICATE_NOT_YET_VALID -> return TlsTrustFailure.ValidityPeriod
                SERVER_CERTIFICATE_UNTRUSTED,
                SERVER_CERTIFICATE_HAS_UNKNOWN_ROOT -> return TlsTrustFailure.CertificateChain
            }
        }
        current = candidate.cause
    }
    return null
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun isUnsupportedAuthenticationChallenge(failure: Throwable): Boolean {
    var current: Throwable? = failure
    repeat(MAX_CAUSE_DEPTH) {
        val candidate = current ?: return false
        val origin = (candidate as? DarwinHttpRequestException)?.origin
        if (
            origin?.domain == NSURLErrorDomain &&
            origin?.code in UNSUPPORTED_AUTHENTICATION_ERROR_CODES
        ) {
            return true
        }
        current = candidate.cause
    }
    return false
}

private const val SERVER_CERTIFICATE_HAS_BAD_DATE = -1201L
private const val SERVER_CERTIFICATE_UNTRUSTED = -1202L
private const val SERVER_CERTIFICATE_HAS_UNKNOWN_ROOT = -1203L
private const val SERVER_CERTIFICATE_NOT_YET_VALID = -1204L
private val UNSUPPORTED_AUTHENTICATION_ERROR_CODES = setOf(-1012L, -1013L)
private const val MAX_CAUSE_DEPTH = 16
