package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.emulator.DesignTourShots
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.PlaybackObserver
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import com.legitimateapps.dulcet.search.SearchObservation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The production grouped search, with a real server and remote movement across its preview boundary. */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorGroupedSearchProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun theRemoteExpandsAlbumsWalksPastThePreviewAndReturnsToTheBar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback"))
        val probe = DisposableServerProbe.fromInstrumentation()
        val query = listOf("Dulcet", "Fixture", "Album").firstOrNull { candidate ->
            (probe.call("search3", mapOf("query" to candidate, "albumCount" to "100", "artistCount" to "0", "songCount" to "0"))
                .getJSONObject("searchResult3").optJSONArray("album")?.length() ?: 0) > 4
        } ?: error("The disposable corpus needs a query with at least five albums")
        connectSavedAccount(context, probe)
        PlaybackObserver(context).use {
            ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java)).use { activity ->
                activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                await("the navigation bar") { exists("search.open") }
                // Establish the search route. Every movement under proof below is an actual remote key.
                compose.onNodeWithTag("search.open").performSemanticsAction(SemanticsActions.RequestFocus)
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                await("search's query") { exists("search.query") }
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                check(focused("search.query")) { "DOWN from Search did not reach its query" }
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                InstrumentationRegistry.getInstrumentation().sendStringSync(query)
                await("the server's grouped albums") {
                    val state = compose.onNodeWithTag("search.results").fetchSemanticsNode().config[SearchObservation]
                    state.query == query && state.scope == com.legitimateapps.dulcet.core.AndroidLibrarySearchScope.ServerAndDevice &&
                        !state.serverPending && state.rows.count { it.item.type == SearchResultType.Album } > 4
                }
                val rows = compose.onNodeWithTag("search.results").fetchSemanticsNode().config[SearchObservation].rows
                val albums = rows.withIndex().filter { it.value.item.type == SearchResultType.Album }
                remote(KeyEvent.KEYCODE_BACK) // Dismiss the on-screen keyboard, retaining the query route.
                await("the query after dismissing the keyboard") { focused("search.query") }
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                downTo("search.result.${albums.first().index}")
                remote(KeyEvent.KEYCODE_DPAD_UP)
                await("the collapsed group's See all") { focused("search.seeAll.album") }
                println("GROUPED SEARCH TV OBSERVED preview=${albums.size} focused=search.seeAll.album")
                val shots = DesignTourShots("tv-grouped-search")
                if (DesignTourShots.requested()) shots.shoot("see-all-focused")
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                check(focused("search.result.${albums.first().index}")) { "See all DOWN did not reach its first album" }
                remote(KeyEvent.KEYCODE_DPAD_UP)
                await("See all again") { focused("search.seeAll.album") }
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                await("the first expanded album") { focused("search.result.${albums.first().index}") }
                check(!exists("search.seeAll.album")) { "The expanded group still offers See all" }
                downTo("search.result.${albums[4].index}")
                compose.onNodeWithTag("search.result.${albums[4].index}").assertTextContains(albums[4].value.item.title)
                val card = compose.onNodeWithTag("search.result.${albums[4].index}").fetchSemanticsNode().boundsInRoot
                if (DesignTourShots.requested()) shots.shoot("fifth-album-focused")
                repeat(64) { if (!focused("search.query")) remote(KeyEvent.KEYCODE_DPAD_UP) }
                check(focused("search.query")) { "UP from expanded albums never returned to the query" }
                remote(KeyEvent.KEYCODE_DPAD_UP)
                check(focused("search.open")) { "UP from the query never returned to the bar" }
                val bar = compose.onNodeWithTag("search.open").fetchSemanticsNode().boundsInRoot
                check(bar.bottom <= card.top) { "The navigation bar covered the fifth album" }
                println("GROUPED SEARCH TV OBSERVED expanded-fifth=${albums[4].value.item.id.rawId} bar-return=true remote-only=true")
            }
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun focused(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
    private fun await(what: String, condition: () -> Boolean) = compose.waitUntil(what, 30_000) { runCatching(condition).getOrDefault(false) }
    private fun remote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
    private fun downTo(tag: String) {
        repeat(64) { if (!focused(tag)) remote(KeyEvent.KEYCODE_DPAD_DOWN) }
        check(focused(tag)) { "DOWN did not reach $tag" }
    }
}
