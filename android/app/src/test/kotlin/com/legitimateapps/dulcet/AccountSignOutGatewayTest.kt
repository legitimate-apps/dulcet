package com.legitimateapps.dulcet

import android.app.Application
import android.os.Looper
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidLibraryReaderAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The changes half of §14.7 step 2 through the PRODUCTION gateway: a favourite queued offline is
 * counted by the account's real library reader and offered, and Send delivers it through that
 * reader's reconnect to a server, after which the account is signed out. Only playback and the
 * Keystore are replaced; the gateway, the reader, its session, its transport and the database are
 * the production ones. The server is a loopback socket that answers every request `ok` and records
 * what it was asked.
 */
@RunWith(RobolectricTestRunner::class)
class AccountSignOutGatewayTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun clean() {
        scope.cancel()
        AndroidLibraryReader.closeCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        application.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit().clear().commit()
        application.getSharedPreferences(AccountRemovalJournal.PREFERENCES_NAME, 0).edit().clear().commit()
        application.deleteDatabase("dulcet.db")
    }

    @Test fun aFavouriteQueuedOfflineIsOfferedAndSendDeliversItThroughTheReaderBeforeSigningOut() = OkServer().use { server ->
        val store = AndroidAccountCredentialStore(application, PlaintextCipher())
        val account = store.save("Fixture Server", server.url, "fixture-listener", "fixture-password", true)
        val signOut = AccountSignOut(scope, store, CoreAccountDataGateway(application), FakePlayback(mutableListOf()),
            AccountRemovalJournal(application)) {}

        // The favourite is made on the process's reader for the account — the one the gateway uses —
        // while it is offline, so it stays queued.
        val reader = AndroidLibraryReader.forAccount(application,
            AndroidLibraryReaderAccount(account.id, account.serverUrl, account.username, account.password, true), foreground = true)
        reader.setOnline(false)
        assertTrue(reader.setFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, ALBUM), true))
        var queued: Long? = -1
        reader.pendingChangeCount { queued = it }
        settle { queued != -1L }
        assertEquals(1L, queued, "control: the reader holds one queued change")
        assertTrue(server.stars().isEmpty(), "control: nothing was sent before the offer")

        signOut.request()
        settle { signOut.state.value != SignOutState.Checking }
        assertEquals(SignOutState.Offer(PendingChanges(emptySet(), 1), afterSend = false, hidesAccount = false), signOut.state.value,
            "The production gateway must offer the reader's queued change")

        signOut.send()
        settle { signOut.state.value == SignOutState.Idle || signOut.state.value is SignOutState.Offer }
        assertEquals(listOf(ALBUM), server.stars(), "Send must deliver the change through the reader's reconnect")
        assertIs<SignOutState.Idle>(signOut.state.value, "With the change delivered, nothing is owed and the account is signed out")
        assertNull(store.load())
        assertTrue(AccountRemovalJournal(application).pending().isEmpty(), "The removal finished")
    }

    /** Runs the main looper, which the sign-out's coroutines and the reader's completions use, until [done]. */
    private fun settle(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "never settled" }
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Answers every request with an `ok` envelope and records its endpoint and parameters. */
    private class OkServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        private val connections = Executors.newCachedThreadPool()

        init {
            connections.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    connections.submit { answer(client) }
                }
            }
        }

        fun stars(): List<String> = requests.filter { it.first == "star" }.mapNotNull { it.second["albumId"] ?: it.second["id"] }

        private fun answer(client: java.net.Socket) = client.use {
            client.soTimeout = 5_000
            val input = client.getInputStream().bufferedReader()
            val line = input.readLine()?.split(' ') ?: return@use
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
            val uri = URI(line[1])
            val parameters = listOfNotNull(uri.rawQuery, String(body).takeIf { it.isNotEmpty() })
                .flatMap { it.split('&') }.filter { it.isNotEmpty() }.associate {
                    val pair = it.split('=', limit = 2)
                    URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
            requests += uri.path.substringAfterLast('/').removeSuffix(".view") to parameters
            val envelope = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
            client.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${envelope.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray())
                write(envelope)
                flush()
            }
        }

        override fun close() {
            socket.close()
            connections.shutdownNow()
        }
    }

    private companion object {
        const val ALBUM = "album-queued-offline"
    }
}
