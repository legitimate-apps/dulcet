package com.legitimateapps.dulcet.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryHomeRow
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.shared.R

/*
 * What every shell's library needs from Compose, whatever its design system: the session's lifecycle,
 * windows opened while composing and closed when they leave, and the shared words for rows and cards.
 * The phone draws them in `PhoneLibrary.kt`, the TV in its own `TvLibrary.kt`.
 */

/** State observations carry no account data: catalog ids and freshness, from the real session. */
public val LibraryObservation: SemanticsPropertyKey<LibraryObservationState> = SemanticsPropertyKey("LibraryObservation")

/**
 * Whether the host is in the foreground NOW — started, at least — for a reader created at this
 * composition (§16.14). Only the value at the reader's creation matters: [LibraryLifecycle] reports
 * every later change.
 */
@Composable
public fun hostInForeground(): Boolean =
    LocalLifecycleOwner.current.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

/** Starts and stops [session] with the host's lifecycle and closes it when it leaves composition. */
@Composable
public fun LibraryLifecycle(session: LibrarySession) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.start()
                Lifecycle.Event.ON_STOP -> session.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.close()
        }
    }
}

/**
 * The home screen's rows, opened WHILE COMPOSING — so each paints from the cache in its first frame,
 * before its own read is issued — and closed when the screen leaves composition, so the reader's
 * visible screen is exactly what is shown.
 */
@Composable
public fun rememberHomeRows(session: LibrarySession): List<LibraryHomeRowSurface> =
    remember(session) { Opened(session.openHome()) { rows -> rows.forEach { it.surface.close() } } }.value

/** One surface, opened while composing and closed when it leaves composition (see [rememberHomeRows]). */
@Composable
public fun rememberSurface(session: LibrarySession, key: Any?, open: LibrarySession.() -> LibrarySurface): LibrarySurface =
    remember(session, key) { Opened(session.open()) { it.close() } }.value

private class Opened<T>(val value: T, private val close: (T) -> Unit) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = close(value)
    override fun onAbandoned() = close(value)
}

/** The title of a home row, in the shared words. */
public fun AndroidLibraryHomeRow.titleResource(): Int = when (this) {
    AndroidLibraryHomeRow.Favourites -> R.string.library_home_favourites
    is AndroidLibraryHomeRow.Albums -> when (type) {
        AndroidAlbumListType.Recent -> R.string.library_home_recent
        AndroidAlbumListType.Frequent -> R.string.library_home_frequent
        else -> R.string.library_home_newest
    }
}

/** A card's title for any kind of row. */
public fun AndroidLibraryItem.displayTitle(): String = when (this) {
    is AndroidLibraryItem.Album -> title
    is AndroidLibraryItem.Artist -> name
    is AndroidLibraryItem.Track -> title.orEmpty()
    is AndroidLibraryItem.Playlist -> name
    is AndroidLibraryItem.Genre -> rawId
}

/** A card's second line: an album's artist, a track's artist or album. */
public fun AndroidLibraryItem.subtitle(): String? = when (this) {
    is AndroidLibraryItem.Album -> artistName
    is AndroidLibraryItem.Track -> artistName ?: albumTitle
    else -> null
}
