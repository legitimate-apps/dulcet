package com.legitimateapps.dulcet.tv

import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import com.legitimateapps.dulcet.emulator.DesignTourFixtures
import com.legitimateapps.dulcet.emulator.DesignTourShots
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.await
import com.legitimateapps.dulcet.emulator.awaitQueuedBroadcastsDelivered
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screenshot tour of the TV app for design review; see `DesignTourShots`. Moves the way a remote
 * does -- focus, then the centre key -- so every shot shows the focus a person would see: the home
 * screen at launch (the library), Library, Albums, an album, Artists, an artist, Favourites, Playlists, a
 * playlist, Now Playing with Up Next while playing, the lyrics panel, the account screen, and Search
 * with results -- last, so a failure there cannot cost the others. `tools/design-tour-android` runs it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvDesignTourTest {
    @get:Rule val compose = createEmptyComposeRule()
    init {
        com.legitimateapps.dulcet.emulator.DesignTourShots.advanceClock = { compose.mainClock.advanceTimeBy(it) }
        com.legitimateapps.dulcet.emulator.DesignTourShots.awaitComposeIdle = { compose.waitForIdle() }
    }

    @Test fun designTour() {
        if (!DesignTourShots.requested()) {
            println("DESIGN TOUR not requested; it runs only from tools/design-tour-android")
            return
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) { "The TV tour needs a TV device" }
        val label = InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel") ?: "tv"
        val probe = DisposableServerProbe.fromInstrumentation()
        val shots = DesignTourShots(label)
        val launch = Intent(Intent.ACTION_MAIN).setClassName(context, TvSearchActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<TvSearchActivity>(launch).use {
            shots.step("connection-empty") { awaitTag("tv.connect.server") && shots.settle() }
            shots.step("connection-error-input") { select("tv.connect.submit") && awaitTag("tv.connect.status") && shots.settle() }
        }
        ActivityScenario.launch<TvPlaybackActivity>(Intent(context, TvPlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("now-playing-empty") { awaitTag("tv.player.connect") && focusTag("tv.player.connect") && shots.settle() }
        }
        // A freshly booted emulator's first request to the host can be reset; the tour retries it.
        retrying(3) { starFixtures(probe); seedPlaylist(probe) }
        probe.call("scrobble", mapOf("id" to probe.songId(TRACK), "submission" to "true"))
        seedHistory(probe)
        awaitQueuedBroadcastsDelivered()
        println("DESIGN TOUR TV connecting disposable account")
        connectSavedAccount(context, probe)
        println("DESIGN TOUR TV account connected")
        ActivityScenario.launch<TvPlaybackActivity>(Intent(context, TvPlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("now-playing-empty", "signed-in-empty") {
                // A player opened cold on a saved account says it has not reconnected until the person asks; the
                // state to photograph is the connected one.
                awaitTag("tv.player.browse") &&
                    (!awaitTag("tv.player.reconnect", 3_000) || (select("tv.player.reconnect") && awaitAbsent("tv.player.reconnect"))) &&
                    focusTag("tv.player.browse") && shots.settle()
            }
        }
        println("DESIGN TOUR TV launching search activity")
        ActivityScenario.launch<TvSearchActivity>(launch).use {
            // Roots own different destinations: Library is personal, Browse is the catalog.
            shots.step("home") {
                awaitTag("listenNow.recent.item.0") && awaitAbsent("library.saved") &&
                    focusTag("listenNow.recent.item.0") && awaitNotLoading("listenNow.recent") && shots.settle(2_000)
            }
            shots.step("library") { select("library.open") && awaitTag("library.nav") && awaitAbsent("library.saved") && shots.settle() }
            shots.step("albums") { select("library.view.albums") && awaitTag("library.albums.item.0") && focusTag("library.albums.item.0") && shots.settle(2_000) }
            shots.step("recently-added") {
                select("browse.open") && awaitTag("library.home.0.item.0") && select("library.home.0.seeAll") &&
                    awaitTag("library.recentlyAdded.item.0") && focusTag("library.recentlyAdded.item.0") && shots.settle(2_000)
            }
            shots.step("recently-played") {
                select("listenNow.open") && run {
                    compose.onNodeWithTag("listenNow.surface").performScrollToNode(hasTestTag("listenNow.recent.seeAll"))
                    select("listenNow.recent.seeAll")
                } && awaitTag("library.recentlyPlayed.item.0") && focusTag("library.recentlyPlayed.item.0") && shots.settle(2_000)
            }
            shots.step("recently-played", "return-to-tile") { returnToTile("library.recentlyPlayed.item.1") && shots.settle() }
            shots.step("most-played") { select("library.view.mostPlayed") && awaitTag("library.mostPlayed.item.0") && focusTag("library.mostPlayed.item.0") && shots.settle(2_000) }
            shots.step("most-played", "return-to-tile") { returnToTile("library.mostPlayed.item.1") && shots.settle() }
            check(select("library.view.albums"))
            check(chooseAlbumSort("alphabeticalbyname"))
            shots.step("album-menu", "grid-context") { longSelect("library.albums.item.0") && awaitTag("library.albums.item.0.actions") && shots.settle() }
            back(1)
            shots.step("album-menu") {
                openCard("library.albums", ALBUM) && awaitDetail("album", ALBUM, "album.track.0") &&
                    select("album.more") && awaitTag("album.menu") && dialogFocused() && shots.settle()
            }
            // The menu is capped to the screen and scrolls: the remote reaches its last row, Remove download, set apart
            // below Refresh once the album holds a download, and Refresh before then.
            shots.step("album-menu", "scrolled") {
                var steps = 0
                while (steps++ < 12 && !awaitFocusedNow("album.download.remove")) remote(KeyEvent.KEYCODE_DPAD_DOWN)
                (awaitFocusedNow("album.download.remove") || awaitFocusedNow("album.refresh")) && shots.settle()
            }
            back(1)
            shots.step("album") { awaitDetail("album", ALBUM, "album.track.0") && focusTag("album.play") && shots.settle(2_000) }
            shots.step("playlist-add") { select("album.more") && select("album.addToPlaylist") && awaitTag("playlists.add") && dialogFocused() && shots.settle() }
            shots.step("playlist-create", "create-valid") { select("playlists.add.new") && awaitTag("playlists.add.name") && shots.settle() }
            select("playlists.add.name.cancel")
            back(1)
            shots.step("pinned") {
                select("album.more") && select("album.pin") && select("library.open") && awaitTag("library.view.pinned") &&
                    select("library.view.pinned") && awaitTag("library.pinned") && focusTag("library.pinned.item.0") && shots.settle(2_000)
            }
            // The server's artist pictures arrive after the cards: photograph once they have had time to.
            shots.step("artists") { select("library.view.artists") && focusTag("library.artists.item.0") && shots.settle(2_000) }
            shots.step("artist") { openCard("library.artists", ARTIST) && awaitText("artist.title", ARTIST) && awaitNotLoading("artist") && shots.settle(2_000) }
            back(2)
            shots.step("genres") { select("library.view.genres") && focusTag("library.genres.item.0") && shots.settle() }
            shots.step("genre") { select("library.genres.item.0") && awaitTag("genre.track.0") && awaitNotLoading("genre") && shots.settle() }
            back(2)
            shots.step("songs") { select("library.view.songs") && focusTag("library.songs.track.0") && shots.settle() }
            back(1)
            shots.step("downloads-populated", "completed") { select("library.view.downloaded") && focusTag("library.downloaded.track.0") && shots.settle() }
            back(1)
            shots.step("favourites") {
                select("library.view.favourites") && awaitTag("library.favourites.album.0") &&
                    focusTag("library.favourites.album.0") && awaitNotLoading("library.favourites") && shots.settle(2_000)
            }
            back(1)
            shots.step("playlists") { select("library.view.playlists") && focusTag("library.playlists.item.0") && shots.settle() }
            shots.step("playlist") { openCard("library.playlists", PLAYLIST) && awaitDetail("playlist", PLAYLIST, "playlist.entry.0") && shots.settle(2_000) }
            shots.step("playlist-delete-confirmation") { select("playlist.more") && select("playlist.delete") && awaitTag("playlist.delete.dialog") && dialogFocused() && shots.settle() }
            back(1)
            back(2)
            shots.step("now-playing") {
                select("library.view.albums") && openCard("library.albums", ALBUM) && awaitFocused("album.play")
                    && run { remote(KeyEvent.KEYCODE_DPAD_CENTER); true }
                    && runCatching { await("the TV's Now playing in front", 30_000) { resumed() == TvPlaybackActivity::class.java.name } }.isSuccess
                    && awaitTag("tv.player.title") && awaitTag("tv.player.queue") && awaitTag("tv.player.upnext.preview.0") && pinPosition("0:08") && shots.settle(2_500)
            }
            // Up Next is its own destination from the transport's Queue button; its rows are what
            // follows the playing track. Centre on the focused first row jumps to the track with the
            // corpus's synced lyrics, and Back returns focus to the Queue button.
            shots.step("up-next") {
                select("tv.player.queue") && awaitTag("tv.player.upnext.0") && focusTag("tv.player.upnext.0") && shots.settle()
            }
            shots.step("lyrics") {
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                shots.settle(1_500)
                    && run { remote(KeyEvent.KEYCODE_BACK); true }
                    && awaitFocused("tv.player.queue")
                    && focusTag("tv.player.lyrics")
                    && run { remote(KeyEvent.KEYCODE_DPAD_CENTER); true }
                    // A rendered line, not the panel's loading ellipsis.
                    && awaitTag("tv.player.lyrics.panel", 10_000) && awaitTag("tv.player.lyrics.line.0", 10_000) && pinPosition("0:07") && shots.settle(200)
            }
            shots.step("listening-session") { select("tv.listening.open") && awaitTag("tv.listening.sleep") && shots.settle() }
            shots.step("sleep-timer") { select("tv.listening.sleep") && awaitTag("tv.listening.sleep.cancel") && dialogFocused() && shots.settle() }
            shots.step("listening-session", "sleep-active") { select("tv.listening.sleep.15m") && shots.settle() }
            shots.step("crossfade") { select("tv.listening.crossfade") && awaitTag("tv.listening.crossfade.0") && dialogFocused() && shots.settle() }
            shots.step("listening-session", "crossfade-active") { select("tv.listening.crossfade.3") && shots.settle() }
            back(1)
            back(1)
            shots.step("account") {
                select("tv.account.open") && awaitTag("account.surface") && shots.settle()
            }
            shots.step("server-list") { awaitTag("tv.account.add-server") && shots.settle() }
            shots.step("streaming-quality") { select("quality.unmetered") && awaitTag("quality.unmetered.menu") && dialogFocused() && shots.settle() }
            // The dialog opens on its selected row; the frame the focus treatment is judged on is the next one, not selected.
            shots.step("streaming-quality", "focus-unselected") {
                // The page's own row keeps its focused flag behind a dialog: only the dialog's rows answer this.
                fun inDialog() = focusedTag { it.startsWith("quality.unmetered.") }
                val opened = inDialog()
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                if (inDialog() == opened) remote(KeyEvent.KEYCODE_DPAD_UP)
                val now = inDialog()
                mark("streaming-quality focus moved from $opened to $now")
                now != null && now != opened && now.startsWith("quality.unmetered.") && shots.settle()
            }
            back(1)
            shots.step("streaming-quality", "unmetered") { select("quality.unmetered") && select("quality.unmetered.original") && shots.settle() }
            shots.step("streaming-quality", "metered") { select("quality.metered") && awaitTag("quality.metered.menu") && dialogFocused() && shots.settle() }
            select("quality.metered.original")
            shots.step("storage") {
                select("account.storage") && awaitTag("offline.storage") && shots.settle()
            }
            markStorageFoot("at rest")
            shots.step("storage", "automatic-off") {
                // The setting is written off the main thread: the frame is of the page once it has turned off.
                select("offline.automatic") && runCatching {
                    compose.waitUntil("automatic downloads off", 15_000) {
                        compose.onAllNodes(hasTestTag("offline.automatic.progress"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
                    }
                }.isSuccess && shots.settle()
            }
            shots.step("storage", "usage") {
                compose.onNodeWithTag("account.storage.surface").performScrollToNode(hasTestTag("offline.download.usage")); shots.settle()
            }
            shots.step("storage", "budget") { select("offline.cache.limit") && awaitTag("offline.cache.budget.menu") && dialogFocused() && shots.settle() }
            check(select("offline.cache.budget.268435456"))
            shots.step("storage", "clear") { select("offline.cache.clear") && awaitText("offline.cache.clear", "Cache cleared", substring = true) && shots.settle() }
            markStorageFoot("after clear")
            AndroidDownloads.controller(context)?.offlineStats?.value.let { stats ->
                mark("storage after clear cacheBytes=${stats?.cacheBytes} artworkBytes=${stats?.artworkBytes}")
            }
            // Back from Downloads & storage to Account, where Sign out is.
            back(1)
            // The foot of a long settings page: what a person sees with the remote on its last row.
            shots.step("account", "last-row") { focusTag("tv.account.signout") && shots.settle() }
            shots.step("signout-confirmation") { select("tv.account.signout") && awaitTag("signout.confirm") && dialogFocused() && shots.settle() }
            back(1)
            back(1)
            shots.step("search-landing") { select("search.open") && awaitTag("search.query") && shots.settle() }
            shots.step("search") {
                mark("search: in front") && runCatching {
                    await("search in front", 15_000) { resumed() == TvSearchActivity::class.java.name }
                }.isSuccess
                    && mark("search: open") && select("search.open")
                    && mark("search: field") && focusTag("search.query")
                    && mark("search: type") && run {
                        // As a remote does: the centre key asks for the keyboard, then real key events.
                        remote(KeyEvent.KEYCODE_DPAD_CENTER)
                        InstrumentationRegistry.getInstrumentation().sendStringSync("Threshold")
                        compose.waitForIdle(); true
                    }
                    && mark("search: results") && awaitTag("search.result.0")
                    && mark("search: close keyboard") && run { remote(KeyEvent.KEYCODE_BACK); focusTag("search.result.0") }
                    && shots.settle()
            }
            shots.step("server-add") { select("tv.account.open") && select("tv.account.add-server") && awaitTag("tv.connect.server") && shots.settle() }
        }
        check(shots.unreached.isEmpty()) { "The design tour could not reach: ${shots.unreached}" }
        println("DESIGN TOUR done label=$label")
    }

    /**
     * The dialogs' focus, measured: each opens with no help from the tour, then is photographed at once,
     * six seconds later, and after the remote moves down and up. The log says which window has focus.
     */
    @Test fun dialogFocusTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        retrying(3) { starFixtures(probe); seedPlaylist(probe) }
        connectSavedAccount(context, probe)
        val shots = DesignTourShots("tv-dialogs")
        fun windows(name: String) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                println("DESIGN TOUR window $name ${view.javaClass.simpleName} windowFocus=${view.hasWindowFocus()} touchMode=${view.isInTouchMode} focused=${view.findFocus()?.javaClass?.simpleName}")
            }
        }
        fun probeDialog(name: String, reach: () -> Boolean) {
            shots.step(name) { reach() && shots.settle() }
            windows(name)
            Thread.sleep(6_000); shots.shoot("$name-later")
            remote(KeyEvent.KEYCODE_DPAD_DOWN); shots.settle(); shots.shoot("$name-down")
            remote(KeyEvent.KEYCODE_DPAD_UP); shots.settle(); shots.shoot("$name-up")
            windows("$name-after")
        }
        ActivityScenario.launch<TvPlaybackActivity>(Intent(context, TvPlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            probeDialog("player-empty") { awaitTag("tv.player.browse") }
        }
        ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            check(select("library.view.albums"))
            check(openCard("library.albums", ALBUM))
            check(awaitDetail("album", ALBUM, "album.track.0"))
            probeDialog("album-menu") { select("album.more") && awaitTag("album.menu") && dialogFocused() }
            back(1)
            check(awaitDetail("album", ALBUM, "album.track.0"))
            probeDialog("playlist-add") { select("album.more") && select("album.addToPlaylist") && awaitTag("playlists.add") && dialogFocused() }
            back(1)
            check(select("library.view.playlists"))
            check(openCard("library.playlists", PLAYLIST))
            check(awaitDetail("playlist", PLAYLIST, "playlist.entry.0"))
            probeDialog("playlist-delete") { select("playlist.more") && select("playlist.delete") && awaitTag("playlist.delete.dialog") && dialogFocused() }
            back(1)
            check(select("tv.account.open"))
            check(awaitTag("account.surface"))
            probeDialog("signout") { select("tv.account.signout") && awaitTag("signout.confirm") && dialogFocused() }
            back(1)
        }
        check(shots.unreached.isEmpty()) { "Unreached TV dialogs: ${shots.unreached}" }
    }

    @Test fun detailsTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        retrying(3) { starFixtures(probe); DesignTourFixtures.seedLongPlaylist(probe) }
        // The server may split this title into several albums; the plain one is the one holding the probe.
        val plainSongs = probe.albumIds("Dulcet Conformance").map {
            probe.call("getAlbum", mapOf("id" to it)).getJSONObject("album").getJSONArray("song")
        }.first { songs -> (0 until songs.length()).any { songs.getJSONObject(it).getString("title") == "Ogg Probe" } }
        val plainIndex = (0 until plainSongs.length()).first { plainSongs.getJSONObject(it).getString("title") == "Ogg Probe" }
        connectSavedAccount(context, probe)
        val shots = DesignTourShots("tv-details")
        ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("playlist", "long-name") {
                select("library.view.playlists") && openCard("library.playlists", DesignTourFixtures.LONG_PLAYLIST) && awaitDetail("playlist", DesignTourFixtures.LONG_PLAYLIST, "playlist.entry.0") && shots.settle(2_000)
            }
            shots.step("playlist-delete-confirmation", "cancel") {
                if (!select("playlist.more") || !select("playlist.delete") || !awaitTag("playlist.delete.dialog")) return@step false
                back(1); awaitDetail("playlist", DesignTourFixtures.LONG_PLAYLIST, "playlist.entry.0") && shots.settle()
            }
            shots.step("songs", "filter") {
                select("library.view.songs") && select("library.songs.filter.favorites") && awaitTag("library.songs.track.0") && shots.settle()
            }
            shots.step("album", "long-title") {
                select("library.view.albums") && openCard("library.albums", DesignTourFixtures.LONG_ALBUM) && awaitDetail("album", DesignTourFixtures.LONG_ALBUM, "album.track.0") && shots.settle(2_000)
            }
            shots.step("now-playing", "long-title") {
                select("album.play") && awaitTag("tv.player.title") && runCatching {
                    compose.waitUntil("long TV metadata", 10_000) { compose.onAllNodes(hasText(DesignTourFixtures.LONG_TRACK)).fetchSemanticsNodes().isNotEmpty() }
                }.isSuccess && shots.settle()
            }
            shots.step("now-playing", "paused") {
                select("tv.player.playpause") && runCatching { compose.waitUntil("TV paused", 10_000) {
                    compose.onAllNodes(hasTestTag("tv.player.playpause") and hasContentDescription(context.getString(R.string.tv_player_play))).fetchSemanticsNodes().isNotEmpty()
                } }.isSuccess && shots.settle()
            }
            shots.step("lyrics", "absent") {
                select("tv.player.lyrics") && awaitTag("tv.player.lyrics.statement") && runCatching { compose.waitUntil("TV no lyrics", 10_000) {
                    compose.onAllNodes(hasText(context.getString(com.legitimateapps.dulcet.shared.R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty()
                } }.isSuccess && shots.settle()
            }
            back(1)
            shots.step("lyrics", "unsynced") {
                if (!select("library.view.albums") || !openCard("library.albums", "Dulcet Conformance")) return@step false
                // The destination exists before its asynchronous album publication arrives.
                // Wait for this album's header before searching its virtualized track rows.
                compose.waitUntil("plain album loaded", 30_000) {
                    compose.onAllNodes(hasTestTag("album.title") and hasText("Dulcet Conformance"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // album.surface is the split page's Row, not its list; select() below scrolls to the row itself.
                runCatching { compose.onNodeWithTag("album.surface").performScrollToNode(hasTestTag("album.track.$plainIndex")) }
                    .onFailure { runCatching { compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasTestTag("album.track.$plainIndex")) } }
                select("album.track.$plainIndex") && awaitTag("tv.player.title") && select("tv.player.playpause") && select("tv.player.lyrics") &&
                    awaitTag("tv.player.lyrics.line.0") && runCatching { compose.waitUntil("TV plain lyrics", 10_000) {
                        compose.onAllNodes(hasText("Dulcet plain sidecar line one", substring = true)).fetchSemanticsNodes().isNotEmpty()
                    } }.isSuccess && shots.settle()
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached TV detail states: ${shots.unreached}" }
    }

    @Test fun connectionStatesTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val shots = DesignTourShots("tv-connection")
        fun field(tag: String, value: String) = editField(tag, value)
        fun failure(resource: Int): Boolean {
            val title = context.getString(resource)
            val shown = runCatching { compose.waitUntil("connection failure $title", 30_000) {
                compose.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty()
            } }.isSuccess
            if (shown) compose.onNodeWithTag("tv.connect.status").performScrollTo()
            return shown && shots.settle()
        }
        com.legitimateapps.dulcet.emulator.ServerRelay(probe.baseUrl).use { relay ->
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                check(awaitTag("tv.connect.server"))
                field("tv.connect.server", relay.url)
                field("tv.connect.username", DisposableServerProbe.USER)
                field("tv.connect.password", DisposableServerProbe.PASSWORD)
                shots.step("connection-empty", "filled") { shots.settle() }
                shots.step("connection-error-input", "security") { select("tv.connect.submit") && failure(com.legitimateapps.dulcet.shared.R.string.error_security_title) }
                select("tv.connect.allow-local-http")
                field("tv.connect.password", "incorrect-disposable-password")
                shots.step("connection-error-input", "auth") { select("tv.connect.submit") && failure(com.legitimateapps.dulcet.shared.R.string.error_auth_title) }
                field("tv.connect.password", DisposableServerProbe.PASSWORD)
                relay.cut()
                shots.step("connection-error-input", "transport") { select("tv.connect.submit") && failure(com.legitimateapps.dulcet.shared.R.string.error_unreachable_title) }
                relay.makeReachable(); relay.hold()
                shots.step("connection-empty", "connecting") {
                    if (!select("tv.connect.submit")) return@step false
                    val shown = runCatching { compose.waitUntil("TV connecting", 10_000) {
                        compose.onAllNodes(hasText(context.getString(R.string.tv_connecting))).fetchSemanticsNodes().isNotEmpty()
                    } }.isSuccess
                    if (shown) compose.onNodeWithTag("tv.connect.status").performScrollTo()
                    shown && shots.settle()
                }
                select("tv.connect.submit")
            }
        }
        check(com.legitimateapps.dulcet.AndroidAccountCredentialStore(context).activeAccountId() == null) { "Cancelled TV design connection saved an account" }
        check(shots.unreached.isEmpty()) { "Unreached TV connection states: ${shots.unreached}" }
    }

    @Test fun emptyLibraryTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        check(probe.call("getPlaylists").getJSONObject("playlists").optJSONArray("playlist").let { it == null || it.length() == 0 })
        check(probe.call("getAlbumList2", mapOf("type" to "recent", "size" to "10")).getJSONObject("albumList2").optJSONArray("album").let { it == null || it.length() == 0 })
        connectSavedAccount(context, probe)
        val shots = DesignTourShots("tv-empty")
        ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("favourites", "empty") { select("library.view.favourites") && awaitTag("library.favourites.empty") && shots.settle() }
            for ((category, page) in listOf("albums" to "library-albums", "artists" to "library-artists", "songs" to "songs")) {
                shots.step(page, "empty") {
                    select("library.view.$category") && select("library.$category.filter.favorites") && awaitTag("library.$category.empty") && shots.settle()
                }
                select("library.$category.filter.all")
            }
            shots.step("downloads-empty", "empty") { select("library.view.downloaded") && awaitTag("library.downloaded.empty") && shots.settle() }
            shots.step("playlists", "empty") { select("library.view.playlists") && awaitTag("library.playlists.empty") && shots.settle() }
            shots.step("playlist-add", "empty") {
                select("library.view.albums") && openCard("library.albums", ALBUM) && select("album.more") && select("album.addToPlaylist") && awaitTag("playlists.add.empty") && shots.settle()
            }
            shots.step("playlist-create", "create-empty") {
                if (!select("playlists.add.new") || !awaitTag("playlists.add.name")) return@step false
                editField("playlists.add.name", "")
                // Done moved the remote on; a person who cleared the field is still on it.
                focusTag("playlists.add.name") && shots.settle()
            }
            select("playlists.add.name.cancel"); back(1)
            shots.step("pinned", "empty") {
                select("album.more") && select("album.pin") && select("library.open") && awaitTag("library.view.pinned") &&
                    select("library.view.pinned") && awaitTag("library.pinned.item.0") &&
                    longSelect("library.pinned.item.0") && select("library.pinned.item.0.actions.pin") &&
                    awaitTag("library.pinned.empty") && awaitNotLoading("library.pinned") && shots.settle()
            }
            shots.step("listen-now", "no-history") {
                select("listenNow.open") && run {
                    // The shelf is on screen at the top of the page: wait for its first card to arrive
                    // rather than scrolling to a node that is not composed yet (the scroll threw).
                    awaitTag("listenNow.newest.item.0") && run {
                        compose.onNodeWithTag("listenNow.surface").performScrollToIndex(0)
                        awaitAbsent("listenNow.recent") && awaitNotLoading("listenNow") && awaitAbsent("library.saved") &&
                            focusTag("listenNow.newest.item.0") && shots.settle(2_000)
                    }
                }
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached TV empty states: ${shots.unreached}" }
    }

    /** Preseed before connecting: an empty-list cache from another tour is not this prerequisite. */
    @Test fun emptyPlaylistTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val created = probe.call("createPlaylist", mapOf("name" to "Empty Fixture Playlist")).getJSONObject("playlist")
        val id = created.getString("id")
        val listed = probe.call("getPlaylists").getJSONObject("playlists").getJSONArray("playlist")
        check((0 until listed.length()).any { listed.getJSONObject(it).getString("id") == id }) { "Fixture empty playlist was not listed by the server" }
        check(probe.call("getPlaylist", mapOf("id" to id)).getJSONObject("playlist").optJSONArray("entry").let { it == null || it.length() == 0 })
        connectSavedAccount(context, probe)
        val shots = DesignTourShots("tv-empty")
        ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("playlist", "empty") {
                select("library.view.playlists") && openCard("library.playlists", "Empty Fixture Playlist") && awaitTag("playlist.empty") && shots.settle()
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached TV empty playlist detail: ${shots.unreached}" }
    }

    @Test fun dulcetTour() {
        if (!DesignTourShots.requested()) return
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("dulcetDiscoverDisposable") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = com.legitimateapps.dulcet.AndroidAccountCredentialStore(context)
        check(store.activeAccountId() == null)
        // Through a relay, so the tour can take the server away for My Library's notice.
        val relay = com.legitimateapps.dulcet.emulator.ServerRelay(args.getString("dulcetDiscoverServerUrl")!!)
        val outcome = kotlinx.coroutines.runBlocking {
            com.legitimateapps.dulcet.connectAndSaveAccount(
                com.legitimateapps.dulcet.core.AccountConnectionRequest(
                    relay.url, args.getString("dulcetDiscoverUsername")!!,
                    args.getString("dulcetDiscoverPassword")!!, allowLocalHttp = true),
                com.legitimateapps.dulcet.core.AccountConnector()::connect, store)
        }
        check(outcome is com.legitimateapps.dulcet.AccountConnectOutcome.Connected)
        val shots = DesignTourShots("tv-dulcet")
        relay.use {
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                shots.step("personal-library", "empty") { select("library.open") && awaitTag("mylibrary.empty") && shots.settle() }
                shots.step("browse") { select("browse.open") && awaitTag("library.home.0.item.0") && shots.settle() }
            }
            // The app opened with the server gone: My Library under the notice.
            relay.cut()
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                shots.step("personal-library", "offline-notice") {
                    awaitTag("library.saved", 45_000) && select("library.open") &&
                        awaitTag("mylibrary.surface") && awaitTag("library.saved") && awaitTagMatching(30_000) { it == "mylibrary.nothingSaved" || it == "mylibrary.empty" } && shots.settle()
                }
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached Dulcet TV routes: ${shots.unreached}" }
    }

    /**
     * Listen Now with the server gone, over the library every other TV frame shows, so a tile's picture is real. The covers
     * land before the server goes. The account goes through a relay so the tour can take the server away.
     */
    @Test fun savedAwayTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        retrying(3) { starFixtures(probe) }
        // A history of its own, so this section alone photographs a Recently Played shelf with something to keep,
        // the longest title on it, because that is the caption a notice page has to keep clear of the foot.
        DesignTourFixtures.scrobbleAlbumFirstTrack(probe, DesignTourFixtures.LONG_ALBUM, System.currentTimeMillis() - 60_000)
        probe.call("scrobble", mapOf("id" to probe.songId(TRACK), "submission" to "true"))
        // More than a shelf holds, so a notice page's focused tile is judged on a full one.
        seedHistory(probe)
        val shots = DesignTourShots("tv-saved")
        com.legitimateapps.dulcet.emulator.ServerRelay(probe.baseUrl).use { relay ->
            connectSavedAccount(context, probe, relay.url)
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            // A person who has read their Library has its covers: the offline Library frames below are held to them.
            check(select("library.open") && awaitTag("library.home.0.item.0") && shots.artLanded()) { "The Library's shelves never landed before the server went away" }
            // The server goes away while the session is open: the shelves keep what they loaded and Listen Now
            // says the refresh failed. The shelves load first, because only a loaded one can keep its items.
            shots.step("listen-now-dropped", "server-dropped") {
                if (!select("listenNow.open") || !awaitTagMatching(45_000) { it.startsWith("listenNow.") && it.endsWith(".item.0") }) return@step false
                // A page read means its covers have landed: going away sooner photographs tiles no one could have kept.
                if (!shots.artLanded()) return@step false
                try {
                    relay.cut()
                    val reached = select("listenNow.more") && select("listenNow.refresh") &&
                        awaitTagMatching(45_000) { (it.startsWith("listenNow.") && (it.endsWith(".freshness") || it.endsWith(".unavailable"))) || it == "library.connection" || it == "library.saved" }
                    mark("listen-now-dropped statement on screen=$reached")
                    reached && shots.settle()
                } finally { relay.makeReachable() }
            }
            }
            // A running session keeps its shelves when reads start failing; the account notice comes from
            // a reconnect. So the viewer's offline state is the app opened while the server is unreachable.
            relay.cut()
            com.legitimateapps.dulcet.ui.ArtworkImages.forgetDecoded()
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                shots.step("offline-library") {
                    val offlineTags = listOf("library.connection", "library.saved") +
                        (0..2).flatMap { listOf("library.home.$it.freshness", "library.home.$it.unavailable") }
                    fun noticeShown(millis: Long) = runCatching {
                        compose.waitUntil("an offline notice on screen", millis) {
                            offlineTags.any { compose.onAllNodes(hasTestTag(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                        }
                    }.isSuccess
                    val shown = noticeShown(45_000)
                    mark("offline-library notice on opening=$shown")
                    // A person with a remote must be able to see where it is: the frame is only of the
                    // settled page with the remote on the showing root's tab.
                    fun focusedTags() = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                        .map { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "?" } }
                    mark("offline-library focused when the notice showed=${focusedTags()}")
                    val resting = shown && shots.settle() && runCatching {
                        compose.waitUntil("the remote on the Listen Now tab", 15_000) {
                            compose.onAllNodes(hasTestTag("listenNow.open") and androidx.compose.ui.test.isFocused(), useUnmergedTree = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                    }.isSuccess
                    mark("offline-library remote on the Listen Now tab=$resting focused=${focusedTags()}")
                    resting
                }
                if ("offline-library" in shots.unreached) writeDiagnostics("offline-library-diagnostics")
                // A scaled tile on the notice's shorter shelf is the page most likely to reach the foot fade.
                shots.step("offline-library", "listen-now-tile-focused") {
                    val tile = shelfTile { it.startsWith("listenNow.") && it.endsWith(".item.0") }
                    tile != null && focusTag(tile) && awaitFocused(tile) && shots.settle()
                }
                shots.step("offline-library", "library-home") { select("library.open") && awaitTag("library.saved") && shots.settle() }
                shots.step("offline-library", "library-tile-focused") {
                    focusTag("library.home.0.item.0") && awaitFocused("library.home.0.item.0") && shots.settle()
                }
                shots.step("offline-library", "library-third-tile-focused") {
                    focusTag("library.home.0.item.2") && awaitFocused("library.home.0.item.2") && shots.settle()
                }
                shots.step("offline-library", "browse") { select("browse.open") && awaitTag("library.saved") && shots.settle() }
                shots.step("offline-library", "browse-tile-focused") {
                    val tile = shelfTile { it.startsWith("library.home.") && it.endsWith(".item.0") }
                    tile != null && focusTag(tile) && awaitFocused(tile) && shots.settle()
                }
            }
            // An album a person has opened, then a server that stops taking the saved password: the album page's notice.
            relay.makeReachable()
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                check(select("library.open") && select("library.view.albums") && openCard("library.albums", ALBUM) &&
                    awaitDetail("album", ALBUM, "album.track.0")) { "The album never opened before the password stopped working" }
                DesignTourFixtures.downloadSavedAlbumTracks(context, probe, ALBUM)
                // Remove download at the menu's foot, focused and then not: the row that must keep saying what it does.
                shots.step("album-menu", "remove-download-focused") {
                    select("album.more") && awaitTag("album.menu") && dialogFocused() && awaitTag("album.download.remove", 30_000) && run {
                        var steps = 0
                        while (steps++ < 14 && !awaitFocusedNow("album.download.remove")) remote(KeyEvent.KEYCODE_DPAD_DOWN)
                        awaitFocused("album.download.remove")
                    } && shots.settle()
                }
                shots.step("album-menu", "remove-download-unfocused") {
                    remote(KeyEvent.KEYCODE_DPAD_UP)
                    awaitAbsentFocus("album.download.remove") && awaitTag("album.download.remove") && shots.settle()
                }
                back(1)
                // The server goes away with the album on screen and the person asks for a refresh: it stays, and says it failed.
                shots.step("album-notice", "refresh-failed") {
                    try {
                        relay.cut()
                        val said = select("album.more") && select("album.refresh") &&
                            awaitTagMatching(45_000) { it == "album.freshness" || it == "library.connection" }
                        mark("album-notice refresh-failed statement on screen=$said")
                        said && shots.settle()
                    } finally { relay.makeReachable() }
                }
            }
            relay.refuseCredentials()
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                shots.step("album-notice", "credentials-refused") {
                    awaitTag("library.connection") && select("library.open") && select("library.view.albums") &&
                        openCard("library.albums", ALBUM) && awaitDetail("album", ALBUM, "album.track.0") &&
                        awaitTag("library.connection") && shots.settle(2_000)
                }
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached saved TV states: ${shots.unreached}" }
    }

    /** Whether the page's "more below" chevron is up, so a frame without one can be read as the page or the capture. */
    private fun markStorageFoot(moment: String) {
        val shown = compose.onAllNodes(hasTestTag("account.storage.surface.moreBelow"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        mark("storage $moment moreBelow=$shown")
    }

    /** A history that fills a shelf and gives Most Played a rank to show, oldest first, in an order of its own. */
    private fun seedHistory(probe: DisposableServerProbe) {
        listOf("Paging Atlas", "Long Titles", "Double Lines").forEachIndexed { index, album ->
            DesignTourFixtures.scrobbleAlbumFirstTrack(probe, album, System.currentTimeMillis() - 3_600_000L * (index + 1))
        }
    }

    /** Opens a page's tile and comes back: the remote must land on the tile it left, with the page where it was. */
    private fun returnToTile(tile: String): Boolean {
        if (!select(tile) || !awaitTag("album.track.0") || !awaitNotLoading("album")) return false
        back(1)
        return awaitFocused(tile)
    }

    /** Holds a frame until the playhead reads this time, so one run's frame compares with the next's. Always goes on. */
    private fun pinPosition(position: String): Boolean {
        val pinned = awaitText("tv.player.position", position)
        // A playhead already past it is a frame at another time, not a frame that failed: the xml says which it was.
        mark("playback position pinned at $position=$pinned")
        return true
    }

    private fun focusedTag(matches: (String) -> Boolean): String? = compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config.getOrElse(SemanticsProperties.TestTag) { "" } }.firstOrNull(matches)

    private fun awaitAbsentFocus(tag: String): Boolean = runCatching {
        compose.waitUntil("$tag not focused", 10_000) { !awaitFocusedNow(tag) }
    }.isSuccess

    /** The first tag matching, once one has come up. */
    private fun shelfTile(matches: (String) -> Boolean): String? {
        if (!awaitTagMatching(30_000, matches)) return null
        return compose.onAllNodes(SemanticsMatcher("a matching tile") { node -> matches(node.config.getOrElse(SemanticsProperties.TestTag) { "" }) }, useUnmergedTree = true)
            .fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.ifEmpty { null } }
    }

    private fun retrying(attempts: Int, block: () -> Unit) {
        repeat(attempts - 1) { runCatching(block).onSuccess { return }; Thread.sleep(2_000) }
        block()
    }

    private fun starFixtures(probe: DisposableServerProbe) {
        val albumId = probe.albumId(ALBUM)
        val album = probe.call("getAlbum", mapOf("id" to albumId)).getJSONObject("album")
        probe.call("star", mapOf("albumId" to albumId))
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

    /** Focus, then the centre key, as a remote selects. */
    private fun select(tag: String): Boolean {
        if (tag in setOf("listenNow.open", "library.open", "browse.open", "search.open", "tv.account.open") && resumed() != TvSearchActivity::class.java.name) {
            // Independent sections recover through the real activity entry point after a failed
            // Back path. The failed step remains UNREACHED rather than invalidating every later page.
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!awaitTag(tag)) return false
        }
        if (tag == "tv.account.signout" || tag == "tv.account.add-server" || tag == "account.storage" ||
            tag == "quality.unmetered" || tag == "quality.metered") {
            compose.onNodeWithTag("account.surface").performScrollToNode(hasTestTag(tag))
        }
        if (tag.startsWith("offline.") && !tag.startsWith("offline.cache.budget.")) {
            compose.onNodeWithTag("account.storage.surface").performScrollToNode(hasTestTag(tag))
        }
        if (tag.startsWith("library.view.")) {
            // A return restores the category focus, while a fresh root focuses its first card.
            // Scroll the root's category pane to the requested row; do not assume either focus state.
            val key = tag.removePrefix("library.view.")
            val root = if (key in setOf("artists", "albums", "songs", "genres")) "browse.open" else "library.open"
            if (!select(root)) return false
            if (!awaitTag("library.nav")) return false
            compose.onNodeWithTag("library.nav").performScrollToNode(hasTestTag(tag))
        }
        if (!focusTag(tag)) return false
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        return true
    }

    private fun chooseAlbumSort(key: String): Boolean {
        compose.onNodeWithTag("library.albums").performScrollToIndex(0)
        return select("library.albums.sort") && select("library.albums.sort.$key")
    }
    private fun openCard(grid: String, title: String): Boolean {
        if (!awaitTag("$grid.item.0")) return false
        compose.onNodeWithTag(grid).performScrollToNode(hasText(title))
        val tag = compose.onAllNodes(hasText(title) and SemanticsMatcher("a card of $grid") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("$grid.item.")
        }).fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.TestTag) ?: return false
        return select(tag)
    }

    /** TV fields are read-only until Select enters editing; text actions then become available. */
    private fun editField(tag: String, value: String) {
        fun editing() = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().any { it.config.contains(SemanticsActions.SetText) }
        // The previous field's Next action moves focus to this one, and a Select sent while that move settles
        // can be lost. Try the remote's Select, then a touch (the field also enters editing on a tap), then Select again.
        check(select(tag))
        var entered = runCatching { compose.waitUntil("$tag editing", 5_000) { editing() } }.isSuccess
        if (!entered) {
            println("DESIGN TOUR $tag not editing after Select; trying a tap")
            compose.onNodeWithTag(tag).performTouchInput { click() }
            compose.waitForIdle()
            entered = runCatching { compose.waitUntil("$tag editing", 5_000) { editing() } }.isSuccess
        }
        if (!entered) {
            println("DESIGN TOUR $tag not editing after a tap; selecting once more")
            check(select(tag))
        }
        compose.waitUntil("$tag editing", 10_000) { editing() }
        compose.onNodeWithTag(tag).performTextClearance()
        if (value.isNotEmpty()) compose.onNodeWithTag(tag).performTextInput(value)
        // Done moves focus away and leaves read-only mode, avoiding a keyboard over the capture.
        compose.onNodeWithTag(tag).performImeAction()
        compose.waitForIdle()
    }

    /** A held Select, as a remote long-press delivers it: the key's first repeat fires long-click. */
    private fun longSelect(tag: String): Boolean {
        if (!focusTag(tag)) return false
        val now = android.os.SystemClock.uptimeMillis()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        inst.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.waitForIdle()
        return true
    }

    /**
     * A dialog is photographed only once its window has a focused node. A dialog that never gets one fails
     * its step, which is an app defect, not a capture to retry.
     */
    private fun dialogFocused(): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        while (System.currentTimeMillis() < deadline) {
            val focused = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) return true
            Thread.sleep(100)
        }
        println("DESIGN TOUR dialog has no focused node")
        return false
    }

    private fun focusTag(tag: String): Boolean {
        if (tag == "quality.unmetered" || tag == "quality.metered" || tag == "tv.account.signout") {
            runCatching { compose.onNodeWithTag("account.surface").performScrollToNode(hasTestTag(tag)) }
        }
        if (!awaitTag(tag)) return false
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return awaitFocused(tag)
    }

    /** A real publication's identity and rows, rather than the destination shell's placeholder. */
    private fun awaitDetail(kind: String, title: String, contentTag: String): Boolean =
        awaitText("$kind.title", title) && awaitTag(contentTag) && awaitNotLoading(kind)

    private fun awaitText(tag: String, value: String, substring: Boolean = false): Boolean = runCatching {
        compose.waitUntil("$tag shows $value", 30_000) {
            // ListItem's supporting text belongs to its merged focus target; Text titles work here too.
            compose.onAllNodes(hasTestTag(tag) and hasText(value, substring = substring))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private fun awaitAbsent(tag: String): Boolean = runCatching {
        compose.waitUntil("$tag finished", 30_000) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }.isSuccess

    private fun awaitNotLoading(prefix: String): Boolean = runCatching {
        compose.waitUntil("$prefix content settled", 30_000) {
            compose.onAllNodes(SemanticsMatcher("a $prefix loading node") {
                val tag = it.config.getOrElse(SemanticsProperties.TestTag) { "" }
                tag.startsWith(prefix) && (tag.endsWith(".loading") || tag.contains(".loading."))
            }, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }.isSuccess

    private fun awaitFocusedNow(tag: String): Boolean = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
        .fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun awaitFocused(tag: String): Boolean = runCatching {
        compose.waitUntil("$tag focused", 30_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
                .fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
        }
    }.isSuccess

    private fun awaitTag(tag: String, timeoutMillis: Long = 30_000): Boolean = runCatching {
        compose.waitUntil("$tag on screen", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private fun awaitTagMatching(timeoutMillis: Long, matches: (String) -> Boolean): Boolean = runCatching {
        compose.waitUntil("a matching tag on screen", timeoutMillis) {
            compose.onAllNodes(SemanticsMatcher("a matching tag") { matches(it.config.getOrElse(SemanticsProperties.TestTag) { "" }) }, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    /** A progress line in the instrumentation log, so an unreached screen names the sub-step. */
    private fun mark(what: String): Boolean { println("DESIGN TOUR $what"); marks.appendLine(what); return true }

    private val marks = StringBuilder()

    /**
     * The tour's own log never leaves the device, but files named like its shots are pulled with them:
     * what the step did and the Compose tree it ended on, so an unreached screen can be explained.
     */
    private fun writeDiagnostics(name: String) {
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-tour")
        val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 14) }.getOrElse { "tree unavailable: ${it.message}" }
        java.io.File(directory, "tv-dulcet-99-$name.txt").writeText("$marks\n$tree\n")
    }

    private fun back(times: Int) = repeat(times) { remote(KeyEvent.KEYCODE_BACK); Thread.sleep(500) }

    private fun remote(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun resumed(): String? {
        var name: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            name = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.javaClass?.name
        }
        return name
    }

    private companion object {
        const val ALBUM = "Threshold Boundary"
        const val ARTIST = "Dulcet Fixtures"
        const val TRACK = "Twenty Nine Seconds"
        const val LYRICS_TRACK = "Thirty One Seconds"
        const val PLAYLIST = "Dulcet Tour Mix"
    }
}
