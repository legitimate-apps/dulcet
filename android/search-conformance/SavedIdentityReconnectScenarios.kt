package com.legitimateapps.dulcet.search.conformance

import android.os.Looper
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Production Reconnect handler used by phone and TV, real identity negotiation and durable outbox. */
class SavedIdentityReconnectScenarios : AutoCloseable {
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { serverType = "Navidrome" }
    private val account = SearchAccount("provider:reconnect-${UUID.randomUUID()}", server.url, "u", "p", true)
    private val session: LibrarySession
    private val reader: AndroidLibraryReader

    init {
        AndroidServerIdentity.seed(context, account.providerInstanceId, ServerIdentityPolicy.identity("Navidrome"))
        session = LibrarySession(context, account, foreground = false, restoredAccount = true)
        reader = AndroidLibraryReader.currentFor(account.providerInstanceId)!!
    }

    private fun await(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "$what; connection=${session.connection.value}; requests=${server.endpoints()}" }
            Thread.sleep(5)
        }
    }

    private fun reconnect() {
        val answers = session.observation.value.reconnectAnswers
        // Exactly the action installed on both shells' Reconnect controls. Never call accept().
        session.connectSavedAccount()
        await("Reconnect must finish") { session.observation.value.reconnectAnswers > answers }
    }

    private fun pending(): Long {
        var count: Long? = null
        reader.pendingChangeCount { count = it }
        await("Pending count must be delivered") { count != null }
        return count!!
    }

    private fun rejectAndQueue() {
        reconnect()
        assertIs<LibraryConnectionState.Online>(session.connection.value, "Control: the saved server connects")
        assertTrue(SavedAccountConnection.isConnected(account.providerInstanceId))
        assertNotNull(context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork,
            "The platform must still report reachability throughout identity rejection")
        server.serverType = "Gonic"
        reconnect()
        val failed = assertIs<LibraryConnectionState.Failed>(session.connection.value)
        assertEquals(DomainError.Protocol.ServerIdentityChanged, failed.error)
        assertFalse(SavedAccountConnection.isConnected(account.providerInstanceId))
        // Reachability still reports a network. Only identity rejection prevents this send.
        session.setFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, "original-song"), true)
        assertEquals(1L, pending())
        assertTrue(server.requests("star").isEmpty())
    }

    fun originalServerReturnsAndReconnectReopensContactAndFlushesPendingChanges() {
        rejectAndQueue()
        server.serverType = "Navidrome"
        reconnect()
        assertIs<LibraryConnectionState.Online>(session.connection.value,
            "A fresh negotiation of the saved identity must reopen the rejected account")
        assertTrue(SavedAccountConnection.isConnected(account.providerInstanceId))
        assertEquals(0L, pending(), "The accepted original server acknowledges the pending change")
        assertEquals(listOf("original-song"), server.requests("star").map { it["id"] })
    }

    fun replacementRemainsAndReconnectKeepsContactRejectedAndChangesPending() {
        rejectAndQueue()
        val before = server.endpoints().size
        reconnect()
        assertTrue(server.endpoints().size > before, "Reconnect must perform a new negotiation")
        assertEquals(DomainError.Protocol.ServerIdentityChanged,
            assertIs<LibraryConnectionState.Failed>(session.connection.value).error)
        assertFalse(SavedAccountConnection.isConnected(account.providerInstanceId))
        assertEquals(1L, pending())
        assertTrue(server.requests("star").isEmpty(), "Original IDs must never be sent to the replacement")
    }

    override fun close() {
        session.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        await("Reader close must finish") { closed }
        server.close()
        context.deleteDatabase("dulcet.db")
    }
}
