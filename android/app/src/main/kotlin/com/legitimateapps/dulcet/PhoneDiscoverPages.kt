package com.legitimateapps.dulcet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.DiscoverAlbum
import com.legitimateapps.dulcet.core.DiscoverArtist
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.DiscoverOperation
import com.legitimateapps.dulcet.core.DiscoverPage
import com.legitimateapps.dulcet.core.DiscoverResult
import com.legitimateapps.dulcet.core.WishFilter
import com.legitimateapps.dulcet.core.WishSource
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.ui.DulcetIcons

/** A metadata operation belongs to one page and selection, independent of durable wish mutations. */
private class DiscoverPageLifetime {
    var active = true
    private val operations = mutableListOf<DiscoverOperation>()
    fun own(operation: DiscoverOperation) { if (active) operations += operation else operation.cancel() }
    fun close() { active = false; operations.forEach(DiscoverOperation::cancel); operations.clear() }
}

@Composable
internal fun PhoneDiscoverArtist(
    session: DiscoverWishlistSession,
    mbid: String,
    openLibrary: (DiscoverLibraryRef) -> Unit,
    openAlbum: (String) -> Unit,
    back: () -> Unit,
) {
    LaunchedEffect(session) { session.refreshWishlist(WishFilter()) }
    var artist by remember(session, mbid) { mutableStateOf<DiscoverArtist?>(null) }
    var error by remember(session, mbid) { mutableStateOf<DiscoverFailure?>(null) }
    var actionError by remember(session, mbid) { mutableStateOf<DiscoverFailure?>(null) }
    var loading by remember(session, mbid) { mutableStateOf(true) }
    var loadingMore by remember(session, mbid) { mutableStateOf(false) }
    var refresh by remember(session, mbid) { mutableStateOf(0) }
    var more by remember(session, mbid) { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(session, mbid, refresh) {
        val lifetime = DiscoverPageLifetime()
        loading = true; error = null; loadingMore = false
        lifetime.own(session.artist(mbid) { result -> if (lifetime.active) {
            loading = false
            when (result) {
                is DiscoverResult.Value -> artist = result.value
                is DiscoverResult.Failed -> error = result.error
            }
        } })
        more = load@{
            val cursor = artist?.albums?.nextCursor ?: return@load
            if (loading || loadingMore) return@load
            loadingMore = true; error = null
            lifetime.own(session.artistAlbums(mbid, cursor) { result -> if (lifetime.active) {
                loadingMore = false
                when (result) {
                    is DiscoverResult.Value -> artist = artist?.let { it.copy(albums = appendPage(it.albums, result.value)) }
                    is DiscoverResult.Failed -> error = result.error
                }
            } })
        }
        onDispose { lifetime.close(); more = null }
    }
    Column(Modifier.fillMaxSize().testTag("discover.artist.surface")) {
        DiscoverMetadataBar(artist?.item?.title ?: stringResource(R.string.discover_pages_artist), back) {
            artist?.item?.let { DiscoverRejectMenu(session, it) { actionError = it } }
        }
        LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.weight(1f).fillMaxWidth().testTag("discover.artist.list"),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("credit", span = { GridItemSpan(maxLineSpan) }) { DiscoverCredit() }
            artist?.item?.let { header -> item("artist", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    DiscoverDetailHeader(
                        session = session,
                        item = header,
                        source = WishSource("artist", mbid),
                        headerTag = "discover.artist.header",
                        metadata = pluralStringResource(R.plurals.library_album_count, artist?.albums?.items?.size ?: 0,
                            artist?.albums?.items?.size ?: 0),
                        artwork = { Monogram(header.title, 120.dp) },
                        openLibrary = openLibrary,
                    )
                    DiscoverLinks(header.links)
                }
            } }
            item("notices", span = { GridItemSpan(maxLineSpan) }) {
                DiscoverPageNotices(artist?.albums, error ?: actionError) { refresh++ }
            }
            if (loading) item("loading", span = { GridItemSpan(maxLineSpan) }) {
                CircularProgressIndicator(Modifier.testTag("discover.artist.loading"))
            }
            item("albums-title", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.discover_pages_albums), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            }
            itemsIndexed(artist?.albums?.items.orEmpty(), key = { index, item -> "album:$index:${item.id}" }) { index, item ->
                DiscoverAlbumCard(session, item, index, WishSource("artist", mbid), openLibrary, openAlbum)
            }
            if (artist?.albums?.nextCursor != null) item("more", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { more?.invoke() }, enabled = !loading && !loadingMore,
                    modifier = Modifier.testTag("discover.artist.more")) {
                    Text(stringResource(if (loadingMore) R.string.discover_pages_loading else R.string.discover_pages_more_albums))
                }
            }
        }
    }
}

@Composable
internal fun PhoneDiscoverAlbum(
    session: DiscoverWishlistSession,
    mbid: String,
    openLibrary: (DiscoverLibraryRef) -> Unit,
    back: () -> Unit,
) {
    LaunchedEffect(session) { session.refreshWishlist(WishFilter()) }
    var album by remember(session, mbid) { mutableStateOf<DiscoverAlbum?>(null) }
    var requestedRelease by remember(session, mbid) { mutableStateOf<String?>(null) }
    var error by remember(session, mbid) { mutableStateOf<DiscoverFailure?>(null) }
    var actionError by remember(session, mbid) { mutableStateOf<DiscoverFailure?>(null) }
    var loading by remember(session, mbid) { mutableStateOf(true) }
    var loadingMore by remember(session, mbid) { mutableStateOf(false) }
    var refresh by remember(session, mbid) { mutableStateOf(0) }
    var more by remember(session, mbid) { mutableStateOf<(() -> Unit)?>(null) }
    // Keep the header and editions while switching; tracks belong only to the selected release.
    val selectedRelease = requestedRelease ?: album?.selectedReleaseId
    val selectedAlbum = album?.takeIf { it.selectedReleaseId == selectedRelease }
    DisposableEffect(session, mbid, requestedRelease, refresh) {
        val lifetime = DiscoverPageLifetime()
        loading = true; error = null; loadingMore = false
        lifetime.own(session.album(mbid, requestedRelease) { result -> if (lifetime.active) {
            loading = false
            when (result) {
                is DiscoverResult.Value -> album = result.value
                is DiscoverResult.Failed -> error = result.error
            }
        } })
        more = load@{
            val current = album ?: return@load
            val release = current.selectedReleaseId ?: return@load
            if (requestedRelease != null && release != requestedRelease) return@load
            val cursor = current.tracks.nextCursor ?: return@load
            if (loading || loadingMore) return@load
            loadingMore = true; error = null
            lifetime.own(session.albumTracks(mbid, release, cursor) { result -> if (lifetime.active) {
                loadingMore = false
                when (result) {
                    is DiscoverResult.Value -> album = album?.takeIf { it.selectedReleaseId == release }
                        ?.let { it.copy(tracks = appendPage(it.tracks, result.value)) }
                    is DiscoverResult.Failed -> error = result.error
                }
            } })
        }
        onDispose { lifetime.close(); more = null }
    }
    Column(Modifier.fillMaxSize().testTag("discover.album.surface")) {
        DiscoverMetadataBar(album?.item?.title ?: stringResource(R.string.discover_pages_album), back) {
            album?.item?.let { DiscoverRejectMenu(session, it) { actionError = it } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover.album.list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("credit") { DiscoverCredit() }
            album?.item?.let { header -> item("album") {
                Column {
                    val selectedEdition = album?.editions?.firstOrNull { it.releaseId == selectedRelease }
                    DiscoverDetailHeader(
                        session = session,
                        item = header,
                        source = WishSource("album", mbid),
                        headerTag = "discover.album.header",
                        metadata = listOfNotNull(header.releaseDate ?: header.year?.toString(),
                            selectedEdition?.trackCount?.let { pluralStringResource(R.plurals.discover_pages_track_count, it, it) })
                            .joinToString(" · "),
                        artwork = {
                            DiscoverArtwork(session, header.artworkId, header.title, null,
                                Modifier.fillMaxWidth(0.72f).aspectRatio(1f).shadow(16.dp, RoundedCornerShape(16.dp)))
                        },
                        openLibrary = openLibrary,
                    )
                    DiscoverLinks(header.links)
                }
            } }
            if (!album?.editions.isNullOrEmpty()) item("editions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.discover_pages_edition), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        album?.editions.orEmpty().forEachIndexed { index, edition ->
                            FilterChip(selected = edition.releaseId == selectedRelease,
                                onClick = { if (edition.releaseId != selectedRelease) requestedRelease = edition.releaseId },
                                modifier = Modifier.testTag("discover.album.edition.$index"),
                                label = { Column {
                                    Text(stringResource(R.string.discover_pages_edition_number, index + 1))
                                    edition.releaseDate?.let { Text(it) }
                                    edition.trackCount?.let { Text(pluralStringResource(R.plurals.discover_pages_track_count, it, it)) }
                                } })
                        }
                    }
                }
            }
            item("notices") { DiscoverPageNotices(selectedAlbum?.tracks, error ?: actionError) { refresh++ } }
            if (loading) item("loading") { CircularProgressIndicator(Modifier.testTag("discover.album.loading")) }
            item("tracks-title") { Text(stringResource(R.string.discover_pages_tracks), style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
            itemsIndexed(selectedAlbum?.tracks?.items.orEmpty(), key = { index, item -> "track:$index:${item.id}" }) { index, item ->
                DiscoverTrackRow(session, item, index, WishSource("album", mbid), openLibrary)
            }
            if (selectedAlbum?.tracks?.nextCursor != null) item("more") {
                TextButton(onClick = { more?.invoke() }, enabled = !loading && !loadingMore,
                    modifier = Modifier.testTag("discover.album.more")) {
                    Text(stringResource(if (loadingMore) R.string.discover_pages_loading else R.string.discover_pages_more_tracks))
                }
            }
        }
    }
}

/** The cursor can overlap its predecessor; preserve order and distinct edition slots. */
private fun appendPage(previous: DiscoverPage, next: DiscoverPage): DiscoverPage = next.copy(
    items = (previous.items + next.items).distinctBy { Triple(it.id, it.disc, it.position) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverMetadataBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = back, modifier = Modifier.testTag("discover.back")) {
            Icon(DulcetIcons.ArrowBack, contentDescription = stringResource(R.string.discover_pages_back))
        }
    }, actions = actions)
}
