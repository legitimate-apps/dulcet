package com.legitimateapps.dulcet

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Bottom-bar names follow the system font scale; auto-size only guards against real overflow. */
@Composable
internal fun PhoneNavigationLabel(text: String, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    BasicText(text, onTextLayout = onTextLayout, maxLines = 1, softWrap = false,
        style = LocalTextStyle.current.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
        autoSize = TextAutoSize.StepBased(minFontSize = 6.sp,
            maxFontSize = LocalTextStyle.current.fontSize, stepSize = 0.5.sp))
}
