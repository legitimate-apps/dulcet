package com.legitimateapps.dulcet.library

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
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
 * every later change. Reading the current state in composition is therefore deliberate: a value that
 * went stale would not be read again.
 */
@SuppressLint("LifecycleCurrentStateInComposition")
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

/**
 * The favourite state of [target] as this device knows it, for a surface no window backs (Now
 * Playing): null while unknown, before the first answer, and for a null [target].
 */
@Composable
public fun rememberWatchedFavourite(session: LibrarySession, target: AndroidLibraryEntity?): Boolean? {
    val state = remember(session, target) { mutableStateOf<Boolean?>(null) }
    DisposableEffect(session, target) {
        val watch = target?.let { session.watchFavourite(it) { value -> state.value = value } }
        onDispose { watch?.close() }
    }
    return state.value
}

/** The rating of [target] as this device knows it (0 unrated), for Now Playing; null while unknown. */
@Composable
public fun rememberWatchedRating(session: LibrarySession, target: AndroidLibraryEntity?): Int? {
    val state = remember(session, target) { mutableStateOf<Int?>(null) }
    DisposableEffect(session, target) {
        val watch = target?.let { session.watchRating(it) { value -> state.value = value } }
        onDispose { watch?.close() }
    }
    return state.value
}

/** What a heart on this row changes: an album, an artist or a track; null for anything else. */
public fun AndroidLibraryItem.favouriteTarget(): AndroidLibraryEntity? = when (this) {
    is AndroidLibraryItem.Album -> AndroidLibraryEntity(AndroidLibraryEntityKind.Album, rawId)
    is AndroidLibraryItem.Artist -> AndroidLibraryEntity(AndroidLibraryEntityKind.Artist, rawId)
    is AndroidLibraryItem.Track -> AndroidLibraryEntity(AndroidLibraryEntityKind.Track, rawId)
    else -> null
}

/** Whether this row is shown as a favourite: the core's value, with any pending change already in it. */
public fun AndroidLibraryItem.isFavourite(): Boolean = when (this) {
    is AndroidLibraryItem.Album -> favourite == true
    is AndroidLibraryItem.Artist -> favourite == true
    is AndroidLibraryItem.Track -> favourite == true
    else -> false
}

/**
 * The outcome lines (§16.20) about [targets] — the entities a screen shows a heart for — in the
 * order the targets are given, each once: a target's heart and stars each say their own outcome, on
 * one line ([outcomeLines]). They are dismissed when the screen goes, so an outcome is
 * only ever said on a screen that shows its entity.
 */
@Composable
public fun rememberOutcomeLines(session: LibrarySession, targets: List<AndroidLibraryEntity>): List<Pair<AndroidLibraryEntity, String>> {
    val outcomes by session.outcomes.collectAsState()
    val resources = libraryResources()
    val shown = targets.distinct()
    // Every target this screen has shown, dismissed when it goes: a list that changes while the
    // screen stays (a track list arriving) must not dismiss an outcome that is still to be read.
    val seen = remember(session) { mutableSetOf<AndroidLibraryEntity>() }
    seen += shown
    DisposableEffect(session) { onDispose { seen.forEach(session::dismissOutcome) } }
    return shown.mapNotNull { target -> resources.outcomeLines(outcomes, target)?.let { target to it } }
}
