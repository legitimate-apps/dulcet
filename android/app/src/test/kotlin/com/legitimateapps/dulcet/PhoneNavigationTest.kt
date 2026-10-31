package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun largestFontKeepsNavigationWordsWholeAndBodyScaled() = verifyNavigation()

    @Test @Config(qualifiers = "w360dp-h800dp-port")
    fun narrowPhoneKeepsAllSixControlsReadableAtLargestFont() = verifyNavigation()

    private fun verifyNavigation() {
        val labels = listOf("Listen Now", "Discover", "Library", "Browse", "Search", "Account")
        val layouts = mutableMapOf<String, TextLayoutResult>()
        val labelBounds = mutableMapOf<String, Rect>()
        val iconBounds = mutableMapOf<String, Rect>()
        var bodyScale = 0f
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DulcetTheme {
                    bodyScale = LocalDensity.current.fontScale
                    NavigationBar {
                        labels.forEach { label -> NavigationBarItem(
                            selected = label == "Search", onClick = {},
                            icon = { Box(Modifier.size(24.dp).onGloballyPositioned { iconBounds[label] = it.boundsInRoot() }) },
                            label = { Box(Modifier.onGloballyPositioned { labelBounds[label] = it.boundsInRoot() }) {
                                PhoneNavigationLabel(label, onTextLayout = { layouts[label] = it })
                            } },
                        ) }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(2f, bodyScale, "Body text keeps the requested accessibility scale")
            assertEquals(labels.toSet(), layouts.keys, "Positive actual layout for every label")
            labels.forEach { label ->
                val result = layouts.getValue(label)
                assertTrue(result.size.width > 20, "Positive native text measurement: $label ${result.size}")
                assertEquals(1, result.lineCount, "Navigation word split: $label")
                assertFalse(result.hasVisualOverflow, "Navigation word clipped: $label")
                assertEquals(label.length, result.getLineEnd(0, visibleEnd = true), "Every navigation word is drawn: $label")
                val glyphCenter = labelBounds.getValue(label).left + (result.getLineLeft(0) + result.getLineRight(0)) / 2f
                assertTrue(abs(glyphCenter - iconBounds.getValue(label).center.x) <= 1.5f,
                    "Navigation glyphs must be centered under their icon: $label glyph=$glyphCenter icon=${iconBounds.getValue(label).center.x}")
                assertEquals(label, result.layoutInput.text.text, "Accessible full label remains intact")
            }
        }
    }
}
