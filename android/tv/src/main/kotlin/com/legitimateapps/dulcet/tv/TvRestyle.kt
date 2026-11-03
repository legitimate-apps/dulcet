package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/*
 * The round-1 Material restyle's shared TV pieces: one dialog shell (28 dp shape, 60% scrim), one
 * centered empty state with a single focusable action, one radio-choice dialog. Tags passed in by
 * callers are load-bearing for the design tours.
 */

private val TvDialogShape = RoundedCornerShape(28.dp)

/**
 * A dialog over a 60% scrim with the M3 shape. The platform's own dim is switched off so the scrim
 * strength is exactly this one's. [topAnchored] keeps the content above the TV IME's lower half.
 */
@Composable
internal fun TvDialogShell(tag: String, onDismiss: () -> Unit, topAnchored: Boolean = false,
                           width: Int = 640, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .padding(top = if (topAnchored) 80.dp else 0.dp),
            contentAlignment = if (topAnchored) Alignment.TopCenter else Alignment.Center,
        ) {
            Surface(
                Modifier.width(width.dp).testTag(tag),
                shape = TvDialogShape,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                content()
            }
        }
    }
}

/** One row inside a [TvDialogShell] list: a tv-material ListItem, one focus stop. */
@Composable
internal fun TvDialogRow(label: String, tag: String, modifier: Modifier = Modifier,
                         selected: Boolean = false, leading: (@Composable () -> Unit)? = null,
                         supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier.testTag(tag),
        leadingContent = leading?.let { l -> { l() } },
        supportingContent = supporting?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (selected) ({ RadioButton(selected = true, onClick = null) }) else null,
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/**
 * A single-choice dialog: radio rows, the chosen one marked independently of focus, initial focus
 * on the current choice (or the first). Back dismisses without choosing. [tag] prefixes each
 * choice's tag (`$tag.$choiceTag`); [surfaceTag] tags the dialog itself.
 */
@Composable
internal fun <T> TvChoiceDialog(tag: String, surfaceTag: String, title: String, choices: List<T>, choiceTag: (T) -> String,
                                choiceLabel: @Composable (T) -> String, selected: T?, onChoose: (T) -> Unit,
                                onDismiss: () -> Unit) {
    TvDialogShell(surfaceTag, onDismiss) {
        val initial = remember { FocusRequester() }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(choices, key = { _, choice -> choiceTag(choice) }) { index, choice ->
                    val first = selected == null && index == 0
                    TvDialogRow(
                        choiceLabel(choice),
                        "$tag.${choiceTag(choice)}",
                        modifier = if (first || choice == selected) Modifier.focusRequester(initial) else Modifier,
                        selected = choice == selected,
                    ) { onChoose(choice) }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { initial.requestFocus() } }
    }
}

/** A settings row: one focusable tv-material ListItem with a headline and a trailing value. */
@Composable
internal fun TvSettingRow(label: String, tag: String, supporting: String? = null,
                          leading: (@Composable () -> Unit)? = null, default: Boolean = false,
                          error: Boolean = false, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = Modifier.tvFocus(tag, default),
        leadingContent = leading?.let { l -> { l() } },
        supportingContent = supporting?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        headlineContent = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
    )
}

/** A settings value: a headline with the value trailing, not a focus target. */
@Composable
internal fun TvInfoRow(label: String, tag: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The centered empty state: a headline, one supporting line, and one action that takes the route's
 * initial focus. The [tag] node wraps the whole state, so an `awaitTag` on it holds whether or not
 * an action exists.
 */
@Composable
internal fun TvEmptyState(title: String, hint: String?, tag: String, actionLabel: String? = null,
                          actionTag: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && actionTag != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.tvFocus(actionTag, default = true)) {
                Text(actionLabel)
            }
        }
    }
}
