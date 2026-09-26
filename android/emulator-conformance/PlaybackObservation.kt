package com.legitimateapps.dulcet.emulator

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AccountConnectOutcome
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.connectAndSaveAccount
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Returns once the system has delivered every broadcast queued before the call — above all this
 * installation's own `PACKAGE_ADDED`.
 *
 * The notification service answers `PACKAGE_ADDED` by cancelling every notification the package
 * holds, foreground-service notifications included, and the service stays in the foreground
 * without one. The instrumentation installs the app immediately before the test, and on a freshly
 * booted emulator that broadcast can still be queued many seconds into the test, so a test that
 * reads the notification after it lands is measuring the installation, not the app. Waiting here,
 * before anything plays, leaves every later notification check observing only the app.
 *
 * The command's own "passed" line is required, so a device that ignored the command cannot read
 * as one whose queue was drained. Returns how long the wait took.
 */
fun awaitQueuedBroadcastsDelivered(timeoutSeconds: Long = 120): Long {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val started = SystemClock.elapsedRealtime()
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val output = try {
        executor.submit<String> {
            automation.executeShellCommand("am wait-for-broadcast-barrier --flush-broadcast-loopers").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
            }
        }.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (timeout: java.util.concurrent.TimeoutException) {
        error("Queued broadcasts were still undelivered after ${timeoutSeconds}s")
    } finally {
        executor.shutdownNow()
    }
    check("Test barrier passed" in output) { "The broadcast barrier did not report passing: ${output.trim().take(400)}" }
    val elapsed = SystemClock.elapsedRealtime() - started
    println("QUEUED BROADCASTS DELIVERED after ${elapsed}ms")
    return elapsed
}

/** Connects the app's saved account through the production connect sequence, as its connect screen does. */
fun connectSavedAccount(context: Context, probe: DisposableServerProbe) {
    val store = AndroidAccountCredentialStore(context)
    check(store.load() == null) { "Emulator playback tests require an app installation with no saved account" }
    val outcome = runBlocking {
        connectAndSaveAccount(AccountConnectionRequest(probe.baseUrl, DisposableServerProbe.USER,
            DisposableServerProbe.PASSWORD, allowLocalHttp = true), AccountConnector()::connect, store)
    }
    check(outcome is AccountConnectOutcome.Connected) { "The disposable server refused the production connect: $outcome" }
}

/**
 * Reads the playback service's published state from outside the UI. Unbound before the app is
 * backgrounded, so nothing here can be what keeps the service alive.
 */
class PlaybackObserver(private val context: Context) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var service: PlaybackService? = null
    private var bound = false
    private val connected = CountDownLatch(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlaybackService.LocalBinder).service; connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    fun bind() {
        bound = context.bindService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),
            connection, Context.BIND_AUTO_CREATE)
        check(bound && connected.await(15, TimeUnit.SECONDS)) { "The playback service did not bind" }
    }

    fun state(): AndroidPlaybackState {
        var value: AndroidPlaybackState? = null
        instrumentation.runOnMainSync { value = service?.playback?.state?.value }
        return checkNotNull(value) { "The playback service has no controller" }
    }

    /** Keeps the service reference but drops the binding, so only foreground ownership can keep it alive. */
    fun unbind() { if (bound) { context.unbindService(connection); bound = false } }

    fun foregroundNotificationPosted(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }

    /**
     * Fails with what the system says about the service, its notification and its session, so a
     * red run names whether the service left the foreground, the notification was removed, or the
     * session stopped playing — three different defects that read identically as "no notification".
     */
    fun requireForegroundNotification(label: String) {
        if (foregroundNotificationPosted()) return
        val state = state()
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }
        val service = shell("dumpsys activity services ${context.packageName}").lines()
            .filter { "ServiceRecord" in it || "isForeground" in it || "startRequested" in it }.joinToString(" | ") { it.trim() }
        val notification = shell("dumpsys notification --noredact").lines()
            .filter { "pkg=${context.packageName}" in it }.joinToString(" | ") { it.trim().take(200) }
        val session = shell("dumpsys media_session").lines().dropWhile { "package=${context.packageName}" !in it }
            .take(14).filter { "state=" in it || "active=" in it }.joinToString(" | ") { it.trim().take(160) }
        error("$label: no foreground notification. app phase=${state.phase} position=${state.positionMilliseconds} " +
            "wanted=${state.playWhenReady} session=${state.hasSession} || service: $service || notifications: " +
            "${notification.ifEmpty { "none" }} || media session: $session")
    }

    fun stopPlayback() { instrumentation.runOnMainSync { service?.playback?.stop() } }

    /** Runs [block] on the main thread against the service's production controller. */
    fun onController(block: (com.legitimateapps.dulcet.core.AndroidPlaybackController) -> Unit) {
        var ran = false
        instrumentation.runOnMainSync { service?.playback?.let { block(it); ran = true } }
        check(ran) { "The playback service has no controller" }
    }

    override fun close() {
        unbind()
        stopPlayback()
        context.stopService(Intent(context, PlaybackService::class.java))
        AndroidAccountCredentialStore(context).delete()
    }
}

fun await(what: String, timeoutMillis: Long = 60_000, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (!condition()) {
        check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for: $what" }
        Thread.sleep(200)
    }
}

/**
 * Media time, sampled twice by the app's own published state, must advance under a progressing
 * phase. Returns the later position.
 */
fun requireMediaTimeAdvances(observer: PlaybackObserver, label: String): Long {
    await("$label: media time progressing") {
        observer.state().let { it.phase == "Progressing" && it.positionMilliseconds > 0 }
    }
    val first = observer.state().positionMilliseconds
    Thread.sleep(2_500)
    val second = observer.state()
    check(second.positionMilliseconds >= first + 1_500) {
        "$label: media time did not advance (${first}ms then ${second.positionMilliseconds}ms, phase ${second.phase})"
    }
    return second.positionMilliseconds
}

/**
 * The server's play record must rise by exactly one, and stay there after the queue has ended —
 * a second submission would be a duplicate play, and none would be a lost one.
 */
fun requireExactlyOneServerPlay(probe: DisposableServerProbe, rawId: String, before: Int, observer: PlaybackObserver) {
    await("server play count ${before + 1}", timeoutMillis = 120_000) { probe.playCount(rawId) >= before + 1 }
    await("the one-song queue to end", timeoutMillis = 90_000) { !observer.state().hasSession }
    Thread.sleep(5_000)
    val after = probe.playCount(rawId)
    check(after == before + 1) { "Expected exactly one server play: before=$before after=$after" }
}
