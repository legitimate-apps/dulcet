package com.legitimateapps.dulcet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Past this, a glyph beside text stops growing: at font scale 2 it would crowd its row and gain nothing. */
private const val TEXT_ICON_MAX_SCALE = 1.5f

/**
 * The size of a glyph that reads with the text beside it. A dp size stays put while sp text grows, so at large text
 * the glyph looks like a speck next to its label. Default-scale layouts are unchanged.
 */
@Composable
public fun textScaledIconSize(base: Dp): Dp =
    base * LocalDensity.current.fontScale.coerceIn(1f, TEXT_ICON_MAX_SCALE)
