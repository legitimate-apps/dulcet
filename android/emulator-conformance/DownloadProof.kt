package com.legitimateapps.dulcet.emulator

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.CoreAccountDataGateway
import com.legitimateapps.dulcet.StoredAccount
import com.legitimateapps.dulcet.core.AndroidDownloadController
import com.legitimateapps.dulcet.core.AndroidDownloadItem
import com.legitimateapps.dulcet.core.AndroidDownloadState
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.downloads.AndroidDownloads
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * CONF-51 and CONF-52 on an emulator (spec §14.5), shared by the phone and TV proofs.
 *
 * CONF-51: the production controller, through a real WorkManager task, downloads the fixture song
 * from the disposable server; exactly one file is promoted, under the download root, with no
 * temporary file left, its length the exact length the row records, and its bytes those of the
 * server's original file read independently by the probe.
 *
 * CONF-52: with the server unreachable to the app, the production play entry plays that song from
 * the file: media time advances, the controller reports it is playing a download, and the file is
 * still byte-identical to the server's original. The app's server is a [ServerRelay] this process
 * owns, and cutting it is the network going away. Radios are not the instrument: CI's emulator
 * reaches the server over loopback or a host alias, which airplane mode leaves in place (OBSERVED:
 * the TV emulator's server still answered with airplane mode, Wi-Fi and data off). Before playback,
 * a request to the app's server URL must fail while the server itself still answers the probe.
 * Afterwards the relay must have forwarded nothing since the cut.
 *
 * Afterwards the account's data is removed through the sign-out gateway, which must leave no
 * download file and no download task behind (spec §14.7).
 */
class DownloadProof(private val context: Context, private val probe: DisposableServerProbe) {
    // The app-private download root (AndroidAccountData.downloadRootFor).
    private val root = File(context.noBackupFilesDir, "downloads")

    /** CONF-51: a download through the production controller and WorkManager, validated and promoted. */
    fun conf51() = session { controller, _, rawId, container, duration, original, relay ->
        val proved = proveDownload(controller, rawId, container, duration, original)
        // The app reached the server only through the relay, so the transfer crossed it.
        check(relay.forwardedBytes.get() >= original.size) {
            "The relay carried ${relay.forwardedBytes.get()} bytes, fewer than the ${original.size}-byte file"
        }
        println("ANDROID DOWNLOAD CONF-51 OBSERVED $proved relay-bytes=${relay.forwardedBytes.get()}")
    }

    /** CONF-52: the downloaded song plays from the identical file with the server unreachable to the app. */
    fun conf52(startPlayback: (StoredAccount, String) -> AutoCloseable) =
        session { controller, account, rawId, container, duration, original, relay ->
            proveDownload(controller, rawId, container, duration, original)
            println("ANDROID DOWNLOAD CONF-52 OBSERVED " + proveOfflinePlayback(account, rawId, original, relay, startPlayback))
        }

    /**
     * Connects the saved account, runs [proof] on its download controller, then removes the account
     * through the sign-out gateway and requires that no download file or task survives it.
     */
    private fun session(proof: (AndroidDownloadController, StoredAccount, String, AudioContainer, Long, ByteArray, ServerRelay) -> Unit) {
        val rawId = probe.songId(DisposableServerProbe.CANARY_TITLE)
        val song = probe.call("getSong", mapOf("id" to rawId)).getJSONObject("song")
        val container = when (song.getString("suffix").lowercase()) {
            "flac" -> AudioContainer.Flac
            "mp3" -> AudioContainer.Mp3
            "wav" -> AudioContainer.Wav
            "ogg", "opus" -> AudioContainer.Ogg
            "m4a", "mp4" -> AudioContainer.Mp4
            else -> error("The fixture song's format is not one this proof downloads: ${song.getString("suffix")}")
        }
        val original = probe.originalFile(rawId)
        check(original.size > 1024) { "The probe read too little of the original file to compare: ${original.size} bytes" }
        awaitQueuedBroadcastsDelivered()
        check(root.walkTopDown().none { it.isFile }) { "The installation already holds download files: nothing here would be this run's" }
        val relay = ServerRelay(probe.baseUrl)
        connectSavedAccount(context, probe, relay.url)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        check(account.serverUrl.trimEnd('/') == relay.url) { "The saved account does not name the relay: ${account.serverUrl}" }
        try {
            val controller = checkNotNull(AndroidDownloads.controller(context)) { "No download controller for the saved account" }
            check(runBlocking { controller.awaitReconciled() }) { "Download reconciliation did not run" }
            proof(controller, account, rawId, container, song.optLong("duration") * 1000, original, relay)
        } finally {
            relay.close()
            val removal = runCatching { runBlocking { CoreAccountDataGateway(context).removeAccountData(account.id) } }
            AndroidAccountCredentialStore(context).delete()
            removal.getOrThrow()
        }
        val left = root.walkTopDown().filter { it.isFile }.toList()
        check(left.isEmpty()) { "Removing the account left ${left.size} download file(s)" }
        val tasks = WorkManager.getInstance(context).getWorkInfosByTag("dulcet.download.account:${account.id}").get()
        check(tasks.all { it.state.isFinished }) { "Removing the account left a download task: ${tasks.map { it.state }}" }
        println("ANDROID DOWNLOAD SIGN-OUT OBSERVED files-left=0 tasks-unfinished=0")
    }

    private fun proveDownload(controller: AndroidDownloadController, rawId: String, container: AudioContainer,
                              durationMilliseconds: Long, original: ByteArray): String {
        runBlocking { controller.download(listOf(AndroidDownloadItem(rawId, container, durationMilliseconds))) }
        await("the download to be promoted", timeoutMillis = 180_000) {
            val status = controller.statuses.value[rawId]
            check(status?.needsAttention != true) { "The server refused the download: $status" }
            status?.state == AndroidDownloadState.Downloaded
        }
        // The file came through a WorkManager task, not a call made here. The row is promoted inside
        // the worker, which schedules the next download before it returns, so wait for the task to
        // finish rather than read its state the moment the row is promoted.
        fun downloadTasks() = WorkManager.getInstance(context).getWorkInfosByTag("dulcet.download.account:" +
            controller.providerInstanceId).get().filter { it.tags.any { tag -> tag.startsWith("dulcet.download.id:") } }
        await("the download task to finish", timeoutMillis = 30_000) { downloadTasks().all { it.state.isFinished } }
        val work = downloadTasks()
        val succeeded = work.count { it.state == WorkInfo.State.SUCCEEDED }
        check(succeeded >= 1) { "No download task ran to completion: ${work.map { it.state to it.tags }}" }
        val files = root.walkTopDown().filter { it.isFile }.toList()
        check(files.none { it.name.endsWith(".partial") }) { "A temporary file was left behind: $files" }
        check(files.size == 1) { "Expected exactly one promoted file, found ${files.map { it.name }}" }
        val promoted = files.single()
        check(promoted.parentFile == root) { "The promoted file is not at the download root: ${promoted.parent}" }
        val plan = checkNotNull(runBlocking { controller.localPlan(rawId) }) { "The download has no local plan" }
        check(promoted.length() == plan.exactByteLength) { "File length ${promoted.length()} != exact length ${plan.exactByteLength}" }
        check(promoted.readBytes().contentEquals(original)) { "The promoted file differs from the server's original" }
        return "files=1 temp-left=false bytes=${promoted.length()} sha256=${sha256(original).take(16)} worker-succeeded=$succeeded"
    }

    private fun proveOfflinePlayback(account: StoredAccount, rawId: String, original: ByteArray, relay: ServerRelay,
                                     startPlayback: (StoredAccount, String) -> AutoCloseable): String {
        relay.cut()
        // The app's server URL no longer answers; the server itself still does, so the cut, not a
        // stopped server, is what the app meets.
        check(!reaches(account.serverUrl)) { "The app's server URL still answers after the relay was cut" }
        check(relay.refusedConnections.get() >= 1) { "The control request never reached the cut relay; it proved nothing" }
        check(runCatching { probe.call("ping") }.isSuccess) { "The disposable server itself stopped answering" }
        val forwardedAtCut = relay.forwardedBytes.get()
        val refusedBefore = relay.refusedConnections.get()
        PlaybackObserver(context).use { observer ->
            startPlayback(account, rawId).use {
                observer.bind()
                val position = requireMediaTimeAdvances(observer, "offline")
                check(observer.state().playingDownload) { "Playback advanced, but not from the download" }
                // Read before any further control request: an HTTP client may retry a refused
                // request (MEASURED on the JVM: one failed GET arrived as two connections), so the
                // count is taken while only the app can have added to it.
                val appAttempts = relay.refusedConnections.get() - refusedBefore
                check(relay.forwardedBytes.get() == forwardedAtCut) {
                    "The relay forwarded ${relay.forwardedBytes.get() - forwardedAtCut} bytes to the server during offline playback"
                }
                check(!reaches(account.serverUrl)) { "The app's server URL became reachable during the offline proof" }
                val file = root.walkTopDown().single { it.isFile }
                check(file.readBytes().contentEquals(original)) { "The played file is not the server's original" }
                observer.stopPlayback()
                observer.unbind()
                return "server=unreachable(relay-cut, control-refused) server-requests-during-playback=0 " +
                    "app-attempts-refused=$appAttempts playing-download=true media-ms=$position bytes-identical=true"
            }
        }
    }

    /** True when a request to [serverUrl] gets any HTTP answer at all. */
    private fun reaches(serverUrl: String): Boolean = runCatching {
        val connection = URL("${serverUrl.trimEnd('/')}/rest/ping.view").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try { connection.responseCode } finally { connection.disconnect() }
    }.isSuccess

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** The server's original file for [rawId], read by the probe's own signed `download` request. */
fun DisposableServerProbe.originalFile(rawId: String): ByteArray {
    val connection = URL(signedUrl("download", mapOf("id" to rawId))).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    try {
        check(connection.responseCode == 200) { "The probe's download was refused: HTTP ${connection.responseCode}" }
        return connection.inputStream.readBytes()
    } finally { connection.disconnect() }
}
