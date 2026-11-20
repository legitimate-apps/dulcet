package com.legitimateapps.dulcet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.highlightAt
import com.legitimateapps.dulcet.library.rememberMediaTime
import com.legitimateapps.dulcet.library.rememberNowPlayingLyrics
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.lyricsStatement
import com.legitimateapps.dulcet.library.offersRetry
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR

/*
 * The phone's lyrics (spec §18.4): an expanded sheet over the player's own artwork-tinted surface.
 * Lines are headline type; the lit line is the surface's content colour, the sung and unsung ones
 * dimmed; an instrumental gap has its own quiet divider. Synced lines follow media time through the core's
 * cursor, the lit line is kept in view, and a tap on a synced line seeks to it. Plain lyrics scroll
 * in the same large type with nothing lit. A track with no lyrics says so; one whose lyrics cannot
 * be read says why.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSheet(
    account: SearchAccount,
    session: LibrarySession,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController,
    dismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    PhoneModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag("player.lyrics.sheet")) {
        Column(Modifier.fillMaxSize()) {
            PlayerSheetHeader(account, state.artworkKey, state.title, state.artist)
            LyricsPanel(session, state, Modifier.weight(1f), playback::seek)
        }
    }
}

/** The current track's lyrics, lit as it plays. [seek] moves playback to a synced line's start. */
@Composable
internal fun LyricsPanel(session: LibrarySession, state: AndroidPlaybackState, modifier: Modifier = Modifier, seek: (Long) -> Unit) {
    val track = state.queue.getOrNull(state.currentIndex ?: -1)?.track
    val shown = rememberNowPlayingLyrics(session, state)
    val lyrics = shown.model
    val publication = shown.publication
    val observation by session.observation.collectAsState()
    val resources = libraryResources()
    val position = rememberMediaTime(state)
    val current = publication
    val highlight = current?.highlightAt(position)
    val list = rememberLazyListState()
    LaunchedEffect(highlight?.first) {
        val first = highlight?.first ?: return@LaunchedEffect
        // The lit line sits a little below the top, with what was just sung above it.
        if (first >= 0) list.animateScrollToItem((first + 1 - LINES_ABOVE).coerceAtLeast(0))
    }
    Column(modifier.fillMaxWidth().testTag("lyrics.panel").semantics { this[LibraryObservation] = observation }) {
        if (track == null) return@Column
        if (current == null) {
            Text("…", Modifier.padding(DulcetSpacing.xl).testTag("lyrics.loading"))
            return@Column
        }
        resources.lyricsStatement(current)?.let { statement ->
            Column(Modifier.fillMaxWidth().padding(horizontal = DulcetSpacing.xl, vertical = DulcetSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(PlayerLyricsIcon, null, Modifier.size(DulcetSizes.icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(DulcetSpacing.m))
                Text(statement, Modifier.testTag("lyrics.statement"), style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center)
                if (current.state == AndroidLyricsState.Unavailable && (current.breakerOpen || current.freshness.offersRetry())) {
                    TextButton(onClick = lyrics::retry, modifier = Modifier.testTag("lyrics.retry")) {
                        Text(resources.getString(SharedR.string.library_try_again))
                    }
                }
            }
        }
        if (current.state != AndroidLyricsState.Lyrics) return@Column
        if (current.trimmed) Text(resources.getString(SharedR.string.lyrics_trimmed), Modifier.padding(horizontal = DulcetSpacing.xl),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().testTag(if (current.synced) "lyrics.synced" else "lyrics.plain")) {
            item { Spacer(Modifier.height(DulcetSpacing.s)) }
            itemsIndexed(current.lines) { index, line ->
                val lit = highlight?.lights(index) == true
                val start = line.effectiveStartMilliseconds
                val gap = current.synced && line.text.isBlank()
                Text(if (gap) "♪ ♪ ♪" else line.text.ifBlank { " " },
                    Modifier.fillMaxWidth()
                        .clickable(enabled = current.synced && start != null && state.seekable) { start?.let(seek) }
                        .padding(horizontal = DulcetSpacing.xl, vertical = DulcetSpacing.s)
                        .testTag("lyrics.line.$index")
                        .semantics { selected = lit },
                    style = if (gap) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = if (lit && !gap) FontWeight.Bold else null,
                    textAlign = if (gap) TextAlign.Center else TextAlign.Start,
                    color = if (gap || current.synced && !lit) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface)
            }
            item { Spacer(Modifier.height(DulcetSizes.touchTarget)) }
        }
    }
}

/** Lines kept above the lit one when it is scrolled into view. */
private const val LINES_ABOVE = 2

/** Outline speech and text, paired with the queue's linework in the player controls. */
internal val PlayerLyricsIcon = ImageVector.Builder("LyricsOutline", DulcetSizes.icon, DulcetSizes.icon, 24f, 24f)
    .addPath(addPathNodes("M4 3h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H7l-5 4V5c0-1.1.9-2 2-2zm0 2v13.83L6.3 17H20V5H4zm2 2h12v2H6zm0 4h12v2H6zm0 4h8v1H6z"),
        fill = SolidColor(Color.Black))
    .build()
