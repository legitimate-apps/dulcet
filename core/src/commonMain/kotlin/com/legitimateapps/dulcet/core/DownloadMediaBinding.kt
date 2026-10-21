package com.legitimateapps.dulcet.core

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Account identity for local media and native playback lifetimes. Credentials may refresh without
 * changing this identity. Endpoint paths remain case-sensitive and keep their escaped separators.
 */
public object MediaBindingIdentity {
    /** Validate a replacement before retiring playback or changing its durable namespace. */
    public fun isValidAccount(account: PlaybackEndpointAccount): Boolean =
        isValidBinding(account.providerInstanceId, account.normalizedBaseUrl, account.username)

    /** Raw-field variant lets native callers reject invalid input before constructing an account. */
    public fun isValidBinding(providerInstanceId: String, normalizedBaseUrl: String, username: String): Boolean =
        providerInstanceId.isNotBlank() && username.isNotBlank() && canonicalServerUrl(normalizedBaseUrl) != null

    public fun sameAccount(first: PlaybackEndpointAccount, second: PlaybackEndpointAccount): Boolean =
        sameBinding(
            first.providerInstanceId, first.normalizedBaseUrl, first.username,
            second.providerInstanceId, second.normalizedBaseUrl, second.username,
        )

    public fun sameBinding(
        firstProviderInstanceId: String,
        firstNormalizedBaseUrl: String,
        firstUsername: String,
        secondProviderInstanceId: String,
        secondNormalizedBaseUrl: String,
        secondUsername: String,
    ): Boolean = firstProviderInstanceId == secondProviderInstanceId &&
        firstUsername == secondUsername && sameEndpoint(firstNormalizedBaseUrl, secondNormalizedBaseUrl)

    /**
     * Canonicalizes an already normalized HTTP(S) server base URL. Unicode hosts remain unsupported,
     * as in account connection and redirect policy; ASCII punycode names use normal DNS case folding.
     * An invalid/unsupported endpoint never compares equal through a null canonical representation.
     */
    public fun canonicalServerUrl(normalizedBaseUrl: String): String? = try {
        val input = normalizedBaseUrl.trim()
        val schemeEnd = input.indexOf("://")
        if (schemeEnd <= 0 || '?' in input || '#' in input || '\\' in input) {
            null
        } else {
            val scheme = input.substring(0, schemeEnd).lowercase()
            val authority = input.substring(schemeEnd + 3).substringBefore('/')
            if ((scheme != "http" && scheme != "https") || authority.isBlank() ||
                authority.any { it.isWhitespace() || it.isISOControl() }
            ) {
                null
            } else {
                val parsed = Url(input)
                val host = parsed.host.canonicalRedirectHost()
                if (host !is CanonicalRedirectHost.Canonical || parsed.user != null || parsed.password != null) {
                    null
                } else {
                    val defaultPort = if (scheme == "https") 443 else 80
                    val port = if (parsed.port == defaultPort) "" else ":${parsed.port}"
                    // Account connection removes terminal base separators before building routes.
                    val path = canonicalEncodedPath(parsed.encodedPath.trimEnd('/'))
                    path?.let { "$scheme://${host.value}$port$it" }
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    internal fun sameEndpoint(first: String, second: String): Boolean {
        val canonical = canonicalServerUrl(first) ?: return false
        return canonical == canonicalServerUrl(second)
    }

    private fun canonicalEncodedPath(path: String): String? = buildString {
        var index = 0
        while (index < path.length) {
            val char = path[index]
            if (char != '%') {
                append(char)
                index++
            } else {
                if (index + 2 >= path.length || path[index + 1].digitToIntOrNull(16) == null ||
                    path[index + 2].digitToIntOrNull(16) == null
                ) return null
                // Hex spelling changes the representation, not the escaped octet. Do not decode
                // the octet: an escaped slash must still differ from a path separator.
                append('%')
                append(path[index + 1].uppercaseChar())
                append(path[index + 2].uppercaseChar())
                index += 3
            }
        }
    }
}

/** Media belongs to this endpoint AND user, independently of disposable seen-cache metadata. */
internal data class DownloadMediaBinding(val serverId: String, val normalizedBaseUrl: String, val username: String) {
    init { require(serverId.isNotBlank() && normalizedBaseUrl.isNotBlank()) }
    override fun toString(): String = "DownloadMediaBinding(serverId=$serverId, <redacted>)"
}

internal data class DownloadMediaBindingResult(val purgedDownloadIds: Set<DownloadId>)

// A reserved device preference, deliberately outside SeenCacheStore's namespace purge. This is
// persisted by existing schema-v10 library_depth_entry; no cache issue-sequence owns this value.
internal const val MEDIA_BINDING_CATEGORY = "download.mediaBinding"
internal const val MEDIA_BINDING_KEY = "binding"

internal fun encodeMediaBinding(binding: DownloadMediaBinding): String = JsonArray(listOf(
    JsonPrimitive(requireNotNull(MediaBindingIdentity.canonicalServerUrl(binding.normalizedBaseUrl))),
    JsonPrimitive(binding.username.also { require(it.isNotBlank()) }),
    JsonPrimitive(secureRandomBytes(16).toLowerHex()),
)).toString()

private data class DecodedMediaBinding(
    val endpoint: String,
    val username: String,
    val nonce: String,
    val authenticatedAliases: Set<String>,
)

private fun decodeMediaBinding(encoded: String?): DecodedMediaBinding? = try {
    val parts = encoded?.let { Json.parseToJsonElement(it).jsonArray }
    if (parts == null || parts.size !in 3..4 || parts.take(3).any { !it.jsonPrimitive.isString }) {
        null
    } else {
        val endpoint = MediaBindingIdentity.canonicalServerUrl(parts[0].jsonPrimitive.content)
        val username = parts[1].jsonPrimitive.content
        val nonce = parts[2].jsonPrimitive.content
        val aliases = if (parts.size == 4) parts[3].jsonArray.map {
            require(it.jsonPrimitive.isString && it.jsonPrimitive.content.isNotBlank())
            it.jsonPrimitive.content
        }.toSet() else emptySet()
        if (endpoint == null || username.isBlank() || !Regex("[0-9a-f]{32}").matches(nonce)) null
        else DecodedMediaBinding(endpoint, username, nonce, aliases)
    }
} catch (_: Exception) { null }

internal fun matchesMediaBinding(encoded: String?, binding: DownloadMediaBinding): Boolean {
    val stored = decodeMediaBinding(encoded) ?: return false
    return MediaBindingIdentity.sameEndpoint(stored.endpoint, binding.normalizedBaseUrl) &&
        (binding.username == stored.username || binding.username in stored.authenticatedAliases)
}

/** Adding an authenticated spelling preserves the ownership epoch of already running executors. */
internal fun sameMediaBindingEpoch(first: String?, second: String?): Boolean {
    if (first == second) return true
    val previous = decodeMediaBinding(first) ?: return false
    val current = decodeMediaBinding(second) ?: return false
    return previous.nonce == current.nonce && previous.endpoint == current.endpoint &&
        previous.username == current.username
}

internal fun adoptingMediaBindingUsername(encoded: String, username: String): String? {
    val stored = decodeMediaBinding(encoded) ?: return null
    if (username.isBlank()) return null
    if (username == stored.username || username in stored.authenticatedAliases) return encoded
    // Keep the original fields byte-for-byte, including a legacy URL spelling. Both the old and
    // confirmed saved credential remain usable if the process stops between adoption and save.
    val parts = Json.parseToJsonElement(encoded).jsonArray
    return JsonArray(parts.take(3) + listOf(JsonArray(
        (stored.authenticatedAliases + username).sorted().map(::JsonPrimitive),
    ))).toString()
}
