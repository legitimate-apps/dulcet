package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.queueEditRefused
import com.legitimateapps.dulcet.shared.R as SharedR

/** A named remote action below a card works in full grids and shelves without a long-press gesture. */
@Composable
internal fun TvCardOptions(tag: String, title: String, choices: List<TvQueueChoice>) {
    if (choices.isEmpty()) return
    var expanded by remember(tag) { mutableStateOf(false) }
    TvAction(stringResource(R.string.tv_more_options), "$tag.more") { expanded = true }
    if (expanded) TvQueueMenu("$tag.actions", title, choices, stringResource(SharedR.string.queue_cancel)) { expanded = false }
}

/** The same membership, pin and queue actions that are available after opening the item. */
@Composable
internal fun TvContextCard(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    item: AndroidLibraryItem,
    tag: String,
    default: Boolean = false,
    width: Int? = 200,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val resources = context.resources
    val target = item.favouriteTarget()
    val membership = target?.let { TvMembershipAction(session.myLibrary, it) }
    val depth by session.depth.collectAsState()
    val pinnable = target != null || (item is AndroidLibraryItem.Playlist && !item.local)
    val pinned = depth?.let { session.isPinned(item) } == true
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
        membership?.let { toggle -> add(TvQueueChoice(resources.getString(
            if (toggle.added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library), "$tag.actions.membership", toggle.toggle)) }
        if (pinnable && depth != null) add(TvQueueChoice(resources.getString(
            if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin), "$tag.actions.pin") { session.togglePin(item) })
        if (item is AndroidLibraryItem.Album && playback != null) {
            add(TvQueueChoice(resources.getString(SharedR.string.queue_play_next), "$tag.actions.playNext") { addAlbum(AndroidQueueInsertion.PlayNext) })
            add(TvQueueChoice(resources.getString(SharedR.string.queue_add_to_queue), "$tag.actions.addToQueue") { addAlbum(AndroidQueueInsertion.AddToQueue) })
        } else track?.let {
            add(TvQueueChoice(resources.getString(SharedR.string.queue_play_next), "$tag.actions.playNext", it.playNext))
            add(TvQueueChoice(resources.getString(SharedR.string.queue_add_to_queue), "$tag.actions.addToQueue", it.addToQueue))
        }
    }
    Column {
        TvCard(account, item, tag, default, width, onClick)
        TvCardOptions(tag, item.displayTitle(), choices)
    }
}
