package com.legitimateapps.dulcet

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density

/** The four navigation names share one scale; page content retains the full accessibility scale. */
@Composable
internal fun PhoneNavigationLabel(text: String, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.1f))) {
        BasicText(text, onTextLayout = onTextLayout, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(color = LocalContentColor.current, textAlign = TextAlign.Center))
    }
}
