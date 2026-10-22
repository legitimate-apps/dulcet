package com.legitimateapps.dulcet.emulator

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.net.URLDecoder

/** Real playlist activation -> Media3 media time -> submitted outbox -> server -> rendered Home. */
@RunWith(AndroidJUnit4::class)
class PlaylistPlayContextEmulatorProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun playlistThresholdSubmissionPopulatesRecentlyPlayedPlaylists() {
        val extensions = DisposableServerProbe.fromInstrumentation()
            .call("getOpenSubsonicExtensions").getJSONArray("openSubsonicExtensions")
        assumeTrue("Playlist context Home proof requires advertised dulcetHome v1", (0 until extensions.length()).any { index ->
            val extension = extensions.getJSONObject(index)
            extension.getString("name") == "dulcetHome" && (0 until extension.getJSONArray("versions").length())
                .any { version -> extension.getJSONArray("versions").getInt(version) == 1 }
        })
        PlaylistPlayContextProof(compose).run()
    }
}

/** Reused by the required Home proof after its ordinary navigation and screenshot assertions. */
class PlaylistPlayContextProof(private val compose: ComposeTestRule) {
    fun run() {
        val probe = DisposableServerProbe.fromInstrumentation()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = if (isTelevision()) "androidtv" else "android"
        check(context.packageManager.hasSystemFeature("android.software.leanback") == isTelevision()) {
            "The configured device class must match the installed client"
        }
        val extensions = probe.call("getOpenSubsonicExtensions").getJSONArray("openSubsonicExtensions")
        check((0 until extensions.length()).any {
            val extension = extensions.getJSONObject(it)
            extension.getString("name") == "dulcetHome" && (0 until extension.getJSONArray("versions").length())
                .any { index -> extension.getJSONArray("versions").getInt(index) == 1 }
        }) { "The positive proof needs advertised dulcetHome v1" }
        val songId = probe.songId(DisposableServerProbe.CANARY_TITLE)
        val name = "Playlist context $platform ${System.currentTimeMillis()}"
        val playlistId = probe.callPairs("createPlaylist", listOf("name" to name, "songId" to songId))
            .getJSONObject("playlist").getString("id")
        fun serverHasPlaylist(): Boolean {
            val playlists = probe.call("getDulcetHome", mapOf("shelf" to "recentPlaylists", "size" to "50"))
                .getJSONObject("dulcetHome").optJSONArray("playlist") ?: return false
            return (0 until playlists.length()).any { playlists.getJSONObject(it).getString("id") == playlistId }
        }
        try {
            check(!serverHasPlaylist()) { "A fresh unread playlist must not already be recently played" }
            val beforeCount = probe.playCount(songId)
            ServerRelay(probe.baseUrl).use { relay ->
                PlaybackObserver(context).use { observer ->
                    awaitQueuedBroadcastsDelivered()
                    connectSavedAccount(context, probe, relay.url)
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                        ?: error("No production activity")
                    ActivityScenario.launch<Activity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                        awaitTag("listenNow.surface")
                        observer.bind()
                        activate("browse.open")
                        activate("library.view.playlists")
                        awaitTag("library.playlists")
                        compose.onNodeWithTag("library.playlists").performScrollToNode(hasText(name))
                        val row = compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("library.playlists")))
                        activate(row)
                        awaitTag("playlist.entry.0")
                        check(!serverHasPlaylist()) { "Viewing the real playlist must not record a listen" }
                        activate("playlist.entry.0")
                        requireMediaTimeAdvances(observer, "playlist-context-$platform")
                        await("real playlist submitted play in relay", 90_000) {
                            requests(relay).any { (path, query) ->
                                path.contains("/scrobble") && query["submission"] == "true" &&
                                    query["id"] == songId && query["dulcetContextType"] == "playlist" &&
                                    query["dulcetContextId"] == playlistId
                            }
                        }
                        await("playlist in independent real-server Home") { serverHasPlaylist() }
                        check(probe.playCount(songId) > beforeCount) { "No real submitted song play reached the server" }
                        val nowPlaying = requests(relay).filter { (path, query) -> path.contains("/scrobble") && query["submission"] == "false" }
                        check(nowPlaying.isNotEmpty()) { "The negative control must witness now-playing requests" }
                        check(nowPlaying.all { (_, query) -> "dulcetContextType" !in query && "dulcetContextId" !in query }) {
                            "Now-playing must carry no playlist context"
                        }
                        observer.stopPlayback()
                        if (isTelevision()) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        else activate("player.close")
                        activate("listenNow.open")
                        activate("listenNow.refresh")
                        // Anchor the lazy list on the logical shelf once. Repeated full-list
                        // searches can dispose an initially empty shelf before its live read arrives.
                        if (!isTelevision()) compose.onNodeWithTag("listenNow.surface")
                            .performScrollToIndex(if (observer.state().hasSession) 3 else 2)
                        var moves = 0
                        var lastMove = 0L
                        try {
                            compose.waitUntil("recent playlists shelf renders", 30_000) {
                                val found = compose.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag("listenNow.recentPlaylists")))
                                    .fetchSemanticsNodes().isNotEmpty()
                                // A TV must reveal lazy shelves by moving its focused control;
                                // forcing a scroll while Refresh keeps focus can scroll back.
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (!found && isTelevision() && moves < 12 && now - lastMove >= 500) {
                                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                                    compose.waitForIdle()
                                    moves++
                                    lastMove = now
                                }
                                found
                            }
                        } catch (failure: Throwable) {
                            println(compose.onRoot(useUnmergedTree = true).printToString())
                            throw failure
                        }
                        activate(compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("listenNow.recentPlaylists"))))
                        // TV renders the tagged title before its asynchronous playlist read finishes.
                        compose.waitUntil("Home opens the expected playlist", 30_000) {
                            compose.onAllNodes(hasTestTag("playlist.title") and hasText(name))
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        compose.onNodeWithTag("playlist.title").assertTextEquals(name)
                        println("PLAYLIST CONTEXT OBSERVED platform=$platform trigger=playlist-entry progressing-media=true submitted-context=true now-playing-context=false server-home=true rendered-home=true home-opens-playlist=true")
                    }
                }
            }
        } finally {
            probe.call("deletePlaylist", mapOf("id" to playlistId))
        }
    }

    private fun requests(relay: ServerRelay): List<Pair<String, Map<String, String>>> = relay.connections().flatMap { tap ->
        Regex("(?m)^GET ([^ ]+) HTTP/[^\\r\\n]+").findAll(tap.sent().toString(Charsets.US_ASCII)).map { match ->
            val uri = URI(match.groupValues[1])
            uri.path to uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
        }.toList()
    }

    private fun awaitTag(tag: String) = compose.waitUntil("$tag exists", 30_000) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun activate(tag: String) { awaitTag(tag); activate(compose.onNodeWithTag(tag)) }
    private fun activate(node: SemanticsNodeInteraction) {
        if (isTelevision()) {
            // Names are children of the focusable TV card; focus its merged semantics node.
            node.performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            node.assertIsFocused()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        } else {
            // Browse categories can lie outside the horizontal viewport on a phone.
            runCatching { node.performScrollTo() }
            node.assertIsDisplayed().performClick()
        }
        compose.waitForIdle()
    }
    private fun isTelevision(): Boolean =
        (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
}
