package com.legitimateapps.dulcet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverItem
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.DiscoverResult
import com.legitimateapps.dulcet.core.WishSource
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.ui.DulcetIcons

/** Library's detail header with Discover's wish/open actions in place of Play/Shuffle. */
@Composable
internal fun DiscoverDetailHeader(
    session: DiscoverWishlistSession,
    item: DiscoverItem,
    source: WishSource,
    headerTag: String,
    metadata: String?,
    artwork: @Composable () -> Unit,
    openLibrary: (DiscoverLibraryRef) -> Unit,
) {
    val publication by session.publication.collectAsState()
    val access = publication?.access
    val wish = publication?.wishes?.firstOrNull {
        (it.wish?.target ?: it.pendingAdd?.target) == item.target && item.target != null
    }
    var saveError by remember(session, item.id) { mutableStateOf<DiscoverFailure?>(null) }
    val wishAction = when {
        wish != null && access?.canRemoveWish == true -> DetailAction(
            R.string.discover_remove_wish, DulcetIcons.Wish, "discover.wish.${item.kind}.0") {
            session.remove(wish.key) { saveError = (it as? DiscoverResult.Failed)?.error }
        }
        access?.canAddWish == true && item.wishable && item.target != null && item.availability.status != "held" -> DetailAction(
            R.string.discover_add_wish, DulcetIcons.WishBorder, "discover.wish.${item.kind}.0") {
            session.add(item, source) { saveError = (it as? DiscoverResult.Failed)?.error }
        }
        else -> null
    }
    val openAction = item.availability.libraryItems.firstOrNull()?.let { first ->
        DetailAction(R.string.discover_open_library, DulcetIcons.LibraryMusic, "discover.openLibrary.${item.kind}.0") {
            openLibrary(first)
        }
    }
    LibraryDetailHeader(
        title = item.title,
        artwork = artwork,
        modifier = Modifier.testTag(headerTag),
        subtitle = item.artist.takeIf { item.kind == "album" },
        metadata = metadata,
        status = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DiscoverAvailabilityText(item.availability, tag = "discover.availability.${item.kind}.0")
                if (wish?.pending == true) Text(stringResource(R.string.discover_waiting), style = MaterialTheme.typography.bodySmall)
                (saveError ?: wish?.error)?.let { failure ->
                    Text(stringResource(if (failure.retryable) R.string.discover_waiting else R.string.discover_not_saved),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) {
        when {
            wishAction != null -> DetailActionButtons(wishAction, openAction, stacked = true)
            openAction != null -> DetailActionButtons(openAction, stacked = true)
        }
    }
}

/** A Discover track in the numbered row form Library album pages use. */
@Composable
internal fun DiscoverTrackRow(
    session: DiscoverWishlistSession,
    item: DiscoverItem,
    index: Int,
    source: WishSource,
    openLibrary: (DiscoverLibraryRef) -> Unit,
) {
    val publication by session.publication.collectAsState()
    val wish = publication?.wishes?.firstOrNull {
        (it.wish?.target ?: it.pendingAdd?.target) == item.target && item.target != null
    }
    var saveError by remember(session, item.id) { mutableStateOf<DiscoverFailure?>(null) }
    Column(Modifier.fillMaxWidth()) {
        TrackRowVisual(
            title = item.title,
            supporting = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.artist?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DiscoverAvailabilityText(item.availability, tag = "discover.availability.${item.kind}.$index")
                }
            },
            number = item.position ?: index + 1,
            modifier = Modifier.testTag("discover.item.${item.kind}.$index"),
            trailing = {
                DiscoverItemActions(session, item, index, source, wish, publication?.access, openLibrary) { saveError = it }
            },
        )
        if (wish?.pending == true) Text(stringResource(R.string.discover_waiting))
        (saveError ?: wish?.error)?.let { failure ->
            Text(stringResource(if (failure.retryable) R.string.discover_waiting else R.string.discover_not_saved),
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A Discover discography card in the shape Library album grids use. */
@Composable
internal fun DiscoverAlbumCard(
    session: DiscoverWishlistSession,
    item: DiscoverItem,
    index: Int,
    source: WishSource,
    openLibrary: (DiscoverLibraryRef) -> Unit,
    openAlbum: (String) -> Unit,
) {
    val publication by session.publication.collectAsState()
    val wish = publication?.wishes?.firstOrNull {
        (it.wish?.target ?: it.pendingAdd?.target) == item.target && item.target != null
    }
    var saveError by remember(session, item.id) { mutableStateOf<DiscoverFailure?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Box {
            AlbumCardVisual(
                title = item.title,
                subtitle = listOfNotNull(item.artist, item.releaseDate ?: item.year?.toString())
                    .joinToString(" · ").takeIf { it.isNotBlank() },
                modifier = Modifier.testTag("discover.item.${item.kind}.$index"),
                artwork = {
                    Box {
                        DiscoverArtwork(session, item.artworkId, item.title, null,
                            Modifier.fillMaxWidth().aspectRatio(1f))
                        DiscoverAvailabilityText(item.availability,
                            tag = "discover.availability.${item.kind}.$index",
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                },
                onClick = item.target?.let { target -> { openAlbum(target.mbid) } },
            )
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))) {
                DiscoverItemActions(session, item, index, source, wish, publication?.access, openLibrary) { saveError = it }
            }
        }
        if (wish?.pending == true) Text(stringResource(R.string.discover_waiting))
        (saveError ?: wish?.error)?.let { failure ->
            Text(stringResource(if (failure.retryable) R.string.discover_waiting else R.string.discover_not_saved),
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Match correction is the page's secondary action, kept behind the metadata overflow. */
@Composable
internal fun DiscoverRejectMenu(
    session: DiscoverWishlistSession,
    item: DiscoverItem,
    failed: (DiscoverFailure?) -> Unit,
) {
    val publication by session.publication.collectAsState()
    val access = publication?.access
    val wish = publication?.wishes?.firstOrNull {
        (it.wish?.target ?: it.pendingAdd?.target) == item.target && item.target != null
    }
    if (access?.canRejectMatch != true || item.availability.libraryItems.isEmpty()) return
    var open by remember(session, item.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("discover.item.menu.${item.kind}.0")) {
            Icon(DulcetIcons.MoreVert, stringResource(R.string.discover_item_actions, item.title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            item.availability.libraryItems.forEachIndexed { refIndex, ref ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.discover_reject)) },
                    leadingIcon = { Icon(DulcetIcons.Edit, null) },
                    onClick = {
                        open = false
                        session.reject(item, ref, wish?.wish?.id) { failed((it as? DiscoverResult.Failed)?.error) }
                    },
                    modifier = Modifier.testTag("discover.reject.${item.kind}.0.$refIndex"),
                )
            }
        }
    }
}
