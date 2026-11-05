package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Of the window's height: what is left under a top bar and above the tab bar and mini player. */
private const val EMPTY_PAGE_HEIGHT_FRACTION = 0.7f

/**
 * The shared empty state: a centred icon, one headline, at most one supporting line and one
 * action. Pages with nothing to show use this instead of a bare text line; controls that act on
 * content (sort, filter, shuffle) stay hidden while it is visible.
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTag: String? = null,
    fillsPage: Boolean = false,
) {
    // A page with nothing else on it centres its statement, as the unreachable Listen Now does. A minimum, not a
    // fixed height: at large text in a short window the statement outgrows it and the list scrolls to its action.
    val pageHeight = if (fillsPage) (LocalConfiguration.current.screenHeightDp * EMPTY_PAGE_HEIGHT_FRACTION).dp else 0.dp
    Column(
        modifier.fillMaxWidth().heightIn(min = pageHeight).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp).let { base -> if (actionTag != null) base.testTag(actionTag) else base }) { Text(actionLabel) }
        }
    }
}

/**
 * The single place a destination reports offline, stale or failed state: one line and one action
 * under the app bar, never per shelf or per section. [onAction] is the retry or reconnect.
 */
@Composable
internal fun StatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTag: String? = null,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier) { Text(actionLabel) }
            }
        }
    }
}
