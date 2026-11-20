package com.legitimateapps.dulcet

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.legitimateapps.dulcet.ui.ArtworkEntity

/** A metadata operation belongs to one page and selection, independent of durable wish mutations. */
private class DiscoverPageLifetime {
    var active = true
    private val operations = mutableListOf<DiscoverOperation>()
    fun own(operation: DiscoverOperation) { if (active) operations += operation else operation.cancel() }
    fun close() { active = false; operations.forEach(DiscoverOperation::cancel); operations.clear() }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scrollBehavior = rememberPositionTintedBar { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0 }
    Column(Modifier.fillMaxSize().testTag("discover.artist.surface")) {
        // The hero names the artist at rest; the bar takes the name over once the hero has left.
        DiscoverMetadataBar(artist?.item?.title?.let { if (grid.firstVisibleItemIndex > 0) it else "" }
            ?: stringResource(R.string.discover_pages_artist), back, scrollBehavior) {
            artist?.item?.let { DiscoverRejectMenu(session, it) { actionError = it } }
        }
        LazyVerticalGrid(GridCells.Adaptive(DulcetSizes.gridMinCell), Modifier.weight(1f).fillMaxWidth().testTag("discover.artist.list"), state = grid,
            contentPadding = PaddingValues(DulcetSpacing.pageGutter),
            horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.cardGap)) {
            // Always composed, empty until the page loads: the list keeps its first item in place, so a
            // header that arrived ahead of it would open scrolled out of view.
            item("artist", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    val failure = error ?: actionError
                    if (artist?.albums?.age?.isSaved() == true || failure != null) {
                        Box(Modifier.padding(bottom = DulcetSpacing.l)) {
                            DiscoverPageNotices(artist?.albums, failure) { refresh++ }
                        }
                    }
                    artist?.item?.let { header ->
                        val albumCount = artist?.albums?.items?.size ?: 0
                        DiscoverDetailHeader(
                            session = session,
                            item = header,
                            source = WishSource("artist", mbid),
                            headerTag = "discover.artist.header",
                            metadata = pluralStringResource(R.plurals.library_album_count, albumCount, albumCount),
                            artwork = {
                                DiscoverArtwork(session, header.artworkId, header.title, null, Modifier.detailHeroArt(),
                                    entity = ArtworkEntity.Artist, loadTag = "discover.artist.artwork")
                            },
                            openLibrary = openLibrary,
                        )
                        DiscoverLinks(header.links)
                    }
                }
            }
            if (loading && artist == null) item("loading", span = { GridItemSpan(maxLineSpan) }) {
                DiscoverLoadingRows("discover.artist.loading")
            }
            if (artist != null) item("albums-title", span = { GridItemSpan(maxLineSpan) }) {
                DulcetSectionHeader(stringResource(R.string.discover_pages_albums), Modifier.bleedKeyline(), role = SectionRole.Detail)
            }
            itemsIndexed(artist?.albums?.items.orEmpty(), key = { index, item -> "album:$index:${item.id}" }) { index, item ->
                Box(Modifier.padding(bottom = DulcetSpacing.cardGap)) {
                    DiscoverAlbumCard(session, item, index, WishSource("artist", mbid), openLibrary, openAlbum, omitArtist = true)
                }
            }
            if (artist?.albums?.nextCursor != null) item("more", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { more?.invoke() }, enabled = !loading && !loadingMore,
                    modifier = Modifier.testTag("discover.artist.more")) {
                    Text(stringResource(if (loadingMore) R.string.discover_pages_loading else R.string.discover_pages_more_albums))
                }
            }
            item("credit", span = { GridItemSpan(maxLineSpan) }) { DiscoverCredit() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PhoneDiscoverAlbum(
    session: DiscoverWishlistSession,
    mbid: String,
    openLibrary: (DiscoverLibraryRef) -> Unit,
    back: () -> Unit,
    playLibrary: ((DiscoverLibraryRef, Boolean) -> Unit)? = null,
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
    LaunchedEffect(album?.selectedReleaseId, album?.editions, requestedRelease) {
        if (requestedRelease == null && album != null && album?.selectedReleaseId == null)
            requestedRelease = album?.editions?.firstOrNull()?.releaseId
    }
    val selectedRelease = requestedRelease ?: album?.selectedReleaseId
    val selectedAlbum = album?.takeIf { it.selectedReleaseId == selectedRelease }
    var editionsOpen by remember(mbid) { mutableStateOf(false) }
    DisposableEffect(session, mbid, requestedRelease, refresh) {
        val lifetime = DiscoverPageLifetime()
        loading = true; error = null; loadingMore = false
        lifetime.own(session.album(mbid, requestedRelease) { result -> if (lifetime.active) {
            loading = false
            when (result) {
                is DiscoverResult.Value -> album = result.value.let { value ->
                    if (value.selectedReleaseId == null && requestedRelease != null) value.copy(selectedReleaseId = requestedRelease) else value
                }
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
    val publication by session.publication.collectAsState()
    val downloadedOnly = publication?.connection == "offline" || error?.code in setOf("unauthorized", "offline")
    val tracks = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollBehavior = rememberPositionTintedBar { tracks.firstVisibleItemIndex > 0 || tracks.firstVisibleItemScrollOffset > 0 }
    Column(Modifier.fillMaxSize().testTag("discover.album.surface")) {
        DiscoverMetadataBar(album?.item?.title?.let { if (tracks.firstVisibleItemIndex > 0) it else "" }
            ?: stringResource(R.string.discover_pages_album), back, scrollBehavior) {
            album?.item?.let { DiscoverRejectMenu(session, it) { actionError = it } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover.album.list"), state = tracks,
            contentPadding = PaddingValues(DulcetSpacing.pageGutter)) {
            // Always composed, empty until the page loads: the list keeps its first item in place, so a
            // header that arrived ahead of it would open scrolled out of view.
            item("album") {
                Column {
                    val failure = error ?: actionError
                    if (selectedAlbum?.tracks?.age?.isSaved() == true || failure != null) {
                        Box(Modifier.padding(bottom = DulcetSpacing.l)) {
                            DiscoverPageNotices(selectedAlbum?.tracks, failure) { refresh++ }
                        }
                    }
                    album?.item?.let { header ->
                        val selectedEdition = album?.editions?.firstOrNull { it.releaseId == selectedRelease }
                        DiscoverDetailHeader(
                            session = session,
                            item = header,
                            source = WishSource("album", mbid),
                            headerTag = "discover.album.header",
                            metadata = listOfNotNull(header.releaseDate?.take(4) ?: header.year?.toString(),
                                selectedEdition?.trackCount?.let { pluralStringResource(R.plurals.discover_pages_track_count, it, it).replace(" ", "\u00a0") })
                                .metadataLine(),
                            artwork = {
                                DiscoverArtwork(session, header.artworkId, header.title, null,
                                    Modifier.detailHeroArt(),
                                    entity = ArtworkEntity.Album, loadTag = "discover.album.artwork")
                            },
                            openLibrary = openLibrary,
                            playLibrary = playLibrary?.let { play -> { ref -> play(ref, downloadedOnly) } },
                        )
                        DiscoverLinks(header.links)
                    }
                }
            }
            if (!album?.editions.isNullOrEmpty()) item("editions") {
                Column {
                    DulcetSectionHeader(stringResource(R.string.discover_pages_edition), Modifier.bleedKeyline(), role = SectionRole.Detail)
                    if (album?.editions.orEmpty().size > 3) androidx.compose.foundation.layout.Box {
                        val selectedIndex = album?.editions.orEmpty().indexOfFirst { it.releaseId == selectedRelease }.coerceAtLeast(0)
                        androidx.compose.material3.OutlinedButton({ editionsOpen = true }, Modifier.fillMaxWidth().testTag("discover.album.editions")) {
                            Text(album?.editions?.getOrNull(selectedIndex)?.releaseDate
                                ?: stringResource(R.string.discover_pages_edition_number, selectedIndex + 1), Modifier.weight(1f))
                            Icon(DulcetIcons.ExpandMore, null)
                        }
                        androidx.compose.material3.DropdownMenu(editionsOpen, { editionsOpen = false }) {
                            album?.editions.orEmpty().forEachIndexed { index, edition ->
                                PhoneMenuItem(
                                    text = { Text(listOfNotNull(edition.releaseDate?.let(::discoverEditionDate), edition.trackCount?.let {
                                        pluralStringResource(R.plurals.discover_pages_track_count, it, it) }).metadataLine()
                                        .ifBlank { stringResource(R.string.discover_pages_edition_number, index + 1) }) },
                                    leadingIcon = { if (edition.releaseId == selectedRelease) Icon(DulcetIcons.Check, null) },
                                    onClick = { editionsOpen = false; if (edition.releaseId != selectedRelease) requestedRelease = edition.releaseId },
                                    modifier = Modifier.testTag("discover.album.edition.$index"))
                            }
                        }
                    } else FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.s),
                        verticalArrangement = Arrangement.spacedBy(DulcetSpacing.s),
                    ) {
                        album?.editions.orEmpty().forEachIndexed { index, edition ->
                            FilterChip(selected = edition.releaseId == selectedRelease,
                                onClick = { if (edition.releaseId != selectedRelease) requestedRelease = edition.releaseId },
                                modifier = Modifier.testTag("discover.album.edition.$index"),
                                leadingIcon = { if (edition.releaseId == selectedRelease) Icon(DulcetIcons.Check, null) },
                                label = { Text(listOfNotNull(edition.releaseDate?.let(::discoverEditionDate),
                                    edition.trackCount?.let { pluralStringResource(R.plurals.discover_pages_track_count, it, it) })
                                    .metadataLine().ifBlank { stringResource(R.string.discover_pages_edition_number, index + 1) },
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
                        }
                    }
                }
            }
            if (loading) item("loading") { DiscoverLoadingRows("discover.album.loading") }
            if (album != null) item("tracks-title") {
                DulcetSectionHeader(stringResource(R.string.discover_pages_tracks), Modifier.bleedKeyline(), role = SectionRole.Detail)
            }
            if (!loading && error == null && selectedAlbum != null && selectedAlbum.tracks.items.isEmpty() && selectedAlbum.tracks.nextCursor == null) {
                item("tracks-empty") {
                    Text(stringResource(R.string.discover_pages_no_tracks),
                        Modifier.fillMaxWidth().testTag("discover.album.tracks.empty"),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(selectedAlbum?.tracks?.items.orEmpty(), key = { index, item -> "track:$index:${item.id}" }) { index, item ->
                DiscoverTrackRow(session, item, index, WishSource("album", mbid), openLibrary)
            }
            if (selectedAlbum?.tracks?.nextCursor != null) item("more") {
                TextButton(onClick = { more?.invoke() }, enabled = !loading && !loadingMore,
                    modifier = Modifier.testTag("discover.album.more")) {
                    Text(stringResource(if (loadingMore) R.string.discover_pages_loading else R.string.discover_pages_more_tracks))
                }
            }
            item("credit") { DiscoverCredit() }
        }
    }
}

/** The cursor can overlap its predecessor; preserve order and distinct edition slots. */
private fun appendPage(previous: DiscoverPage, next: DiscoverPage): DiscoverPage = next.copy(
    items = (previous.items + next.items).distinctBy { Triple(it.id, it.disc, it.position) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverMetadataBar(title: String, back: () -> Unit, scrollBehavior: TopAppBarScrollBehavior, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { DetailBarTitleText(title, androidx.compose.material3.LocalTextStyle.current, maxLines = 1) },
        expandedHeight = detailAppBarHeight(collapsed = false, title = title, alwaysShowTitle = true), scrollBehavior = scrollBehavior, navigationIcon = {
        IconButton(onClick = back, modifier = Modifier.testTag("discover.back")) {
            Icon(DulcetIcons.ArrowBack, contentDescription = stringResource(R.string.discover_pages_back))
        }
    }, actions = actions)
}

/** An edition's date as a person reads it. MusicBrainz gives a year, a month or a day; anything else stays as it came. */
internal fun discoverEditionDate(iso: String, locale: Locale = Locale.getDefault()): String {
    val parts = Regex("""(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?""").matchEntire(iso)?.groupValues ?: return iso
    val month = parts[2].toIntOrNull()
    val day = parts[3].toIntOrNull()
    if (month != null && month !in 1..12 || day != null && day !in 1..31) return iso
    val utc = TimeZone.getTimeZone("UTC")
    val calendar = Calendar.getInstance(utc).apply { clear(); set(parts[1].toInt(), (month ?: 1) - 1, day ?: 1) }
    val skeleton = if (day != null) "yMMMd" else if (month != null) "yMMM" else "y"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = utc }.format(calendar.time)
}
