package com.legitimateapps.dulcet

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual downloaded bytes, browsed through Library with the server cut, then played from that row. */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorLibraryDepthProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadProof(context, DisposableServerProbe.fromInstrumentation()).conf52 { _, _ ->
            val activity = ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
            activity.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.setTurnScreenOn(true)
                it.setShowWhenLocked(true)
            }
            compose.onNodeWithTag("library.open").performClick()
            compose.onNodeWithTag("library.view.downloaded").performScrollTo().performClick()
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("library.downloaded.track.0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("library.downloaded.track.0").assertTextContains(DisposableServerProbe.CANARY_TITLE)
            val shot = compose.onNodeWithTag("library.downloaded").captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "library-depth-phone-downloaded-offline.jpg").outputStream().use {
                shot.compress(Bitmap.CompressFormat.JPEG, 65, it)
            }
            compose.onNodeWithTag("library.downloaded.track.0").performClick()
            activity
        }
        println("ANDROID LIBRARY DEPTH OBSERVED downloaded-view=true offline=true row-started-playback=true")
    }
}
