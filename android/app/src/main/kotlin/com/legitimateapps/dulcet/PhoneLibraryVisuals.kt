package com.legitimateapps.dulcet

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.ui.DulcetIcons

/** The visual frame Library artwork uses; loaders stay with the account or Discover endpoint that owns them. */
@Composable
internal fun ArtworkFrame(image: ImageBitmap?, contentDescription: String?, size: Dp?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (size != null && size < 80.dp) 8.dp else 12.dp)
    val sized = if (size != null) modifier.size(size) else modifier
    Box(sized.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Crossfade(image, label = "artwork") { loaded ->
            if (loaded != null) Image(loaded, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(DulcetIcons.Album, null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Library's centred detail header. [actions] receives the same Material buttons each detail kind uses. */
@Composable
internal fun LibraryDetailHeader(
    title: String,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleTag: String? = null,
    subtitle: String? = null,
    onSubtitle: (() -> Unit)? = null,
    metadata: String? = null,
    status: (@Composable () -> Unit)? = null,
    artworkSpacing: Dp = 20.dp,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        artwork()
        Spacer(Modifier.height(artworkSpacing))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = titleTag?.let { Modifier.testTag(it) } ?: Modifier)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .let { base -> if (onSubtitle != null) base.clickable(onClick = onSubtitle) else base }
                    .padding(4.dp))
        }
        metadata?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        status?.invoke()
        if (actions != null) {
            Spacer(Modifier.height(16.dp))
            actions()
            Spacer(Modifier.height(8.dp))
        }
    }
}

internal class DetailAction(
    val label: Int,
    val icon: ImageVector,
    val tag: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Library's filled/tonal detail actions. [stacked] lets external-metadata pages grow with large text. */
@Composable
internal fun DetailActionButtons(primary: DetailAction, secondary: DetailAction? = null, stacked: Boolean = false) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailButton(primary, true, Modifier.fillMaxWidth())
            secondary?.let { DetailButton(it, false, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailButton(primary, true, Modifier.width(148.dp))
            secondary?.let { DetailButton(it, false, Modifier.width(148.dp)) }
        }
    }
}

@Composable
private fun DetailButton(action: DetailAction, filled: Boolean, modifier: Modifier) {
    val content: @Composable () -> Unit = {
        Icon(action.icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(action.label))
    }
    if (filled) Button(onClick = action.onClick, enabled = action.enabled,
        modifier = modifier.testTag(action.tag), content = { content() })
    else FilledTonalButton(onClick = action.onClick, enabled = action.enabled,
        modifier = modifier.testTag(action.tag), content = { content() })
}

/** The card Library's album grids use; the caller supplies artwork through its own source. */
@Composable
internal fun AlbumCardVisual(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    artwork: @Composable () -> Unit,
    menuItems: (@Composable (closeMenu: () -> Unit) -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(modifier.clip(RoundedCornerShape(12.dp)).let { base ->
            when {
                menuItems != null && onClick != null -> base.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                onClick != null -> base.clickable(onClick = onClick)
                menuItems != null -> base.clickable { menuOpen = true }
                else -> base
            }
        }) {
            artwork()
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (menuItems != null) DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menuItems { menuOpen = false }
        }
    }
}

/** The numbered list row Library's album, artist and genre tracks share. */
@Composable
internal fun TrackRowVisual(
    title: String,
    supporting: (@Composable () -> Unit)?,
    number: Int,
    modifier: Modifier,
    playing: Boolean = false,
    unavailable: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val accent = when {
        playing -> MaterialTheme.colorScheme.primary
        unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = accent) },
        supportingContent = supporting,
        leadingContent = leading ?: {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing), tint = accent)
                else Text(number.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        trailingContent = trailing,
        modifier = modifier.let { base -> if (onClick != null) base.clickable(onClick = onClick) else base },
    )
}
