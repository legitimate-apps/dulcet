package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.highlightAt
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.lyricsStatement
import com.legitimateapps.dulcet.library.rememberMediaTime
import com.legitimateapps.dulcet.library.rememberNowPlayingLyrics
import com.legitimateapps.dulcet.shared.R as SharedR

/**
 * The TV's lyrics sit between the song metadata and the scrubber (§18.4). The highlighted line
 * follows media time without taking D-pad focus. Plain lyrics start at the top, and no lyrics,
 * loading, and failed reads each have their own state.
 */
@Composable
internal fun TvLyricsPanel(session: LibrarySession, state: AndroidPlaybackState, modifier: Modifier = Modifier) {
    val shown = rememberNowPlayingLyrics(session, state)
    val current = shown.publication
    val observation by session.observation.collectAsState()
    val resources = libraryResources()
    val highlight = current?.highlightAt(rememberMediaTime(state))
    val list = rememberLazyListState()
    LaunchedEffect(highlight?.first) {
        val first = highlight?.first ?: return@LaunchedEffect
        if (first < 0) return@LaunchedEffect
        list.animateScrollToItem((first - 1).coerceAtLeast(0))
        // Keep a line in view even if its predecessor wrapped and filled the viewport.
        val viewport = list.layoutInfo
        val active = viewport.visibleItemsInfo.firstOrNull { it.index == first }
        if (active == null || active.offset + active.size > viewport.viewportEndOffset) {
            list.animateScrollToItem(first)
        }
    }
    val topAligned = current?.state == AndroidLyricsState.Lyrics || current?.state == AndroidLyricsState.None
    Column(modifier.fillMaxWidth().fillMaxHeight().testTag("tv.player.lyrics.panel").semantics { this[LibraryObservation] = observation },
        verticalArrangement = if (topAligned) Arrangement.Top else Arrangement.Center) {
        if (current == null) {
            TvLoadingProgress("tv.player.lyrics.loading")
            return@Column
        }
        if (current.state == AndroidLyricsState.None) {
            TvEmptyState(
                title = stringResource(R.string.tv_lyrics_empty_title),
                icon = TvEmptyIcons.Lyrics,
                tag = "tv.player.lyrics.empty",
                modifier = Modifier.padding(top = TvSpacing.xl),
            )
            return@Column
        }
        resources.lyricsStatement(current)?.let {
            Text(it, Modifier.testTag("tv.player.lyrics.statement"), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current.state != AndroidLyricsState.Lyrics) return@Column
        Spacer(Modifier.height(TvSpacing.xl))
        if (current.trimmed) {
            Text(resources.getString(SharedR.string.lyrics_trimmed), Modifier.testTag("tv.player.lyrics.trimmed"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val lyricStyle = MaterialTheme.typography.headlineSmall
        val interludeHeight = lyricStyle.lineDp(LocalDensity.current) / 2
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(bottom = TvSpacing.l).testTag("tv.player.lyrics").lyricsEdgeFade(list),
            state = list,
        ) {
            itemsIndexed(current.lines) { index, line ->
                val lit = highlight?.lights(index) == true
                val lineModifier = Modifier.testTag("tv.player.lyrics.line.$index").semantics { selected = lit }
                if (line.text.isBlank()) {
                    Spacer(lineModifier.height(interludeHeight))
                    return@itemsIndexed
                }
                Text(line.text,
                    lineModifier.padding(bottom = TvSpacing.xs),
                    style = lyricStyle,
                    fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
                    // The line being sung, in the phone's lit-line colour; unsung lines recede.
                    color = if (lit) MaterialTheme.colorScheme.onSurface
                        else if (!current.synced) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** So a line crossing the viewport edge recedes clear of the scrubber. */
private fun Modifier.lyricsEdgeFade(list: LazyListState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        if (list.canScrollBackward) {
            val top = TvSpacing.l.toPx().coerceAtMost(size.height / 2)
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = top),
                size = Size(size.width, top), blendMode = BlendMode.DstIn,
            )
        }
        if (list.canScrollForward) {
            val bottom = TvSpacing.xl.toPx().coerceAtMost(size.height / 2)
            val start = size.height - bottom
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = start, endY = size.height),
                topLeft = Offset(0f, start), size = Size(size.width, bottom), blendMode = BlendMode.DstIn,
            )
        }
    }
