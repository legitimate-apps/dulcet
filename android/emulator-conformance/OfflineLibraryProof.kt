package com.legitimateapps.dulcet.emulator

import android.content.Context
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.CoreAccountDataGateway
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AndroidDownloadItem
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryQuery
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidMyLibrary
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real server, reader, WorkManager, Media3 tee and offline local replay, in the installed app. */
class OfflineLibraryProof(private val context: Context, private val probe: DisposableServerProbe) {
    fun run(
        launch: (StoredAccount, String?) -> AutoCloseable,
        turnAutomaticOffInSettings: () -> Unit,
    ) {
        val extensions = probe.call("getOpenSubsonicExtensions").getJSONArray("openSubsonicExtensions")
        check((0 until extensions.length()).any { extensions.getJSONObject(it).getString("name") == "dulcetLibrary" }) {
            "Offline library proof requires the disposable Dulcet Server fixture"
        }
        awaitQueuedBroadcastsDelivered()
        val relay = ServerRelay(probe.baseUrl, persistentConnections = false)
        connectSavedAccount(context, probe, relay.url)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        try {
            launch(account, null).use {
                await("the production library reader") { AndroidLibraryReader.currentFor(account.id) != null }
                val reader = checkNotNull(AndroidLibraryReader.currentFor(account.id))
                val library = AndroidMyLibrary(reader)
                val refreshed = CountDownLatch(1)
                library.refresh { refreshed.countDown() }
                check(refreshed.await(30, TimeUnit.SECONDS)) { "My Library never refreshed" }
                val controller = checkNotNull(AndroidDownloads.controller(context))
                check(runBlocking { controller.awaitReconciled() })
                await("default Automatic Downloads setting") { controller.offlineSettings.value.automaticDownloadsEnabled }
                val albums = probe.call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "50"))
                    .getJSONObject("albumList2").getJSONArray("album")
                val album = (0 until albums.length()).map(albums::getJSONObject)
                    .first { !it.getString("name").startsWith("[") && it.getString("name") != "Threshold Boundary" }
                val albumId = album.getString("id")
                val songs = probe.call("getAlbum", mapOf("id" to albumId)).getJSONObject("album").getJSONArray("song")
                check(songs.length() > 0)
                val ids = (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }
                val detailReady = CountDownLatch(1)
                val window = reader.openWindow(AndroidLibraryQuery.Album(albumId)) {
                    if (it.itemsState == AndroidLibraryItemsState.Present && it.items.isNotEmpty()) detailReady.countDown()
                }
                check(detailReady.await(30, TimeUnit.SECONDS)) { "The album never read its real tracks" }
                window.close()
                val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Album, albumId)
                check(library.add(target))
                await("automatic downloads of the added album", 180_000) {
                    ids.all { controller.statuses.value[it]?.playsOffline == true }
                }
                val first = songs.getJSONObject(0)
                val explicitId = first.getString("id")
                val beforePath = localFile(checkNotNull(runBlocking { controller.localPlan(explicitId) })).path
                val beforeBytes = File(beforePath).readBytes()
                // Production explicit action must upgrade the existing automatic row, with no refetch.
                runBlocking { controller.download(listOf(AndroidDownloadItem(explicitId, container(first.getString("suffix")), first.optLong("duration") * 1000))) }
                check(library.remove(target))
                await("automatic-only files to disappear after removing the album", 60_000) {
                    ids.filterNot { it == explicitId }.all { runBlocking { controller.localPlan(it) } == null }
                }
                check(File(beforePath).readBytes().contentEquals(beforeBytes)) { "Removing My Library deleted the explicit file" }
                turnAutomaticOffInSettings()
                await("Automatic Downloads off") { !controller.offlineSettings.value.automaticDownloadsEnabled }
                check(File(beforePath).readBytes().contentEquals(beforeBytes)) { "Turning off Automatic Downloads deleted the explicit file" }
                check(runBlocking { controller.localPlan(explicitId) } != null)
                runBlocking { controller.remove(listOf(explicitId)) }
                println("OFFLINE LIBRARY AUTO OBSERVED album-add->worker->local songs=${ids.size} remove->automatic-files-gone explicit-survives-remove=true explicit-survives-off=true")
            val canary = probe.songId(DisposableServerProbe.CANARY_TITLE)
            check(runBlocking { controller.localPlan(canary) } == null) { "Cache proof requires a song never downloaded or cached" }
            PlaybackObserver(context, removeAccountDataOnClose = false).use { observer ->
                    observer.bind()
                    observer.onController { it.setAutoplay(false); it.playSong(account.id, canary, DisposableServerProbe.CANARY_TITLE) }
                    try { requireMediaTimeAdvances(observer, "first-stream") } catch (failure: Throwable) {
                        println("OFFLINE LIBRARY FIRST STREAM FAILURE state=${observer.state()}")
                        throw failure
                    }
                    check(!observer.state().playingDownload) { "First play must stream the actual server response" }
                    await("the actual stream to become a validated cache file", 90_000) {
                        runBlocking { controller.localPlan(canary) } != null
                    }
                    val mediaRequests = relay.connections().flatMap { tap ->
                        Regex("(?m)^GET ([^ ]+) HTTP/[^\r\n]+").findAll(tap.sent().toString(Charsets.US_ASCII)).map { match ->
                            java.net.URI(match.groupValues[1])
                        }.toList()
                    }.filter { uri -> uri.path.contains("/stream") && uri.rawQuery.orEmpty().split('&').any { parameter ->
                        val pair = parameter.split('=', limit = 2)
                        pair.size == 2 && pair[0] == "id" && java.net.URLDecoder.decode(pair[1], "UTF-8") == canary
                    } }
                    check(mediaRequests.size == 1) { "Expected one Media3 stream request without a cache refetch; observed ${mediaRequests.size}" }
                    observer.stopPlayback()
                    val cached = checkNotNull(runBlocking { controller.localPlan(canary) })
                    val bytes = localFile(cached).readBytes()
                    check(bytes.isNotEmpty())
                    val libraryState = java.util.concurrent.atomic.AtomicReference<com.legitimateapps.dulcet.core.AndroidMyLibraryPublication>()
                    val membership = library.observe(com.legitimateapps.dulcet.core.AndroidMyLibrarySort.RecentlyAdded) { libraryState.set(it) }
                    val uncached = ids.first()
                    check(library.add(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, canary)))
                    check(library.add(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, uncached)))
                    await("cached and uncached songs in My Library") {
                        libraryState.get()?.songs?.let { songs -> listOf(canary, uncached).all { id -> songs.any { it.rawId == id && !it.pending } } } == true
                    }
                    // A reconnect joins a whole running sequence, including reads after its
                    // identity check. Settle it before cutting this proof's next fresh request.
                    val reader = checkNotNull(AndroidLibraryReader.currentFor(account.id))
                    val settled = CountDownLatch(1)
                    var verified = false
                    reader.reconnect { connection -> verified = connection.error == null; settled.countDown() }
                    check(settled.await(45, TimeUnit.SECONDS) && verified) {
                        "The online reader did not complete its reconnect before the cut"
                    }
                    relay.cut()
                    val forwarded = relay.forwardedBytes.get()
                    val cutMarker = System.nanoTime()
                    // A real failed reconnect establishes reader offline while Wi-Fi stays present.
                    val offline = CountDownLatch(1)
                    var unreachable = false
                    reader.reconnect { connection -> unreachable = connection.error == com.legitimateapps.dulcet.core.DomainError.Transport.Unreachable; offline.countDown() }
                    check(offline.await(45, TimeUnit.SECONDS) && unreachable) { "The cut server did not produce an actual unreachable outcome" }
                    check(relay.refusedConnections.get() > 0) {
                        "The failed reconnect never reached the cut relay:\n${relay.connectionDiagnostics(cutMarker)}"
                    }
                    await("offline My Library playable and unavailable states") {
                        val songs = libraryState.get()?.songs.orEmpty()
                        songs.any { it.rawId == canary && it.playability == com.legitimateapps.dulcet.core.AndroidLibraryPlayability.Downloaded } &&
                            songs.any { it.rawId == uncached && it.playability == com.legitimateapps.dulcet.core.AndroidLibraryPlayability.UnavailableOffline }
                    }
                    observer.onController { it.playQueue(listOf(
                        com.legitimateapps.dulcet.core.AndroidTrack(account.id, canary, DisposableServerProbe.CANARY_TITLE),
                        com.legitimateapps.dulcet.core.AndroidTrack(account.id, uncached, "Unavailable offline"),
                    ), 0, com.legitimateapps.dulcet.core.AndroidQueueSource.Library, "Offline My Library") }
                    val position = requireMediaTimeAdvances(observer, "cached-offline")
                    check(observer.state().playingDownload) { "Offline playback did not use the cached local plan" }
                    check(observer.state().queue.none { it.track.rawId == uncached }) { "Offline queue retained an unavailable item" }
                    check(relay.forwardedBytes.get() == forwarded) { "Offline replay fetched server bytes" }
                    check(localFile(cached).readBytes().contentEquals(bytes))
                    observer.stopPlayback()
                    // Stop releases current protection; Clear Cache acts only on disposable streamed bytes.
                    runBlocking { controller.clearStreamCache() }
                    check(runBlocking { controller.localPlan(canary) } == null)
                    membership.close()
                    probe.call("removeFromDulcetLibrary", mapOf("id" to canary))
                    probe.call("removeFromDulcetLibrary", mapOf("id" to uncached))
                    println("OFFLINE LIBRARY CACHE OBSERVED first-play=remote media-requests=1 cached-bytes=${bytes.size} replay=local media-ms=$position server-bytes-during-replay=0 unavailable-queue-entry=false clear-cache->file-gone=true")
                }
            }
        } finally {
            relay.close()
            runBlocking { CoreAccountDataGateway(context).removeAccountData(account.id) }
            AndroidAccountCredentialStore(context).delete()
        }
    }

    private fun localFile(plan: com.legitimateapps.dulcet.core.LocalPlaybackPlan): File =
        File(context.noBackupFilesDir, "downloads").listFiles().orEmpty()
            .single { it.isFile && it.name.startsWith(plan.downloadId.value + ".") && !it.name.endsWith(".partial") }

    private fun container(value: String): AudioContainer = when (value.lowercase()) {
        "flac" -> AudioContainer.Flac
        "mp3" -> AudioContainer.Mp3
        "wav" -> AudioContainer.Wav
        "m4a", "mp4" -> AudioContainer.Mp4
        "ogg", "opus" -> AudioContainer.Ogg
        else -> error("Unsupported fixture container $value")
    }
}
