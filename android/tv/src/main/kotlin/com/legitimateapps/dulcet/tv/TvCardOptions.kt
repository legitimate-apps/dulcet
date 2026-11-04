package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.queueEditRefused
import com.legitimateapps.dulcet.shared.R as SharedR

/**
 * A card whose context actions open on long-Select (a held centre key), not from a "More options"
 * pill below it: the card stays the item's one focus stop, and the menu is tagged `$tag.actions`.
 */
@Composable
internal fun TvContextCard(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    item: AndroidLibraryItem,
    tag: String,
    default: Boolean = false,
    width: Int? = 200,
    rank: Int? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val resources = context.resources
    val target = item.favouriteTarget()
    val membership = target?.let { TvMembershipAction(session.myLibrary, it) }
    val depth by session.depth.collectAsState()
    val pinnable = target != null || (item is AndroidLibraryItem.Playlist && !item.local)
    val pinned = depth?.let { session.isPinned(item) } == true
    var menu by remember(item.rawId) { mutableStateOf(false) }
    var collecting by remember(item.rawId) { mutableStateOf<AutoCloseable?>(null) }
    DisposableEffect(item.rawId) { onDispose { collecting?.close() } }
    fun addAlbum(insertion: AndroidQueueInsertion) {
        collecting?.close()
        collecting = session.collectAlbums(listOf(item.rawId)) { publications ->
            if (publications.singleOrNull()?.let { queueAlbum(playback, account.providerInstanceId, it, insertion) } != true)
                queueEditRefused(context)
        }
    }
    val track = (item as? AndroidLibraryItem.Track)?.let { tvTrackAddition(context, playback, account.providerInstanceId, it, null) }
    val choices = buildList {
        if (item is AndroidLibraryItem.Album && playback != null) {
            add(TvQueueChoice(resources.getString(SharedR.string.queue_play_next), "$tag.actions.playNext") { addAlbum(AndroidQueueInsertion.PlayNext) })
            add(TvQueueChoice(resources.getString(SharedR.string.queue_add_to_queue), "$tag.actions.addToQueue") { addAlbum(AndroidQueueInsertion.AddToQueue) })
        } else track?.let {
            add(TvQueueChoice(resources.getString(SharedR.string.queue_play_next), "$tag.actions.playNext", it.playNext))
            add(TvQueueChoice(resources.getString(SharedR.string.queue_add_to_queue), "$tag.actions.addToQueue", it.addToQueue))
        }
        if (target != null) add(TvQueueChoice(resources.getString(
            if (item.isFavourite()) R.string.tv_favorite_remove else R.string.tv_favorite_add), "$tag.actions.favourite") {
            session.toggleFavourite(target)
        })
        membership?.let { toggle -> add(TvQueueChoice(resources.getString(
            if (toggle.added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library), "$tag.actions.membership", toggle.toggle)) }
        if (pinnable && depth != null) add(TvQueueChoice(resources.getString(
            if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin), "$tag.actions.pin") { session.togglePin(item) })
    }
    TvCard(account, item, tag, default, width, rank = rank, onLongClick = if (choices.isEmpty()) null else ({ menu = true }), onClick = onClick)
    if (menu) TvQueueMenu("$tag.actions", item.displayTitle(), choices, resources.getString(SharedR.string.queue_cancel),
        supporting = tvItemSubtitle(item),
        artwork = { TvArtwork(account, tvItemArtworkKey(item), 64, tvItemPlaceholder(item), circle = item is AndroidLibraryItem.Artist) },
    ) { menu = false }
}
