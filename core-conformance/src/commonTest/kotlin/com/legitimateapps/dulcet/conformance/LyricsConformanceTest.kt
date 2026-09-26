package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnectionResult
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.CapabilitySet
import com.legitimateapps.dulcet.core.LyricsContract
import com.legitimateapps.dulcet.core.LyricsControlFreshness
import com.legitimateapps.dulcet.core.LyricsControlPublication
import com.legitimateapps.dulcet.core.LyricsControlRequest
import com.legitimateapps.dulcet.core.LyricsControlSession
import com.legitimateapps.dulcet.core.LyricsCursor
import com.legitimateapps.dulcet.core.LyricsLayer
import com.legitimateapps.dulcet.core.LyricsSource
import com.legitimateapps.dulcet.core.SearchPageRequest
import com.legitimateapps.dulcet.core.SearchPageResult
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.core.ServerSearch
import com.legitimateapps.dulcet.core.cursorAtMilliseconds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * CONF-42 (spec §18.4, §20.4): the `songLyrics` structured response shape, read by the production
 * lyrics path against the disposable reference server's synthetic corpus (`tools/seed-corpus`):
 * a sidecar LRC with an offset and a blank line, a sidecar text file, embedded lyrics in three
 * languages (two synced), embedded plain lyrics, and one track with none.
 *
 * Preconditions are asserted, never skipped (§20.2.2): the extension must be advertised at the
 * version the enhanced shape needs, and every fixture track must be found before anything is read.
 * The "no lyrics" answer counts only because the same instrument returned lyrics for the others.
 */
class LyricsConformanceTest {
    @Test
    fun conf42SongLyricsStructuredShapeSelectionAndOfflineCache() = runTest(timeout = 3.minutes) {
        val capabilities = connectAndRequireSongLyrics()
        val ids = fixtureTrackIds()

        withSession(capabilities, listOf("en-US")) { session ->
            assertEquals("getLyricsBySongId", session.endpointName, "CONF-42 gate chose the wrong endpoint")
            assertTrue(session.requestsEnhanced, "CONF-42 must read the v2 shape the server advertises")

            // Sidecar LRC: synced, offset, unknown language, a blank line kept as the gap.
            val synced = session.read(ids.getValue(SYNCED_LRC), ARTIST, SYNCED_LRC).requireLive("sidecar LRC")
            val lrc = synced.single("sidecar LRC")
            assertEquals(LyricsSource.SongLyricsExtension, synced.lyrics?.source)
            assertTrue(lrc.synced, "CONF-42 sidecar LRC must be synced")
            assertEquals(250L, lrc.offsetMilliseconds, "CONF-42 [offset:+250] must reach the model")
            assertNull(lrc.language, "CONF-42 an LRC's unknown language (xxx/und) must read as unknown")
            assertEquals("main", lrc.kind, "CONF-42 the v2 shape classifies every layer; main expected")
            assertEquals(listOf(1_000L, 5_500L, 10_000L, 20_250L), lrc.lines.map { it.startMilliseconds })
            assertEquals(
                listOf("Dulcet synced line one", "Dulcet synced line two", "", "Dulcet synced line four"),
                lrc.lines.map { it.text },
            )
            // The cursor over the server's real layer: the offset moves every line 250 ms sooner.
            assertEquals(-1, lrc.cursorAtMilliseconds(749).index)
            assertTrue(lrc.cursorAtMilliseconds(749).isInterlude)
            assertEquals(0, lrc.cursorAtMilliseconds(750).index)
            assertEquals(2, lrc.cursorAtMilliseconds(9_750).index)
            assertTrue(lrc.cursorAtMilliseconds(12_000).isInterlude, "CONF-42 the blank line is an interlude")
            assertEquals(3, lrc.cursorAtMilliseconds(20_000).index)
            assertEquals(1, lrc.cursorAtMilliseconds(5_250).index, "CONF-42 a backward seek")

            // Embedded, three languages: the person's language among the synced layers.
            val multi = session.read(ids.getValue(MULTILINGUAL), ARTIST, MULTILINGUAL).requireLive("multilingual")
            val layers = multi.lyrics?.layers.orEmpty()
            assertEquals(setOf("deu", "eng", "por"), layers.map { it.language }.toSet(), "CONF-42 languages")
            assertEquals(setOf("deu", "eng"), layers.filter { it.synced }.map { it.language }.toSet())
            assertTrue(layers.all { it.kind == "main" }, "CONF-42 every layer must carry kind=main")
            assertEquals("eng", multi.lyrics?.selected?.language, "CONF-42 selection for en-US")
            assertEquals("Dulcet English line one", multi.lyrics?.selected?.lines?.first()?.text)

            // Sidecar text and embedded plain lyrics: unsynced, no current line.
            val text = session.read(ids.getValue(SIDECAR_TXT), ARTIST, SIDECAR_TXT).requireLive("sidecar TXT").single("sidecar TXT")
            assertFalse(text.synced)
            assertEquals(listOf("Dulcet plain sidecar line one", "Dulcet plain sidecar line two"), text.lines.map { it.text })
            assertEquals(LyricsCursor.NONE, text.cursorAtMilliseconds(1_000))
            val plain = session.read(ids.getValue(EMBEDDED_PLAIN), ARTIST, EMBEDDED_PLAIN).requireLive("embedded plain").single("embedded plain")
            assertFalse(plain.synced)
            assertEquals(listOf("Dulcet embedded plain line one", "Dulcet embedded plain line two"), plain.lines.map { it.text })

            // The control track has none: an empty document, live — validated by the four above.
            val none = session.read(ids.getValue(WITHOUT_LYRICS), ARTIST, WITHOUT_LYRICS).requireLive("no lyrics")
            assertEquals(emptyList(), none.lyrics?.layers, "CONF-42 a track without lyrics must have no layers")
            assertNull(none.lyrics?.selected)
            assertFalse(none.notFound, "CONF-42 a known track without lyrics is not a code-70 answer")

            // An id the server does not know answers code 70; that is "no lyrics", stored. The
            // marker is what proves code 70 was seen: live with no layers is also the line above.
            val unknown = session.read(UNKNOWN_ID, ARTIST, "Nothing").requireLive("unknown id")
            assertTrue(unknown.notFound, "CONF-42 the unknown id must be answered with code 70")
            assertEquals(emptyList(), unknown.lyrics?.layers)

            // Offline: seen lyrics are served from the seen-cache with no request.
            session.setOnline(false)
            val offline = session.read(ids.getValue(SYNCED_LRC), ARTIST, SYNCED_LRC)
            assertEquals(LyricsControlFreshness.CachedOffline, offline.freshness, "CONF-42 offline freshness")
            assertEquals(emptyList(), offline.endpoints, "CONF-42 an offline read must send nothing")
            assertEquals(synced.lyrics, offline.lyrics, "CONF-42 offline lyrics must equal the live read")
            val neverRead = session.read(NEVER_READ_ID, ARTIST, "Never")
            assertEquals(LyricsControlFreshness.Unavailable, neverRead.freshness)
            assertEquals(emptyList(), neverRead.endpoints)
            // Every one of the six requests above went out with the §18.4 response size limit,
            // through the real transport that stops reading at it.
            assertEquals(0, session.requestsWithoutSizeLimit, "CONF-42 a lyrics request was sent without its size limit")

            println(
                "CONF-42 OBSERVED endpoint=getLyricsBySongId enhanced=true lrc_offset_ms=${lrc.offsetMilliseconds} " +
                    "lrc_lines=${lrc.lines.size} multilingual_order=${layers.map { it.language }} " +
                    "selected_en=${multi.lyrics?.selected?.language} none_layers=0 unknown_code70=${unknown.notFound} " +
                    "offline=${offline.freshness} " +
                    "offline_requests=0 unlimited_requests=${session.requestsWithoutSizeLimit}",
            )
        }

        // Selection follows the person, and synced beats a preferred-language unsynced layer.
        withSession(capabilities, listOf("de")) { session ->
            val read = session.read(ids.getValue(MULTILINGUAL), ARTIST, MULTILINGUAL).requireLive("multilingual de")
            assertEquals("deu", read.lyrics?.selected?.language, "CONF-42 selection for de")
        }
        withSession(capabilities, listOf("pt-BR")) { session ->
            val read = session.read(ids.getValue(MULTILINGUAL), ARTIST, MULTILINGUAL).requireLive("multilingual pt")
            val selected = assertNotNull(read.lyrics?.selected)
            assertTrue(selected.synced, "CONF-42 a synced layer must win over the unsynced Portuguese one")
            assertEquals("deu", selected.language, "CONF-42 the tie between two other languages is by code")
        }
    }

    @Test
    fun conf42LegacyGetLyricsOnlyWhenTheExtensionIsNotAdvertised() = runTest(timeout = 3.minutes) {
        val advertised = connectAndRequireSongLyrics()
        val ids = fixtureTrackIds()
        // The same server, presented as one that does not advertise the extension.
        val withoutExtension = advertised.copy(extensions = advertised.extensions - "songLyrics")
        withSession(withoutExtension, listOf("en")) { session ->
            assertEquals("getLyrics", session.endpointName, "CONF-42 gate without the extension")
            val legacy = session.read(ids.getValue(SYNCED_LRC), ARTIST, SYNCED_LRC).requireLive("legacy getLyrics")
            assertEquals(listOf("getLyrics"), legacy.endpoints)
            assertEquals(LyricsSource.LegacyGetLyrics, legacy.lyrics?.source)
            val layer = legacy.single("legacy getLyrics")
            assertFalse(layer.synced, "CONF-42 getLyrics is unsynced")
            assertNull(layer.language)
            assertEquals(
                listOf("Dulcet synced line one", "Dulcet synced line two", "", "Dulcet synced line four"),
                layer.lines.map { it.text },
                "CONF-42 getLyrics must return the LRC text without its timestamps",
            )
            val unmatched = session.read(NEVER_READ_ID, ARTIST, "No Such Dulcet Title").requireLive("legacy unmatched")
            assertEquals(emptyList(), unmatched.lyrics?.layers)
            assertFalse(unmatched.notFound, "CONF-42 an unmatched getLyrics answers ok with an empty value, not code 70")
            assertEquals(0, session.requestsWithoutSizeLimit, "CONF-42 a getLyrics request was sent without its size limit")
            println(
                "CONF-42 OBSERVED legacy endpoint=getLyrics lines=${layer.lines.size} unmatched_layers=0 " +
                    "unlimited_requests=${session.requestsWithoutSizeLimit}",
            )
        }
    }

    // ---- Preconditions (§20.2.2: fail, never skip) ---------------------------------------------------

    private suspend fun connectAndRequireSongLyrics(): CapabilitySet {
        val result = AccountConnector().connect(
            AccountConnectionRequest(disposableConformanceBaseUrl(), username(), password(), allowLocalHttp = true),
        )
        val connected = (result as? AccountConnectionResult.Connected)?.account
            ?: fail("CONF-42 precondition: account connect to the disposable server failed: $result")
        val capabilities = connected.capabilities
        assertFalse(capabilities.legacySubsonic, "CONF-42 precondition: the server must speak OpenSubsonic")
        val versions = capabilities.extensions["songLyrics"]
            ?: fail("CONF-42 precondition: songLyrics is not advertised; extensions=${capabilities.extensions.keys.sorted()}")
        assertTrue(2 in versions, "CONF-42 precondition: songLyrics v2 is not advertised; versions=$versions")
        return capabilities
    }

    private suspend fun fixtureTrackIds(): Map<String, String> =
        listOf(SYNCED_LRC, MULTILINGUAL, SIDECAR_TXT, EMBEDDED_PLAIN, WITHOUT_LYRICS).associateWith { title ->
            val result = ServerSearch().search(
                SearchPageRequest(
                    providerInstanceId = PROVIDER_ID,
                    normalizedBaseUrl = disposableConformanceBaseUrl(),
                    username = username(),
                    password = password(),
                    allowLocalHttp = true,
                    query = title,
                    artistCount = 0,
                    artistOffset = 0,
                    albumCount = 0,
                    albumOffset = 0,
                    trackCount = 20,
                    trackOffset = 0,
                ),
            )
            val tracks = (result as? SearchPageResult.Loaded)?.page?.results
                ?: fail("CONF-42 precondition: search3 for '$title' failed: $result")
            tracks.singleOrNull { it.type == SearchResultType.Track && it.title == title }?.id?.rawId
                ?: fail("CONF-42 precondition: the corpus has no single track '$title'; observed=${tracks.map { it.title }}")
        }

    private suspend fun withSession(
        capabilities: CapabilitySet,
        languages: List<String>,
        block: suspend (LyricsControlSession) -> Unit,
    ) {
        val session = LyricsContract.open(
            LyricsControlRequest(
                providerInstanceId = PROVIDER_ID,
                normalizedBaseUrl = disposableConformanceBaseUrl(),
                username = username(),
                password = password(),
                allowLocalHttp = true,
                capabilities = capabilities,
                preferredLanguages = languages,
            ),
        )
        try {
            block(session)
        } finally {
            session.close()
        }
    }

    private fun LyricsControlPublication.requireLive(label: String): LyricsControlPublication {
        assertEquals(
            LyricsControlFreshness.Live,
            freshness,
            "CONF-42 $label: expected a live read, got $freshness failure=$failure endpoints=$endpoints",
        )
        assertEquals(1, endpoints.size, "CONF-42 $label: one read is one request; sent $endpoints")
        return this
    }

    private fun LyricsControlPublication.single(label: String): LyricsLayer {
        val layers = lyrics?.layers.orEmpty()
        assertEquals(1, layers.size, "CONF-42 $label: expected one layer, got ${layers.map { it.language to it.synced }}")
        return layers.single()
    }

    private fun username() = environmentOrNull("DULCET_CONFORMANCE_USERNAME") ?: ADMIN_USER
    private fun password() = environmentOrNull("DULCET_CONFORMANCE_PASSWORD") ?: ADMIN_PASSWORD

    private companion object {
        const val PROVIDER_ID = "provider:disposable-conformance-lyrics"
        const val ADMIN_USER = "dulcet-admin"
        const val ADMIN_PASSWORD = "dulcet-ci-canary-password"
        const val ARTIST = "Dulcet Fixtures"
        const val SYNCED_LRC = "Thirty One Seconds"
        const val MULTILINGUAL = "Twenty Nine Seconds"
        const val SIDECAR_TXT = "Ogg Probe"
        const val EMBEDDED_PLAIN = "Shared Credit"
        const val WITHOUT_LYRICS = "Dulcet Health Probe"
        const val UNKNOWN_ID = "dulcet-conformance-unknown-track-id"
        const val NEVER_READ_ID = "dulcet-conformance-never-read-id"
    }
}
