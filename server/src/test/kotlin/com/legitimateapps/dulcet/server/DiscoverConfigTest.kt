package com.legitimateapps.dulcet.server

import kotlin.test.*
import java.nio.file.Files

class DiscoverConfigTest {
    @Test fun defaultsOverridesAndIndependentSwitches() {
        val defaults = Config.load(emptyMap(), emptyMap()).discover
        assertTrue(defaults.enabled && defaults.wishlistEnabled)
        assertEquals(1000L, defaults.musicBrainzMinGapMillis)
        assertEquals("https://musicbrainz.org", defaults.musicBrainzUrl)
        assertEquals("https://coverartarchive.org", defaults.coverArtArchiveUrl)
        assertEquals("https://api.listenbrainz.org", defaults.listenBrainzUrl)
        assertEquals("https://labs.api.listenbrainz.org", defaults.listenBrainzLabsUrl)
        val configured = Config.load(mapOf("discover" to "off", "musicbrainz-url" to "http://127.0.0.1:1/mb", "cover-art-archive-url" to "http://127.0.0.1:1/caa", "listenbrainz-url" to "http://127.0.0.1:1/lb", "listenbrainz-labs-url" to "http://127.0.0.1:1/labs"), emptyMap()).discover
        assertFalse(configured.enabled); assertTrue(configured.wishlistEnabled)
        assertTrue(Config.load(mapOf("wishlist" to "off"), emptyMap()).discover.enabled)
        assertFalse(Config.load(mapOf("wishlist" to "off"), emptyMap()).discover.wishlistEnabled)
        assertTrue(configured.listenBrainzLabsUrl.endsWith("/labs"))
    }
    @Test fun invalidBaseAndPublicRateBypassFailStartup() {
        for (bad in listOf("https://user@music.test", "https://music.test?key=x", "https://music.test#x", "file:///tmp/x"))
            assertFailsWith<IllegalArgumentException> { Config.load(mapOf("musicbrainz-url" to bad), emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Config.load(mapOf("musicbrainz-min-gap-ms" to "999"), emptyMap()) }
    }
    @Test fun ordinaryFixtureStartsWithoutOutsideCalls() {
        val root = Files.createTempDirectory("discover-config")
        val ctx = Fixtures.context(root)
        try { assertFalse(ctx.config.discover.enabled) } finally { ctx.db.close(); root.toFile().deleteRecursively() }
    }
}
