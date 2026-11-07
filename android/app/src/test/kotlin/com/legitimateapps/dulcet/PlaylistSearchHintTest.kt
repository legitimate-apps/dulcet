package com.legitimateapps.dulcet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Public playlists' search field says its whole hint at the largest font, on the narrowest phone the tour uses. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PlaylistSearchHintTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theHintIsWholeAtTheLargestFont() {
        val hint = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.discover_playlists_query)
        compose.setContent {
            val outer = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(outer.density, 2f)) {
                MaterialTheme { PhoneSearchField("", {}, {}, "discover.playlists.query", hint, avatar = false) }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(hint, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.last()
        // Ellipsis only: at this font the field's line is taller than the field, for any hint, and that is the field's own height.
        assertFalse(layout.isLineEllipsized(0), "\"$hint\" is cut: ${layout.size} for ${layout.layoutInput.constraints}")
    }
}
