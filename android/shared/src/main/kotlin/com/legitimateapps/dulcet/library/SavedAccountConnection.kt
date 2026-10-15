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
 * Server-contact readiness shared by the process reader, library hosts and restored playback.
 * A saved account paints from the device before its first foreground host opens this gate and
 * starts the ordinary reconnect. Sign-out or account replacement closes the reader, taking its
 * readiness with it. The gate controls contact, not whether a reconnect has succeeded.
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
     * Whether a foreground host has opened contact with [accountId]'s server: on the form,
     * not yet claimed by a reader, or by Reconnect on the process's current reader for it.
     */
    public fun isConnected(accountId: String): Boolean = synchronized(lock) {
        chosenAccountId == accountId ||
            AndroidLibraryReader.currentFor(accountId)?.let { it in connectedReaders } == true
    }

    /** A foreground host starts reconnecting: [reader] reads the server from now on, in every screen host. */
    internal fun reconnectChosen(reader: AndroidLibraryReader) {
        synchronized(lock) { connectedReaders += reader }
        changed()
    }

    /**
     * Calls [listener] on the main thread after contact opens, whichever screen host made
     * it; closing the result stops it.
     */
    internal fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Playback's view of [accountId]: opens with the saved account's automatic reconnect. */
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
