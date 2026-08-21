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
            when (origin.code) {
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

private const val SERVER_CERTIFICATE_HAS_BAD_DATE = -1201L
private const val SERVER_CERTIFICATE_UNTRUSTED = -1202L
private const val SERVER_CERTIFICATE_HAS_UNKNOWN_ROOT = -1203L
private const val SERVER_CERTIFICATE_NOT_YET_VALID = -1204L
private const val MAX_CAUSE_DEPTH = 16
