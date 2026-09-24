package com.legitimateapps.dulcet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibraryCoverage
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.library.LibraryHomeRowSurface
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.LibrarySurface
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.coverageLine
import com.legitimateapps.dulcet.library.discardedChangesLine
import com.legitimateapps.dulcet.library.displayTitle
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.noEpochLine
import com.legitimateapps.dulcet.library.offersRetry
import com.legitimateapps.dulcet.library.orderLine
import com.legitimateapps.dulcet.library.outcomeLine
import com.legitimateapps.dulcet.library.rememberHomeRows
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.library.titleResource
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.rememberArtwork

/*
 * The phone's library over the reader (spec §16.14). Every screen opens the windows it shows while it
 * is composed and closes them when it goes, so the reader's "visible screen" is what is on screen.
 * Freshness, coverage, playability and a pending favourite arrive in the core's publications; this
 * file draws them with the shared words in `LibraryCopy.kt`, the same words the TV app uses.
 */

private enum class LibraryView { Home, Albums, Artists }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryHome(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    var view by rememberSaveable { mutableStateOf(LibraryView.Home) }
    val observation by session.observation.collectAsState()
    Column(Modifier.fillMaxSize().testTag("library.surface").semantics { this[LibraryObservation] = observation }) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_library), fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = session::refresh, modifier = Modifier.testTag("library.refresh")) {
                    Icon(DulcetIcons.Refresh, stringResource(R.string.library_refresh))
                }
            },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in LibraryView.entries) FilterChip(
                selected = view == option,
                onClick = { view = option },
                label = { Text(stringResource(when (option) {
                    LibraryView.Home -> R.string.library_view_home
                    LibraryView.Albums -> R.string.library_view_albums
                    LibraryView.Artists -> R.string.library_view_artists
                })) },
                modifier = Modifier.testTag("library.view.${option.name.lowercase()}"),
            )
        }
        ConnectionNotices(session, accountNotices = true)
        when (view) {
            LibraryView.Home -> HomeRows(account, session, actions)
            LibraryView.Albums -> AlbumsGrid(account, session, actions)
            LibraryView.Artists -> ArtistsList(session, actions)
        }
    }
}

// ---- Home -----------------------------------------------------------------------------------------------

/** N independent rows (§16.9): each paints, fails and recovers on its own, with its own freshness. */
@Composable
private fun HomeRows(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    val rows = rememberHomeRows(session)
    LazyColumn(Modifier.fillMaxSize().testTag("library.home"), contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(rows) { index, row -> HomeRow(account, index, row, session, actions) }
    }
}

@Composable
private fun HomeRow(account: SearchAccount, index: Int, row: LibraryHomeRowSurface, session: LibrarySession, actions: PhoneActions) {
    val publication by row.surface.state.collectAsState()
    val resources = libraryResources()
    Column(Modifier.fillMaxWidth().testTag("library.home.$index").padding(top = 20.dp)) {
        Text(resources.getString(row.row.titleResource()), Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val current = publication ?: return@Column
        FreshnessLine(current.freshness, "library.home.$index", session::retry)
        when (val freshness = current.freshness) {
            AndroidLibraryFreshness.Loading -> RowPlaceholder(Modifier.testTag("library.home.$index.loading"))
            is AndroidLibraryFreshness.Unavailable -> StatementText(
                resources.unavailableLine(freshness.reason, LibrarySubject.List), "library.home.$index.unavailable")
            else -> if (current.items.isEmpty()) {
                StatementText(resources.getString(SharedR.string.library_empty_list), "library.home.$index.empty")
            } else {
                LazyRow(Modifier.testTag("library.home.$index.items"),
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(current.items, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                        AlbumCard(account, item, Modifier.width(140.dp).testTag("library.home.$index.item.$position")) {
                            if (item is AndroidLibraryItem.Album) actions.openAlbum(item.rawId)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowPlaceholder(modifier: Modifier) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Box(Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        }
    }
}

// ---- Albums and artists ---------------------------------------------------------------------------------

/** Every album, `alphabeticalByName`, windowed (§16.12): the viewport is reported, pages extend it. */
@Composable
private fun AlbumsGrid(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    val surface = rememberSurface(session, "albums") { openAlbums() }
    val publication by surface.state.collectAsState()
    val grid = rememberLazyGridState()
    val current = publication
    ReportViewport(surface, grid, current)
    LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.fillMaxSize().testTag("library.albums"), state = grid,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (current != null) item(span = { GridItemSpan(maxLineSpan) }) {
            ListStatus(current, "library.albums", session::retry)
        }
        itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
            AlbumCard(account, item, Modifier.testTag("library.albums.item.$position")) { actions.openAlbum(item.rawId) }
        }
    }
}

/**
 * Tells the window what is on screen — which the reader rebases and looks ahead around — and asks
 * for the next page near the end. Indexes are into the publication drawn; the facade carries them
 * to newer ones by identity. The reader decides whether a page is read at all (never offline).
 */
@Composable
private fun ReportViewport(surface: LibrarySurface, grid: LazyGridState, publication: AndroidLibraryPublication?) {
    val latest by rememberUpdatedState(publication)
    LaunchedEffect(surface, grid) {
        snapshotFlow {
            // Album cells are keyed by their id; the status line above them is not.
            val visible = grid.layoutInfo.visibleItemsInfo.filter { it.key is String }
            visible.firstOrNull()?.index to visible.lastOrNull()?.index
        }.collect { (first, last) ->
            val shown = latest ?: return@collect
            if (first == null || last == null) return@collect
            // Grid index 0 is the status line; items start at 1.
            val from = (first - 1).coerceAtLeast(0)
            val to = (last - 1).coerceAtLeast(from)
            surface.setViewport(from, to)
            if (shown.coverage == AndroidLibraryCoverage.Open && to >= shown.items.size - PAGE_AHEAD) surface.loadMore()
            if (shown.leadingOffset > 0 && from == 0) surface.loadBefore()
        }
    }
}

private const val PAGE_AHEAD = 12

@Composable
private fun ArtistsList(session: LibrarySession, actions: PhoneActions) {
    val surface = rememberSurface(session, "artists") { openArtists() }
    val publication by surface.state.collectAsState()
    val current = publication
    LazyColumn(Modifier.fillMaxSize().testTag("library.artists")) {
        if (current != null) item { ListStatus(current, "library.artists", session::retry) }
        itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
            if (item is AndroidLibraryItem.Artist) ArtistRow(item, Modifier.testTag("library.artists.item.$position")) {
                actions.openArtist(item.rawId)
            }
        }
    }
}

/**
 * A list's freshness, coverage and order lines, or the statement shown instead of it. A detail's
 * list (an artist's albums) can be loading or unavailable under a cached header: that is said, never
 * "Nothing here yet".
 */
@Composable
private fun ListStatus(publication: AndroidLibraryPublication, tag: String, retry: () -> Unit) {
    val resources = libraryResources()
    Column {
        FreshnessLine(publication.freshness, tag, retry)
        resources.coverageLine(publication)?.let { StatementText(it, "$tag.coverage") }
        resources.orderLine(publication)?.let { StatementText(it, "$tag.order") }
        val freshness = publication.freshness
        when {
            freshness is AndroidLibraryFreshness.Unavailable ->
                StatementText(resources.unavailableLine(freshness.reason, LibrarySubject.List), "$tag.unavailable")
            freshness == AndroidLibraryFreshness.Loading || publication.itemsState == AndroidLibraryItemsState.Loading ->
                StatementText("…", "$tag.loading")
            publication.itemsState == AndroidLibraryItemsState.Unavailable -> StatementText(resources.unavailableLine(
                publication.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List,
            ), "$tag.unavailable")
            publication.items.isEmpty() -> StatementText(resources.getString(SharedR.string.library_empty_list), "$tag.empty")
        }
    }
}

/**
 * What is true of the connection rather than of one screen: why the last reconnect failed, with
 * "Try again"; and, on the library's first screen only ([accountNotices]), the two account-level
 * statements that are made once rather than on every list (§16.12, §16.10).
 */
@Composable
private fun ConnectionNotices(session: LibrarySession, accountNotices: Boolean) {
    val connection by session.connection.collectAsState()
    val discarded by session.discardedChanges.collectAsState()
    val resources = libraryResources()
    resources.connectionLine(connection)?.let { line ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, Modifier.weight(1f).testTag("library.connection"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = session::retry, modifier = Modifier.testTag("library.connection.retry")) {
                Text(resources.getString(SharedR.string.library_try_again))
            }
        }
    }
    if (!accountNotices) return
    resources.noEpochLine(connection)?.let { StatementText(it, "library.noEpoch") }
    resources.discardedChangesLine(discarded)?.let { line ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, Modifier.weight(1f).testTag("library.discarded"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = session::dismissDiscardedChanges) {
                Text(resources.getString(SharedR.string.library_dismiss))
            }
        }
    }
}

// ---- Album --------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumScreen(
    account: SearchAccount,
    session: LibrarySession,
    rawId: String,
    playingRawId: String?,
    actions: PhoneActions,
) {
    val surface = rememberSurface(session, "album:$rawId") { openAlbum(rawId) }
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val outcomes by session.outcomes.collectAsState()
    // Only an outcome about this album is said here, and it goes when the screen does.
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Album, rawId)
    val outcome = outcomes[target]
    DisposableEffect(session, target) { onDispose { session.dismissOutcome(target) } }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    val resources = libraryResources()
    LaunchedEffect(publication) { publication?.let(actions.rememberAlbum) }
    Column(Modifier.fillMaxSize().testTag("album.surface").semantics { this[LibraryObservation] = observation }) {
        TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = actions.back, modifier = Modifier.testTag("album.back")) {
                Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back))
            }
        }, actions = {
            IconButton(onClick = session::refresh, modifier = Modifier.testTag("album.refresh")) {
                Icon(DulcetIcons.Refresh, stringResource(R.string.library_refresh))
            }
            val album = publication?.header as? AndroidLibraryItem.Album
            if (album != null) FavouriteButton(album.favourite == true) {
                session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId))
            }
        })
        ConnectionNotices(session, accountNotices = false)
        val current = publication ?: return@Column
        val album = current.header as? AndroidLibraryItem.Album
        val tracks = current.items.filterIsInstance<AndroidLibraryItem.Track>()
        val anyPlayable = current.itemsState == AndroidLibraryItemsState.Present &&
            tracks.any { it.playability != AndroidLibraryPlayability.UnavailableOffline }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FreshnessLine(current.freshness, "album", session::retry)
                    resources.outcomeLine(outcome)?.let { StatementText(it, "album.outcome") }
                    note?.let { StatementText(it, "album.note") }
                    (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                        StatementText(resources.unavailableLine(unavailable.reason, LibrarySubject.Album), "album.unavailable")
                    }
                    if (album != null) {
                        Artwork(account, album.artworkKey, album.title, null,
                            Modifier.fillMaxWidth(0.72f).aspectRatio(1f).shadow(16.dp, RoundedCornerShape(16.dp)))
                        Spacer(Modifier.height(20.dp))
                        Text(album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("album.title"))
                        album.artistName?.let { artist ->
                            Text(artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = album.artistRawId != null) {
                                    album.artistRawId?.let(actions.openArtist)
                                }.padding(4.dp))
                        }
                        val songs = album.songCount ?: tracks.size.takeIf { current.itemsState == AndroidLibraryItemsState.Present }
                        Text(listOfNotNull(album.year?.toString(),
                            songs?.let { pluralStringResource(R.plurals.album_song_count, it, it) },
                            album.durationMilliseconds?.takeIf { it > 0 }?.let { longDuration(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        PlayShuffleButtons(
                            onPlay = { actions.playAlbum(current, 0, false) },
                            onShuffle = { actions.playAlbum(current, 0, true) },
                            enabled = anyPlayable, tagPrefix = "album")
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            when (current.itemsState) {
                AndroidLibraryItemsState.Loading -> item { RowPlaceholder(Modifier.testTag("album.tracks.loading")) }
                // The header is known and the track list cannot be shown: the core says why (§16.14).
                AndroidLibraryItemsState.Unavailable -> if (album != null) item {
                    StatementText(resources.unavailableLine(
                        current.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure,
                        LibrarySubject.Album,
                    ), "album.tracks.unavailable")
                }
                AndroidLibraryItemsState.Present -> itemsIndexed(current.items, key = { position, item -> item.rawId + "#" + position }) { position, item ->
                    if (item is AndroidLibraryItem.Track) {
                        TrackRow(item, position + 1, item.rawId == playingRawId, Modifier.testTag("album.track.$position"),
                            onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) }) {
                            note = null
                            actions.playAlbum(current, position, false)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FavouriteButton(favourite: Boolean, onClick: () -> Unit) {
    val resources = libraryResources()
    IconButton(onClick = onClick, modifier = Modifier.testTag("album.favourite")) {
        Icon(if (favourite) DulcetIcons.Star else DulcetIcons.StarBorder,
            resources.getString(if (favourite) SharedR.string.library_favourite_remove else SharedR.string.library_favourite_add),
            tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Artist -------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistScreen(account: SearchAccount, session: LibrarySession, rawId: String, actions: PhoneActions) {
    val surface = rememberSurface(session, "artist:$rawId") { openArtist(rawId) }
    val publication by surface.state.collectAsState()
    val current = publication
    val artist = current?.header as? AndroidLibraryItem.Artist
    val resources = libraryResources()
    // Playing an artist opens each album first; leaving the screen abandons that, so nothing starts
    // playing after the person has gone, and a second tap while it runs does nothing.
    var collecting by remember(rawId) { mutableStateOf<AutoCloseable?>(null) }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    DisposableEffect(rawId) { onDispose { collecting?.close() } }
    fun play(publication: AndroidLibraryPublication, shuffle: Boolean) {
        if (collecting != null) return
        note = null
        var finished = false
        val handle = actions.playArtist(publication, shuffle) { result ->
            finished = true
            collecting = null
            note = when (result) {
                ArtistPlayResult.Played -> null
                // Some album holds tracks this device could play once it reconnects.
                ArtistPlayResult.NeedsConnection -> resources.getString(SharedR.string.library_plays_on_reconnect)
                ArtistPlayResult.NothingPlayable -> resources.getString(SharedR.string.library_artist_nothing_playable)
            }
        }
        if (!finished) collecting = handle
    }
    Column(Modifier.fillMaxSize().testTag("artist.surface")) {
        TopAppBar(title = { Text(artist?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = actions.back) { Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back)) }
            })
        ConnectionNotices(session, accountNotices = false)
        if (current == null) return@Column
        val albums = current.items.filterIsInstance<AndroidLibraryItem.Album>()
        val listed = current.itemsState == AndroidLibraryItemsState.Present
        LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.fillMaxSize().testTag("artist.albums"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ListStatus(current, "artist", session::retry)
                    if (artist != null) {
                        Monogram(artist.name, 120.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(artist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center)
                        if (listed) Text(pluralStringResource(R.plurals.library_album_count, albums.size, albums.size),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        // Whether anything can play is the tracks' playability, known once each album is
                        // open; if nothing can, the tap says so.
                        PlayShuffleButtons(onPlay = { play(current, false) }, onShuffle = { play(current, true) },
                            enabled = listed && albums.isNotEmpty() && collecting == null, tagPrefix = "artist")
                        note?.let { StatementText(it, "artist.note") }
                    }
                }
            }
            itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
                AlbumCard(account, album, Modifier.testTag("artist.album.$position")) { actions.openAlbum(album.rawId) }
            }
        }
    }
}

// ---- Shared pieces --------------------------------------------------------------------------------------

/** The one freshness line (§16.14), with "Try again" where a reconnect can help. Null for live content. */
@Composable
private fun FreshnessLine(freshness: AndroidLibraryFreshness, tag: String, retry: () -> Unit) {
    val resources = libraryResources()
    val line = resources.freshnessLine(freshness) ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line, Modifier.weight(1f).testTag("$tag.freshness"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (freshness.offersRetry()) TextButton(onClick = retry, modifier = Modifier.testTag("$tag.retry")) {
            Text(resources.getString(SharedR.string.library_try_again))
        }
    }
}

@Composable
private fun StatementText(text: String, tag: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AlbumCard(account: SearchAccount, item: AndroidLibraryItem, modifier: Modifier, onClick: () -> Unit) {
    val title = item.displayTitle()
    val artwork = (item as? AndroidLibraryItem.Album)?.artworkKey
    val subtitle = (item as? AndroidLibraryItem.Album)?.artistName
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Artwork(account, artwork, title, null, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistRow(item: AndroidLibraryItem.Artist, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val count = item.albumCount
            Text(if (count != null && count > 0) pluralStringResource(R.plurals.library_album_count, count, count)
                else stringResource(R.string.library_artist_kind))
        },
        leadingContent = { Monogram(item.name, 48.dp) },
        trailingContent = { Icon(DulcetIcons.ChevronRight, null) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * One track. A track this device cannot play offline says so in the row, and a tap on it says why it
 * will not play ([onUnavailable]) instead of doing nothing (§16.14) — the statement comes from the
 * core's playability, never from a guess here.
 */
@Composable
internal fun TrackRow(
    track: AndroidLibraryItem.Track,
    number: Int,
    playing: Boolean,
    modifier: Modifier,
    onUnavailable: () -> Unit,
    onClick: () -> Unit,
) {
    val unavailable = track.playability == AndroidLibraryPlayability.UnavailableOffline
    val accent = when {
        playing -> MaterialTheme.colorScheme.primary
        unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val resources = libraryResources()
    val supporting = if (unavailable) resources.getString(SharedR.string.library_not_available_offline) else track.artistName
    ListItem(
        headlineContent = {
            Text(track.title ?: stringResource(R.string.unknown_title), maxLines = 1, overflow = TextOverflow.Ellipsis, color = accent)
        },
        supportingContent = supporting?.let { text -> { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing), tint = accent)
                else Text((track.trackNumber ?: number).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        trailingContent = track.durationMilliseconds?.let { duration ->
            { Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall) }
        },
        modifier = modifier.clickable { if (unavailable) onUnavailable() else onClick() },
    )
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
