package com.legitimateapps.dulcet.core

import io.ktor.http.Url
import io.ktor.http.encodedPath

/** Resolver boundary used by the plaintext-local transport policy and deterministic tests. */
public fun interface HostResolver {
    public suspend fun resolve(host: String): List<String>
}

internal expect suspend fun platformResolveHost(host: String): List<String>

private object SystemHostResolver : HostResolver {
    override suspend fun resolve(host: String): List<String> = platformResolveHost(host)
}

internal fun systemHostResolver(): HostResolver = SystemHostResolver

internal data class ConnectionTarget(
    val url: String,
    val hostHeader: String?,
)

internal class LocalHttpPolicyFailure(
    val error: DomainError.Security,
) : Exception()

/**
 * Resolves every plaintext request immediately before sending and pins the connection URL to one
 * vetted address. The HTTP engine therefore connects to the address that this policy classified.
 */
internal class LocalHttpConnectionPolicy(
    private val resolver: HostResolver,
) {
    suspend fun targetFor(url: String, allowLocalHttp: Boolean): ConnectionTarget {
        val parsed = Url(url)
        if (parsed.protocol.name != "http") return ConnectionTarget(url, null)
        if (!allowLocalHttp) reject()

        val addresses = resolve(parsed.host)
        if (addresses.isEmpty() || addresses.any { !it.isPermittedLocalHttpAddress() }) reject()

        val selected = addresses.first()
        val addressAuthority = selected.asUrlHost()
        val pinnedUrl = "http://$addressAuthority:${parsed.port}${parsed.encodedPath}"
        val logicalAuthority = parsed.host.asUrlHost()
        return ConnectionTarget(
            url = pinnedUrl,
            hostHeader = "$logicalAuthority:${parsed.port}",
        )
    }

    suspend fun leavesLocalNetwork(currentUrl: String, targetUrl: String): Boolean {
        val current = Url(currentUrl)
        val target = Url(targetUrl)
        if (current.protocol.name != "http" || current.host.equals(target.host, ignoreCase = true)) {
            return false
        }
        val addresses = resolve(target.host)
        return addresses.isEmpty() || addresses.any { !it.isPermittedLocalHttpAddress() }
    }

    private suspend fun resolve(host: String): List<String> {
        val normalizedHost = host.substringBefore('%')
        val addresses = if (normalizedHost.isIpAddressLiteral()) {
            listOf(host)
        } else {
            resolver.resolve(host)
        }
        return addresses.map { it.removeSurrounding("[", "]") }.distinct()
    }

    private fun reject(): Nothing = throw LocalHttpPolicyFailure(
        DomainError.Security.LocalExceptionViolated,
    )
}

internal fun String.isPermittedLocalHttpAddress(): Boolean {
    val address = removeSurrounding("[", "]").substringBefore('%').lowercase()
    address.parseIpv4()?.let { octets ->
        return octets[0] == 127 ||
            octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
    if (!address.isValidIpv6Literal()) return false
    if (address == "::1" || address == "0:0:0:0:0:0:0:1") return true
    val firstHextet = address.substringBefore(':').toIntOrNull(16) ?: return false
    return firstHextet and 0xfe00 == 0xfc00
}

private fun String.isIpAddressLiteral(): Boolean = parseIpv4() != null || ':' in this

internal fun String.parseIpv4(): List<Int>? {
    val octets = split('.')
    if (octets.size != 4) return null
    return octets.map { it.toIntOrNull() ?: return null }
        .takeIf { values -> values.all { it in 0..255 } }
}

internal fun String.isValidIpv6Literal(): Boolean {
    val normalized = if ('.' in this) {
        val ipv4Tail = substringAfterLast(':')
        if (ipv4Tail.parseIpv4() == null) return false
        "${substringBeforeLast(':')}:0:0"
    } else {
        this
    }
    if (normalized.count { it == ':' } < 2) return false
    val compressionIndex = normalized.indexOf("::")
    if (compressionIndex >= 0 && normalized.indexOf("::", compressionIndex + 2) >= 0) return false
    val groups = normalized.split(':').filter(String::isNotEmpty)
    if (groups.any { it.length !in 1..4 || it.any { character -> !character.isHexDigit() } }) {
        return false
    }
    return if (compressionIndex >= 0) groups.size < 8 else groups.size == 8
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.asUrlHost(): String = if (':' in this) "[$this]" else this
