package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
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

    @Test fun largestFontKeepsNavigationWordsWholeAndBodyScaled() {
        val labels = listOf("Listen Now", "Library", "Browse", "Search", "Account")
        val layouts = mutableMapOf<String, TextLayoutResult>()
        var bodyScale = 0f
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DulcetTheme {
                    bodyScale = LocalDensity.current.fontScale
                    NavigationBar {
                        labels.forEach { label -> NavigationBarItem(
                            selected = label == "Search", onClick = {}, icon = {},
                            label = { PhoneNavigationLabel(label, onTextLayout = { layouts[label] = it }) },
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
                assertEquals(label, result.layoutInput.text.text, "Accessible full label remains intact")
            }
        }
    }
}
