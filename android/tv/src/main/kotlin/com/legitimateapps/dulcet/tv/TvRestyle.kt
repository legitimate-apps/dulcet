package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.RadioButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.ui.DulcetIcons

/*
 * The Material restyle's shared TV pieces: the theme and type, focus and scrolling rules, the settings page and its
 * rows, the notice and the empty state. Layout values come from TvDimens.kt; the dialog family is in TvDialogs.kt and
 * the page header and two-pane root in TvPageLayout.kt. Tags passed in by callers are load-bearing for the design tours.
 */

private val TV_SECONDARY_TEXT = androidx.compose.ui.graphics.Color(0xFFDDD8E3)

/**
 * A settings group's heading has its own token: it labels the rows under it and is not one of them, so it stays a step
 * dimmer and smaller than a row's headline however bright the secondary lines go.
 */
private val TV_GROUP_HEADER_TEXT = androidx.compose.ui.graphics.Color(0xFFADA8B5)

/**
 * tv-material's type scale with its secondary roles raised to what reads from the couch. Its own
 * components set secondary lines in `bodySmall` (12 sp) and overlines in `labelSmall` (11 sp), and
 * this app's cards and rows set theirs in `bodyMedium` (14 sp), all small at ten feet: those roles take
 * 15 sp, a step under the 16 sp titles above them, and the two small label roles take `labelLarge` (14 sp).
 * One point of size cannot carry a hierarchy from across a room, so a title goes a weight up and the secondary line
 * takes a little more leading; no line gets smaller.
 */
// design-tokens:begin TvTypography is the TV type scale's one definition
private val TvTypography: Typography = Typography().let {
    val secondary = it.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp)
    it.copy(titleMedium = it.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        bodyMedium = secondary, bodySmall = secondary, labelMedium = it.labelLarge, labelSmall = it.labelLarge)
}
// design-tokens:end

/**
 * Where a focused element comes to rest in a TV page or menu. The platform's default pins every focused element 30% down
 * its container, so a page that fits on the screen still moved: the first focus lifted the title against the tab bar, by
 * however far down that element happened to be. Here a container moves only when focus needs it to:
 *  - an element clear of the edges stays put;
 *  - one going down rests above the bottom edge with room for what follows: [bottomRoomPx] alone on a page, where the next
 *    row only has to show; on a menu, whose edge is covered by a fade, [bottomRoomPx] is that cover and [nextRowGapPx] the
 *    gap to the next row, which is taken to be this row's size, so the next row comes clear of the cover;
 *  - one going up, or under the [topCoverPx] at the top, keeps the default's room above it, so the header comes back.
 * An element too tall for that room takes the default.
 *
 * This rule asks for a distance; the container's scroll range caps it. A page's bottom padding is part of that range,
 * which is why pages use [TV_PAGE_BOTTOM]: a page that nearly fits then moves by what it overflows, not by the rule's room.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class TvSteadyBringIntoView(
    private val default: androidx.compose.foundation.gestures.BringIntoViewSpec,
    private val bottomRoomPx: Float,
    private val topCoverPx: Float = 0f,
    private val nextRowGapPx: Float? = null,
) : androidx.compose.foundation.gestures.BringIntoViewSpec by default {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val topRoom = containerSize * 0.3f
        val bottomRoom = bottomRoomPx + (nextRowGapPx?.let { it + size } ?: 0f)
        if (size > containerSize - topRoom - bottomRoom) return default.calculateScrollDistance(offset, size, containerSize)
        return when {
            offset < topCoverPx -> offset - topRoom
            offset + size > containerSize - bottomRoom -> offset + size - (containerSize - bottomRoom)
            else -> 0f
        }
    }
}

/** How far scrolled content dissolves under the tab bar: inside every page's top padding, so nothing at rest touches it. */
private val TV_PAGE_TOP_FADE = 16.dp

/**
 * A page's edges under scrolling: content leaving under the tab bar dissolves instead of being cut through its letters,
 * and a row running off the bottom of the screen fades across the overscan margin, which says more follows. Both bands
 * are empty on a page at rest that fits, so they show only when there is something to say.
 */
internal fun Modifier.tvPageEdgeFade(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = TV_PAGE_TOP_FADE.toPx()
        val bottom = TV_PAGE_BOTTOM.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = top),
            size = androidx.compose.ui.geometry.Size(size.width, top), blendMode = BlendMode.DstIn)
        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - bottom, endY = size.height),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - bottom),
            size = androidx.compose.ui.geometry.Size(size.width, bottom), blendMode = BlendMode.DstIn)
    }

/**
 * A settings page: its rows are uniform and can end cleanly at the fold, where neither a partial row nor the edge fade
 * shows that more follows, so a chevron says so while the last row lies below the screen. [footRoom] lifts the page's
 * last rest, for a page whose rows would otherwise end with one ghosted in the top fade.
 *
 * Layout: [tvPagePadding] around the content, [TvSpacing.l] between items. Put each section in its own item, headed by
 * [TvSettingsHeader], with its rows `spacedBy(TvSpacing.xs)` under it; a row that opens a choice passes `value =`.
 *
 * The chevron sits in a [TV_SCROLL_CUE_BAND] at the page's foot across which rows fade out, so it is never drawn over a
 * row's words. The band is a fade, not extra padding: padding would only show at the page's end, where the chevron has
 * already gone, and would change how far a nearly-fitting page moves (`TvPageScrollTest`). A focused row never sits in
 * the band: the page's steady scroll keeps 72 dp under it.
 */
@Composable
internal fun TvSettingsPage(tag: String, footRoom: Dp = 0.dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    val moreBelow by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && (last.index < info.totalItemsCount - 1 || last.offset + last.size > info.viewportEndOffset)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().tvFootFade { moreBelow }.testTag(tag), state = list,
            contentPadding = tvPagePadding(extraBottom = footRoom),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.l), content = content)
        if (moreBelow) Icon(DulcetIcons.ExpandMore, null, Modifier.align(Alignment.BottomCenter).size(TV_SCROLL_CUE).testTag("$tag.moreBelow"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Fades the content out across the [TV_SCROLL_CUE_BAND] at its foot while [active] says more lies below. */
private fun Modifier.tvFootFade(active: () -> Boolean): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (!active()) return@drawWithContent
        val band = TV_SCROLL_CUE_BAND.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - band, endY = size.height),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - band),
            size = androidx.compose.ui.geometry.Size(size.width, band), blendMode = BlendMode.DstIn)
    }

/** A metadata line at a row title's size, without the weight that marks a title. */
@Composable
internal fun tvMetaLineStyle() = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)

/**
 * A row's second line. tv-material draws that slot at 80% of the row's content colour, which the room's token
 * cannot reach, so the line asks for its own strength here, and it still follows the row's colour under focus.
 */
@Composable
internal fun tvRowLineColor(): Color = LocalContentColor.current.copy(alpha = 0.92f)

/**
 * Secondary text on a dark room's screen: tv-material's dark `onSurfaceVariant` is a step too dim for
 * a line read from the couch, so the one token that every subtitle and helper line uses goes brighter.
 */
private val TvColorScheme = darkColorScheme().let { it.copy(onSurfaceVariant = TV_SECONDARY_TEXT) }

/** Every TV activity's theme: dark, because the room's display is the couch's, day or night. */
@Composable
internal fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColorScheme, typography = TvTypography, content = content)
}

/** [TvSteadyBringIntoView] for everything under it, over whatever the platform's own default is. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun tvSteadyBringIntoView(): androidx.compose.runtime.ProvidedValue<*> {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val platform = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val spec = androidx.compose.runtime.remember(platform, density) {
        with(density) { TvSteadyBringIntoView(platform, 72.dp.toPx(), topCoverPx = TV_PAGE_TOP_FADE.toPx()) }
    }
    return androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec
}

/** Gives [requester] the first focus of a new window, in a way that shows: [TvFirstFocus]. */
@Composable
internal fun FocusWhenWindowFocused(requester: FocusRequester) {
    val firstFocus = rememberFirstFocus()
    LaunchedEffect(requester) { firstFocus.give(requester) }
}

/**
 * The first focus of a window that is just opening. A focus request made before the window holds window
 * focus succeeds: the node is focused and the semantics say so, but tv-material's control, which learns of
 * focus from its interaction source, stays unlit (measured on an emulator: unchanged six seconds on, lit once the remote
 * moves off and back). Asking again changes nothing on a node already focused, so the focus is taken back
 * and given again once the window is focused; no key can have moved it before then.
 */
internal class TvFirstFocus(private val window: WindowInfo, private val manager: FocusManager) {
    suspend fun give(requester: FocusRequester) {
        runCatching { requester.requestFocus() }
        if (window.isWindowFocused) return
        snapshotFlow { window.isWindowFocused }.first { it }
        withFrameNanos { }
        manager.clearFocus(force = true)
        runCatching { requester.requestFocus() }
    }
}

@Composable
internal fun rememberFirstFocus(): TvFirstFocus {
    val window = LocalWindowInfo.current
    val manager = LocalFocusManager.current
    return remember(window, manager) { TvFirstFocus(window, manager) }
}

/**
 * A settings row: one focusable tv-material ListItem with a headline, and either:
 *  - a [value] trailing with a chevron, for a row that opens a choice (Wi-Fi streaming, Cache limit). A row that opens
 *    a choice always passes `value =`, never `supporting =`: a value on the second line reads as a fixed setting;
 *  - a bare [chevron], for a row that opens a page (Connections, Downloads & storage);
 *  - or neither, for a row that acts at once.
 * [supporting] is help text under the headline, never a value. [error] draws a destructive row (Sign out).
 * [default] makes it the route's first focus. The row sits on the page gutter; its ink starts [TV_ROW_INSET] in.
 */
@Composable
internal fun TvSettingRow(label: String, tag: String, supporting: String? = null,
                          leading: (@Composable () -> Unit)? = null, default: Boolean = false,
                          error: Boolean = false, value: String? = null, chevron: Boolean = value != null,
                          onClick: () -> Unit) {
    ListItem(
        scale = TvRowScale,
        selected = false,
        onClick = onClick,
        modifier = Modifier.tvFocus(tag, default),
        colors = if (error) tvDestructiveRowColors() else ListItemDefaults.colors(contentColor = MaterialTheme.colorScheme.onSurface),
        leadingContent = leading?.let { l -> { l() } },
        supportingContent = supporting?.let { s -> { Text(s, style = MaterialTheme.typography.bodyMedium, color = tvRowLineColor(), maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (value != null || chevron) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpacing.s)) {
                value?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (chevron) Icon(DulcetIcons.ChevronRight, null)
            }
        }) else null,
        headlineContent = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

/**
 * A section's heading inside a TV settings page, on the rows' ink column ([tvInkColumn]). It owns its own space: 20 dp
 * above (with the page's [TvSpacing.l] between items, 36 dp from the previous section's last row) and 4 dp below.
 * Forms can align the heading with their field labels instead of the rows' inset text.
 */
@Composable
internal fun TvSettingsHeader(title: String, modifier: Modifier = Modifier, alignWithRows: Boolean = true) {
    val alignment = if (alignWithRows) Modifier.tvInkColumn() else Modifier
    val style = MaterialTheme.typography.titleSmall
    Text(
        title,
        alignment.padding(top = TV_SETTINGS_HEADER_TOP, bottom = TvSpacing.xs).then(modifier),
        style = if (alignWithRows) style else style.copy(fontWeight = FontWeight.SemiBold),
        color = if (alignWithRows) TV_GROUP_HEADER_TEXT else MaterialTheme.colorScheme.onSurface,
    )
}

/** A settings heading's own top space: with the page's 16 dp between items, a section starts 36 dp under the last. */
private val TV_SETTINGS_HEADER_TOP = 20.dp

/**
 * TV's one notice, the phone's `ServerStateNotice` for a remote: the state in words on a tonal card across the page, its
 * one action inside the card and focusable. A notice speaks for the page: it goes in the page's one notice slot
 * ([TvPageNoticeSlot]) under the tab bar, never under one shelf's heading.
 *
 * Insets are [TV_NOTICE_PADDING] (20/8/16/8): the text starts 20 dp in and the action's pill ends 16 dp from the edge,
 * which reads as one inset because a pill's rounded end sits optically further in. [error] is for a state the person
 * must act on (credentials refused); an unreachable server is not an error. The action is a tonal button: at rest a
 * dark pill on the card ([MaterialTheme] `surface`, the card being `surfaceVariant`), lit like every other control when
 * focused. Not outlined, which is the tertiary level; not `secondaryContainer`, which in this dark scheme is within a
 * shade of the card's `surfaceVariant` and would vanish into it.
 */
@Composable
internal fun TvNotice(line: String, lineTag: String, action: String, actionTag: String, modifier: Modifier = Modifier,
                      actionModifier: Modifier = Modifier, error: Boolean = false, onAction: () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(TvSpacing.l),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(TV_NOTICE_PADDING),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpacing.l)) {
            Text(line, Modifier.weight(1f).testTag(lineTag), style = MaterialTheme.typography.bodyLarge,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onAction,
                modifier = actionModifier.tvFocus(actionTag),
                colors = tvTonalButtonColors(),
            ) { Text(action, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A notice's inner padding; see [TvNotice]. */
internal val TV_NOTICE_PADDING = PaddingValues(start = 20.dp, top = TvSpacing.s, end = TvSpacing.l, bottom = TvSpacing.s)

/** The tonal button level, for a secondary action on a `surfaceVariant` card: a dark pill at rest, lit when focused. */
@Composable
internal fun tvTonalButtonColors() = androidx.tv.material3.ButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

/** A settings value: a headline with the value trailing, not a focus target. Its ink is on the rows' ink column. */
@Composable
internal fun TvInfoRow(label: String, tag: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = TV_ROW_INSET, vertical = TvSpacing.m).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * Every list row keeps its size when focused: the fill says where focus is. Tv-material's 1.05 grew a
 * full-width row by about 20 dp a side, over a trailing More and past the text's own place.
 */
internal val TvRowScale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f)

/**
 * Lets a horizontal row reach past its parent's padding, so a shelf runs to the edge of its pane while
 * its first card, kept on the same keyline by the row's own content padding, can still grow when focused.
 * Shelves only: a vertical list's rows never bleed (see [tvInkColumn]).
 */
internal fun Modifier.tvBleed(start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp): Modifier =
    layout { measurable, constraints ->
        val startPx = start.roundToPx()
        val width = constraints.maxWidth + startPx + end.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-startPx, 0) }
    }

/**
 * Every "nothing here": one centred block of icon, title, hint, extra content and action, the same rhythm everywhere:
 *  - an outlined glyph in a [TV_EMPTY_ICON] (48 dp) box, then [TvSpacing.l] (16) to the title (`headlineMedium`);
 *  - [TvSpacing.s] (8) to the one-sentence hint, at most [TV_EMPTY_HINT_MAX] wide with balanced line breaks;
 *  - [TvSpacing.xl] (24) to [content], when given: a status line or controls a state needs (the player's saved
 *    account, say), laid out by the caller in this centred column;
 *  - [TvSpacing.xl] (24) to the one action, which takes the route's initial focus unless [actionDefault] is false (on a
 *    two-pane root, whose rail's first row is that initial focus).
 * The [tag] node wraps the whole state, so an `awaitTag` on it holds whether or not an action exists. [topPadding] is
 * the room above it: [TvSpacing.xxxl] on a page, [TV_EMPTY_UNDER_CHIPS] under filter chips, 0 when the caller centres
 * the block in a region. Pass `icon = null` only when the region already says what it is.
 */
@Composable
internal fun TvEmptyState(title: String, hint: String?, tag: String, topPadding: Dp = TvSpacing.xxxl, actionLabel: String? = null,
                          actionTag: String? = null, icon: ImageVector? = DulcetIcons.MusicNote,
                          actionDefault: Boolean = true, content: (@Composable ColumnScope.() -> Unit)? = null,
                          onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(top = topPadding, bottom = TvSpacing.xxxl).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(TV_EMPTY_ICON).testTag("$tag.icon"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(TvSpacing.l))
        }
        Text(title, Modifier.testTag("$tag.title"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(TvSpacing.s))
            Text(hint, Modifier.widthIn(max = TV_EMPTY_HINT_MAX).testTag("$tag.hint"), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (content != null) {
            Spacer(Modifier.height(TvSpacing.xl))
            content()
        }
        if (actionLabel != null && actionTag != null && onAction != null) {
            Spacer(Modifier.height(TvSpacing.xl))
            Button(onClick = onAction, modifier = Modifier.tvFocus(actionTag, default = actionDefault)) {
                Text(actionLabel)
            }
        }
    }
}

/** Outline glyphs for screen-wide empty states. */
internal object TvEmptyIcons {
    val Playlists = outline("EmptyPlaylists",
        "M3 6h11 M3 10h11 M3 14h7 M16 17V7h5 M16 17a2 2 0 1 0-4 0a2 2 0 1 0 4 0")
    val Library = outline("EmptyLibrary",
        "M6 4h14v14H6z M3 8v13h13 M13 13V8h4 M13 13a1.5 1.5 0 1 0-3 0a1.5 1.5 0 1 0 3 0")
    val Genres = outline("EmptyGenres", "M4 5h12l5 7-5 7H4z M8 10v4")

    private fun outline(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f) // design-token: allow intrinsic vector size; rendered at TV_EMPTY_ICON
            .addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            .build()
}

/** The phone's glyphs for Most played and Recently played, which the shared icon set does not hold. */
internal object TvRowIcons {
    val TrendingUp: ImageVector = icon("TrendingUp",
        "M16 6l2.29 2.29-4.88 4.88-4-4L2 16.59 3.41 18l6-6 4 4 6.3-6.29L22 12V6z")
    val History: ImageVector = icon("History", "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(androidx.compose.ui.graphics.vector.addPathNodes(path), fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
            .build()
}
