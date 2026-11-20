package com.legitimateapps.dulcet

import androidx.compose.ui.platform.LocalConfiguration
import com.legitimateapps.dulcet.ui.sortControlSemantics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.textScaledIconSize
import com.legitimateapps.dulcet.ui.ArtworkEntity
import com.legitimateapps.dulcet.ui.MediaArtwork

/**
 * Whether the hero's title has scrolled out of the list. At large text a hero is taller than most of the
 * screen, so its title is gone long before its item is, and the bar has to take the title then.
 */
@Stable
internal class DetailHeroTitle {
    var gone by mutableStateOf(false)
        private set
    val modifier: Modifier = Modifier.onGloballyPositioned { gone = it.boundsInRoot().height <= 0f }
}

/** A hero is gone once the lazy viewport has moved beyond its actual item, or its [hero] title has left. */
@Composable
internal fun detailHeaderCollapsed(list: LazyListState, headerIndex: Int = 0, hero: DetailHeroTitle? = null): Boolean {
    val collapsed by remember(list, headerIndex, hero) {
        derivedStateOf {
            list.firstVisibleItemIndex > headerIndex || (list.firstVisibleItemIndex == headerIndex && hero?.gone == true)
        }
    }
    return collapsed
}

@Composable
internal fun detailHeaderCollapsed(grid: LazyGridState, headerIndex: Int = 0, hero: DetailHeroTitle? = null): Boolean {
    val collapsed by remember(grid, headerIndex, hero) {
        derivedStateOf {
            grid.firstVisibleItemIndex > headerIndex || (grid.firstVisibleItemIndex == headerIndex && hero?.gone == true)
        }
    }
    return collapsed
}

/** The compact identity replaces the scrolled-away hero; it adds no duplicate title at first entry. */
@Composable
internal fun DetailAppBarTitle(title: String?, count: String?, collapsed: Boolean, tag: String,
                               alwaysShowTitle: Boolean = false, titleTag: String? = null) {
    if (title == null || !collapsed && !alwaysShowTitle) return
    Column {
        DetailBarTitleText(title, MaterialTheme.typography.titleLarge, Modifier.testTag(titleTag ?: "$tag.collapsed.title"),
            maxLines = if (collapsed) 1 else detailBarTitleLines())
        if (collapsed && count != null) Text(count, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("$tag.collapsed.count"))
    }
}

/**
 * A top bar's title: balanced, so a two-line name at the accessibility font size never ends on one word, and
 * never opening a line with a dash. Semantics read the name as it is.
 */
@Composable
internal fun DetailBarTitleText(title: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = detailBarTitleLines()) {
    Text(title.dashKeptWithWordBefore(), modifier.semantics { text = AnnotatedString(title) },
        style = style.copy(lineBreak = LineBreak.Heading), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * Holds a dash to the word before it, so no line opens with one. An em dash may break on both sides even
 * with no space around it, hence the joiner; only the shown text changes, never what semantics read.
 */
internal fun String.dashKeptWithWordBefore(): String =
    replace(SPACE_BEFORE_DASH, "\u00A0").replace(BARE_EM_DASH, "\u2060\u2014")

private val SPACE_BEFORE_DASH = Regex(" (?=[\u2014\u2013-])")
private val BARE_EM_DASH = Regex("(?<=[^\\s\u00A0\u2060])\u2014")

/** True when a wrapped text's last line holds one word; a dash or a mark beside it is not a word. A cut-off text is not asked. */
internal fun TextLayoutResult.endsOnLoneWord(): Boolean {
    if (lineCount < 2 || didOverflowHeight) return false
    val lastLine = lineCount - 1
    val line = layoutInput.text.text.substring(getLineStart(lastLine), getLineEnd(lastLine, visibleEnd = true))
    return line.split(' ', '\u00A0').count { word -> word.any(Char::isLetterOrDigit) } == 1
}

/**
 * A hero's title wraps as ordinary text. Balance only steps in when that would leave one word alone on the
 * last line, because it re-breaks every line it touches. Semantics read the name as it is.
 */
@Composable
private fun DetailHeroTitleText(title: String, modifier: Modifier) {
    val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    val maxLines = if (isLargeText()) 6 else 3
    val shown = remember(title) { title.dashKeptWithWordBefore() }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.padding(horizontal = 16.dp)) {
        val width = constraints.maxWidth
        val balanced = remember(shown, style, maxLines, width, measurer) {
            measurer.measure(shown, style, TextOverflow.Ellipsis, maxLines = maxLines, constraints = Constraints(maxWidth = width)).endsOnLoneWord()
        }
        Text(shown, modifier.semantics { text = AnnotatedString(title) },
            style = if (balanced) style.copy(lineBreak = LineBreak.Heading) else style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * At large text, a destination title can use two lines while expanded. A collapsed hero uses one
 * line for its title and keeps a separate line for its count.
 */
@Composable
internal fun detailAppBarHeight(collapsed: Boolean, title: String? = null, count: String? = null, alwaysShowTitle: Boolean = false): Dp {
    val density = LocalDensity.current
    if (!isLargeText() || (!collapsed && !alwaysShowTitle)) return 64.dp
    val floor = if (collapsed && count != null) 96.dp else 64.dp
    if (title == null) return floor
    val measurer = rememberTextMeasurer()
    // Back arrow and two actions: an upper bound, so a name that might wrap always has its room.
    val width = (LocalConfiguration.current.screenWidthDp.dp - BAR_CHROME_WIDTH).coerceAtLeast(120.dp)
    val titleStyle = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading)
    val countStyle = MaterialTheme.typography.labelMedium
    return with(density) {
        val titleHeight = measurer.measure(title.dashKeptWithWordBefore(), titleStyle, maxLines = if (collapsed) 1 else DETAIL_BAR_LARGE_TEXT_LINES,
            constraints = Constraints(maxWidth = width.roundToPx())).size.height.toDp()
        val countHeight = if (collapsed && count != null) measurer.measure(count, countStyle).size.height.toDp() else 0.dp
        maxOf(floor, titleHeight + countHeight + 16.dp)
    }
}

/** A top bar title's line limit: one, or two at the accessibility font size. */
@Composable
internal fun detailBarTitleLines(): Int = if (isLargeText()) DETAIL_BAR_LARGE_TEXT_LINES else 1

private const val DETAIL_BAR_LARGE_TEXT_LINES = 2
private val BAR_CHROME_WIDTH = 176.dp

/**
 * A menu row that grows with its text: Material's 48 dp minimum holds for one line at any ordinary
 * size, and a wrapped or enlarged label keeps padding above and below instead of touching its neighbour.
 */
@Composable
internal fun PhoneMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
) = DropdownMenuItem(text, onClick, modifier, leadingIcon, trailingIcon, enabled, colors,
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp))

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

/** The one mark between a metadata line's parts. [MetadataText] breaks lines at it and drops it at each break. */
internal const val METADATA_SEPARATOR = " · "

/**
 * Lets go of a glued phrase ("Last updated 2 hours ago.") only when it is wider than the line, where the glue
 * would make the text break inside a word. Every phrase that fits stays whole, so a too-long line breaks between sentences.
 */
internal fun keepPhrasesThatFit(text: String, fits: (String) -> Boolean): String =
    text.split(' ').joinToString(" ") { phrase -> if ('\u00A0' in phrase && !fits(phrase)) phrase.replace('\u00A0', ' ') else phrase }

/**
 * Where a metadata line's parts break for a width: the whole line when it fits, otherwise as many
 * parts per line as [fits], with the mark dropped at each break, so every line starts and ends with a part. The last
 * line [maxLines] allows keeps every remaining part for the text's ellipsis to end, so nothing vanishes without one.
 *
 * A part too wide for a line on its own wraps inside itself anyway; breaking after it would spend a whole line on what
 * follows ("0:29") and, at two lines, cut it. So the next part rides that part's last wrapped line instead, its mark
 * held to the words on both sides ([GLUED_SEPARATOR]), and a wrap moves "Artists · 0:29" down as one piece: no line
 * opens or ends on a bare mark either way.
 */
internal fun breakMetadata(text: String, maxLines: Int = Int.MAX_VALUE, fits: (String) -> Boolean): String {
    val parts = text.split(METADATA_SEPARATOR)
    if (parts.size < 2 || maxLines < 2 || fits(text)) return text
    val lines = mutableListOf<String>()
    var line = parts.first()
    parts.drop(1).forEach { part ->
        val joined = line + METADATA_SEPARATOR + part
        when {
            lines.size == maxLines - 1 || fits(joined) -> line = joined
            // The part before wraps on its own; its last word and this part go down together if a wrap moves them.
            !fits(line) && fits(line.substringAfterLast(' ').replace('\u00A0', ' ') + METADATA_SEPARATOR + part) ->
                line += GLUED_SEPARATOR + part
            else -> { lines += line; line = part }
        }
    }
    return (lines + line).joinToString("\n")
}

/** [METADATA_SEPARATOR] with no break on either side of the mark. */
private const val GLUED_SEPARATOR = "\u00A0·\u00A0"

/**
 * One line that keeps its first part whole: on a mixed-artist card the artist identifies the album and the
 * year is extra, so later parts leave, last first, until the line fits. A lead too long even alone comes
 * back alone, for the text's own ellipsis to end.
 */
internal fun keepMetadataLead(text: String, fits: (String) -> Boolean): String {
    var parts = text.split(METADATA_SEPARATOR)
    while (parts.size > 1 && !fits(parts.joinToString(METADATA_SEPARATOR))) parts = parts.dropLast(1)
    return parts.joinToString(METADATA_SEPARATOR)
}

/**
 * Metadata text, measured against the width it really has: " · " stays while the line fits, at any
 * font size, and the line only breaks between parts when it does not. [leadColor] colours the first
 * part. With [keepLead], a line too wide for one line gives up its later parts and never shortens
 * its first to keep them. It lays its own text out because a list row asks its supporting text for intrinsic sizes,
 * which rules out subcomposing for the width. Semantics always read the whole line. With [wholePhrases], the text is
 * sentences rather than parts, for [keepPhrasesThatFit]. With [noLoneLastWord], the text is a name: see [WrappedTitleText].
 * [spokenText] is what a screen reader says when a row's line leaves out what the row's own header shows.
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
    keepLead: Boolean = false,
    wholePhrases: Boolean = false,
    noLoneLastWord: Boolean = false,
    spokenText: String = text,
) {
    val contentColor = LocalContentColor.current
    val resolved = style.copy(
        color = color.takeOrElse { style.color.takeOrElse { contentColor } },
        textAlign = textAlign ?: style.textAlign,
    )
    val measurer = rememberTextMeasurer()
    val laidOut = remember { mutableStateOf<TextLayoutResult?>(null, neverEqualPolicy()) }
    val policy = remember(text, resolved, maxLines, overflow, leadColor, keepLead, wholePhrases, noLoneLastWord, measurer) {
        fun oneLineWidth(line: String) = measurer.measure(line, resolved, softWrap = false, maxLines = 1).size.width
        fun laidOutIn(constraints: Constraints): TextLayoutResult {
            val fits = { line: String -> oneLineWidth(line) <= constraints.maxWidth }
            val shown = when {
                !constraints.hasBoundedWidth || noLoneLastWord -> text
                wholePhrases -> keepPhrasesThatFit(text, fits)
                keepLead -> keepMetadataLead(text, fits)
                else -> breakMetadata(text, maxLines, fits)
            }
            val annotated = buildAnnotatedString {
                append(shown)
                if (leadColor != null) addStyle(SpanStyle(color = leadColor), 0, shown.substringBefore(METADATA_SEPARATOR).substringBefore("\n").length)
            }
            val laidOutConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val ordinary = measurer.measure(annotated, resolved, overflow, maxLines = maxLines, constraints = laidOutConstraints)
            if (!noLoneLastWord || !ordinary.endsOnLoneWord() || !shown.lastWordNeedsCompany()) return ordinary
            // The last two words held together, and nothing else moved: a balanced break would re-break every line.
            val lastBreak = shown.trimEnd().lastIndexOf(' ')
            if (lastBreak <= 0 || oneLineWidth(shown.substring(shown.lastIndexOf(' ', lastBreak - 1) + 1)) > constraints.maxWidth) return ordinary
            val held = measurer.measure(shown.replaceRange(lastBreak, lastBreak + 1, "\u00A0"), resolved, overflow, maxLines = maxLines, constraints = laidOutConstraints)
            return if (held.didOverflowHeight || held.endsOnLoneWord()) ordinary else held
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
                (if (noLoneLastWord) text.split(' ') else text.split(METADATA_SEPARATOR)).maxOf(::oneLineWidth)
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
                this.text = AnnotatedString(spokenText)
                getTextLayoutResult { results -> laidOut.value?.let(results::add) != null }
            }
            .drawBehind { laidOut.value?.let { drawText(it) } },
        measurePolicy = policy,
    )
}

/**
 * Whether a last line of this one word would read as left over. A number means nothing without the word before it ("Fixture Artist /
 * 00"), nor does a very short word, and a name of four words or more has a line to spare. A plain word ending a three-word name
 * stands on its own: "Twenty Nine / Seconds" reads better than "Twenty / Nine Seconds", which splits the number to rescue it.
 */
private fun String.lastWordNeedsCompany(): Boolean {
    val words = trim().split(' ').filter(String::isNotEmpty)
    val last = words.lastOrNull() ?: return false
    return words.size >= 4 || last.any(Char::isDigit) || last.count(Char::isLetter) <= 3
}

/**
 * A row's or a tile's name. At the accessibility font size, where such a name wraps, a last line of one word that
 * [lastWordNeedsCompany] ("Paging Track / 130") takes the word before it down. Below that size it is a plain [Text], so an ordinary row pays nothing and no
 * ordinary frame can change. Above it, a name that fits or ends well costs the one layout any text costs; only a name
 * that would strand a word costs a second, once per width, because a row keeps its measurement until its text changes.
 */
@Composable
internal fun WrappedTitleText(
    title: String,
    modifier: Modifier = Modifier,
    maxLines: Int,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    if (isLargeText() && maxLines >= 2) {
        MetadataText(title, modifier, style = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style, color = color,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis, noLoneLastWord = true)
    } else {
        Text(title, modifier, color = color, fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
    }
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

/**
 * Library's centred detail header. [actions] receives the same Material buttons each detail kind uses.
 *
 * The header has three text tiers: title, primary-coloured subtitle, and one meta style (`bodyMedium`,
 * `onSurfaceVariant`). A second fact about the same thing — "2 of 3 downloaded", "8 of 24 in your library" — goes in
 * [statusLine], which joins [metadata] as one more part ("2026 · 3 songs · 1 min · 2 downloaded") and wraps between
 * parts only when the line does not fit. [status] is for what is not text (a progress glyph); never a second meta line.
 */
@Composable
internal fun LibraryDetailHeader(
    title: String,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleTag: String? = null,
    /** Told when this title leaves the list, so the bar can take it: see [detailHeaderCollapsed]. */
    heroTitle: DetailHeroTitle? = null,
    subtitle: String? = null,
    onSubtitle: (() -> Unit)? = null,
    metadata: String? = null,
    statusLine: String? = null,
    statusLineTag: String? = null,
    status: (@Composable () -> Unit)? = null,
    artworkSpacing: Dp = 20.dp,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        artwork()
        Spacer(Modifier.height(artworkSpacing))
        DetailHeroTitleText(title, Modifier.let { base -> titleTag?.let { base.testTag(it) } ?: base }.then(heroTitle?.modifier ?: Modifier))
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .let { base -> if (onSubtitle != null) base.clickable(onClick = onSubtitle) else base }
                    .padding(4.dp))
        }
        listOfNotNull(metadata, statusLine).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { parts ->
            MetadataText(parts.metadataLine(), Modifier.padding(horizontal = DulcetSpacing.pageGutter)
                .let { base -> statusLineTag?.let { base.testTag(it) } ?: base },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        status?.invoke()
        if (actions != null) {
            Spacer(Modifier.height(DulcetSpacing.l))
            actions()
            Spacer(Modifier.height(DulcetSpacing.s))
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
    if (stacked || isLargeText()) {
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
        Icon(action.icon, null, Modifier.size(textScaledIconSize(20.dp)).testTag("${action.tag}.icon"))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(action.label), maxLines = 1, softWrap = false)
    }
    if (filled) Button(onClick = action.onClick, enabled = action.enabled,
        modifier = modifier.testTag(action.tag), content = { content() })
    else FilledTonalButton(onClick = action.onClick, enabled = action.enabled,
        modifier = modifier.testTag(action.tag), content = { content() })
}

private const val CARD_TITLE_LINES = 2

/**
 * The card Library's album grids and every rail use; the caller supplies artwork through its own source. In a rail, size
 * it with [railTileWidth]; in a grid, the cell sizes it. Unused caption height sits below the text.
 */
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
    moreLabel: String = stringResource(R.string.playlist_track_menu),
    moreInset: Dp = 0.dp,
    captionStatus: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(modifier.clip(RoundedCornerShape(12.dp)).let { base ->
            when {
                menuItems != null && onClick != null -> base.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                onClick != null -> base.clickable(onClick = onClick)
                menuItems != null -> base.combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true })
                else -> base
            }
        }) {
            Box {
                artwork()
                if (moreTag != null) CardMoreButton({ menuOpen = true }, moreTag, moreLabel,
                    Modifier.align(Alignment.BottomEnd).padding(end = moreInset, bottom = moreInset))
            }
            Spacer(Modifier.height(DulcetSpacing.s))
            val titleStyle = MaterialTheme.typography.titleSmall
            val subtitleStyle = MaterialTheme.typography.bodySmall
            val captionHeight = with(LocalDensity.current) {
                (titleStyle.lineHeight * CARD_TITLE_LINES).toDp() + subtitleStyle.lineHeight.toDp() + DulcetSpacing.xs
            }
            Column(Modifier.heightIn(min = captionHeight), verticalArrangement = Arrangement.spacedBy(DulcetSpacing.xs)) {
                WrappedTitleText(title, style = titleStyle, maxLines = CARD_TITLE_LINES, fontWeight = FontWeight.SemiBold)
                subtitle?.let {
                    // A card is too narrow to break between parts, so the year gives way to the artist.
                    MetadataText(it, style = subtitleStyle, maxLines = 1, keepLead = true,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                captionStatus?.invoke()
            }
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
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(DulcetIcons.MoreVert, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** What a media row's reserved status box shows: the song is playing, or it is on this device. */
internal enum class RowStatus { Playing, Downloaded }

/**
 * The numbered list row Library's album, artist and genre tracks share, and the base of every song row.
 *
 * Geometry (AND-03): [leading] is the row's art at [DulcetSizes.rowArt]; without art the row shows its [number] (or the
 * playing glyph) in [DulcetSizes.numberSlot]. With a [rank], [RankedLeading] puts the rank before the art, so the text column starts
 * at 120 dp on every ranked list. [status] is drawn in a 24 dp box placed before [trailing]; with [reserveStatus] the
 * box is kept empty on a row without a status, so every row's text column ends in one place. A list passes
 * `reserveStatus = true` to every row when at least one of its rows shows a status, and false otherwise: a box no row
 * fills only takes width from the titles. More keeps its column either way. The trailing column's ink ends on the 16 dp keyline
 * ([TrailingSlot]). The supporting line carries text only; never put a glyph inline in it.
 */
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
    status: RowStatus? = null,
    reserveStatus: Boolean = false,
    statusTag: String? = null,
    numberTag: String? = null,
) {
    val accent = when {
        playing -> MaterialTheme.colorScheme.primary
        unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val hasStatusBox = status != null || reserveStatus
    ListItem(
        headlineContent = { WrappedTitleText(title, maxLines = titleMaxLines, color = accent) },
        supportingContent = supporting,
        leadingContent = { CentredSlot {
            when {
                rank != null -> RankedLeading(rank) { leading?.invoke() }
                leading != null -> leading()
                else -> Box(Modifier.width(DulcetSizes.numberSlot), contentAlignment = Alignment.Center) {
                    if (playing) Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing), tint = accent)
                    else Text(number.toString(), numberTag?.let { Modifier.testTag(it) } ?: Modifier,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } },
        trailingContent = if (trailing == null && !hasStatusBox) null else ({
            val content: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.xs)) {
                    if (hasStatusBox) RowStatusBox(status, statusTag)
                    trailing?.invoke()
                }
            }
            // Only a trailing IconButton carries the 12 dp of empty box that the optical offset takes back.
            if (trailing != null) TrailingSlot(content = content) else CentredSlot(content = content)
        }),
        modifier = modifier.centredSlots().let { base -> if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base },
    )
}

/** A row's status glyph in its fixed 24 dp box; an empty box when [status] is null, so the column stays put. */
@Composable
internal fun RowStatusBox(status: RowStatus?, tag: String? = null) {
    Box(Modifier.size(DulcetSizes.icon), contentAlignment = Alignment.Center) {
        when (status) {
            RowStatus.Playing -> Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing),
                Modifier.size(DulcetSizes.icon).let { base -> tag?.let { base.testTag(it) } ?: base },
                tint = MaterialTheme.colorScheme.primary)
            RowStatus.Downloaded -> Icon(DulcetIcons.DownloadDone, stringResource(com.legitimateapps.dulcet.shared.R.string.download_downloaded),
                Modifier.size(DulcetSizes.icon).let { base -> tag?.let { base.testTag(it) } ?: base },
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            null -> Unit
        }
    }
}

/**
 * A ranked row's leading slot: the rank in tabular figures in a [DulcetSizes.rankSlot] slot, [DulcetSpacing.xs], then the
 * art. One definition, so Most played and an artist's Top songs start their text on the same 120 dp column.
 */
@Composable
internal fun RankedLeading(rank: Int, artwork: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.xs)) {
        Text(rank.toString(), Modifier.widthIn(min = DulcetSizes.rankSlot), maxLines = 1,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        artwork()
    }
}

/**
 * A ranked media row (an album or artist with a rank, as Most played lists them): [RankedLeading] and the shared slot
 * rules. A ranked *song* is a [TrackRowVisual] with `rank`, which uses the same leading slot.
 */
@Composable
internal fun RankedMediaRow(
    rank: Int?,
    title: String,
    modifier: Modifier,
    artwork: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleMaxLines: Int = 2,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { WrappedTitleText(title, maxLines = titleMaxLines) },
        supportingContent = supporting,
        leadingContent = { CentredSlot { if (rank != null) RankedLeading(rank, artwork) else artwork() } },
        trailingContent = trailing?.let { content -> { TrailingSlot(content = content) } },
        modifier = modifier.centredSlots().let { base -> if (onClick != null) base.clickable(onClick = onClick) else base },
    )
}

/**
 * A list row's leading or trailing slot. Material's [ListItem] lifts both slots to the row's top once its supporting text
 * runs to a second line, but not when its title does, so two rows of the same height in one list put their number and
 * More in two places. On a row with [centredSlots], the slot is as tall as the row's content area, which Material
 * measures it against, and places what it holds the same way in every row of every height:
 * - at ordinary text sizes, centred on the row;
 * - at large text ([isLargeText]), top-aligned with a [DulcetSizes.slotTopOffset] when [topAlignAtLargeText] is true,
 *   so artwork sits beside the title in a multiline row. A row can opt to keep its single-line content centered.
 */
@Composable
internal fun CentredSlot(topAlignAtLargeText: Boolean = true, content: @Composable () -> Unit) {
    if (topAlignAtLargeText && isLargeText()) {
        Box(Modifier.fillMaxHeight().padding(top = DulcetSizes.slotTopOffset), contentAlignment = Alignment.TopCenter) { content() }
    } else {
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * A row's trailing slot that ends in a 48 dp IconButton (More, a queue menu): a [CentredSlot] moved by
 * [trailingOptical], so the 24 dp glyph box ends 16 dp from the edge, in the top bar's action column. The touch target is
 * unchanged. A trailing slot of text or a bare glyph uses [CentredSlot] instead.
 */
@Composable
internal fun TrailingSlot(topAlignAtLargeText: Boolean = true, content: @Composable () -> Unit) {
    Box(Modifier.trailingOptical()) { CentredSlot(topAlignAtLargeText, content) }
}

/** The row's half of [CentredSlot]: the row settles its own natural height first, so a slot has a height to fill. */
internal fun Modifier.centredSlots(): Modifier = height(IntrinsicSize.Min)

/**
 * The list pages' one filter row: toggle chips with a leading check when on — no standalone "All"
 * chip, no selection means all — with the single sort control trailing. [sortLabel] names the
 * current order and anchors its dropdown. The category supplies the available filters and sort choices.
 */
@Composable
internal fun LibraryFilterChips(
    filter: String,
    onFilter: (String) -> Unit,
    category: String,
    modifier: Modifier = Modifier,
    showDownloaded: Boolean = true,
    showFilters: Boolean = true,
    sorts: List<Pair<String, String>> = emptyList(),
    selectedSort: String = "title",
    onSort: (String) -> Unit = {},
    sortLabel: String? = null,
) {
    val chips = if (showFilters) {
        listOf("favorites" to stringResource(R.string.library_favorites_title)) +
            if (showDownloaded) listOf("downloaded" to stringResource(com.legitimateapps.dulcet.shared.R.string.library_downloaded)) else emptyList()
    } else emptyList()
    val sortTag = "library.$category.sort"
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("library.$category.controls").chipRowEdges(row),
        state = row,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DulcetSpacing.pageGutter, vertical = DulcetSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.s),
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
        if (sorts.isNotEmpty()) item(key = "sort") {
            val order = sorts.firstOrNull { it.first == selectedSort }?.second ?: sorts.first().second
            var open by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { Text(sortLabel ?: sortChipLabel(order), maxLines = 1, softWrap = false) },
                    trailingIcon = { Icon(DulcetIcons.ExpandMore, null, Modifier.size(18.dp)) },
                    modifier = Modifier.sortControlSemantics(order, open).testTag(sortTag),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for ((key, label) in sorts) PhoneMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                            if (key == selectedSort) Icon(DulcetIcons.Check, null)
                            else Spacer(Modifier.size(DulcetSizes.icon))
                        },
                        onClick = { open = false; onSort(key) },
                        modifier = Modifier.testTag("$sortTag.$key").semantics { selected = key == selectedSort },
                    )
                }
            }
        }
    }
}

/**
 * A horizontally scrolling chip row's edges. The row keeps its 16 dp content padding, but padding shows only at the
 * row's two ends; between them a chip meets the window edge and is cut mid-letter, which at the largest font reads as a
 * broken label rather than more to scroll to. Each edge fades out over [width] while the row can scroll that way, so
 * the last chip still peeks (the scroll cue round 11 kept) and a chip scrolled past the start never ends in a hard cut.
 */
internal fun Modifier.chipRowEdges(state: LazyListState, width: Dp = 32.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 2)
        val ltr = layoutDirection == LayoutDirection.Ltr
        val (left, right) = if (ltr) state.canScrollBackward to state.canScrollForward else state.canScrollForward to state.canScrollBackward
        if (left) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
            size = Size(fade, size.height), blendMode = BlendMode.DstIn)
        if (right) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstIn)
    }
