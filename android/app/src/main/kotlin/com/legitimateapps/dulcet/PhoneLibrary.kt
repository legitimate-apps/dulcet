package com.legitimateapps.dulcet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.library.LibraryAlbum
import com.legitimateapps.dulcet.library.LibraryArtist
import com.legitimateapps.dulcet.library.LibraryIndex
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySessionState
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.rememberArtwork

private enum class LibraryView { Everything, Albums, Artists }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryHome(
    account: SearchAccount,
    session: LibrarySession,
    state: LibrarySessionState,
    index: LibraryIndex,
    playingRawId: String?,
    actions: PhoneActions,
) {
    var view by rememberSaveable { mutableStateOf(LibraryView.Everything) }
    val rows = state.library?.rows.orEmpty()
    Column(Modifier.fillMaxSize().testTag("library.surface").semantics { this[LibraryObservation] = state }) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_library), fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = session::synchronize, enabled = !state.syncing,
                    modifier = Modifier.testTag("library.sync")) {
                    Icon(DulcetIcons.Refresh, stringResource(if (state.syncing) R.string.library_syncing else R.string.library_sync))
                }
            },
        )
        if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in LibraryView.entries) FilterChip(
                selected = view == option,
                onClick = { view = option },
                label = { Text(stringResource(when (option) {
                    LibraryView.Everything -> R.string.library_view_everything
                    LibraryView.Albums -> R.string.library_view_albums
                    LibraryView.Artists -> R.string.library_view_artists
                })) },
                modifier = Modifier.testTag("library.view.${option.name.lowercase()}"),
            )
        }
        if (state.failed) Text(stringResource(R.string.library_sync_failed), Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (state.library != null && rows.isEmpty()) {
            EmptyLibrary(state.syncing, session::synchronize)
            return@Column
        }
        when (view) {
            LibraryView.Everything -> EverythingList(account, rows, index, playingRawId, actions)
            LibraryView.Albums -> AlbumGrid(account, index.albums, actions, header = null)
            LibraryView.Artists -> LazyColumn(Modifier.fillMaxSize()) {
                items(index.artists, key = { it.item.id.rawId }) { artist ->
                    ArtistRow(artist.item, index.albumsBy(artist).size, Modifier) { actions.openArtist(artist.item.id.rawId) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(syncing: Boolean, sync: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(DulcetIcons.LibraryMusic, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.library_empty), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = sync, enabled = !syncing, modifier = Modifier.testTag("library.sync.empty")) {
            Text(stringResource(if (syncing) R.string.library_syncing else R.string.library_sync))
        }
    }
}

/**
 * Every committed row in the core read's own order — artists, then each album followed by its
 * tracks — so `library.row.N` is always row N of that read.
 */
@Composable
private fun EverythingList(
    account: SearchAccount,
    rows: List<SearchResultItem>,
    index: LibraryIndex,
    playingRawId: String?,
    actions: PhoneActions,
) {
    val placement = remember(rows, index) { trackPlacement(rows, index) }
    val firstAlbum = rows.indexOfFirst { it.type == SearchResultType.Album }
    val firstArtist = rows.indexOfFirst { it.type == SearchResultType.Artist }
    LazyColumn(Modifier.fillMaxSize().testTag("library.rows")) {
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex == firstArtist) item(key = "header-artists") { SectionHeader(stringResource(R.string.library_section_artists)) }
            if (rowIndex == firstAlbum) item(key = "header-albums") { SectionHeader(stringResource(R.string.library_section_albums)) }
            item(key = "row-$rowIndex") {
                val tag = Modifier.testTag("library.row.$rowIndex")
                when (row.type) {
                    SearchResultType.Artist -> {
                        val artist = index.artist(row.id.rawId)
                        ArtistRow(row, artist?.let { index.albumsBy(it).size }, tag) { actions.openArtist(row.id.rawId) }
                    }
                    SearchResultType.Album -> AlbumRow(account, row, tag, onPlay = {
                        index.album(row.id.rawId)?.let { actions.playAlbum(it, 0, false) }
                    }) { actions.openAlbum(row.id.rawId) }
                    SearchResultType.Track -> {
                        val (album, position) = placement[rowIndex] ?: (null to 0)
                        TrackRow(row, position + 1, row.id.rawId == playingRawId, tag) {
                            album?.let { actions.playAlbum(it, position, false) }
                        }
                    }
                }
            }
        }
    }
}

private fun trackPlacement(rows: List<SearchResultItem>, index: LibraryIndex): Map<Int, Pair<LibraryAlbum, Int>> {
    val placement = mutableMapOf<Int, Pair<LibraryAlbum, Int>>()
    var album: LibraryAlbum? = null
    var position = 0
    var albumOrdinal = -1
    rows.forEachIndexed { rowIndex, row ->
        when (row.type) {
            SearchResultType.Album -> { albumOrdinal++; album = index.albums.getOrNull(albumOrdinal); position = 0 }
            SearchResultType.Track -> album?.let { placement[rowIndex] = it to position++ }
            SearchResultType.Artist -> Unit
        }
    }
    return placement
}

@Composable
private fun SectionHeader(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ArtistRow(item: SearchResultItem, albumCount: Int?, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(if (albumCount != null && albumCount > 0) stringResource(R.string.library_album_count, albumCount)
                else stringResource(R.string.library_artist_kind))
        },
        leadingContent = { Monogram(item.title, 48.dp) },
        trailingContent = { Icon(DulcetIcons.ChevronRight, null) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun AlbumRow(account: SearchAccount, item: SearchResultItem, modifier: Modifier, onPlay: () -> Unit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(listOfNotNull(item.credits.joinToString { it.name }.ifBlank { null }, item.year?.toString())
                .joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Artwork(account, item.artworkKey, item.title, 56.dp) },
        trailingContent = {
            IconButton(onClick = onPlay) { Icon(DulcetIcons.Play, stringResource(R.string.action_play_track, item.title)) }
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun TrackRow(item: SearchResultItem, number: Int, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = accent) },
        supportingContent = item.credits.takeIf { it.isNotEmpty() }?.let { credits ->
            { Text(credits.joinToString { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing), tint = accent)
                else Text((item.trackNumber ?: number).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        trailingContent = item.duration?.let { duration ->
            { Text(formatDuration(duration.inWholeMilliseconds), style = MaterialTheme.typography.bodySmall) }
        },
        colors = ListItemDefaults.colors(),
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun AlbumGrid(
    account: SearchAccount,
    albums: List<LibraryAlbum>,
    actions: PhoneActions,
    header: (@Composable () -> Unit)?,
) {
    LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.fillMaxSize().testTag("library.albums"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(albums, key = { it.item.id.rawId }) { album ->
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { actions.openAlbum(album.item.id.rawId) }) {
                Artwork(account, album.item.artworkKey, album.item.title, null, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(8.dp))
                Text(album.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(album.artistLine, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumScreen(account: SearchAccount, album: LibraryAlbum?, playingRawId: String?, actions: PhoneActions) {
    Column(Modifier.fillMaxSize().testTag("album.surface")) {
        TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = actions.back) { Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back)) }
        })
        if (album == null) { Text(stringResource(R.string.album_not_found), Modifier.padding(24.dp)); return@Column }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(account, album.item.artworkKey, album.item.title, null,
                        Modifier.fillMaxWidth(0.72f).aspectRatio(1f).shadow(16.dp, RoundedCornerShape(16.dp)))
                    Spacer(Modifier.height(20.dp))
                    Text(album.item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.testTag("album.title"))
                    val artist = album.item.credits.firstOrNull()
                    Text(album.artistLine, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = artist?.id != null) {
                            artist?.id?.let { actions.openArtist(it.rawId) }
                        }.padding(4.dp))
                    Text(listOfNotNull(album.item.year?.toString(),
                        stringResource(R.string.album_song_count, album.tracks.size),
                        album.durationMilliseconds.takeIf { it > 0 }?.let { longDuration(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    PlayShuffleButtons(
                        onPlay = { actions.playAlbum(album, 0, false) },
                        onShuffle = { actions.playAlbum(album, 0, true) },
                        enabled = album.tracks.isNotEmpty(), tagPrefix = "album")
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(album.tracks.size, key = { album.tracks[it].id.rawId + "#" + it }) { position ->
                val track = album.tracks[position]
                TrackRow(track, position + 1, track.id.rawId == playingRawId, Modifier.testTag("album.track.$position")) {
                    actions.playAlbum(album, position, false)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistScreen(account: SearchAccount, index: LibraryIndex, artist: LibraryArtist?, actions: PhoneActions) {
    Column(Modifier.fillMaxSize().testTag("artist.surface")) {
        TopAppBar(title = { Text(artist?.item?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = actions.back) { Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back)) }
            })
        if (artist == null) { Text(stringResource(R.string.artist_not_found), Modifier.padding(24.dp)); return@Column }
        val albums = remember(index, artist) { index.albumsBy(artist) }
        AlbumGrid(account, albums, actions) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Monogram(artist.item.title, 120.dp)
                Spacer(Modifier.height(12.dp))
                Text(artist.item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Text(stringResource(R.string.library_album_count, albums.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                PlayShuffleButtons(onPlay = { actions.playArtist(artist, false) }, onShuffle = { actions.playArtist(artist, true) },
                    enabled = albums.any { it.tracks.isNotEmpty() }, tagPrefix = "artist")
            }
        }
    }
}

@Composable
private fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean, tagPrefix: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay, enabled = enabled, modifier = Modifier.width(148.dp).testTag("$tagPrefix.play")) {
            Icon(DulcetIcons.Play, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_play))
        }
        FilledTonalButton(onClick = onShuffle, enabled = enabled, modifier = Modifier.width(148.dp).testTag("$tagPrefix.shuffle")) {
            Icon(DulcetIcons.Shuffle, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_shuffle))
        }
    }
}

/** Cover art from the core repository, with a tonal placeholder while loading or when there is none. */
@Composable
internal fun Artwork(account: SearchAccount, key: String?, title: String, size: Dp?, modifier: Modifier = Modifier) {
    val pixels = size?.let { (it.value * 3).toInt() } ?: 512
    val image = rememberArtwork(account, key, pixels)
    val shape = RoundedCornerShape(if (size != null && size < 80.dp) 8.dp else 12.dp)
    val sized = if (size != null) modifier.size(size) else modifier
    Box(sized.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, stringResource(R.string.artwork_description, title), Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        else Icon(DulcetIcons.Album, null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Monogram(name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        val initial = name.trim().firstOrNull()?.uppercase()
        if (initial != null) Text(initial, color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = if (size > 64.dp) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium)
        else Icon(DulcetIcons.Person, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

internal fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun longDuration(milliseconds: Long): String {
    val minutes = (milliseconds / 60_000).coerceAtLeast(1).toInt()
    return if (minutes < 60) stringResource(R.string.duration_minutes, minutes)
    else stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}
