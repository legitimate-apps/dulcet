package com.legitimateapps.dulcet

import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.core.ListenNowShelf
import com.legitimateapps.dulcet.emulator.DesignTourFixtures
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
    init {
        com.legitimateapps.dulcet.emulator.DesignTourShots.advanceClock = { compose.mainClock.advanceTimeBy(it) }
        com.legitimateapps.dulcet.emulator.DesignTourShots.awaitComposeIdle = { compose.waitForIdle() }
    }

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
        scrobbleHistory(probe)
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
            // The saved-copy line is transient while a reachable server reconnects, so a frame here raced it: each run caught
            // it or not. This frame waits it out; savedAwayTour's listen-now-saved--server-away is the away state.
            shots.step("listen-now-saved", "reconnected") {
                waitFor(30_000) { compose.onAllNodes(hasTestTag("library.saved")).fetchSemanticsNodes().isEmpty() } && settledListenNow(shots)
            }
            shots.step("library-home") {
                click("library.open")
                if (!awaitTag("library.view.playlists")) return@step false
                // The Library root is a destination list with no shelves: settled means every root row is drawn.
                compose.onNodeWithTag("library.home").performScrollToIndex(0)
                if (!waitFor(30_000) {
                    LIBRARY_ROOT_ROWS.all { row -> compose.onAllNodes(hasTestTag("library.view.$row")).fetchSemanticsNodes().isNotEmpty() }
                }) return@step false
                shots.settle()
            }
            shots.step("listen-now") {
                click("listenNow.open")
                settledListenNow(shots)
            }
            // The tint a scrolled top bar takes only shows after a scroll, so it needs its own still.
            shots.step("listen-now", "scrolled") {
                compose.onNodeWithTag("listenNow.surface").performTouchInput { swipeUp() }
                shots.settle()
            }
            compose.onNodeWithTag("listenNow.surface").performScrollToIndex(0)
            click("library.open")
            shots.step("library-albums") {
                click("library.view.albums")
                awaitTag("library.albums.item.0") && shots.settle(2_500)
            }
            shots.step("album") { openAlbum() && shots.settle() }
            shots.step("album-menu") { click("album.menu"); shots.settle() }
            back()
            shots.step("playlist-add") { click("album.menu"); click("album.addToPlaylist"); awaitTag("playlists.add") && shots.settle() }
            back()
            shots.step("pinned") {
                click("album.menu"); click("album.pin"); click("library.open"); click("library.view.pinned")
                if (!awaitTag("library.pinned.item.0")) return@step false
                shots.settle()
            }
            shots.step("album-sort") {
                click("library.open"); click("library.view.albums"); click("library.albums.sort"); shots.settle()
            }
            back()
            shots.step("recently-added") {
                click("browse.open"); check(scrollListTo("library.home", "library.home.0.seeAll")); click("library.home.0.seeAll"); awaitTag("recentlyAdded.item.0") && shots.settle()
            }
            shots.step("recently-played") {
                click("listenNow.open"); check(scrollListTo("listenNow.surface", "listenNow.recent.seeAll")); click("listenNow.recent.seeAll"); awaitTag("recentlyPlayed.item.0") && shots.settle()
            }
            shots.step("most-played") {
                click("library.open"); click("library.view.mostPlayed"); awaitTag("mostPlayed.item.0") && shots.settle()
            }
            shots.step("songs") {
                click("library.view.songs")
                scrollListTo("library.songs", "library.songs.track.0") && shots.settle()
            }
            shots.step("downloads-populated", "completed") {
                click("library.view.downloaded")
                if (!awaitTag("library.downloaded.track.0")) return@step false
                compose.onNodeWithTag("library.downloaded").performScrollToNode(hasTestTag("library.downloaded.track.0"))
                shots.settle()
            }
            shots.step("genres") { click("library.view.genres"); awaitTag("library.genres.item.0") && shots.settle() }
            shots.step("genre") { click("library.genres.item.0"); awaitTag("genre.track.0") && shots.settle() }
            back()
            shots.step("library-artists") {
                click("library.open")
                click("library.view.artists")
                awaitTag("library.artists.item.0") && shots.settle()
            }
            shots.step("artist") {
                compose.onNodeWithTag("library.artists").performScrollToNode(hasText(ARTIST))
                compose.onNode(hasText(ARTIST) and hasAnyAncestor(hasTestTag("library.artists"))).performClick()
                // The header can draw before the independent Top songs and album readers finish.
                // Wait for the actual populated page so this step never records a loading shell.
                // The albums are grid items below the fold (at 2x font scale the Top songs fill the screen) and
                // compose only once scrolled to: scroll to the first album card, then back to the header.
                awaitTag("artist.play") && awaitTag("artist.topSongs.0") && scrollListTo("artist.albums", "artist.album.0") && run {
                    compose.onNodeWithTag("artist.albums").performScrollToIndex(0)
                    awaitTag("artist.play")
                } && shots.settle()
            }
            shots.step("artist", "collapsed") {
                compose.onNodeWithTag("artist.albums").performScrollToIndex(1)
                awaitTag("artist.collapsed.count") && shots.settle()
            }
            compose.onNodeWithTag("artist.albums").performScrollToIndex(0)
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
                compose.onNodeWithTag("library.playlists.name").performTextReplacement(VALID_PLAYLIST_NAME)
                // The still is only "valid" once the field holds the name and Create can be pressed.
                waitFor(10_000) {
                    compose.onAllNodes(hasTestTag("library.playlists.name") and hasText(VALID_PLAYLIST_NAME)).fetchSemanticsNodes().isNotEmpty()
                } && shots.settle()
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
            shots.step("now-playing", "playing") {
                click("playlist.entry.1")
                // The track's title IN THE PLAYER: the page beneath can match a plain text wait while
                // a cold service is still publishing the queue. Then prove it held to the shot.
                awaitTag("player.full") && awaitPlayerTitle() && shots.settle(2_000) && awaitPlayerTitle() && shots.settle(2_000)
            }
            // The frame to compare between runs: stopped at one second, so the scrubber and the lyric line are the
            // same every time. This also freezes the short fixture track for the menus; auto-advance changes queue indices.
            shots.step("now-playing") { pause() && pinPosition(LYRICS_TRACK_SECONDS, seconds = 7) && shots.settle() }
            shots.step("up-next") {
                click("player.queue")
                awaitTag("player.upnext") && shots.settle()
            }
            // The Autoplay switch is the queue sheet's Autoplay section header.
            shots.step("listening-session") { awaitTag("listening.autoplay") && shots.settle() }
            // Sleep timer and crossfade live in the player's More sheet; an active timer shows on the player.
            shots.step("sleep-timer") {
                back()
                click("player.more")
                click("listening.sleep")
                shots.settle()
            }
            shots.step("listening-session", "sleep-active") {
                click("listening.sleep.15m")
                back()  // the choice sheet closed on selection; close the More sheet so the player's chip shows
                shots.settle()
            }
            shots.step("crossfade") {
                click("player.more")
                click("listening.crossfade")
                shots.settle()
            }
            shots.step("listening-session", "crossfade-active") { click("listening.crossfade.4"); shots.settle() }
            // The menu again, in the same run: it must show the value just chosen, not Off.
            shots.step("crossfade", "selected") { click("listening.crossfade"); shots.settle() }
            back()
            shots.step("queue-menu") {
                back()  // close the More sheet
                click("player.queue")
                clickUpcomingMenu()
                awaitTag("player.upnext") && shots.settle()
            }
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
                compose.onNodeWithTag("search.query").performImeAction()
                // The device's rows paint first, labelled; the shot is the server's answer.
                val found = awaitTag("search.result.0") && waitFor(15_000) {
                    compose.onAllNodesWithTag("search.scope").fetchSemanticsNodes().isEmpty()
                }
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
            shots.step("search-recents") {
                compose.onNodeWithTag("search.query").performTextClearance()
                awaitTag("search.recent.0") && shots.settle()
            }
            shots.step("account") { click("account.open"); awaitTag("account.page") && shots.settle() }
            shots.step("server-list") { awaitTag("account.add-server") && shots.settle() }
            shots.step("playback") { click("account.playback"); awaitTag("quality.unmetered") && shots.settle() }
            shots.step("streaming-quality") {
                click("quality.unmetered")
                awaitTag("quality.unmetered.original") && shots.settle()
            }
            back()
            shots.step("streaming-quality", "unmetered") {
                click("quality.unmetered"); click("quality.unmetered.original"); click("quality.unmetered")
                awaitTag("quality.unmetered.original") && shots.settle()
            }
            back()
            shots.step("streaming-quality", "metered") { click("quality.metered"); awaitTag("quality.metered.original") && shots.settle() }
            back()
            back()
            shots.step("storage") { click("account.storage"); awaitTag("offline.automatic") && shots.settle() }
            shots.step("storage", "automatic-off") { click("offline.automatic"); shots.settle() }
            shots.step("storage", "usage") { compose.onNodeWithTag("offline.download.usage").performScrollTo(); shots.settle() }
            shots.step("storage", "budget") { click("offline.cache.limit"); awaitTag("offline.cache.budget.268435456") && shots.settle() }
            click("offline.cache.budget.268435456")
            shots.step("storage", "clear-ready") { compose.onNodeWithTag("offline.cache.clear").performScrollTo(); shots.settle() }
            shots.step("storage", "clear") {
                // A cache holding only what is playing or up next has nothing to clear; the frame then shows that row.
                val clearable = compose.onNodeWithTag("offline.cache.clear").fetchSemanticsNode()
                    .config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()
                if (clearable) {
                    click("offline.cache.clear")
                    val cleared = context.getString(com.legitimateapps.dulcet.shared.R.string.offline_cache_cleared)
                    check(waitFor(10_000) { compose.onAllNodes(hasText(cleared)).fetchSemanticsNodes().isNotEmpty() }) { "No word that the cache cleared" }
                }
                shots.settle()
            }
            back()
            shots.step("signout-confirmation") { click("account.signout"); awaitTag("signout.confirm") && shots.settle() }
            runCatching { click("signout.stay") }
            shots.step("server-add") { click("account.add-server"); awaitTag("account.server") && shots.settle() }
        }
        check(shots.unreached.isEmpty()) { "The design tour could not reach: ${shots.unreached}" }
        println("DESIGN TOUR done label=$label")
    }

    @Test fun detailsTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        retrying(3) { starFixtures(probe); seedPlaylist(probe); DesignTourFixtures.seedLongPlaylist(probe) }
        connectSavedAccount(context, probe)
        val shots = DesignTourShots(InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel")!! + "-details")
        fun dismiss(tag: String) {
            repeat(3) { if (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()) back() }
        }
        fun albumNamed(name: String): Boolean {
            click("library.open"); click("library.view.albums")
            if (!awaitTag("library.albums.item.0")) return false
            compose.onNodeWithTag("library.albums").performScrollToNode(hasText(name))
            compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("library.albums"))).performClick()
            return awaitTag("album.title")
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            try {
            shots.step("playlist", "long-name") {
                click("library.open"); click("library.view.playlists"); check(awaitTag("library.playlists.item.0"))
                compose.onNodeWithTag("library.playlists").performScrollToNode(hasText(DesignTourFixtures.LONG_PLAYLIST))
                compose.onNode(hasText(DesignTourFixtures.LONG_PLAYLIST) and hasAnyAncestor(hasTestTag("library.playlists"))).performClick()
                awaitTag("playlist.entry.0") && shots.settle()
            }
            // The 100-entry playlist is the fixture that scrolls past its header; the two-entry one cannot collapse it.
            shots.step("playlist", "collapsed") {
                compose.onNodeWithTag("playlist.entries").performScrollToIndex(2)
                awaitTag("playlist.collapsed.count") && shots.settle()
            }
            compose.onNodeWithTag("playlist.entries").performScrollToIndex(0)
            shots.step("context-menu", "playlist") { click("playlist.menu"); awaitTag("playlist.delete") && shots.settle() }
            shots.step("playlist-delete-confirmation", "cancel") { click("playlist.delete"); check(awaitTag("playlist.delete.confirm")); back(); awaitTag("playlist.title") && shots.settle() }
            shots.step("playlist-edit", "long-list") {
                click("playlist.edit"); compose.onNodeWithTag("playlist.entries").performScrollToNode(hasTestTag("playlist.entry.90.remove")); shots.settle()
            }
            click("playlist.edit")
            shots.step("playlist-create", "cancel") {
                click("library.open"); click("library.view.playlists"); click("library.playlists.new"); check(awaitTag("library.playlists.name"))
                click("library.playlists.name.cancel"); awaitTag("library.playlists") && shots.settle()
            }
            shots.step("playlist-add", "new") { check(openAlbum()); click("album.menu"); click("album.addToPlaylist"); click("playlists.add.new"); awaitTag("playlists.add.name") && shots.settle() }
            click("playlists.add.name.cancel"); dismiss("playlists.add")
            shots.step("context-menu", "track") {
                compose.onNodeWithTag("album.track.0.menu").performScrollTo(); click("album.track.0.menu"); awaitTag("album.track.0.menu.rate") && shots.settle()
            }
            shots.step("context-menu", "rating") { click("album.track.0.menu.rate"); awaitTag("album.track.0.rating.done") && shots.settle() }
            click("album.track.0.rating.done")
            // The three-track fixture album fits one screen; the 300-track Paging Atlas album scrolls past its header.
            shots.step("album", "collapsed") {
                // At twice the font size the second track starts below the fold, so it is not composed yet.
                albumNamed(PAGING_ALBUM) && scrollListTo("album.tracks", "album.track.1") && run {
                    compose.onNodeWithTag("album.tracks").performScrollToIndex(1)
                    awaitTag("album.collapsed.count")
                } && shots.settle()
            }
            back()
            shots.step("artist", "no-biography") {
                click("library.open"); click("library.view.artists"); check(awaitTag("library.artists.item.0"))
                compose.onNodeWithTag("library.artists").performScrollToNode(hasText(ARTIST))
                compose.onNode(hasText(ARTIST) and hasAnyAncestor(hasTestTag("library.artists"))).performClick()
                // The albums arrive after the songs. Bring them into composition once to know they
                // have, then shoot the page from its top, as it opens.
                awaitTag("artist.play") && waitFor(15_000) {
                    runCatching { compose.onNodeWithTag("artist.albums").performScrollToNode(hasTestTag("artist.group.albums")) }.isSuccess
                } && awaitTag("artist.topSongs.0") && run {
                    compose.onNodeWithTag("artist.albums").performScrollToIndex(0)
                    // The bar's compact identity leaves once the hero is back; the frame is of that page.
                    waitFor(15_000) { compose.onAllNodes(hasTestTag("artist.collapsed.count")).fetchSemanticsNodes().isEmpty() }
                } && shots.settle()
            }
            shots.step("artist", "release-groups") {
                // The corpus's EP and single follow the albums; this frame is where one heading ends and the next begins.
                compose.onNodeWithTag("artist.albums").performScrollToNode(hasTestTag("artist.group.epsSingles")); shots.settle()
            }
            shots.step("songs", "filter") {
                click("library.open"); click("library.view.songs"); click("library.songs.filter.favorites")
                scrollListTo("library.songs", "library.songs.track.0") && shots.settle()
            }
            shots.step("now-playing", "paused") {
                check(albumNamed(DesignTourFixtures.LONG_ALBUM)); check(scrollAlbumTo("album.track.0")); click("album.track.0")
                check(awaitTag("player.mini")); click("player.mini"); awaitTag("player.full") && pause() && pinPosition(LONG_TRACK_SECONDS, seconds = 10) && shots.settle()
            }
            shots.step("lyrics", "absent") {
                // A track with no lyrics disables the toggle, with the reason as its tooltip,
                // instead of opening an empty sheet.
                waitFor(30_000) {
                    compose.onAllNodes(hasTestTag("player.lyrics")).fetchSemanticsNodes().firstOrNull()
                        ?.let { androidx.compose.ui.semantics.SemanticsProperties.Disabled in it.config } == true
                } && shots.settle()
            }
            dismiss("player.lyrics.sheet"); click("player.close")
            shots.step("lyrics", "unsynced") {
                click("search.open"); click("search.query"); compose.onNodeWithTag("search.query").performTextInput("Ogg Probe")
                compose.onNodeWithTag("search.query").performImeAction(); check(awaitTag("search.result.0"))
                if (keyboardShown(scenario)) back()
                click("search.result.0")
                if (!awaitTag("player.full", 3_000)) { check(awaitTag("player.mini")); click("player.mini") }
                check(pause()); click("player.lyrics"); awaitTag("lyrics.plain") && shots.settle()
            }
            dismiss("player.lyrics.sheet"); click("player.close")
            shots.step("album", "long-title") { albumNamed(DesignTourFixtures.LONG_ALBUM) && shots.settle() }
            shots.step("now-playing", "long-title") {
                check(scrollAlbumTo("album.track.0")); click("album.track.0"); check(awaitTag("player.mini")); click("player.mini")
                awaitTag("player.full") && waitFor(10_000) {
                    compose.onAllNodes(hasText(DesignTourFixtures.LONG_TRACK) and hasAnyAncestor(hasTestTag("player.full"))).fetchSemanticsNodes().isNotEmpty()
                } && pause() && pinPosition(LONG_TRACK_SECONDS, seconds = 10) && shots.settle()
            }
            // Return the full-screen player to its owner before ActivityScenario teardown.
            if (compose.onAllNodes(hasTestTag("player.close")).fetchSemanticsNodes().isNotEmpty()) click("player.close")
            } finally {
                // PlaybackEntry may replace the Activity Intent. Restore the launch identity so
                // ActivityScenario can observe this same Activity reaching DESTROYED on close.
                scenario.onActivity { it.intent = launchIntent }
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached phone detail states: ${shots.unreached}" }
    }

    @Test fun connectionStatesTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val shots = DesignTourShots(InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel")!! + "-connection")
        fun field(tag: String, value: String) {
            compose.onNodeWithTag(tag).performTextClearance()
            compose.onNodeWithTag(tag).performTextInput(value)
        }
        fun failure(resource: Int): Boolean {
            val title = context.getString(resource)
            val shown = waitFor(30_000) { compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
            if (shown) compose.onNodeWithTag("account.status.failed").performScrollTo()
            return shown && shots.settle()
        }
        com.legitimateapps.dulcet.emulator.ServerRelay(probe.baseUrl).use { relay ->
            ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use {
                check(awaitTag("account.server"))
                field("account.server", relay.url)
                field("account.username", DisposableServerProbe.USER)
                field("account.password", DisposableServerProbe.PASSWORD)
                shots.step("connection-empty", "filled") { shots.settle() }
                shots.step("connection-error-input", "security") { click("account.submit"); failure(com.legitimateapps.dulcet.shared.R.string.error_security_title) }
                click("account.allow-local-http")
                field("account.password", "incorrect-disposable-password")
                shots.step("connection-error-input", "auth") { click("account.submit"); failure(com.legitimateapps.dulcet.shared.R.string.error_auth_title) }
                field("account.password", DisposableServerProbe.PASSWORD)
                relay.cut()
                shots.step("connection-error-input", "transport") { click("account.submit"); failure(com.legitimateapps.dulcet.shared.R.string.error_unreachable_title) }
                relay.makeReachable(); relay.hold()
                shots.step("connection-empty", "connecting") {
                    click("account.submit")
                    if (!awaitTag("account.status.connecting")) return@step false
                    compose.onNodeWithTag("account.status.connecting").performScrollTo()
                    shots.settle()
                }
                click("account.submit") // Cancel the held attempt before disposing/releasing the relay.
            }
        }
        check(AndroidAccountCredentialStore(context).activeAccountId() == null) { "Cancelled design connection saved an account" }
        check(shots.unreached.isEmpty()) { "Unreached connection states: ${shots.unreached}" }
    }

    /**
     * The saved page a person reaches: the home read and its covers landed, then the server gone and the app opened again
     * with nothing but what the device keeps. The account goes through a relay so the tour can take the server away.
     */
    @Test fun savedAwayTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        retrying(3) { starFixtures(probe); seedPlaylist(probe) }
        scrobbleHistory(probe)
        val shots = DesignTourShots(InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel")!! + "-saved")
        fun launchApp() = ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
        com.legitimateapps.dulcet.emulator.ServerRelay(probe.baseUrl).use { relay ->
            connectSavedAccount(context, probe, relay.url)
            launchApp().use {
                check(waitFor(30_000) { compose.onAllNodes(hasTestTag("library.saved")).fetchSemanticsNodes().isEmpty() } && settledListenNow(shots)) { "The home never read" }
                // Walk the page as a person reading it does, so every shelf's first covers have landed before the server goes.
                val surface = compose.onNodeWithTag("listenNow.surface")
                for (index in 0 until 8) {
                    if (runCatching { surface.performScrollToIndex(index) }.isFailure) break
                    check(shots.artLanded()) { "A cover never landed at shelf position $index" }
                }
                surface.performScrollToIndex(0)
                check(shots.artLanded()) { "A cover never landed at the page's top" }
                com.legitimateapps.dulcet.emulator.DesignTourFixtures.awaitSavedAlbumDownloads(context, probe, ALBUM)
            }
            relay.cut()
            com.legitimateapps.dulcet.ui.ArtworkImages.forgetDecoded()
            launchApp().use {
                shots.step("listen-now-saved", "server-away") {
                    awaitTag("library.saved") && waitFor(30_000) { com.legitimateapps.dulcet.ui.ArtworkImages.activity().painted > 0 } && shots.settle(2_000)
                }
                shots.step("library-saved", "server-away") { click("library.open"); awaitTag("library.saved") && shots.settle() }
                shots.step("downloads-populated", "server-away") {
                    click("library.view.downloaded"); awaitTag("library.downloaded.track.0") && shots.settle()
                }
            }
            // An album a person has opened, then a server that stops taking the saved password: the album page's notice.
            relay.makeReachable()
            launchApp().use {
                check(openAlbum()) { "The album never opened before the password stopped working" }
                // The server goes away with the album on screen and the person asks for a refresh: it stays, and says it failed.
                try {
                    shots.step("album-notice", "refresh-failed") {
                        relay.cut()
                        click("album.menu"); click("album.refresh")
                        waitFor(45_000) {
                            listOf("album.freshness", "library.connection").any { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
                        } && shots.settle()
                    }
                    // At large text the notice and hero fill the first screen; row 3 shows the album's two held tracks agree with its count.
                    if (largeText(context)) shots.step("album-notice", "refresh-failed-rows") { scrollAlbumTo("album.track.2") && shots.settle() }
                } finally { relay.makeReachable() }
            }
            // A person chooses a playlist and the server stops answering before the addition lands.
            launchApp().use {
                check(openAlbum()) { "The album never opened before adding to a playlist" }
                click("album.menu"); click("album.addToPlaylist"); check(awaitTag("playlists.add.item.0"))
                shots.step("playlist-add", "failed") {
                    try {
                        relay.cut()
                        click("playlists.add.item.0")
                        awaitTag("playlists.add.note", 45_000) && shots.settle()
                    } finally { relay.makeReachable() }
                }
            }
            relay.refuseCredentials()
            launchApp().use {
                shots.step("album-notice", "credentials-refused") {
                    // At large text the notice and the hero push the first track off the page, so the page's own action says it opened.
                    awaitTag("library.connection") && openAlbum("album.play") && awaitTag("library.connection") && shots.settle(2_000)
                }
                // At large text the notice and hero fill the first screen; row 3 shows the album's two held tracks agree with its count.
                if (largeText(context)) shots.step("album-notice", "credentials-refused-rows") { scrollAlbumTo("album.track.2") && shots.settle() }
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached saved states: ${shots.unreached}" }
    }

    /** A separate, brand-new disposable database: no favourites, playlists, plays or downloads. */
    @Test fun emptyLibraryTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        check(probe.call("getPlaylists").getJSONObject("playlists").optJSONArray("playlist").let { it == null || it.length() == 0 })
        check(probe.call("getAlbumList2", mapOf("type" to "recent", "size" to "10")).getJSONObject("albumList2").optJSONArray("album").let { it == null || it.length() == 0 })
        connectSavedAccount(context, probe)
        val shots = DesignTourShots(InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel")!! + "-empty")
        ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use {
            shots.step("favourites", "empty") { click("library.open"); click("library.view.favourites"); awaitTag("library.favourites.empty") && shots.settle() }
            for ((category, page) in listOf("albums" to "library-albums", "artists" to "library-artists", "songs" to "songs")) {
                shots.step(page, "empty") {
                    click("library.view.$category"); click("library.$category.filter.favorites")
                    scrollListTo("library.$category", "library.$category.empty") && shots.settle()
                }
                // Songs has no All chip: its active Favorites chip toggles back to the unfiltered list.
                click(if (category == "songs") "library.songs.filter.favorites" else "library.$category.filter.all")
                // Category filters persist after a debounce. Do not leave while only the chip's
                // local value changed: wait for the restored All publication before the next page.
                val first = if (category == "songs") "track.0" else "item.0"
                check(scrollListTo("library.$category", "library.$category.$first"))
            }
            shots.step("downloads-empty", "empty") { click("library.view.downloaded"); awaitTag("library.downloaded.empty") && shots.settle() }
            shots.step("playlists", "empty") { click("library.view.playlists"); awaitTag("library.playlists.empty") && shots.settle() }
            shots.step("playlist-add", "empty") { openAlbum() && run { click("album.menu"); click("album.addToPlaylist"); awaitTag("playlists.add.empty") && shots.settle() } }
            back()
            click("library.open"); click("library.view.playlists"); click("library.playlists.new")
            compose.onNodeWithTag("library.playlists.name").performTextInput("Empty Fixture Playlist")
            click("library.playlists.name.confirm")
            shots.step("playlist", "empty") {
                // Create navigates immediately under its local ID, then follows the server ID.
                // A fast completion can leave us on detail before the list row is ever composed.
                awaitTag("playlist.empty") && shots.settle()
            }
            shots.step("pinned", "empty") {
                click("library.open"); click("library.view.pinned")
                awaitTag("library.pinned.empty") && shots.settle()
            }
            shots.step("listen-now", "no-history") {
                click("listenNow.open")
                waitFor(30_000) { compose.onAllNodes(hasTestTag("listenNow.recent")).fetchSemanticsNodes().isEmpty() } && settledListenNow(shots)
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached empty states: ${shots.unreached}" }
    }

    @Test fun supplementalTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel") ?: "phone"
        val probe = DisposableServerProbe.fromInstrumentation()
        retrying(3) { starFixtures(probe); seedPlaylist(probe) }
        scrobbleHistory(probe)
        connectSavedAccount(context, probe)
        val shots = DesignTourShots(label + "-supplement")
        ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use {
            // The saved-copy line is transient while a reachable server reconnects, so a frame here raced it: each run caught
            // it or not. This frame waits it out; savedAwayTour's listen-now-saved--server-away is the away state.
            shots.step("listen-now-saved", "reconnected") {
                waitFor(30_000) { compose.onAllNodes(hasTestTag("library.saved")).fetchSemanticsNodes().isEmpty() } && settledListenNow(shots)
            }
            click("library.open"); click("library.view.albums"); check(awaitTag("library.albums.item.0"))
            shots.step("listen-now") {
                click("listenNow.open")
                compose.onNodeWithTag("listenNow.surface").performScrollToNode(hasTestTag("listenNow.newest"))
                settledListenNow(shots)
            }
            click("library.open"); click("library.view.albums")
            shots.step("recently-played") { click("listenNow.open"); check(scrollListTo("listenNow.surface", "listenNow.recent.seeAll")); click("listenNow.recent.seeAll"); awaitTag("recentlyPlayed.item.0") && shots.settle() }
            shots.step("most-played") { click("library.view.mostPlayed"); awaitTag("mostPlayed.item.0") && shots.settle() }
            shots.step("listening-session") {
                click("library.view.albums"); click("library.albums.sort"); click("library.albums.sort.alphabeticalbyname")
                if (!openAlbum()) return@step false
                click("album.track.0"); check(awaitTag("player.mini")); click("player.mini"); check(awaitTag("player.full")); click("player.playpause"); click("player.queue")
                awaitTag("listening.autoplay") && shots.settle()
            }
            shots.step("sleep-timer") {
                back()  // close the queue sheet; the sleep timer lives in the player's More sheet
                click("player.more")
                click("listening.sleep")
                shots.settle()
            }
            back()  // close the sleep sheet, leaving the More sheet open
            shots.step("crossfade") { click("listening.crossfade"); shots.settle() }
        }
        check(shots.unreached.isEmpty()) { "Unreached supplementary states: ${shots.unreached}" }
    }

    /**
     * Listen Now shows its Recent shelf, and its See all, only from three distinct albums' history; one
     * album's plays show a resume card instead. Play a track of three albums, found through the server.
     */
    private fun scrobbleHistory(probe: DisposableServerProbe) {
        probe.call("scrobble", mapOf("id" to probe.songId(TRACK), "submission" to "true"))
        val albums = probe.call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "50"))
            .getJSONObject("albumList2").getJSONArray("album")
        var scrobbled = 1
        for (index in 0 until albums.length()) {
            if (scrobbled >= 3) break
            val album = albums.getJSONObject(index)
            if (album.getString("name") == ALBUM) continue
            val songs = probe.call("getAlbum", mapOf("id" to album.getString("id"))).getJSONObject("album").optJSONArray("song") ?: continue
            if (songs.length() == 0) continue
            probe.call("scrobble", mapOf("id" to songs.getJSONObject(0).getString("id"), "submission" to "true"))
            scrobbled++
        }
        check(scrobbled == 3) { "Fixture library has too few albums to give Listen Now a Recent shelf" }
    }

    private fun openAlbum(opened: String = "album.track.0"): Boolean {
        click("library.open")
        click("library.view.albums")
        if (!awaitTag("library.albums.item.0")) return false
        compose.onNodeWithTag("library.albums").performScrollToNode(hasText(ALBUM))
        compose.onNode(hasText(ALBUM) and hasAnyAncestor(hasTestTag("library.albums"))).performClick()
        return awaitTag(opened)
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

    /** Wait for publication and virtualized composition, then scroll through the real list. */
    private fun largeText(context: android.content.Context) = context.resources.configuration.fontScale >= 1.5f

    /** Pause, then require the paused state to hold, and pause again if playback resumed: a track chosen a moment ago
     * may still be starting, and a pause that lands before its play command is taken back by it (three captures showed
     * "Pause" 24 to 29 s into the track). */
    private fun pause(): Boolean {
        val play = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_play)
        fun showsPlay() = compose.onAllNodes(hasTestTag("player.playpause") and hasContentDescription(play)).fetchSemanticsNodes().isNotEmpty()
        repeat(4) {
            if (!showsPlay()) click("player.playpause")
            if (waitFor(5_000) { showsPlay() }) {
                Thread.sleep(1_500)
                compose.waitForIdle()
                if (showsPlay()) return true
            }
        }
        return false
    }

    /** Moves the paused player's scrubber to a second of a track of known length, and proves the player got there. */
    private fun pinPosition(trackSeconds: Int, seconds: Int): Boolean {
        // Half a second in, so the readout is not one rounding away from the second before.
        val fraction = (seconds + 0.5f) / trackSeconds
        val shown = "0:%02d".format(seconds)
        return waitFor(20_000) {
            runCatching {
                compose.onNodeWithTag("player.scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(fraction) }
            }
            compose.waitForIdle()
            waitFor(4_000) { compose.onAllNodes(hasTestTag("player.position") and hasText(shown)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun scrollListTo(container: String, target: String): Boolean = waitFor(30_000) {
        runCatching { compose.onNodeWithTag(container).performScrollToNode(hasTestTag(target)) }.isSuccess
    }

    private fun scrollAlbumTo(target: String): Boolean = waitFor(30_000) {
        runCatching {
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)
                and hasAnyAncestor(hasTestTag("album.surface"))).performScrollToNode(hasTestTag(target))
        }.isSuccess
    }

    private fun click(tag: String) {
        if (tag.startsWith("library.view.") && compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) {
            val category = tag.removePrefix("library.view.")
            compose.onNodeWithTag(if (category in setOf("albums", "artists", "songs", "genres", "discover")) "browse.open" else "library.open").performClick()
            compose.waitForIdle()
        }
        if (tag.startsWith("library.view.")) {
            runCatching { compose.onNodeWithTag("library.home").performScrollToNode(hasTestTag(tag)) }
            runCatching { compose.onNodeWithTag("mylibrary.surface").performScrollToNode(hasTestTag(tag)) }
        }
        awaitTag(tag)
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    /** A sort changes the reader window; its container survives while its rows are still loading. */
    private fun settledAlbums(shots: DesignTourShots): Boolean = shots.settle(1_500) && waitFor(30_000) {
        compose.onAllNodes(hasTestTag("library.albums.item.0")).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(hasTestTag("library.albums.loading")).fetchSemanticsNodes().isEmpty()
    } && shots.settle(500)

    /** A home screenshot waits for every visible shelf to settle, not only the first shelf with art. */
    private fun settledListenNow(shots: DesignTourShots): Boolean {
        fun noShelfLoading() = ListenNowShelf.entries.all { shelf ->
            compose.onAllNodes(hasTestTag("listenNow.${shelf.wireName}.loading")).fetchSemanticsNodes().isEmpty()
        }
        // The resume card now rests above the shelves, so the first shelf may start below the fold;
        // bring it into composition to know it loaded, then shoot the page from its top, as it opens.
        return waitFor(30_000) {
            runCatching { compose.onNodeWithTag("listenNow.surface").performScrollToNode(hasTestTag("listenNow.newest")) }
            compose.onAllNodes(hasTestTag("listenNow.newest.item.0")).fetchSemanticsNodes().isNotEmpty() && noShelfLoading()
        } && run {
            compose.onNodeWithTag("listenNow.surface").performScrollToIndex(0)
            waitFor(30_000) { noShelfLoading() }
        } && shots.settle(2_000)
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
        const val LYRICS_TRACK_SECONDS = 31
        const val LONG_TRACK_SECONDS = 32
        const val VALID_PLAYLIST_NAME = "A new fixture playlist"
        const val PLAYLIST = "Dulcet Tour Mix"
        const val PAGING_ALBUM = "Paging Atlas"
        val LIBRARY_ROOT_ROWS = listOf("playlists", "favourites", "downloaded", "pinned", "mostPlayed")
    }
}
