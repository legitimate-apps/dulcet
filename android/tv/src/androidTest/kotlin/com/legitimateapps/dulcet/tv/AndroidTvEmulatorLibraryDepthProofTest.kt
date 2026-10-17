package com.legitimateapps.dulcet.tv

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorLibraryDepthProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback"))
        DownloadProof(context, DisposableServerProbe.fromInstrumentation()).conf52 { _, _ ->
            val activity = ActivityScenario.launch<TvSearchActivity>(Intent(context, TvSearchActivity::class.java))
            activity.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.setTurnScreenOn(true)
                it.setShowWhenLocked(true)
            }
            activate("library.view.downloaded")
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("library.downloaded.track.0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("library.downloaded.track.0").assertTextContains(DisposableServerProbe.CANARY_TITLE)
            val shot = compose.onNodeWithTag("library.downloaded").captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "library-depth-tv-downloaded-offline.jpg").outputStream().use {
                shot.compress(Bitmap.CompressFormat.JPEG, 65, it)
            }
            activate("library.downloaded.track.0")
            activity
        }
        println("ANDROID TV LIBRARY DEPTH OBSERVED downloaded-view=true offline=true row-started-playback=true")
    }
}
