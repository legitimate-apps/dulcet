package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The phone's layout tokens. Colour lives in DulcetTheme.kt; every other layout value a screen uses comes from here.
 * A screen file names a token, never a dp literal. When no token fits, add one here with a one-line role comment.
 * The names and values match docs/DESIGN-CONVENTIONS (Android): an 8 dp rhythm with 4 and 12 for optical pairs.
 */

/** The phone spacing scale. An 8 dp rhythm; 4 and 12 are for optical pairs inside one component. */
internal object DulcetSpacing {
    /** Inside one control group: icon to label, rank to art, status glyph to More. */
    val xs = 4.dp
    /** Between related lines; between buttons in one group. */
    val s = 8.dp
    /** Between cards; between groups inside a header. */
    val m = 12.dp
    /** Page gutter; card and notice inner padding; empty-state icon to title. */
    val l = 16.dp
    /** Above every section heading; before an action group. */
    val xl = 24.dp
    /** The top of a screen without a top app bar; around an empty state. */
    val xxl = 32.dp

    /** Start and end padding of every page's content: the one start keyline. */
    val pageGutter = l
    /** Between cards in rails **and** in grids. */
    val cardGap = m
    /** Above every section heading. */
    val sectionGap = xl
    /** Below a section heading, to the content's visible top. */
    val headingToContent = s
}

/** Element sizes. Media rows follow the restyle spec: 56 dp art, 48 dp targets. */
internal object DulcetSizes {
    /** The minimum size of anything a finger presses. */
    val touchTarget = 48.dp
    /** Every Material glyph, including a row's status glyph and its reserved box. */
    val icon = 24.dp
    /** Media row art: songs, albums, artists' monograms, the queue, search results. */
    val rowArt = 56.dp
    /** Art in a sheet header and the mini player. */
    val compactArt = 48.dp
    /** A track number's slot on an album page. */
    val numberSlot = 40.dp
    /** Tonal artwork tile in a genre row. */
    val genreTile = 40.dp
    /** A ranked row's rank slot (minimum); the text column then starts at 120 dp. */
    val rankSlot = 28.dp
    /** A rail card's width at the default font; see [Modifier.railTileWidth]. */
    val tileWidth = 160.dp
    /** An adaptive grid's minimum cell (spec: 156 dp). */
    val gridMinCell = 156.dp
    /** The glyph of an empty state. */
    val emptyIcon = 48.dp
    /** A detail page's hero art never grows past this. */
    val heroMax = 280.dp
    val dialogMinWidth = 280.dp
    val dialogMaxWidth = 560.dp
    /** An empty state's block never grows past this, so its lines stay readable on a tablet. */
    val emptyMaxWidth = 360.dp
    /** A notice's progress indicator. */
    val progressIndicator = 20.dp
    /** A 48 dp IconButton's ink is 12 dp inside its box; offset by this to put the ink on a keyline. */
    val trailingOptical = 12.dp
    /** At large text a row's leading and trailing slots sit this far below the row's content top (M3 three-line rule). */
    val slotTopOffset = 4.dp
    const val heroFraction = 0.64f
    const val heroFractionLarge = 0.45f
    /** The most a rail tile widens with the font. */
    const val tileMaxScale = 1.5f
}

/** Font scale at and above which layouts switch to their large-text form. */
internal const val LARGE_TEXT_SCALE = 1.5f

/** The one large-text predicate. No screen writes its own `fontScale` comparison. */
@Composable
@ReadOnlyComposable
internal fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE

/** Square detail-header artwork: the same size on album, artist, playlist and Discover pages. */
internal fun Modifier.detailHeroArt(): Modifier = composed {
    val fraction = if (isLargeText()) DulcetSizes.heroFractionLarge else DulcetSizes.heroFraction
    // A fraction of the available width, capped. `fillMaxWidth(f).widthIn(max)` cannot cap: the fill fixes the width first.
    layout { measurable, constraints ->
        val side = minOf((constraints.maxWidth * fraction).roundToInt(), DulcetSizes.heroMax.roundToPx())
        val placeable = measurable.measure(Constraints.fixed(side, side))
        layout(side, side) { placeable.place(0, 0) }
    }
}

/** A rail card's width: [DulcetSizes.tileWidth], widened with the font up to [DulcetSizes.tileMaxScale]. */
internal fun Modifier.railTileWidth(): Modifier = composed { width(railTileWidthDp()) }

/** The width [railTileWidth] applies, for a caller that sizes the artwork inside the card. */
@Composable
@ReadOnlyComposable
internal fun railTileWidthDp(): Dp = DulcetSizes.tileWidth * LocalDensity.current.fontScale.coerceIn(1f, DulcetSizes.tileMaxScale)

/**
 * Moves a trailing 48 dp IconButton (or a row of controls ending in one) toward the edge so its 24 dp glyph box ends on
 * the 16 dp keyline, the column the top bar's actions use. The touch target keeps its full size and runs into the row's
 * end padding; the slot reports itself 12 dp narrower, so the text column gets those 12 dp back (an `offset` would
 * move the drawing and keep the width). [TrailingSlot] applies it; use it directly only outside one.
 */
internal fun Modifier.trailingOptical(): Modifier = layout { measurable, constraints ->
    val inset = DulcetSizes.trailingOptical.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val width = (placeable.width - inset).coerceAtLeast(0)
    layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), placeable.height) { placeable.place(0, 0) }
}
