package com.legitimateapps.dulcet.core

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountConnectionTimeoutTest {
    @Test
    fun engineConnectAndSocketTimeoutsRemainDistinctFromUnreachable() {
        listOf(
            ConnectTimeoutException("connect timeout"),
            SocketTimeoutException("socket timeout"),
        ).forEach { failure ->
            assertEquals(
                DomainError.Transport.Timeout,
                mapAccountConnectionFailure(failure),
                failure::class.simpleName,
            )
        }
    }
}
