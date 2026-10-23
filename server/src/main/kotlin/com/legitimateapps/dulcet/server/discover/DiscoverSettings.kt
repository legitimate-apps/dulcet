package com.legitimateapps.dulcet.server.discover

import java.net.URI

/** Independent optional features and operator-controlled upstream origins, never caller URLs. */
data class DiscoverSettings(
    val enabled: Boolean = true,
    val wishlistEnabled: Boolean = true,
    val musicBrainzUrl: String = "https://musicbrainz.org",
    val coverArtArchiveUrl: String = "https://coverartarchive.org",
    val listenBrainzUrl: String = "https://api.listenbrainz.org",
    val listenBrainzLabsUrl: String = "https://labs.api.listenbrainz.org",
    private val listenBrainzToken: String? = null,
    val musicBrainzMinGapMillis: Long = 1000,
) {
    init {
        listOf(musicBrainzUrl, coverArtArchiveUrl, listenBrainzUrl, listenBrainzLabsUrl).forEach(::validateBase)
        require(musicBrainzMinGapMillis >= 0)
        if (URI(musicBrainzUrl).host.equals("musicbrainz.org", true) || URI(musicBrainzUrl).host.endsWith(".musicbrainz.org", true))
            require(musicBrainzMinGapMillis >= 1000) { "Public MusicBrainz requires at least 1000 ms between requests" }
    }
    override fun toString() = "DiscoverSettings(enabled=$enabled,wishlistEnabled=$wishlistEnabled)"
    companion object {
        fun validateBase(value: String) {
            val uri = URI(value)
            require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) { "Upstream base must be an HTTP(S) URL without credentials, query or fragment" }
        }
        fun load(value: (String, String?) -> String?): DiscoverSettings = DiscoverSettings(
            enabled = value("discover", "on") != "off", wishlistEnabled = value("wishlist", "on") != "off",
            musicBrainzUrl = value("musicbrainz-url", "https://musicbrainz.org")!!,
            coverArtArchiveUrl = value("cover-art-archive-url", "https://coverartarchive.org")!!,
            listenBrainzUrl = value("listenbrainz-url", "https://api.listenbrainz.org")!!,
            listenBrainzLabsUrl = value("listenbrainz-labs-url", "https://labs.api.listenbrainz.org")!!,
            listenBrainzToken = value("listenbrainz-token", null),
            musicBrainzMinGapMillis = value("musicbrainz-min-gap-ms", "1000")!!.toLong(),
        )
    }
}
