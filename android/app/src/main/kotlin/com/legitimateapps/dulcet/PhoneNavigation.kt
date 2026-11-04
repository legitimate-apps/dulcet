package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.legitimateapps.dulcet.shared.R as SharedR

internal const val LISTEN_NOW_LABEL = "Listen Now"

/** The gap Material's navigation bar leaves between its items. The whole-word tests catch a change. */
private val NAVIGATION_ITEM_GAP = 8.dp
private const val NAVIGATION_TAB_COUNT = 4
/** Items round to whole pixels, so each can be up to this much narrower than an exact share. */
private val NAVIGATION_ROUNDING_SLACK = 1.dp
private const val NAVIGATION_SIZE_STEP_SP = 0.5f

/**
 * One type size across all four labels: the font scale honoured as far as the widest word allows on
 * one line, and never smaller than the unscaled size. Derived from the bar's width rather than each
 * item's measured width, since items differ by a pixel and would otherwise land on different sizes.
 */
@Composable
internal fun PhoneNavigationLabel(text: String, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val style = LocalTextStyle.current
    BasicText(text, onTextLayout = onTextLayout, maxLines = 1, softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = style.copy(fontSize = phoneNavigationLabelSize(), color = LocalContentColor.current,
            textAlign = TextAlign.Center))
}

@Composable
private fun phoneNavigationLabelSize(): TextUnit {
    val style = LocalTextStyle.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = listOf(LISTEN_NOW_LABEL, stringResource(R.string.tab_library),
        stringResource(SharedR.string.library_browse), stringResource(R.string.tab_search))
    val insets = NavigationBarDefaults.windowInsets.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val barWidth = LocalConfiguration.current.screenWidthDp.dp -
        insets.calculateStartPadding(direction) - insets.calculateEndPadding(direction)
    val itemWidth = (barWidth - NAVIGATION_ITEM_GAP * (NAVIGATION_TAB_COUNT - 1)) / NAVIGATION_TAB_COUNT -
        NAVIGATION_ROUNDING_SLACK
    return remember(labels, style, density, itemWidth) {
        val available = with(density) { itemWidth.roundToPx() }
        val largest = style.fontSize.value
        val smallest = largest / density.fontScale.coerceAtLeast(1f)
        var size = largest
        while (size - NAVIGATION_SIZE_STEP_SP >= smallest && labels.any { label ->
                measurer.measure(label, style.copy(fontSize = size.sp), maxLines = 1, softWrap = false)
                    .size.width > available
            }) size -= NAVIGATION_SIZE_STEP_SP
        size.sp
    }
}
