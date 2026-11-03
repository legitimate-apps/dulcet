package com.legitimateapps.dulcet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.shared.R as SharedR

/*
 * The phone's lyrics (spec §18.4): an expanded sheet over the player's own artwork-tinted surface.
 * Lines are headline type; the lit line is the surface's content colour, the sung and unsung ones
 * dimmed; an instrumental gap is a ♪, not a hole. Synced lines follow media time through the core's
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
    tint: Color,
    dismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = tint,
        modifier = Modifier.testTag("player.lyrics.sheet")) {
        // The track the words belong to: the artwork shrunk to a header thumbnail.
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(account, state.artworkKey, state.title, 40.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(state.title.ifBlank { stringResource(R.string.now_playing_loading) },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                state.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LyricsPanel(session, state, playback::seek)
    }
}

/** The current track's lyrics, lit as it plays. [seek] moves playback to a synced line's start. */
@Composable
internal fun LyricsPanel(session: LibrarySession, state: AndroidPlaybackState, seek: (Long) -> Unit) {
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
    Column(Modifier.fillMaxWidth().testTag("lyrics.panel").semantics { this[LibraryObservation] = observation }) {
        if (track == null) return@Column
        if (current == null) {
            Text("…", Modifier.padding(24.dp).testTag("lyrics.loading"))
            return@Column
        }
        resources.lyricsStatement(current)?.let { statement ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(DulcetIcons.Lyrics, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
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
        if (current.trimmed) Text(resources.getString(SharedR.string.lyrics_trimmed), Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().testTag(if (current.synced) "lyrics.synced" else "lyrics.plain")) {
            item { Spacer(Modifier.height(8.dp)) }
            itemsIndexed(current.lines) { index, line ->
                val lit = highlight?.lights(index) == true
                val start = line.effectiveStartMilliseconds
                val gap = current.synced && line.text.isBlank()
                Text(if (gap) "♪" else line.text.ifBlank { " " },
                    Modifier.fillMaxWidth()
                        .clickable(enabled = current.synced && start != null && state.seekable) { start?.let(seek) }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("lyrics.line.$index")
                        .semantics { selected = lit }
                        .alpha(if (!current.synced || lit || gap) 1f else DIMMED),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = if (lit) FontWeight.Bold else null,
                    textAlign = if (gap) TextAlign.Center else TextAlign.Start,
                    color = when {
                        !current.synced -> MaterialTheme.colorScheme.onSurface
                        lit -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/** Lines kept above the lit one when it is scrolled into view. */
private const val LINES_ABOVE = 2

/** How strongly a synced line away from the music is dimmed. */
private const val DIMMED = 0.6f
