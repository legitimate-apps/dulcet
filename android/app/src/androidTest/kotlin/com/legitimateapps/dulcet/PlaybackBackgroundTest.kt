package com.legitimateapps.dulcet

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.playback.PlaybackActivity
import com.legitimateapps.dulcet.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Device proof with a disposable loopback protocol fixture and real PCM decoding. */
@RunWith(AndroidJUnit4::class)
class PlaybackBackgroundTest {
    @Test fun localPlaybackRetainsForegroundOwnershipAndProgressAfterHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val credentials = AndroidAccountCredentialStore(context)
        check(credentials.load() == null) { "This test requires an empty disposable app installation" }
        LoopbackAudio().use { fixture ->
            val account = credentials.save("Fixture", fixture.url, "USER_CANARY", "PASSWORD_CANARY", true)
            val connected = CountDownLatch(1)
            var service: PlaybackService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = (binder as PlaybackService.LocalBinder).service
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            var observerBound = false
            try {
                val intent = Intent(context, PlaybackActivity::class.java)
                    .putExtra(PlaybackActivity.PROVIDER, account.id)
                    .putExtra(PlaybackActivity.SONG, "fixture-song")
                    .putExtra(PlaybackActivity.TITLE, "Background fixture")
                ActivityScenario.launch<PlaybackActivity>(intent).use { activity ->
                    observerBound = context.bindService(Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.LOCAL_BIND), connection, Context.BIND_AUTO_CREATE)
                    assertTrue(observerBound)
                    assertTrue(connected.await(15, TimeUnit.SECONDS))
                    fun position(): Long {
                        var value = -1L
                        instrumentation.runOnMainSync { value = service?.playback?.state?.value?.positionMilliseconds ?: -1L }
                        return value
                    }
                    await { position() >= 1500 }
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    await { notifications.activeNotifications.any {
                        it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                    } }
                    // Remove the test's local observer before Home. Only Media3 foreground
                    // ownership can retain this service after the activity drops its own binding.
                    context.unbindService(connection); observerBound = false
                    val before = position()
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                    await { activity.state == Lifecycle.State.CREATED }
                    await { position() >= before + 2000 }
                    assertTrue(notifications.activeNotifications.any {
                        it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                    })
                    val after = position()
                    instrumentation.runOnMainSync {
                        assertNotNull(service!!.playback)
                        service!!.playback!!.stop()
                    }
                    println("OBSERVED local-playback=true decoded-position-before=$before decoded-position-after=$after home-stopped-activity=true observer-unbound=true foreground-notification=true")
                }
            } finally {
                if (observerBound) context.unbindService(connection)
                instrumentation.runOnMainSync { service?.playback?.stop() }
                context.stopService(Intent(context, PlaybackService::class.java))
                credentials.delete()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Playback lifecycle condition timed out" }
            Thread.sleep(100)
        }
    }

    private class LoopbackAudio : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        private val executor = Executors.newCachedThreadPool()
        private val audio = ByteBuffer.allocate(44 + 8000 * 2 * 120).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(capacity() - 44)
            while (hasRemaining()) putShort(0)
        }.array()
        init {
            executor.submit {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    executor.submit {
                        socket.use {
                            it.soTimeout = 10000
                            val reader = it.getInputStream().bufferedReader()
                            val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: return@submit
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val stream = path == "/rest/stream.view"
                            val body = if (stream) audio else (
                                if (path == "/rest/getSong.view")
                                    """{"subsonic-response":{"status":"ok","song":{"id":"fixture-song","suffix":"wav","duration":120}}}"""
                                else """{"subsonic-response":{"status":"ok"}}""").toByteArray()
                            val type = if (stream) "audio/wav" else "application/json"
                            try {
                                it.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(body); flush()
                                }
                            } catch (_: java.io.IOException) { /* Stop may cancel an owned socket. */ }
                        }
                    }
                }
            }
        }
        override fun close() {
            server.close(); executor.shutdown()
            check(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }
}
