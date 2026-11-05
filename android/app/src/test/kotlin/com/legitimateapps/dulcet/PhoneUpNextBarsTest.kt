package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The playing bars read larger with large text, and take none of the title's width for it: a title must not wrap for them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port")
class PhoneUpNextBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun largeTextGrowsTheBarsAndLeavesTheTitleItsWidth() {
        var fontScale by mutableFloatStateOf(1f)
        var title = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Row(Modifier.width(200.dp)) {
                        Box(Modifier.weight(1f).onSizeChanged { title = it.width })
                        NowPlayingIndicator(false)
                    }
                }
            }
        }
        val playing = RuntimeEnvironment.getApplication().getString(R.string.up_next_playing)
        compose.waitForIdle()
        val titleAtDefault = title
        val bars = compose.onNodeWithContentDescription(playing).getBoundsInRoot().height
        fontScale = 2f
        compose.waitForIdle()
        assertEquals(titleAtDefault, title)
        val grown = compose.onNodeWithContentDescription(playing).getBoundsInRoot().height
        assertTrue(grown > bars, "the bars grow with the text: $bars to $grown")
    }
}
