package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun createAccountHttpClient(
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            setURLCredentialStorage(null)
        }
        handleChallenge { _, _, challenge, completionHandler ->
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                completionHandler(
                    NSURLSessionAuthChallengePerformDefaultHandling,
                    challenge.proposedCredential,
                )
            } else {
                throw UnsupportedAuthenticationChallengeFailure()
            }
        }
    }
    configure()
}

/** Content-free marker retained by Ktor's Darwin task handler when it cancels the challenge. */
internal class UnsupportedAuthenticationChallengeFailure : Exception()
