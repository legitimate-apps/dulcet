package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.auth.ClientAddresses
import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientAddressesTest {
    private fun forwarded(name: String, value: String) = headersOf(name, value)

    @Test fun `default and untrusted peers ignore all forwarded claims`() {
        val headers = headersOf("CF-Connecting-IP" to listOf("198.51.100.4"), "X-Real-IP" to listOf("198.51.100.5"), "X-Forwarded-For" to listOf("198.51.100.6"))
        assertEquals("127.0.0.1", ClientAddresses().resolve("127.0.0.1", headers))
        assertEquals("192.0.2.8", ClientAddresses(listOf("192.0.2.9")).resolve("192.0.2.8", headers))
        assertEquals("localhost", ClientAddresses(listOf("127.0.0.1")).resolve("localhost", headers))
    }

    @Test fun `literal addresses and IPv4 IPv6 CIDR boundaries determine trust`() {
        val headers = forwarded("X-Real-IP", "198.51.100.4")
        val policy = ClientAddresses(listOf("192.0.2.128/25", "2001:db8:42::/48", "203.0.113.7"))
        for (peer in listOf("192.0.2.128", "192.0.2.255", "2001:db8:42:ffff::1", "203.0.113.7")) assertEquals("198.51.100.4", policy.resolve(peer, headers))
        for (peer in listOf("192.0.2.127", "192.0.3.1", "2001:db8:43::1", "203.0.113.8")) assertEquals(peer, policy.resolve(peer, headers))
        assertEquals("198.51.100.4", ClientAddresses(listOf("::/0")).resolve("2001:db8::1", headers))
        assertEquals("192.0.2.1", ClientAddresses(listOf("::/0")).resolve("192.0.2.1", headers), "IPv6 trust does not silently trust IPv4")
    }

    @Test fun `header precedence is explicit and malformed authoritative claims fail closed`() {
        val policy = ClientAddresses(listOf("192.0.2.1"))
        assertEquals("198.51.100.1", policy.resolve("192.0.2.1", headersOf("CF-Connecting-IP" to listOf("198.51.100.1"), "X-Real-IP" to listOf("198.51.100.2"), "X-Forwarded-For" to listOf("198.51.100.3"))))
        assertEquals("198.51.100.2", policy.resolve("192.0.2.1", headersOf("X-Real-IP" to listOf("198.51.100.2"), "X-Forwarded-For" to listOf("198.51.100.3"))))
        for (value in listOf("", " ", "client.example", "127.1", "0x7f000001", "127.000.0.1", "198.51.100.1:443", "[2001:db8::1]", "fe80::1%eth0", "198.51.100.1,198.51.100.2", "999.1.1.1")) {
            assertEquals("192.0.2.1", policy.resolve("192.0.2.1", headersOf("CF-Connecting-IP" to listOf(value), "X-Real-IP" to listOf("198.51.100.2"))), value)
        }
        assertEquals("192.0.2.1", policy.resolve("192.0.2.1", headersOf("X-Real-IP" to listOf("198.51.100.1", "198.51.100.2"))))
        assertEquals("192.0.2.1", policy.resolve("192.0.2.1", Headers.Empty))
    }

    @Test fun `forwarded chains stop at the rightmost untrusted hop and ignore claims farther left`() {
        val policy = ClientAddresses(listOf("192.0.2.0/24", "2001:db8::/32"))
        assertEquals("203.0.113.8", policy.resolve("192.0.2.9", forwarded("X-Forwarded-For", "198.51.100.99, 203.0.113.8, 2001:db8::1, 192.0.2.4")))
        assertEquals("192.0.2.9", policy.resolve("192.0.2.9", forwarded("X-Forwarded-For", "192.0.2.1,2001:db8::1")))
        for (chain in listOf("198.51.100.1,", ",198.51.100.1", "198.51.100.1,invalid,192.0.2.4", List(65) { "192.0.2.4" }.joinToString(","))) assertEquals("192.0.2.9", policy.resolve("192.0.2.9", forwarded("X-Forwarded-For", chain)))
    }

    @Test fun `equivalent IPv6 and IPv4 mapped forms share canonical limiter keys`() {
        val policy = ClientAddresses(listOf("192.0.2.0/24", "::ffff:203.0.113.0/120"))
        assertEquals("198.51.100.4", policy.resolve("::ffff:192.0.2.1", forwarded("CF-Connecting-IP", "::ffff:198.51.100.4")))
        assertEquals("198.51.100.4", policy.resolve("203.0.113.1", forwarded("CF-Connecting-IP", "198.51.100.4")))
        val short = policy.resolve("192.0.2.1", forwarded("CF-Connecting-IP", "2001:db8::1"))
        assertEquals(short, policy.resolve("192.0.2.1", forwarded("CF-Connecting-IP", "2001:0DB8:0:0:0:0:0:1")))
    }

    @Test fun `configuration is opt in accepts CLI override and refuses malformed trust networks`() {
        assertEquals(emptyList(), Config.load(emptyMap(), emptyMap()).trustedProxies)
        val env = mapOf("DULCET_TRUSTED_PROXIES" to "192.0.2.0/24, 2001:db8::/32")
        assertEquals(2, Config.load(emptyMap(), env).trustedProxies.size)
        assertEquals(emptyList(), Config.load(mapOf("trusted-proxies" to ""), env).trustedProxies)
        for (value in listOf("proxy.example", "192.0.2.1/33", "2001:db8::/129", "192.0.2.1/-1", "192.0.2.1/24/1", "192.0.2.1,", ",", "::ffff:192.0.2.1/64", "127.01.0.1", "192.0.2.1:443")) {
            assertFailsWith<IllegalArgumentException>(value) { Config.load(emptyMap(), mapOf("DULCET_TRUSTED_PROXIES" to value)) }
        }
    }
}
