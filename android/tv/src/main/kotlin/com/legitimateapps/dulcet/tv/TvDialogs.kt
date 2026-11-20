package com.legitimateapps.dulcet.tv

import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.RadioButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.ui.DulcetIcons

/*
 * The TV dialog family. Every dialog is a [TvDialogShell]; a screen never lays out its own dialog Column.
 *
 *  - [TvChoiceDialog]   a single choice from a list (radio rows): sleep timer, crossfade, streaming quality, cache limit.
 *  - [TvConfirmDialog]  a question with a safe and a destructive answer: delete playlist, sign out.
 *  - [TvMessageDialog]  a title, a body and any row of [TvDialogButton]s, for a dialog with a third answer (sign-out's
 *                       Send / Stay signed in / Discard) or none destructive.
 *  - a menu             [TvDialogShell] with `width = TvDialogWidth.List`, a [TvDialogHeader] in `header` (pinned, never
 *                       scrolls) and its rows in `content`.
 *
 * Geometry, owned here so no caller can drift:
 *  - padding [TV_DIALOG_PADDING] (24) on every side;
 *  - width by kind, [TvDialogWidth]: List (520) for choices, menus and two-answer confirms, Form (640) for forms and
 *    messages with three answers;
 *  - title to body or list [TvSpacing.l] (16), body to buttons [TvSpacing.xl] (24), buttons [TvSpacing.l] apart;
 *  - list rows [TvSpacing.xs] (4) apart; in a list dialog the header takes [tvInkColumn], so the title starts on the
 *    rows' ink column, with an optional artwork slot that shares the row's keyline.
 *  - the surface stays inside the overscan margin, so a long list scrolls instead of running off the screen.
 *
 * Focus: a dialog's first focus goes to its safe answer (Cancel, Stay signed in) or its current choice, through
 * [FocusWhenWindowFocused], so it is lit as soon as the window is. A destructive button is never the first focus, and
 * shows the error colour as a fill only while focused ([tvDestructiveButtonColors]). Back dismisses: [onDismiss] means
 * the safe answer.
 */

private val TvDialogShape = RoundedCornerShape(28.dp)

/** How strongly a dialog dims the screen behind it: the page, the tab bar and its pills alike. */
internal const val TV_DIALOG_SCRIM = 0.8f

/** Where a [TvDialogShell] with `topAnchored` starts: high enough to keep a text field clear of the TV IME. */
private val TV_DIALOG_IME_TOP = 80.dp

/** A dialog's width, by what it holds. */
internal enum class TvDialogWidth(val dp: Dp) {
    /** Choices and menus (rows with a trailing radio or a leading icon), and a confirm's two answers. */
    List(TV_DIALOG_WIDTH_LIST),
    /** Forms, and messages with three answers. */
    Form(TV_DIALOG_WIDTH),
}

/**
 * The window every dialog draws in: a full-window scrim with the M3 shape. The platform's own dim is switched off so the
 * scrim strength is exactly this one's, and the window spans the whole screen so the scrim covers the tab bar too.
 */
@Composable
private fun TvDialogWindow(tag: String, onDismiss: () -> Unit, topAnchored: Boolean, width: Dp,
                           content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.apply {
            setDimAmount(0f)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = TV_DIALOG_SCRIM))
                .padding(top = if (topAnchored) TV_DIALOG_IME_TOP else TV_SAFE_VERTICAL, bottom = TV_SAFE_VERTICAL),
            contentAlignment = if (topAnchored) Alignment.TopCenter else Alignment.Center,
        ) {
            Surface(
                Modifier.width(width).testTag(tag),
                shape = TvDialogShape,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                content()
            }
        }
    }
}

/**
 * Every TV dialog. [tag] tags the surface. [header] (usually a [TvDialogHeader]) is pinned above [content] and never
 * scrolls; [content] is a column that is given what height remains, so a list inside it scrolls under the header.
 * The shell owns the padding ([TV_DIALOG_PADDING]) and the width ([width]); [topAnchored] keeps a dialog with a text
 * field above the TV IME.
 */
@Composable
internal fun TvDialogShell(
    tag: String,
    onDismiss: () -> Unit,
    width: TvDialogWidth = TvDialogWidth.Form,
    topAnchored: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    TvDialogWindow(tag, onDismiss, topAnchored, width.dp) {
        Column(Modifier.padding(TV_DIALOG_PADDING)) {
            if (header != null) {
                header()
                Spacer(Modifier.height(TvSpacing.l))
            }
            Column(Modifier.weight(1f, fill = false), content = content)
        }
    }
}

/**
 * A dialog's header: optional artwork, the title and an optional supporting line. [artwork] is the caller's own cover
 * (an album's, a playlist's); it is drawn [artworkSize] square whatever size it asks for. List dialogs
 * supply the inset or artwork size that puts the title on the row's ink column. [TvDialogShell] sets
 * the space below the header.
 */
@Composable
internal fun TvDialogHeader(title: String, supporting: String? = null, artwork: (@Composable () -> Unit)? = null,
                            modifier: Modifier = Modifier, artworkSize: Dp = TV_ROW_ART) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpacing.l),
        verticalAlignment = Alignment.CenterVertically) {
        artwork?.let { Box(Modifier.size(artworkSize), propagateMinConstraints = true) { it() } }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One row inside a dialog list: a tv-material ListItem, one focus stop. [radio] gives every
 * row a radio button, filled on the [selected] one, so a choice dialog reads as a choice whichever
 * row the remote is on. The row's own surface only lights for focus: the choice is the radio's.
 */
@Composable
internal fun TvDialogRow(label: String, tag: String, modifier: Modifier = Modifier,
                         selected: Boolean = false, radio: Boolean = selected, leading: (@Composable () -> Unit)? = null,
                         supporting: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        scale = TvRowScale,
        selected = selected,
        onClick = onClick,
        enabled = enabled,
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
 * A single-choice dialog: a radio on every row, the chosen one filled independently of focus, initial focus on the
 * current choice (or the first), scrolled into view when the list is longer than the dialog. [supporting] sits under
 * the title (when a choice takes effect, say). Choosing calls [onChoose], which closes it; Back calls [onDismiss]
 * without choosing. [tag] prefixes each choice's tag (`$tag.$choiceTag`); [surfaceTag] tags the dialog itself.
 */
@Composable
internal fun <T> TvChoiceDialog(tag: String, surfaceTag: String, title: String, choices: List<T>, choiceTag: (T) -> String,
                                choiceLabel: @Composable (T) -> String, selected: T?, onChoose: (T) -> Unit,
                                onDismiss: () -> Unit, supporting: String? = null) {
    TvDialogShell(surfaceTag, onDismiss, width = TvDialogWidth.List,
        header = { TvDialogHeader(title, supporting, modifier = Modifier.tvInkColumn()) }) {
        val focusIndex = choices.indexOf(selected).coerceAtLeast(0)
        val labelHeight = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
        val rowHeight = maxOf(TV_CHOICE_ROW_MIN, labelHeight + TvSpacing.l)
        TvDialogList(surfaceTag, choices.map(choiceTag), rowHeight, focusIndex) { index, modifier ->
            val choice = choices[index]
            TvDialogRow(
                choiceLabel(choice),
                "$tag.${choiceTag(choice)}",
                modifier = modifier,
                selected = choice == selected,
                radio = true,
            ) { onChoose(choice) }
        }
    }
}

/**
 * A bounded list of uniform dialog rows with a pinned header above it. The viewport holds whole rows,
 * and an overflowing list reserves equal room for its two cues. Focus keeps the next row visible when
 * it fits, so the remote can move down without searching past the bottom edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvDialogList(
    tag: String,
    keys: List<String>,
    rowHeight: Dp,
    initialIndex: Int,
    row: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    if (keys.isEmpty()) return
    val initial = remember { FocusRequester() }
    val firstFocus = rememberFirstFocus()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowPx = with(density) { rowHeight.roundToPx() }
        val gapPx = with(density) { TvSpacing.xs.roundToPx() }
        val pitchPx = rowPx + gapPx
        val available = constraints.maxHeight
        val cuePx = with(density) { TV_DIALOG_CUE_BAND.roundToPx() }
            .coerceAtMost(((available - rowPx) / 2).coerceAtLeast(0))
        val overflow = keys.size * pitchPx - gapPx > available
        val room = if (overflow) available - 2 * cuePx else available
        val visible = ((room + gapPx) / pitchPx).coerceIn(1, keys.size)
        val viewport = with(density) { (visible * pitchPx - gapPx).coerceAtMost(room).toDp() }
        val cueHeight = with(density) { cuePx.toDp() }
        val bringIntoView = remember(pitchPx) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    if (offset < 0f) return offset
                    val lookahead = if (size + pitchPx <= containerSize) pitchPx else 0
                    return (offset + size + lookahead - containerSize).coerceAtLeast(0f)
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            if (initialIndex >= visible) scroll.scrollTo((initialIndex - visible + 1) * pitchPx)
            firstFocus.give(initial)
        }
        Column {
            if (overflow) TvDialogListCue("$tag.moreAbove", cueHeight, scroll.canScrollBackward, above = true)
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                Column(
                    Modifier.height(viewport).testTag("$tag.rows").verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.xs),
                ) {
                    keys.forEachIndexed { index, id ->
                        key(id) {
                            val modifier = Modifier.fillMaxWidth().height(rowHeight)
                            row(index, if (index == initialIndex) modifier.focusRequester(initial) else modifier)
                        }
                    }
                }
            }
            if (overflow) TvDialogListCue("$tag.moreBelow", cueHeight, scroll.canScrollForward, above = false)
        }
    }
}

@Composable
private fun TvDialogListCue(tag: String, height: Dp, visible: Boolean, above: Boolean) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        if (visible) {
            if (!above) {
                val surface = MaterialTheme.colorScheme.surface
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, surface))))
            }
            Box(Modifier.size(TV_DIALOG_CUE_ICON).testTag(tag)) {
                Icon(
                    DulcetIcons.ExpandMore, null,
                    Modifier.matchParentSize().rotate(if (above) 180f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A button that takes something away: the error colour for its words at rest, and while focused (or pressed) the error
 * colour fills it, so the signal holds under the fill that says where the remote is. At rest its container is the same
 * as any dialog button's, so a safe and a destructive button sit as one pair.
 */
@Composable
internal fun tvDestructiveButtonColors() = ButtonDefaults.colors(
    contentColor = MaterialTheme.colorScheme.error,
    focusedContainerColor = MaterialTheme.colorScheme.error,
    focusedContentColor = MaterialTheme.colorScheme.onError,
    pressedContainerColor = MaterialTheme.colorScheme.error,
    pressedContentColor = MaterialTheme.colorScheme.onError,
)

/** The same for a list row that takes something away (Sign out, Remove download). */
@Composable
internal fun tvDestructiveRowColors() = ListItemDefaults.colors(
    contentColor = MaterialTheme.colorScheme.error,
    focusedContainerColor = MaterialTheme.colorScheme.error,
    focusedContentColor = MaterialTheme.colorScheme.onError,
    pressedContainerColor = MaterialTheme.colorScheme.error,
    pressedContentColor = MaterialTheme.colorScheme.onError,
)

/**
 * One answer in a dialog's button row. [initialFocus] makes it the dialog's first focus (give it to the safe answer);
 * [destructive] draws it with [tvDestructiveButtonColors], and a destructive button never takes the first focus.
 */
@Composable
internal fun TvDialogButton(label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                            destructive: Boolean = false, initialFocus: Boolean = false, enabled: Boolean = true) {
    val focusModifier = if (initialFocus && !destructive) {
        val focus = remember { FocusRequester() }
        FocusWhenWindowFocused(focus)
        Modifier.focusRequester(focus)
    } else Modifier
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(focusModifier).testTag(tag),
        colors = if (destructive) tvDestructiveButtonColors() else ButtonDefaults.colors(),
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/**
 * A title, a body and a row of answers: [actions] holds [TvDialogButton]s, the safe one with `initialFocus`, read in
 * the order the remote meets them (safe before destructive). A null [title] gives a body-only dialog (a wait).
 * [bodyTag] tags the body text.
 */
@Composable
internal fun TvMessageDialog(tag: String, title: String?, body: String, onDismiss: () -> Unit, bodyTag: String? = null,
                             width: TvDialogWidth = TvDialogWidth.Form, actions: @Composable RowScope.() -> Unit) {
    TvDialogShell(tag, onDismiss, width = width, header = title?.let { t -> { TvDialogHeader(t) } }) {
        Text(body, Modifier.let { if (bodyTag != null) it.testTag(bodyTag) else it },
            style = MaterialTheme.typography.bodyLarge.copy(
                lineBreak = LineBreak.Paragraph.copy(strategy = LineBreak.Strategy.Balanced),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(TvSpacing.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.l), verticalAlignment = Alignment.CenterVertically,
            content = actions)
    }
}

/**
 * A question with a safe and a destructive answer. The safe answer comes first and takes the first focus, so the centre
 * key pressed at once never destroys anything; the destructive one is a RIGHT away, and fills with the error colour only
 * while focused. Back is [onSafe].
 */
@Composable
internal fun TvConfirmDialog(
    tag: String,
    title: String,
    body: String,
    safeLabel: String,
    safeTag: String,
    destructiveLabel: String,
    destructiveTag: String,
    onSafe: () -> Unit,
    onDestructive: () -> Unit,
    bodyTag: String? = null,
) {
    // List width: a question with two short answers reads best in lines of about 60 characters, not the Form's 85.
    TvMessageDialog(tag, title, body, onSafe, bodyTag, width = TvDialogWidth.List) {
        TvDialogButton(safeLabel, safeTag, onSafe, initialFocus = true)
        TvDialogButton(destructiveLabel, destructiveTag, onDestructive, destructive = true)
    }
}
