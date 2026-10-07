package com.legitimateapps.dulcet.library

import android.os.Handler
import android.os.Looper
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidServerContact
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Whether the person has chosen, in this process, to contact the saved account's server (spec §13.1,
 * CONF-10b). A launch into a saved account sends nothing on the person's behalf until they choose
 * Connect on the form or Reconnect on a screen: until then the library paints what this device has
 * seen and says the account is saved and not connected, and playback ([contact]) restores its queue
 * paused, plays only downloads and keeps plays in the outbox. A session connected in this process
 * follows reachability and returns to the foreground as §16.11 and §16.14 say.
 *
 * The choice belongs to the process's reader, which lives exactly as long as the process's connection
 * to the account: a new process has no reader, so nothing it makes has been chosen, and a reader
 * closed for a sign-out or an account change takes its choice with it.
 */
public object SavedAccountConnection {
    private val lock = Any()

    /** The account the person connected on the form, until a session claims it for the reader. */
    private var chosenAccountId: String? = null

    private val connectedReaders: MutableSet<AndroidLibraryReader> =
        Collections.newSetFromMap(WeakHashMap())

    /** How many open sessions of each account are holding for Reconnect. */
    private val waitingCounts = mutableMapOf<String, Int>()

    private val waiting = MutableStateFlow<Set<String>>(emptySet())

    /** Bumped by every choice to connect, so an observer re-reads [isConnected]. */
    private val choices = MutableStateFlow(0L)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The accounts a library session is holding for Reconnect. Everything else that would contact
     * their server — cover art — reads only what this device has kept while an account is here.
     */
    public val waitingForReconnect: StateFlow<Set<String>> = waiting.asStateFlow()

    /** One more session of [accountId] holds for Reconnect; each is released by its own [stopWaiting]. */
    internal fun waitFor(accountId: String) {
        synchronized(lock) {
            waitingCounts[accountId] = (waitingCounts[accountId] ?: 0) + 1
            waiting.value = waitingCounts.keys.toSet()
        }
    }

    /** One session of [accountId] stopped holding: the account is held while any other still is. */
    internal fun stopWaiting(accountId: String) {
        synchronized(lock) {
            val left = (waitingCounts[accountId] ?: return) - 1
            if (left > 0) waitingCounts[accountId] = left else waitingCounts -= accountId
            waiting.value = waitingCounts.keys.toSet()
        }
    }

    /**
     * The person connected [accountId] on the form, and it was saved: the reader its screens use next
     * reads the server, and playback may contact it. Called by the one connect sequence every Android
     * shell uses.
     */
    public fun connectedOnTheForm(accountId: String) {
        synchronized(lock) { chosenAccountId = accountId }
        changed()
    }

    /** Whether [reader], the process's reader for [accountId], may contact the server. */
    internal fun isConnected(reader: AndroidLibraryReader, accountId: String): Boolean = synchronized(lock) {
        if (chosenAccountId == accountId) {
            connectedReaders += reader
            chosenAccountId = null
        }
        reader in connectedReaders
    }

    /**
     * Whether the person has chosen, in this process, to contact [accountId]'s server: on the form,
     * not yet claimed by a reader, or by Reconnect on the process's current reader for it.
     */
    public fun isConnected(accountId: String): Boolean = synchronized(lock) {
        chosenAccountId == accountId ||
            AndroidLibraryReader.currentFor(accountId)?.let { it in connectedReaders } == true
    }

    /** The person chose Reconnect: [reader] reads the server from now on, in every screen host. */
    internal fun reconnectChosen(reader: AndroidLibraryReader) {
        synchronized(lock) { connectedReaders += reader }
        changed()
    }

    /**
     * Calls [listener] on the main thread after every choice to connect, whichever screen host made
     * it; closing the result stops it.
     */
    internal fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Playback's view of [accountId] (spec §13.1): open once the person has chosen to connect it. */
    public fun contact(accountId: String): AndroidServerContact = object : AndroidServerContact {
        override fun isOpen(): Boolean = isConnected(accountId)
        override suspend fun awaitOpen() {
            choices.first { isConnected(accountId) }
        }
    }

    private fun changed() {
        choices.update { it + 1 }
        for (listener in listeners) {
            if (Looper.myLooper() == Looper.getMainLooper()) listener() else main.post(listener)
        }
    }
}
