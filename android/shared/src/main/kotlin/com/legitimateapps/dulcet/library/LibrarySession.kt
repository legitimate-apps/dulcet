package com.legitimateapps.dulcet.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.legitimateapps.dulcet.core.AndroidCommittedLibrary
import com.legitimateapps.dulcet.core.AndroidLibraryDatabase
import com.legitimateapps.dulcet.core.LibrarySyncRequest
import com.legitimateapps.dulcet.core.LibrarySyncResponse
import com.legitimateapps.dulcet.search.SearchAccount
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

public data class LibrarySessionState(
    val library: AndroidCommittedLibrary? = null,
    val syncing: Boolean = false,
    val failed: Boolean = false,
    val syncStarts: Int = 0,
    val scheduledRefreshes: Int = 0,
    val savedReads: Int = 0,
)

/** Saved-account opens never establish connectivity. Only an explicit sync enables refresh. */
public class LibrarySession(context: Context, private val account: SearchAccount) : AutoCloseable {
    private val database = AndroidLibraryDatabase(context)
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(LibrarySessionState())
    public val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var active = true
    private var connected = false
    private val refresh = Runnable {
        if (active && connected) {
            mutableState.value = mutableState.value.copy(scheduledRefreshes = state.value.scheduledRefreshes + 1)
            Log.i("DulcetLibrary", "LIBRARY_REFRESH_FIRED monotonic=true")
            synchronize()
        }
    }

    public fun openSaved() {
        operation = scope.launch {
            try {
                val library = database.readCommitted(account.providerInstanceId)
                ensureActive()
                mutableState.value = state.value.copy(library = library, savedReads = state.value.savedReads + 1)
                Log.i("DulcetLibrary", "LIBRARY_SAVED_READ generation=${library.generation} syncStarts=${state.value.syncStarts}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = state.value.copy(failed = true) }
        }
    }

    public fun synchronize() {
        if (!active || state.value.syncing) return
        handler.removeCallbacks(refresh)
        operation?.cancel()
        mutableState.value = state.value.copy(syncing = true, failed = false, syncStarts = state.value.syncStarts + 1)
        operation = scope.launch {
            try {
                val response = database.synchronize(LibrarySyncRequest(account.providerInstanceId,
                    account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp))
                ensureActive()
                if (response is LibrarySyncResponse.Completed) {
                    val library = database.readCommitted(account.providerInstanceId)
                    ensureActive()
                    connected = true
                    mutableState.value = state.value.copy(library = library, syncing = false)
                    Log.i("DulcetLibrary", "LIBRARY_COMMITTED generation=${library.generation}")
                    schedule()
                } else {
                    connected = false
                    mutableState.value = state.value.copy(syncing = false, failed = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                connected = false
                mutableState.value = state.value.copy(syncing = false, failed = true)
            }
        }
    }

    private fun schedule() {
        handler.removeCallbacks(refresh)
        // Handler deadlines use uptimeMillis (monotonic), never the wall clock.
        if (active && connected) handler.postDelayed(refresh, REFRESH_MILLIS)
    }
    public fun pause() {
        active = false
        handler.removeCallbacks(refresh)
        operation?.cancel()
        mutableState.value = state.value.copy(syncing = false)
    }
    public fun resume() {
        active = true
        if (state.value.library == null && operation?.isActive != true) openSaved() else schedule()
    }
    override fun close() { pause(); scope.cancel() }
    public companion object { const val REFRESH_MILLIS: Long = 15 * 60 * 1000L }
}
