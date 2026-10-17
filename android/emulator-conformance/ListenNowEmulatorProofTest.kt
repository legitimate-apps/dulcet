package com.legitimateapps.dulcet.emulator

import android.app.Activity
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
    @get:Rule val compose = createEmptyComposeRule()
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
        }
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
        val mode = instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) {
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
