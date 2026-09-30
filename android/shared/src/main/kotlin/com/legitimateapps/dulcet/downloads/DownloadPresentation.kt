package com.legitimateapps.dulcet.downloads

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidDownloadController
import com.legitimateapps.dulcet.core.AndroidDownloadItem
import com.legitimateapps.dulcet.core.AndroidDownloadState
import com.legitimateapps.dulcet.core.AndroidDownloadStatus
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A track that can be downloaded: the original file of one whose container this device plays
 * directly (spec §14.5 identity `(server, raw id, original)`). A track stored in a format that must
 * be transcoded to play has no download, since its original file would not play offline.
 */
public fun AndroidLibraryItem.Track.downloadItem(): AndroidDownloadItem? =
    if (metadataMissing) null else sourceContainer?.let { AndroidDownloadItem(rawId, it, durationMilliseconds) }

/** An album's downloads as one control: how many of its downloadable tracks are downloaded. */
public data class AlbumDownloads(
    val downloadable: Int,
    val downloaded: Int,
    /** Tracks with a download requested and not yet playable offline. */
    val pending: Int,
) {
    /** Some of the album is downloaded or requested, so the control removes rather than adds. */
    val anyRequested: Boolean get() = downloaded + pending > 0
    val complete: Boolean get() = downloadable > 0 && downloaded == downloadable

    public companion object {
        public fun of(tracks: List<AndroidLibraryItem.Track>, statuses: Map<String, AndroidDownloadStatus>): AlbumDownloads {
            val downloadable = tracks.filter { it.downloadItem() != null }
            val requested = downloadable.mapNotNull { statuses[it.rawId] }
            return AlbumDownloads(
                downloadable = downloadable.size,
                downloaded = requested.count(AndroidDownloadStatus::playsOffline),
                pending = requested.count { !it.playsOffline },
            )
        }
    }
}

/** The words for one track's download, or null when it has none. */
public fun Resources.downloadLine(status: AndroidDownloadStatus?): String? = when {
    status == null -> null
    status.playsOffline -> getString(R.string.download_downloaded)
    status.needsAttention -> getString(R.string.download_needs_attention)
    status.state == AndroidDownloadState.Downloading -> status.totalBytes?.takeIf { it > 0 }
        ?.let { getString(R.string.download_in_progress_percent, (status.bytesWritten * 100 / it).toInt().coerceIn(0, 100)) }
        ?: getString(R.string.download_in_progress)
    status.state == AndroidDownloadState.Interrupted -> getString(R.string.download_will_retry)
    else -> getString(R.string.download_queued)
}

/** The words for an album's download progress, or null when nothing of it is requested. */
public fun Resources.albumDownloadLine(album: AlbumDownloads): String? = when {
    !album.anyRequested -> null
    album.complete -> getString(R.string.download_album_complete)
    else -> getString(R.string.download_album_progress, album.downloaded, album.downloadable)
}

/**
 * What the screens do with downloads. Each request runs off the main thread on the process's
 * controller, which outlives the screen that asked, so leaving the screen does not drop it.
 */
public interface DownloadRequests {
    public val statuses: StateFlow<Map<String, AndroidDownloadStatus>>
    public fun download(tracks: List<AndroidLibraryItem.Track>)
    public fun remove(rawIds: Collection<String>)
}

/** Production requests, on the saved account's controller. */
public class AccountDownloadRequests private constructor(private val context: Context) : DownloadRequests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatuses = MutableStateFlow<Map<String, AndroidDownloadStatus>>(emptyMap())
    override val statuses: StateFlow<Map<String, AndroidDownloadStatus>> = mutableStatuses
    @Volatile private var following: AndroidDownloadController? = null

    /** Follows the saved account's controller; called when a screen shows downloads. */
    internal suspend fun attach() {
        val controller = withContext(Dispatchers.IO) { AndroidDownloads.controller(context) }
        if (controller == null) { mutableStatuses.value = emptyMap(); return }
        if (following === controller) return
        following = controller
        scope.launch { controller.statuses.collect { if (following === controller) mutableStatuses.value = it } }
    }

    override fun download(tracks: List<AndroidLibraryItem.Track>) {
        val items = tracks.mapNotNull { it.downloadItem() }
        if (items.isEmpty()) return
        scope.launch { AndroidDownloads.controller(context)?.download(items) }
    }

    override fun remove(rawIds: Collection<String>) {
        if (rawIds.isEmpty()) return
        val ids = rawIds.toList()
        scope.launch { AndroidDownloads.controller(context)?.remove(ids) }
    }

    public companion object {
        @Volatile private var instance: AccountDownloadRequests? = null
        public fun of(context: Context): AccountDownloadRequests = instance ?: synchronized(this) {
            instance ?: AccountDownloadRequests(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * The download statuses for the saved account, following its controller. The first composition
 * creates the controller if nothing has yet, which runs the relaunch reconciliation (spec §14.5).
 */
@Composable
public fun rememberDownloadStatuses(requests: DownloadRequests? = null): Map<String, AndroidDownloadStatus> {
    val context = LocalContext.current
    val source = requests ?: remember(context) { AccountDownloadRequests.of(context) }
    if (source is AccountDownloadRequests) LaunchedEffect(source) { source.attach() }
    val statuses by source.statuses.collectAsState()
    return statuses
}
