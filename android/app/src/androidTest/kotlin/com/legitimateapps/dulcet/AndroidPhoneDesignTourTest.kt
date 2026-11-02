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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
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
        probe.call("scrobble", mapOf("id" to probe.songId(TRACK), "submission" to "true"))
        awaitQueuedBroadcastsDelivered()
        val shots = DesignTourShots(label)
        ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use {
            shots.step("connection-empty") { awaitTag("account.server") && shots.settle() }
            shots.step("connection-error-input") {
                click("account.server")
                compose.onNodeWithTag("account.server").performTextClearance()
                compose.onNodeWithTag("account.server").performTextInput("not a server")
                click("account.submit")
                awaitTag("account.status.failed") && shots.settle()
            }
        }
        connectSavedAccount(context, probe)
        ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use { scenario ->
            shots.step("listen-now-saved") { awaitTag("listenNow.surface") && shots.settle(3_000) }
            shots.step("library-home") {
                click("library.open")
                awaitTag("library.home.0.item.0") && shots.settle(3_000)
            }
            shots.step("listen-now") {
                click("listenNow.open")
                run {
                    compose.onNodeWithTag("listenNow.surface").performScrollToNode(hasTestTag("listenNow.newest"))
                    awaitTag("listenNow.newest.item.0")
                } && shots.settle(2_000)
            }
            click("library.open")
            shots.step("library-albums") {
                click("library.view.albums")
                awaitTag("library.albums.item.0") && shots.settle(2_500)
            }
            shots.step("album") { openAlbum() && shots.settle() }
            shots.step("album-menu") { click("album.menu"); shots.settle() }
            back()
            shots.step("playlist-add") { click("album.addToPlaylist"); awaitTag("playlists.add") && shots.settle() }
            back()
            shots.step("pinned") {
                click("album.pin"); click("library.open")
                compose.onNodeWithTag("library.pinned.item.0").performScrollTo()
                awaitTag("library.pinned.item.0") && shots.settle()
            }
            shots.step("album-sort") {
                click("library.open"); click("library.view.albums"); click("library.albums.sort"); shots.settle()
            }
            back()
            shots.step("recently-added") {
                click("library.albums.sort"); click("library.albums.sort.newest"); awaitTag("library.albums.item.0") && shots.settle()
            }
            shots.step("recently-played") {
                click("library.albums.sort"); click("library.albums.sort.recent"); awaitTag("library.albums") && shots.settle()
            }
            shots.step("most-played") {
                click("library.albums.sort"); click("library.albums.sort.frequent"); awaitTag("library.albums") && shots.settle()
            }
            shots.step("songs") { click("library.view.songs"); awaitTag("library.songs") && shots.settle() }
            shots.step("downloads-empty") { click("library.view.downloaded"); awaitTag("library.downloaded") && shots.settle() }
            shots.step("genres") { click("library.view.genres"); awaitTag("library.genres.item.0") && shots.settle() }
            shots.step("genre") { click("library.genres.item.0"); awaitTag("genre.title") && shots.settle() }
            back()
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
            shots.step("playlist-create") { click("library.playlists.new"); awaitTag("library.playlists.name") && shots.settle() }
            shots.step("playlist-create", "create-valid") {
                compose.onNodeWithTag("library.playlists.name").performTextInput("A new fixture playlist")
                shots.settle()
            }
            // Name editors are inline and are dismissed with their Cancel action.
            runCatching { click("library.playlists.name.cancel") }
            shots.step("playlist") {
                compose.onNodeWithTag("library.playlists").performScrollToNode(hasText(PLAYLIST))
                compose.onNode(hasText(PLAYLIST) and hasAnyAncestor(hasTestTag("library.playlists"))).performClick()
                // The page paints from the cache and re-reads at once; let the entries land.
                awaitTag("playlist.title") && awaitTag("playlist.entry.0") && shots.settle(2_500)
            }
            shots.step("playlist-edit") { click("playlist.edit"); awaitTag("playlist.entry.0.remove") && shots.settle() }
            runCatching { click("playlist.edit") }
            shots.step("playlist-rename") { click("playlist.menu"); click("playlist.rename"); awaitTag("playlist.name") && shots.settle() }
            runCatching { click("playlist.name.cancel") }
            shots.step("playlist-delete-confirmation") { click("playlist.menu"); click("playlist.delete"); awaitTag("playlist.delete.confirm") && shots.settle() }
            back()
            // The playlist's second entry carries the corpus's synced lyrics, so the player and the
            // lyrics sheet are shot with lines that light up.
            shots.step("now-playing") {
                click("playlist.entry.1")
                // The track's title IN THE PLAYER: the page beneath can match a plain text wait while
                // a cold service is still publishing the queue. Then prove it held to the shot.
                awaitTag("player.full") && awaitPlayerTitle() && shots.settle(2_000) && awaitPlayerTitle() && shots.settle(2_000)
            }
            // Freeze the short fixture track before examining menus; auto-advance changes queue indices.
            runCatching { click("player.playpause") }
            shots.step("up-next") {
                click("player.queue")
                awaitTag("player.upnext") && shots.settle()
            }
            shots.step("listening-session") { awaitTag("listening.autoplay") && shots.settle() }
            shots.step("sleep-timer") { click("listening.sleep"); shots.settle() }
            shots.step("listening-session", "sleep-active") { click("listening.sleep.15m"); shots.settle() }
            shots.step("crossfade") { click("listening.crossfade"); shots.settle() }
            shots.step("listening-session", "crossfade-active") { click("listening.crossfade.3"); shots.settle() }
            shots.step("queue-menu") { clickUpcomingMenu(); awaitTag("player.upnext") && shots.settle() }
            back()
            // Back out of the sheet; the player stays open for the lyrics.
            repeat(1) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle(); shots.settle(600) }
            shots.step("lyrics") {
                click("player.lyrics")
                awaitTag("player.lyrics.sheet") && awaitTag("lyrics.line.0") && shots.settle(1_500)
            }
            // Back out of the sheet and the player to the page.
            repeat(2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle(); shots.settle(600) }
            shots.step("mini-player") { awaitTag("player.mini") && shots.settle() }
            shots.step("search-landing") { click("search.open"); awaitTag("search.query") && shots.settle() }
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
            shots.step("search-empty") {
                compose.onNodeWithTag("search.query").performTextClearance()
                compose.onNodeWithTag("search.query").performTextInput("zzzz-no-fixture-match")
                awaitTag("search.empty") && shots.settle()
            }
            back()
            shots.step("search-recents") {
                compose.onNodeWithTag("search.query").performTextClearance()
                awaitTag("search.recent.0") && shots.settle()
            }
            shots.step("account") { click("account.open"); awaitTag("account.dialog") && shots.settle() }
            shots.step("server-list") { awaitTag("account.add-server") && shots.settle() }
            shots.step("streaming-quality") { click("quality.unmetered"); awaitTag("quality.unmetered.original") && shots.settle() }
            back()
            shots.step("storage") { compose.onNodeWithTag("offline.cache.clear").performScrollTo(); shots.settle() }
            shots.step("storage", "automatic-on") { click("offline.automatic"); shots.settle() }
            shots.step("storage", "budget") { click("offline.cache.budget.268435456"); shots.settle() }
            shots.step("storage", "clear") { click("offline.cache.clear"); shots.settle() }
            shots.step("signout-confirmation") { click("account.signout"); awaitTag("signout.confirm") && shots.settle() }
            runCatching { click("signout.stay") }
            shots.step("server-add") { click("account.open"); click("account.add-server"); awaitTag("account.server") && shots.settle() }
        }
        check(shots.unreached.isEmpty()) { "The design tour could not reach: ${shots.unreached}" }
        println("DESIGN TOUR done label=$label")
    }

    @Test fun supplementalTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel") ?: "phone"
        val probe = DisposableServerProbe.fromInstrumentation()
        retrying(3) { starFixtures(probe); seedPlaylist(probe) }
        probe.call("scrobble", mapOf("id" to probe.songId(TRACK), "submission" to "true"))
        connectSavedAccount(context, probe)
        val shots = DesignTourShots(label + "-supplement")
        ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use {
            shots.step("listen-now-saved") { awaitTag("listenNow.surface") && shots.settle() }
            click("library.open"); click("library.view.albums"); check(awaitTag("library.albums.item.0"))
            shots.step("listen-now") {
                click("listenNow.open")
                compose.onNodeWithTag("listenNow.surface").performScrollToNode(hasTestTag("listenNow.newest"))
                awaitTag("listenNow.newest.item.0") && shots.settle()
            }
            click("library.open"); click("library.view.albums")
            shots.step("recently-played") { click("library.albums.sort"); click("library.albums.sort.recent"); awaitTag("library.albums.item.0") && shots.settle() }
            shots.step("most-played") { click("library.albums.sort"); click("library.albums.sort.frequent"); awaitTag("library.albums.item.0") && shots.settle() }
            shots.step("listening-session") {
                if (!openAlbum()) return@step false
                click("album.track.0"); check(awaitTag("player.full")); click("player.playpause"); click("player.queue")
                awaitTag("listening.autoplay") && shots.settle()
            }
            shots.step("sleep-timer") { click("listening.sleep"); shots.settle() }
            back()
            shots.step("crossfade") { click("listening.crossfade"); shots.settle() }
        }
        check(shots.unreached.isEmpty()) { "Unreached supplementary states: ${shots.unreached}" }
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

    private fun clickUpcomingMenu() {
        val matcher = SemanticsMatcher("an upcoming entry menu") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }
                .matches(Regex("player\\.upnext\\.\\d+\\.menu"))
        }
        compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.let { node ->
            click(node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag])
        } ?: error("No upcoming entry menu is visible")
    }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        Thread.sleep(600)
    }

    private fun click(tag: String) {
        awaitTag(tag)
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = 30_000): Boolean = runCatching {
        compose.waitUntil("$tag on screen", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    /** The playing track's title as the player itself shows it, not a row on the page beneath. */
    private fun awaitPlayerTitle(timeoutMillis: Long = 30_000): Boolean = runCatching {
        compose.waitUntil("player title", timeoutMillis) {
            compose.onAllNodes(hasText(LYRICS_TRACK) and hasAnyAncestor(hasTestTag("player.full")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
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
