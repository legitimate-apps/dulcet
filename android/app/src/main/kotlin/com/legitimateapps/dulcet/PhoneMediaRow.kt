package com.legitimateapps.dulcet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Search and Discover use the shared row slots. [trailingAction] aligns a trailing IconButton to the page keyline. */
@Composable
internal fun PhoneMediaRow(
    title: String,
    modifier: Modifier,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleMaxLines: Int = 1,
    enabled: Boolean = true,
    trailingAction: Boolean = false,
) {
    ListItem(
        headlineContent = { WrappedTitleText(title, maxLines = titleMaxLines) },
        supportingContent = supporting,
        leadingContent = leading?.let { content -> { CentredSlot(content) } },
        trailingContent = trailing?.let { content ->
            { if (trailingAction) TrailingSlot(content) else CentredSlot(content) }
        },
        modifier = modifier.fillMaxWidth().centredSlots().let { base ->
            if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base
        },
    )
}
