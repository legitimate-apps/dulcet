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
import com.legitimateapps.dulcet.emulator.PlaybackObserver
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV's My Library / Browse split against the disposable server the runner names (spec §29.9):
 * the production TV app, driven by remote keys alone. With `dulcetExpectSplit=true` (a Dulcet
 * Server): the bar splits Library and Browse, an album's Add to Library is reached and pressed by
 * the D-pad, the server's own read-back holds the album, and My Library shows it; photographed at
 * each screen ([DesignTourShots]). With `dulcetExpectSplit=false` (the default, Navidrome): the bar is as before.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvMyLibraryProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun myLibrarySplit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) { "The TV proof needs a TV device" }
        val probe = DisposableServerProbe.fromInstrumentation()
        // Ordinary emulator CI targets Navidrome. Dulcet Server runs opt into its extension contract.
        val expectSplit = when (val argument = InstrumentationRegistry.getArguments().getString("dulcetExpectSplit")) {
            null, "false" -> false
            "true" -> true
            else -> error("dulcetExpectSplit must be true or false, was $argument")
        }
        val label = InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel") ?: "tv"
        connectSavedAccount(context, probe)
        // The next proof requires an empty installation, including when this proof fails.
        PlaybackObserver(context).use {
            val shots = DesignTourShots(label)
            // Check the fixture independently, so an omitted flag on Dulcet Server cannot silently
            // exercise only the fallback, and a discovery failure in the app cannot satisfy it.
            val extensions = probe.call("getOpenSubsonicExtensions").getJSONArray("openSubsonicExtensions")
            val advertisesLibrary = (0 until extensions.length()).map(extensions::getJSONObject).any { extension ->
                val versions = extension.getJSONArray("versions")
                extension.getString("name") == "dulcetLibrary" &&
                    (0 until versions.length()).any { versions.getInt(it) == 1 }
            }
            check(advertisesLibrary == expectSplit) {
                "Fixture dulcetLibrary v1=$advertisesLibrary, but dulcetExpectSplit=$expectSplit"
            }

            val albums = probe.call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "50"))
                .getJSONObject("albumList2").getJSONArray("album")
            check(albums.length() > 0) { "The fixture corpus has no albums" }

            val launch = Intent(Intent.ACTION_MAIN).setClassName(context, TvSearchActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<TvSearchActivity>(launch).use activity@{
                if (!expectSplit) {
                    step(shots, "library-nosplit") {
                        awaitFocused("library.home.0.item.0") && shots.settle(2_000) &&
                            noTag("browse.open", 5_000)
                    }
                    check(noTag("browse.open", 1)) { "A server without dulcetLibrary must not split the bar" }
                    step(shots, "catalog-album") {
                        check(select("library.view.albums")) { "The catalog Albums action is absent" }
                        check(awaitFocused("library.albums.item.0")) { "The catalog album card did not receive focus" }
                        remote(KeyEvent.KEYCODE_DPAD_CENTER)
                        awaitTag("album.surface") && awaitFocused("album.play") && noTag("album.library", 1)
                    }
                    return@activity
                }
                step(shots, "mylibrary") {
                    awaitTag("browse.open") && awaitTag("mylibrary.view.albums") && shots.settle(2_000)
                }
                // Browse is the catalog; its Albums view's first card opens an album.
                step(shots, "browse") {
                    select("browse.open") && awaitTag("library.home.0.item.0") && shots.settle(2_000)
                }
                step(shots, "album-add") {
                    select("library.view.albums") && awaitFocused("library.albums.item.0")
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    awaitTag("album.surface") && awaitFocused("album.play") && shots.settle()
                }
                // The album the remote opened, named on screen, resolved to its id through the probe.
                val title = compose.onAllNodes(hasTestTag("album.title")).fetchSemanticsNodes()
                    .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
                    .firstOrNull()?.text ?: error("The album screen names nothing")
                val albumId = (0 until albums.length()).map(albums::getJSONObject)
                    .firstOrNull { it.getString("name") == title }?.getString("id")
                    ?: error("The corpus has no album titled $title")
                // RIGHT along the album's actions to its Add to Library, then the centre key.
                var rights = 0
                while (!focused("album.library") && rights < 10) { remote(KeyEvent.KEYCODE_DPAD_RIGHT); rights += 1 }
                check(focused("album.library")) { "Add to Library is not reachable by remote from the album's Play" }
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitServer("the album in getDulcetLibrary after Add") {
                    probe.call("getDulcetLibrary", mapOf("type" to "album"))
                        .getJSONObject("dulcetLibrary").optJSONArray("album")?.let { inLibrary ->
                            (0 until inLibrary.length()).any { inLibrary.getJSONObject(it).getString("id") == albumId }
                        } == true
                }
                step(shots, "mylibrary-added") {
                    select("library.open") && awaitTag("mylibrary.recent.album.0") && shots.settle(2_000)
                }
            }
            check(shots.unreached.isEmpty()) { "The TV My Library proof could not reach: ${shots.unreached}" }
            println("MYLIBRARY TV PROOF done label=$label split=$expectSplit")
        }
    }

    /** A proof cannot continue past a screen the screenshot tour failed to reach. */
    private fun step(shots: DesignTourShots, screen: String, reach: () -> Boolean) {
        shots.step(screen, reach)
        check(shots.unreached.isEmpty()) { "The My Library proof could not reach: ${shots.unreached}" }
    }

    /** Focus, then the centre key, as a remote selects. */
    private fun select(tag: String): Boolean {
        // Home's first card takes default focus and can scroll its sections out of composition.
        // Reveal them with the remote before selecting one, as the catalog host proof does.
        if (tag.startsWith("library.view.")) {
            var steps = 0
            repeat(3) {
                if (compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                    println("MYLIBRARY TV remote-reveal before-UP focused=${focusedTags()}")
                    remote(KeyEvent.KEYCODE_DPAD_UP)
                    steps += 1
                    println("MYLIBRARY TV remote-reveal after-UP focused=${focusedTags()}")
                }
            }
            println("MYLIBRARY TV remote-reveal tag=$tag up-steps=$steps composed=${!noTag(tag, 1)}")
        }
        if (!awaitTag(tag)) return false
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        return true
    }

    private fun focusedTags(): List<String> = compose.onAllNodes(SemanticsMatcher("focused") {
        it.config.getOrElse(SemanticsProperties.Focused) { false }
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }

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

    /** The server's own answer, polled: a write's send is asynchronous, after the UI has shown it. */
    private fun awaitServer(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.nanoTime() < deadline) { "never on the server: $what" }
            Thread.sleep(250)
        }
    }
}
