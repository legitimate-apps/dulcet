package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.res.stringResource
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
        if (first >= 0) list.animateScrollToItem((first - 1).coerceAtLeast(0))
    }
    val lines = current?.state == AndroidLyricsState.Lyrics
    Column(modifier.fillMaxWidth().fillMaxHeight().testTag("tv.player.lyrics.panel").semantics { this[LibraryObservation] = observation },
        verticalArrangement = if (lines) Arrangement.Top else Arrangement.Center) {
        if (current == null) {
            TvLoadingProgress("tv.player.lyrics.loading")
            return@Column
        }
        if (current.state == AndroidLyricsState.None) {
            TvEmptyState(
                title = stringResource(R.string.tv_lyrics_empty_title),
                hint = null,
                tag = "tv.player.lyrics.empty",
                topPadding = 0.dp,
                bottomPadding = 0.dp,
                icon = TvEmptyIcons.Lyrics,
            )
            return@Column
        }
        resources.lyricsStatement(current)?.let {
            Text(it, Modifier.testTag("tv.player.lyrics.statement"), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!lines) return@Column
        Spacer(Modifier.height(TvSpacing.xl))
        if (current.trimmed) {
            Text(resources.getString(SharedR.string.lyrics_trimmed), Modifier.testTag("tv.player.lyrics.trimmed"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(bottom = TvSpacing.s).testTag("tv.player.lyrics"),
            state = list,
        ) {
            itemsIndexed(current.lines) { index, line ->
                val lit = highlight?.lights(index) == true
                Text(line.text.ifBlank { " " },
                    Modifier.padding(bottom = TvSpacing.xs).testTag("tv.player.lyrics.line.$index").semantics { selected = lit },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Normal,
                    // The line being sung, in the phone's lit-line colour; unsung lines recede.
                    color = if (lit) MaterialTheme.colorScheme.onSurface
                        else if (!current.synced) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
