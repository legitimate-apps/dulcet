package com.legitimateapps.dulcet.server.integrations

import com.legitimateapps.dulcet.server.discover.DiscoverSettings

/** An operator-configured service: a base URL and an API key that only ever travels in a request header. */
class ServiceEndpoint(val url: String, private val apiKey: String) {
    init {
        DiscoverSettings.validateBase(url)
        require(apiKey.isNotEmpty() && apiKey.all { it.code in 0x21..0x7e }) { "API key must be printable ASCII without spaces" }
    }
    internal fun key() = apiKey
    override fun toString() = "ServiceEndpoint(configured)"
}

/** Settings of the integrations layer, all under the `integration-` prefix (`DULCET_INTEGRATION_*`). */
data class IntegrationSettings(
    val lidarr: ServiceEndpoint? = null,
    val jellyfin: ServiceEndpoint? = null,
    val emby: ServiceEndpoint? = null,
    val lidarrImageHosts: Set<String> = setOf(DEFAULT_LIDARR_IMAGE_HOST),
    /** Artist-image sources in the order asked (`lidarr`, `lidarr-feed`, `jellyfin`, `emby`); null is the default order, empty is off. */
    val artistImageOrder: List<String>? = null,
) {
    init { require(lidarrImageHosts.all { it.isNotEmpty() && it == it.lowercase() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }) { "Image hosts are bare lowercase host names" } }
    override fun toString() = "IntegrationSettings(lidarr=${lidarr != null},jellyfin=${jellyfin != null},emby=${emby != null})"
    companion object {
        const val DEFAULT_LIDARR_IMAGE_HOST = "images.lidarr.audio"
        fun load(value: (String, String?) -> String?): IntegrationSettings {
            fun endpoint(name: String): ServiceEndpoint? {
                val url = value("integration-$name-url", null)?.trim()?.takeIf(String::isNotEmpty)
                val key = value("integration-$name-api-key", null)?.trim()?.takeIf(String::isNotEmpty)
                require((url == null) == (key == null)) { "integration-$name-url and integration-$name-api-key are set together" }
                return url?.let { ServiceEndpoint(it.trimEnd('/'), key!!) }
            }
            return IntegrationSettings(
                lidarr = endpoint("lidarr"), jellyfin = endpoint("jellyfin"), emby = endpoint("emby"),
                artistImageOrder = value("integration-artist-images", null)?.let { raw -> if (raw.trim() == "off") emptyList() else raw.split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty) },
                lidarrImageHosts = value("integration-lidarr-image-hosts", DEFAULT_LIDARR_IMAGE_HOST)!!.split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet(),
            )
        }
    }
}
