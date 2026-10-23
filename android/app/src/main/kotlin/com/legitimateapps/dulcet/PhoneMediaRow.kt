package com.legitimateapps.dulcet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** Search's media result row, shared by server search and Discover's Everywhere results. */
@Composable
internal fun PhoneMediaRow(
    title: String,
    modifier: Modifier,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting,
        leadingContent = leading,
        trailingContent = trailing,
        modifier = modifier.fillMaxWidth().let { base ->
            if (onClick != null) base.clickable(onClick = onClick) else base
        },
    )
}
