package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.SaltSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSURLAuthenticationMethodHTTPBasic
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.NSURLCredentialStorage
import platform.Foundation.NSURLProtectionSpace
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class DarwinAmbientCredentialConformanceTest {
    @Test
    fun sharedCredentialStorageCannotAuthenticateBelowTheKtorBoundary() = runTest {
        val protectionSpace = NSURLProtectionSpace(
            host = "127.0.0.1",
            port = 4540,
            protocol = "http",
            realm = AMBIENT_AUTH_REALM,
            authenticationMethod = NSURLAuthenticationMethodHTTPBasic,
        )
        val credential = NSURLCredential.create(
            user = "ambient-auth-user",
            password = "ambient-auth-password",
            persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceForSession,
        )
        val storage = NSURLCredentialStorage.sharedCredentialStorage
        storage.setCredential(credential, protectionSpace)
        storage.setDefaultCredential(credential, protectionSpace)
        assertNotNull(storage.defaultCredentialForProtectionSpace(protectionSpace))

        val observationClient = HttpClient(Darwin) { expectSuccess = false }
        try {
            AccountConnector(saltSource = SaltSource { "0123456789abcdef0123456789abcdef" }).connect(
                AccountConnectionRequest(
                    serverUrl = "http://127.0.0.1:4540/ambient-auth",
                    username = "dulcet-ambient-auth",
                    password = "subsonic-password",
                    allowLocalHttp = true,
                ),
            )

            val observation = observationClient.get(
                "http://127.0.0.1:4540/observations/ambient-auth",
            )
            assertEquals(
                200,
                observation.status.value,
                "Darwin used an ambient URL credential below Ktor's observation boundary: " +
                    observation.bodyAsText(),
            )
        } finally {
            observationClient.close()
            storage.removeCredential(credential, protectionSpace)
        }
    }

    private companion object {
        const val AMBIENT_AUTH_REALM = "dulcet-ambient-auth"
    }
}
