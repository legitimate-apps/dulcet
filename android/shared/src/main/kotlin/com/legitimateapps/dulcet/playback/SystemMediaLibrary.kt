package com.legitimateapps.dulcet.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.SavedAccountConnection
import kotlinx.coroutines.*

/** A driver-safe projection of the same core reader as the native library. IDs carry no URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SystemMediaLibrary(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playback: () -> AndroidPlaybackController?,
) : MediaLibrarySession.Callback {
    private val issued = mutableMapOf<String, AndroidLibraryItem>()
    private var accountId: String? = null
    private data class BrowsePage(val query: AndroidLibraryQuery, val offset: Int)
    private val pages = mutableMapOf<String, BrowsePage>()

    override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> =
        com.google.common.util.concurrent.Futures.immediateFuture(
            // Media3's trust check includes our UID. A claimed package name alone is not
            // authentication when the connecting package is not visible to the service.
            if (controller.isTrusted)
                MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                    .setAvailablePlayerCommands(session.player.availableCommands.buildUpon()
                        .add(Player.COMMAND_SET_MEDIA_ITEM).add(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()).build()
            else MediaSession.ConnectionResult.reject())

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Dulcet"), params))

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        if (parentId == ROOT) return@future LibraryResult.ofItemList(listOf(folder(ALBUMS, "Library"), folder(PLAYLISTS, "Playlists"), folder(RECENT, "Recently Played"), folder(FAVOURITES, "Favourites")), params)
        val reader = reader() ?: return@future LibraryResult.ofError(SessionResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED)
        val continuation = pages[parentId]
        val offset = continuation?.offset ?: 0
        val query = continuation?.query ?: when (parentId) {
            ALBUMS -> AndroidLibraryQuery.AlbumList(AndroidAlbumListType.AlphabeticalByName)
            PLAYLISTS -> AndroidLibraryQuery.Playlists
            RECENT -> AndroidLibraryQuery.AlbumList(AndroidAlbumListType.Recent)
            FAVOURITES -> AndroidLibraryQuery.Starred
            else -> when (val item = issued[parentId]) {
                is AndroidLibraryItem.Album -> AndroidLibraryQuery.Album(item.rawId)
                is AndroidLibraryItem.Playlist -> AndroidLibraryQuery.Playlist(item.rawId)
                is AndroidLibraryItem.Artist -> AndroidLibraryQuery.Artist(item.rawId)
                else -> return@future LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            }
        }
        val publication = read(reader, query, offset + 101)
        val children = publication.items.drop(offset).take(100).map(::mediaItem).toMutableList()
        // Auto requests one list. Explicit continuation folders keep every page driver-accessible.
        if (publication.items.size > offset + 100) {
            val next = "dulcet.page." + java.util.UUID.randomUUID()
            pages[next] = BrowsePage(query, offset + 100)
            children += folder(next, "More…")
        }
        LibraryResult.ofItemList(children, params)
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
        val builtIn = mapOf(ROOT to "Dulcet", ALBUMS to "Library", PLAYLISTS to "Playlists", RECENT to "Recently Played", FAVOURITES to "Favourites")
        builtIn[mediaId]?.let { return@future LibraryResult.ofItem(folder(mediaId, it), null) }
        reader() ?: return@future LibraryResult.ofError(SessionResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED)
        pages[mediaId]?.let { return@future LibraryResult.ofItem(folder(mediaId, "More…"), null) }
        issued[mediaId]?.let { LibraryResult.ofItem(mediaItem(it), null) }
            ?: LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
        val results = search(query)
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        LibraryResult.ofItemList(search(query), params)
    }

    override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        val reader = reader() ?: throw IllegalStateException("Connect an account first")
        val requested = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            ?: throw IllegalArgumentException("No music selected")
        val item = issued[requested.mediaId] ?: requested.requestMetadata.searchQuery?.let { query ->
            val matches = search(query)
            if (matches.size == 1) issued[matches.single().mediaId] else null
        } ?: throw IllegalArgumentException("Select music from Dulcet's library")
        val provider = accountId ?: throw IllegalStateException("Account unavailable")
        val tracks = when (item) {
            is AndroidLibraryItem.Track -> listOfNotNull(item.toTrack(provider))
            is AndroidLibraryItem.Album -> read(reader, AndroidLibraryQuery.Album(item.rawId)).items.filterIsInstance<AndroidLibraryItem.Track>().mapNotNull { it.toTrack(provider) }
            is AndroidLibraryItem.Playlist -> read(reader, AndroidLibraryQuery.Playlist(item.rawId)).items.filterIsInstance<AndroidLibraryItem.Track>().mapNotNull { it.toTrack(provider) }
            else -> emptyList()
        }
        check(AndroidAccountCredentialStore(context).activeAccountId() == provider && tracks.isNotEmpty()) { "Music unavailable" }
        playback()?.playQueue(tracks, 0, AndroidQueueSource.Library, "Dulcet")
            ?: throw IllegalStateException("Playback unavailable")
        // The session's player ignores generic replacement. Only core-validated playQueue installs sources.
        MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
    }

    private fun reader(): AndroidLibraryReader? {
        val account = runCatching { AndroidAccountCredentialStore(context).load() }.getOrNull() ?: return null
        if (accountId != account.id) { issued.clear(); pages.clear(); accountId = account.id }
        val reader = AndroidLibraryReader.forAccount(context, AndroidLibraryReaderAccount(account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp), false)
        SavedAccountConnection.reconnectChosen(reader)
        reader.reconnect()
        return reader
    }

    private suspend fun read(reader: AndroidLibraryReader, query: AndroidLibraryQuery, minimum: Int = 0): AndroidLibraryPublication = withTimeout(15_000) {
        var latest: AndroidLibraryPublication? = null
        var extendedSize: Int? = null
        val window = reader.openWindow(query) { latest = it }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val publication = latest
                if (publication != null && publication.freshness !is AndroidLibraryFreshness.Loading && publication.itemsState != AndroidLibraryItemsState.Loading) {
                    val grows = publication.coverage in listOf(AndroidLibraryCoverage.Open, AndroidLibraryCoverage.UnverifiedScanning, AndroidLibraryCoverage.UnverifiedNoEpoch)
                    if (publication.items.size >= minimum || !grows) return@withTimeout publication
                    if (extendedSize != publication.items.size) { extendedSize = publication.items.size; window.setViewport(0, maxOf(0, publication.items.size - 1)); window.loadMore() }
                }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally { window.close() }
    }

    private suspend fun search(query: String): List<MediaItem> {
        val reader = reader() ?: return emptyList()
        return withTimeout(15_000) {
            val answer = CompletableDeferred<List<MediaItem>>()
            val search = reader.openSearch { publication ->
                if (publication.query == query && publication.scope !is AndroidLibrarySearchScope.DeviceWhileServerPending) {
                    val tracks = publication.rows.filter { it.item.type == SearchResultType.Track }.map { row ->
                        val result = row.item
                        val track = AndroidLibraryItem.Track(result.id.rawId, result.title, result.albumRawId, result.albumTitle,
                            result.credits.firstOrNull()?.name, null, null, null, result.duration?.inWholeMilliseconds,
                            null, result.artworkKey, row.favourite, row.rating, null,
                            row.playability ?: AndroidLibraryPlayability.Streamable, false)
                        mediaItem(track)
                    }
                    answer.complete(tracks)
                }
            }
            try { search.updateQuery(query); answer.await() } finally { search.close() }
        }
    }

    private fun mediaItem(item: AndroidLibraryItem): MediaItem {
        val kind = when (item) { is AndroidLibraryItem.Track -> "song"; is AndroidLibraryItem.Album -> "album"; is AndroidLibraryItem.Artist -> "artist"; is AndroidLibraryItem.Playlist -> "playlist"; is AndroidLibraryItem.Genre -> "genre" }
        val id = Uri.Builder().scheme("dulcet").authority(accountId).appendPath(kind).appendPath(item.rawId).build().toString()
        issued[id] = item
        val title = when (item) { is AndroidLibraryItem.Track -> item.title; is AndroidLibraryItem.Album -> item.title; is AndroidLibraryItem.Artist -> item.name; is AndroidLibraryItem.Playlist -> item.name; is AndroidLibraryItem.Genre -> item.rawId }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title)
            .setIsBrowsable(item !is AndroidLibraryItem.Track).setIsPlayable(item is AndroidLibraryItem.Track && item.playability != AndroidLibraryPlayability.UnavailableOffline)
            .setMediaType(if (item is AndroidLibraryItem.Track) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    }
    internal fun AndroidLibraryItem.Track.toTrack(provider: String): AndroidTrack? =
        if (playability == AndroidLibraryPlayability.UnavailableOffline || title.isNullOrBlank()) null else AndroidTrack(provider, rawId, title.orEmpty(), artistName, albumTitle, durationMilliseconds, artworkKey = artworkKey, albumRawId = albumRawId)

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = scope.launch { try { future.set(block()) } catch (error: Exception) { future.setException(IllegalStateException("Dulcet media request unavailable")) } }
        future.addListener({ if (future.isCancelled) job.cancel() }, java.util.concurrent.Executor { it.run() })
        return future
    }
    companion object {
        const val ROOT = "dulcet.root"
        const val ALBUMS = "dulcet.albums"
        const val PLAYLISTS = "dulcet.playlists"
        const val RECENT = "dulcet.recent"
        const val FAVOURITES = "dulcet.favourites"
        fun folder(id: String, title: String): MediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    }
}
