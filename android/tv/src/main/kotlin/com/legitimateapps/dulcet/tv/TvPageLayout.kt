package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/*
 * Page scaffolding shared by the TV pages: one header, one notice slot, one two-pane root. A page builds its top from
 * these, never from its own Column of title, subtitle and padding.
 */

/**
 * A page's title, optional [subtitle] (a count, a summary) and optional [actions] (Play, Shuffle, filter chips), in one
 * rhythm on every page: title `displaySmall`, [TvSpacing.s] to the subtitle, [TvSpacing.l] to the actions, then
 * [TvSpacing.xl] to the page's content. The header owns that last gap: inside a list arranged `spacedBy(x)`, pass
 * [listSpacing] = x and the header pads the difference, so the content still starts 24 dp below it.
 *
 * Starts on the page gutter, not the ink column: a page title heads the whole page, cards and rows alike. Its
 * [actions] are a row of controls [TV_CONTROL_HEIGHT] high, [TvSpacing.m] apart.
 */
@Composable
internal fun TvPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleTag: String? = null,
    subtitleTag: String? = null,
    listSpacing: Dp = 0.dp,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(bottom = (TvSpacing.xl - listSpacing).coerceAtLeast(0.dp))) {
        Text(title, Modifier.let { if (titleTag != null) it.testTag(titleTag) else it },
            style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Spacer(Modifier.height(TvSpacing.s))
            Text(subtitle, Modifier.let { if (subtitleTag != null) it.testTag(subtitleTag) else it },
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (actions != null) {
            Spacer(Modifier.height(TvSpacing.l))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.m), verticalAlignment = Alignment.CenterVertically,
                content = actions)
        }
    }
}

/** The page notice slot's padding: under the tab bar, on the page gutter, 8 dp above and 16 dp below. */
internal val TV_PAGE_NOTICE_PADDING = PaddingValues(start = TV_PAGE_GUTTER, top = TvSpacing.s, end = TV_PAGE_GUTTER, bottom = TvSpacing.l)

/**
 * A page's one notice slot, directly under the tab bar and above the page: every route's connection notice
 * ([TvNotice]) goes here, so it sits at the same place on every tab and on album pages. Nothing is drawn, not even the
 * padding, while [visible] is false. Pages never place a connection notice themselves.
 */
@Composable
internal fun TvPageNoticeSlot(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!visible) return
    Box(modifier.fillMaxWidth().padding(TV_PAGE_NOTICE_PADDING)) { content() }
}

/** How far a two-pane root's panes sit under its title; their scrolled content dissolves across it. */
internal val TV_PANE_TOP = TvSpacing.m

/**
 * A two-pane root (Library, Browse): the page title on its own full-width row, then a [rail] of ways in on the left
 * ([TV_RAIL_WIDTH], rows `spacedBy(TvSpacing.xs)`) and the [content] pane on the right (sections
 * `spacedBy(TvSpacing.xl)`). Both panes start on one top line, [TV_PANE_TOP] under the title, so the rail's first row
 * and the content's first heading share a top edge; each scrolls on its own, and what scrolls up dissolves before the
 * title. The rail starts on the page gutter; the content pane [TV_PANE_GAP] after the rail and ends on the gutter.
 *
 * [railModifier] and [contentModifier] carry the panes' tags and semantics; [contentState] lets a caller bring a row
 * back into view. The rail's first row is the root's initial focus (`tvFocus(default = true, withRoute = true)`).
 */
@Composable
internal fun TvRootTwoPane(
    title: String,
    modifier: Modifier = Modifier,
    titleTag: String? = null,
    railModifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    contentState: LazyListState = rememberLazyListState(),
    rail: LazyListScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TvPageHeader(title, Modifier.padding(start = TV_PAGE_GUTTER, top = TV_PAGE_TOP, end = TV_PAGE_GUTTER),
            titleTag = titleTag, listSpacing = TvSpacing.xl) // no gap of its own: the panes own it (TV_PANE_TOP)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                Modifier.width(TV_RAIL_WIDTH).fillMaxHeight().tvPaneTopFade().then(railModifier),
                contentPadding = PaddingValues(start = TV_PAGE_GUTTER, top = TV_PANE_TOP, bottom = TV_PAGE_BOTTOM),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.xs),
                content = rail,
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().tvPaneTopFade().then(contentModifier),
                state = contentState,
                contentPadding = PaddingValues(start = TV_PANE_GAP, top = TV_PANE_TOP, end = TV_PAGE_GUTTER, bottom = TV_PAGE_BOTTOM),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.xl),
                content = content,
            )
        }
    }
}

/** Content scrolled up out of a pane dissolves across the pane's [TV_PANE_TOP] instead of being cut at the title. */
private fun Modifier.tvPaneTopFade(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val band = TV_PANE_TOP.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = band),
            size = androidx.compose.ui.geometry.Size(size.width, band), blendMode = BlendMode.DstIn)
    }
