package com.legitimateapps.dulcet

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.StreamingQuality
import com.legitimateapps.dulcet.core.StreamingQualityPreference
import com.legitimateapps.dulcet.playback.StreamingQualitySettings
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The signed-in phone app's account dialog carries the streaming-quality choice (spec §12.5), and a
 * choice made there is the one the playback service reads: saved on the device in the core's form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = AndroidSearchTestApplication::class)
class PhoneStreamingQualityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun clean() {
        RuntimeEnvironment.getApplication().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aCellularCapChosenInTheAccountDialogIsSavedForTheNextSong() {
        val settings = StreamingQualitySettings.get(RuntimeEnvironment.getApplication())
        assertEquals(StreamingQualityPreference.Default, settings.preference.value, "nothing chosen is Original on both")

        compose.onNodeWithTag("account.open").performClick()
        compose.onNodeWithTag("quality.section").assertIsDisplayed()
        compose.onNodeWithTag("quality.unmetered").assertTextContains("Original")
        compose.onNodeWithTag("quality.metered").performClick()
        compose.onNodeWithTag("quality.metered.128").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("quality.metered").assertTextContains("128 kbps")
        val expected = StreamingQualityPreference(StreamingQuality.Original, StreamingQuality.Kbps128)
        assertEquals(expected, settings.preference.value)
        // What the service reads after a restart: the stored value, not this process's memory.
        val stored = RuntimeEnvironment.getApplication().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString("preference", null)
        assertEquals(expected, StreamingQualityPreference.decode(stored))
    }

    private companion object {
        const val PREFERENCES = "dulcet.streaming-quality"
    }
}
