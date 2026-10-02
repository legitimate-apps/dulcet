package com.legitimateapps.dulcet.search.conformance

import android.os.LocaleList
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidLibraryCachedReason
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.library.LibraryConnectionState
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LyricsFrame
import com.legitimateapps.dulcet.library.deviceLanguages
import com.legitimateapps.dulcet.search.SearchAccount
import java.util.Locale
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CONF-42 through each Android app's own lyrics panel (spec §18.4): the production panel, its
 * `LibrarySession`, and the process's `AndroidLibraryReader` against the disposable server, counting
 * requests at a loopback forwarder. The panel is composed directly with the track Now Playing names,
 * paused, so nothing but the panel and the session decides what is read; opening the panel from the
 * player is the platform's existing proof. Each platform's test class calls each scenario from its
 * own test method, so phone and TV prove the same thing under their own test identities.
 *
 * The parts of CONF-42 a panel does not reach — the response size limit the transport enforces, and
 * the shapes of the sidecar and plain fixtures — stay with the core conformance suite.
 */
class LyricsPanelScenarios<A : ComponentActivity>(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<A>, A>,
    private val environment: ProductionLibraryEnvironment,
    /** The panel's tag prefix: its lines are `<tags>.line.N` and its statement `<tags>.statement`. */
    private val tags: String,
    private val platform: String,
    private val panel: @Composable (LibrarySession, AndroidPlaybackState) -> Unit,
) {
    private val proxy get() = environment.proxy
    private val server get() = environment.server
    private val sessions = mutableListOf<LibrarySession>()

    /** Closes every session a scenario started; the platform's test calls it from `@After`. */
    fun close() {
        sessions.forEach { it.close() }
        sessions.clear()
    }

    /**
     * The layer shown is the core's choice for the languages Android reports for this device: German
     * first shows the German synced layer; Portuguese first shows a synced layer, German, over the
     * Portuguese layer, which is unsynced — the tie between two other languages broken by code.
     */
    fun conf42TheLayerShownFollowsTheDevicesLanguageAndSyncedBeatsUnsynced() {
        val song = server.songId(MULTILINGUAL)
        val saved = LocaleList.getDefault()
        val savedLocale = Locale.getDefault()
        try {
            val german = shownFor(song, "de-DE")
            assertEquals("deu", german.language, "a German device is shown the German layer")
            assertTrue(german.synced, "the German layer is synced")
            assertEquals("Dulcet deutsche Zeile eins", lineText(0))
            close()

            val portuguese = shownFor(song, "pt-BR")
            assertTrue(portuguese.synced, "a synced layer is shown over the device's own language, which is unsynced")
            assertEquals("deu", portuguese.language, "the tie between the two synced layers is by code")
            assertEquals("Dulcet deutsche Zeile eins", lineText(0))
            assertTrue(compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text,
                listOf(androidx.compose.ui.text.AnnotatedString(PORTUGUESE_LINE)))).fetchSemanticsNodes().isEmpty(),
                "the Portuguese words are not drawn")
        } finally {
            LocaleList.setDefault(saved)
            Locale.setDefault(savedLocale)
        }
        assertNoCredentialLeak()
        println("CONF-42 APP OBSERVED $platform de=deu pt-BR=deu(synced-over-unsynced-por)")
    }

    /**
     * Lyrics read once are shown offline from what this device stored, with no request; lyrics never
     * read say so offline; and the network back, what was shown from storage is read live again.
     */
    fun conf42SeenLyricsShowOfflineWithNoRequestAndUnseenOnesSaySo() {
        val seen = server.songId(MULTILINGUAL)
        val unseen = server.songId(SYNCED_LRC)
        val session = startSession()
        val state = mutableStateOf(paused(seen, MULTILINGUAL))
        show(session, state)
        val live = awaitFrame("the lyrics read live") { it.trackRawId == seen && it.freshness == AndroidLibraryFreshness.Live }
        assertEquals(AndroidLyricsState.Lyrics, live.state)
        awaitLine(0, "Dulcet English line one")

        environment.network.lose()
        await("the session offline") { session.connection.value is LibraryConnectionState.Offline }
        val mark = proxy.size()

        state.value = paused(unseen, SYNCED_LRC)
        val never = awaitFrame("the never-read lyrics answered offline") { it.trackRawId == unseen }
        assertEquals(AndroidLyricsState.Unavailable, never.state)
        assertEquals(AndroidLibraryUnavailableReason.NotCachedOffline,
            (never.freshness as? AndroidLibraryFreshness.Unavailable)?.reason, "never read here: ${never.freshness}")
        await("the statement drawn") { exists("$tags.statement") }
        assertEquals(resources().getString(com.legitimateapps.dulcet.shared.R.string.lyrics_unavailable_offline),
            text("$tags.statement"))

        state.value = paused(seen, MULTILINGUAL)
        // The stored document is published first and then the read, which offline sends nothing and
        // answers with the stored document marked offline: that last is what is awaited.
        val stored = awaitFrame("the seen lyrics shown from storage, offline") {
            it.trackRawId == seen && it.state == AndroidLyricsState.Lyrics &&
                (it.freshness as? AndroidLibraryFreshness.Cached)?.reason == AndroidLibraryCachedReason.Offline
        }
        assertEquals(live.lineCount, stored.lineCount, "what was stored is what was read")
        assertEquals(live.language, stored.language)
        assertEquals(live.synced, stored.synced)
        awaitLine(0, "Dulcet English line one")
        val offline = proxy.since(mark).filter { it.endpoint != "getCoverArt" }
        assertEquals(emptyList(), offline.map { it.endpoint }, "nothing is sent for lyrics offline")

        val back = proxy.size()
        environment.network.restore()
        awaitFrame("the stored lyrics read live again once the network is back") {
            it.trackRawId == seen && it.freshness == AndroidLibraryFreshness.Live
        }
        val reads = proxy.since(back).filter { it.endpoint == "getLyricsBySongId" }.map { it.parameters["id"] }
        assertTrue(seen in reads, "read again through the extension: $reads")
        assertTrue(unseen !in reads, "the track no longer shown is not read")
        assertNoCredentialLeak()
        println("CONF-42 APP OBSERVED $platform offline-stored=${stored.freshness::class.simpleName} offline-requests=0 " +
            "never-read=NotCachedOffline back-online=live")
    }

    /**
     * A server that does not advertise `songLyrics` — the same server, its extension list answered
     * without it at the forwarder — has its lyrics read with `getLyrics` by artist and title: the LRC's
     * words, unsynced, with no timestamps, and no `getLyricsBySongId` is sent.
     */
    fun conf42WithoutTheExtensionAdvertisedTheLyricsAreReadWithGetLyrics() {
        proxy.rewrite({ it.endpoint == "getOpenSubsonicExtensions" }) { body -> withoutExtension(body, "songLyrics") }
        val song = server.songId(SYNCED_LRC)
        val artist = server.get("getSong", mapOf("id" to song)).getJSONObject("song").getString("artist")
        val session = startSession()
        val mark = proxy.size()
        show(session, mutableStateOf(paused(song, SYNCED_LRC, artist)))
        val shown = awaitFrame("the lyrics read live") { it.trackRawId == song && it.freshness == AndroidLibraryFreshness.Live }
        assertEquals(AndroidLyricsState.Lyrics, shown.state)
        assertTrue(!shown.synced, "getLyrics carries no timing")
        assertEquals(null, shown.language)
        assertEquals(LEGACY_LINES.size, shown.lineCount)
        LEGACY_LINES.forEachIndexed { index, line -> if (line.isNotEmpty()) awaitLine(index, line) }
        val sent = proxy.since(mark)
        assertTrue(sent.any { it.endpoint == "getOpenSubsonicExtensions" && it.rewritten },
            "control: the app was told of no songLyrics: ${sent.map { it.endpoint }}")
        val legacy = sent.filter { it.endpoint == "getLyrics" }
        assertEquals(1, legacy.size, "one getLyrics read: ${sent.map { it.endpoint }}")
        assertEquals(artist, legacy.single().parameters["artist"])
        assertEquals(SYNCED_LRC, legacy.single().parameters["title"])
        assertTrue(sent.none { it.endpoint == "getLyricsBySongId" }, "the extension is not used unadvertised")
        assertNoCredentialLeak()
        println("CONF-42 APP OBSERVED $platform legacy endpoint=getLyrics synced=false lines=${shown.lineCount}")
    }

    // ---- Instruments ------------------------------------------------------------------------------

    private fun shownFor(song: String, languages: String): LyricsFrame {
        LocaleList.setDefault(LocaleList.forLanguageTags(languages))
        Locale.setDefault(Locale.forLanguageTag(languages.substringBefore(',')))
        assertEquals(languages.split(','), deviceLanguages(), "setup: Android reports the device's languages")
        val session = startSession()
        show(session, mutableStateOf(paused(song, MULTILINGUAL)))
        return awaitFrame("the lyrics read live for $languages") { it.trackRawId == song && it.freshness == AndroidLibraryFreshness.Live }
            .also { assertEquals(AndroidLyricsState.Lyrics, it.state) }
    }

    private fun startSession(): LibrarySession {
        val session = LibrarySession(RuntimeEnvironment.getApplication(), account(), foreground = true)
        sessions += session
        session.start()
        await("the session online") { session.connection.value is LibraryConnectionState.Online }
        return session
    }

    private var content: MutableState<Pair<LibrarySession, MutableState<AndroidPlaybackState>>?>? = null

    /** Composes the panel once per test; a later call swaps the session and state it shows. */
    private fun show(session: LibrarySession, state: MutableState<AndroidPlaybackState>) {
        val existing = content
        if (existing != null) {
            existing.value = session to state
        } else {
            val holder = mutableStateOf<Pair<LibrarySession, MutableState<AndroidPlaybackState>>?>(session to state)
            content = holder
            compose.setContent {
                holder.value?.let { (shownSession, shownState) ->
                    androidx.compose.runtime.key(shownSession) { panel(shownSession, shownState.value) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun account(): SearchAccount {
        val stored = checkNotNull(AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).load()) { "setup: no account" }
        return SearchAccount(stored.id, stored.serverUrl, stored.username, stored.password, stored.allowLocalHttp)
    }

    private fun paused(rawId: String, title: String, artist: String = FIXTURE_ARTIST) = AndroidPlaybackState(
        title = title, phase = "Paused", positionMilliseconds = 0, durationMilliseconds = 30_000,
        artist = artist, queueEntryId = "q-$rawId",
        queue = listOf(AndroidQueueEntry("q-$rawId", AndroidTrack(account().providerInstanceId, rawId, title, artist))),
        currentIndex = 0,
    )

    private fun observed(): LibraryObservationState =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(LibraryObservation)).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(LibraryObservation) ?: LibraryObservationState()

    private fun awaitFrame(what: String, matches: (LyricsFrame) -> Boolean): LyricsFrame {
        await(what) { observed().lyrics.lastOrNull()?.let(matches) == true }
        return observed().lyrics.last()
    }

    private fun awaitLine(index: Int, expected: String) {
        await("line $index drawn as \"$expected\"") { exists("$tags.line.$index") && text("$tags.line.$index") == expected }
    }

    private fun lineText(index: Int): String {
        await("line $index drawn") { exists("$tags.line.$index") }
        return text("$tags.line.$index")
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("") { it.text }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun resources() = RuntimeEnvironment.getApplication().resources

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(WAIT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                runCatching(condition).getOrDefault(false)
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; frames=${observed().lyrics.takeLast(4)} " +
                "requests=${proxy.log().map { it.endpoint }}", timeout)
        }
    }

    /** Credentials ride in the query string; nothing the app logged may carry them (docs/TRAPS.md trap 12). */
    private fun assertNoCredentialLeak() {
        val logged = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        for (canary in listOf(ProductionLibraryEnvironment.USERNAME, ProductionLibraryEnvironment.PASSWORD)) {
            assertTrue(!logged.contains(canary), "A credential reached the log")
        }
    }

    private companion object {
        const val FIXTURE_ARTIST = "Dulcet Fixtures"
        /** Embedded lyrics in German and English (synced) and Portuguese (unsynced). */
        const val MULTILINGUAL = "Twenty Nine Seconds"
        /** A sidecar LRC; read with getLyrics, its words without their timestamps. */
        const val SYNCED_LRC = "Thirty One Seconds"
        const val PORTUGUESE_LINE = "Dulcet linha portuguesa"
        val LEGACY_LINES = listOf("Dulcet synced line one", "Dulcet synced line two", "", "Dulcet synced line four")
        const val WAIT_MILLIS = 60_000L

        /** The extension list as a server that does not offer [name] would answer it. */
        fun withoutExtension(body: ByteArray, name: String): ByteArray {
            val document = JSONObject(String(body, Charsets.UTF_8))
            val response = document.getJSONObject("subsonic-response")
            val extensions = response.getJSONArray("openSubsonicExtensions")
            val kept = org.json.JSONArray()
            var removed = 0
            for (index in 0 until extensions.length()) {
                val extension = extensions.getJSONObject(index)
                if (extension.getString("name") == name) removed++ else kept.put(extension)
            }
            check(removed == 1) { "setup: the server must advertise $name for the forwarder to withhold it" }
            response.put("openSubsonicExtensions", kept)
            return document.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
