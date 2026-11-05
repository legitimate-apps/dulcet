package com.legitimateapps.dulcet.tv

import android.view.WindowManager
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
 * The Material restyle's shared TV pieces: one dialog shell (28 dp shape, full-window scrim), one
 * centered empty state with a single focusable action, one radio-choice dialog. Tags passed in by
 * callers are load-bearing for the design tours.
 */

private val TvDialogShape = RoundedCornerShape(28.dp)

/** How strongly a dialog dims the screen behind it: the page, the tab bar and its pills alike. */
internal const val TV_DIALOG_SCRIM = 0.8f

/**
 * The overscan-safe area of a 10-foot screen: 5% of a 960 x 540 dp display, about 48 dp at the
 * sides and 27 dp at the top and bottom. Every TV page keeps its content inside it.
 */
internal val TV_SAFE_HORIZONTAL = 48.dp
internal val TV_SAFE_VERTICAL = 27.dp

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
private val TvTypography: Typography = Typography().let {
    val secondary = it.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp)
    it.copy(titleMedium = it.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        bodyMedium = secondary, bodySmall = secondary, labelMedium = it.labelLarge, labelSmall = it.labelLarge)
}

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

/**
 * A TV page's bottom padding: the overscan margin and no more. It is also how far past its last row the page can scroll,
 * so with this a page whose last row just crosses the margin moves by that overlap, and its title stays where it was.
 */
internal val TV_PAGE_BOTTOM = TV_SAFE_VERTICAL

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
        LazyColumn(Modifier.fillMaxSize().testTag(tag), state = list,
            contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = TV_PAGE_BOTTOM + footRoom),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        if (moreBelow) Icon(DulcetIcons.ExpandMore, null, Modifier.align(Alignment.BottomCenter).size(32.dp).testTag("$tag.moreBelow"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Where an empty state starts under a filter's chips: the chips already part it from the title, and at
 * the full 48 dp its action falls inside the bottom room, so focusing it would move the page.
 */
internal val TV_EMPTY_UNDER_CHIPS = 36.dp

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

/**
 * [TvSteadyBringIntoView] for a menu whose edges [TvMenuScrollCue] covers: the row after the focused one comes clear of
 * the cue, over [nextRowGap] (a menu's rows are 4 dp apart, and its divider adds 17 dp more).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun tvMenuBringIntoView(cue: androidx.compose.ui.unit.Dp, nextRowGap: androidx.compose.ui.unit.Dp): androidx.compose.runtime.ProvidedValue<*> {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val platform = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val spec = remember(platform, density) {
        with(density) { TvSteadyBringIntoView(platform, cue.toPx(), topCoverPx = cue.toPx(), nextRowGapPx = nextRowGap.toPx()) }
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
 * A dialog over a full-window scrim with the M3 shape. The platform's own dim is switched off so the
 * scrim strength is exactly this one's, and the window spans the whole screen so the scrim covers the
 * tab bar too. [topAnchored] keeps the content above the TV IME's lower half.
 */
@Composable
internal fun TvDialogShell(tag: String, onDismiss: () -> Unit, topAnchored: Boolean = false,
                           width: Int = 640, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.apply {
            setDimAmount(0f)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = TV_DIALOG_SCRIM))
                .padding(top = if (topAnchored) 80.dp else 0.dp),
            contentAlignment = if (topAnchored) Alignment.TopCenter else Alignment.Center,
        ) {
            Surface(
                Modifier.width(width.dp).testTag(tag),
                shape = TvDialogShape,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                content()
            }
        }
    }
}

/**
 * A dialog's header: optional artwork, the title and an optional supporting line. [artwork] is the
 * caller's own cover (an album's, a playlist's) at the size it chooses.
 */
@Composable
internal fun TvDialogHeader(title: String, supporting: String? = null, artwork: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        artwork?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One row inside a [TvDialogShell] list: a tv-material ListItem, one focus stop. [radio] gives every
 * row a radio button, filled on the [selected] one, so a choice dialog reads as a choice whichever
 * row the remote is on. The row's own surface only lights for focus: the choice is the radio's.
 */
@Composable
internal fun TvDialogRow(label: String, tag: String, modifier: Modifier = Modifier,
                         selected: Boolean = false, radio: Boolean = selected, leading: (@Composable () -> Unit)? = null,
                         supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        scale = TvRowScale,
        selected = selected,
        onClick = onClick,
        modifier = modifier.testTag(tag),
        colors = ListItemDefaults.colors(
            selectedContainerColor = Color.Transparent,
            selectedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        leadingContent = leading?.let { l -> { l() } },
        supportingContent = supporting?.let { s -> { Text(s, style = MaterialTheme.typography.bodyMedium, color = tvRowLineColor(), maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (radio) ({ RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(
            selectedColor = LocalContentColor.current, unselectedColor = LocalContentColor.current)) }) else null,
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * A single-choice dialog: a radio on every row, the chosen one filled independently of focus, initial
 * focus on the current choice (or the first). [supporting] sits under the title (when a choice takes
 * effect, say). Back dismisses without choosing. [tag] prefixes each choice's tag
 * (`$tag.$choiceTag`); [surfaceTag] tags the dialog itself.
 */
@Composable
internal fun <T> TvChoiceDialog(tag: String, surfaceTag: String, title: String, choices: List<T>, choiceTag: (T) -> String,
                                choiceLabel: @Composable (T) -> String, selected: T?, onChoose: (T) -> Unit,
                                onDismiss: () -> Unit, supporting: String? = null) {
    TvDialogShell(surfaceTag, onDismiss) {
        val initial = remember { FocusRequester() }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvDialogHeader(title, supporting)
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(choices, key = { _, choice -> choiceTag(choice) }) { index, choice ->
                    val first = (selected == null || selected !in choices) && index == 0
                    TvDialogRow(
                        choiceLabel(choice),
                        "$tag.${choiceTag(choice)}",
                        modifier = if (first || choice == selected) Modifier.focusRequester(initial) else Modifier,
                        selected = choice == selected,
                        radio = true,
                    ) { onChoose(choice) }
                }
            }
        }
        FocusWhenWindowFocused(initial)
    }
}

/**
 * A settings row: one focusable tv-material ListItem with a headline, optional supporting text and,
 * for a row that opens a choice, its current [value] trailing with a chevron.
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
        colors = ListItemDefaults.colors(contentColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
        leadingContent = leading?.let { l -> { l() } },
        supportingContent = supporting?.let { s -> { Text(s, style = MaterialTheme.typography.bodyMedium, color = tvRowLineColor(), maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (value != null || chevron) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                value?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (chevron) Icon(DulcetIcons.ChevronRight, null)
            }
        }) else null,
        headlineContent = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

/** A section's heading inside a TV settings page. */
@Composable
internal fun TvSettingsHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = TV_GROUP_HEADER_TEXT)
}

/**
 * TV's one notice, the phone's `ServerStateNotice` for a remote: the state in words on a tonal card across the page, its
 * one action inside the card and focusable. A notice speaks for the page, so it sits above the content, never under one
 * shelf's heading.
 */
@Composable
internal fun TvNotice(line: String, lineTag: String, action: String, actionTag: String, modifier: Modifier = Modifier,
                      actionModifier: Modifier = Modifier, error: Boolean = false, onAction: () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(line, Modifier.weight(1f).testTag(lineTag), style = MaterialTheme.typography.bodyLarge,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            // Outlined: tv-material's quiet button is this card's own colour, and would read as plain text.
            TvAction(action, actionTag, outlined = true, modifier = actionModifier, onClick = onAction)
        }
    }
}

/** A settings value: a headline with the value trailing, not a focus target. */
@Composable
internal fun TvInfoRow(label: String, tag: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
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
 */
internal fun Modifier.tvBleed(start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp): Modifier =
    layout { measurable, constraints ->
        val startPx = start.roundToPx()
        val width = constraints.maxWidth + startPx + end.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-startPx, 0) }
    }

/**
 * The centered empty state: a headline, one supporting line, and one action that takes the route's
 * initial focus — unless [actionDefault] is false, on a two-pane root whose left pane's first section
 * is that one initial focus. The [tag] node wraps the whole state, so an `awaitTag` on it holds
 * whether or not an action exists.
 */
@Composable
internal fun TvEmptyState(title: String, hint: String?, tag: String, topPadding: androidx.compose.ui.unit.Dp = 48.dp, actionLabel: String? = null,
                          actionTag: String? = null, icon: ImageVector? = DulcetIcons.MusicNote,
                          actionDefault: Boolean = true, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(top = topPadding, bottom = 48.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { Icon(it, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (hint != null) {
            Text(hint, Modifier.widthIn(max = 600.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && actionTag != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 12.dp).tvFocus(actionTag, default = actionDefault)) {
                Text(actionLabel)
            }
        }
    }
}
