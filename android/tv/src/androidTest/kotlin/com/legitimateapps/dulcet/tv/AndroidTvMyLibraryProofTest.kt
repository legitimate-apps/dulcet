package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.DesignTourShots
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV's My Library / Browse split against the disposable server the runner names (spec §29.9):
 * the production TV app, driven by remote keys alone. With `dulcetExpectSplit=true` (a Dulcet
 * Server): the bar splits Library and Browse, an album's Add to Library is reached and pressed by
 * the D-pad, the server's own read-back holds the album, and My Library shows it; photographed at
 * each screen ([DesignTourShots]). With `dulcetExpectSplit=false` (Navidrome): the bar is as before.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvMyLibraryProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun myLibrarySplit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) { "The TV proof needs a TV device" }
        val probe = DisposableServerProbe.fromInstrumentation()
        val expectSplit = InstrumentationRegistry.getArguments().getString("dulcetExpectSplit") != "false"
        val label = InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel") ?: "tv"
        connectSavedAccount(context, probe)
        val shots = DesignTourShots(label)

        val albums = probe.call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "50"))
            .getJSONObject("albumList2").getJSONArray("album")
        check(albums.length() > 0) { "The fixture corpus has no albums" }
        val albumId = albums.getJSONObject(0).getString("id")

        val launch = Intent(Intent.ACTION_MAIN).setClassName(context, TvSearchActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<TvSearchActivity>(launch).use {
            if (!expectSplit) {
                shots.step("library-nosplit") {
                    awaitTag("library.open") && awaitTag("library.home.0.item.0") && shots.settle(2_000) &&
                        noTag("browse.open", 5_000)
                }
                check(noTag("browse.open", 1)) { "A server without dulcetLibrary must not split the bar" }
                return@use
            }
            shots.step("mylibrary") {
                awaitTag("browse.open") && awaitTag("mylibrary.view.albums") && shots.settle(2_000)
            }
            // Browse is the catalog; its home's first card opens an album.
            shots.step("browse") {
                select("browse.open") && awaitTag("library.home.0.item.0") && shots.settle(2_000)
            }
            shots.step("album-add") {
                awaitFocused("library.home.0.item.0")
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitTag("album.surface") && awaitFocused("album.play") && shots.settle()
            }
            // RIGHT along the album's actions to its Add to Library, then the centre key.
            var rights = 0
            while (!focused("album.library") && rights < 10) { remote(KeyEvent.KEYCODE_DPAD_RIGHT); rights += 1 }
            check(focused("album.library")) { "Add to Library is not reachable by remote from the album's Play" }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            val inLibrary = probe.call("getDulcetLibrary", mapOf("type" to "album"))
                .getJSONObject("dulcetLibrary").optJSONArray("album")
            check(inLibrary != null && (0 until inLibrary.length()).any { inLibrary.getJSONObject(it).getString("id") == albumId }) {
                "The server's own getDulcetLibrary does not hold the album added by remote"
            }
            shots.step("mylibrary-added") {
                select("library.open") && awaitTag("mylibrary.recent.album.0") && shots.settle(2_000)
            }
        }
        check(shots.unreached.isEmpty()) { "The TV My Library proof could not reach: ${shots.unreached}" }
        println("MYLIBRARY TV PROOF done label=$label split=$expectSplit")
    }

    /** Focus, then the centre key, as a remote selects. */
    private fun select(tag: String): Boolean {
        if (!awaitTag(tag)) return false
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        return true
    }

    private fun focused(tag: String): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
            .fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun awaitFocused(tag: String): Boolean = runCatching {
        compose.waitUntil("$tag focused", 30_000) { focused(tag) }
    }.isSuccess

    private fun awaitTag(tag: String, timeoutMillis: Long = 30_000): Boolean = runCatching {
        compose.waitUntil("$tag on screen", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private fun noTag(tag: String, timeoutMillis: Long): Boolean = runCatching {
        compose.waitUntil("$tag absent", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }.isSuccess

    private fun remote(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }
}
