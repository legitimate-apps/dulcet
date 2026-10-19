package com.legitimateapps.dulcet

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * The phone's My Library / Browse split against the disposable server the runner names (spec §29.9):
 * the production app, production reader and core `AndroidMyLibrary`, driven by touch. With
 * `dulcetExpectSplit=true` (a Dulcet Server): the Library and Browse tabs split, an album added from
 * Browse appears in Library at once and on the server's own read-back, and a remove takes it out;
 * each screen is photographed ([DesignTourShots]). With `dulcetExpectSplit=false` (the default, Navidrome): the
 * app is exactly as before — no Browse tab, and no library endpoint is ever called (the probe reads
 * the server log is not available, so the no-call claim is the Robolectric integration test's).
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorMyLibraryProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun automaticLibraryRetentionAndSameResponseCachePlayOffline() {
        // This proof requires the extension server; the ordinary Navidrome matrix still
        // exercises catalog fallback through myLibrarySplit below.
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("dulcetExpectSplit") == "true")
        AndroidEmulatorOfflineLibraryProofTest().runOfflineProof(compose)
    }

    @Test fun myLibrarySplit() {
        val probe = DisposableServerProbe.fromInstrumentation()
        // Ordinary emulator CI targets Navidrome. Dulcet Server runs opt into its extension contract.
        val expectSplit = when (val argument = InstrumentationRegistry.getArguments().getString("dulcetExpectSplit")) {
            null, "false" -> false
            "true" -> true
            else -> error("dulcetExpectSplit must be true or false, was $argument")
        }
        val label = InstrumentationRegistry.getArguments().getString("dulcetDesignTourLabel") ?: "phone"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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

            // An album the library does not hold yet, chosen from the catalog the server serves — the
            // first with a real name, so the shots show one (the corpus's first is a singles bucket).
            val albums = probe.call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "50"))
                .getJSONObject("albumList2").getJSONArray("album")
            check(albums.length() > 0) { "The fixture corpus has no albums" }
            val picked = (0 until albums.length()).map(albums::getJSONObject)
                .firstOrNull { !it.getString("name").startsWith("[") } ?: albums.getJSONObject(0)
            val albumId = picked.getString("id")
            val albumName = picked.getString("name")

            ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use activity@{
                if (!expectSplit) {
                    // Navidrome: nothing splits, and the library is the whole catalog as it always was.
                    step(shots, "library-nosplit") {
                        click("library.open")
                        awaitTag("library.home.0.item.0") && shots.settle(2_000) &&
                            noTag("browse.open", 5_000)
                    }
                    check(noTag("browse.open", 1)) { "A server without dulcetLibrary must not split the tabs" }
                    step(shots, "catalog-album") {
                        click("library.view.albums")
                        check(awaitTag("library.albums.item.0")) { "The catalog has no album card" }
                        click("library.albums.item.0")
                        awaitTag("album.surface") && awaitTag("album.play") && noTag("album.library", 1)
                    }
                    return@activity
                }
                step(shots, "browse") {
                    click("browse.open")
                    awaitTag("library.surface") && awaitTag("library.home.0.item.0") && shots.settle(2_000)
                }
                step(shots, "album-add") {
                    click("browse.open")
                    click("library.view.albums")
                    awaitTag("library.albums.item.0")
                    compose.onNodeWithTag("library.albums").performScrollToNode(
                        androidx.compose.ui.test.hasText(albumName))
                    compose.onNode(androidx.compose.ui.test.hasText(albumName)
                        .and(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("library.albums"))))
                        .performClick()
                    awaitTag("album.surface") && awaitTag("album.library") && shots.settle()
                }
                // Add it from its header; the check shows at once, and the server records it.
                click("album.library")
                check(awaitSelected("album.library", 10_000)) { "The album's membership did not show after Add" }
                awaitServer("the album in getDulcetLibrary after Add") {
                    probe.call("getDulcetLibrary", mapOf("type" to "album"))
                        .getJSONObject("dulcetLibrary").optJSONArray("album")?.let { inLibrary ->
                            (0 until inLibrary.length()).any { inLibrary.getJSONObject(it).getString("id") == albumId }
                        } == true
                }
                step(shots, "mylibrary") {
                    click("library.open")
                    awaitTag("mylibrary.surface") && shots.settle(2_000)
                }
                check(awaitTag("mylibrary.recent.album.0", 10_000)) { "The added album never showed in My Library" }
                // And Remove takes it out, on the server too.
                click("mylibrary.recent.album.0")
                awaitTag("album.library")
                click("album.library")
                awaitServer("the album out of getDulcetLibrary after Remove") {
                    val after = probe.call("getDulcetLibrary", mapOf("type" to "album"))
                        .getJSONObject("dulcetLibrary").optJSONArray("album")
                    after == null || (0 until after.length()).none { after.getJSONObject(it).getString("id") == albumId }
                }
            }
            check(shots.unreached.isEmpty()) { "The My Library tour could not reach: ${shots.unreached}" }
            println("MYLIBRARY PROOF done label=$label split=$expectSplit")
        }
    }

    /** A proof cannot continue past a screen the screenshot tour failed to reach. */
    private fun step(shots: DesignTourShots, screen: String, reach: () -> Boolean) {
        shots.step(screen, reach)
        check(shots.unreached.isEmpty()) { "The My Library proof could not reach: ${shots.unreached}" }
    }

    private fun click(tag: String) {
        check(awaitTag(tag)) { "No touch target: $tag" }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = 30_000): Boolean = runCatching {
        compose.waitUntil("$tag on screen", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private fun noTag(tag: String, timeoutMillis: Long): Boolean = runCatching {
        compose.waitUntil("$tag absent", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
        }
    }.isSuccess

    private fun awaitSelected(tag: String, timeoutMillis: Long): Boolean = runCatching {
        compose.waitUntil("$tag selected", timeoutMillis) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
                .any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false } }
        }
    }.isSuccess

    /** The server's own answer, polled: a write's send is asynchronous, after the UI has shown it. */
    private fun awaitServer(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!runCatching(condition).getOrDefault(false)) {
            check(System.nanoTime() < deadline) { "never on the server: $what" }
            Thread.sleep(250)
        }
    }
}
