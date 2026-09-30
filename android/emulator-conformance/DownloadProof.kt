package com.legitimateapps.dulcet.emulator

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
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
 * CONF-52: with every network down — proved by the probe's own request failing — the production
 * play entry plays that song from the file: media time advances, the controller reports it is
 * playing a download, and the file is still byte-identical to the server's original.
 *
 * Afterwards the account's data is removed through the sign-out gateway, which must leave no
 * download file and no download task behind (spec §14.7).
 */
class DownloadProof(private val context: Context, private val probe: DisposableServerProbe) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // The app-private download root (AndroidAccountData.downloadRootFor).
    private val root = File(context.noBackupFilesDir, "downloads")

    /** CONF-51: a download through the production controller and WorkManager, validated and promoted. */
    fun conf51() = session { controller, _, rawId, container, duration, original ->
        println("ANDROID DOWNLOAD CONF-51 OBSERVED " + proveDownload(controller, rawId, container, duration, original))
    }

    /** CONF-52: the downloaded song plays from the identical file with every network down. */
    fun conf52(startPlayback: (StoredAccount, String) -> AutoCloseable) =
        session { controller, account, rawId, container, duration, original ->
            proveDownload(controller, rawId, container, duration, original)
            println("ANDROID DOWNLOAD CONF-52 OBSERVED " + proveOfflinePlayback(account, rawId, original, startPlayback))
        }

    /**
     * Connects the saved account, runs [proof] on its download controller, then removes the account
     * through the sign-out gateway and requires that no download file or task survives it.
     */
    private fun session(proof: (AndroidDownloadController, StoredAccount, String, AudioContainer, Long, ByteArray) -> Unit) {
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
        connectSavedAccount(context, probe)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        try {
            val controller = checkNotNull(AndroidDownloads.controller(context)) { "No download controller for the saved account" }
            check(runBlocking { controller.awaitReconciled() }) { "Download reconciliation did not run" }
            proof(controller, account, rawId, container, song.optLong("duration") * 1000, original)
        } finally {
            setAirplaneMode(false)
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
        // The file came through a WorkManager task, not a call made here.
        val work = WorkManager.getInstance(context).getWorkInfosByTag("dulcet.download.account:" +
            controller.providerInstanceId).get()
        val succeeded = work.count { it.state == WorkInfo.State.SUCCEEDED && it.tags.any { tag -> tag.startsWith("dulcet.download.id:") } }
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

    private fun proveOfflinePlayback(account: StoredAccount, rawId: String, original: ByteArray,
                                     startPlayback: (StoredAccount, String) -> AutoCloseable): String {
        setAirplaneMode(true)
        await("the disposable server to be unreachable", timeoutMillis = 30_000) { runCatching { probe.call("ping") }.isFailure }
        PlaybackObserver(context).use { observer ->
            startPlayback(account, rawId).use {
                observer.bind()
                val position = requireMediaTimeAdvances(observer, "offline")
                check(observer.state().playingDownload) { "Playback advanced, but not from the download" }
                check(runCatching { probe.call("ping") }.isFailure) { "The server became reachable during the offline proof" }
                val file = root.walkTopDown().single { it.isFile }
                check(file.readBytes().contentEquals(original)) { "The played file is not the server's original" }
                observer.stopPlayback()
                observer.unbind()
                return "network=down(probe-refused) playing-download=true media-ms=$position bytes-identical=true"
            }
        }
    }

    /**
     * Every radio down, or back up. Airplane mode alone can leave Wi-Fi on where the person chose
     * that, so Wi-Fi and mobile data are switched too; the proof's own control — the probe failing
     * to reach the server — decides whether the network is actually gone.
     */
    private fun setAirplaneMode(enabled: Boolean) {
        val commands = if (enabled) listOf("cmd connectivity airplane-mode enable", "svc wifi disable", "svc data disable")
            else listOf("svc wifi enable", "svc data enable", "cmd connectivity airplane-mode disable")
        for (command in commands) instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }
        if (!enabled) await("the disposable server to answer again", timeoutMillis = 60_000) {
            runCatching { probe.call("ping") }.isSuccess
        }
    }

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
