package com.legitimateapps.dulcet

import androidx.compose.material3.IconButton
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.layout
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity
import com.legitimateapps.dulcet.ui.MediaArtwork

/** A hero is gone only once the lazy viewport has moved beyond its actual item. */
@Composable
internal fun detailHeaderCollapsed(list: LazyListState, headerIndex: Int = 0): Boolean {
    val collapsed by remember(list, headerIndex) { derivedStateOf { list.firstVisibleItemIndex > headerIndex } }
    return collapsed
}

@Composable
internal fun detailHeaderCollapsed(grid: LazyGridState, headerIndex: Int = 0): Boolean {
    val collapsed by remember(grid, headerIndex) { derivedStateOf { grid.firstVisibleItemIndex > headerIndex } }
    return collapsed
}

/** The compact identity replaces the scrolled-away hero; it adds no duplicate title at first entry. */
@Composable
internal fun DetailAppBarTitle(title: String?, count: String?, collapsed: Boolean, tag: String,
                               alwaysShowTitle: Boolean = false, titleTag: String? = null) {
    if (title == null || !collapsed && !alwaysShowTitle) return
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(titleTag ?: "$tag.collapsed.title"))
        if (collapsed && count != null) Text(count, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("$tag.collapsed.count"))
    }
}

/** Two scaled text lines need more than the standard64dp bar at the accessibility font size. */
@Composable
internal fun detailAppBarHeight(collapsed: Boolean): Dp =
    if (collapsed && LocalDensity.current.fontScale >= 1.5f) 96.dp else 64.dp

/**
 * For a full-width item of a lazy list with 16 dp horizontal content padding, when the item carries
 * its own 16 dp keyline (a [ListItem], a scrolling chip row, the detail buttons). It undoes the
 * list's padding, so the two insets don't add up to 32 dp and a row's ripple reaches both edges.
 */
internal fun Modifier.bleedKeyline(inset: Dp = 16.dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx()
    val placeable = measurable.measure(constraints.copy(
        minWidth = constraints.minWidth + 2 * extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth,
    ))
    layout(placeable.width - 2 * extra, placeable.height) { placeable.placeRelative(-extra, 0) }
}

/**
 * Joins a row's metadata. The largest fonts wrap these lines wherever they run out, which can leave
 * a "·" hanging at a line's end, so there each part takes its own line and the mark goes away.
 */
@Composable @ReadOnlyComposable
internal fun List<String>.metadataLine(): String =
    joinToString(if (LocalDensity.current.fontScale >= METADATA_OWN_LINES_FONT_SCALE) "\n" else " · ")

/** The same for a finished string resource that holds its own " · ". */
@Composable @ReadOnlyComposable
internal fun String.metadataBreak(): String = split(" · ").metadataLine()

private const val METADATA_OWN_LINES_FONT_SCALE = 1.5f

/** The visual frame Library artwork uses; loaders stay with the account or Discover endpoint that owns them. */
@Composable
internal fun ArtworkFrame(image: ImageBitmap?, contentDescription: String?, size: Dp?, modifier: Modifier = Modifier,
                         entity: ArtworkEntity = ArtworkEntity.Album) {
    val shape = if (entity == ArtworkEntity.Artist) CircleShape
        else RoundedCornerShape(if (size != null && size < 80.dp) 8.dp else 12.dp)
    val sized = if (size != null) modifier.size(size) else modifier
    MediaArtwork(image, entity, contentDescription, sized, shape,
        placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        iconColor = MaterialTheme.colorScheme.onSurfaceVariant)
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
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp).let { base -> titleTag?.let { base.testTag(it) } ?: base })
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .let { base -> if (onSubtitle != null) base.clickable(onClick = onSubtitle) else base }
                    .padding(4.dp))
        }
        metadata?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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

/** Library's filled/tonal detail actions. They split the content width equally, never wrap their
 * labels, and stack once text grows past the point where a pair would clip (font scale ≥ 1.5). */
@Composable
internal fun DetailActionButtons(primary: DetailAction, secondary: DetailAction? = null, stacked: Boolean = false) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    if (stacked || largeText) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailButton(primary, true, Modifier.fillMaxWidth())
            secondary?.let { DetailButton(it, false, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailButton(primary, true, Modifier.weight(1f))
            secondary?.let { DetailButton(it, false, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DetailButton(action: DetailAction, filled: Boolean, modifier: Modifier) {
    val content: @Composable () -> Unit = {
        Icon(action.icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(action.label), maxLines = 1, softWrap = false)
    }
    if (filled) Button(onClick = action.onClick, enabled = action.enabled,
        modifier = modifier.testTag(action.tag), content = { content() })
    else FilledTonalButton(onClick = action.onClick, enabled = action.enabled,
        modifier = modifier.testTag(action.tag),
        colors = ButtonDefaults.filledTonalButtonColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ), content = { content() })
}

/** The card Library's album grids use; the caller supplies artwork through its own source. */
@Composable
internal fun AlbumCardVisual(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    artwork: @Composable () -> Unit,
    menuItems: (@Composable (closeMenu: () -> Unit) -> Unit)? = null,
    /** Names a visible More beside the title, for a card among rows that all carry one. */
    moreTag: String? = null,
    onClick: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val largeText = LocalDensity.current.fontScale >= 1.5f
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
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (moreTag != null) IconButton({ menuOpen = true }, Modifier.size(32.dp).testTag(moreTag)) {
                    Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu), Modifier.size(20.dp))
                }
            }
            } ?: Spacer(Modifier.height(with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }))
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
    titleMaxLines: Int = 2,
    enabled: Boolean = true,
    rank: Int? = null,
) {
    val accent = when {
        playing -> MaterialTheme.colorScheme.primary
        unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = { Text(title, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis, color = accent) },
        supportingContent = supporting,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rank != null) Text(rank.toString(), Modifier.widthIn(min = 28.dp, max = 48.dp), maxLines = 1,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (leading != null) leading()
                else if (rank == null) Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    if (playing) Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing), tint = accent)
                    else Text(number.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        trailingContent = trailing,
        modifier = modifier.let { base -> if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base },
    )
}

/**
 * The list pages' one filter row: toggle chips with a leading check when on — no standalone "All"
 * chip, no selection means all — with the single sort control trailing. [sortLabel] names the
 * current order; tapping it opens [LibrarySortSheet] from the caller. Tags: `library.<category>.filter.<key>`
 * and [sortTag] (`library.<category>.sort`), the ones the chip strip carried.
 */
@Composable
internal fun LibraryFilterChips(
    filter: String,
    onFilter: (String) -> Unit,
    category: String,
    modifier: Modifier = Modifier,
    showDownloaded: Boolean = true,
    sortLabel: String? = null,
    sortTag: String? = null,
    onSort: (() -> Unit)? = null,
) {
    val chips = listOf("favorites" to stringResource(R.string.library_favorites_title)) +
        if (showDownloaded) listOf("downloaded" to stringResource(com.legitimateapps.dulcet.shared.R.string.library_downloaded)) else emptyList()
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(chips, key = { it.first }) { (key, label) ->
            val active = filter == key
            FilterChip(
                selected = active,
                onClick = { onFilter(if (active) "all" else key) },
                label = { Text(label, maxLines = 1, softWrap = false) },
                leadingIcon = { if (active) Icon(DulcetIcons.Check, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("library.$category.filter.$key"),
            )
        }
        if (sortLabel != null && sortTag != null && onSort != null) item(key = "sort") {
            AssistChip(
                onClick = onSort,
                label = { Text(sortLabel, maxLines = 1, softWrap = false) },
                trailingIcon = { Icon(DulcetIcons.ExpandMore, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag(sortTag),
            )
        }
    }
}

/** One sort choice in [LibrarySortSheet]: the value the category store keeps, and its label. */
internal class LibrarySortChoice(val key: String, val label: String)

/**
 * A list's sort as a modal bottom sheet of radio rows — the one place an order is chosen (the
 * trigger stays a single chip). Each row is tagged `<tag>.<key>` with `selected` semantics, as the
 * old dropdown's items were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySortSheet(
    choices: List<LibrarySortChoice>,
    selected: String,
    onChoose: (String) -> Unit,
    tag: String,
    onDismiss: () -> Unit,
) {
    PhoneModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(com.legitimateapps.dulcet.shared.R.string.library_sort_by),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        )
        for (choice in choices) {
            val isSelected = choice.key == selected
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onChoose(choice.key); onDismiss() })
                    .testTag("$tag.${choice.key}")
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
        }
    }
}
