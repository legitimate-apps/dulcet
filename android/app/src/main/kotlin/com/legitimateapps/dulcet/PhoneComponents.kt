package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.legitimateapps.dulcet.ui.DulcetIcons

/*
 * The phone's shared components (docs/DESIGN-CONVENTIONS, Android). A screen builds from these and from the rows in
 * PhoneLibraryVisuals.kt (TrackRowVisual, RankedMediaRow, AlbumCardVisual, LibraryFilterChips) rather than drawing its
 * own header, notice, empty state or dialog. Every value inside them is a DulcetSpacing / DulcetSizes token.
 */

/** Of the window's height: what is left under a top bar and above the tab bar and mini player. */
private const val EMPTY_PAGE_HEIGHT_FRACTION = 0.75f

/**
 * The shared empty state (AND-07): a centred block of a 48 dp outlined glyph in `onSurfaceVariant`, 16 dp, one title,
 * 8 dp, at most one supporting line, 24 dp, at most one action. The block is capped at 360 dp wide. Pages with nothing to
 * show use this instead of a bare text line; controls that act on content (sort, filter, shuffle) stay hidden while it is
 * visible. Pass an outlined glyph ([DulcetIcons.FavouriteBorder] and the like): the component sizes and tints it, it
 * cannot outline a filled one.
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
    pageInset: Dp = 0.dp,
    titleTag: String? = null,
) {
    // A page with nothing else on it centres its statement, as the unreachable Listen Now does. A minimum, not a
    // fixed height: at large text in a short window the statement outgrows it and the list scrolls to its action.
    val pageHeight = if (fillsPage) (LocalConfiguration.current.screenHeightDp * EMPTY_PAGE_HEIGHT_FRACTION).dp - pageInset else 0.dp
    Box(modifier.fillMaxWidth().heightIn(min = pageHeight).padding(DulcetSpacing.xxl), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = DulcetSizes.emptyMaxWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(DulcetSizes.emptyIcon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(DulcetSpacing.l))
            Text(title, titleTag?.let { Modifier.testTag(it) } ?: Modifier,
                style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            body?.let {
                Spacer(Modifier.height(DulcetSpacing.s))
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(DulcetSpacing.xl))
                Button(onClick = onAction, modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier) { Text(actionLabel) }
            }
        }
    }
}

/** What a [DulcetNotice] says about the page: its colours and its leading glyph. */
internal enum class NoticeTone { Info, Warning, Error, Progress }

/**
 * The one notice (AND-06): offline, stale, failed or in-progress state about the page it sits on. Rules:
 * - It is the first item **inside** the scrolling content, never pinned above a list; a page has one notice slot.
 * - [title] and [body] come as a pair: what happened, then what it means or what to do ("Can't reach the server" /
 *   "Showing what this phone already has."). Only a notice that needs no explanation omits [body].
 *   Use [detail] for a separate recovery paragraph. Set [showProgressIndicator] to false when
 *   the disabled primary action on the same form already shows progress.
 * - At most one action, a TextButton at the end of the last line, named for what it does: "Try again" for an
 *   unreachable server, "Sign in again" for refused credentials.
 *
 * Geometry: 16 dp padding, a 24 dp leading glyph (a 20 dp spinner for [NoticeTone.Progress]) and 12 dp to the text.
 * Colours: Error `errorContainer`; Warning `tertiaryContainer`; Info and Progress `secondaryContainer`. Put it inside
 * the page gutter (`padding(horizontal = DulcetSpacing.pageGutter)`); the notice adds no outer margin of its own.
 */
@Composable
internal fun DulcetNotice(
    tone: NoticeTone,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTag: String? = null,
    icon: ImageVector? = null,
    titleTag: String? = null,
    detail: String? = null,
    showProgressIndicator: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        NoticeTone.Error -> scheme.errorContainer to scheme.onErrorContainer
        NoticeTone.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        NoticeTone.Info, NoticeTone.Progress -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val glyph = icon ?: when (tone) {
        NoticeTone.Error, NoticeTone.Warning -> DulcetIcons.Warning
        NoticeTone.Info -> DulcetIcons.CloudOff
        NoticeTone.Progress -> null
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Row(Modifier.padding(DulcetSpacing.l), horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.m)) {
            if (tone != NoticeTone.Progress || showProgressIndicator) {
                // Center the glyph against the title's first line, including at large text sizes.
                val firstLine = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
                Box(
                    Modifier.width(DulcetSizes.icon).heightIn(min = maxOf(DulcetSizes.icon, firstLine)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tone == NoticeTone.Progress) CircularProgressIndicator(
                        Modifier.size(DulcetSizes.progressIndicator), color = content, strokeWidth = 2.dp,
                    ) else if (glyph != null) Icon(glyph, null, Modifier.size(DulcetSizes.icon))
                }
            }
            Column(Modifier.weight(1f)) {
                MetadataText(title, titleTag?.let { Modifier.testTag(it) } ?: Modifier,
                    style = MaterialTheme.typography.titleMedium, wholePhrases = true)
                body?.let {
                    Spacer(Modifier.height(DulcetSpacing.xs))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                detail?.let {
                    Spacer(Modifier.height(DulcetSpacing.s))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)
                        .let { base -> if (actionTag != null) base.testTag(actionTag) else base },
                        colors = ButtonDefaults.textButtonColors(contentColor = content)) { Text(actionLabel) }
                }
            }
        }
    }
}

/** Which kind of heading a [DulcetSectionHeader] is. */
internal enum class SectionRole {
    /** A shelf on Listen Now, the Library root, Search or Discover: `titleLarge` (restyle spec). */
    Shelf,
    /** A subsection inside a detail page (Edition, Tracks, an artist's Albums): `titleMedium`. */
    Detail,
}

/**
 * The one section heading (AND-04). The title starts on the page gutter; See all is a TextButton whose text ink ends on
 * the 16 dp keyline (12 dp content padding plus 4 dp end padding), so no screen adds its own `offset`. The row is at
 * least [DulcetSizes.touchTarget] tall with or without See all, so every shelf has the same rhythm.
 *
 * Rhythm: [DulcetSpacing.sectionGap] (24) of visible space above the title's line box and at least
 * [DulcetSpacing.headingToContent] (8) below it. The 48 dp row already holds part of both; this component subtracts
 * that part, so the caller adds no spacer of its own. Pass `topGap = 0.dp` for the first heading under a top bar. A
 * `ListItem` below adds its own 8 dp top padding; measure to the content's visible top.
 */
@Composable
internal fun DulcetSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    role: SectionRole = SectionRole.Shelf,
    onSeeAll: (() -> Unit)? = null,
    seeAllTag: String? = null,
    seeAllLabel: String = stringResource(com.legitimateapps.dulcet.shared.R.string.search_see_all),
    topGap: Dp = DulcetSpacing.sectionGap,
    followingRowTopInset: Dp = 0.dp,
) {
    val style = if (role == SectionRole.Shelf) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    val lineBox = with(LocalDensity.current) { style.lineHeight.toDp() }
    // The part of the 48 dp row above and below a one-line title; nothing at large text, where the line outgrows the row.
    val slack = ((DulcetSizes.touchTarget - lineBox) / 2).coerceAtLeast(0.dp)
    val beforeRow = followingRowTopInset > 0.dp
    val above = if (beforeRow) topGap else (topGap - slack).coerceAtLeast(0.dp)
    val below = if (beforeRow) (DulcetSpacing.m - followingRowTopInset).coerceAtLeast(0.dp)
        else (DulcetSpacing.headingToContent - slack).coerceAtLeast(0.dp)
    Row(
        modifier.fillMaxWidth()
            .padding(top = above, bottom = below)
            .heightIn(min = if (beforeRow) 0.dp else DulcetSizes.touchTarget)
            .padding(start = DulcetSpacing.pageGutter, end = if (onSeeAll != null) DulcetSpacing.xs else DulcetSpacing.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onSeeAll != null) {
            TextButton(onSeeAll, modifier = if (seeAllTag != null) Modifier.testTag(seeAllTag) else Modifier,
                contentPadding = PaddingValues(horizontal = DulcetSpacing.m)) { Text(seeAllLabel, maxLines = 1) }
        }
    }
}

/**
 * The one dialog shell for a short form or confirmation (AND-01): [DulcetSizes.dialogMinWidth] to
 * [DulcetSizes.dialogMaxWidth] wide whatever its content, 24 dp padding, a `headlineSmall` title, [content] at full
 * width (give text fields `fillMaxWidth()`), then the actions at the end: [dismissLabel] then [confirmLabel].
 * [destructive] colours the confirm action `error`.
 *
 * The dialog fills its window and pads for the keyboard itself (`decorFitsSystemWindows = false` plus `imePadding`), so a
 * focused field never resizes the window under it. A field that should focus on open asks from inside [content] (a
 * `LaunchedEffect` there runs once the dialog's window has composed).
 */
@Composable
internal fun DulcetDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    confirmTag: String? = null,
    dismissTag: String? = null,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(DulcetSpacing.xl), contentAlignment = Alignment.Center) {
            Surface(modifier.widthIn(min = DulcetSizes.dialogMinWidth, max = DulcetSizes.dialogMaxWidth).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(DulcetSpacing.xl), verticalArrangement = Arrangement.spacedBy(DulcetSpacing.l)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DulcetSpacing.s), content = content)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.s, Alignment.End)) {
                        if (dismissLabel != null) TextButton(onClick = onDismiss,
                            modifier = if (dismissTag != null) Modifier.testTag(dismissTag) else Modifier) { Text(dismissLabel) }
                        TextButton(onClick = onConfirm, enabled = confirmEnabled,
                            modifier = if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier,
                            colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                else ButtonDefaults.textButtonColors()) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
}
