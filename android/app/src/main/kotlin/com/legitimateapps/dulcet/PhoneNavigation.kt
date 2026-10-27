package com.legitimateapps.dulcet

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Keep the bottom-bar names whole on a phone. Only these short labels use a bounded scale;
 * their full accessibility names and all page/dialog text retain the user's system setting. */
@Composable
internal fun PhoneNavigationLabel(text: String, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1f))) {
        BasicText(text, onTextLayout = onTextLayout, maxLines = 1,
            style = LocalTextStyle.current.copy(color = LocalContentColor.current),
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp,
                maxFontSize = LocalTextStyle.current.fontSize, stepSize = 0.5.sp))
    }
}
