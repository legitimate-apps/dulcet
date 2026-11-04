package com.legitimateapps.dulcet

import androidx.compose.material3.IconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain
import kotlin.math.roundToInt
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
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

/** Joins a row's metadata; [MetadataText] draws the result, so the mark never hangs at a line's end. */
internal fun List<String>.metadataLine(): String = joinToString(METADATA_SEPARATOR)

private const val METADATA_SEPARATOR = " · "

/**
 * Where a metadata line's parts break for a width: the whole line when it fits, otherwise as many
 * parts per line as [fits], with the mark dropped at each break. The last line [maxLines] allows
 * keeps every remaining part for the text's ellipsis to end, so nothing vanishes without one.
 */
internal fun breakMetadata(text: String, maxLines: Int = Int.MAX_VALUE, fits: (String) -> Boolean): String {
    val parts = text.split(METADATA_SEPARATOR)
    if (parts.size < 2 || maxLines < 2 || fits(text)) return text
    val lines = mutableListOf<String>()
    var line = parts.first()
    parts.drop(1).forEach { part ->
        val joined = line + METADATA_SEPARATOR + part
        if (lines.size == maxLines - 1 || fits(joined)) line = joined
        else { lines += line; line = part }
    }
    return (lines + line).joinToString("\n")
}

/**
 * Metadata text, measured against the width it really has: " · " stays while the line fits, at any
 * font size, and the line only breaks between parts when it does not. [leadColor] colours the first
 * part. It lays its own text out because a list row asks its supporting text for intrinsic sizes,
 * which rules out subcomposing for the width. Semantics always read the whole line.
 */
@Composable
internal fun MetadataText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    leadColor: Color? = null,
) {
    val contentColor = LocalContentColor.current
    val resolved = style.copy(
        color = color.takeOrElse { style.color.takeOrElse { contentColor } },
        textAlign = textAlign ?: style.textAlign,
    )
    val measurer = rememberTextMeasurer()
    val laidOut = remember { mutableStateOf<TextLayoutResult?>(null, neverEqualPolicy()) }
    val policy = remember(text, resolved, maxLines, overflow, leadColor, measurer) {
        fun oneLineWidth(line: String) = measurer.measure(line, resolved, softWrap = false, maxLines = 1).size.width
        fun laidOutIn(constraints: Constraints): TextLayoutResult {
            val shown = if (constraints.hasBoundedWidth) breakMetadata(text, maxLines) { oneLineWidth(it) <= constraints.maxWidth } else text
            val annotated = buildAnnotatedString {
                append(shown)
                if (leadColor != null) addStyle(SpanStyle(color = leadColor), 0, text.substringBefore(METADATA_SEPARATOR).length)
            }
            return measurer.measure(annotated, resolved, overflow, maxLines = maxLines,
                constraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        }
        object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val result = laidOutIn(constraints)
                laidOut.value = result
                val size = constraints.constrain(result.size)
                return layout(size.width, size.height, mapOf(FirstBaseline to result.firstBaseline.roundToInt(),
                    LastBaseline to result.lastBaseline.roundToInt())) {}
            }
            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = oneLineWidth(text)
            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
                text.split(METADATA_SEPARATOR).maxOf(::oneLineWidth)
            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
                laidOutIn(Constraints(maxWidth = width)).size.height
            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
                laidOutIn(Constraints(maxWidth = width)).size.height
        }
    }
    Layout(
        content = {},
        modifier = modifier
            .semantics {
                this.text = AnnotatedString(text)
                getTextLayoutResult { results -> laidOut.value?.let(results::add) != null }
            }
            .drawBehind { laidOut.value?.let { drawText(it) } },
        measurePolicy = policy,
    )
}

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
            MetadataText(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
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
            Box {
                artwork()
                if (moreTag != null) CardMoreButton({ menuOpen = true }, moreTag, stringResource(R.string.playlist_track_menu),
                    Modifier.align(Alignment.BottomEnd))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = if (largeText) 2 else 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                MetadataText(it, style = MaterialTheme.typography.bodySmall, maxLines = if (largeText) 2 else 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Spacer(Modifier.height(with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }))
        }
        if (menuItems != null) DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menuItems { menuOpen = false }
        }
    }
}

/**
 * A card's More, on its artwork's corner: beside the caption it took a column from the title and
 * the metadata at every font size. The disc keeps the glyph legible on any cover.
 */
@Composable
internal fun CardMoreButton(onClick: () -> Unit, tag: String, label: String, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.testTag(tag)) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(DulcetIcons.MoreVert, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
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
