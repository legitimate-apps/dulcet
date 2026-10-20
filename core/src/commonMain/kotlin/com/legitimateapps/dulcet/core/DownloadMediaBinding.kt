package com.legitimateapps.dulcet.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

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
    JsonPrimitive(binding.normalizedBaseUrl), JsonPrimitive(binding.username),
    JsonPrimitive(secureRandomBytes(16).toLowerHex()),
)).toString()

internal fun matchesMediaBinding(encoded: String?, binding: DownloadMediaBinding): Boolean = try {
    val parts = encoded?.let { Json.parseToJsonElement(it).jsonArray }
    parts != null && parts.size == 3 && parts[0].jsonPrimitive.content == binding.normalizedBaseUrl &&
        parts[1].jsonPrimitive.content == binding.username && parts.all { it.jsonPrimitive.isString } &&
        Regex("[0-9a-f]{32}").matches(parts[2].jsonPrimitive.content)
} catch (_: Exception) { false }
