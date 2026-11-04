package com.legitimateapps.dulcet

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** The four navigation names share one fitted size; page content retains the full accessibility scale. */
@Composable
internal fun PhoneNavigationLabel(text: String, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val referenceStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
    val widestLabelWidth = measurer.measure("Listen Now", style = referenceStyle, maxLines = 1, softWrap = false)
        .size.width.toFloat()
    BoxWithConstraints {
        val availableWidth = with(density) { maxWidth.toPx() }
        val fit = if (widestLabelWidth > availableWidth && widestLabelWidth > 0f) {
            (availableWidth / widestLabelWidth * 0.9f).coerceAtMost(1f)
        } else 1f
        BasicText(text, onTextLayout = onTextLayout, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(fontSize = 12.sp * fit,
                color = LocalContentColor.current, textAlign = TextAlign.Center))
    }
}
