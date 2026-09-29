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
 * screen at launch, Library, Albums, an album, Artists, an artist, Favourites, Now Playing with Up
 * Next while playing, and Search with results -- last, so a failure there cannot cost the others.
 * `tools/design-tour-android` runs it.
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
        // A freshly booted emulator's first request to the host can be reset; the tour retries it.
        retrying(3) { starFixtures(probe) }
        awaitQueuedBroadcastsDelivered()
        connectSavedAccount(context, probe)
        val shots = DesignTourShots(label)
        val launch = Intent(Intent.ACTION_MAIN).setClassName(context, TvSearchActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<TvSearchActivity>(launch).use {
            shots.step("home") { awaitTag("search.open") && awaitTag("library.open") && shots.settle() }
            shots.step("library") { select("library.open") && awaitTag("library.home.0.item.0") && shots.settle() }
            shots.step("albums") { select("library.view.albums") && awaitTag("library.albums.item.0") && shots.settle() }
            shots.step("album") { openCard("library.albums", ALBUM) && awaitTag("album.title") && shots.settle() }
            back(2)
            shots.step("artists") { select("library.view.artists") && awaitTag("library.artists.item.0") && shots.settle() }
            shots.step("artist") { openCard("library.artists", ARTIST) && awaitTag("artist.title") && shots.settle() }
            back(2)
            shots.step("favourites") { select("library.view.favourites") && awaitTag("library.favourites") && shots.settle(2_000) }
            back(1)
            shots.step("now-playing") {
                select("library.view.albums") && openCard("library.albums", ALBUM) && awaitFocused("album.play")
                    && run { remote(KeyEvent.KEYCODE_DPAD_CENTER); true }
                    && runCatching { await("the TV's Now Playing in front", 30_000) { resumed() == TvPlaybackActivity::class.java.name } }.isSuccess
                    && awaitTag("tv.player.title") && awaitTag("tv.player.upnext.0") && shots.settle(2_500)
            }
            shots.step("up-next") { focusTag("tv.player.upnext.1") && shots.settle() }
            back(1)
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
        }
        check(shots.unreached.isEmpty()) { "The design tour could not reach: ${shots.unreached}" }
        println("DESIGN TOUR done label=$label")
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

    /** Focus, then the centre key, as a remote selects. */
    private fun select(tag: String): Boolean {
        if (!focusTag(tag)) return false
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
    }
}
