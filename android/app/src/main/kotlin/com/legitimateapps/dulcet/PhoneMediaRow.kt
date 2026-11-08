package com.legitimateapps.dulcet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Search's media result row, shared by server search and Discover's Everywhere results. Its slots centre as a track row's do. */
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
) {
    ListItem(
        headlineContent = { WrappedTitleText(title, maxLines = titleMaxLines) },
        supportingContent = supporting,
        leadingContent = leading?.let { content -> { CentredSlot(content) } },
        trailingContent = trailing?.let { content -> { CentredSlot(content) } },
        modifier = modifier.fillMaxWidth().centredSlots().let { base ->
            if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base
        },
    )
}
