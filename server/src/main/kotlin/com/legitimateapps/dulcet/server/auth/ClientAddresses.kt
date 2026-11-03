package com.legitimateapps.dulcet.server.auth

import io.ktor.http.Headers
import io.ktor.server.application.ApplicationCall
import java.net.InetAddress

/** Forwarded addresses are assertions by a configured proxy, never by an arbitrary client. */
class ClientAddresses(trustedProxies: List<String> = emptyList()) {
    private val networks = trustedProxies.map { Network.parse(it) }

    fun isTrustedProxy(peer: String): Boolean = literal(peer)?.let(::trusted) == true

    fun resolve(call: ApplicationCall): String {
        if (networks.isEmpty()) return call.request.local.remoteHost
        // CIO's remoteHost performs reverse DNS and can cache that hostname in the socket.
        // Read remoteAddress first so trust decisions use the numeric socket peer.
        return resolve(call.request.local.remoteAddress, call.request.headers)
    }

    fun resolve(peer: String, headers: Headers): String {
        if (networks.isEmpty()) return peer
        val socket = literal(peer) ?: return peer
        if (!trusted(socket)) return peer

        // A present but malformed authoritative header must not enable a lower-priority spoof.
        for (name in listOf("CF-Connecting-IP", "X-Real-IP", "X-Forwarded-For")) {
            val values = headers.getAll(name) ?: continue
            if (values.size != 1 || values.single().length > 8192) return peer
            val value = values.single()
            if (name != "X-Forwarded-For") return literal(value.trim())?.text ?: peer
            val parts = value.split(',')
            if (parts.size > 64) return peer
            val hops = parts.map { literal(it.trim()) ?: return peer }
            return hops.asReversed().firstOrNull { !trusted(it) }?.text ?: peer
        }
        return peer
    }

    private fun trusted(address: Address) = networks.any { it.contains(address) }

    private class Address(val bytes: ByteArray) {
        val text: String get() = InetAddress.getByAddress(bytes).hostAddress
    }

    private class Network(private val address: Address, private val prefix: Int) {
        fun contains(other: Address): Boolean {
            if (address.bytes.size != other.bytes.size) return false
            val whole = prefix / 8
            for (index in 0 until whole) if (address.bytes[index] != other.bytes[index]) return false
            val bits = prefix % 8
            if (bits == 0) return true
            val mask = (0xff shl (8 - bits)) and 0xff
            return (address.bytes[whole].toInt() and mask) == (other.bytes[whole].toInt() and mask)
        }

        companion object {
            fun parse(entry: String): Network {
                val parts = entry.trim().split('/')
                require(parts.size in 1..2) { "trusted-proxies requires IP addresses or CIDRs" }
                val address = requireNotNull(literal(parts[0])) { "trusted-proxies requires literal IP addresses" }
                var prefix = if (parts.size == 1) address.bytes.size * 8 else {
                    require(parts[1].matches(Regex("[0-9]{1,3}"))) { "Invalid trusted-proxies CIDR prefix" }
                    parts[1].toInt()
                }
                // The JVM represents IPv4-mapped IPv6 literals as IPv4. Keep the equivalent
                // network and client key identical instead of providing a second limiter identity.
                if (parts.size == 2 && ':' in parts[0] && address.bytes.size == 4) {
                    require(prefix in 96..128) { "IPv4-mapped trusted-proxies CIDRs require a /96 to /128 prefix" }
                    prefix -= 96
                }
                require(prefix in 0..address.bytes.size * 8) { "Invalid trusted-proxies CIDR prefix" }
                return Network(address, prefix)
            }
        }
    }

    companion object {
        private fun ipv4(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            return parts.map { part ->
                if (!part.matches(Regex("0|[1-9][0-9]{0,2}"))) return null
                val value = part.toInt()
                if (value > 255) return null
                value.toByte()
            }.toByteArray()
        }

        /** The lexical gate excludes hostnames, zones, brackets and ports before any JVM lookup. */
        private fun literal(text: String): Address? {
            if (':' !in text) return ipv4(text)?.let(::Address)
            if (text.length > 45 || !text.matches(Regex("[0-9a-fA-F:.]+"))) return null
            if ('.' in text && ipv4(text.substringAfterLast(':')) == null) return null
            return runCatching { Address(InetAddress.getByName(text).address) }.getOrNull()
        }
    }
}
