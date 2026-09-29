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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.highlightAt
import com.legitimateapps.dulcet.library.rememberMediaTime
import com.legitimateapps.dulcet.library.rememberNowPlayingLyrics
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.lyricsStatement
import com.legitimateapps.dulcet.library.offersRetry
import com.legitimateapps.dulcet.shared.R as SharedR

/*
 * The phone's lyrics (spec §18.4), a sheet over Now Playing. Synced lines follow media time through
 * the core's cursor, the lit line is kept in view, and a tap on a synced line seeks to it. Plain lyrics
 * scroll. A track with no lyrics says so; one whose lyrics cannot be read says why.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSheet(session: LibrarySession, state: AndroidPlaybackState, playback: AndroidPlaybackController, dismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, modifier = Modifier.testTag("player.lyrics.sheet")) {
        Text(stringResource(SharedR.string.lyrics_title), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        resources.freshnessLine(current.freshness)?.let {
            Text(it, Modifier.padding(horizontal = 24.dp).testTag("lyrics.freshness"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        resources.lyricsStatement(current)?.let { statement ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(statement, Modifier.weight(1f).testTag("lyrics.statement"), style = MaterialTheme.typography.bodyLarge)
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
                Text(line.text.ifBlank { " " },
                    Modifier.fillMaxWidth()
                        .clickable(enabled = current.synced && start != null && state.seekable) { start?.let(seek) }
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .testTag("lyrics.line.$index")
                        .semantics { selected = lit },
                    style = if (current.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (lit) FontWeight.Bold else null,
                    color = when {
                        !current.synced -> MaterialTheme.colorScheme.onSurface
                        lit -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/** Lines kept above the lit one when it is scrolled into view. */
private const val LINES_ABOVE = 2
