package com.legitimateapps.dulcet

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.DesignTourShots
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.awaitQueuedBroadcastsDelivered
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screenshot tour of the phone app for design review; see [DesignTourShots]. It visits the
 * library's Home, Albums, an album, Artists, an artist, Favourites, Playlists, a playlist, Now
 * Playing while playing the track that has synced lyrics, Up Next, the lyrics sheet, and Search
 * with results, against the disposable server, and fails naming every screen it could not reach.
 * `tools/design-tour-android` runs it and collects the shots.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPhoneDesignTourTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun designTour() {
        if (!DesignTourShots.requested()) {
            println("DESIGN TOUR not requested; it runs only from tools/design-tour-android")
            return
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val label = InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel") ?: "phone"
        val probe = DisposableServerProbe.fromInstrumentation()
        // A freshly booted emulator's first request to the host can be reset; the tour retries it.
        retrying(3) { starFixtures(probe); seedPlaylist(probe) }
        awaitQueuedBroadcastsDelivered()
        connectSavedAccount(context, probe)
        val shots = DesignTourShots(label)
        ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use { scenario ->
            shots.step("library-home") {
                click("library.open")
                awaitTag("library.home.0.item.0") && shots.settle(3_000)
            }
            shots.step("library-albums") {
                click("library.view.albums")
                awaitTag("library.albums.item.0") && shots.settle(2_500)
            }
            shots.step("album") { openAlbum() && shots.settle() }
            shots.step("library-artists") {
                click("library.open")
                click("library.view.artists")
                awaitTag("library.artists.item.0") && shots.settle()
            }
            shots.step("artist") {
                compose.onNodeWithTag("library.artists").performScrollToNode(hasText(ARTIST))
                compose.onNode(hasText(ARTIST) and hasAnyAncestor(hasTestTag("library.artists"))).performClick()
                awaitTag("artist.album.0") && shots.settle()
            }
            shots.step("favourites") {
                click("library.open")
                click("library.view.favourites")
                awaitTag("library.favourites.album.0") && shots.settle()
            }
            shots.step("playlists") {
                click("library.open")
                click("library.view.playlists")
                awaitTag("library.playlists.item.0") && shots.settle()
            }
            shots.step("playlist") {
                compose.onNodeWithTag("library.playlists").performScrollToNode(hasText(PLAYLIST))
                compose.onNode(hasText(PLAYLIST) and hasAnyAncestor(hasTestTag("library.playlists"))).performClick()
                awaitTag("playlist.title") && shots.settle()
            }
            // The playlist's second entry carries the corpus's synced lyrics, so the player and the
            // lyrics sheet are shot with lines that light up.
            shots.step("now-playing") {
                click("playlist.entry.1")
                awaitTag("player.full") && awaitText(LYRICS_TRACK) && shots.settle(3_000)
            }
            shots.step("up-next") {
                click("player.queue")
                awaitTag("player.upnext.0") && shots.settle()
            }
            // Back out of the sheet; the player stays open for the lyrics.
            repeat(1) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle(); shots.settle(600) }
            shots.step("lyrics") {
                click("player.lyrics")
                awaitTag("player.lyrics.sheet") && awaitTag("lyrics.line.0") && shots.settle(1_500)
            }
            // Back out of the sheet and the player to the page.
            repeat(2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle(); shots.settle(600) }
            shots.step("search") {
                click("search.open")
                click("search.query")
                compose.onNodeWithTag("search.query").performTextInput("Threshold")
                val found = awaitTag("search.result.0")
                // The keyboard's insets arrive after the text does; wait for it, then close it so the
                // results are the shot, and prove it went.
                val shown = waitFor(5_000) { keyboardShown(scenario) }
                if (shown) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                found && waitFor(5_000) { !keyboardShown(scenario) } && shots.settle()
            }
        }
        check(shots.unreached.isEmpty()) { "The design tour could not reach: ${shots.unreached}" }
        println("DESIGN TOUR done label=$label")
    }

    private fun openAlbum(): Boolean {
        click("library.open")
        click("library.view.albums")
        if (!awaitTag("library.albums.item.0")) return false
        compose.onNodeWithTag("library.albums").performScrollToNode(hasText(ALBUM))
        compose.onNode(hasText(ALBUM) and hasAnyAncestor(hasTestTag("library.albums"))).performClick()
        return awaitTag("album.track.0")
    }

    /** One favourite album, artist and song, so Favourites shows each of its sections. */
    private fun retrying(attempts: Int, block: () -> Unit) {
        repeat(attempts - 1) { runCatching(block).onSuccess { return }; Thread.sleep(2_000) }
        block()
    }

    private fun starFixtures(probe: DisposableServerProbe) {
        val albums = probe.call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "50"))
            .getJSONObject("albumList2").getJSONArray("album")
        val album = (0 until albums.length()).map(albums::getJSONObject).first { it.getString("name") == ALBUM }
        probe.call("star", mapOf("albumId" to album.getString("id")))
        probe.call("star", mapOf("artistId" to album.getString("artistId")))
        probe.call("star", mapOf("id" to probe.songId(TRACK)))
    }

    /** One playlist the tours open: the playing track and the one with synced lyrics. Idempotent. */
    private fun seedPlaylist(probe: DisposableServerProbe) {
        val existing = probe.call("getPlaylists").getJSONObject("playlists").optJSONArray("playlist")
        if (existing != null && (0 until existing.length()).any { existing.getJSONObject(it).getString("name") == PLAYLIST }) return
        probe.callPairs("createPlaylist", listOf(
            "name" to PLAYLIST,
            "songId" to probe.songId(TRACK),
            "songId" to probe.songId(LYRICS_TRACK),
        ))
    }

    private fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = 30_000): Boolean = runCatching {
        compose.waitUntil("$tag on screen", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private fun awaitText(text: String, timeoutMillis: Long = 30_000): Boolean = runCatching {
        compose.waitUntil("$text on screen", timeoutMillis) {
            compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private fun waitFor(millis: Long, condition: () -> Boolean): Boolean = runCatching {
        compose.waitUntil("condition", millis) { condition() }
    }.isSuccess

    private fun keyboardShown(scenario: ActivityScenario<MainActivity>): Boolean {
        var shown = false
        scenario.onActivity { activity ->
            shown = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private companion object {
        const val ALBUM = "Threshold Boundary"
        const val ARTIST = "Dulcet Fixtures"
        const val TRACK = "Twenty Nine Seconds"
        const val LYRICS_TRACK = "Thirty One Seconds"
        const val PLAYLIST = "Dulcet Tour Mix"
    }
}
