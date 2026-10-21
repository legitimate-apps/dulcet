package com.legitimateapps.dulcet

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

class AndroidEmulatorOfflineLibraryProofTest {
    internal fun runOfflineProof(compose: androidx.compose.ui.test.junit4.ComposeTestRule) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        OfflineLibraryProof(context, DisposableServerProbe.fromInstrumentation()).run(
            launch = { account, rawId ->
                val intent = if (rawId == null) context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                    else PlaybackIntents.playTrack(context, account.id, rawId, DisposableServerProbe.CANARY_TITLE)
                        .setClassName(context, PLAYBACK_ENTRY_ALIAS)
                ActivityScenario.launch<MainActivity>(intent)
            },
            turnAutomaticOffInSettings = {
                compose.onNodeWithTag("account.open").performClick()
                await("Offline Music settings") {
                    compose.onAllNodes(androidx.compose.ui.test.hasTestTag("offline.automatic")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("offline.automatic").performScrollTo()
                com.legitimateapps.dulcet.emulator.DesignTourShots("offline-phone").shoot("offline-music-settings")
                compose.onNodeWithTag("offline.automatic").performClick()
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            },
        )
    }
}
