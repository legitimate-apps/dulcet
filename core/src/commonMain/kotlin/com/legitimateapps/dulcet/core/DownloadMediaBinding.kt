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
                    val path = parsed.encodedPath.trimEnd('/')
                    "$scheme://${host.value}$port$path"
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
    JsonPrimitive(binding.username),
    JsonPrimitive(secureRandomBytes(16).toLowerHex()),
)).toString()

internal fun matchesMediaBinding(encoded: String?, binding: DownloadMediaBinding): Boolean = try {
    val parts = encoded?.let { Json.parseToJsonElement(it).jsonArray }
    parts != null && parts.size == 3 &&
        MediaBindingIdentity.sameEndpoint(parts[0].jsonPrimitive.content, binding.normalizedBaseUrl) &&
        parts[1].jsonPrimitive.content == binding.username && parts.all { it.jsonPrimitive.isString } &&
        Regex("[0-9a-f]{32}").matches(parts[2].jsonPrimitive.content)
} catch (_: Exception) { false }
