package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.highlightAt
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.lyricsStatement
import com.legitimateapps.dulcet.library.rememberMediaTime
import com.legitimateapps.dulcet.library.rememberNowPlayingLyrics
import com.legitimateapps.dulcet.shared.R as SharedR

/**
 * The TV's lyrics panel (spec §18.4), in place of Up Next when the person turns it on. Read at a
 * distance, so the lines are large and not focusable: the lit line follows media time through the
 * core's cursor and is kept in view; plain lyrics show from the top. A track with no lyrics says so.
 */
@Composable
internal fun TvLyricsPanel(session: LibrarySession, state: AndroidPlaybackState) {
    val shown = rememberNowPlayingLyrics(session, state)
    val current = shown.publication
    val observation by session.observation.collectAsState()
    val resources = libraryResources()
    val highlight = current?.highlightAt(rememberMediaTime(state))
    val list = rememberLazyListState()
    LaunchedEffect(highlight?.first) {
        val first = highlight?.first ?: return@LaunchedEffect
        if (first >= 0) list.animateScrollToItem((first - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxWidth().testTag("tv.player.lyrics.panel").semantics { this[LibraryObservation] = observation }) {
        Text(resources.getString(SharedR.string.lyrics_title), style = MaterialTheme.typography.titleMedium)
        if (current == null) {
            Text("…", Modifier.testTag("tv.player.lyrics.loading"))
            return@Column
        }
        resources.freshnessLine(current.freshness)?.let {
            Text(it, Modifier.testTag("tv.player.lyrics.freshness"), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        resources.lyricsStatement(current)?.let {
            Text(it, Modifier.padding(top = 8.dp).testTag("tv.player.lyrics.statement"), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current.state != AndroidLyricsState.Lyrics) return@Column
        if (current.trimmed) {
            Text(resources.getString(SharedR.string.lyrics_trimmed), Modifier.testTag("tv.player.lyrics.trimmed"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth().height(200.dp).testTag("tv.player.lyrics"), state = list) {
            itemsIndexed(current.lines) { index, line ->
                val lit = highlight?.lights(index) == true
                Text(line.text.ifBlank { " " },
                    Modifier.padding(vertical = 4.dp).testTag("tv.player.lyrics.line.$index").semantics { selected = lit },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (lit) FontWeight.Bold else null,
                    // The line being sung, in the phone's lit-line colour; unsung lines recede.
                    color = if (lit) MaterialTheme.colorScheme.primary
                        else if (!current.synced) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
