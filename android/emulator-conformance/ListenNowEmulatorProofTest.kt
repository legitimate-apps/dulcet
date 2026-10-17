package com.legitimateapps.dulcet.emulator

import android.app.Activity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
        connectSavedAccount(context,probe)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        val shots = DesignTourShots("listen-now")
        PlaybackObserver(context).use {
            ActivityScenario.launch<Activity>(intent).use { activity ->
                await("listenNow.surface")
                compose.onNodeWithTag("listenNow.open").assertIsSelected()
                scrollTo("listenNow.newest.item.0")
                shots.shoot("home-newest")
                compose.onNodeWithTag("listenNow.newest.item.0").performClick()
                await("album.surface")
                shots.shoot("opened-album")
                activity.recreate()
                compose.waitForIdle()
            }
            ActivityScenario.launch<Activity>(intent).use {
                await("listenNow.surface")
                scrollTo("listenNow.newest.item.0")
                shots.shoot("relaunch")
                compose.onNodeWithTag("library.open").performClick()
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("library.surface") or hasTestTag("mylibrary.surface")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("listenNow.open").performClick()
                await("listenNow.surface")
            }
        }
    }
    private fun await(tag:String) {
        compose.waitUntil("$tag exists",30_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun scrollTo(tag:String) {
        compose.waitUntil("$tag can be reached",30_000) {
            runCatching {
                compose.onNodeWithTag("listenNow.surface").performScrollToNode(hasTestTag(tag))
                compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }
}
