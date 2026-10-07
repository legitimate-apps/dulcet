package com.legitimateapps.dulcet.library

import com.legitimateapps.dulcet.core.AndroidLibraryReader
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether the person has chosen, in this process, to contact the saved account's server (spec §13.1,
 * CONF-10b). A launch into a saved account sends nothing until they choose Connect on the form or
 * Reconnect on a screen: until then the library paints what this device has seen and says the account
 * is saved and not connected. A session connected in this process follows reachability and returns to
 * the foreground as §16.11 and §16.14 say.
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

    private val waiting = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The accounts a library session is holding for Reconnect. Everything else that would contact
     * their server — cover art — reads only what this device has kept while an account is here.
     */
    public val waitingForReconnect: StateFlow<Set<String>> = waiting.asStateFlow()

    internal fun waitFor(accountId: String) = waiting.update { it + accountId }

    internal fun stopWaiting(accountId: String) = waiting.update { it - accountId }

    /**
     * The person connected [accountId] on the form, and it was saved: the reader its screens use next
     * reads the server. Called by the one connect sequence every Android shell uses.
     */
    public fun connectedOnTheForm(accountId: String) {
        synchronized(lock) { chosenAccountId = accountId }
    }

    /** Whether [reader], the process's reader for [accountId], may contact the server. */
    internal fun isConnected(reader: AndroidLibraryReader, accountId: String): Boolean = synchronized(lock) {
        if (chosenAccountId == accountId) {
            connectedReaders += reader
            chosenAccountId = null
        }
        reader in connectedReaders
    }

    /** The person chose Reconnect: [reader] reads the server from now on, in every screen host. */
    internal fun reconnectChosen(reader: AndroidLibraryReader) {
        synchronized(lock) { connectedReaders += reader }
    }
}
