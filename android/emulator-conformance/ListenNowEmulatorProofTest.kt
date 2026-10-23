package com.legitimateapps.dulcet.emulator

import android.app.Activity
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production activity, reader and server: home at launch, an album opens, tabs still work. */
@RunWith(AndroidJUnit4::class)
class ListenNowEmulatorProofTest {
    @get:Rule val compose = createHomeProofComposeRule()
    @Test fun homeLaunchAlbumAndCachedRelaunch() {
        val probe = DisposableServerProbe.fromInstrumentation()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shots = DesignTourShots("listen-now")
        PlaybackObserver(context).use {
            // Own cleanup before connecting: a failed launch must not leave this fixture account
            // behind for the next proof. TV apps advertise LEANBACK_LAUNCHER, not LAUNCHER.
            connectSavedAccount(context,probe)
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                ?: error("The installed app has no launch activity")
            ActivityScenario.launch<Activity>(intent).use { activity ->
                await("listenNow.surface")
                compose.onNodeWithTag("listenNow.open").assertIsSelected()
                scrollTo("listenNow.newest.item.0")
                if (isTelevision()) compose.onNodeWithTag("listenNow.open").assertIsFocused()
                shots.shoot("home-newest")
                activate("listenNow.newest.item.0")
                await("album.surface")
                shots.shoot("opened-album")
                activity.recreate()
                compose.waitForIdle()
            }
            ActivityScenario.launch<Activity>(intent).use {
                await("listenNow.surface")
                scrollTo("listenNow.newest.item.0")
                shots.shoot("relaunch")
                activate("library.open")
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("library.surface") or hasTestTag("mylibrary.surface")).fetchSemanticsNodes().isNotEmpty() }
                activate("listenNow.open")
                await("listenNow.surface")
            }
            // Gradle uninstalls the target after instrumentation, removing external-files.
            // Preserve only this proof's photos in a shell-owned disposable directory first.
            val source = java.io.File(context.getExternalFilesDir(null), "design-tour")
            val destination = "/data/local/tmp/dulcet-listen-now-${context.packageName}"
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
            }
            // executeShellCommand runs a command directly: shell operators/globs are not expanded.
            shell("mkdir -p $destination")
            val photos = source.listFiles { file -> file.name.startsWith("listen-now-") && file.extension == "jpg" }.orEmpty()
            check(photos.size == 3) { "Expected three Home photographs, observed ${photos.size}" }
            photos.forEach { photo ->
                val output = shell("cp ${photo.absolutePath} $destination/${photo.name}")
                check(output.isBlank()) { "Could not retain Home photograph: $output" }
            }
        }
        // The existing required CI Home method exercises this full chain only on the positive
        // advertised fixture. Ordinary OpenSubsonic keeps the original navigation proof.
        val extensions = probe.call("getOpenSubsonicExtensions").getJSONArray("openSubsonicExtensions")
        val playlistContextOffered = (0 until extensions.length()).any { index ->
            val extension = extensions.getJSONObject(index)
            extension.getString("name") == "dulcetHome" && (0 until extension.getJSONArray("versions").length())
                .any { version -> extension.getJSONArray("versions").getInt(version) == 1 }
        }
        if (playlistContextOffered) PlaylistPlayContextProof(compose).run()
    }
    private fun await(tag:String) {
        try {
            compose.waitUntil("$tag exists",30_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: AssertionError) {
            println(compose.onRoot(useUnmergedTree=true).printToString())
            throw failure
        }
    }
    private fun activate(tag: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (isTelevision()) {
            // TV Material controls activate through the centre key, as the shipped remote does.
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitForIdle()
        } else {
            compose.onNodeWithTag(tag).performClick()
        }
    }
    private fun isTelevision(): Boolean =
        (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    private fun scrollTo(tag:String) {
        compose.waitUntil("$tag can be reached",30_000) {
            runCatching {
                // A LazyRow's children are not searchable by its ancestor LazyColumn until
                // that shelf is composed. Bring the shelf into view, then select its first tile.
                compose.onNodeWithTag("listenNow.surface").performScrollToNode(hasTestTag(tag.substringBefore(".item.")))
                compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }
}
