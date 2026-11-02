package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
        // A freshly booted emulator's first request to the host can be reset; the tour retries it.
        retrying(3) { starFixtures(probe); seedPlaylist(probe) }
        probe.call("scrobble", mapOf("id" to probe.songId(TRACK), "submission" to "true"))
        awaitQueuedBroadcastsDelivered()
        println("DESIGN TOUR TV connecting disposable account")
        connectSavedAccount(context, probe)
        println("DESIGN TOUR TV account connected")
        ActivityScenario.launch<TvPlaybackActivity>(Intent(context, TvPlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("now-playing-empty") { shots.settle() }
        }
        println("DESIGN TOUR TV launching search activity")
        ActivityScenario.launch<TvSearchActivity>(launch).use {
            // The app opens on Listen Now; Library keeps its own catalog surface.
            shots.step("home") { awaitTag("listenNow.surface") && shots.settle() }
            shots.step("library") { select("library.open") && awaitTag("library.home.0.item.0") && shots.settle() }
            shots.step("albums") { select("library.view.albums") && awaitTag("library.albums.item.0") && shots.settle() }
            shots.step("recently-added") { chooseAlbumSort("newest") && awaitTag("library.albums.item.0") && shots.settle() }
            shots.step("recently-played") { chooseAlbumSort("recent") && awaitTag("library.albums.item.0") && shots.settle() }
            shots.step("most-played") { chooseAlbumSort("frequent") && awaitTag("library.albums.item.0") && shots.settle() }
            chooseAlbumSort("alphabeticalbyname")
            shots.step("album-menu") { select("library.albums.item.0.more") && awaitTag("library.albums.item.0.actions") && shots.settle() }
            back(1)
            shots.step("album") { openCard("library.albums", ALBUM) && awaitTag("album.title") && shots.settle() }
            shots.step("playlist-add") { select("album.queue") && select("queue.add.playlist") && awaitTag("playlists.add") && shots.settle() }
            shots.step("playlist-create", "create-valid") { select("playlists.add.new") && awaitTag("playlists.add.name") && shots.settle() }
            select("playlists.add.name.cancel")
            select("playlists.add.cancel")
            shots.step("pinned") {
                select("album.pin") && select("library.open") && run {
                    compose.onNodeWithTag("library.surface").performScrollToNode(hasTestTag("library.pinned.item.0"))
                    focusTag("library.pinned.item.0") && shots.settle()
                }
            }
            shots.step("artists") { select("library.view.artists") && focusTag("library.artists.item.0") && shots.settle() }
            shots.step("artist") { openCard("library.artists", ARTIST) && awaitTag("artist.title") && shots.settle() }
            back(2)
            shots.step("genres") { select("library.view.genres") && focusTag("library.genres.item.0") && shots.settle() }
            shots.step("genre") { select("library.genres.item.0") && awaitTag("genre.title") && shots.settle() }
            back(2)
            shots.step("songs") { select("library.view.songs") && focusTag("library.songs.track.0") && shots.settle() }
            back(1)
            shots.step("downloads-populated", "completed") { select("library.view.downloaded") && focusTag("library.downloaded.track.0") && shots.settle() }
            back(1)
            shots.step("favourites") { select("library.view.favourites") && focusTag("library.favourites.track.0") && shots.settle(2_000) }
            back(1)
            shots.step("playlists") { select("library.view.playlists") && focusTag("library.playlists.item.0") && shots.settle() }
            shots.step("playlist") { openCard("library.playlists", PLAYLIST) && awaitTag("playlist.title") && shots.settle() }
            shots.step("playlist-delete-confirmation") { select("playlist.delete") && awaitTag("playlist.delete.dialog") && shots.settle() }
            back(1)
            back(2)
            shots.step("now-playing") {
                select("library.view.albums") && openCard("library.albums", ALBUM) && awaitFocused("album.play")
                    && run { remote(KeyEvent.KEYCODE_DPAD_CENTER); true }
                    && runCatching { await("the TV's Now Playing in front", 30_000) { resumed() == TvPlaybackActivity::class.java.name } }.isSuccess
                    && awaitTag("tv.player.title") && awaitTag("tv.player.upnext.0") && shots.settle(2_500)
            }
            shots.step("up-next") { focusTag("tv.player.upnext.1") && shots.settle() }
            // Centre on the focused Up Next row jumps to the track with the corpus's synced lyrics,
            // then the transport's lyrics toggle shows the panel in Up Next's place.
            shots.step("lyrics") {
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                shots.settle(1_500)
                    && focusTag("tv.player.lyrics")
                    && run { remote(KeyEvent.KEYCODE_DPAD_CENTER); true }
                    && awaitTag("tv.player.lyrics.panel", 10_000) && shots.settle(1_500)
            }
            shots.step("listening-session") { select("tv.listening.open") && awaitTag("tv.listening.sleep") && shots.settle() }
            shots.step("sleep-timer") { select("tv.listening.sleep") && shots.settle() }
            shots.step("listening-session", "sleep-active") { select("tv.listening.sleep.15m") && shots.settle() }
            shots.step("crossfade") { select("tv.listening.crossfade") && shots.settle() }
            shots.step("listening-session", "crossfade-active") { select("tv.listening.crossfade.3") && shots.settle() }
            back(1)
            back(1)
            shots.step("account") {
                select("tv.account.open") && awaitTag("tv.account.signout") && shots.settle()
            }
            shots.step("server-list") { awaitTag("tv.account.add-server") && shots.settle() }
            shots.step("streaming-quality") { focusTag("quality.unmetered.original") && shots.settle() }
            shots.step("streaming-quality", "unmetered") { select("quality.unmetered.original") && shots.settle() }
            shots.step("streaming-quality", "metered") { select("quality.metered.original") && shots.settle() }
            shots.step("storage") {
                compose.onNodeWithTag("account.surface").performScrollToNode(hasTestTag("offline.storage"))
                awaitTag("offline.storage") && shots.settle()
            }
            shots.step("storage", "automatic-off") { select("offline.automatic") && shots.settle() }
            shots.step("storage", "usage") {
                compose.onNodeWithTag("account.surface").performScrollToNode(hasTestTag("offline.download.usage")); shots.settle()
            }
            shots.step("storage", "budget") { select("offline.cache.budget.268435456") && shots.settle() }
            shots.step("storage", "clear") { select("offline.cache.clear") && shots.settle() }
            shots.step("signout-confirmation") { select("tv.account.signout") && awaitTag("signout.confirm") && shots.settle() }
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

    @Test fun connectionStatesTour() {
        if (!DesignTourShots.requested()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val shots = DesignTourShots("tv-connection")
        fun field(tag: String, value: String) = editField(tag, value)
        fun failure(resource: Int): Boolean {
            val title = context.getString(resource)
            val shown = runCatching { compose.waitUntil("connection failure $title", 30_000) {
                compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
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
            shots.step("downloads-empty", "empty") { select("library.view.downloaded") && awaitTag("library.downloaded.empty") && shots.settle() }
            shots.step("playlists", "empty") { select("library.view.playlists") && awaitTag("library.playlists.empty") && shots.settle() }
            shots.step("playlist-add", "empty") {
                select("library.view.albums") && openCard("library.albums", ALBUM) && select("album.queue") && select("queue.add.playlist") && awaitTag("playlists.add.empty") && shots.settle()
            }
            shots.step("playlist-create", "create-empty") {
                if (!select("playlists.add.new") || !awaitTag("playlists.add.name")) return@step false
                editField("playlists.add.name", "")
                shots.settle()
            }
            select("playlists.add.name.cancel"); select("playlists.add.cancel")
            shots.step("pinned", "empty") {
                select("library.open") && awaitTag("library.home.0.item.0") && compose.onAllNodes(hasTestTag("library.pinned.item.0")).fetchSemanticsNodes().isEmpty() && shots.settle()
            }
            shots.step("listen-now", "no-history") {
                select("listenNow.open") && runCatching { await("empty recent shelf", 30_000) {
                    compose.onAllNodes(hasTestTag("listenNow.recent")).fetchSemanticsNodes().isEmpty()
                } }.isSuccess && shots.settle()
            }
        }
        // Out-of-band fixture writes are observed by a fresh screen session. Refreshing Home
        // cannot refresh a Playlists surface that has already left composition.
        probe.call("createPlaylist", mapOf("name" to "Empty Fixture Playlist"))
        ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("playlist", "empty") {
                select("library.view.playlists") && openCard("library.playlists", "Empty Fixture Playlist") && awaitTag("playlist.empty") && shots.settle()
            }
        }
        check(shots.unreached.isEmpty()) { "Unreached TV empty states: ${shots.unreached}" }
    }

    @Test fun dulcetTour() {
        if (!DesignTourShots.requested()) return
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("dulcetDiscoverDisposable") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = com.legitimateapps.dulcet.AndroidAccountCredentialStore(context)
        check(store.activeAccountId() == null)
        val outcome = kotlinx.coroutines.runBlocking {
            com.legitimateapps.dulcet.connectAndSaveAccount(
                com.legitimateapps.dulcet.core.AccountConnectionRequest(
                    args.getString("dulcetDiscoverServerUrl")!!, args.getString("dulcetDiscoverUsername")!!,
                    args.getString("dulcetDiscoverPassword")!!, allowLocalHttp = true),
                com.legitimateapps.dulcet.core.AccountConnector()::connect, store)
        }
        check(outcome is com.legitimateapps.dulcet.AccountConnectOutcome.Connected)
        val shots = DesignTourShots("tv-dulcet")
        ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            shots.step("personal-library", "empty") { select("library.open") && awaitTag("mylibrary.empty") && shots.settle() }
            shots.step("browse") { select("browse.open") && awaitTag("library.home.0.item.0") && shots.settle() }
            try {
                setFixtureMode("offline")
                shots.step("offline-library") {
                    select("library.refresh") && (awaitTag("library.home.0.retry", 10_000) || awaitTag("library.connection", 10_000)) && shots.settle()
                }
            } finally { setFixtureMode("normal") }
        }
        check(shots.unreached.isEmpty()) { "Unreached Dulcet TV routes: ${shots.unreached}" }
    }

    private fun setFixtureMode(mode: String) {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("dulcetDiscoverDisposable") == "true")
        val base = args.getString("dulcetDiscoverControlUrl")!!
        val origin = java.net.URI(base)
        check(origin.scheme == "http" && origin.host == "127.0.0.1" && origin.port > 0)
        val connection = java.net.URL("$base/control/scenario").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(org.json.JSONObject().put("name", mode).toString().toByteArray()) }
            val answer = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(answer.getString("scenario") == mode && answer.getBoolean("armed"))
        } finally { connection.disconnect() }
    }

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

    /** Focus, then the centre key, as a remote selects. */
    private fun select(tag: String): Boolean {
        if (tag in setOf("library.open", "search.open", "tv.account.open") && resumed() != TvSearchActivity::class.java.name) {
            // Independent sections recover through the real activity entry point after a failed
            // Back path. The failed step remains UNREACHED rather than invalidating every later page.
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(Intent(context, TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!awaitTag(tag)) return false
        }
        if (tag == "tv.account.signout" || tag == "tv.account.add-server" || tag.startsWith("quality.") || tag.startsWith("offline.")) {
            compose.onNodeWithTag("account.surface").performScrollToNode(hasTestTag(tag))
        }
        if (tag.startsWith("library.view.") || tag == "library.refresh") {
            // A return restores the category focus, while a fresh root focuses its first card.
            // Scroll the real root to the requested category; do not assume either focus state.
            if (tag.startsWith("library.view.") && !select("library.open")) return false
            if (!awaitTag("library.surface")) return false
            compose.onNodeWithTag("library.surface").performScrollToNode(hasTestTag(tag))
        }
        if (!focusTag(tag)) return false
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        return true
    }

    private fun chooseAlbumSort(key: String): Boolean {
        compose.onNodeWithTag("library.albums").performScrollToIndex(0)
        // Its lazy row preserves scroll on return. Start from a composed choice, then walk by
        // the exact offset so moving left never leaves the picker at its boundary.
        val keys = listOf("alphabeticalbyname", "alphabeticalbyartist", "newest", "recent", "frequent", "highest", "random", "byyear")
        val visible = keys.indexOfFirst { candidate ->
            compose.onAllNodes(hasTestTag("library.albums.sort.$candidate")).fetchSemanticsNodes().isNotEmpty()
        }
        if (visible < 0 || !focusTag("library.albums.sort.${keys[visible]}")) return false
        val offset = keys.indexOf(key) - visible
        repeat(kotlin.math.abs(offset)) { remote(if (offset > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT) }
        if (!awaitFocused("library.albums.sort.$key")) return false
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        return true
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
        check(select(tag))
        compose.waitUntil("$tag editing", 10_000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().any {
                it.config.contains(SemanticsActions.SetText)
            }
        }
        compose.onNodeWithTag(tag).performTextClearance()
        if (value.isNotEmpty()) compose.onNodeWithTag(tag).performTextInput(value)
        // Done moves focus away and leaves read-only mode, avoiding a keyboard over the capture.
        compose.onNodeWithTag(tag).performImeAction()
        compose.waitForIdle()
    }

    private fun focusTag(tag: String): Boolean {
        if (!awaitTag(tag)) return false
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return awaitFocused(tag)
    }

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

    /** A progress line in the instrumentation log, so an unreached screen names the sub-step. */
    private fun mark(what: String): Boolean { println("DESIGN TOUR $what"); return true }

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
