package com.legitimateapps.dulcet.tv

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.OfflineLibraryProof
import com.legitimateapps.dulcet.emulator.await
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class AndroidTvOfflineLibraryProofTest {
    internal fun runOfflineProof(compose: androidx.compose.ui.test.junit4.ComposeTestRule) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) { "The TV proof needs a TV device" }
        OfflineLibraryProof(context, DisposableServerProbe.fromInstrumentation()).run(
            launch = { account, rawId ->
                val intent = if (rawId == null) android.content.Intent(context, TvSearchActivity::class.java)
                    else PlaybackIntents.playTrack(context, account.id, rawId, DisposableServerProbe.CANARY_TITLE)
                        .setClassName(context, TvPlaybackActivity::class.java.name)
                if (rawId == null) ActivityScenario.launch<TvSearchActivity>(intent)
                    else ActivityScenario.launch<TvPlaybackActivity>(intent)
            },
            turnAutomaticOffInSettings = {
                fun remoteClick(tag: String) {
                    compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
                    compose.waitForIdle()
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                }
                remoteClick("tv.account.open")
                compose.onNodeWithTag("account.surface").performScrollToNode(hasTestTag("offline.automatic"))
                await("Offline Music settings") {
                    compose.onAllNodes(androidx.compose.ui.test.hasTestTag("offline.automatic")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("offline.automatic").performScrollTo()
                com.legitimateapps.dulcet.emulator.DesignTourShots("offline-tv").shoot("offline-music-settings")
                remoteClick("offline.automatic")
            },
        )
    }
}
