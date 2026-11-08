package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.library.canBeQueued
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.library.rememberLibraryCategory
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.MyLibraryAlbum
import com.legitimateapps.dulcet.library.MyLibraryArtist
import com.legitimateapps.dulcet.library.MyLibraryAvailability
import com.legitimateapps.dulcet.library.MyLibraryMembership
import com.legitimateapps.dulcet.library.MyLibraryPublication
import com.legitimateapps.dulcet.library.MyLibrarySession
import com.legitimateapps.dulcet.library.MyLibrarySong
import com.legitimateapps.dulcet.library.MyLibrarySort
import com.legitimateapps.dulcet.library.MyLibrarySyncState
import com.legitimateapps.dulcet.library.membershipOf
import com.legitimateapps.dulcet.library.toLibraryItem
import com.legitimateapps.dulcet.library.toTrack
import com.legitimateapps.dulcet.library.toggleMembership
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity

/*
 * My Library on the phone (spec §29.4): what this account added to its library on a server that
 * advertises `dulcetLibrary`, in the Apple Music split — Library beside Browse. Sections: Recently
 * Added, Artists, Albums, Songs, and Playlists, which stay the reader's own screen. The lists are the
 * core's publication, painted as they arrive; add and remove are its optimistic writes. Without the
 * extension this screen is never reachable: PhoneApp does not split the tabs.
 */

/** My Library's sections, in the Apple shells' order. */
internal enum class MyLibraryView { Recent, Artists, Albums, Songs, Playlists, Downloaded }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyLibraryHome(
    account: SearchAccount,
    session: LibrarySession,
    myLibrary: MyLibrarySession,
    actions: PhoneActions,
    openBrowse: () -> Unit,
    wishlist: (@Composable () -> Unit)? = null,
) {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val navigation = if (actions.openRoute !== NoPhoneRoute) actions else actions.copy(openRoute = { route = it }, back = { route = null })
    androidx.activity.compose.BackHandler(route != null) { route = null }
    route?.let {
        LibraryRoutePage(it, account, session, navigation, null, myLibrary)
        return
    }
    val publication by myLibrary.state.collectAsState()
    val current = publication
    LibraryRootFrame(stringResource(R.string.tab_library), "mylibrary.refresh", { myLibrary.refresh() }, loading =
        current == null || ((current.syncState == MyLibrarySyncState.Never || current.syncState == MyLibrarySyncState.Syncing) &&
            current.albums.isEmpty() && current.artists.isEmpty() && current.songs.isEmpty())) {
        // The notice stands above the list: as a lazy first item it starts empty, which the list treats
        // as scrolled past, so a failure arriving later stayed above the viewport.
        Column(Modifier.fillMaxSize()) {
        ConnectionNotices(session, accountNotices = true, savedAccount = account)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mylibrary.surface"), contentPadding = PaddingValues(bottom = 24.dp)) {
            for (id in com.legitimateapps.dulcet.library.LibraryCategories.rootIds(false, true)) when (id) {
                "playlists" -> item { LibraryRootRow(stringResource(R.string.library_view_playlists), DulcetIcons.QueueMusic, "mylibrary.view.playlists") { navigation.openRoute(id) } }
                "favourites" -> item { LibraryRootRow(stringResource(R.string.library_favorites_title), DulcetIcons.Favourite, "mylibrary.view.favourites") { navigation.openRoute(id) } }
                "downloaded" -> item { LibraryRootRow(stringResource(R.string.library_downloads_title), DulcetIcons.Download, "mylibrary.view.downloaded") { navigation.openRoute(id) } }
                "wishlist" -> if (wishlist != null) item { wishlist() }
                "pinned" -> item { LibraryRootRow(stringResource(SharedR.string.library_pinned), LibraryRowIcons.PushPin, "mylibrary.view.pinned") { navigation.openRoute(id) } }
                "mostPlayed" -> {
                    item { LibraryRootRow(stringResource(SharedR.string.library_home_recent), LibraryRowIcons.History, "mylibrary.view.recentlyPlayed") { navigation.openRoute("recentlyPlayed") } }
                    item { LibraryRootRow(stringResource(SharedR.string.library_home_frequent), LibraryRowIcons.TrendingUp, "mylibrary.view.mostPlayed") { navigation.openRoute(id) } }
                }
                "artists" -> {
                    item { Text(stringResource(R.string.library_in_your_library),
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    item { LibraryRootRow(stringResource(R.string.library_view_artists), DulcetIcons.Person, "mylibrary.view.artists") { navigation.openRoute("myLibraryArtists") } }
                }
                "albums" -> item { LibraryRootRow(stringResource(R.string.library_view_albums), DulcetIcons.Album, "mylibrary.view.albums") { navigation.openRoute("myLibraryAlbums") } }
                "songs" -> item { LibraryRootRow(stringResource(SharedR.string.search_group_songs), DulcetIcons.MusicNote, "mylibrary.view.songs") { navigation.openRoute("myLibrarySongs") } }
            }
            when {
                current == null || ((current.syncState == MyLibrarySyncState.Never || current.syncState == MyLibrarySyncState.Syncing) &&
                    current.albums.isEmpty() && current.artists.isEmpty() && current.songs.isEmpty()) ->
                    Unit
                current.isEmpty() -> Unit
                else -> {
                    item { SectionHeading(SharedR.string.library_home_newest, "mylibrary.recent") }
                    item { LazyRow(Modifier.testTag("mylibrary.recent.albums"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(current.albums.take(RECENT_COUNT), key = { _, album -> album.rawId }) { index, album ->
                            MyLibraryAlbumCard(account, myLibrary, album, "mylibrary.recent.album.$index", Modifier.width(160.dp)) { actions.openAlbum(album.rawId) }
                        }
                    } }
                    val songs = current.songs.take(RECENT_COUNT)
                    val tracks = songs.map(MyLibrarySong::toTrack)
                    if (songs.isNotEmpty()) {
                        item { SectionHeading(SharedR.string.library_favourites_songs, "mylibrary.recent.songs") }
                        itemsIndexed(songs, key = { _, song -> song.rawId }) { index, song ->
                            MyLibraryTrackRow(account, myLibrary, song, tracks, index, "mylibrary.recent.track.$index", playingRawId = null, actions)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun MyLibraryCollection(account: SearchAccount, session: LibrarySession, myLibrary: MyLibrarySession?, actions: PhoneActions, route: String, playingRawId: String?) {
    if (myLibrary == null) return
    val publication by myLibrary.state.collectAsState()
    val current = publication
    if (current == null) { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("mylibrary.loading")); return }
    val category = when (route) { "myLibraryAlbums" -> "albums"; "myLibraryArtists" -> "artists"; else -> "songs" }
    val controls = rememberLibraryCategory(session, "mylibrary.$category")
    LaunchedEffect(route, controls.sort) {
        myLibrary.setSort(if (controls.sort == "recentlyAdded") MyLibrarySort.RecentlyAdded else MyLibrarySort.Alphabetical)
    }
    Column(Modifier.fillMaxSize()) {
        LibraryCategoryControls(controls, showFilters = false, sorts = listOf("title" to if (route == "myLibraryArtists") "Artist (A–Z)" else "Title (A–Z)", "recentlyAdded" to "Recently added"))
        val collectionEmpty = when (route) { "myLibraryAlbums" -> current.albums.isEmpty(); "myLibraryArtists" -> current.artists.isEmpty(); else -> current.songs.isEmpty() }
        if (collectionEmpty) {
            if (current.syncState == MyLibrarySyncState.Never || current.syncState == MyLibrarySyncState.Syncing)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("mylibrary.loading"))
            else MyLibraryEmpty { actions.openRoute("browse") }
            return@Column
        }
        when (route) {
            "myLibraryAlbums" -> MyLibraryAlbums(account, myLibrary, current, actions)
            "myLibraryArtists" -> MyLibraryArtists(myLibrary, current, actions)
            else -> MyLibrarySongs(account, myLibrary, current, actions, playingRawId)
        }
    }
}

private fun MyLibraryPublication.isEmpty(): Boolean =
    albums.isEmpty() && artists.isEmpty() && songs.isEmpty() &&
        (syncState == MyLibrarySyncState.Current || syncState == MyLibrarySyncState.Cached)

/** The empty library says how to fill it: Browse is the whole catalog, one tap away. */
@Composable
private fun MyLibraryEmpty(openBrowse: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(SharedR.string.library_my_library_empty), Modifier.testTag("mylibrary.empty"),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(SharedR.string.library_my_library_empty_hint),
            Modifier.padding(top = 8.dp).testTag("mylibrary.empty.hint"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        TextButton(onClick = openBrowse, modifier = Modifier.padding(top = 12.dp).testTag("mylibrary.browse")) {
            Icon(DulcetIcons.LibraryMusic, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(SharedR.string.library_browse))
        }
    }
}

/** Recently Added: the albums newest first as cards, then the songs, playable from their rows. */
@Composable
private fun MyLibraryRecent(account: SearchAccount, myLibrary: MyLibrarySession, publication: MyLibraryPublication, actions: PhoneActions) {
    val albums = publication.albums.take(RECENT_COUNT)
    val songs = publication.songs.take(RECENT_COUNT)
    val tracks = songs.map(MyLibrarySong::toTrack)
    LazyColumn(Modifier.fillMaxSize().testTag("mylibrary.recent"), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (albums.isNotEmpty()) {
            item {
                LazyRow(Modifier.testTag("mylibrary.recent.albums"),
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
                        MyLibraryAlbumCard(account, myLibrary, album, "mylibrary.recent.album.$position",
                            Modifier.width(140.dp)) { actions.openAlbum(album.rawId) }
                    }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { SectionHeading(SharedR.string.library_favourites_songs, "mylibrary.recent.songs") }
            itemsIndexed(songs, key = { _, song -> song.rawId }) { position, song ->
                MyLibraryTrackRow(account, myLibrary, song, tracks, position, "mylibrary.recent.track.$position", playingRawId = null, actions)
            }
        }
    }
}

private const val RECENT_COUNT = 20

@Composable
private fun MyLibraryArtists(myLibrary: MyLibrarySession, publication: MyLibraryPublication, actions: PhoneActions) {
    LazyColumn(Modifier.fillMaxSize().testTag("mylibrary.artists")) {
        itemsIndexed(publication.artists, key = { _, artist -> artist.rawId }) { position, artist ->
            MyLibraryArtistRow(myLibrary, artist, "mylibrary.artists.$position") {
                actions.openArtist(artist.rawId)
            }
        }
    }
}

@Composable
private fun MyLibraryAlbums(account: SearchAccount, myLibrary: MyLibrarySession, publication: MyLibraryPublication, actions: PhoneActions) {
    LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.fillMaxSize().testTag("mylibrary.albums"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        itemsIndexed(publication.albums, key = { _, album -> album.rawId }) { position, album ->
            MyLibraryAlbumCard(account, myLibrary, album, "mylibrary.albums.$position") { actions.openAlbum(album.rawId) }
        }
    }
}

@Composable
private fun MyLibrarySongs(account: SearchAccount, myLibrary: MyLibrarySession, publication: MyLibraryPublication, actions: PhoneActions, playingRawId: String?) {
    val tracks = publication.songs.map(MyLibrarySong::toTrack)
    LazyColumn(Modifier.fillMaxSize().testTag("mylibrary.songs")) {
        item {
            TextButton(onClick = { actions.playTracksFrom(tracks, null, "My library", true) }, enabled = tracks.any { it.canBeQueued() },
                modifier = Modifier.testTag("mylibrary.songs.shuffleAll")) { Text(stringResource(SharedR.string.library_shuffle_all)) }
        }
        itemsIndexed(publication.songs, key = { _, song -> song.rawId }) { position, song ->
            MyLibraryTrackRow(account, myLibrary, song, tracks, position, "mylibrary.songs.$position", playingRawId, actions)
        }
    }
}

@Composable
private fun MyLibraryTrackRow(
    account: SearchAccount,
    myLibrary: MyLibrarySession,
    song: MyLibrarySong,
    tracks: List<AndroidLibraryItem.Track>,
    position: Int,
    tag: String,
    playingRawId: String?,
    actions: PhoneActions,
) {
    val track = song.toTrack()
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Track, song.rawId)
    // Every song in these lists is a member, pending or not; the row's menu removes it.
    TrackRow(track, position + 1, track.rawId == playingRawId, Modifier.testTag(tag),
        onUnavailable = {},
        rowTagOverride = tag,
        artwork = { Artwork(account, track.artworkKey, track.title.orEmpty(), 48.dp, entity = ArtworkEntity.Song) },
        library = TrackLibrary(MyLibraryMembership.Added) { myLibrary.remove(target) }) {
        actions.playTracks(tracks, track.rawId,
            track.albumTitle ?: track.artistName ?: track.title.orEmpty())
    }
}

/** An artist of the library: monogram, name, album count, and its Remove beside it. */
@Composable
private fun MyLibraryArtistRow(myLibrary: MyLibrarySession, artist: MyLibraryArtist, tag: String, onClick: () -> Unit) {
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Artist, artist.rawId)
    ListItem(
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(if (artist.albumCount > 0) pluralStringResource(R.plurals.library_album_count, artist.albumCount, artist.albumCount)
                else stringResource(R.string.library_artist_kind))
        },
        leadingContent = { Monogram(artist.name, 48.dp) },
        trailingContent = {
            MembershipButton(MyLibraryMembership.Added, "$tag.library") { myLibrary.remove(target) }
        },
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
    )
}

/** An album of the library: its card, opening the album; a long press offers Remove (or Add, for one held by its songs). */
@Composable
private fun MyLibraryAlbumCard(account: SearchAccount, myLibrary: MyLibrarySession, album: MyLibraryAlbum, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId)
    val membership = when {
        album.explicitlyAdded -> MyLibraryMembership.Added
        album.memberSongCount > 0 -> MyLibraryMembership.Partial
        else -> MyLibraryMembership.NotAdded
    }
    AlbumCard(account, album.toLibraryItem(), modifier.testTag(tag), library = MembershipAction(membership == MyLibraryMembership.Added, tag) {
        myLibrary.toggleMembership(target, membership)
    }, onClick = onClick)
}

/**
 * The + / checkmark of an item's membership, as Apple Music draws it: the check is in the library
 * (tap removes it), the + adds. Its description is the action, and its state is `selected`.
 */
@Composable
internal fun MembershipButton(membership: MyLibraryMembership, tag: String, onToggle: () -> Unit) {
    val added = membership == MyLibraryMembership.Added
    IconButton(onClick = onToggle, modifier = Modifier.testTag(tag).semantics { selected = added }) {
        Icon(if (added) DulcetIcons.Check else DulcetIcons.Add,
            stringResource(if (added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library),
            tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
