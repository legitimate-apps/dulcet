package com.legitimateapps.dulcet.library

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.legitimateapps.dulcet.search.SearchAccount

/** State observations carry no account data and are emitted by the actual session paths. */
public val LibraryObservation: SemanticsPropertyKey<LibrarySessionState> = SemanticsPropertyKey("LibraryObservation")

@Composable
public fun LibraryEntry(account: SearchAccount, search: @Composable () -> Unit) {
    var showingLibrary by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        if (showingLibrary) LibraryScreen(account) else search()
        BasicText(if (showingLibrary) "Search" else "Library",
            Modifier.align(Alignment.TopEnd).testTag("library.open")
                .background(Color.White).clickable { showingLibrary = !showingLibrary }.padding(16.dp))
    }
}

@Composable
private fun LibraryScreen(account: SearchAccount) {
    val context = LocalContext.current
    val session = remember(account.providerInstanceId) { LibrarySession(context, account) }
    val state by session.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        session.openSaved()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.resume()
                Lifecycle.Event.ON_STOP -> session.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.close() }
    }
    Column(Modifier.fillMaxSize().testTag("library.surface").semantics { this[LibraryObservation] = state }) {
        BasicText("Saved library", Modifier.padding(16.dp))
        BasicText(if (state.syncing) "Syncing…" else "Sync library",
            Modifier.testTag("library.sync").clickable(enabled = !state.syncing) { session.synchronize() }.padding(16.dp))
        if (state.failed) BasicText("Unable to sync. Saved music remains available.")
        if (state.library?.rows?.isEmpty() == true) BasicText("No saved music yet. Sync your library to browse offline.")
        LazyColumn(Modifier.testTag("library.rows")) {
            itemsIndexed(state.library?.rows.orEmpty()) { index, row ->
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().testTag("library.card.$index")
                    .onFocusChanged { focused = it.isFocused }.focusable()
                    .background(if (focused) Color.LightGray else Color.White).padding(16.dp)) {
                    BasicText(row.title, Modifier.testTag("library.row.$index"))
                    if (row.credits.isNotEmpty()) BasicText(row.credits.joinToString { it.name })
                }
            }
        }
    }
}
