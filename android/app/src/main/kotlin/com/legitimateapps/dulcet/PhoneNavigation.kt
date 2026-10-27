package com.legitimateapps.dulcet

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density

/** Keep the five bottom-bar names whole on a phone. Only these short labels use a bounded scale;
 * their full accessibility names and all page/dialog text retain the user's system setting. */
@Composable
internal fun PhoneNavigationLabel(text: String, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1f))) {
        Text(text, onTextLayout = onTextLayout)
    }
}
