package com.legitimateapps.dulcet.tv

import android.view.WindowManager
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

/**
 * tv-material's type scale with its two smallest roles raised to the smallest that reads from the
 * couch: tv-material's own components set secondary lines in `bodySmall` (12 sp) and overlines in
 * `labelSmall` (11 sp), so those roles take `bodyMedium` and `labelMedium`.
 */
private val TvTypography: Typography = Typography().let { it.copy(bodySmall = it.bodyMedium, labelSmall = it.labelMedium) }

/** Every TV activity's theme: dark, because the room's display is the couch's, day or night. */
@Composable
internal fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), typography = TvTypography, content = content)
}

/**
 * Asks [requester] for focus now and again once this dialog's window holds window focus. A request
 * made only at first composition can run before the platform has moved window focus to the new
 * dialog: it succeeds on paper and leaves nothing focused once the window arrives — a dialog with
 * no focused row.
 */
@Composable
internal fun FocusWhenWindowFocused(requester: FocusRequester) {
    val window = LocalWindowInfo.current
    LaunchedEffect(requester) {
        runCatching { requester.requestFocus() }
        if (window.awaitWindowFocus()) runCatching { requester.requestFocus() }
    }
}

/**
 * Suspends until this window holds window focus, then one frame more, so a focus request lands.
 * Returns whether it had to wait: a window already focused needs no second request.
 */
internal suspend fun WindowInfo.awaitWindowFocus(): Boolean {
    if (isWindowFocused) return false
    snapshotFlow { isWindowFocused }.first { it }
    withFrameNanos { }
    return true
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
        selected = selected,
        onClick = onClick,
        modifier = modifier.testTag(tag),
        colors = ListItemDefaults.colors(
            selectedContainerColor = Color.Transparent,
            selectedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        leadingContent = leading?.let { l -> { l() } },
        supportingContent = supporting?.let { s -> { Text(s, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
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
        selected = false,
        onClick = onClick,
        modifier = Modifier.tvFocus(tag, default),
        colors = ListItemDefaults.colors(contentColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
        leadingContent = leading?.let { l -> { l() } },
        supportingContent = supporting?.let { s -> { Text(s, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
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
    Text(title, modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * The centered empty state: a headline, one supporting line, and one action that takes the route's
 * initial focus — unless [actionDefault] is false, on a two-pane root whose left pane's first section
 * is that one initial focus. The [tag] node wraps the whole state, so an `awaitTag` on it holds
 * whether or not an action exists.
 */
@Composable
internal fun TvEmptyState(title: String, hint: String?, tag: String, actionLabel: String? = null,
                          actionTag: String? = null, icon: ImageVector? = DulcetIcons.MusicNote,
                          actionDefault: Boolean = true, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag(tag),
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
            Button(onClick = onAction, modifier = Modifier.tvFocus(actionTag, default = actionDefault)) {
                Text(actionLabel)
            }
        }
    }
}
